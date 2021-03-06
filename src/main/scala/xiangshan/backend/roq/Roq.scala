package xiangshan.backend.roq

import chisel3.ExcitingUtils._
import chisel3._
import chisel3.util._
import xiangshan._
import utils._
import xiangshan.backend.LSUOpType
import xiangshan.backend.fu.fpu.Fflags
object roqDebugId extends Function0[Integer] {
  var x = 0
  def apply(): Integer = {
    x = x + 1
    return x
  }
}

class RoqPtr extends CircularQueuePtr(RoqPtr.RoqSize) with HasCircularQueuePtrHelper {
  def needFlush(redirect: Valid[Redirect]): Bool = {
    redirect.valid && (redirect.bits.isException || redirect.bits.isFlushPipe || isAfter(this, redirect.bits.roqIdx))
  }
}

object RoqPtr extends HasXSParameter {
  def apply(f: Bool, v: UInt): RoqPtr = {
    val ptr = Wire(new RoqPtr)
    ptr.flag := f
    ptr.value := v
    ptr
  }
}

class RoqCSRIO extends XSBundle {
  val intrBitSet = Input(Bool())
  val trapTarget = Input(UInt(VAddrBits.W))

  val fflags = Output(new Fflags)
  val dirty_fs = Output(Bool())
}

class Roq(numWbPorts: Int) extends XSModule with HasCircularQueuePtrHelper {
  val io = IO(new Bundle() {
    val brqRedirect = Input(Valid(new Redirect))
    val memRedirect = Input(Valid(new Redirect))
    val enq = new Bundle {
      val canAccept = Output(Bool())
      val isEmpty = Output(Bool())
      val extraWalk = Vec(RenameWidth, Input(Bool()))
      val req = Vec(RenameWidth, Flipped(ValidIO(new MicroOp)))
      val resp = Vec(RenameWidth, Output(new RoqPtr))
    }
    val redirect = Output(Valid(new Redirect))
    val exception = Output(new MicroOp)
    // exu + brq
    val exeWbResults = Vec(numWbPorts, Flipped(ValidIO(new ExuOutput)))
    val commits = new RoqCommitIO
    val bcommit = Output(UInt(BrTagWidth.W))
    val roqDeqPtr = Output(new RoqPtr)
    val csr = new RoqCSRIO
  })

  // instvalid field
  val valid = RegInit(VecInit(List.fill(RoqSize)(false.B)))

  // status
  val writebacked = Reg(Vec(RoqSize, Bool()))

  // data for redirect, exception, etc.
  val flagBkup = RegInit(VecInit(List.fill(RoqSize)(false.B)))
  val exuFflags = Mem(RoqSize, new Fflags)

  // uop field used when commit
  // flushPipe (wb) (commit) (used in roq)
  // lidx (wb) (commit)
  // sidx (wb) (commit)
  // uop.ctrl.commitType (wb) (commit) (L/S)
  // exceptionVec (wb) (commit)
  // roqIdx (dispatch) (commit)
  // crossPageIPFFix (dispatch) (commit)

  // uop field used when walk
  // ctrl.fpWen (dispatch) (walk)
  // ctrl.rfWen (dispatch) (walk)
  // ldest (dispatch) (walk)

  // data for debug
  val microOp = Mem(RoqSize, new MicroOp)
  val debug_exuData = Reg(Vec(RoqSize, UInt(XLEN.W)))//for debug
  val debug_exuDebug = Reg(Vec(RoqSize, new DebugBundle))//for debug

  // ptr
  val enqPtrExt = RegInit(0.U.asTypeOf(new RoqPtr))
  val deqPtrExt = RegInit(0.U.asTypeOf(new RoqPtr))
  val walkPtrExt = Reg(new RoqPtr)
  val walkTgtExt = Reg(new RoqPtr)
  val enqPtr = enqPtrExt.value
  val deqPtr = deqPtrExt.value
  val walkPtr = walkPtrExt.value
  val isEmpty = enqPtr === deqPtr && enqPtrExt.flag ===deqPtrExt.flag
  val isFull = enqPtr === deqPtr && enqPtrExt.flag =/= deqPtrExt.flag
  val notFull = !isFull

