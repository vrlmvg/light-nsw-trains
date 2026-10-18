package io.github.vrlmvg.nswtrains

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightThemeController
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import com.thelightphone.sdk.ui.designVerticalPxToSp
import com.thelightphone.sdk.ui.gridUnitsAsDp
import com.thelightphone.sdk.ui.lightClickable

/** Applies the user's LightOS theme and background to a whole screen. */
@Composable
fun TrainsTheme(content: @Composable () -> Unit) {
    val themeColors by LightThemeController.colors.collectAsState()
    LightTheme(colors = themeColors) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(LightThemeTokens.colors.background),
        ) {
            content()
        }
    }
}

@Composable
fun BackTopBar(title: String, onBack: () -> Unit) {
    LightTopBar(
        leftButton = LightBarButton.LightIcon(
            icon = LightIcons.BACK,
            onClick = onBack,
            contentDescription = "Back",
        ),
        center = LightTopBarCenter.Text(title),
        modifier = Modifier.padding(bottom = 0.25f.gridUnitsAsDp()),
    )
}

/** Centered message filling the remaining space, e.g. loading or empty states. */
@Composable
fun ColumnScope.CenteredMessage(message: String, action: String? = null, onAction: (() -> Unit)? = null) {
    Column(
        modifier = Modifier
            .weight(1f)
            .fillMaxWidth()
            .padding(horizontal = 1f.gridUnitsAsDp()),
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        LightText(text = message, variant = LightTextVariant.Copy, align = TextAlign.Center)
        if (action != null && onAction != null) {
            LightText(
                text = action,
                variant = LightTextVariant.Button,
                underline = true,
                modifier = Modifier
                    .padding(top = 1.5f.gridUnitsAsDp())
                    .lightClickable(onClick = onAction)
                    .padding(0.5f.gridUnitsAsDp()),
            )
        }
    }
}

@Composable
private fun compactCopyStyle(): TextStyle {
    val base = LightThemeTokens.typography.copy
    return base.copy(
        fontSize = base.fontSize.value.designVerticalPxToSp(),
        lineHeight = (base.fontSize.value * 1.2f).designVerticalPxToSp(),
    )
}

@Composable
fun BoldLine(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = compactCopyStyle().copy(fontWeight = FontWeight.Bold),
        color = LightThemeTokens.colors.content,
        modifier = modifier,
    )
}

@Composable
fun CopyLine(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = compactCopyStyle(),
        color = LightThemeTokens.colors.content,
        modifier = modifier,
    )
}

@Composable
fun DetailLine(text: String, modifier: Modifier = Modifier, lighten: Boolean = false) {
    LightText(text = text, variant = LightTextVariant.Detail, lighten = lighten, modifier = modifier)
}

/** Space between entries in a list. LightOS separates items with whitespace, not rules. */
const val LIST_ITEM_GAP_UNITS = 1.5f

@Composable
fun SettingRow(label: String, value: String, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .lightClickable(onClick = onClick)
            .padding(vertical = 0.75f.gridUnitsAsDp()),
    ) {
        LightText(text = label, variant = LightTextVariant.Detail)
        LightText(text = value, variant = LightTextVariant.Heading)
    }
}
