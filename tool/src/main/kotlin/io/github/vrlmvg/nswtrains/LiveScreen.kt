package io.github.vrlmvg.nswtrains

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewModelScope
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightFullscreenModal
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightScrollView
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import com.thelightphone.sdk.ui.gridUnitsAsDp
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.time.Clock

private const val REFRESH_MS = 15_000L

class LiveViewModel(store: TrainsStore, private val journey: Journey) : LightViewModel<Unit>() {
    private val api = TfnswApi(store::currentApiKey)
    val rows = MutableStateFlow(LiveLine.rows(journey, emptyList(), 0))
    val updatedAt = MutableStateFlow<Long?>(null)
    val error = MutableStateFlow<String?>(null)
    private var loop: Job? = null

    override fun onScreenShow(screen: SimpleLightScreen<Unit>) {
        super.onScreenShow(screen)
        loop?.cancel()
        loop = viewModelScope.launch {
            while (isActive) {
                runCatching { api.vehiclePositions(journey) }
                    .onSuccess { vehicles ->
                        val now = Clock.System.now().epochSeconds
                        rows.value = LiveLine.rows(journey, vehicles, now)
                        updatedAt.value = now
                    }
                    .onFailure { if (updatedAt.value == null) error.value = errorMessage(it) }
                delay(REFRESH_MS)
            }
        }
    }

    override fun onScreenHide(screen: SimpleLightScreen<Unit>) {
        super.onScreenHide(screen)
        loop?.cancel()
    }

    fun dismissError() { error.value = null }

    override fun onCleared() {
        api.close()
        super.onCleared()
    }
}

/** Your journey's stations as a line, with trains (yours ●, others ○) between them, updated live. */
class LiveScreen(
    sealedActivity: SealedLightActivity,
    private val store: TrainsStore,
    private val journey: Journey,
) : LightScreen<Unit, LiveViewModel>(sealedActivity) {

    override val viewModelClass: Class<LiveViewModel>
        get() = LiveViewModel::class.java

    override fun createViewModel() = LiveViewModel(store, journey)

    @Composable
    override fun Content() {
        val rows by viewModel.rows.collectAsState()
        val updatedAt by viewModel.updatedAt.collectAsState()
        val error by viewModel.error.collectAsState()
        var now by remember { mutableStateOf(Clock.System.now().epochSeconds) }
        LaunchedEffect(Unit) { while (true) { delay(1_000); now = Clock.System.now().epochSeconds } }

        val scroll = rememberScrollState()
        var myTrainY by remember { mutableStateOf<Int?>(null) }
        var scrolledToMine by remember { mutableStateOf(false) }
        LaunchedEffect(myTrainY) {
            val y = myTrainY ?: return@LaunchedEffect
            if (!scrolledToMine) {
                scroll.scrollTo((y - 300).coerceAtLeast(0))
                scrolledToMine = true
            }
        }

        TrainsTheme {
            Column(modifier = Modifier.fillMaxSize()) {
                LightTopBar(
                    leftButton = LightBarButton.LightIcon(icon = LightIcons.BACK, onClick = { goBack() }, contentDescription = "Back"),
                    center = LightTopBarCenter.TwoLineDetail(
                        line1 = "Live trains",
                        line2 = updatedAt?.let { "updated ${(now - it).coerceAtLeast(0)}s ago" } ?: "locating trains…",
                    ),
                    modifier = Modifier.padding(bottom = 0.25f.gridUnitsAsDp()),
                )
                LightScrollView(
                    scrollState = scroll,
                    modifier = Modifier.weight(1f).fillMaxWidth().padding(start = 0.5f.gridUnitsAsDp()),
                ) {
                    rows.forEach { row ->
                        LiveRowView(row, onMineMeasured = { myTrainY = it })
                    }
                    if (updatedAt != null && rows.none { it is LiveRow.Train }) {
                        DetailLine(
                            "No trains on this route are reporting their position right now.",
                            lighten = true,
                            modifier = Modifier.padding(top = 1f.gridUnitsAsDp(), end = 1f.gridUnitsAsDp()),
                        )
                    }
                }
            }
            error?.let { LightFullscreenModal(message = it, onClose = viewModel::dismissError) }
        }
    }
}

@Composable
private fun LiveRowView(row: LiveRow, onMineMeasured: (Int) -> Unit) {
    val color = LightThemeTokens.colors.content
    val faint = color.copy(alpha = 0.45f)
    val background = LightThemeTokens.colors.background
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .then(
                if (row is LiveRow.Train && row.isMine) {
                    Modifier.onGloballyPositioned { onMineMeasured(it.positionInParent().y.toInt()) }
                } else {
                    Modifier
                },
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // The rail: a vertical line through every row, with a marker for stations and trains.
        Box(
            modifier = Modifier
                .width(2f.gridUnitsAsDp())
                .fillMaxHeight()
                .drawBehind {
                    val x = size.width / 2
                    val railColor = if (row is LiveRow.Change) faint else color
                    drawLine(railColor, Offset(x, 0f), Offset(x, size.height), strokeWidth = 2.dp.toPx())
                    val c = Offset(x, size.height / 2)
                    when (row) {
                        is LiveRow.Station -> when (row.kind) {
                            StationKind.Stop -> drawCircle(color, radius = 4.dp.toPx(), center = c)
                            else -> drawCircle(color, radius = 6.dp.toPx(), center = c)
                        }
                        is LiveRow.Train -> if (row.isMine) {
                            drawCircle(color, radius = 9.dp.toPx(), center = c)
                        } else {
                            drawCircle(background, radius = 7.dp.toPx(), center = c)
                            drawCircle(color, radius = 7.dp.toPx(), center = c, style = Stroke(width = 2.dp.toPx()))
                        }
                        is LiveRow.Change -> Unit
                    }
                },
        )
        Column(modifier = Modifier.padding(vertical = 0.4f.gridUnitsAsDp(), horizontal = 0.25f.gridUnitsAsDp())) {
            when (row) {
                is LiveRow.Station -> {
                    val label = row.time?.let { "${formatClock(it)}  ${row.name}" } ?: row.name
                    if (row.kind == StationKind.Stop) CopyLine(label) else BoldLine(label)
                }
                is LiveRow.Train -> if (row.isMine) BoldLine(row.description) else DetailLine(row.description, lighten = true)
                is LiveRow.Change -> DetailLine(row.text, lighten = true, modifier = Modifier.padding(vertical = 0.75f.gridUnitsAsDp()))
            }
        }
    }
}