  val emptyEntries = RoqSize.U - distanceBetween(enqPtrExt, deqPtrExt)

  val s_idle :: s_walk :: s_extrawalk :: Nil = Enum(3)
  val state = RegInit(s_idle)

  io.roqDeqPtr := deqPtrExt

  // common signal
  val enqPtrValPlus = Wire(Vec(RenameWidth, UInt(log2Up(RoqSize).W)))
  val enqPtrFlagPlus = Wire(Vec(RenameWidth, Bool()))
  for (i <- 0 until RenameWidth) {
    val offset = PopCount(io.enq.req.map(_.valid).take(i))
    val roqIdxExt = enqPtrExt + offset
    enqPtrValPlus(i) := roqIdxExt.value
    enqPtrFlagPlus(i) := roqIdxExt.flag
  }

  val deqPtrExtPlus = Wire(Vec(RenameWidth, UInt(log2Up(RoqSize).W)))
  for(i <- 0 until CommitWidth){
    val roqIdxExt = deqPtrExt + i.U
    deqPtrExtPlus(i) := roqIdxExt.value
  }

  // Dispatch
  val hasBlockBackward = RegInit(false.B)
  val hasNoSpecExec = RegInit(false.B)
  // When blockBackward instruction leaves Roq (commit or walk), hasBlockBackward should be set to false.B
  val blockBackwardLeave = Cat((0 until CommitWidth).map(i => io.commits.valid(i) && io.commits.uop(i).ctrl.blockBackward)).orR
  when(blockBackwardLeave || io.redirect.valid) { hasBlockBackward:= false.B }
  // When noSpecExec instruction commits (it should not be walked except when it has not entered Roq),
  // hasNoSpecExec should be set to false.B
  val noSpecExecCommit = !io.commits.isWalk && Cat((0 until CommitWidth).map(i => io.commits.valid(i) && io.commits.uop(i).ctrl.noSpecExec)).orR
  when(noSpecExecCommit || io.redirect.valid) { hasNoSpecExec:= false.B }
  // Assertion on that noSpecExec should never be walked since it's the only instruction in Roq.
  // Extra walk should be ok since noSpecExec has not enter Roq.
  val walkNoSpecExec = io.commits.isWalk && Cat((0 until CommitWidth).map(i => io.commits.valid(i) && io.commits.uop(i).ctrl.noSpecExec)).orR
  XSError(state =/= s_extrawalk && walkNoSpecExec, "noSpecExec should not walk\n")

  val validDispatch = io.enq.req.map(_.valid)
  for (i <- 0 until RenameWidth) {
    val offset = PopCount(validDispatch.take(i))
    val roqIdxExt = enqPtrExt + offset
    val roqIdx = roqIdxExt.value

    when(io.enq.req(i).valid) {
      microOp(roqIdx) := io.enq.req(i).bits
      when(io.enq.req(i).bits.ctrl.blockBackward) {
        hasBlockBackward := true.B
      }
      when(io.enq.req(i).bits.ctrl.noSpecExec) {
        hasNoSpecExec := true.B
      }
    }
    io.enq.resp(i) := roqIdxExt
  }

  val validEntries = distanceBetween(enqPtrExt, deqPtrExt)
  val firedDispatch = Cat(io.enq.req.map(_.valid))
  io.enq.canAccept := (validEntries <= (RoqSize - RenameWidth).U) && !hasBlockBackward
  io.enq.isEmpty   := isEmpty
  XSDebug(p"(ready, valid): ${io.enq.canAccept}, ${Binary(firedDispatch)}\n")

  val dispatchCnt = PopCount(firedDispatch)
  enqPtrExt := enqPtrExt + dispatchCnt
  when (firedDispatch.orR) {
    XSInfo("dispatched %d insts\n", dispatchCnt)
  }

