package io.github.vrlmvg.nswtrains

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.lifecycle.viewModelScope
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.gridUnitsAsDp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface WifiKeyUi {
    data object Starting : WifiKeyUi
    data class Waiting(val url: String) : WifiKeyUi
    data class Received(val key: String) : WifiKeyUi
    data class Failed(val message: String) : WifiKeyUi
}

class WifiKeyViewModel : LightViewModel<String>() {
    private var receiver: KeyReceiver? = null

    private val _ui = MutableStateFlow<WifiKeyUi>(WifiKeyUi.Starting)
    val ui: StateFlow<WifiKeyUi> = _ui.asStateFlow()

    init {
        viewModelScope.launch {
            val address = KeyReceiver.localAddress()
            if (address == null) {
                _ui.value = WifiKeyUi.Failed("Connect your phone to Wi-Fi first, then try again.")
                return@launch
            }
            val started = runCatching { withContext(Dispatchers.IO) { KeyReceiver() } }
            val server = started.getOrElse {
                _ui.value = WifiKeyUi.Failed("Couldn't start receiving. Try again.")
                return@launch
            }
            receiver = server
            val port = if (server.port == 80) "" else ":${server.port}"
            _ui.value = WifiKeyUi.Waiting("$address$port/${server.code}")
            _ui.value = runCatching { server.awaitKey() }.fold(
                onSuccess = { WifiKeyUi.Received(it) },
                onFailure = { error ->
                    WifiKeyUi.Failed(
                        if (error is TooManyWrongCodesException) {
                            "Too many wrong addresses were tried, so receiving stopped. Go back and try again."
                        } else {
                            "Receiving stopped. Go back and try again."
                        },
                    )
                },
            )
            server.close()
        }
    }

    override fun onCleared() {
        receiver?.close()
        super.onCleared()
    }
}

/** Receives the API key from a browser on a computer on the same Wi-Fi and returns it. */
class WifiKeyScreen(sealedActivity: SealedLightActivity) :
    LightScreen<String, WifiKeyViewModel>(sealedActivity) {

    override val viewModelClass: Class<WifiKeyViewModel>
        get() = WifiKeyViewModel::class.java

    override fun createViewModel(): WifiKeyViewModel = WifiKeyViewModel()

    @Composable
    override fun Content() {
        val ui by viewModel.ui.collectAsState()

        LaunchedEffect(ui) {
            (ui as? WifiKeyUi.Received)?.let { goBack(it.key) }
        }

        TrainsTheme {
            Column(modifier = Modifier.fillMaxSize()) {
                BackTopBar("Send from computer", onBack = { goBack() })
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(horizontal = 1f.gridUnitsAsDp()),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    when (val state = ui) {
                        WifiKeyUi.Starting, is WifiKeyUi.Received ->
                            LightText("…", variant = LightTextVariant.Copy, align = TextAlign.Center)
                        is WifiKeyUi.Failed ->
                            LightText(state.message, variant = LightTextVariant.Copy, align = TextAlign.Center)
                        is WifiKeyUi.Waiting -> {
                            LightText(
                                "On a computer on the same Wi-Fi, open",
                                variant = LightTextVariant.Copy,
                                align = TextAlign.Center,
                            )
                            LightText(
                                state.url,
                                variant = LightTextVariant.Heading,
                                align = TextAlign.Center,
                                modifier = Modifier.padding(vertical = 1f.gridUnitsAsDp()),
                            )
                            LightText(
                                "and paste your API key there.",
                                variant = LightTextVariant.Copy,
                                align = TextAlign.Center,
                            )
                        }
                    }
                }
                LightText(
                    "Keep this screen open until the key arrives.",
                    variant = LightTextVariant.Fine,
                    align = TextAlign.Center,
                    lighten = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(1f.gridUnitsAsDp()),
                )
            }
        }
    }
}
