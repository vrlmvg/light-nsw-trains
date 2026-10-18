package io.github.vrlmvg.nswtrains

import kotlin.math.cos
import kotlin.math.sqrt
import kotlin.time.Instant

/** A row of the live line diagram, top to bottom. */
sealed interface LiveRow {
    data class Station(val name: String, val time: Instant?, val kind: StationKind) : LiveRow
    data class Train(val description: String, val isMine: Boolean) : LiveRow
    data class Change(val text: String) : LiveRow
}

enum class StationKind { Board, Stop, Alight }

/**
 * Lays out a journey's stations and the trains currently between them. A train is placed on a leg
 * when its GPS fix is within [MAX_OFFSET_M] of that leg's track; its distance along the track decides
 * which two stations it sits between.
 */
object LiveLine {
    private const val MAX_OFFSET_M = 300.0
    private const val MAX_AGE_S = 5 * 60

    fun rows(journey: Journey, vehicles: List<VehiclePosition>, nowEpochSeconds: Long): List<LiveRow> {
        val fresh = vehicles.filter {
            (it.timestamp == 0L || nowEpochSeconds - it.timestamp <= MAX_AGE_S) &&
                // Empty-running and yard movements, not passenger services.
                !it.tripId.startsWith("NonTimetabled")
        }
        val legs = journey.vehicleLegs.filter { it.stops.size >= 2 }
        val placed = mutableSetOf<String>()
        val rows = mutableListOf<LiveRow>()

        legs.forEachIndexed { legIndex, leg ->
            if (legIndex > 0) {
                val previous = legs[legIndex - 1]
                val wait = (leg.departure - previous.arrival).inWholeMinutes.coerceAtLeast(0)
                rows += LiveRow.Change("Change at ${leg.stops.first().name} · ${formatDuration(wait)}")
            }
            val track = Track(leg.track.ifEmpty { leg.stops.mapNotNull { it.point } })
            val stationAlong = leg.stops.map { stop -> stop.point?.let { track.project(it)?.first } }
                .let(::monotonic)

            // Your train first, so it is never claimed by another leg that shares the same track.
            val candidates = fresh.sortedByDescending { it.tripId == leg.realtimeTripId }
            val between = mutableMapOf<Int, MutableList<Pair<Double, LiveRow.Train>>>()
            for (vehicle in candidates) {
                val key = vehicle.tripId.ifEmpty { "${vehicle.lat},${vehicle.lon}" }
                if (key in placed) continue
                val (along, offset) = track.project(GeoPoint(vehicle.lat, vehicle.lon)) ?: continue
                if (offset > MAX_OFFSET_M) continue
                if (along < stationAlong.first() - 200 || along > stationAlong.last() + 200) continue
                val segment = stationAlong.indexOfLast { it <= along }.coerceIn(0, stationAlong.size - 2)
                val isMine = vehicle.tripId.isNotEmpty() && vehicle.tripId == leg.realtimeTripId
                placed += key
                between.getOrPut(segment) { mutableListOf() } += along to LiveRow.Train(describe(vehicle, isMine), isMine)
            }

            // Your train is in the feed but not on this stretch yet: say how far away it is.
            val mine = leg.realtimeTripId?.let { id -> fresh.firstOrNull { it.tripId == id } }
            val boarding = leg.stops.first().point
            if (mine != null && mine.tripId !in placed && boarding != null) {
                val km = distanceMetres(GeoPoint(mine.lat, mine.lon), boarding) / 1000
                if (km < 300) {
                    placed += mine.tripId
                    rows += LiveRow.Train("Your train · ${"%.0f".format(km)} km away", isMine = true)
                }
            }

            leg.stops.forEachIndexed { i, stop ->
                val kind = when (i) {
                    0 -> StationKind.Board
                    leg.stops.lastIndex -> StationKind.Alight
                    else -> StationKind.Stop
                }
                rows += LiveRow.Station(stop.name, stop.time, kind)
                if (i < leg.stops.lastIndex) {
                    between[i]?.sortedBy { it.first }?.forEach { rows += it.second }
                }
            }
        }
        return rows
    }

    /**
     * Sydney Trains labels: "20:24 Central Station to Lithgow Station";
     * NSW TrainLink labels: "08:49pm Moss Vale - Campbelltown", "08:42pm (621)  Sydney - Melbourne".
     */
    internal fun describe(vehicle: VehiclePosition, isMine: Boolean): String {
        if (isMine) return "Your train"
        val label = vehicle.label
        val destination = when {
            " to " in label -> label.substringAfterLast(" to ")
            " - " in label -> label.substringAfterLast(" - ")
            else -> ""
        }.replace(" Station", "").trim()
        return if (destination.isNotEmpty()) "Train to $destination" else "Train"
    }

    private fun distanceMetres(a: GeoPoint, b: GeoPoint): Double {
        val kx = 111_320.0 * cos(Math.toRadians((a.lat + b.lat) / 2))
        val dx = (a.lon - b.lon) * kx
        val dy = (a.lat - b.lat) * 110_540.0
        return sqrt(dx * dx + dy * dy)
    }

    /** Station distances must not go backwards (a stop's coordinate can project slightly behind the previous one). */
    private fun monotonic(values: List<Double?>): List<Double> {
        var last = 0.0
        return values.map { value -> maxOf(value ?: last, last).also { last = it } }
    }

    /** A polyline in local metres (equirectangular; plenty for a few hundred km). */
    private class Track(points: List<GeoPoint>) {
        private val lat0 = points.firstOrNull()?.lat ?: 0.0
        private val kx = 111_320.0 * cos(Math.toRadians(lat0))
        private val ky = 110_540.0
        private val xs = points.map { it.lon * kx }
        private val ys = points.map { it.lat * ky }
        private val cumulative = DoubleArray(points.size).also { c ->
            for (i in 1 until points.size) c[i] = c[i - 1] + dist(xs[i - 1], ys[i - 1], xs[i], ys[i])
        }

        /** Distance along the track of the closest point to [p], and how far [p] is from the track. */
        fun project(p: GeoPoint): Pair<Double, Double>? {
            if (xs.size < 2) return null
            val px = p.lon * kx
            val py = p.lat * ky
            var bestAlong = 0.0
            var bestOffset = Double.MAX_VALUE
            for (i in 0 until xs.size - 1) {
                val dx = xs[i + 1] - xs[i]
                val dy = ys[i + 1] - ys[i]
                val lengthSq = dx * dx + dy * dy
                val t = if (lengthSq == 0.0) 0.0 else (((px - xs[i]) * dx + (py - ys[i]) * dy) / lengthSq).coerceIn(0.0, 1.0)
                val cx = xs[i] + t * dx
                val cy = ys[i] + t * dy
                val offset = dist(px, py, cx, cy)
                if (offset < bestOffset) {
                    bestOffset = offset
                    bestAlong = cumulative[i] + t * sqrt(lengthSq)
                }
            }
            return bestAlong to bestOffset
        }

        private fun dist(x1: Double, y1: Double, x2: Double, y2: Double): Double {
            val dx = x2 - x1
            val dy = y2 - y1
            return sqrt(dx * dx + dy * dy)
        }
    }
}
