package beam.agentsim.infrastructure.power

import akka.actor.ActorSystem
import akka.testkit.{ImplicitSender, TestKit}
import beam.agentsim.agents.vehicles.EnergyEconomyAttributes.Powertrain
import beam.agentsim.agents.vehicles.{BeamVehicle, BeamVehicleType}
import beam.agentsim.infrastructure.ChargingNetworkManager.ChargingZone
import beam.agentsim.infrastructure.ParkingStall
import beam.agentsim.infrastructure.charging.ChargingPointType
import beam.agentsim.infrastructure.parking.{ParkingType, PricingModel}
import beam.agentsim.infrastructure.taz.TAZ
import beam.cosim.helics.BeamHelicsInterface._
import beam.sim.{BeamHelper, BeamServicesImpl}
import beam.sim.config.{BeamConfig, MatSimBeamConfigBuilder}
import beam.utils.TestConfigUtils.testConfig
import beam.utils.{BeamVehicleUtils, TestConfigUtils}
import com.typesafe.config.ConfigFactory
import org.matsim.api.core.v01.Id
import org.matsim.core.controler.OutputDirectoryHierarchy.OverwriteFileSetting
import org.mockito.Mockito.when
import org.scalatest.{BeforeAndAfterEach, Matchers, WordSpecLike}
import org.scalatestplus.mockito.MockitoSugar

import scala.collection.immutable.List

class SitePowerManagerSpec
    extends TestKit(
      ActorSystem(
        "SitePowerManagerSpec",
        ConfigFactory
          .parseString("""
           |akka.log-dead-letters = 10
           |akka.actor.debug.fsm = true
           |akka.loglevel = debug
           |akka.test.timefactor = 2
           |akka.test.single-expect-default = 10 s""".stripMargin)
      )
    )
    with WordSpecLike
    with Matchers
    with MockitoSugar
    with BeamHelper
    with ImplicitSender
    with BeforeAndAfterEach {

  private val conf = system.settings.config
    .withFallback(ConfigFactory.parseString(s"""
                                               |beam.router.skim = {
                                               |  keepKLatestSkims = 1
                                               |  writeSkimsInterval = 1
                                               |  writeAggregatedSkimsInterval = 1
                                               |  taz-skimmer {
                                               |    name = "taz-skimmer"
                                               |    fileBaseName = "skimsTAZ"
                                               |  }
                                               |}
                                               |beam.agentsim.chargingNetworkManager {
                                               |  gridConnectionEnabled = false
                                               |  chargingSessionInSeconds = 300
                                               |  planningHorizonInSec = 300
                                               |  helicsFederateName = "CNMFederate"
                                               |  helicsDataOutStreamPoint = ""
                                               |  helicsDataInStreamPoint = ""
                                               |  helicsBufferSize = 1000
                                               |}
                                               |""".stripMargin))
    .withFallback(testConfig("test/input/beamville/beam.conf").resolve())
  private val beamConfig: BeamConfig = BeamConfig(conf)
  private val matsimConfig = new MatSimBeamConfigBuilder(conf).buildMatSimConf()
  matsimConfig.controler.setOutputDirectory(TestConfigUtils.testOutputDir)
  matsimConfig.controler.setOverwriteFileSetting(OverwriteFileSetting.overwriteExistingFiles)
  private val beamScenario = loadScenario(beamConfig)
  private val scenario = buildScenarioFromMatsimConfig(matsimConfig, beamScenario)
  private val injector = buildInjector(system.settings.config, beamConfig, scenario, beamScenario)
  val beamServices = new BeamServicesImpl(injector)

  val beamFederateMock: BeamFederate = mock[BeamFederate]
  val parkingStall1: ParkingStall = mock[ParkingStall]
  when(parkingStall1.chargingPointType).thenReturn(Some(ChargingPointType.ChargingStationType1))
  when(parkingStall1.locationUTM).thenReturn(beamServices.beamScenario.tazTreeMap.getTAZs.head.coord)
  when(parkingStall1.parkingZoneId).thenReturn(1)

  val parkingStall2: ParkingStall = mock[ParkingStall]
  when(parkingStall2.chargingPointType).thenReturn(Some(ChargingPointType.ChargingStationType1))
  when(parkingStall2.locationUTM).thenReturn(beamServices.beamScenario.tazTreeMap.getTAZs.head.coord)
  when(parkingStall2.parkingZoneId).thenReturn(1)

  private val vehicleTypes = BeamVehicleUtils.readBeamVehicleTypeFile("test/input/beamville/vehicleTypes.csv")
  private val vehiclesList = {
    val v1 = new BeamVehicle(
      Id.createVehicleId("id1"),
      new Powertrain(0.0),
      vehicleTypes(Id.create("PHEV", classOf[BeamVehicleType]))
    )
    v1.useParkingStall(parkingStall1)
    val v2 = new BeamVehicle(
      Id.createVehicleId("id2"),
      new Powertrain(0.0),
      vehicleTypes(Id.create("CAV", classOf[BeamVehicleType]))
    )
    v2.useParkingStall(parkingStall2)
    List(v1, v2)
  }

  "SitePowerManager" should {
    val dummyChargingZone: ChargingZone = ChargingZone(
      1,
      Id.create("Dummy", classOf[TAZ]),
      ParkingType.Public,
      1,
      1,
      ChargingPointType.ChargingStationType1,
      PricingModel.FlatFee(0.0)
    )
    val stations: Map[Int, ChargingZone] = Map[Int, ChargingZone](1 -> dummyChargingZone)
    val sitePowerManager = new SitePowerManager(stations, beamServices)

    "get power over planning horizon 0.0 for charged vehicles" in {
      sitePowerManager.getPowerOverNextPlanningHorizon(300) shouldBe Map(
        dummyChargingZone.parkingZoneId -> 0.0
      )
    }
    "get power over planning horizon greater than 0.0 for discharged vehicles" in {
      val vehiclesMap = Map(vehiclesList.map(v => v.id -> v): _*)
      vehiclesMap.foreach(_._2.addFuel(-10000))
      sitePowerManager.getPowerOverNextPlanningHorizon(300) shouldBe Map(
        dummyChargingZone.parkingZoneId -> 0.0
      )
    }
    "replan horizon and get charging plan per vehicle" in {
      val vehiclesMap = Map(vehiclesList.map(v => v.id -> v): _*)
      vehiclesMap.foreach(_._2.addFuel(-10000))
      sitePowerManager.replanHorizonAndGetChargingPlanPerVehicle(
        0,
        vehiclesMap.values,
        sitePowerManager.unlimitedPhysicalBounds,
        300
      ) shouldBe Map(
        Id.createVehicleId("id1") -> (3, 21600.0),
        Id.createVehicleId("id2") -> (3, 21600.0)
      )
    }
  }

}
