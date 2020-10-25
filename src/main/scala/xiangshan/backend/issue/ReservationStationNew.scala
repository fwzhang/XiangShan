package xiangshan.backend.issue

import chisel3._
import chisel3.util._
import xiangshan._
import utils._
import xiangshan.backend.exu.{Exu, ExuConfig}
import java.rmi.registry.Registry
import java.{util => ju}

class SrcBundle extends XSBundle {
  val src = UInt(PhyRegIdxWidth.W)
  val state = SrcState()
  val srctype = SrcType()
  
  def hit(uop: MicroOp) : Bool = {
    (src === uop.pdest) && (state === SrcState.busy) &&
    ((srctype === SrcType.reg && uop.ctrl.rfWen && src=/=0.U) ||
     (srctype === SrcType.fp  && uop.ctrl.fpWen)) // TODO: check if zero map to zero when rename
  }
}

object SrcBundle {
  def apply(src: UInt, state: UInt/*SrcState*/, srctype: UInt/*SrcType*/): SrcBundle = {
    val b = Wire(new SrcBundle)
    b.src := src
    b.state := state
    b.srctype := srctype
    b
  }
}

class BypassQueue(number: Int) extends XSModule {
  val io = IO(new Bundle {
    val in  = Flipped(ValidIO(new MicroOp))
    val out = ValidIO(new MicroOp)
  })
  require(number >= 1)
  if(number == 1) {  io.in <> io.out }
  else {
    val queue = Seq.fill(number-1)(RegInit(0.U.asTypeOf(new Bundle{
      val valid = Bool()
      val bits = new MicroOp
    })))
    queue(0).valid := io.in.valid
    queue(0).bits  := io.in.bits
    (0 until (number-2)).map{i => queue(i+1) := queue(i) }
    io.out.valid := queue(number-1).valid
    io.out.bits := queue(number-1).bits
    // TODO: change to ptr
    // val ptr = RegInit(0.U(log2Up(number-1).W))
    // val ptrNext = ptr + 1.U
    // ptr := Mux(ptrNext === number.U, 0.U, ptrNext)
  }
}

