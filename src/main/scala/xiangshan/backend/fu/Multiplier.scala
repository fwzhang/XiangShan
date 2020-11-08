package xiangshan.backend.fu

import chisel3._
import chisel3.util._
import xiangshan._
import utils._
import xiangshan.backend._
import xiangshan.backend.fu.FunctionUnit._

class MulDivCtrl extends Bundle{
  val sign = Bool()
  val isW = Bool()
  val isHi = Bool() // return hi bits of result ?
}

class MulDivOutput extends XSBundle {
  val data = UInt(XLEN.W)
  val uop = new MicroOp
}

class MulDivIO(val len: Int) extends XSBundle {
  val in = Flipped(DecoupledIO(new Bundle() {
    val src1, src2 = UInt(len.W)
    val ctrl = new MulDivCtrl
  }))
  val out = DecoupledIO(new MulDivOutput)
  val redirect = Flipped(ValidIO(new Redirect))
}

abstract class Multiplier
(
  val len: Int,
  latency: Int = 3
) extends FunctionUnit(cfg = mulCfg, len, extIn = new MulDivCtrl, latency = latency)
  with HasPipelineReg[MulDivCtrl, Null]
{

  val (src1, src2) = (io.in.bits.src(0), io.in.bits.src(1))
}

class ArrayMultiplier
(
  len: Int,
  latency: Int = 3,
  realArray: Boolean = false
) extends Multiplier(len, latency) {

  val mulRes = src1.asSInt() * src2.asSInt()

  var dataVec = Seq(mulRes.asUInt())
  var ctrlVec = Seq(io.in.bits.ext.get)

  for(i <- 1 to latency){
    dataVec = dataVec :+ PipelineReg(i)(dataVec(i-1))
    ctrlVec = ctrlVec :+ PipelineReg(i)(ctrlVec(i-1))
  }

  val xlen = io.out.bits.data.getWidth
  val res = Mux(ctrlVec.last.isHi, dataVec.last(2*xlen-1, xlen), dataVec.last(xlen-1,0))
  io.out.bits.data := Mux(ctrlVec.last.isW, SignExt(res(31,0),xlen), res)

  XSDebug(p"validVec:${Binary(Cat(validVec))} flushVec:${Binary(Cat(flushVec))}\n")(this.name)

  //  printf(p"t=${GTimer()} in: v${io.in.valid} r:${io.in.ready}\n")
  //  printf(p"t=${GTimer()} out: v:${io.out.valid} r:${io.out.ready} vec:${Binary(Cat(validVec))}\n")
}