  // Writeback
  val firedWriteback = io.exeWbResults.map(_.fire())
  XSInfo(PopCount(firedWriteback) > 0.U, "writebacked %d insts\n", PopCount(firedWriteback))
  for(i <- 0 until numWbPorts) {
    when(io.exeWbResults(i).fire()){
      val wbIdxExt = io.exeWbResults(i).bits.uop.roqIdx
      val wbIdx = wbIdxExt.value
      microOp(wbIdx).cf.exceptionVec := io.exeWbResults(i).bits.uop.cf.exceptionVec
      microOp(wbIdx).lqIdx := io.exeWbResults(i).bits.uop.lqIdx
      microOp(wbIdx).sqIdx := io.exeWbResults(i).bits.uop.sqIdx
      microOp(wbIdx).ctrl.flushPipe := io.exeWbResults(i).bits.uop.ctrl.flushPipe
      microOp(wbIdx).diffTestDebugLrScValid := io.exeWbResults(i).bits.uop.diffTestDebugLrScValid
      debug_exuData(wbIdx) := io.exeWbResults(i).bits.data
      debug_exuDebug(wbIdx) := io.exeWbResults(i).bits.debug

      val debug_Uop = microOp(wbIdx)
      XSInfo(true.B,
        p"writebacked pc 0x${Hexadecimal(debug_Uop.cf.pc)} wen ${debug_Uop.ctrl.rfWen} " +
        p"data 0x${Hexadecimal(io.exeWbResults(i).bits.data)} ldst ${debug_Uop.ctrl.ldest} pdst ${debug_Uop.pdest} " +
        p"skip ${io.exeWbResults(i).bits.debug.isMMIO} roqIdx: ${wbIdxExt}\n"
      )
    }
  }

  // Interrupt
  val deqUop = microOp(deqPtr)
  val deqPtrWritebacked = writebacked(deqPtr) && valid(deqPtr)
  val intrEnable = io.csr.intrBitSet && !isEmpty && !hasNoSpecExec &&
    deqUop.ctrl.commitType =/= CommitType.STORE && deqUop.ctrl.commitType =/= CommitType.LOAD// TODO: wanna check why has hasCsr(hasNoSpec)
  val exceptionEnable = deqPtrWritebacked && Cat(deqUop.cf.exceptionVec).orR()
  val isFlushPipe = deqPtrWritebacked && deqUop.ctrl.flushPipe
  io.redirect := DontCare
  io.redirect.valid := (state === s_idle) && (intrEnable || exceptionEnable || isFlushPipe)// TODO: add fence flush to flush the whole pipe
  io.redirect.bits.isException := intrEnable || exceptionEnable
  // reuse isFlushPipe to represent interrupt for CSR
  io.redirect.bits.isFlushPipe := isFlushPipe || intrEnable
  io.redirect.bits.target := Mux(isFlushPipe, deqUop.cf.pc + 4.U, io.csr.trapTarget)
  io.exception := deqUop
  XSDebug(io.redirect.valid,
    "generate redirect: pc 0x%x intr %d excp %d flushpp %d target:0x%x Traptarget 0x%x exceptionVec %b\n",
    io.exception.cf.pc, intrEnable, exceptionEnable, isFlushPipe, io.redirect.bits.target, io.csr.trapTarget,
    Cat(microOp(deqPtr).cf.exceptionVec))

  // Commit uop to Rename (walk)
  val walkCounter = Reg(UInt(log2Up(RoqSize).W))
  val shouldWalkVec = Wire(Vec(CommitWidth, Bool()))
  val walkPtrVec = Wire(Vec(CommitWidth, new RoqPtr))
  for(i <- shouldWalkVec.indices){
    walkPtrVec(i) := walkPtrExt - i.U
    shouldWalkVec(i) := i.U < walkCounter
  }
  val walkFinished = walkCounter <= CommitWidth.U //&& // walk finish in this cycle
    //!io.brqRedirect.valid // no new redirect comes and update walkptr

  // extra space is used weh roq has no enough space, but mispredict recovery needs such info to walk regmap
  val needExtraSpaceForMPR = WireInit(VecInit(
    List.tabulate(RenameWidth)(i => io.brqRedirect.valid && io.enq.extraWalk(i))
  ))
  val extraSpaceForMPR = Reg(Vec(RenameWidth, new MicroOp))
  val usedSpaceForMPR = Reg(Vec(RenameWidth, Bool()))

