package io.github.vrlmvg.nswtrains

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.readRawBytes
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.time.Instant

// Transport for NSW Open Data Trip Planner API:
// https://opendata.transport.nsw.gov.au/dataset/trip-planner-apis
private const val TFNSW_ROOT = "https://api.transport.nsw.gov.au"
private const val TFNSW_BASE = "$TFNSW_ROOT/v1/tp"
private const val TFNSW_API_VERSION = "10.2.1.42"
private const val TRIPS_PER_REQUEST = 6

// Product classes excluded when only trains and metro are wanted:
// light rail, bus, coach, ferry, school bus.
private val NON_RAIL_MODES = listOf(4, 5, 7, 9, 11)
private val RAIL_MODES = setOf(1, 2)

internal val SYDNEY: TimeZone = TimeZone.of("Australia/Sydney")

class MissingApiKeyException : Exception("No API key set.")
class InvalidApiKeyException : Exception("Transport for NSW rejected the API key.")
class TfnswApiException(message: String) : Exception(message)

class TfnswApi(private val apiKey: suspend () -> String?) {
    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
    }

    private val client = HttpClient(OkHttp) {
        expectSuccess = false
    }

    suspend fun findStops(query: String, filter: TransportFilter): List<StopResult> {
        val body = request("stop_finder") {
            // "stop" here would mean name_sf is a stop ID; "any" searches by name.
            parameter("type_sf", "any")
            parameter("name_sf", query)
            parameter("TfNSWSF", "true")
        }
        return json.decodeFromString<StopFinderResponse>(body).toStopResults(filter)
    }

    suspend fun findJourneys(
        origin: StopRef,
        destination: StopRef,
        departAfter: Instant,
        filter: TransportFilter,
    ): List<Journey> {
        val local = departAfter.toLocalDateTime(SYDNEY)
        val body = request("trip") {
            parameter("depArrMacro", "dep")
            parameter("itdDate", "%04d%02d%02d".format(local.year, local.month.ordinal + 1, local.day))
            parameter("itdTime", "%02d%02d".format(local.hour, local.minute))
            parameter("type_origin", "any")
            parameter("name_origin", origin.id)
            parameter("type_destination", "any")
            parameter("name_destination", destination.id)
            parameter("calcNumberOfTrips", TRIPS_PER_REQUEST)
            parameter("TfNSWTR", "true")
            if (filter == TransportFilter.TrainsAndMetro) {
                parameter("excludedMeans", "checkbox")
                NON_RAIL_MODES.forEach { parameter("exclMOT_$it", "1") }
            }
        }
        return json.decodeFromString<TripResponse>(body).toJourneys()
    }

    /** Live positions from the GTFS-realtime feeds that can carry the legs' services. */
    suspend fun vehiclePositions(journey: Journey): List<VehiclePosition> {
        val key = apiKey()?.takeIf { it.isNotBlank() } ?: throw MissingApiKeyException()
        val modes = journey.vehicleLegs.map { it.mode }.toSet()
        val feeds = buildList {
            if (TransportMode.Train in modes) {
                add("v2/gtfs/vehiclepos/sydneytrains") // Sydney Trains and NSW TrainLink intercity
                add("v1/gtfs/vehiclepos/nswtrains") // NSW TrainLink regional
            }
            if (TransportMode.Metro in modes) add("v2/gtfs/vehiclepos/metro")
        }
        return feeds.flatMap { feed ->
            val response = client.get("$TFNSW_ROOT/$feed") { header(HttpHeaders.Authorization, "apikey $key") }
            when {
                response.status == HttpStatusCode.Unauthorized || response.status == HttpStatusCode.Forbidden ->
                    throw InvalidApiKeyException()
                !response.status.isSuccess() -> emptyList()
                else -> runCatching { GtfsRealtime.parseVehiclePositions(response.readRawBytes()) }.getOrDefault(emptyList())
            }
        }
    }

    fun close() {
        client.close()
    }

    private suspend fun request(endpoint: String, block: HttpRequestBuilder.() -> Unit): String {
        val key = apiKey()?.takeIf { it.isNotBlank() } ?: throw MissingApiKeyException()
        val response = client.get("$TFNSW_BASE/$endpoint") {
            header(HttpHeaders.Authorization, "apikey $key")
            parameter("outputFormat", "rapidJSON")
            parameter("coordOutputFormat", "EPSG:4326")
            parameter("version", TFNSW_API_VERSION)
            block()
        }
        when {
            response.status == HttpStatusCode.Unauthorized ||
                response.status == HttpStatusCode.Forbidden -> throw InvalidApiKeyException()
            !response.status.isSuccess() ->
                throw TfnswApiException("Transport for NSW returned HTTP ${response.status.value}.")
        }
        return response.bodyAsText()
    }
}

@Serializable
internal data class StopFinderResponse(
    val locations: List<ApiLocation> = emptyList(),
)

@Serializable
internal data class ApiLocation(
    val id: String? = null,
    val name: String? = null,
    val disassembledName: String? = null,
    val type: String? = null,
    val matchQuality: Int = 0,
    val isBest: Boolean = false,
    val modes: List<Int> = emptyList(),
    val parent: ApiParent? = null,
)

@Serializable
internal data class ApiParent(
    val name: String? = null,
    val disassembledName: String? = null,
    val type: String? = null,
)

@Serializable
internal data class TripResponse(
    val journeys: List<ApiJourney> = emptyList(),
)

@Serializable
internal data class ApiJourney(
    val legs: List<ApiLeg> = emptyList(),
)

