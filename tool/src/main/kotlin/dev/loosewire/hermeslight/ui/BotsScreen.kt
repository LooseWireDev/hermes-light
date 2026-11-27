package dev.loosewire.hermeslight.ui

import android.os.SystemClock
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import com.thelightphone.sdk.ui.verticalGridUnitsAsDp
import dev.loosewire.hermeslight.ui.common.FocusRow
import dev.loosewire.hermeslight.ui.common.HintLine
import dev.loosewire.hermeslight.ui.common.StatusLine
import dev.loosewire.hermeslight.ui.common.WheelScroll
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.filter

private const val RETRY_THROTTLE_MS = 250L

/**
 * Bots/profiles with honest loading and retry. A failed scan keeps a focusable retry row, so an
 * empty screen is never a dead end on a wheel-only device.
 */
@Composable
fun BotsScreen(
    state: BotsUi,
    actions: BotsActions,
) {
    val colors = LightThemeTokens.colors
    val listState = rememberLazyListState()
    val currentState by rememberUpdatedState(state)
    val currentActions by rememberUpdatedState(actions)
    val retryIndex = state.bots.size
    val showRetry = state.loadError != null || (!state.loading && state.bots.isEmpty())
    val initialMaxRow = if (showRetry) retryIndex else state.bots.lastIndex.coerceAtLeast(0)
    var focusRow by remember(state.profileId) {
        mutableIntStateOf(
            (state.position?.index ?: state.bots.indexOfFirst { it.id == state.selectedBotId })
                .coerceIn(0, initialMaxRow),
        )
    }
    var initialApplied by remember(state.profileId) { mutableStateOf(false) }
    var lastRetryAt by remember(state.profileId) { mutableStateOf(0L) }

    fun retry() {
        val now = SystemClock.elapsedRealtime()
        if (!currentState.loading && now - lastRetryAt > RETRY_THROTTLE_MS) {
            lastRetryAt = now
            currentActions.onLoadBots()
        }
    }

    LaunchedEffect(state.profileId, state.bots.size, state.loading, state.loadError) {
        if (!initialApplied && state.bots.isNotEmpty()) {
            val index =
                (
                    state.position?.index
                        ?: state.bots.indexOfFirst { it.id == state.selectedBotId }
                ).coerceIn(0, state.bots.lastIndex)
            focusRow = index
            listState.scrollToItem(index, state.position?.offsetPx ?: 0)
            initialApplied = true
        }
        val maxRow = if (showRetry) state.bots.size else state.bots.lastIndex.coerceAtLeast(0)
        focusRow = focusRow.coerceIn(0, maxRow)
    }

    LaunchedEffect(focusRow, initialApplied, showRetry) {
        val maxRow = if (showRetry) state.bots.size else state.bots.lastIndex.coerceAtLeast(0)
        if (initialApplied && (showRetry || state.bots.isNotEmpty()) && focusRow in 0..maxRow &&
            listState.layoutInfo.visibleItemsInfo.none { it.index == focusRow }
        ) {
            listState.animateScrollToItem(focusRow)
        }
    }

    LaunchedEffect(listState, state.bots.size, initialApplied) {
        if (!initialApplied) return@LaunchedEffect
        snapshotFlow {
            Triple(
                listState.firstVisibleItemIndex,
                listState.firstVisibleItemScrollOffset,
                listState.isScrollInProgress,
            )
        }.filter { !it.third }
            .collect { (index, offset, _) ->
                val bots = currentState.bots
                if (bots.isEmpty()) return@collect
                val clamped = index.coerceIn(0, bots.lastIndex)
                currentActions.onPositionChanged(ListPosition(clamped, offset))
            }
    }

    WheelScroll(
        onUp = {
            if (focusRow > 0) focusRow--
        },
        onDown = {
            when {
                focusRow < state.bots.lastIndex -> focusRow++
                showRetry -> {
                    focusRow = retryIndex
                    retry()
                }
                else -> Unit
            }
        },
        onClick = {
            val bot = state.bots.getOrNull(focusRow)
            if (bot != null) {
                actions.onOpenBot(bot.id)
            } else if (showRetry) {
                retry()
            }
        },
        onBack = actions.onBack,
        onLongBack = actions.onLongBack,
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
                center = LightTopBarCenter.Text("Bots"),
                modifier = Modifier.padding(bottom = 0.4f.verticalGridUnitsAsDp()),
            )
            StatusLine(status = state.status)

            when {
                state.loading && state.bots.isEmpty() -> HintLine("scanning bots…")
                state.loadError != null -> HintLine("scan failed: ${state.loadError}")
                state.bots.isEmpty() -> HintLine(state.emptyText)
            }

            LazyColumn(
                state = listState,
                modifier =
                    Modifier
                        .weight(1f)
                        .fillMaxWidth(),
            ) {
                itemsIndexed(items = state.bots, key = { _, bot -> bot.id }) { index, bot ->
                    FocusRow(
                        focused = focusRow == index,
                        onClick = { actions.onOpenBot(bot.id) },
                    ) {
                        LightText(
                            text = bot.name.ifBlank { bot.id },
                            variant = LightTextVariant.Copy,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        val detail =
                            buildString {
                                append(bot.source.uppercase())
                                append(" · ")
                                append(bot.sessionCount)
                                append(" sessions")
                                if (bot.id == state.selectedBotId) append(" · current")
                                bot.detail?.takeIf { it.isNotBlank() }?.let { append(" · ").append(it) }
                            }
                        LightText(
                            text = detail,
                            variant = LightTextVariant.Superfine,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }

                if (showRetry) {
                    item(key = "bots-retry-row") {
                        FocusRow(
                            focused = focusRow == retryIndex,
                            onClick = { retry() },
                        ) {
                            LightText(
                                text = if (state.loadError == null) "Scan again" else "Retry scan",
                                variant = LightTextVariant.Copy,
                            )
                            LightText(
                                text = state.loadError ?: "ask the dashboard for bot profiles",
                                variant = LightTextVariant.Superfine,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
    }
}
