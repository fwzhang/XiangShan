package xiangshan.backend.rename

import chisel3._
import chisel3.util._
import xiangshan._
import xiangshan.backend.brq.BrqPtr

class RatReadPort extends XSBundle {
  val addr = Input(UInt(5.W))
  val rdata = Output(UInt(XLEN.W))
}

class RatWritePort extends XSBundle {
  val wen = Input(Bool())
  val addr = Input(UInt(5.W))
  val wdata = Input(UInt(XLEN.W))
}

class RenameTable(float: Boolean) extends XSModule {
  val io = IO(new Bundle() {
    val flush = Input(Bool())
    val redirect = Flipped(ValidIO(new Redirect))
    val cpReqs = Vec(RenameWidth, Flipped(ValidIO(new BrqPtr)))
    val readPorts = Vec({if(float) 4 else 3} * RenameWidth, new RatReadPort)
    val specWritePorts = Vec(RenameWidth, new RatWritePort)
    val archWritePorts = Vec(CommitWidth, new RatWritePort)
  })

  // speculative rename table
  val spec_table = RegInit(VecInit(Seq.tabulate(32)(i => i.U(PhyRegIdxWidth.W))))

  // current state of spec table
  val curr_table = Array.fill(RenameWidth)(Wire(Vec(32, UInt(PhyRegIdxWidth.W))))

  // arch state rename table
  val arch_table = RegInit(VecInit(Seq.tabulate(32)(i => i.U(PhyRegIdxWidth.W))))

  // full checkpoints
  val checkPoints = Reg(Vec(BrqSize, Vec(32, UInt(PhyRegIdxWidth.W))))

  for(((cp, w), i) <- io.cpReqs.zip(io.specWritePorts).zipWithIndex){
    if(i == 0){
      curr_table(i) := spec_table
    } else  {
      curr_table(i) := curr_table(i-1)
    }
    when(w.wen){
      curr_table(i)(w.addr) := w.wdata
    }
    when(cp.valid){
      checkPoints(cp.bits.value) := curr_table(i)
    }
  }

  for((r, i) <- io.readPorts.zipWithIndex){
    r.rdata := spec_table(r.addr)
    for(w <- io.specWritePorts.take(i/{if(float) 4 else 3})){ // bypass
      when(w.wen && (w.addr === r.addr)){ r.rdata := w.wdata }
    }
  }

  val arch_table_next = WireInit(arch_table)
  for(w <- io.archWritePorts){
    when(w.wen){ arch_table_next(w.addr) := w.wdata }
  }
  arch_table := arch_table_next

  val recovery_table = Mux(io.redirect.bits.isException || io.redirect.bits.isFlushPipe,
    arch_table_next,
    checkPoints(io.redirect.bits.brTag.value)
  )

  spec_table := Mux(io.redirect.valid && !io.redirect.bits.isReplay,
    recovery_table,
    curr_table.last
  )

//  when(io.flush){
//    spec_table := arch_table
//    for(w <- io.archWritePorts) {
//      when(w.wen){ spec_table(w.addr) := w.wdata }
//    }
//  }

  if (!env.FPGAPlatform) {
    ExcitingUtils.addSource(
      arch_table,
      if(float) "DEBUG_FP_ARCH_RAT" else "DEBUG_INI_ARCH_RAT",
      ExcitingUtils.Debug
    )
  }
}