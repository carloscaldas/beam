package beam.agentsim.infrastructure.parking

import beam.agentsim.agents.vehicles.{VehicleCategory, VehicleManager}
import beam.agentsim.infrastructure.charging.ChargingPointType
import beam.agentsim.infrastructure.parking.ParkingZoneSearch.ZoneSearchTree
import beam.utils.matsim_conversion.MatsimPlanConversion.IdOps
import beam.utils.FileUtils
import com.typesafe.scalalogging.LazyLogging
import org.matsim.api.core.v01.Id
import org.matsim.core.utils.io.IOUtils

import java.io.{BufferedReader, File, IOException}
import scala.annotation.tailrec
import scala.collection.mutable
import scala.collection.mutable.ArrayBuffer
import scala.util.matching.Regex
import scala.util.{Failure, Random, Success, Try}
import com.github.tototoshi.csv._

// utilities to read/write parking zone information from/to a file
object ParkingZoneFileUtils extends LazyLogging {

  /**
    * header for parking files (used for writing new parking files)
    */
  private val ParkingFileHeader: String =
    "taz,parkingType,pricingModel,chargingType,numStalls,feeInCents,parkingZoneName,landCostInUSDPerSqft,reservedFor"

  /**
    * when a parking file is not provided, we generate one that covers all TAZs with free and ubiquitous parking
    * this should consider charging when it is implemented as well.
    * @param geoId a valid id for a geo object
    * @param parkingType the parking type we are using to generate a row
    * @param maybeChargingPoint charging point type
    * @return a row describing infinite free parking at this TAZ
    */
  def defaultParkingRow[GEO](
    geoId: Id[GEO],
    parkingType: ParkingType,
    maybeChargingPoint: Option[ChargingPointType]
  ): String = {
    val chargingPointStr = maybeChargingPoint.map(_.toString).getOrElse("NoCharger")
    s"$geoId,$parkingType,${PricingModel.FlatFee(0)},$chargingPointStr,${ParkingZone.UbiqiutousParkingAvailability},0,"
  }

  /**
    * used to build up parking alternatives from a file
    * @param zones the parking zones read in
    * @param tree the search tree constructed from the loaded zones
    * @param totalRows number of rows read
    * @param failedRows number of rows which failed to parse
    */
  case class ParkingLoadingAccumulator[GEO](
    zones: ArrayBuffer[ParkingZone[GEO]] = ArrayBuffer.empty[ParkingZone[GEO]],
    tree: mutable.Map[Id[GEO], Map[ParkingType, Vector[Int]]] =
      mutable.Map.empty[Id[GEO], Map[ParkingType, Vector[Int]]],
    totalRows: Int = 0,
    failedRows: Int = 0
  ) {

    def countFailedRow: ParkingLoadingAccumulator[GEO] =
      this.copy(
        totalRows = totalRows + 1,
        failedRows = failedRows + 1
      )
    def nextParkingZoneId: Int = zones.length
    def someRowsFailed: Boolean = failedRows > 0

    def totalParkingStalls: Long =
      zones.map { _.maxStalls.toLong }.sum

    def parkingStallsPlainEnglish: String = {
      val count: Long = totalParkingStalls
      if (count > 1000000000L) s"${count / 1000000000L} billion"
      else if (count > 1000000L) s"${count / 1000000L} million"
      else if (count > 1000L) s"${count / 1000L} thousand"
      else count.toString
    }

  }

  /**
    * parking data associated with a row of the parking file
    * @param tazId a TAZ id
    * @param parkingType the parking type of this row
    * @param parkingZone the parking zone produced by this row
    */
  case class ParkingLoadingDataRow[GEO](tazId: Id[GEO], parkingType: ParkingType, parkingZone: ParkingZone[GEO])

