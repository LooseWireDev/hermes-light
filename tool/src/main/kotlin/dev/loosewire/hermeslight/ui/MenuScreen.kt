package dev.loosewire.hermeslight.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import com.thelightphone.sdk.ui.gridUnitsAsDp
import com.thelightphone.sdk.ui.verticalGridUnitsAsDp
import dev.loosewire.hermeslight.ui.common.FocusRow
import dev.loosewire.hermeslight.ui.common.HintLine
import dev.loosewire.hermeslight.ui.common.RuleLine
import dev.loosewire.hermeslight.ui.common.SectionLabel
import dev.loosewire.hermeslight.ui.common.StatusLine
import dev.loosewire.hermeslight.ui.common.WheelScroll

/**
 * Everything the Menu page shows. Counts are nullable on purpose: null means the gateway has not
 * answered yet (or cannot list that resource), and the page says so instead of inventing a zero.
 * [recentSessions] is the app-wide recent snapshot, independent of the selected bot, newest first.
 */
data class MenuUi(
    val status: StatusLineModel = StatusLineModel(""),
    /** A failed count/session fetch; shown as one honest line instead of a stale count. */
    val error: String? = null,
    val model: String = "",
    val sessionCount: Int? = null,
    val botCount: Int? = null,
    val cronCount: Int? = null,
    val recentSessions: List<SessionRowUi> = emptyList(),
    val version: String = "0.2.1",
    val cronSummary: String = "Cron is scheduled and managed in the Hermes desktop dashboard.",
)

data class MenuActions(
    val onHistory: () -> Unit = {},
    val onBots: () -> Unit = {},
    val onSettings: () -> Unit = {},
    val onOpenSession: (String) -> Unit = {},
    val onBack: () -> Unit = {},
)

private const val DESTINATION_ROWS = 5
private const val RECENT_LIMIT = 3

private enum class MenuExpansion { Cron, About }

/**
 * Menu: connection/model/count status block, five destinations with live counts, and the three
 * most recent sessions. Cron and About expand in place — they are facts about this install, not
 * screens worth a full page. Recents open their thread directly; Back walks Thread → Sessions →
 * Menu → Home from there.
 */
