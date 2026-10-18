package io.github.vrlmvg.nswtrains

import kotlinx.serialization.Serializable
import kotlin.time.Instant

@Serializable
data class StopRef(
    val id: String,
    val name: String,
)

@Serializable
data class SavedTrip(
    val origin: StopRef,
    val destination: StopRef,
) {
    val key: String get() = "${origin.id}>${destination.id}"

    fun reversed(): SavedTrip = SavedTrip(origin = destination, destination = origin)
}

enum class TransportFilter(val label: String) {
    TrainsAndMetro("Trains & Metro"),
    AllModes("All transport");

    fun toggled(): TransportFilter = if (this == TrainsAndMetro) AllModes else TrainsAndMetro
}

data class StopResult(
    val id: String,
    val name: String,
    val area: String?,
)

enum class TransportMode {
    Train, Metro, LightRail, Bus, Coach, Ferry, SchoolBus, Walk, Other;

    val isWalk: Boolean get() = this == Walk

    companion object {
        // TfNSW product classes
        fun fromProductClass(productClass: Int?): TransportMode = when (productClass) {
            1 -> Train
            2 -> Metro
            4 -> LightRail
            5 -> Bus
            7 -> Coach
            9 -> Ferry
            11 -> SchoolBus
            99, 100 -> Walk
            else -> Other
        }
    }
}

data class Leg(
    val mode: TransportMode,
    /** Short line code, e.g. "T1". */
    val line: String?,
    /** Where the service is heading, e.g. "Hornsby via Strathfield". */
    val headsign: String?,
    val originName: String,
    val originPlatform: String?,
    val destinationName: String,
    val destinationPlatform: String?,
    val departurePlanned: Instant,
    val departureEstimated: Instant?,
    val arrivalPlanned: Instant,
    val arrivalEstimated: Instant?,
    val stopCount: Int,
    val isRealtime: Boolean,
    val alerts: List<String>,
    /** Matches trip_id in the GTFS-realtime vehicle feeds, when the service is tracked. */
    val realtimeTripId: String? = null,
    /** Every stop the service makes on this leg, boarding and alighting stops included. */
    val stops: List<LegStop> = emptyList(),
    /** The actual track shape for this leg. */
    val track: List<GeoPoint> = emptyList(),
) {
    val departure: Instant get() = departureEstimated ?: departurePlanned
    val arrival: Instant get() = arrivalEstimated ?: arrivalPlanned

    /** Minutes late at departure; null when there is no realtime data. */
    val departureDelayMinutes: Long?
        get() = departureEstimated?.let { (it - departurePlanned).inWholeMinutes }
}

data class GeoPoint(val lat: Double, val lon: Double)

data class LegStop(val name: String, val point: GeoPoint?, val time: Instant?)

data class Journey(val legs: List<Leg>) {
    val departure: Instant get() = legs.first().departure
    val arrival: Instant get() = legs.last().arrival
    val vehicleLegs: List<Leg> get() = legs.filterNot { it.mode.isWalk }
    val firstVehicleLeg: Leg? get() = vehicleLegs.firstOrNull()
    val changes: Int get() = (vehicleLegs.size - 1).coerceAtLeast(0)

    /** Identity for de-duplicating journeys across paged requests. */
    val key: String
        get() = legs.joinToString("|") { "${it.line}@${it.departurePlanned}" }
}