  /**
    * write the loaded set of parking and charging options to an instance parking file
    *
    * @param stallSearch the search tree of available parking options
    * @param stalls the stored ParkingZones
    * @param writeDestinationPath a file path to write to
    */
  def writeParkingZoneFile[GEO](
    stallSearch: ZoneSearchTree[GEO],
    stalls: Array[ParkingZone[GEO]],
    writeDestinationPath: String
  ): Unit = {

    val destinationFile = new File(writeDestinationPath)

    Try {
      for {
        (tazId, parkingTypesSubtree)  <- stallSearch.toList
        (parkingType, parkingZoneIds) <- parkingTypesSubtree.toList
        parkingZoneId                 <- parkingZoneIds
      } yield {

        val parkingZone = stalls(parkingZoneId)
        val (pricingModel, feeInCents) = parkingZone.pricingModel match {
          case None     => ("", "")
          case Some(pm) => (s"$pm", s"${pm.costInDollars / 100.0}")
        }
        val chargingPoint = parkingZone.chargingPointType match {
          case None     => "NoCharger"
          case Some(cp) => s"$cp"
        }
        val parkingZoneName = parkingZone.parkingZoneName.getOrElse("")
        val landCostInUSDPerSqft = parkingZone.landCostInUSDPerSqft.getOrElse("")

        s"$tazId,$parkingType,$pricingModel,$chargingPoint,${parkingZone.maxStalls},$feeInCents,$parkingZoneName,$landCostInUSDPerSqft"
      }
    } match {
      case Failure(e) =>
        throw new RuntimeException(s"failed while converting parking configuration to csv format.\n$e")
      case Success(rows) =>
        val newlineFormattedCSVOutput: String = (List(ParkingFileHeader) ::: rows).mkString("\n")
        Try {
          destinationFile.getParentFile.mkdirs()
          val writer = IOUtils.getBufferedWriter(writeDestinationPath)
          writer.write(newlineFormattedCSVOutput)
          writer.close()
        } match {
          case Failure(e) =>
            throw new IOException(s"failed while writing parking configuration to file $writeDestinationPath.\n$e")
          case Success(_) =>
        }
    }
  }

  /**
    * loads taz parking data from file, creating an array of parking zones along with a search tree to find zones.
    *
    * the Array[ParkingZone] should be a private member of at most one Actor to prevent race conditions.
    *
    * @param filePath location in FS of taz parking data file (.csv)
    * @param header whether or not the file is expected to have a csv header row
    * @return table and tree
    */
  // TODO: this is redundant. clients can call fromFileToAccumulator
  def fromFile[GEO: GeoLevel](
    filePath: String,
    rand: Random,
    parkingStallCountScalingFactor: Double = 1.0,
    parkingCostScalingFactor: Double = 1.0,
    header: Boolean = true, // IT IS ALWAYS TRUe
    vehicleManagerId: Option[Id[VehicleManager]]
  ): (Array[ParkingZone[GEO]], ZoneSearchTree[GEO]) = {
    val parkingLoadingAccumulator =
      fromFileToAccumulator(
        filePath,
        rand,
        parkingStallCountScalingFactor,
        parkingCostScalingFactor,
        header,
        vehicleManagerId
      )
    (parkingLoadingAccumulator.zones.toArray, parkingLoadingAccumulator.tree)
  }

  /**
    * Loads taz parking data from file, creating a parking zone accumulator
    * This method allows to read multiple parking files into a single array of parking zones
    * along with a single search tree to find zones
    *
    * @param filePath location in FS of taz parking data file (.csv)
    * @param header   whether or not the file is expected to have a csv header row
    * @return parking zone accumulator
    */
  def fromFileToAccumulator[GEO: GeoLevel](
    filePath: String,
    rand: Random,
    parkingStallCountScalingFactor: Double = 1.0,
    parkingCostScalingFactor: Double = 1.0,
    header: Boolean = true,
    vehicleManagerId: Option[Id[VehicleManager]],
    parkingLoadingAcc: ParkingLoadingAccumulator[GEO] = ParkingLoadingAccumulator[GEO]()
  ): ParkingLoadingAccumulator[GEO] = {
    Try {
      val reader: BufferedReader = FileUtils.getReader(filePath)
      CSVReader.open(reader).iteratorWithHeaders
    } match {
      case Success(value) =>
        val reader = value.asInstanceOf[Iterator[Map[String, String]]]
        val parkingLoadingAccumulator: ParkingLoadingAccumulator[GEO] =
          fromBufferedReader(
            reader = reader,
            rand = rand,
            parkingStallCountScalingFactor = parkingStallCountScalingFactor,
            parkingCostScalingFactor = parkingCostScalingFactor,
            vehicleManagerId = vehicleManagerId,
            parkingLoadingAccumulator = parkingLoadingAcc
          )
        logger.info(
          s"loaded ${parkingLoadingAccumulator.totalRows} rows as parking zones from $filePath, with ${parkingLoadingAccumulator.parkingStallsPlainEnglish} stalls (${parkingLoadingAccumulator.totalParkingStalls}) in system"
        )
        if (parkingLoadingAccumulator.someRowsFailed) {
          logger.warn(s"${parkingLoadingAccumulator.failedRows} rows of parking data failed to load")
        }
        parkingLoadingAccumulator
      case Failure(e) =>
        throw new java.io.IOException(s"Unable to load parking configuration file with path $filePath.\n$e")
    }
  }