@Composable
fun MenuScreen(
    state: MenuUi,
    actions: MenuActions,
) {
    val colors = LightThemeTokens.colors
    val listState = rememberLazyListState()
    val recents = remember(state.recentSessions) { state.recentSessions.take(RECENT_LIMIT) }
    val lastRow = DESTINATION_ROWS - 1 + recents.size
    var focusRow by remember { mutableIntStateOf(0) }
    var expansion by remember { mutableStateOf<MenuExpansion?>(null) }

    // A shrinking recent snapshot must not leave focus parked on a row that no longer exists.
    LaunchedEffect(lastRow) { focusRow = focusRow.coerceIn(0, lastRow) }

    // Destination items are 0..4; the section label occupies the next item before the recents.
    fun itemIndex(row: Int): Int = if (row < DESTINATION_ROWS) row else row + 1

    LaunchedEffect(focusRow, expansion, recents.size) {
        listState.animateScrollToItem(itemIndex(focusRow))
    }

    val sessionsText = countText(state.sessionCount, "session")
    val botsText = countText(state.botCount, "bot")
    val jobsText = countText(state.cronCount, "job")
    val statusCounts =
        if (state.botCount == null) {
            sessionsText
        } else {
            "$sessionsText · $botsText"
        }

    WheelScroll(
        onUp = { if (focusRow > 0) focusRow-- },
        onDown = { if (focusRow < lastRow) focusRow++ },
        onClick = {
            when {
                focusRow == 0 -> actions.onHistory()
                focusRow == 1 -> actions.onBots()
                focusRow == 2 -> expansion = expansion.toggle(MenuExpansion.Cron)
                focusRow == 3 -> actions.onSettings()
                focusRow == 4 -> expansion = expansion.toggle(MenuExpansion.About)
                else -> recents.getOrNull(focusRow - DESTINATION_ROWS)?.let { actions.onOpenSession(it.id) }
            }
        },
        onBack = actions.onBack,
        onLongBack = actions.onSettings,
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .background(colors.background),
        ) {
            LightTopBar(
                leftButton =
                    LightBarButton.LightIcon(
                        icon = LightIcons.BACK,
                        onClick = actions.onBack,
                    ),
                center = LightTopBarCenter.Text("Menu"),
                modifier = Modifier.padding(bottom = 0.4f.verticalGridUnitsAsDp()),
            )
            StatusLine(status = state.status)

            Column(modifier = Modifier.padding(horizontal = 1f.gridUnitsAsDp(), vertical = 0.2f.verticalGridUnitsAsDp())) {
                LightText(
                    text = state.model.ifBlank { "Model unavailable" },
                    variant = LightTextVariant.Copy,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                LightText(
                    text = statusCounts,
                    variant = LightTextVariant.Superfine,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            state.error?.let { HintLine(it) }
            RuleLine()

            LazyColumn(
                state = listState,
                modifier =
                    Modifier
                        .weight(1f)
                        .fillMaxWidth(),
            ) {
                item(key = "menu-history") {
                    MenuRow(
                        focused = focusRow == 0,
                        title = "History",
                        detail = "$sessionsText · every bot",
                        onClick = actions.onHistory,
                    )
                }
                item(key = "menu-bots") {
                    MenuRow(
                        focused = focusRow == 1,
                        title = "Bots",
                        detail = "$botsText · profiles and session counts",
                        onClick = actions.onBots,
                    )
                }
                item(key = "menu-cron") {
                    Column {
                        MenuRow(
                            focused = focusRow == 2,
                            title = "Cron",
                            detail =
                                if (expansion == MenuExpansion.Cron) {
                                    "$jobsText · click to hide"
                                } else {
                                    "$jobsText · click for details"
                                },
                            onClick = { expansion = expansion.toggle(MenuExpansion.Cron) },
                        )
                        if (expansion == MenuExpansion.Cron) {
                            HintLine(cronDetail(state))
                        }
                    }
                }
                item(key = "menu-settings") {
                    MenuRow(
                        focused = focusRow == 3,
                        title = "Settings",
                        detail = "endpoint, credentials and providers",
                        onClick = actions.onSettings,
                    )
                }
                item(key = "menu-about") {
                    Column {
                        MenuRow(
                            focused = focusRow == 4,
                            title = "About",
                            detail =
                                if (expansion == MenuExpansion.About) {
                                    "Hermes Light · v${state.version} · click to hide"
                                } else {
                                    "Hermes Light · v${state.version} · click for details"
                                },
                            onClick = { expansion = expansion.toggle(MenuExpansion.About) },
                        )
                        if (expansion == MenuExpansion.About) {
                            HintLine("Hermes Light ${state.version} — a wheel-first Hermes client for the Light Phone III.")
                            HintLine(
                                "Sessions, streaming, approvals and sends use the Hermes gateway; " +
                                    "the desktop dashboard remains the control room.",
                            )
                        }
                    }
                }
                item(key = "menu-recents-label") { SectionLabel("Recent sessions") }
                itemsIndexed(
                    items = recents,
                    key = { _, session -> "menu-recent-${session.id}" },
                ) { index, session ->
                    val row = DESTINATION_ROWS + index
                    FocusRow(
                        focused = focusRow == row,
                        onClick = { actions.onOpenSession(session.id) },
                    ) {
                        LightText(
                            text = session.title.ifBlank { "Untitled session" },
                            variant = LightTextVariant.Copy,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        val detail =
                            buildString {
                                append(session.source.uppercase())
                                append(" · ")
                                append(session.age)
                                if (session.live) append(" · ● live")
                                session.detail?.takeIf { it.isNotBlank() }?.let { append(" · ").append(it) }
                            }
                        LightText(
                            text = detail,
                            variant = LightTextVariant.Superfine,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                if (recents.isEmpty()) {
                    item(key = "menu-recents-empty") {
                        HintLine("Recent sessions appear here once the gateway has messages.")
                    }
                }
            }
        }
    }
}

@Composable
private fun MenuRow(
    focused: Boolean,
    title: String,
    detail: String,
    onClick: () -> Unit,
) {
    FocusRow(focused = focused, onClick = onClick) {
        LightText(
            text = title,
            variant = LightTextVariant.Copy,
            maxLines = 1,
        )
        LightText(
            text = detail,
            variant = LightTextVariant.Superfine,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private fun MenuExpansion?.toggle(target: MenuExpansion): MenuExpansion? =
    if (this == target) null else target

private fun countText(count: Int?, noun: String): String =
    when {
        count == null -> "$noun count unavailable"
        count == 1 -> "1 $noun"
        else -> "$count ${noun}s"
    }

private fun cronDetail(state: MenuUi): String {
    val facts =
        when (val jobs = state.cronCount) {
            null -> "Job count unavailable."
            0 -> "No jobs scheduled."
            1 -> "1 job scheduled."
            else -> "$jobs jobs scheduled."
        }
    return listOf(facts, state.cronSummary.trim())
        .filter { it.isNotEmpty() }
        .joinToString(" ")
}