  val storeCommitVec = WireInit(VecInit(Seq.fill(CommitWidth)(false.B)))
  val cfiCommitVec = WireInit(VecInit(Seq.fill(CommitWidth)(false.B)))
  // wiring to csr
  val fflags = WireInit(0.U.asTypeOf(new Fflags))
  val dirty_fs = WireInit(false.B)

  io.commits.isWalk := state =/= s_idle
  for (i <- 0 until CommitWidth) {
    io.commits.valid(i) := false.B
    io.commits.uop(i)   := DontCare

    switch(state){
      is(s_idle){
        val commitIdx = deqPtr + i.U
        val commitUop = microOp(commitIdx)
        val hasException = Cat(commitUop.cf.exceptionVec).orR() || intrEnable
        val canCommit = if(i!=0) (io.commits.valid(i-1) && !io.commits.uop(i-1).ctrl.flushPipe) else true.B
        val v = valid(commitIdx)
        val w = writebacked(commitIdx)
        io.commits.valid(i) := v && w && canCommit && !hasException
        io.commits.uop(i) := commitUop

        storeCommitVec(i) := io.commits.valid(i) &&
          commitUop.ctrl.commitType === CommitType.STORE

        cfiCommitVec(i) := io.commits.valid(i) &&
          !commitUop.cf.brUpdate.pd.notCFI

        val commitFflags = exuFflags(commitIdx)
        when(io.commits.valid(i)){
          when(commitFflags.asUInt.orR()){
            // update fflags
            fflags := exuFflags(commitIdx)
          }
          when(commitUop.ctrl.fpWen){
            // set fs to dirty
            dirty_fs := true.B
          }
        }

        XSInfo(io.commits.valid(i),
          "retired pc %x wen %d ldest %d pdest %x old_pdest %x data %x fflags: %b\n",
          commitUop.cf.pc,
          commitUop.ctrl.rfWen,
          commitUop.ctrl.ldest,
          commitUop.pdest,
          commitUop.old_pdest,
          debug_exuData(commitIdx),
          exuFflags(commitIdx).asUInt
        )
        XSInfo(io.commits.valid(i) && debug_exuDebug(commitIdx).isMMIO,
          "difftest skiped pc0x%x\n",
          commitUop.cf.pc
        )
      }

      is(s_walk){
        val idx = walkPtrVec(i).value
        val v = valid(idx)
        val walkUop = microOp(idx)
        io.commits.valid(i) := v && shouldWalkVec(i)
        io.commits.uop(i) := walkUop
        when(shouldWalkVec(i)){
          v := false.B
        }
        XSInfo(io.commits.valid(i) && shouldWalkVec(i), "walked pc %x wen %d ldst %d data %x\n",
          walkUop.cf.pc,
          walkUop.ctrl.rfWen,
          walkUop.ctrl.ldest,
          debug_exuData(idx)
        )
      }

      is(s_extrawalk){
        val idx = RenameWidth-i-1
        val walkUop = extraSpaceForMPR(idx)
        io.commits.valid(i) := usedSpaceForMPR(idx)
        io.commits.uop(i) := walkUop
        state := s_walk
        XSInfo(io.commits.valid(i), "use extra space walked pc %x wen %d ldst %d\n",
          walkUop.cf.pc,
          walkUop.ctrl.rfWen,
          walkUop.ctrl.ldest
        )
      }
    }
  }

  io.csr.fflags := fflags
  io.csr.dirty_fs := dirty_fs

  val validCommit = io.commits.valid
  val commitCnt = PopCount(validCommit)
  when(state===s_walk) {
    //exit walk state when all roq entry is commited
    when(walkFinished) {
      state := s_idle
    }
    walkPtrExt := walkPtrExt - CommitWidth.U
    walkCounter := walkCounter - commitCnt
    XSInfo("rolling back: enqPtr %d deqPtr %d walk %d:%d walkcnt %d\n", enqPtr, deqPtr, walkPtrExt.flag, walkPtr, walkCounter)
  }

  // move tail ptr
  when(state === s_idle){
    deqPtrExt := deqPtrExt + commitCnt
  }
  val retireCounter = Mux(state === s_idle, commitCnt, 0.U)
  XSInfo(retireCounter > 0.U, "retired %d insts\n", retireCounter)

