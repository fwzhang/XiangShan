package xiangshan.backend.decode.isa

import chisel3.util._
import xiangshan.HasXSParameter
import xiangshan.FuType._
import xiangshan.backend.decode._
import xiangshan.backend.LSUOpType
import xiangshan.backend.fu.fpu.FPUOpType._

object RVDInstr extends HasXSParameter with HasInstrType {

  def FADD_D             = BitPat("b0000001??????????????????1010011")
  def FSUB_D             = BitPat("b0000101??????????????????1010011")
  def FMUL_D             = BitPat("b0001001??????????????????1010011")
  def FDIV_D             = BitPat("b0001101??????????????????1010011")
  def FSGNJ_D            = BitPat("b0010001??????????000?????1010011")
  def FSGNJN_D           = BitPat("b0010001??????????001?????1010011")
  def FSGNJX_D           = BitPat("b0010001??????????010?????1010011")
  def FMIN_D             = BitPat("b0010101??????????000?????1010011")
  def FMAX_D             = BitPat("b0010101??????????001?????1010011")
  def FCVT_S_D           = BitPat("b010000000001?????????????1010011")
  def FCVT_D_S           = BitPat("b010000100000?????????????1010011")
  def FSQRT_D            = BitPat("b010110100000?????????????1010011")
  def FLE_D              = BitPat("b1010001??????????000?????1010011")
  def FLT_D              = BitPat("b1010001??????????001?????1010011")
  def FEQ_D              = BitPat("b1010001??????????010?????1010011")
  def FCVT_W_D           = BitPat("b110000100000?????????????1010011")
  def FCVT_WU_D          = BitPat("b110000100001?????????????1010011")
  def FCVT_L_D           = BitPat("b110000100010?????????????1010011")
  def FCVT_LU_D          = BitPat("b110000100011?????????????1010011")
  def FMV_X_D            = BitPat("b111000100000?????000?????1010011")
  def FCLASS_D           = BitPat("b111000100000?????001?????1010011")
  def FCVT_D_W           = BitPat("b110100100000?????????????1010011")
  def FCVT_D_WU          = BitPat("b110100100001?????????????1010011")
  def FCVT_D_L           = BitPat("b110100100010?????????????1010011")
  def FCVT_D_LU          = BitPat("b110100100011?????????????1010011")
  def FMV_D_X            = BitPat("b111100100000?????000?????1010011")
  def FLD                = BitPat("b?????????????????011?????0000111")
  def FSD                = BitPat("b?????????????????011?????0100111")
  def FMADD_D            = BitPat("b?????01??????????????????1000011")
  def FMSUB_D            = BitPat("b?????01??????????????????1000111")
  def FNMSUB_D           = BitPat("b?????01??????????????????1001011")
  def FNMADD_D           = BitPat("b?????01??????????????????1001111")

  val table = Array(
    FLD -> List(InstrFI, ldu, LSUOpType.ld),
    FSD -> List(InstrFS, stu, LSUOpType.sd),

    // FR
    FADD_D    -> List(InstrFR, fmac,  fadd),
    FSUB_D    -> List(InstrFR, fmac,  fsub),
    FMUL_D    -> List(InstrFR, fmac,  fmul),
    FDIV_D    -> List(InstrFR, fmisc, fdiv),
    FMIN_D    -> List(InstrFR, fmisc, fmin),
    FMAX_D    -> List(InstrFR, fmisc, fmax),
    FSGNJ_D   -> List(InstrFR, fmisc, fsgnj),
    FSGNJN_D  -> List(InstrFR, fmisc, fsgnjn),
    FSGNJX_D  -> List(InstrFR, fmisc, fsgnjx),
    FSQRT_D   -> List(InstrFR, fmisc, fsqrt),
    FMADD_D   -> List(InstrFR, fmac,  fmadd),
    FNMADD_D  -> List(InstrFR, fmac,  fnmadd),
    FMSUB_D   -> List(InstrFR, fmac,  fmsub),
    FNMSUB_D  -> List(InstrFR, fmac,  fnmsub),
    FCVT_S_D  -> List(InstrFR, fmisc, d2s),
    FCVT_D_S  -> List(InstrFR, fmisc, s2d),

    // FtoG
    FCLASS_D  -> List(InstrFtoG, fmisc, fclass),
    FMV_X_D   -> List(InstrFtoG, fmisc, fmv_f2i),
    FCVT_W_D  -> List(InstrFtoG, fmisc, f2w),
    FCVT_WU_D -> List(InstrFtoG, fmisc, f2wu),
    FCVT_L_D  -> List(InstrFtoG, fmisc, f2l),
    FCVT_LU_D -> List(InstrFtoG, fmisc, f2lu),
    FLE_D     -> List(InstrFtoG, fmisc, fle),
    FLT_D     -> List(InstrFtoG, fmisc, flt),
    FEQ_D     -> List(InstrFtoG, fmisc, feq),

    // GtoF
    FMV_D_X   -> List(InstrGtoF, i2f, fmv_i2f),
    FCVT_D_W  -> List(InstrGtoF, i2f, w2f),
    FCVT_D_WU -> List(InstrGtoF, i2f, wu2f),
    FCVT_D_L  -> List(InstrGtoF, i2f, l2f),
    FCVT_D_LU -> List(InstrGtoF, i2f, lu2f)
  )
}
