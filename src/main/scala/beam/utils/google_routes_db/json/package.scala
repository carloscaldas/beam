package beam.utils.google_routes_db

import io.circe.Decoder

package object json {

  case class GoogleRoutes(
    geocodedWaypoints: Seq[GeocodedWaypoint],
    routes: Seq[GoogleRoute],
    status: String
  )

  case class GeocodedWaypoint(
    geocoderStatus: String
    // Note: partial definition
  )

  case class GoogleRoute(
    bounds: GoogleRoute.Bounds,
    copyrights: String,
    legs: Seq[GoogleRoute.Leg],
    summary: String
  )

  object GoogleRoute {

    case class Coord(lat: Double, lng: Double)

    case class ValueTxt(value: Int, text: String)

    case class Bounds(
      northeast: Coord,
      southwest: Coord
    )

    case class Leg(
      distance: ValueTxt,
      duration: ValueTxt,
      durationInTraffic: Option[ValueTxt],
      endAddress: String,
      endLocation: Coord,
      startAddress: String,
      startLocation: Coord,
      steps: Seq[Step]
      // Note: partial definition
    )

    case class Step(
      distance: ValueTxt,
      duration: ValueTxt,
      endLocation: Coord,
      startLocation: Coord,
      travelMode: String
      // Note: partial definition
    )
  }

  implicit val coordDecoder: Decoder[GoogleRoute.Coord] =
    Decoder.forProduct2("lat", "lng")(GoogleRoute.Coord.apply)

  implicit val boundsDecoder: Decoder[GoogleRoute.Bounds] =
    Decoder.forProduct2("northeast", "southwest")(GoogleRoute.Bounds.apply)

  implicit val valueTxtDecoder: Decoder[GoogleRoute.ValueTxt] =
    Decoder.forProduct2("value", "text")(GoogleRoute.ValueTxt.apply)

  implicit val stepDecoder: Decoder[GoogleRoute.Step] =
    Decoder.forProduct5(
      "distance",
      "duration",
      "end_location",
      "start_location",
      "travel_mode",
    )(GoogleRoute.Step.apply)

  implicit val legDecoder: Decoder[GoogleRoute.Leg] =
    Decoder.forProduct8(
      "distance",
      "duration",
      "duration_in_traffic",
      "end_address",
      "end_location",
      "start_address",
      "start_location",
      "steps"
    )(GoogleRoute.Leg.apply)

  implicit val googleRouteDecoder: Decoder[GoogleRoute] =
    Decoder.forProduct4(
      "bounds",
      "copyrights",
      "legs",
      "summary"
    )(GoogleRoute.apply)

  implicit val geocodedWaypointDecoder: Decoder[GeocodedWaypoint] =
    Decoder.forProduct1("geocoder_status")(GeocodedWaypoint.apply)

  implicit val googleRoutesDecoder: Decoder[GoogleRoutes] =
    Decoder.forProduct3(
      "geocoded_waypoints",
      "routes",
      "status"
    )(GoogleRoutes.apply)
}