  // commit branch to brq
  io.bcommit := PopCount(cfiCommitVec)

  // when redirect, walk back roq entries
  when(io.brqRedirect.valid){ // TODO: need check if consider exception redirect?
    state := s_walk
    val nextEnqPtr = (enqPtrExt - 1.U) + dispatchCnt
    walkPtrExt := Mux(state === s_walk,
      walkPtrExt - Mux(walkFinished, walkCounter, CommitWidth.U),
      Mux(state === s_extrawalk, walkPtrExt, nextEnqPtr))
    // walkTgtExt := io.brqRedirect.bits.roqIdx
    val currentWalkPtr = Mux(state === s_walk || state === s_extrawalk, walkPtrExt, nextEnqPtr)
    walkCounter := distanceBetween(currentWalkPtr, io.brqRedirect.bits.roqIdx) - Mux(state === s_walk, commitCnt, 0.U)
    enqPtrExt := io.brqRedirect.bits.roqIdx + 1.U
  }

  // no enough space for walk, allocate extra space
  when(needExtraSpaceForMPR.asUInt.orR && io.brqRedirect.valid){
    usedSpaceForMPR := needExtraSpaceForMPR
    (0 until RenameWidth).foreach(i => extraSpaceForMPR(i) := io.enq.req(i).bits)
    state := s_extrawalk
    XSDebug("roq full, switched to s_extrawalk. needExtraSpaceForMPR: %b\n", needExtraSpaceForMPR.asUInt)
  }

  // when exception occurs, cancels all
  when (io.redirect.valid) { // TODO: need check for flushPipe
    state := s_idle
    enqPtrExt := 0.U.asTypeOf(new RoqPtr)
    deqPtrExt := 0.U.asTypeOf(new RoqPtr)
  }

  // instvalid field

  // write
  // enqueue logic writes 6 valid
  for (i <- 0 until RenameWidth) {
    when(io.enq.req(i).fire()){
      valid(enqPtrValPlus(i)) := true.B
    }
  }
  // dequeue/walk logic writes 6 valid, dequeue and walk will not happen at the same time
  for(i <- 0 until CommitWidth){
    switch(state){
      is(s_idle){
        when(io.commits.valid(i)){valid(deqPtrExtPlus(i)) := false.B}
      }
      is(s_walk){
        val idx = walkPtrVec(i).value
        when(shouldWalkVec(i)){
          valid(idx) := false.B
        }
      }
    }
  }

  // read
  // enqueue logic reads 6 valid
  // dequeue/walk logic reads 6 valid, dequeue and walk will not happen at the same time
  // rollback reads all valid? is it necessary?

  // reset
  // when exception, reset all valid to false
  when (io.redirect.valid) {
    for (i <- 0 until RoqSize) {
      valid(i) := false.B
    }
  }

  // status field: writebacked

  // write
  // enqueue logic set 6 writebacked to false
  for (i <- 0 until RenameWidth) {
    when(io.enq.req(i).fire()){
      writebacked(enqPtrValPlus(i)) := false.B
    }
  }
  // writeback logic set numWbPorts writebacked to true
  for(i <- 0 until numWbPorts) {
    when(io.exeWbResults(i).fire()){
      val wbIdxExt = io.exeWbResults(i).bits.uop.roqIdx
      val wbIdx = wbIdxExt.value
      writebacked(wbIdx) := true.B
    }
  }
  // rollback: write all
  // when rollback, reset writebacked entry to valid
  // when(io.memRedirect.valid) { // TODO: opt timing
  //   for (i <- 0 until RoqSize) {
  //     val recRoqIdx = RoqPtr(flagBkup(i), i.U)
  //     when (valid(i) && isAfter(recRoqIdx, io.memRedirect.bits.roqIdx)) {
  //       writebacked(i) := false.B
  //     }
  //   }
  // }

  // read
  // deqPtrWritebacked
  // gen io.commits(i).valid read 6 (CommitWidth)

