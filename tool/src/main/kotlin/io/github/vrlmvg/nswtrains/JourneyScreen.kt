package io.github.vrlmvg.nswtrains

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightBottomBar
import com.thelightphone.sdk.ui.LightIcon
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightScrollView
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.gridUnitsAsDp

/** Leg-by-leg breakdown of one journey. Display only, so no view model. */
class JourneyScreen(
    sealedActivity: SealedLightActivity,
    private val store: TrainsStore,
    private val journey: Journey,
) : SimpleLightScreen<Unit>(sealedActivity) {

    @Composable
    override fun Content() {
        TrainsTheme {
            Column(modifier = Modifier.fillMaxSize()) {
                BackTopBar(
                    title = "${formatClock(journey.departure)} → ${formatClock(journey.arrival)}",
                    onBack = { goBack() },
                )
                LightScrollView(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(start = 1f.gridUnitsAsDp()),
                ) {
                    journey.legs.forEachIndexed { index, leg ->
                        val previous = journey.legs.getOrNull(index - 1)
                        if (previous != null && !previous.mode.isWalk && !leg.mode.isWalk) {
                            ChangeGap(station = leg.originName, waitMinutes = (leg.departure - previous.arrival).inWholeMinutes)
                        }
                        if (leg.mode.isWalk) WalkLeg(leg) else VehicleLeg(leg)
                    }
                }
                if (journey.vehicleLegs.any { it.stops.size >= 2 }) {
                    LightBottomBar(
                        listOf(LightBarButton.Text("LIVE", onClick = {
                            navigateTo(screenFactory = { LiveScreen(it, store, journey) })
                        })),
                    )
                }
            }
        }
    }
}

@Composable
private fun VehicleLeg(leg: Leg) {
    Column(modifier = Modifier.padding(vertical = 0.5f.gridUnitsAsDp())) {
        BoldLine("${formatClock(leg.departure)}  ${leg.originName}")
        leg.originPlatform?.let { DetailLine(it) }

        // Small gap between boarding and getting off; the train details are on the trip list.
        Spacer(Modifier.height(0.75f.gridUnitsAsDp()))

        BoldLine("${formatClock(leg.arrival)}  ${leg.destinationName}")
        leg.destinationPlatform?.let { DetailLine(it) }

        leg.alerts.forEach { alert ->
            LightText(
                text = "Alert: $alert",
                variant = LightTextVariant.Fine,
                modifier = Modifier.padding(top = 0.5f.gridUnitsAsDp()),
            )
        }
    }
}

/** Whitespace between two trains, labelled with where to change and how long the wait is. */
@Composable
private fun ChangeGap(station: String, waitMinutes: Long) {
    DetailLine(
        text = "Change at $station · ${formatDuration(waitMinutes.coerceAtLeast(0))}",
        lighten = true,
        modifier = Modifier.padding(vertical = 1.5f.gridUnitsAsDp()),
    )
}

@Composable
private fun WalkLeg(leg: Leg) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(vertical = 1.5f.gridUnitsAsDp()),
    ) {
        LightIcon(
            icon = LightIcons.DIRECTIONS_PEDESTRIAN,
            size = 1.5f,
            modifier = Modifier.padding(end = 0.5f.gridUnitsAsDp()),
            contentDescription = "Walk",
        )
        DetailLine(
            "Walk ${formatDuration((leg.arrival - leg.departure).inWholeMinutes)} to ${leg.destinationName}",
            lighten = true,
        )
    }
}