@Serializable
internal data class ApiLeg(
    val origin: ApiStop? = null,
    val destination: ApiStop? = null,
    val transportation: ApiTransportation? = null,
    val stopSequence: List<ApiStop> = emptyList(),
    val infos: List<ApiInfo> = emptyList(),
    val isRealtimeControlled: Boolean = false,
    /** Track shape as [lat, lon] pairs. */
    val coords: List<List<Double>> = emptyList(),
)

@Serializable
internal data class ApiStop(
    val name: String? = null,
    val disassembledName: String? = null,
    val parent: ApiParent? = null,
    val departureTimePlanned: String? = null,
    val departureTimeEstimated: String? = null,
    val arrivalTimePlanned: String? = null,
    val arrivalTimeEstimated: String? = null,
    val coord: List<Double>? = null,
)

@Serializable
internal data class ApiTransportation(
    val name: String? = null,
    val disassembledName: String? = null,
    val number: String? = null,
    val product: ApiProduct? = null,
    val destination: ApiNamed? = null,
    val properties: ApiTransportProperties? = null,
)

@Serializable
internal data class ApiTransportProperties(
    @SerialName("RealtimeTripId") val realtimeTripId: String? = null,
)

@Serializable
internal data class ApiProduct(
    @SerialName("class") val productClass: Int? = null,
    val name: String? = null,
)

@Serializable
internal data class ApiNamed(
    val name: String? = null,
)

@Serializable
internal data class ApiInfo(
    val subtitle: String? = null,
    val priority: String? = null,
)

internal fun StopFinderResponse.toStopResults(filter: TransportFilter): List<StopResult> =
    locations
        .filter { it.type == "stop" && it.id != null }
        .filter { location ->
            filter == TransportFilter.AllModes ||
                location.modes.isEmpty() ||
                location.modes.any { it in RAIL_MODES }
        }
        .sortedWith(compareByDescending<ApiLocation> { it.isBest }.thenByDescending { it.matchQuality })
        .map { location ->
            val name = location.disassembledName ?: location.name.orEmpty()
            StopResult(
                id = location.id!!,
                name = name,
                area = location.parent?.name?.takeIf { it.isNotBlank() && it != name },
            )
        }

internal fun TripResponse.toJourneys(): List<Journey> =
    journeys.mapNotNull { journey ->
        val legs = journey.legs.mapNotNull { it.toLeg() }
        legs.takeIf { it.isNotEmpty() }?.let(::Journey)
    }

private fun ApiLeg.toLeg(): Leg? {
    val origin = origin ?: return null
    val destination = destination ?: return null
    val departurePlanned = origin.departureTimePlanned?.toInstantOrNull() ?: return null
    val arrivalPlanned = destination.arrivalTimePlanned?.toInstantOrNull() ?: return null
    val mode = TransportMode.fromProductClass(transportation?.product?.productClass)
    return Leg(
        mode = mode,
        line = transportation?.disassembledName?.takeIf { it.isNotBlank() },
        headsign = transportation?.destination?.name?.takeIf { it.isNotBlank() },
        originName = origin.stationName(),
        originPlatform = origin.platformName(),
        destinationName = destination.stationName(),
        destinationPlatform = destination.platformName(),
        departurePlanned = departurePlanned,
        departureEstimated = origin.departureTimeEstimated?.toInstantOrNull(),
        arrivalPlanned = arrivalPlanned,
        arrivalEstimated = destination.arrivalTimeEstimated?.toInstantOrNull(),
        stopCount = (stopSequence.size - 1).coerceAtLeast(0),
        isRealtime = isRealtimeControlled,
        alerts = infos
            .filter { it.priority !in QUIET_ALERT_PRIORITIES }
            .mapNotNull { it.subtitle?.trim()?.takeIf(String::isNotEmpty) }
            .distinct(),
        realtimeTripId = transportation?.properties?.realtimeTripId?.takeIf { it.isNotBlank() },
        stops = stopSequence.map { stop ->
            LegStop(
                name = stop.stationName(),
                point = stop.coord?.toGeoPoint(),
                time = (stop.departureTimeEstimated ?: stop.departureTimePlanned
                    ?: stop.arrivalTimeEstimated ?: stop.arrivalTimePlanned)?.toInstantOrNull(),
            )
        },
        track = coords.mapNotNull { it.toGeoPoint() },
    )
}

// General notices ("Opal can be used…", "Platforms may change…") rather than disruptions.
private val QUIET_ALERT_PRIORITIES = setOf("veryLow", "low")

private val PLATFORM_PATTERN = Regex("""\b(Platform|Stand|Wharf|Side)\s+[\w-]+""", RegexOption.IGNORE_CASE)

/**
 * "Central Station, Platform 18, Sydney" → "Central Station".
 * The API's own parent of a platform is the platform itself, so the name is split instead.
 */
internal fun ApiStop.stationName(): String {
    val full = listOfNotNull(name, disassembledName, parent?.name).firstOrNull { it.isNotBlank() }
        ?: return ""
    return full.split(",").first().trim().ifEmpty { full }
}

/** "Central Station, Platform 18, Sydney" → "Platform 18". */
internal fun ApiStop.platformName(): String? =
    listOfNotNull(disassembledName, name)
        .firstNotNullOfOrNull { PLATFORM_PATTERN.find(it)?.value }

private fun List<Double>.toGeoPoint(): GeoPoint? = if (size >= 2) GeoPoint(this[0], this[1]) else null

private fun String.toInstantOrNull(): Instant? = runCatching { Instant.parse(this) }.getOrNull()
