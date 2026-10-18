package io.github.vrlmvg.nswtrains

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

class TfnswParsingTest {
    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }

    private val tripJson = """
    {
      "version": "10.2.1.42",
      "journeys": [{
        "legs": [
          {
            "origin": {
              "id": "2000338", "name": "Central Station, Platform 18, Sydney",
              "disassembledName": "Central Station, Platform 18", "type": "platform",
              "parent": { "id": "2000338", "name": "Central Station, Platform 18, Sydney",
                          "disassembledName": "Central Station, Platform 18", "type": "stop" },
              "departureTimePlanned": "2026-10-17T21:12:00Z",
              "departureTimeEstimated": "2026-10-17T21:14:00Z"
            },
            "destination": {
              "name": "Strathfield Station, Platform 3, Strathfield",
              "disassembledName": "Strathfield Station, Platform 3",
              "parent": { "name": "Strathfield Station, Platform 3, Strathfield",
                          "disassembledName": "Strathfield Station, Platform 3", "type": "stop" },
              "arrivalTimePlanned": "2026-10-17T21:24:00Z",
              "arrivalTimeEstimated": "2026-10-17T21:26:00Z"
            },
            "transportation": {
              "name": "Sydney Trains Network T1 North Shore & Western Line",
              "disassembledName": "T1", "number": "T1 North Shore & Western Line",
              "product": { "class": 1, "name": "Sydney Trains Network" },
              "destination": { "name": "Emu Plains via Strathfield" }
            },
            "stopSequence": [{}, {}, {}, {}],
            "infos": [
              { "subtitle": " Lifts out of service at Redfern ", "priority": "normal" },
              { "subtitle": "Platforms may change.", "priority": "veryLow" }
            ],
            "isRealtimeControlled": true
          },
          {
            "origin": { "name": "Strathfield Station, Platform 3, Strathfield",
                        "departureTimePlanned": "2026-10-17T21:26:00Z" },
            "destination": { "name": "Strathfield Station, Platform 7, Strathfield",
                             "arrivalTimePlanned": "2026-10-17T21:30:00Z" },
            "transportation": { "product": { "class": 100, "name": "footpath" } }
          },
          {
            "origin": { "name": "Strathfield Station, Platform 7, Strathfield",
                        "disassembledName": "Platform 7",
                        "departureTimePlanned": "2026-10-17T21:33:00Z" },
            "destination": { "name": "Hornsby Station, Platform 2, Hornsby",
                             "disassembledName": "Platform 2",
                             "arrivalTimePlanned": "2026-10-17T22:01:00Z" },
            "transportation": { "disassembledName": "T9", "product": { "class": 1 },
                                "destination": { "name": "Hornsby" } },
            "stopSequence": [{}, {}]
          }
        ]
      }]
    }
    """.trimIndent()

    @Test
    fun parsesJourneyLegs() {
        val journeys = json.decodeFromString<TripResponse>(tripJson).toJourneys()
        assertEquals(1, journeys.size)
        val journey = journeys.single()

        assertEquals(3, journey.legs.size)
        assertEquals(1, journey.changes)
        assertEquals(Instant.parse("2026-10-17T21:14:00Z"), journey.departure)
        assertEquals(Instant.parse("2026-10-17T22:01:00Z"), journey.arrival)

        val first = journey.legs[0]
        assertEquals(TransportMode.Train, first.mode)
        assertEquals("T1", first.line)
        assertEquals("Central Station", first.originName)
        assertEquals("Platform 18", first.originPlatform)
        assertEquals("Strathfield Station", first.destinationName)
        assertEquals("Platform 3", first.destinationPlatform)
        assertEquals(3, first.stopCount)
        assertEquals(2L, first.departureDelayMinutes)
        assertEquals(listOf("Lifts out of service at Redfern"), first.alerts)

        assertEquals(TransportMode.Walk, journey.legs[1].mode)
        // No parent stop: station name falls back to the first part of the full name.
        assertEquals("Hornsby Station", journey.legs[2].destinationName)
        assertEquals("Platform 2", journey.legs[2].destinationPlatform)
    }

    @Test
    fun summarisesJourney() {
        val journey = json.decodeFromString<TripResponse>(tripJson).toJourneys().single()
        assertEquals("T1 · Platform 18 · 1 change · 2 min late", journeySummary(journey))
    }

    @Test
    fun filtersStopsToRailWhenRequested() {
        val response = json.decodeFromString<StopFinderResponse>(
            """
            { "locations": [
              { "id": "street", "name": "Central Rd, Avalon", "type": "street", "matchQuality": 999, "modes": null },
              { "id": "10101100", "name": "Central Station, Sydney", "disassembledName": "Central Station",
                "type": "stop", "matchQuality": 950, "isBest": true, "modes": [1, 2, 5],
                "parent": { "name": "Sydney", "type": "locality" } },
              { "id": "200060", "name": "Central Station, Eddy Av, Stand D, Haymarket",
                "type": "stop", "matchQuality": 990, "modes": [5] },
              { "id": "x", "name": "Central Coast", "type": "locality", "matchQuality": 999 }
            ] }
            """.trimIndent(),
        )
        val rail = response.toStopResults(TransportFilter.TrainsAndMetro)
        assertEquals(listOf("10101100"), rail.map { it.id })
        assertEquals("Central Station", rail.single().name)
        assertEquals("Sydney", rail.single().area)

        // The API's best match comes first, then by match quality.
        val all = response.toStopResults(TransportFilter.AllModes)
        assertEquals(listOf("10101100", "200060"), all.map { it.id })
    }

    @Test
    fun formatsSydneyTimes() {
        // 21:12 UTC on 17 Oct 2026 is 8:12am AEDT (UTC+11).
        assertEquals("8:12am", formatClock(Instant.parse("2026-10-17T21:12:00Z")))
        assertEquals("12:05pm", formatClock(Instant.parse("2026-10-18T01:05:00Z")))
        assertEquals("12:00am", formatClock(Instant.parse("2026-10-17T13:00:00Z")))
    }

    @Test
    fun formatsCountdown() {
        val now = Instant.parse("2026-10-17T21:00:00Z")
        assertEquals("now", formatCountdown(Instant.parse("2026-10-17T21:00:30Z"), now))
        assertEquals("in 6 min", formatCountdown(Instant.parse("2026-10-17T21:06:00Z"), now))
        assertEquals("in 1 hr 5 min", formatCountdown(Instant.parse("2026-10-17T22:05:00Z"), now))
        assertEquals("departed", formatCountdown(Instant.parse("2026-10-17T20:58:00Z"), now))
    }

    @Test
    fun collapsesRoutesWithSameTimes() {
        fun leg(line: String, dep: String, arr: String) = Leg(
            mode = TransportMode.Train, line = line, headsign = null,
            originName = "A", originPlatform = null, destinationName = "B", destinationPlatform = null,
            departurePlanned = Instant.parse(dep), departureEstimated = null,
            arrivalPlanned = Instant.parse(arr), arrivalEstimated = null,
            stopCount = 1, isRealtime = false, alerts = emptyList(),
        )
        val viaCentral = Journey(listOf(
            leg("SHL", "2026-10-17T07:47:00Z", "2026-10-17T08:52:00Z"),
            leg("T8", "2026-10-17T08:58:00Z", "2026-10-17T09:58:00Z"),
            leg("T4", "2026-10-17T10:02:00Z", "2026-10-17T10:04:00Z"),
        ))
        val viaWolliCreek = Journey(listOf(
            leg("SHL", "2026-10-17T07:47:00Z", "2026-10-17T08:52:00Z"),
            leg("T8", "2026-10-17T08:58:00Z", "2026-10-17T10:04:00Z"),
        ))
        val later = Journey(listOf(leg("636", "2026-10-17T08:40:00Z", "2026-10-17T10:28:00Z")))

        val result = listOf(later, viaCentral, viaWolliCreek).withoutSameTimeRoutes()
        assertEquals(listOf(viaWolliCreek, later), result)
    }
}