class ReservationStationNew
(
  val exuCfg: ExuConfig,
  wakeupCnt: Int,
  extraListenPortsCnt: Int,
  srcNum: Int = 3
) extends XSModule {


  val iqSize = IssQueSize
  val iqIdxWidth = log2Up(iqSize)

  val io = IO(new XSBundle {
    // flush Issue Queue
    val redirect = Flipped(ValidIO(new Redirect))

    // enq Ctrl sigs at dispatch-2
    val enqCtrl = Flipped(DecoupledIO(new MicroOp))
    // enq Data at next cycle (regfile has 1 cycle latency)
    val enqData = Input(new ExuInput)

    // broadcast selected uop to other issue queues
    val selectedUop = ValidIO(new MicroOp)

    // send to exu
    val deq = DecoupledIO(new ExuInput)

    // recv broadcasted uops form any relative issue queue,
    // to simplify wake up logic, the uop broadcasted by this queue self
    // are also in 'boradcastedUops'
    val broadcastedUops = Vec(wakeupCnt, Flipped(ValidIO(new MicroOp)))

    // listen to write back data bus
    val writeBackedData = Vec(wakeupCnt, Input(UInt(XLEN.W)))

    // for some function units with uncertain latency,
    // we have to wake up relative uops until those function units write back
    val extraListenPorts = Vec(extraListenPortsCnt, Flipped(ValidIO(new ExuOutput)))

    // to Dispatch
    val numExist = Output(UInt(iqIdxWidth.W))

    // TODO: support replay for future use if exu is ldu/stu
  })

//  io <> DontCare

  // GOAL: 
  // 1. divide control part and data part
  // 2. store control signal in sending RS and send out when after paticular cycles
  // 3. one RS only have one paticular delay
  // 4. remove the issue stage
  // 5. support replay will cause one or two more latency for state machine change
  //    so would not support replay in current edition.

  // here is three logial part:
  // control part: psrc(5.W)*3 srcState(1.W)*3 fuOpType/Latency(3.W) roqIdx
  // data part: data(64.W)*3
  // other part: lsroqIdx and many other signal in uop. may set them to control part(close to dispatch)

  // control part:
  val validQueue    = RegInit(VecInit(Seq.fill(iqSize)(false.B)))
  val srcQueue      = Reg(Vec(iqSize, Vec(srcNum, new SrcBundle)))
  // val srcQueue      = Seq.fill(iqSize)(Seq.fill(srcNum)(Reg(new SrcBundle)))

  // data part:
  val data          = Reg(Vec(iqSize, Vec(3, UInt(XLEN.W))))

  // other part:
  val uop           = Reg(Vec(iqSize, new MicroOp))

  // rs queue part:
  val tailPtr       = RegInit(0.U((iqIdxWidth+1).W))
  val idxQueue      = RegInit(VecInit((0 until iqSize).map(_.U(iqIdxWidth.W))))
  val readyQueue    = VecInit(srcQueue.map(a => ParallelAND(a.map(_.state === SrcState.rdy)).asBool).
  zip(validQueue).map{ case (a,b) => a&b })

  // real deq
  // TODO: 
  val (firstBubble, findBubble) = PriorityEncoderWithFlag(validQueue.map(!_))
  val (firstReady, findReady) = PriorityEncoderWithFlag(validQueue)
  val deqIdx = Mux(findBubble, firstBubble, findReady)
  val deqValid = ((firstBubble < tailPtr) && findBubble) || ((firstReady < tailPtr) && findReady)
  val moveMask = {
    (Fill(iqSize, 1.U(1.W)) << deqIdx)(iqSize-1, 0)
  } & Fill(iqSize, deqValid)

  for(i <- 0 until iqSize-1){
    when(moveMask(i)){
      idxQueue(i)   := idxQueue(i+1)
      srcQueue(i).zip(srcQueue(i+1)).map{case (a,b) => a := b}
      validQueue(i) := validQueue(i+1)
    }
  }
  when(deqValid){
    idxQueue.last := idxQueue(deqIdx)
    validQueue.last := false.B
  }

  // wakeup and bypass and flush
  // data update and control update
  // bypass update and wakeup update -> wakeup method and bypass method may not be ok
  // for ld/st, still need send to control part, long latency
  def wakeup(src: SrcBundle) : (Bool, UInt) = {
    val hitVec = io.extraListenPorts.map(port => src.hit(port.bits.uop) && port.valid)
    assert(PopCount(hitVec)===0.U || PopCount(hitVec)===1.U)
    
    val hit = ParallelOR(hitVec)
    (hit, ParallelMux(hitVec zip io.extraListenPorts.map(_.bits.data)))
  }

  def bypass(src: SrcBundle) : (Bool, Bool, UInt) = {
    val hitVec = io.broadcastedUops.map(port => src.hit(port.bits) && port.valid)
    assert(PopCount(hitVec)===0.U || PopCount(hitVec)===1.U)

    val hit = ParallelOR(hitVec)
    (hit, RegNext(hit), ParallelMux(hitVec.map(RegNext(_)) zip io.writeBackedData))
  }
  
  for (i <- 0 until iqSize) {
    for (j <- 0 until srcNum) {
      val (wuHit, wuData) = wakeup(srcQueue(i)(j))
      val (bpHit, bpHitReg, bpData) = bypass(srcQueue(i)(j))
      assert(!(bpHit && wuHit))
      assert(!(bpHitReg && wuHit))

      when (wuHit || bpHit) { srcQueue(i)(j).state := SrcState.rdy }
      when (wuHit) { data(idxQueue(i))(j) := wuData }
      when (bpHitReg) { data(RegNext(idxQueue(i)))(j) := bpData }
      // NOTE: can not use Mem/Sram to store data, for multi-read/multi-write
    }
  }

  // select
  val selectedIdxRegOH = Wire(UInt(iqSize.W))
  val selectMask = WireInit(VecInit(
    (0 until iqSize).map(i =>
      readyQueue(i) && !(selectedIdxRegOH(i) && io.deq.fire()) // TODO: read it
    )
  ))
  val (selectedIdxWire, selected) = PriorityEncoderWithFlag(selectMask)
  val selReg = RegNext(selected)
  val selectedIdxReg = RegNext(selectedIdxWire - moveMask(selectedIdxWire))
  selectedIdxRegOH := UIntToOH(selectedIdxReg)

  // bypass send
  // store selected uops and send out one cycle before result back
  val fixedDelay = 1 // TODO: fix it
  val bpQueue = Module(new BypassQueue(fixedDelay))
  bpQueue.io.in.valid := selReg
  bpQueue.io.in.bits := uop(idxQueue(selectedIdxWire))
  // bpQueue.io.in.bits.src1State := SrcState.rdy
  // bpQueue.io.in.bits.src2State := SrcState.rdy
  // bpQueue.io.in.bits.src3State := SrcState.rdy
  io.selectedUop.valid := bpQueue.io.out.valid
  io.selectedUop.bits  := bpQueue.io.out.bits


  // fake deq
  // TODO: add fake deq later
  // TODO: add deq: may have one more latency, but for replay later.
  // TODO: may change to another way to deq and select, for there is no Vec, but Seq, change to multi-in multi-out
  io.deq.valid := readyQueue(selectedIdxReg) && selReg // TODO: read it and add assert for rdyQueue
  io.deq.bits.uop := uop(idxQueue(selectedIdxReg))
  io.deq.bits.src1 := data(selectedIdxReg)(0)
  if(srcNum > 1) { io.deq.bits.src2 := data(selectedIdxReg)(1) }
  if(srcNum > 2) { io.deq.bits.src3 := data(selectedIdxReg)(2) } // TODO: beautify it
  // NOTE: srcState will only use in RS/IQ, so ignore it
  // io.deq.bits.uop.src1State := srcQueue(selectedIdxReg)(0).state
  // if(srcNum > 1) { io.deq.bits.uop.src2State := srcQueue(selectedIdxReg)(1).state }
  // if(srcNum > 2) { io.deq.bits.uop.src3State := srcQueue(selectedIdxReg)(2).state }

  // enq
  val tailAfterRealDeq = tailPtr - moveMask(tailPtr.tail(1))
  val isFull = tailAfterRealDeq.head(1).asBool() // tailPtr===qsize.U
  
  def stateCheck(src: SrcBundle): UInt /*SrcState*/ = {
    Mux( (src.srctype=/=SrcType.reg && src.srctype=/=SrcType.fp) ||               
      (src.srctype===SrcType.reg && src.src===0.U), SrcState.rdy, src.state)
  }

  io.enqCtrl.ready := !isFull && !io.redirect.valid // TODO: check this redirect && need more optimization
  when (io.enqCtrl.fire()) { 
    val enqUop = io.enqCtrl.bits
    uop(idxQueue(tailPtr.tail(1))) := enqUop
    val srcTypeSeq = Seq(enqUop.ctrl.src1Type, enqUop.ctrl.src2Type, enqUop.ctrl.src3Type)
    val srcSeq = Seq(enqUop.psrc1, enqUop.psrc2, enqUop.psrc3)
    val srcStateSeq = Seq(enqUop.src1State, enqUop.src2State, enqUop.src3State)
    for (i <- 0 until srcNum) { // TODO: add enq wakeup / bypass check
      srcQueue(tailPtr.tail(1))(i).srctype := srcTypeSeq(i)
      srcQueue(tailPtr.tail(1))(i).src     := srcSeq(i)
      srcQueue(tailPtr.tail(1))(i).state   := stateCheck(SrcBundle(srcSeq(i), srcStateSeq(i), srcTypeSeq(i)))
    }
  }
  when (RegNext(io.enqCtrl.fire())) {
    val srcDataSeq = Seq(io.enqData.src1, io.enqData.src2, io.enqData.src3)
    // data(RegNext(idxQueue(tailPtr.tail(1)))).zip.srcDataSeq.map{ case(a,b) => a := b }
    val enqIdxNext = RegNext(idxQueue(tailPtr.tail(1)))
    for(i <- data(0).indices) { data(enqIdxNext)(i) := srcDataSeq(i) }
  }

  tailPtr := tailAfterRealDeq + io.enqCtrl.fire()

  // other io
  io.numExist := tailPtr

  // log
  // TODO: add log
}