package io.github.vrlmvg.nswtrains

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

class LiveLineTest {

    @Test
    fun decodesRealSydneyTrainsFeed() {
        // Captured from v2/gtfs/vehiclepos/sydneytrains; expected values read with Google's gtfs-realtime-bindings.
        val vehicles = GtfsRealtime.parseVehiclePositions(File("src/test/resources/vehiclepos-sydneytrains.pb").readBytes())
        assertEquals(191, vehicles.size)
        val v = vehicles.first { it.tripId == "W787.2013.105.128.D.6.92230344" }
        assertEquals("BMT_1", v.routeId)
        assertEquals(-33.72747039794922, v.lat, 1e-9)
        assertEquals(150.49066162109375, v.lon, 1e-9)
        assertEquals("20:24 Central Station to Lithgow Station", v.label)
        assertEquals(1792234518L, v.timestamp)
    }

    private fun stop(name: String, lat: Double) = LegStop(name, GeoPoint(lat, 151.0), Instant.parse("2026-10-17T10:00:00Z"))

    private fun leg(tripId: String, vararg stops: LegStop) = Leg(
        mode = TransportMode.Train, line = "T1", headsign = null,
        originName = stops.first().name, originPlatform = null,
        destinationName = stops.last().name, destinationPlatform = null,
        departurePlanned = Instant.parse("2026-10-17T10:00:00Z"), departureEstimated = null,
        arrivalPlanned = Instant.parse("2026-10-17T10:30:00Z"), arrivalEstimated = null,
        stopCount = stops.size - 1, isRealtime = true, alerts = emptyList(),
        realtimeTripId = tripId, stops = stops.toList(),
    )

    @Test
    fun placesTrainsBetweenTheRightStations() {
        // A straight north-south line; 0.01° latitude ≈ 1.1 km.
        val journey = Journey(listOf(leg("mine", stop("A", -33.90), stop("B", -33.88), stop("C", -33.86))))
        val now = 1_000_000L
        val vehicles = listOf(
            VehiclePosition("mine", "T1", -33.87, 151.0, "", now),             // between B and C
            VehiclePosition("other", "T1", -33.89, 151.0005, "10:00 X to Y Station", now), // between A and B, ~50 m off
            VehiclePosition("far", "T1", -33.89, 151.02, "", now),             // 1.8 km off the line
            VehiclePosition("stale", "T1", -33.89, 151.0, "", now - 3600),     // too old
        )
        val rows = LiveLine.rows(journey, vehicles, now)
        val text = rows.map {
            when (it) {
                is LiveRow.Station -> it.name
                is LiveRow.Train -> if (it.isMine) "ME" else it.description
                is LiveRow.Change -> it.text
            }
        }
        assertEquals(listOf("A", "Train to Y", "B", "ME", "C"), text)
        assertTrue((rows[0] as LiveRow.Station).kind == StationKind.Board)
        assertTrue((rows.last() as LiveRow.Station).kind == StationKind.Alight)
    }

    @Test
    fun yourTrainApproachingAndRegionalLabels() {
        val journey = Journey(listOf(leg("mine", stop("A", -33.90), stop("B", -33.88))))
        val now = 1_000_000L
        val vehicles = listOf(
            VehiclePosition("mine", "SHL", -34.35, 151.0, "09:07pm Goulburn - Campbelltown", now), // ~50 km south of A
            VehiclePosition("xpt", "ST21", -33.89, 151.0, "08:42pm (621)  Sydney - Melbourne", now),
            VehiclePosition("NonTimetabled.S710", "", -33.89, 151.0, "", now),
        )
        val rows = LiveLine.rows(journey, vehicles, now)
        val first = rows.first() as LiveRow.Train
        assertTrue(first.isMine && first.description.startsWith("Your train · 5") && first.description.endsWith("km away"), first.description)
        assertEquals(listOf("Train to Melbourne"), rows.filterIsInstance<LiveRow.Train>().filterNot { it.isMine }.map { it.description })
    }
}