  /**
    * loads taz parking data from file, creating a lookup table of stalls along with a search tree to find stalls
    *
    * @param reader a java.io.BufferedReader of a csv file
    * @return ParkingZone array and tree lookup
    */
  private def fromBufferedReader[GEO: GeoLevel](
    reader: Iterator[Map[String, String]],
    rand: Random,
    parkingStallCountScalingFactor: Double = 1.0,
    parkingCostScalingFactor: Double = 1.0,
    vehicleManagerId: Option[Id[VehicleManager]],
    parkingLoadingAccumulator: ParkingLoadingAccumulator[GEO] = ParkingLoadingAccumulator()
  ): ParkingLoadingAccumulator[GEO] = {

    @tailrec
    def _read(
      accumulator: ParkingLoadingAccumulator[GEO]
    ): ParkingLoadingAccumulator[GEO] = {
      if (reader.hasNext) {
        val csvRow = reader.next()
        val updatedAccumulator = parseParkingZoneFromRow(
          csvRow = csvRow,
          nextParkingZoneId = accumulator.nextParkingZoneId,
          rand = rand,
          parkingStallCountScalingFactor = parkingStallCountScalingFactor,
          parkingCostScalingFactor = parkingCostScalingFactor,
          maybeVehicleManagerId = vehicleManagerId
        ) match {
          case None =>
            accumulator.countFailedRow
          case Some(row: ParkingLoadingDataRow[GEO]) =>
            addStallToSearch(row, accumulator)
        }
        _read(updatedAccumulator)
      } else {
        accumulator
      }
    }
    _read(parkingLoadingAccumulator)
  }

  /**
    * loads taz parking data from file, creating a lookup table of stalls along with a search tree to find stalls
    *
    * @param csvFileContents each line from a file to be read
    * @return table and search tree
    */
  def fromIterator[GEO: GeoLevel](
    csvFileContents: Iterator[Map[String, String]],
    random: Random = Random,
    parkingStallCountScalingFactor: Double = 1.0,
    parkingCostScalingFactor: Double = 1.0,
    header: Boolean = true,
    vehicleManagerId: Id[VehicleManager]
  ): ParkingLoadingAccumulator[GEO] = {

    val maybeWithoutHeader = if (header) csvFileContents.drop(1) else csvFileContents

    maybeWithoutHeader.foldLeft(ParkingLoadingAccumulator[GEO]()) { (accumulator, csvRow: Map[String, String]) =>
      Try {
        if (csvRow.isEmpty) accumulator
        else {
          parseParkingZoneFromRow(
            csvRow,
            accumulator.nextParkingZoneId,
            random,
            parkingStallCountScalingFactor,
            parkingCostScalingFactor,
            Some(vehicleManagerId)
          ) match {
            case None =>
              accumulator.countFailedRow
            case Some(row: ParkingLoadingDataRow[GEO]) =>
              addStallToSearch(row, accumulator)
          }
        }
      } match {
        case Success(updatedAccumulator) =>
          updatedAccumulator
        case Failure(e) =>
          logger.info(s"failed to load parking data row due to ${e.getMessage}. Original row: '$csvRow'")
          accumulator.countFailedRow
      }
    }
  }

  /**
    * Creating search tree to find stalls from a sequence of Parking zones
    *
    * @param zones each line from a file to be read
    * @return table and search tree
    */
  def createZoneSearchTree[GEO](zones: Seq[ParkingZone[GEO]]): ZoneSearchTree[GEO] = {

    zones.foldLeft(Map.empty: ZoneSearchTree[GEO]) { (accumulator, zone) =>
      val parkingTypes = accumulator.getOrElse(zone.geoId, Map())
      val parkingZoneIds: Vector[Int] = parkingTypes.getOrElse(zone.parkingType, Vector.empty[Int])

      accumulator.updated(
        zone.geoId,
        parkingTypes.updated(
          zone.parkingType,
          parkingZoneIds :+ zone.parkingZoneId
        )
      )
    }
  }

