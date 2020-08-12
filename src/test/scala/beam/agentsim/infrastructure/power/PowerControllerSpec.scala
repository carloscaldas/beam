package beam.agentsim.infrastructure.power

import beam.cosim.helics.BeamFederate
import beam.sim.BeamServices
import beam.sim.config.BeamConfig
import beam.utils.TestConfigUtils.testConfig
import com.typesafe.config.ConfigFactory
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito._
import org.scalatest.{BeforeAndAfterEach, Matchers, WordSpecLike}
import org.scalatestplus.mockito.MockitoSugar

class PowerControllerSpec extends WordSpecLike with Matchers with MockitoSugar with BeforeAndAfterEach {

  private val config =
    ConfigFactory
      .parseString(s"""
                      |beam.cosim.helics = {
                      |  timeStep = 300
                      |  federateName = "BeamFederate"
                      |}
                    """.stripMargin)
      .withFallback(testConfig("test/input/beamville/beam.conf"))
      .resolve()

  val beamServicesMock = mock[BeamServices]
  val beamFederateMock = mock[BeamFederate]

  override def beforeEach = {
    reset(beamServicesMock, beamFederateMock)
    when(beamFederateMock.syncAndGetPowerFlowValue(300)).thenReturn((600, 5678.90))
  }

  "PowerController when connected to grid" should {
    val powerController = new PowerController(beamServicesMock, BeamConfig(config)) {
      override private[power] lazy val beamFederateMaybe = Some(beamFederateMock)
    }

    "publish power over planning horizon" in {
      powerController.publishPowerOverPlanningHorizon(100.0)
      verify(beamFederateMock, times(1)).publishPowerOverPlanningHorizon(100.0)
    }
    "obtain power physical bounds" in {
      val (bounds, nextTick) = powerController.obtainPowerPhysicalBounds(300)
      bounds shouldBe PhysicalBounds(5678.90, 5678.90, 0.0)
      nextTick shouldBe 600
      verify(beamFederateMock, times(1)).syncAndGetPowerFlowValue(300)
    }
  }
  "PowerController when not connected to grid" should {
    val powerController = new PowerController(beamServicesMock, BeamConfig(config)) {
      override private[power] lazy val beamFederateMaybe = None
    }

    "publish nothing" in {
      powerController.publishPowerOverPlanningHorizon(100.0)
      verify(beamFederateMock, never()).publishPowerOverPlanningHorizon(any())
    }

    "obtain default (0.0) power physical bounds" in {
      val (bounds, nextTick) = powerController.obtainPowerPhysicalBounds(300)
      bounds shouldBe PhysicalBounds(0.0, 0.0, 0.0)
      nextTick shouldBe 600
      verify(beamFederateMock, never()).syncAndGetPowerFlowValue(300)
    }

  }
}
