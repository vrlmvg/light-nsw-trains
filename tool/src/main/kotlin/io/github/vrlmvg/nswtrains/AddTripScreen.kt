package io.github.vrlmvg.nswtrains

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewModelScope
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.rememberKeyboardOptions
import com.thelightphone.sdk.ui.LightFullscreenModal
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightScrollView
import com.thelightphone.sdk.ui.LightTextInputEditor
import com.thelightphone.sdk.ui.gridUnitsAsDp
import com.thelightphone.sdk.ui.lightClickable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class TripEnd(val inputTitle: String, val resultsTitle: String) {
    From("From station", "Travelling from"),
    To("To station", "Travelling to"),
}

sealed interface AddTripMode {
    data class Input(val end: TripEnd) : AddTripMode
    data class Searching(val end: TripEnd) : AddTripMode
    data class Results(val end: TripEnd, val results: List<StopResult>) : AddTripMode
    data object Done : AddTripMode
}

data class AddTripState(
    val mode: AddTripMode = AddTripMode.Input(TripEnd.From),
    val origin: StopRef? = null,
    val inputSession: Int = 0,
    val error: String? = null,
)

class AddTripViewModel(private val store: TrainsStore) : LightViewModel<Unit>() {
    private val api = TfnswApi(store::currentApiKey)
    private val _state = MutableStateFlow(AddTripState())
    val state: StateFlow<AddTripState> = _state.asStateFlow()

    fun search(query: CharSequence) {
        val end = currentEnd()
        val text = query.toString().trim()
        if (text.length < 2) {
            _state.update { it.copy(error = "Type at least two letters of a station name.") }
            return
        }
        _state.update { it.copy(mode = AddTripMode.Searching(end)) }
        viewModelScope.launch {
            val result = runCatching { api.findStops(text, store.currentFilter()) }
            _state.update { state ->
                result.fold(
                    onSuccess = { stops ->
                        if (stops.isEmpty()) {
                            state.backToInput(end).copy(error = "No stations found for “$text”.")
                        } else {
                            state.copy(mode = AddTripMode.Results(end, stops.take(MAX_RESULTS)))
                        }
                    },
                    onFailure = { state.backToInput(end).copy(error = errorMessage(it)) },
                )
            }
        }
    }

    fun select(stop: StopResult) {
        val ref = StopRef(id = stop.id, name = stop.name)
        when (currentEnd()) {
            TripEnd.From -> _state.update { it.copy(origin = ref).backToInput(TripEnd.To) }
            TripEnd.To -> {
                val origin = _state.value.origin ?: return
                if (origin.id == ref.id) {
                    _state.update { it.copy(error = "Pick a different station to travel to.") }
                    return
                }
                viewModelScope.launch {
                    store.addTrip(SavedTrip(origin = origin, destination = ref))
                    _state.update { it.copy(mode = AddTripMode.Done) }
                }
            }
        }
    }

    fun dismissError() {
        _state.update { it.copy(error = null) }
    }

    override fun onBackPressed(): Boolean {
        val state = _state.value
        when (val mode = state.mode) {
            is AddTripMode.Results -> _state.update { it.backToInput(mode.end) }
            is AddTripMode.Input -> if (mode.end == TripEnd.To) {
                _state.update { it.copy(origin = null).backToInput(TripEnd.From) }
            } else {
                return false
            }
            is AddTripMode.Searching -> Unit
            AddTripMode.Done -> return false
        }
        return true
    }

    override fun onCleared() {
        api.close()
        super.onCleared()
    }

    private fun currentEnd(): TripEnd = when (val mode = _state.value.mode) {
        is AddTripMode.Input -> mode.end
        is AddTripMode.Searching -> mode.end
        is AddTripMode.Results -> mode.end
        AddTripMode.Done -> TripEnd.To
    }

    private fun AddTripState.backToInput(end: TripEnd) =
        copy(mode = AddTripMode.Input(end), inputSession = inputSession + 1)

    private companion object {
        const val MAX_RESULTS = 12
    }
}

class AddTripScreen(
    sealedActivity: SealedLightActivity,
    private val store: TrainsStore,
) : LightScreen<Unit, AddTripViewModel>(sealedActivity) {

    override val viewModelClass: Class<AddTripViewModel>
        get() = AddTripViewModel::class.java

    override fun createViewModel(): AddTripViewModel = AddTripViewModel(store)

    @Composable
    override fun Content() {
        val state by viewModel.state.collectAsState()
        val textFieldState = rememberTextFieldState("")
        val keyboardOptionsFlow = rememberKeyboardOptions()

        LaunchedEffect(state.inputSession) { textFieldState.clearText() }
        LaunchedEffect(state.mode) {
            if (state.mode == AddTripMode.Done) goBack()
        }

        TrainsTheme {
            when (val mode = state.mode) {
                is AddTripMode.Input -> LightTextInputEditor(
                    title = mode.end.inputTitle,
                    editorKey = state.inputSession,
                    state = textFieldState,
                    keyboardOptionsFlow = keyboardOptionsFlow,
                    onSubmit = viewModel::search,
                    onBack = { goBack() },
                    submitIcon = LightIcons.SEARCH,
                    singleLine = true,
                    initialCaps = true,
                    modifier = Modifier.fillMaxSize(),
                )
                is AddTripMode.Searching -> Column(modifier = Modifier.fillMaxSize()) {
                    BackTopBar(mode.end.resultsTitle, onBack = { goBack() })
                    CenteredMessage("Searching…")
                }
                is AddTripMode.Results -> Column(modifier = Modifier.fillMaxSize()) {
                    BackTopBar(mode.end.resultsTitle, onBack = { goBack() })
                    StopResults(
                        results = mode.results,
                        onSelect = viewModel::select,
                        modifier = Modifier.weight(1f),
                    )
                }
                AddTripMode.Done -> Unit
            }

            state.error?.let { LightFullscreenModal(message = it, onClose = viewModel::dismissError) }
        }
    }
}

@Composable
private fun StopResults(results: List<StopResult>, onSelect: (StopResult) -> Unit, modifier: Modifier) {
    LightScrollView(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 1f.gridUnitsAsDp()),
    ) {
        results.forEach { stop ->
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .lightClickable(onClick = { onSelect(stop) })
                    .padding(vertical = 0.5f.gridUnitsAsDp()),
            ) {
                CopyLine(stop.name)
                stop.area?.let { DetailLine(it) }
            }
        }
    }
}

internal fun errorMessage(error: Throwable): String = when (error) {
    is MissingApiKeyException -> "Add your Transport for NSW API key in Settings first."
    is InvalidApiKeyException -> "Transport for NSW rejected your API key. Check it in Settings."
    is TfnswApiException -> error.message ?: "Transport for NSW is unavailable right now."
    else -> "NSW Trains needs a network connection. Connect to Wi-Fi or mobile data and try again."
}