  implicit private class RowsExtensions(csvRow: Map[String, String]) {
    def mandatoryValue(column: String): String = {
      val result = csvRow.getOrElse(column, "").trim
      if (result.isEmpty) {
        throw new IllegalStateException(s"Could not find value for the field [$column]")
      } else {
        result
      }
    }

    def mandatoryValue(column: String, regex: Regex): String = {
      val result = mandatoryValue(column)

      result
    }
  }

  /**
    * parses a row of parking configuration into the data structures used to represent it
    * @param csvRow the comma-separated parking attributes
    * @return a ParkingZone and it's corresponding ParkingType and Taz Id
    */
  private def parseParkingZoneFromRow[GEO: GeoLevel](
//    csvRow: String,
    csvRow: Map[String, String],
    nextParkingZoneId: Int,
    rand: Random,
    parkingStallCountScalingFactor: Double = 1.0,
    parkingCostScalingFactor: Double = 1.0,
    maybeVehicleManagerId: Option[Id[VehicleManager]],
  ): Option[ParkingLoadingDataRow[GEO]] = {
//    (\w+),
    val tazString = csvRow.mandatoryValue("tazString")
//    (\w+),
    val parkingTypeString = csvRow.mandatoryValue("parkingTypeString")
//    (\w+),
    val pricingModelString = csvRow.mandatoryValue("pricingModelString")
//    (\w.+),
    val chargingTypeString = csvRow.mandatoryValue("chargingTypeString")
//    (\d+),
    val numStallsString = csvRow.mandatoryValue("numStallsString")
//    (\d+\.{0,1}\d*),?
    val feeInCentsString = csvRow.mandatoryValue("feeInCentsString")
//    ([\w\-]+)?,?
    val parkingZoneNameString = csvRow.getOrElse("parkingZoneNameString", "")
//    (\d+\.{0,1}\d*)?,?
    val landCostInUSDPerSqftString = csvRow.getOrElse("landCostInUSDPerSqftString", "")
//    ([\w\-]+)?
    val reservedForString = csvRow.getOrElse("reservedForString", "")
    Try {
      doSomething(
        nextParkingZoneId = nextParkingZoneId,
        rand = rand,
        parkingStallCountScalingFactor = parkingStallCountScalingFactor,
        parkingCostScalingFactor = parkingCostScalingFactor,
        maybeVehicleManagerId = maybeVehicleManagerId,
        tazString = tazString,
        parkingTypeString = parkingTypeString,
        pricingModelString = pricingModelString,
        chargingTypeString = chargingTypeString,
        numStallsString = numStallsString,
        feeInCentsString = feeInCentsString,
        parkingZoneNameString = parkingZoneNameString,
        landCostInUSDPerSqftString = landCostInUSDPerSqftString,
        reservedForString = reservedForString
      )

    } match {
      case Success(updatedAccumulator) =>
        Some { updatedAccumulator }
      case Failure(e) =>
        throw new java.io.IOException(s"Failed to load parking data from row with contents '$csvRow'.", e)
    }

  }