  // flagBkup
  // write: update when enqueue
  // enqueue logic set 6 flagBkup at most
  for (i <- 0 until RenameWidth) {
    when(io.enq.req(i).fire()){
      flagBkup(enqPtrValPlus(i)) := enqPtrFlagPlus(i)
    }
  }
  // read: used in rollback logic
  // all flagBkup will be used

  // exuFflags
  // write: writeback logic set numWbPorts exuFflags
  for(i <- 0 until numWbPorts) {
    when(io.exeWbResults(i).fire()){
      val wbIdxExt = io.exeWbResults(i).bits.uop.roqIdx
      val wbIdx = wbIdxExt.value
      exuFflags(wbIdx) := io.exeWbResults(i).bits.fflags
    }
  }
  // read: used in commit logic
  // read CommitWidth exuFflags

  // debug info
  XSDebug(p"enqPtr ${enqPtrExt} deqPtr ${deqPtrExt}\n")
  XSDebug("")
  for(i <- 0 until RoqSize){
    XSDebug(false, !valid(i), "-")
    XSDebug(false, valid(i) && writebacked(i), "w")
    XSDebug(false, valid(i) && !writebacked(i), "v")
  }
  XSDebug(false, true.B, "\n")

  for(i <- 0 until RoqSize) {
    if(i % 4 == 0) XSDebug("")
    XSDebug(false, true.B, "%x ", microOp(i).cf.pc)
    XSDebug(false, !valid(i), "- ")
    XSDebug(false, valid(i) && writebacked(i), "w ")
    XSDebug(false, valid(i) && !writebacked(i), "v ")
    if(i % 4 == 3) XSDebug(false, true.B, "\n")
  }

  val id = roqDebugId()
  val difftestIntrNO = WireInit(0.U(XLEN.W))
  val difftestCause = WireInit(0.U(XLEN.W))
  ExcitingUtils.addSink(difftestIntrNO, s"difftestIntrNOfromCSR$id")
  ExcitingUtils.addSink(difftestCause, s"difftestCausefromCSR$id")

