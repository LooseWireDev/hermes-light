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

private const val PAGING_THROTTLE_MS = 250L

/**
 * Sessions: recent sessions, a disabled desktop-only "New session" row, and Bots as the footer row.
 *
 * The wheel reaches the last loaded session; turning past it asks for the next page and stays
 * put. A failed fetch stays retryable with the same gesture. Position is reported as an index
 * into [SessionsUi.sessions] plus a pixel offset, which is what the application persists.
 */
@Composable
fun SessionsScreen(
    state: SessionsUi,
    actions: SessionsActions,
) {
    val colors = LightThemeTokens.colors
    val listState = rememberLazyListState()
    val currentState by rememberUpdatedState(state)
    val currentActions by rememberUpdatedState(actions)
    val footerIndex = state.sessions.size
    var focusRow by remember(state.profileId) {
        mutableIntStateOf(
            (state.position?.index ?: state.sessions.indexOfFirst { it.id == state.selectedSessionId })
                .coerceIn(0, footerIndex),
        )
    }
    var initialApplied by remember(state.profileId) { mutableStateOf(false) }
    var lastPagingAt by remember(state.profileId) { mutableStateOf(0L) }

    fun requestPage() {
        val now = SystemClock.elapsedRealtime()
        if (currentState.hasMore && !currentState.loading && now - lastPagingAt > PAGING_THROTTLE_MS) {
            lastPagingAt = now
            currentActions.onLoadMore()
        }
    }

    LaunchedEffect(state.profileId, state.sessions.size) {
        if (!initialApplied && state.sessions.isNotEmpty()) {
            val index =
                (
                    state.position?.index
                        ?: state.sessions.indexOfFirst { it.id == state.selectedSessionId }
                ).coerceIn(0, state.sessions.lastIndex)
            focusRow = index
            listState.scrollToItem(index, state.position?.offsetPx ?: 0)
            initialApplied = true
        }
        focusRow = focusRow.coerceIn(0, state.sessions.size)
    }

    LaunchedEffect(focusRow, initialApplied) {
        if (initialApplied && focusRow in 0..state.sessions.size &&
            listState.layoutInfo.visibleItemsInfo.none { it.index == focusRow }
        ) {
            listState.animateScrollToItem(focusRow)
        }
    }

    LaunchedEffect(listState, state.sessions.size, initialApplied) {
        if (!initialApplied) return@LaunchedEffect
        snapshotFlow {
            Triple(
                listState.firstVisibleItemIndex,
                listState.firstVisibleItemScrollOffset,
                listState.isScrollInProgress,
            )
        }.filter { !it.third }
            .collect { (index, offset, _) ->
                val sessions = currentState.sessions
                if (sessions.isEmpty()) return@collect
                val clamped = index.coerceIn(0, sessions.lastIndex)
                currentActions.onPositionChanged(ListPosition(clamped, offset))
            }
    }

    WheelScroll(
        onUp = {
            if (focusRow > 0) focusRow--
        },
        onDown = {
            val lastSession = state.sessions.lastIndex
            when {
                focusRow < lastSession -> focusRow++
                focusRow == lastSession -> {
                    if (state.hasMore) requestPage() else focusRow = footerIndex
                }
                else -> requestPage()
            }
        },
        onClick = {
            val session = state.sessions.getOrNull(focusRow)
            if (session != null) {
                actions.onOpenSession(session.id)
            } else {
                actions.onOpenBots()
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
                center = LightTopBarCenter.Text("Sessions"),
                modifier = Modifier.padding(bottom = 0.4f.verticalGridUnitsAsDp()),
            )
            StatusLine(status = state.status)

            FocusRow(focused = false, enabled = false) {
                LightText(
                    text = "New session",
                    variant = LightTextVariant.Copy,
                    lighten = true,
                    maxLines = 1,
                )
                LightText(
                    text = "create on desktop for now",
                    variant = LightTextVariant.Superfine,
                )
            }

            when {
                state.loading && state.sessions.isEmpty() -> HintLine("loading sessions…")
                state.loadError != null -> HintLine("load failed — turn at the end to retry: ${state.loadError}")
                state.sessions.isEmpty() -> HintLine("No sessions yet · turn down for Bots")
            }

            LazyColumn(
                state = listState,
                modifier =
                    Modifier
                        .weight(1f)
                        .fillMaxWidth(),
            ) {
                itemsIndexed(items = state.sessions, key = { _, session -> session.id }) { index, session ->
                    val selected = session.id == state.selectedSessionId
                    FocusRow(
                        focused = focusRow == index,
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
                                if (selected) append(" · current")
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

                item(key = "sessions-bots-footer") {
                    FocusRow(
                        focused = focusRow == footerIndex,
                        onClick = actions.onOpenBots,
                    ) {
                        LightText(
                            text = "Bots",
                            variant = LightTextVariant.Copy,
                        )
                        LightText(
                            text = "profiles and session counts",
                            variant = LightTextVariant.Superfine,
                        )
                    }
                }
            }

            if (state.loading && state.sessions.isNotEmpty()) {
                HintLine("loading more…")
            } else if (!state.hasMore && state.sessions.isNotEmpty()) {
                HintLine("end of sessions")
            }
        }
    }
}