  private def doSomething[GEO: GeoLevel](
    nextParkingZoneId: Int,
    rand: Random,
    parkingStallCountScalingFactor: Double,
    parkingCostScalingFactor: Double,
    maybeVehicleManagerId: Option[Id[VehicleManager]],
    tazString: String,
    parkingTypeString: String,
    pricingModelString: String,
    chargingTypeString: String,
    numStallsString: String,
    feeInCentsString: String,
    parkingZoneNameString: String,
    landCostInUSDPerSqftString: String,
    reservedForString: String
  ): ParkingLoadingDataRow[GEO] = {
    val newCostInDollarsString = (feeInCentsString.toDouble * parkingCostScalingFactor / 100.0).toString
    val expectedNumberOfStalls = numStallsString.toDouble * parkingStallCountScalingFactor
    val floorNumberOfStalls = math.floor(expectedNumberOfStalls).toInt
    val numberOfStallsToCreate = if ((expectedNumberOfStalls % 1.0) > rand.nextDouble) {
      floorNumberOfStalls + 1
    } else {
      floorNumberOfStalls
    }
    val (vehicleManagerId, reservedFor) =
      maybeVehicleManagerId match {
        case Some(managerId) => (managerId, toCategories(reservedForString, tazString))
        case None if reservedForString == null || reservedForString.trim.isEmpty =>
          throw new IOException(s"No manager id set for $tazString")
        case None => (reservedForString.trim.createId[VehicleManager], IndexedSeq.empty)
      }

    // parse this row from the source file
    val taz = GeoLevel[GEO].parseId(tazString.toUpperCase)
    val parkingType = ParkingType(parkingTypeString)
    val pricingModel = PricingModel(pricingModelString, newCostInDollarsString)
    val chargingPoint = ChargingPointType(chargingTypeString)
    val numStalls = numberOfStallsToCreate
    val parkingZoneName =
      if (parkingZoneNameString == null || parkingZoneNameString.isEmpty) None else Some(parkingZoneNameString)
    val landCostInUSDPerSqft =
      if (landCostInUSDPerSqftString == null || landCostInUSDPerSqftString.isEmpty) None
      else Some(landCostInUSDPerSqftString.toDouble)
    val parkingZone =
      ParkingZone(
        nextParkingZoneId,
        taz,
        parkingType,
        numStalls,
        reservedFor,
        vehicleManagerId,
        chargingPoint,
        pricingModel,
        parkingZoneName,
        landCostInUSDPerSqft
      )

    ParkingLoadingDataRow(taz, parkingType, parkingZone)
  }

  private def toCategories(categoryName: String, geoId: String): IndexedSeq[VehicleCategory.VehicleCategory] = {
    (if (categoryName == null) "" else categoryName).trim.toLowerCase match {
      //we had Any, Ridehail and RideHailManager in the taz-parking.csv files
      //allow the users not to modify existing files
      case "" | "any" => IndexedSeq.empty
      case value =>
        val maybeCategories =
          value
            .split('|')
            .map(categoryStr => categoryStr -> VehicleCategory.fromStringOptional(categoryStr))
            .toIndexedSeq
        maybeCategories.foreach {
          case (categoryStr, maybeCategory) =>
            if (maybeCategory.isEmpty) {
              logger.error(s"Wrong category '$categoryStr' for zone $geoId, ignoring the category")
            }
        }
        maybeCategories.flatMap { case (_, maybeCategory) => maybeCategory }
    }
  }

  /**
    * a kind of lens-based update for the search tree
    *
    * @param row the row data we parsed from a file
    * @param accumulator the currently loaded zones and search tree
    * @return updated tree, stalls
    */
  private[ParkingZoneFileUtils] def addStallToSearch[GEO](
    row: ParkingLoadingDataRow[GEO],
    accumulator: ParkingLoadingAccumulator[GEO]
  ): ParkingLoadingAccumulator[GEO] = {

    // find any data stored already within this TAZ and with this ParkingType
    val parkingTypes = accumulator.tree.getOrElse(row.tazId, Map())
    val parkingZoneIds: Vector[Int] = parkingTypes.getOrElse(row.parkingType, Vector.empty[Int])

    // create new ParkingZone in array with new parkingZoneId. should this be an ArrayBuilder?
    accumulator.zones.append(row.parkingZone)

    // update the tree with the id of this ParkingZone
    accumulator.tree.put(
      row.tazId,
      parkingTypes.updated(
        row.parkingType,
        parkingZoneIds :+ row.parkingZone.parkingZoneId
      )
    )

    ParkingLoadingAccumulator(accumulator.zones, accumulator.tree, accumulator.totalRows + 1, accumulator.failedRows)
  }

  /**
    * generates ubiquitous parking from a taz centers file, such as test/input/beamville/taz-centers.csv
    * @param geoObjects geo objects that should be used to hold parking stalls
    * @param parkingTypes the parking types we are generating, by default, the complete set
    * @return
    */
  def generateDefaultParkingFromGeoObjects[GEO: GeoLevel](
    geoObjects: Iterable[GEO],
    random: Random,
    parkingTypes: Seq[ParkingType] = ParkingType.AllTypes,
    vehicleManagerId: Id[VehicleManager]
  ): (Array[ParkingZone[GEO]], ZoneSearchTree[GEO]) = {
    val parkingLoadingAccumulator =
      generateDefaultParkingAccumulatorFromGeoObjects(geoObjects, random, parkingTypes, vehicleManagerId)
    (parkingLoadingAccumulator.zones.toArray, parkingLoadingAccumulator.tree)
  }

