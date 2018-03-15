package beam.agentsim.agents.vehicles

import beam.agentsim.events.SpaceTime
import beam.router.RoutingModel.BeamLeg
import org.matsim.api.core.v01.Id
import org.matsim.api.core.v01.population.Person
import org.matsim.vehicles.Vehicle

import scala.collection.immutable.TreeMap
import scala.collection.mutable

/**
  * Holds information about the numbers and identities of agents in the model
  */
case class PassengerSchedule(schedule: TreeMap[BeamLeg, Manifest]) {

  def addLegs(legs: Seq[BeamLeg]): PassengerSchedule = {
    PassengerSchedule(schedule ++ legs.map(leg => (leg, Manifest())))
  }

  def addPassenger(passenger: VehiclePersonId, legs: Seq[BeamLeg]): PassengerSchedule = {
    PassengerSchedule(schedule ++ (legs.map(leg => (leg, schedule(leg).copy(riders = schedule(leg).riders + passenger))) ++
      legs.headOption.map(boardLeg => (boardLeg, schedule(boardLeg).copy(boarders = schedule(boardLeg).boarders + passenger.vehicleId))) ++
      legs.lastOption.map(alightLeg => (alightLeg, schedule(alightLeg).copy(alighters = schedule(alightLeg).alighters + passenger.vehicleId)))))
  }

  override def toString: String = {
    schedule.map(keyVal => s"${keyVal._1.toString} -> ${keyVal._2.toString}").mkString("--")
  }

}


object PassengerSchedule {
  def apply(): PassengerSchedule = new PassengerSchedule(TreeMap[BeamLeg, Manifest]()(Ordering.by(x=>(x.startTime,x.duration))))
}

case class VehiclePersonId(vehicleId: Id[Vehicle], personId: Id[Person])

case class Manifest(riders: Set[VehiclePersonId]=Set.empty, boarders: Set[Id[Vehicle]]=Set.empty, alighters: Set[Id[Vehicle]]=Set.empty) {
  override def toString: String = {
    s"[${riders.size}riders;${boarders.size}boarders;${alighters.size}alighters]"
  }
}