package io.github.vrlmvg.nswtrains

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightBottomBar
import com.thelightphone.sdk.ui.LightScrollView
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.gridUnitsAsDp

private val STEPS = listOf(
    "On a computer, sign up at opendata.transport.nsw.gov.au.",
    "Create an application and add the Trip Planner APIs and Public Transport - Realtime Vehicle Positions.",
    "Copy the application's API key.",
    "Turn the key into a QR code on the computer, without using a website. " +
        "In a terminal: qrencode -t ansiutf8 'YOUR_KEY'. " +
        "More ways: github.com/vrlmvg/light-nsw-trains",
    "Tap SCAN KEY below and point the camera at the QR code.",
)

/** How to get a key and scan it as a QR code. Returns true when the user wants to scan now. */
class KeyHelpScreen(sealedActivity: SealedLightActivity) : SimpleLightScreen<Boolean>(sealedActivity) {
    @Composable
    override fun Content() {
        TrainsTheme {
            Column(modifier = Modifier.fillMaxSize()) {
                BackTopBar("Get an API key", onBack = { goBack() })
                LightScrollView(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(start = 1f.gridUnitsAsDp()),
                ) {
                    STEPS.forEachIndexed { index, step ->
                        Column(modifier = Modifier.padding(bottom = LIST_ITEM_GAP_UNITS.gridUnitsAsDp())) {
                            LightText("${index + 1}", variant = LightTextVariant.Detail, lighten = true)
                            LightText(step, variant = LightTextVariant.Copy)
                        }
                    }
                }
                LightBottomBar(items = listOf(LightBarButton.Text(text = "SCAN KEY", onClick = { goBack(true) })))
            }
        }
    }
}
