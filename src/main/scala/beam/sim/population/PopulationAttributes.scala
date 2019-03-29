package beam.sim.population

import beam.agentsim.agents.choice.mode.ModeChoiceMultinomialLogit
import beam.agentsim.agents.vehicles.{BeamVehicle, BeamVehicleType}
import beam.router.Modes.BeamMode
import beam.router.Modes.BeamMode._
import beam.router.model.{BeamLeg, EmbodiedBeamLeg}
import org.matsim.api.core.v01.Id
import org.matsim.households.{Household, IncomeImpl}
import org.matsim.households.Income.IncomePeriod
import org.matsim.api.core.v01.population._
import beam.sim.BeamServices
import beam.agentsim.agents.modalbehaviors.ModeChoiceCalculator._
import beam.router.RouteHistory.LinkId

import scala.collection.JavaConverters._
import scala.collection.mutable

sealed trait PopulationAttributes

case class AttributesOfIndividual(
  householdAttributes: HouseholdAttributes,
  modalityStyle: Option[String],
  isMale: Boolean,
  availableModes: Seq[BeamMode],
  valueOfTime: Double,
  age: Option[Int],
  income: Option[Double]
) extends PopulationAttributes {
  lazy val hasModalityStyle: Boolean = modalityStyle.nonEmpty

  // Get Value of Travel Time for a specific leg of a travel alternative:
  // If it is a car leg, we use link-specific multipliers, otherwise we just look at the entire leg travel time and mode

  def getGeneralizedTimeOfLinkForMNL(
    IdAndTT: (LinkId, Int),
    beamMode: BeamMode,
    modeChoiceModel: ModeChoiceMultinomialLogit,
    beamServices: BeamServices,
    beamVehicleTypeId: Option[Id[BeamVehicleType]] = None,
    destinationActivity: Option[Activity] = None,
    isRideHail: Boolean = false,
    isPooledTrip: Boolean = false
  ): Double = {
    val isWorkTrip = destinationActivity match {
      case None =>
        false
      case Some(activity) =>
        activity.getType().equalsIgnoreCase("work")
    }
    val vehicleAutomationLevel = getAutomationLevel(beamVehicleTypeId, beamServices)

    val multiplier = beamMode match {
      case CAR =>
        if (isRideHail) {
          if (isPooledTrip) {
            getModeVotMultiplier(Option(RIDE_HAIL_POOLED), modeChoiceModel.modeMultipliers) *
            getPooledFactor(vehicleAutomationLevel, modeChoiceModel.poolingMultipliers)
          } else {
            getModeVotMultiplier(Option(RIDE_HAIL), modeChoiceModel.modeMultipliers)
          }
        } else {
          getSituationMultiplier(
            IdAndTT._1,
            IdAndTT._2,
            isWorkTrip,
            modeChoiceModel.situationMultipliers,
            vehicleAutomationLevel,
            beamServices
          ) * getModeVotMultiplier(Option(CAR), modeChoiceModel.modeMultipliers)
        }
      case _ =>
        getModeVotMultiplier(Option(beamMode), modeChoiceModel.modeMultipliers)
    }
    multiplier * IdAndTT._2 / 3600
  }

  def getGeneralizedTimeOfLegForMNL(
    embodiedBeamLeg: EmbodiedBeamLeg,
    modeChoiceModel: ModeChoiceMultinomialLogit,
    beamServices: BeamServices,
    destinationActivity: Option[Activity]
  ): Double = {
    embodiedBeamLeg.beamLeg.mode match {
      case CAR => // NOTE: Ride hail legs are classified as CAR mode. For now we only need to loop through links here
        val idsAndTravelTimes =
          embodiedBeamLeg.beamLeg.travelPath.linkIds.zip(embodiedBeamLeg.beamLeg.travelPath.linkTravelTime)
        idsAndTravelTimes.foldLeft(0.0)(
          _ + getGeneralizedTimeOfLinkForMNL(
            _,
            embodiedBeamLeg.beamLeg.mode,
            modeChoiceModel,
            beamServices,
            Option(embodiedBeamLeg.beamVehicleTypeId),
            destinationActivity,
            embodiedBeamLeg.isRideHail,
            embodiedBeamLeg.isPooledTrip
          )
        )
      case _ =>
        getModeVotMultiplier(Option(embodiedBeamLeg.beamLeg.mode), modeChoiceModel.modeMultipliers) *
        embodiedBeamLeg.beamLeg.duration / 3600
    }
  }

  def getVOT(generalizedTime: Double): Double = {
    valueOfTime * generalizedTime
  }

  private def getAutomationLevel(
    beamVehicleTypeId: Option[Id[BeamVehicleType]],
    beamServices: BeamServices
  ): automationLevel = {
    val automationInt = beamVehicleTypeId match {
      case Some(beamVehicleTypeId) =>
        // Use default if it exists, otherwise look up from vehicle ID
        beamServices
          .getDefaultAutomationLevel()
          .getOrElse(beamServices.vehicleTypes(beamVehicleTypeId).automationLevel)
      case None =>
        1
    }
    automationInt match {
      case 1 => levelLE2
      case 2 => levelLE2
      case 3 => level3
      case 4 => level4
      case 5 => level5
      case _ => levelLE2
    }
  }

  // Convert from seconds to hours and bring in person's base VOT
  def unitConversionVOTT(duration: Double): Double = {
    valueOfTime / 3600 * duration
  }

  def getModeVotMultiplier(
    beamMode: Option[BeamMode],
    modeMultipliers: mutable.Map[Option[BeamMode], Double]
  ): Double = {
    modeMultipliers.getOrElse(beamMode, 1.0)
  }

  private def getPooledFactor(
    vehicleAutomationLevel: automationLevel,
    poolingMultipliers: mutable.Map[automationLevel, Double]
  ): Double = {
    poolingMultipliers.getOrElse(vehicleAutomationLevel, 1.0)
  }

  private def getLinkCharacteristics(
    linkID: Int,
    travelTime: Double,
    beamServices: BeamServices
  ): (congestionLevel, roadwayType) = {
    // Note: cutoffs for congested (2/3 free flow speed) and highway (ff speed > 20 m/s) are arbitrary and could be inputs
    val currentLink = beamServices.networkHelper.getLink(linkID).get
    val freeSpeed: Double = currentLink.getFreespeed()
    val currentSpeed: Double = if (travelTime == 0) { freeSpeed } else { currentLink.getLength() / travelTime }
    if (currentSpeed < 0.67 * freeSpeed) {
      if (freeSpeed > 20) {
        (highCongestion, highway)
      } else {
        (highCongestion, nonHighway)
      }
    } else {
      if (freeSpeed > 20) {
        (lowCongestion, highway)
      } else {
        (lowCongestion, nonHighway)
      }
    }
  }

  private def getSituationMultiplier(
    linkID: Int,
    travelTime: Double,
    isWorkTrip: Boolean = true,
    situationMultipliers: mutable.Map[(timeSensitivity, congestionLevel, roadwayType, automationLevel), Double],
    vehicleAutomationLevel: automationLevel,
    beamServices: BeamServices
  ): Double = {
    val sensitivity: timeSensitivity = if (isWorkTrip) {
      highSensitivity
    } else {
      lowSensitivity
    }
    val (congestion, roadway) = getLinkCharacteristics(linkID, travelTime, beamServices)
    situationMultipliers.getOrElse((sensitivity, congestion, roadway, vehicleAutomationLevel), 1.0)
  }

}

object AttributesOfIndividual {
  val EMPTY = AttributesOfIndividual(HouseholdAttributes.EMPTY, None, true, Seq(), 0.0, None, None)
}

case class HouseholdAttributes(
  householdIncome: Double,
  householdSize: Int,
  numCars: Int,
  numBikes: Int
) extends PopulationAttributes

object HouseholdAttributes {

  val EMPTY = HouseholdAttributes(0.0, 0, 0, 0)

  def apply(household: Household, vehicles: Map[Id[BeamVehicle], BeamVehicle]): HouseholdAttributes = {
    new HouseholdAttributes(
      Option(household.getIncome)
        .getOrElse(new IncomeImpl(0, IncomePeriod.year))
        .getIncome,
      household.getMemberIds.size(),
      household.getVehicleIds.asScala
        .map(id => vehicles(id))
        .count(_.beamVehicleType.id.toString.toLowerCase.contains("car")),
      household.getVehicleIds.asScala
        .map(id => vehicles(id))
        .count(_.beamVehicleType.id.toString.toLowerCase.contains("bike"))
    )
  }
}
