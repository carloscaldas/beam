package beam.agentsim.infrastructure.power

import beam.cosim.helics.BeamFederate
import beam.sim.BeamServices
import beam.sim.config.BeamConfig
import org.slf4j.{Logger, LoggerFactory}

import scala.util.{Failure, Try}

class PowerController(beamServices: BeamServices, beamConfig: BeamConfig) {

  private val logger: Logger = LoggerFactory.getLogger(classOf[PowerController])

  logger.debug("Init PowerController resources...")
  private[power] lazy val beamFederateMaybe: Option[BeamFederate] = Try {
    BeamFederate.loadHelics
    BeamFederate.getInstance(beamServices)
  }.recoverWith {
    case e =>
      logger.error("Cannot init BeamFederate: {}", e.getMessage)
      Failure(e)
  }.toOption

  /**
    * Publishes required power to the grid
    *
    * @param requiredPower power (in joules) over planning horizon
    */
  def publishPowerOverPlanningHorizon(requiredPower: Double): Unit = {
    beamFederateMaybe
      .map(_.publishPowerOverPlanningHorizon(requiredPower))
      .getOrElse {
        logger.warn("Not connected to Grid, nothing was sent")
      }
  }

  /**
    * Obtains physical bounds from the grid
    *
    * @param tick current tick
    * @return tuple of PhysicalBounds and Int (next tick)
    */
  def obtainPowerPhysicalBounds(tick: Int): (PhysicalBounds, Int) = {
    logger.debug("Obtaining power from the grid at time {}...", tick)
    val (nextTick, powerValue) =
      beamFederateMaybe
        .map(_.syncAndGetPowerFlowValue(tick))
        .getOrElse {
          logger.warn("Not connected to Grid, using default physical bounds (0.0)")
          val fedTimeStep = beamConfig.beam.cosim.helics.timeStep
          (fedTimeStep * (1 + (tick / fedTimeStep)), 0.0)
        }
    logger.debug("Obtained power from the grid {}...", powerValue)
    (PhysicalBounds.default(powerValue), nextTick)
  }

  def close(): Unit = {
    logger.debug("Release PowerController resources...")
    beamFederateMaybe
      .map(_.close())
      .getOrElse {
        logger.warn("Not connected to Grid, just releasing helics resources")
      }
    BeamFederate.destroyInstance()
  }
}