  /**
    * generates ubiquitous parking from a taz centers file, such as test/input/beamville/taz-centers.csv
    * @param geoObjects geo objects that should be used to hold parking stalls
    * @param parkingTypes the parking types we are generating, by default, the complete set
    * @return the parking accumulator
    */
  def generateDefaultParkingAccumulatorFromGeoObjects[GEO: GeoLevel](
    geoObjects: Iterable[GEO],
    random: Random,
    parkingTypes: Seq[ParkingType] = ParkingType.AllTypes,
    vehicleManagerId: Id[VehicleManager]
  ): ParkingLoadingAccumulator[GEO] = {
    val result = generateDefaultParking(geoObjects, random, parkingTypes, vehicleManagerId)
    logger.info(
      s"generated ${result.totalRows} parking zones,one for each provided geo level, with ${result.parkingStallsPlainEnglish} stalls (${result.totalParkingStalls}) in system"
    )
    if (result.someRowsFailed) {
      logger.warn(s"${result.failedRows} rows of parking data failed to load")
    }
    result
  }

  /**
    * generates ubiquitous parking from the contents of a TAZ centers file
    * @param geoObjects an iterable of geo objects
    * @param parkingTypes the parking types we are generating, by default, the complete set
    * @return parking zones and parking search tree
    */
  def generateDefaultParking[GEO: GeoLevel](
    geoObjects: Iterable[GEO],
    random: Random,
    parkingTypes: Seq[ParkingType] = ParkingType.AllTypes,
    vehicleManagerId: Id[VehicleManager]
  ): ParkingLoadingAccumulator[GEO] = {

    val rows: Iterable[String] = for {
      geoObj      <- geoObjects
      parkingType <- parkingTypes
      // We have to pass parking types: Some(CustomChargingPoint) and None
      // None is `NoCharger` which will allow non-charger ParkingZones. Check `returnSpotsWithoutChargers` in `ZonalParkingManager`
      maybeChargingPoint <- Seq(Some(ChargingPointType.CustomChargingPoint("DCFast", "50", "DC")), None) // NoCharger
    } yield {
      import GeoLevel.ops._
      defaultParkingRow(geoObj.getId, parkingType, maybeChargingPoint)
    }

    val newIterator = buildIteratorWithoutHeader(rows)
    fromIterator(newIterator, random, header = false, vehicleManagerId = vehicleManagerId)
  }

  private val rowHeaders = ParkingFileHeader.split(",")

  def buildIteratorWithoutHeader(rows: Iterable[String]): Iterator[Map[String, String]] = {
    rows.iterator.map { line =>
      val allColumns: Array[String] = line.split(",")
      allColumns.zipWithIndex.map {
        case (columnValue: String, index: Int) =>
          rowHeaders(index) -> columnValue
      }.toMap
    }
  }

  /**
    * Write parking zones to csv.
    */
  def toCsv[GEO: GeoLevel](parkingZones: Array[ParkingZone[GEO]], filePath: String): Unit = {
    val fileContent = parkingZones
      .map { parkingZone =>
        List(
          parkingZone.geoId,
          parkingZone.parkingType,
          parkingZone.pricingModel.getOrElse(""),
          parkingZone.chargingPointType.getOrElse(""),
          parkingZone.maxStalls,
          parkingZone.pricingModel.map(_.costInDollars).getOrElse(""),
          parkingZone.parkingZoneName.getOrElse(""),
          parkingZone.parkingZoneId,
          parkingZone.landCostInUSDPerSqft.getOrElse("")
        ).mkString(",")
      }
      .mkString(System.lineSeparator())

    FileUtils.writeToFile(
      filePath,
      Some("taz,parkingType,pricingModel,chargingType,numStalls,feeInCents,name,parkingZoneId,landCostInUSDPerSqft"),
      fileContent,
      None
    )

  }

}
