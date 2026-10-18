package io.github.vrlmvg.nswtrains

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewModelScope
import com.thelightphone.sdk.InitialScreen
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightBottomBar
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightScrollView
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import com.thelightphone.sdk.ui.gridUnitsAsDp
import com.thelightphone.sdk.ui.lightClickable
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

sealed interface HomeState {
    data object Loading : HomeState
    data object NeedsApiKey : HomeState
    data class Trips(val trips: List<SavedTrip>) : HomeState
}

class HomeViewModel(store: TrainsStore) : LightViewModel<Unit>() {
    val state: StateFlow<HomeState> = combine(store.apiKey, store.trips) { key, trips ->
        if (key == null) HomeState.NeedsApiKey else HomeState.Trips(trips)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, HomeState.Loading)
}

@InitialScreen
class HomeScreen(sealedActivity: SealedLightActivity) :
    LightScreen<Unit, HomeViewModel>(sealedActivity) {

    private val store by lazy { TrainsStore(lightContext.dataStore) }

    override val viewModelClass: Class<HomeViewModel>
        get() = HomeViewModel::class.java

    override fun createViewModel(): HomeViewModel = HomeViewModel(store)

    private fun openSettings() = navigateTo(screenFactory = { SettingsScreen(it, store) })

    private fun openAddTrip() = navigateTo(screenFactory = { AddTripScreen(it, store) })

    private fun openTrip(trip: SavedTrip) = navigateTo(screenFactory = { TripScreen(it, store, trip) })

    @Composable
    override fun Content() {
        val state by viewModel.state.collectAsState()
        TrainsTheme {
            Column(modifier = Modifier.fillMaxSize()) {
                LightTopBar(
                    center = LightTopBarCenter.Text("NSW Trains"),
                    modifier = Modifier.padding(bottom = 0.25f.gridUnitsAsDp()),
                )
                when (val s = state) {
                    HomeState.Loading -> CenteredMessage("Loading…")
                    HomeState.NeedsApiKey -> CenteredMessage(
                        message = "Add your free Transport for NSW Open Data API key to get started.",
                        action = "SET UP",
                        onAction = ::openSettings,
                    )
                    is HomeState.Trips -> if (s.trips.isEmpty()) {
                        CenteredMessage(
                            message = "No saved trips yet.",
                            action = "ADD A TRIP",
                            onAction = ::openAddTrip,
                        )
                    } else {
                        TripList(trips = s.trips, onSelect = ::openTrip, modifier = Modifier.weight(1f))
                    }
                }
                LightBottomBar(
                    items = listOf(
                        LightBarButton.LightIcon(
                            icon = LightIcons.SETTINGS,
                            onClick = ::openSettings,
                            contentDescription = "Settings",
                        ),
                        if (state is HomeState.Trips) {
                            LightBarButton.LightIcon(
                                icon = LightIcons.ADD,
                                onClick = ::openAddTrip,
                                contentDescription = "Add trip",
                            )
                        } else {
                            null
                        },
                    ),
                )
            }
        }
    }
}

@Composable
private fun TripList(trips: List<SavedTrip>, onSelect: (SavedTrip) -> Unit, modifier: Modifier) {
    LightScrollView(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 1f.gridUnitsAsDp()),
    ) {
        trips.forEach { trip ->
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .lightClickable(onClick = { onSelect(trip) })
                    .padding(vertical = 0.5f.gridUnitsAsDp()),
            ) {
                BoldLine(trip.origin.name)
                DetailLine("to ${trip.destination.name}")
            }
        }
    }
}
