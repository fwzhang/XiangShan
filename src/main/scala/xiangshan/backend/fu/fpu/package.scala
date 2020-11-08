package xiangshan.backend.fu.fpu

import chisel3._
import chisel3.util._

object FPUOpType {
  def funcWidth = 6
  def FpuOp(fu: String, op: String): UInt = ("b" + fu + op).U(funcWidth.W)

  def FU_FMAC = "000"
  def FU_FCMP = "001"
  def FU_FMV =  "010"
  def FU_F2I =  "011"
  def FU_I2F =  "100"
  def FU_S2D =  "101"
  def FU_D2S =  "110"
  def FU_DIVSQRT = "111"

  // FMA
  def fadd:UInt   = FpuOp(FU_FMAC, "000")
  def fsub:UInt   = FpuOp(FU_FMAC, "001")
  def fmadd:UInt  = FpuOp(FU_FMAC, "100")
  def fmsub:UInt  = FpuOp(FU_FMAC, "101")
  def fnmsub:UInt = FpuOp(FU_FMAC, "110")
  def fnmadd:UInt = FpuOp(FU_FMAC, "111")
  def fmul:UInt   = FpuOp(FU_FMAC, "010")

  // FCMP
  def fmin:UInt   = FpuOp(FU_FCMP, "000")
  def fmax:UInt   = FpuOp(FU_FCMP, "001")
  def fle:UInt    = FpuOp(FU_FCMP, "010")
  def flt:UInt    = FpuOp(FU_FCMP, "011")
  def feq:UInt    = FpuOp(FU_FCMP, "100")

  // FMV
  def fmv_f2i:UInt= FpuOp(FU_FMV, "000")
  def fmv_i2f:UInt= FpuOp(FU_FMV, "001")
  def fclass:UInt = FpuOp(FU_FMV, "010")
  def fsgnj:UInt  = FpuOp(FU_FMV, "110")
  def fsgnjn:UInt = FpuOp(FU_FMV, "101")
  def fsgnjx:UInt = FpuOp(FU_FMV, "100")

  // FloatToInt
  def f2w:UInt    = FpuOp(FU_F2I, "000")
  def f2wu:UInt   = FpuOp(FU_F2I, "001")
  def f2l:UInt    = FpuOp(FU_F2I, "010")
  def f2lu:UInt   = FpuOp(FU_F2I, "011")

  // IntToFloat
  def w2f:UInt    = FpuOp(FU_I2F, "000")
  def wu2f:UInt   = FpuOp(FU_I2F, "001")
  def l2f:UInt    = FpuOp(FU_I2F, "010")
  def lu2f:UInt   = FpuOp(FU_I2F, "011")

  // FloatToFloat
  def s2d:UInt    = FpuOp(FU_S2D, "000")
  def d2s:UInt    = FpuOp(FU_D2S, "000")

  // Div/Sqrt
  def fdiv:UInt   = FpuOp(FU_DIVSQRT, "000")
  def fsqrt:UInt  = FpuOp(FU_DIVSQRT, "001")
}

object FPUIOFunc {
  def in_raw = 0.U(1.W)
  def in_unbox = 1.U(1.W)

  def out_raw = 0.U(2.W)
  def out_box = 1.U(2.W)
  def out_sext = 2.U(2.W)
  def out_zext = 3.U(2.W)

  def apply(inputFunc: UInt, outputFunc:UInt) = Cat(inputFunc, outputFunc)
}

class Fflags extends Bundle {
  val invalid = Bool()    // 4
  val infinite = Bool()   // 3
  val overflow = Bool()   // 2
  val underflow = Bool()  // 1
  val inexact = Bool()    // 0
}

object RoudingMode {
  val RNE = "b000".U(3.W)
  val RTZ = "b001".U(3.W)
  val RDN = "b010".U(3.W)
  val RUP = "b011".U(3.W)
  val RMM = "b100".U(3.W)
}

class FloatPoint(val expWidth: Int, val mantWidth:Int) extends Bundle{
  val sign = Bool()
  val exp = UInt(expWidth.W)
  val mant = UInt(mantWidth.W)
  def defaultNaN: UInt = Cat(0.U(1.W), Fill(expWidth+1,1.U(1.W)), Fill(mantWidth-1,0.U(1.W)))
  def posInf: UInt = Cat(0.U(1.W), Fill(expWidth, 1.U(1.W)), 0.U(mantWidth.W))
  def negInf: UInt = Cat(1.U(1.W), posInf.tail(1))
  def maxNorm: UInt = Cat(0.U(1.W), Fill(expWidth-1, 1.U(1.W)), 0.U(1.W), Fill(mantWidth, 1.U(1.W)))
  def expBias: UInt = Fill(expWidth-1, 1.U(1.W))
  def expBiasInt: Int = (1 << (expWidth-1)) - 1
  def mantExt: UInt = Cat(exp=/=0.U, mant)
  def apply(x: UInt): FloatPoint = x.asTypeOf(new FloatPoint(expWidth, mantWidth))
}

object Float32 extends FloatPoint(8, 23)
object Float64 extends FloatPoint(11, 52)


object expOverflow {
  def apply(sexp: SInt, expWidth: Int): Bool =
    sexp >= Cat(0.U(1.W), Fill(expWidth, 1.U(1.W))).asSInt()

  def apply(uexp: UInt, expWidth: Int): Bool =
    expOverflow(Cat(0.U(1.W), uexp).asSInt(), expWidth)
}

object boxF32ToF64 {
  def apply(x: UInt): UInt = Cat(Fill(32, 1.U(1.W)), x(31, 0))
}

object unboxF64ToF32 {
  def apply(x: UInt): UInt =
    Mux(x(63, 32)===Fill(32, 1.U(1.W)), x(31, 0), Float32.defaultNaN)
}

object extF32ToF64 {
  def apply(x: UInt): UInt = {
    val f32 = Float32(x)
    Cat(
      f32.sign,
      Mux(f32.exp === 0.U,
        0.U(Float64.expWidth.W),
        Mux((~f32.exp).asUInt() === 0.U,
          Cat("b111".U(3.W), f32.exp),
          Cat("b0111".U(4.W) + f32.exp.head(1), f32.exp.tail(1))
        )
      ),
      Cat(f32.mant, 0.U((Float64.mantWidth - Float32.mantWidth).W))
    )
  }
}

