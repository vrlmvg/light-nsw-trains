package io.github.vrlmvg.nswtrains

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.lifecycle.viewModelScope
import com.thelightphone.sdk.LightQrCodeScanner
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.rememberKeyboardOptions
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightFullscreenModal
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextInputEditor
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.gridUnitsAsDp
import androidx.compose.foundation.background
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SettingsUi(
    val editingKey: Boolean = false,
    val checkingKey: Boolean = false,
    val message: String? = null,
)

class SettingsViewModel(private val store: TrainsStore) : LightViewModel<Unit>() {
    private val api = TfnswApi(store::currentApiKey)

    val apiKey: StateFlow<String?> = store.apiKey.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val filter: StateFlow<TransportFilter> =
        store.filter.stateIn(viewModelScope, SharingStarted.Eagerly, TransportFilter.TrainsAndMetro)

    private val _ui = MutableStateFlow(SettingsUi())
    val ui: StateFlow<SettingsUi> = _ui.asStateFlow()

    fun editKey() = _ui.update { it.copy(editingKey = true) }

    fun toggleFilter() {
        viewModelScope.launch { store.setFilter(filter.value.toggled()) }
    }

    /** Saves the key, then makes one cheap request to confirm Transport for NSW accepts it. */
    fun saveKey(raw: CharSequence) {
        val key = raw.toString().trim()
        if (key.isEmpty()) {
            _ui.update { it.copy(editingKey = false) }
            return
        }
        _ui.update { it.copy(editingKey = false, checkingKey = true) }
        viewModelScope.launch {
            store.setApiKey(key)
            val result = runCatching { api.findStops("Central", TransportFilter.AllModes) }
            _ui.update {
                it.copy(
                    checkingKey = false,
                    message = result.fold(
                        onSuccess = { "API key saved and working." },
                        onFailure = { error ->
                            if (error is InvalidApiKeyException) {
                                "Transport for NSW didn't accept that key. Check it and try again."
                            } else {
                                errorMessage(error)
                            }
                        },
                    ),
                )
            }
        }
    }

    fun dismissMessage() = _ui.update { it.copy(message = null) }

    override fun onBackPressed(): Boolean {
        if (_ui.value.editingKey) {
            _ui.update { it.copy(editingKey = false) }
            return true
        }
        return false
    }

    override fun onCleared() {
        api.close()
        super.onCleared()
    }
}

class SettingsScreen(
    sealedActivity: SealedLightActivity,
    private val store: TrainsStore,
) : LightScreen<Unit, SettingsViewModel>(sealedActivity) {

    override val viewModelClass: Class<SettingsViewModel>
        get() = SettingsViewModel::class.java

    override fun createViewModel(): SettingsViewModel = SettingsViewModel(store)

    private fun scanKey() {
        navigateTo(screenFactory = { ApiKeyScannerScreen(it) }) { scanned ->
            viewModel.saveKey(scanned)
        }
    }

    private fun receiveKeyOverWifi() {
        navigateTo(screenFactory = { WifiKeyScreen(it) }) { received ->
            viewModel.saveKey(received)
        }
    }

    private fun showKeyHelp() {
        navigateTo(screenFactory = { KeyHelpScreen(it) })
    }

    @Composable
    override fun Content() {
        val apiKey by viewModel.apiKey.collectAsState()
        val filter by viewModel.filter.collectAsState()
        val ui by viewModel.ui.collectAsState()
        val keyField = rememberTextFieldState("")
        val keyboardOptionsFlow = rememberKeyboardOptions()

        TrainsTheme {
            if (ui.editingKey) {
                LightTextInputEditor(
                    title = "API key",
                    state = keyField,
                    keyboardOptionsFlow = keyboardOptionsFlow,
                    onSubmit = viewModel::saveKey,
                    onBack = { goBack() },
                    submitLabel = "SAVE",
                    singleLine = true,
                    leftBottomBarItem = LightBarButton.LightIcon(
                        icon = LightIcons.CAMERA,
                        onClick = ::scanKey,
                        contentDescription = "Scan QR code",
                    ),
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Column(modifier = Modifier.fillMaxSize()) {
                    BackTopBar("Settings", onBack = { goBack() })
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .padding(horizontal = 1f.gridUnitsAsDp()),
                    ) {
                        SettingRow(
                            label = "Transport for NSW API key",
                            value = when {
                                ui.checkingKey -> "Checking…"
                                apiKey == null -> "Not set"
                                else -> "••••" + apiKey!!.takeLast(4)
                            },
                            onClick = viewModel::editKey,
                        )
                        SettingRow(
                            label = "Scan API key",
                            value = "From QR code",
                            onClick = ::scanKey,
                        )
                        SettingRow(
                            label = "Send API key",
                            value = "From a computer",
                            onClick = ::receiveKeyOverWifi,
                        )
                        SettingRow(
                            label = "No key yet?",
                            value = "How to get one",
                            onClick = ::showKeyHelp,
                        )
                        SettingRow(
                            label = "Show",
                            value = filter.label,
                            onClick = viewModel::toggleFilter,
                        )
                    }
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(1f.gridUnitsAsDp()),
                        contentAlignment = Alignment.Center,
                    ) {
                        LightText(
                            text = "Get a free key at opendata.transport.nsw.gov.au\n" +
                                "You can also enter your key via the Dashboard\n" +
                                "Timetable data © Transport for NSW",
                            variant = LightTextVariant.Fine,
                            align = TextAlign.Center,
                        )
                    }
                }
            }

            ui.message?.let { LightFullscreenModal(message = it, onClose = viewModel::dismissMessage) }
        }
    }
}

/** Scans a QR code containing the raw API key text and returns it. */
class ApiKeyScannerScreen(sealedActivity: SealedLightActivity) : SimpleLightScreen<String>(sealedActivity) {
    // The scanner can report the same code on several frames; only return once.
    private var returned = false

    @Composable
    override fun Content() {
        TrainsTheme {
            LightQrCodeScanner(
                title = "Scan API key",
                onScanned = { scanned ->
                    if (!returned) {
                        returned = true
                        goBack(scanned.trim())
                    }
                },
                onBack = { goBack() },
                modifier = Modifier.background(LightThemeTokens.colors.background),
            )
        }
    }
}
