package beam.router.skim.urbansim

import akka.actor.ActorSystem
import beam.router.FreeFlowTravelTime
import beam.router.Modes.BeamMode
import beam.router.skim.ActivitySimSkimmer.ActivitySimSkimmerKey
import beam.router.skim.{AbstractSkimmerInternal, AbstractSkimmerKey, ActivitySimPathType}
import beam.sim.{BeamHelper, BeamServices}
import beam.sim.config.BeamExecutionConfig
import beam.utils.TestConfigUtils.testConfig
import com.google.inject.Injector
import com.typesafe.config.{Config, ConfigFactory}
import org.matsim.core.scenario.MutableScenario
import org.scalatest.{FlatSpec, Matchers}
import org.scalatestplus.mockito.MockitoSugar

import scala.concurrent.duration._
import scala.concurrent.Await

class BackgroundSkimsCreatorTest extends FlatSpec with Matchers with MockitoSugar with BeamHelper {

  val actorSystemName = "BackgroundSkimsCreatorTest"

  val config: Config = ConfigFactory
    .parseString(
      s"""
        |beam.actorSystemName = "$actorSystemName"
        |beam.routing.carRouter="staticGH"
        |beam.urbansim.backgroundODSkimsCreator.skimsKind = "activitySim"
        |beam.urbansim.backgroundODSkimsCreator.routerType = "r5+gh"
        |beam.agentsim.taz.filePath = test/test-resources/taz-centers.10.csv
        |beam.urbansim.backgroundODSkimsCreator.maxTravelDistanceInMeters.walk = 1000
      """.stripMargin
    )
    .withFallback(testConfig("test/input/sf-light/sf-light-1k.conf"))
    .resolve()

  implicit val actorSystem: ActorSystem = ActorSystem(s"$actorSystemName", config)

  val beamExecutionConfig: BeamExecutionConfig = setupBeamWithConfig(config)

  val (scenarioBuilt, beamScenario) = buildBeamServicesAndScenario(
    beamExecutionConfig.beamConfig,
    beamExecutionConfig.matsimConfig
  )
  val scenario: MutableScenario = scenarioBuilt
  val injector: Injector = buildInjector(config, beamExecutionConfig.beamConfig, scenario, beamScenario)
  val beamServices: BeamServices = buildBeamServices(injector, scenario)

  def createBackgroundSkimsCreator(
    modes: Seq[BeamMode],
    withTransit: Boolean,
    buildDirectWalkRoute: Boolean,
    buildDirectCarRoute: Boolean
  ): BackgroundSkimsCreator = {
    val tazClustering: TAZClustering = new TAZClustering(beamScenario.tazTreeMap)
    val tazActivitySimSkimmer = BackgroundSkimsCreator.createTAZActivitySimSkimmer(beamServices, tazClustering)
    new BackgroundSkimsCreator(
      beamServices,
      beamScenario,
      tazClustering,
      tazActivitySimSkimmer,
      new FreeFlowTravelTime,
      modes,
      withTransit = withTransit,
      buildDirectWalkRoute = buildDirectWalkRoute,
      buildDirectCarRoute = buildDirectCarRoute,
      calculationTimeoutHours = 1
    )(actorSystem)
  }

  "skims creator" should "generate WALK skims only" in {
    val skimsCreator =
      createBackgroundSkimsCreator(
        modes = Seq(BeamMode.CAR, BeamMode.WALK),
        withTransit = false,
        buildDirectCarRoute = false,
        buildDirectWalkRoute = true
      )
    skimsCreator.start()
    skimsCreator.increaseParallelismTo(Runtime.getRuntime.availableProcessors())

    val finalSkimmer = Await.result(skimsCreator.getResult, 10.minutes).abstractSkimmer
    skimsCreator.stop()

    val skims: Map[AbstractSkimmerKey, AbstractSkimmerInternal] = finalSkimmer.currentSkim.toMap
    val keys = skims.keys.map(_.asInstanceOf[ActivitySimSkimmerKey]).toSeq

    keys.count(_.pathType != ActivitySimPathType.WALK) shouldBe 0
    keys.size shouldBe 22 // because max walk trip length is 1000 meters
  }