  if(!env.FPGAPlatform) {

    //difftest signals
    val firstValidCommit = deqPtr + PriorityMux(validCommit, VecInit(List.tabulate(CommitWidth)(_.U)))

    val skip = Wire(Vec(CommitWidth, Bool()))
    val wen = Wire(Vec(CommitWidth, Bool()))
    val wdata = Wire(Vec(CommitWidth, UInt(XLEN.W)))
    val wdst = Wire(Vec(CommitWidth, UInt(32.W)))
    val diffTestDebugLrScValid = Wire(Vec(CommitWidth, Bool()))
    val wpc = Wire(Vec(CommitWidth, UInt(XLEN.W)))
    val trapVec = Wire(Vec(CommitWidth, Bool()))
    val isRVC = Wire(Vec(CommitWidth, Bool()))
    for(i <- 0 until CommitWidth){
      // io.commits(i).valid
      val idx = deqPtr+i.U
      val uop = io.commits.uop(i)
      val DifftestSkipSC = false
      if(!DifftestSkipSC){
        skip(i) := debug_exuDebug(idx).isMMIO && io.commits.valid(i)
      }else{
        skip(i) := (
            debug_exuDebug(idx).isMMIO ||
            uop.ctrl.fuType === FuType.mou && uop.ctrl.fuOpType === LSUOpType.sc_d ||
            uop.ctrl.fuType === FuType.mou && uop.ctrl.fuOpType === LSUOpType.sc_w
          ) && io.commits.valid(i)
      }
      wen(i) := io.commits.valid(i) && uop.ctrl.rfWen && uop.ctrl.ldest =/= 0.U
      wdata(i) := debug_exuData(idx)
      wdst(i) := uop.ctrl.ldest
      diffTestDebugLrScValid(i) := uop.diffTestDebugLrScValid
      wpc(i) := SignExt(uop.cf.pc, XLEN)
      trapVec(i) := io.commits.valid(i) && (state===s_idle) && uop.ctrl.isXSTrap
      isRVC(i) := uop.cf.brUpdate.pd.isRVC
    }

    val scFailed = !diffTestDebugLrScValid(0) &&
      io.commits.uop(0).ctrl.fuType === FuType.mou &&
      (io.commits.uop(0).ctrl.fuOpType === LSUOpType.sc_d || io.commits.uop(0).ctrl.fuOpType === LSUOpType.sc_w)

    val instrCnt = RegInit(0.U(64.W))
    instrCnt := instrCnt + retireCounter

    XSDebug(difftestIntrNO =/= 0.U, "difftest intrNO set %x\n", difftestIntrNO)
    val retireCounterFix = Mux(io.redirect.valid, 1.U, retireCounter)
    val retirePCFix = SignExt(Mux(io.redirect.valid, microOp(deqPtr).cf.pc, microOp(firstValidCommit).cf.pc), XLEN)
    val retireInstFix = Mux(io.redirect.valid, microOp(deqPtr).cf.instr, microOp(firstValidCommit).cf.instr)

    ExcitingUtils.addSource(RegNext(retireCounterFix), "difftestCommit", ExcitingUtils.Debug)
    ExcitingUtils.addSource(RegNext(retirePCFix), "difftestThisPC", ExcitingUtils.Debug)//first valid PC
    ExcitingUtils.addSource(RegNext(retireInstFix), "difftestThisINST", ExcitingUtils.Debug)//first valid inst
    ExcitingUtils.addSource(RegNext(skip.asUInt), "difftestSkip", ExcitingUtils.Debug)
    ExcitingUtils.addSource(RegNext(isRVC.asUInt), "difftestIsRVC", ExcitingUtils.Debug)
    ExcitingUtils.addSource(RegNext(wen.asUInt), "difftestWen", ExcitingUtils.Debug)
    ExcitingUtils.addSource(RegNext(wpc), "difftestWpc", ExcitingUtils.Debug)
    ExcitingUtils.addSource(RegNext(wdata), "difftestWdata", ExcitingUtils.Debug)
    ExcitingUtils.addSource(RegNext(wdst), "difftestWdst", ExcitingUtils.Debug)
    ExcitingUtils.addSource(RegNext(scFailed), "difftestScFailed", ExcitingUtils.Debug)
    ExcitingUtils.addSource(RegNext(difftestIntrNO), "difftestIntrNO", ExcitingUtils.Debug)
    ExcitingUtils.addSource(RegNext(difftestCause), "difftestCause", ExcitingUtils.Debug)

    val hitTrap = trapVec.reduce(_||_)
    val trapCode = PriorityMux(wdata.zip(trapVec).map(x => x._2 -> x._1))
    val trapPC = SignExt(PriorityMux(wpc.zip(trapVec).map(x => x._2 ->x._1)), XLEN)

    ExcitingUtils.addSource(RegNext(hitTrap), "trapValid")
    ExcitingUtils.addSource(RegNext(trapCode), "trapCode")
    ExcitingUtils.addSource(RegNext(trapPC), "trapPC")
    ExcitingUtils.addSource(RegNext(GTimer()), "trapCycleCnt")
    ExcitingUtils.addSource(RegNext(instrCnt), "trapInstrCnt")
    ExcitingUtils.addSource(state === s_walk || state === s_extrawalk, "perfCntCondRoqWalk", Perf)
    val deqNotWritebacked = valid(deqPtr) && !writebacked(deqPtr)
    val deqUopCommitType = deqUop.ctrl.commitType
    ExcitingUtils.addSource(deqNotWritebacked && deqUopCommitType === CommitType.INT,   "perfCntCondRoqWaitInt",   Perf)
    ExcitingUtils.addSource(deqNotWritebacked && deqUopCommitType === CommitType.FP,    "perfCntCondRoqWaitFp",    Perf)
    ExcitingUtils.addSource(deqNotWritebacked && deqUopCommitType === CommitType.LOAD,  "perfCntCondRoqWaitLoad",  Perf)
    ExcitingUtils.addSource(deqNotWritebacked && deqUopCommitType === CommitType.STORE, "perfCntCondRoqWaitStore", Perf)

    if(EnableBPU){
      ExcitingUtils.addSource(hitTrap, "XSTRAP", ConnectionType.Debug)
    }
  }
}
