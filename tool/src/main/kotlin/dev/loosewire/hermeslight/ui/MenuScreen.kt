package dev.loosewire.hermeslight.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import dev.loosewire.hermeslight.Route
import dev.loosewire.hermeslight.ui.common.FocusRow
import dev.loosewire.hermeslight.ui.common.StatusLine
import dev.loosewire.hermeslight.ui.common.WheelScroll

internal enum class MenuDestination(
    val label: String,
    val route: Route?,
    val note: String? = null,
) {
    Chat("Chat", Route.Home),
    History("History", Route.Sessions),
    Bots("Bots", Route.Bots),
    Cron("Cron", null, "arrives with live tiles"),
    Settings("Settings", Route.Setup),
}

/** A full SDK page. Disabled destinations remain in the wheel list but cannot open. */
@Composable
internal fun MenuScreen(
    status: StatusLineModel,
    onOpen: (Route) -> Unit,
    onBack: () -> Unit,
) {
    val colors = LightThemeTokens.colors
    val rows = MenuDestination.entries
    val listState = rememberLazyListState()
    var focusIndex by remember { mutableIntStateOf(0) }

    LaunchedEffect(focusIndex) {
        listState.animateScrollToItem(focusIndex)
    }

    fun activate(row: MenuDestination) {
        row.route?.let(onOpen)
    }

    WheelScroll(
        onUp = { if (focusIndex > 0) focusIndex-- },
        onDown = { if (focusIndex < rows.lastIndex) focusIndex++ },
        onClick = { activate(rows[focusIndex]) },
        onBack = onBack,
    ) {
        Column(modifier = Modifier.fillMaxSize().background(colors.background)) {
            LightTopBar(
                leftButton = LightBarButton.LightIcon(icon = LightIcons.BACK, onClick = onBack),
                center = LightTopBarCenter.Text("Menu"),
            )
            LazyColumn(state = listState, modifier = Modifier.weight(1f).fillMaxWidth()) {
                itemsIndexed(items = rows, key = { _, row -> row.name }) { index, row ->
                    FocusRow(
                        focused = focusIndex == index,
                        enabled = row.route != null,
                        onClick = { activate(row) },
                    ) {
                        LightText(
                            text = row.label,
                            variant = LightTextVariant.Copy,
                            lighten = row.route == null,
                        )
                        row.note?.let { LightText(text = it, variant = LightTextVariant.Superfine) }
                    }
                }
            }
            StatusLine(status)
        }
    }
}
