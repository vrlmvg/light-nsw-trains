package io.github.vrlmvg.nswtrains

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json

class TrainsStore(private val dataStore: DataStore<Preferences>) {
    private val json = Json { ignoreUnknownKeys = true }

    val apiKey: Flow<String?> = dataStore.data.map { it[API_KEY]?.takeIf(String::isNotBlank) }

    val trips: Flow<List<SavedTrip>> = dataStore.data.map { prefs ->
        prefs[TRIPS_JSON]
            ?.let { runCatching { json.decodeFromString<List<SavedTrip>>(it) }.getOrNull() }
            .orEmpty()
    }

    val filter: Flow<TransportFilter> = dataStore.data.map { prefs ->
        prefs[FILTER]
            ?.let { name -> TransportFilter.entries.firstOrNull { it.name == name } }
            ?: TransportFilter.TrainsAndMetro
    }

    suspend fun currentApiKey(): String? = apiKey.first()

    suspend fun currentFilter(): TransportFilter = filter.first()

    suspend fun setApiKey(key: String) {
        dataStore.edit { it[API_KEY] = key.trim() }
    }

    suspend fun setFilter(filter: TransportFilter) {
        dataStore.edit { it[FILTER] = filter.name }
    }

    suspend fun addTrip(trip: SavedTrip) {
        editTrips { trips -> trips.filterNot { it.key == trip.key } + trip }
    }

    suspend fun removeTrip(trip: SavedTrip) {
        editTrips { trips -> trips.filterNot { it.key == trip.key } }
    }

    private suspend fun editTrips(transform: (List<SavedTrip>) -> List<SavedTrip>) {
        dataStore.edit { prefs ->
            val current = prefs[TRIPS_JSON]
                ?.let { runCatching { json.decodeFromString<List<SavedTrip>>(it) }.getOrNull() }
                .orEmpty()
            prefs[TRIPS_JSON] = json.encodeToString(transform(current))
        }
    }

    private companion object {
        val API_KEY = stringPreferencesKey("tfnsw_api_key")
        val TRIPS_JSON = stringPreferencesKey("saved_trips")
        val FILTER = stringPreferencesKey("transport_filter")
    }
}
