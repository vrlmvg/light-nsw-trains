package io.github.vrlmvg.nswtrains

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewModelScope
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightBottomBar
import com.thelightphone.sdk.ui.LightFullscreenModal
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightScrollView
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import com.thelightphone.sdk.ui.gridUnitsAsDp
import com.thelightphone.sdk.ui.lightClickable
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

private val AUTO_REFRESH_INTERVAL = 30.seconds
private val CLOCK_TICK = 15.seconds

data class TripState(
    val trip: SavedTrip,
    val journeys: List<Journey> = emptyList(),
    val isLoading: Boolean = true,
    val isLoadingMore: Boolean = false,
    val now: Instant = Clock.System.now(),
    val confirmingDelete: Boolean = false,
    val error: String? = null,
    val deleted: Boolean = false,
)

class TripViewModel(
    private val store: TrainsStore,
    trip: SavedTrip,
) : LightViewModel<Unit>() {
    private val api = TfnswApi(store::currentApiKey)
    private val _state = MutableStateFlow(TripState(trip = trip))
    val state: StateFlow<TripState> = _state.asStateFlow()

    private var refreshLoop: Job? = null
    private var request: Job? = null

    override fun onScreenShow(screen: SimpleLightScreen<Unit>) {
        super.onScreenShow(screen)
        refreshLoop?.cancel()
        refreshLoop = viewModelScope.launch {
            var sinceRefresh = AUTO_REFRESH_INTERVAL
            while (isActive) {
                if (sinceRefresh >= AUTO_REFRESH_INTERVAL) {
                    refresh(showLoading = _state.value.journeys.isEmpty())
                    sinceRefresh = 0.seconds
                }
                _state.update { it.copy(now = Clock.System.now()) }
                delay(CLOCK_TICK)
                sinceRefresh += CLOCK_TICK
            }
        }
    }

    override fun onScreenHide(screen: SimpleLightScreen<Unit>) {
        super.onScreenHide(screen)
        refreshLoop?.cancel()
    }

    fun refresh(showLoading: Boolean = true) {
        request?.cancel()
        if (showLoading) _state.update { it.copy(isLoading = true) }
        request = viewModelScope.launch {
            val trip = _state.value.trip
            // Start slightly in the past so a train pulling in right now still shows.
            val from = Clock.System.now() - 2.minutes
            val result = runCatching {
                api.findJourneys(trip.origin, trip.destination, from, store.currentFilter())
            }
            _state.update { state ->
                result.fold(
                    onSuccess = { journeys ->
                        state.copy(
                            journeys = journeys.upcoming(Clock.System.now()).withoutSameTimeRoutes(),
                            isLoading = false,
                            now = Clock.System.now(),
                        )
                    },
                    onFailure = { state.copy(isLoading = false, error = errorMessage(it)) },
                )
            }
        }
    }

    fun loadLater() {
        val state = _state.value
        val last = state.journeys.lastOrNull() ?: return
        if (state.isLoadingMore) return
        _state.update { it.copy(isLoadingMore = true) }
        viewModelScope.launch {
            val result = runCatching {
                api.findJourneys(
                    state.trip.origin,
                    state.trip.destination,
                    last.departure + 1.minutes,
                    store.currentFilter(),
                )
            }
            _state.update { current ->
                result.fold(
                    onSuccess = { more ->
                        val known = current.journeys.map { it.key }.toSet()
                        current.copy(
                            journeys = (current.journeys + more.filterNot { it.key in known })
                                .withoutSameTimeRoutes(),
                            isLoadingMore = false,
                        )
                    },
                    onFailure = { current.copy(isLoadingMore = false, error = errorMessage(it)) },
                )
            }
        }
    }

    fun reverse() {
        _state.update { it.copy(trip = it.trip.reversed(), journeys = emptyList()) }
        refresh()
    }

    fun askDelete() {
        _state.update { it.copy(confirmingDelete = true) }
    }

    fun cancelDelete() {
        _state.update { it.copy(confirmingDelete = false) }
    }

    fun confirmDelete() {
        viewModelScope.launch {
            val trip = _state.value.trip
            // The trip may be shown reversed; remove whichever direction was saved.
            store.removeTrip(trip)
            store.removeTrip(trip.reversed())
            _state.update { it.copy(confirmingDelete = false, deleted = true) }
        }
    }

    fun dismissError() {
        _state.update { it.copy(error = null) }
    }

    override fun onBackPressed(): Boolean {
        if (_state.value.confirmingDelete) {
            cancelDelete()
            return true
        }
        return false
    }

    override fun onCleared() {
        api.close()
        super.onCleared()
    }
}

/** Drops journeys that have already left (allowing a minute of grace). */
internal fun List<Journey>.upcoming(now: Instant): List<Journey> =
    filter { it.departure >= now - 1.minutes }