  "skims creator" should "generate CAR skims only" in {
    val skimsCreator =
      createBackgroundSkimsCreator(
        modes = Seq(BeamMode.CAR, BeamMode.WALK),
        withTransit = false,
        buildDirectCarRoute = true,
        buildDirectWalkRoute = false
      )
    skimsCreator.start()
    skimsCreator.increaseParallelismTo(Runtime.getRuntime.availableProcessors())

    val finalSkimmer = Await.result(skimsCreator.getResult, 10.minutes).abstractSkimmer
    skimsCreator.stop()

    val skims: Map[AbstractSkimmerKey, AbstractSkimmerInternal] = finalSkimmer.currentSkim.toMap
    val keys = skims.keys.map(_.asInstanceOf[ActivitySimSkimmerKey]).toSeq

    keys.count(_.pathType != ActivitySimPathType.SOV) shouldBe 0
    keys.size shouldBe 100
  }

  "skims creator" should "generate transit skims only" in {
    val skimsCreator =
      createBackgroundSkimsCreator(
        modes = Seq(BeamMode.CAR, BeamMode.WALK),
        withTransit = true,
        buildDirectCarRoute = false,
        buildDirectWalkRoute = false
      )
    skimsCreator.start()
    skimsCreator.increaseParallelismTo(Runtime.getRuntime.availableProcessors())

    val finalSkimmer = Await.result(skimsCreator.getResult, 10.minutes).abstractSkimmer
    skimsCreator.stop()

    val skims: Map[AbstractSkimmerKey, AbstractSkimmerInternal] = finalSkimmer.currentSkim.toMap

    val pathTypeToSkimsCount = skims.keys
      .map(_.asInstanceOf[ActivitySimSkimmerKey])
      .foldLeft(scala.collection.mutable.HashMap.empty[ActivitySimPathType, Int]) {
        case (pathTypeToCount, skimmerKey) =>
          pathTypeToCount.get(skimmerKey.pathType) match {
            case Some(count) => pathTypeToCount(skimmerKey.pathType) = count + 1
            case None        => pathTypeToCount(skimmerKey.pathType) = 1
          }
          pathTypeToCount
      }

    pathTypeToSkimsCount(ActivitySimPathType.DRV_HVY_WLK) shouldBe 12
    pathTypeToSkimsCount(ActivitySimPathType.DRV_LOC_WLK) shouldBe 8
    pathTypeToSkimsCount(ActivitySimPathType.WLK_HVY_WLK) shouldBe 23
    pathTypeToSkimsCount(ActivitySimPathType.WLK_LOC_WLK) shouldBe 61
    pathTypeToSkimsCount(ActivitySimPathType.WLK_LRF_WLK) shouldBe 10

    pathTypeToSkimsCount(ActivitySimPathType.OTHER) shouldBe 1

    skims.keys.size shouldBe (12 + 8 + 23 + 61 + 10 + 1)
  }


  "skims creator" should "generate all types of skims" in {
    val skimsCreator =
      createBackgroundSkimsCreator(
        modes = Seq(BeamMode.CAR, BeamMode.WALK),
        withTransit = true,
        buildDirectCarRoute = true,
        buildDirectWalkRoute = true
      )
    skimsCreator.start()
    skimsCreator.increaseParallelismTo(Runtime.getRuntime.availableProcessors())

    val finalSkimmer = Await.result(skimsCreator.getResult, 10.minutes).abstractSkimmer
    skimsCreator.stop()

    val skims: Map[AbstractSkimmerKey, AbstractSkimmerInternal] = finalSkimmer.currentSkim.toMap

    val pathTypeToSkimsCount = skims.keys
      .map(_.asInstanceOf[ActivitySimSkimmerKey])
      .foldLeft(scala.collection.mutable.HashMap.empty[ActivitySimPathType, Int]) {
        case (pathTypeToCount, skimmerKey) =>
          pathTypeToCount.get(skimmerKey.pathType) match {
            case Some(count) => pathTypeToCount(skimmerKey.pathType) = count + 1
            case None        => pathTypeToCount(skimmerKey.pathType) = 1
          }
          pathTypeToCount
      }

    pathTypeToSkimsCount(ActivitySimPathType.DRV_HVY_WLK) shouldBe 12
    pathTypeToSkimsCount(ActivitySimPathType.DRV_LOC_WLK) shouldBe 8
    pathTypeToSkimsCount(ActivitySimPathType.WLK_HVY_WLK) shouldBe 23
    pathTypeToSkimsCount(ActivitySimPathType.WLK_LOC_WLK) shouldBe 61
    pathTypeToSkimsCount(ActivitySimPathType.WLK_LRF_WLK) shouldBe 10

    pathTypeToSkimsCount(ActivitySimPathType.WALK) shouldBe 22 // because max walk trip length is 1000 meters
    pathTypeToSkimsCount(ActivitySimPathType.SOV) shouldBe 100

    pathTypeToSkimsCount(ActivitySimPathType.OTHER) shouldBe 1

    skims.keys.size shouldBe 237
  }}