package top

import system.SoCParameters
import xiangshan.backend.dispatch.DispatchParameters
import xiangshan.{EnviromentParameters, XSCoreParameters}

case class Parameters
(
  coreParameters: XSCoreParameters = XSCoreParameters(),
  socParameters: SoCParameters = SoCParameters(),
  envParameters: EnviromentParameters = EnviromentParameters()
){
  require(
    !(envParameters.FPGAPlatform && envParameters.EnableDebug),
    "Enable debug(display log) is only supported in simulation enviroment!"
  )
  require(
    !(socParameters.EnableILA && !envParameters.FPGAPlatform),
    "ILA is only supported in FPGA platform!"
  )
}

object Parameters {

  val bigCoreParams = XSCoreParameters(
    EnableLB = false,
    RoqSize = 192,
    LoadQueueSize = 64,
    StoreQueueSize = 48,
    BrqSize = 48,
    IssQueSize = 16,
    NRPhyRegs = 160,
    dpParams = DispatchParameters(
      DqEnqWidth = 4,
      IntDqSize = 128,
      FpDqSize = 128,
      LsDqSize = 96,
      IntDqDeqWidth = 4,
      FpDqDeqWidth = 4,
      LsDqDeqWidth = 4,
      IntDqReplayWidth = 4,
      FpDqReplayWidth = 4,
      LsDqReplayWidth = 4
    )
  )

  val dualCoreParameters = Parameters(socParameters = SoCParameters(NumCores = 2))
  val simParameters = Parameters(envParameters = EnviromentParameters(FPGAPlatform = false)) // sim only, disable log
  val debugParameters = Parameters(envParameters = simParameters.envParameters.copy(EnableDebug = true)) // open log

  private var parameters = Parameters() // a default parameter, can be updated before use
  def get: Parameters = parameters
  def set(p: Parameters): Unit = {
    parameters = p
  }
}