/**
 * The planner often returns several routes that leave and arrive at the same times but change
 * at different stations. They look identical in the list, so keep only the one with fewest changes.
 */
internal fun List<Journey>.withoutSameTimeRoutes(): List<Journey> =
    groupBy { it.departure to it.arrival }
        .values
        .map { routes -> routes.minBy { it.changes } }
        .sortedBy { it.departure }

class TripScreen(
    sealedActivity: SealedLightActivity,
    private val store: TrainsStore,
    private val trip: SavedTrip,
) : LightScreen<Unit, TripViewModel>(sealedActivity) {

    override val viewModelClass: Class<TripViewModel>
        get() = TripViewModel::class.java

    override fun createViewModel(): TripViewModel = TripViewModel(store, trip)

    private fun openJourney(journey: Journey) =
        navigateTo(screenFactory = { JourneyScreen(it, store, journey) })

    @Composable
    override fun Content() {
        val state by viewModel.state.collectAsState()
        LaunchedEffect(state.deleted) {
            if (state.deleted) goBack()
        }
        TrainsTheme {
            if (state.confirmingDelete) {
                ConfirmDelete(
                    trip = state.trip,
                    onConfirm = viewModel::confirmDelete,
                    onCancel = viewModel::cancelDelete,
                )
                return@TrainsTheme
            }
            Column(modifier = Modifier.fillMaxSize()) {
                LightTopBar(
                    leftButton = LightBarButton.LightIcon(
                        icon = LightIcons.BACK,
                        onClick = { goBack() },
                        contentDescription = "Back",
                    ),
                    center = LightTopBarCenter.TwoLineDetail(
                        line1 = state.trip.origin.name,
                        line2 = "to ${state.trip.destination.name}",
                    ),
                    modifier = Modifier.padding(bottom = 0.25f.gridUnitsAsDp()),
                )
                when {
                    state.isLoading && state.journeys.isEmpty() -> CenteredMessage("Checking timetable…")
                    state.journeys.isEmpty() -> CenteredMessage(
                        message = "No upcoming services found.",
                        action = "TRY AGAIN",
                        onAction = { viewModel.refresh() },
                    )
                    else -> JourneyList(
                        state = state,
                        onSelect = ::openJourney,
                        onLoadLater = viewModel::loadLater,
                        modifier = Modifier.weight(1f),
                    )
                }
                LightBottomBar(
                    items = listOf(
                        LightBarButton.LightIcon(
                            icon = LightIcons.TRASH,
                            onClick = viewModel::askDelete,
                            contentDescription = "Delete trip",
                        ),
                        LightBarButton.LightIcon(
                            icon = LightIcons.REVERSE_ORDER,
                            onClick = viewModel::reverse,
                            contentDescription = "Reverse direction",
                        ),
                        LightBarButton.LightIcon(
                            icon = LightIcons.REFRESH,
                            onClick = { viewModel.refresh() },
                            contentDescription = "Refresh",
                        ),
                    ),
                )
            }
            state.error?.let { LightFullscreenModal(message = it, onClose = viewModel::dismissError) }
        }
    }
}

@Composable
private fun JourneyList(
    state: TripState,
    onSelect: (Journey) -> Unit,
    onLoadLater: () -> Unit,
    modifier: Modifier,
) {
    LightScrollView(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 1f.gridUnitsAsDp()),
    ) {
        state.journeys.forEach { journey ->
            val minutes = (journey.arrival - journey.departure).inWholeMinutes
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .lightClickable(onClick = { onSelect(journey) })
                    .padding(bottom = LIST_ITEM_GAP_UNITS.gridUnitsAsDp()),
            ) {
                BoldLine("${formatClock(journey.departure)} → ${formatClock(journey.arrival)}")
                CopyLine("${formatCountdown(journey.departure, state.now)} · ${formatDuration(minutes)}")
                DetailLine(journeySummary(journey), lighten = true)
            }
        }
        LightText(
            text = if (state.isLoadingMore) "Loading…" else "LATER SERVICES",
            variant = LightTextVariant.Button,
            underline = !state.isLoadingMore,
            modifier = Modifier
                .lightClickable(enabled = !state.isLoadingMore, onClick = onLoadLater)
                .padding(bottom = 1f.gridUnitsAsDp()),
        )
    }
}

@Composable
private fun ConfirmDelete(trip: SavedTrip, onConfirm: () -> Unit, onCancel: () -> Unit) {
    Column(modifier = Modifier.fillMaxSize()) {
        CenteredMessage("Remove ${trip.origin.name} to ${trip.destination.name} from your trips?")
        LightBottomBar(
            items = listOf(
                LightBarButton.LightIcon(icon = LightIcons.CLOSE, onClick = onCancel, contentDescription = "Cancel"),
                LightBarButton.LightIcon(icon = LightIcons.TRASH, onClick = onConfirm, contentDescription = "Remove"),
            ),
        )
    }
}
