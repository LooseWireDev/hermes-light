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
import com.thelightphone.sdk.ui.lightTapAndLongPress
import com.thelightphone.sdk.ui.verticalGridUnitsAsDp
import dev.loosewire.hermeslight.ui.common.FocusRow
import dev.loosewire.hermeslight.ui.common.HintLine
import dev.loosewire.hermeslight.ui.common.StatusLine
import dev.loosewire.hermeslight.ui.common.WheelScroll
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.filter

private const val PAGING_THROTTLE_MS = 250L

/** Loaded history, filtered locally; wheel hold opens the same actions as touch long-press. */
@Composable
fun SessionsScreen(
    state: SessionsUi,
    actions: SessionsActions,
) {
    val colors = LightThemeTokens.colors
    val listState = rememberLazyListState()
    val currentState by rememberUpdatedState(state)
    val currentActions by rememberUpdatedState(actions)
    var searching by remember(state.profileId) { mutableStateOf(false) }
    var focusedSessionId by remember(state.profileId) { mutableStateOf<String?>(null) }
    var displayedQuery by remember(state.profileId) { mutableStateOf(state.query) }
    var visibleSessionId by remember(state.profileId) { mutableStateOf<String?>(null) }
    var visibleOffset by remember(state.profileId) { mutableIntStateOf(0) }
    val footerIndex = state.sessions.size
    var focusRow by remember(state.profileId) {
        mutableIntStateOf(
            (state.position?.index ?: state.sessions.indexOfFirst { it.id == state.selectedSessionId })
                .coerceIn(-1, footerIndex),
        )
    }
    var initialApplied by remember(state.profileId) { mutableStateOf(false) }
    var lastPagingAt by remember(state.profileId) { mutableStateOf(0L) }

    fun moveFocus(index: Int) {
        focusRow = index
        focusedSessionId = state.sessions.getOrNull(index)?.id
    }

    fun back() {
        if (state.query.isNotEmpty()) actions.onQueryChange("") else actions.onBack()
    }

    fun requestPage() {
        val now = SystemClock.elapsedRealtime()
        if (currentState.hasMore && !currentState.loading && now - lastPagingAt > PAGING_THROTTLE_MS) {
            lastPagingAt = now
            currentActions.onLoadMore()
        }
    }

    LaunchedEffect(state.profileId, state.sessions, state.query, searching, state.action) {
        if (searching || state.action != null) return@LaunchedEffect
        val clearing = displayedQuery.isNotEmpty() && state.query.isEmpty()
        displayedQuery = state.query
        if (!initialApplied && state.sessions.isNotEmpty()) {
            val index =
                (
                    state.position?.index
                        ?: state.sessions.indexOfFirst { it.id == state.selectedSessionId }
                ).coerceIn(-1, state.sessions.lastIndex)
            moveFocus(index)
            if (index >= 0) listState.scrollToItem(index, state.position?.offsetPx ?: 0)
            initialApplied = true
        }
        val visible = state.sessions.indexOfFirst { it.id == visibleSessionId }
        if (clearing && visible >= 0) {
            moveFocus(visible)
            listState.scrollToItem(visible, visibleOffset)
        } else {
            val retained = state.sessions.indexOfFirst { it.id == focusedSessionId }
            moveFocus(if (retained >= 0) retained else focusRow.coerceIn(-2, state.sessions.size))
        }
    }

    LaunchedEffect(focusRow, initialApplied, searching, state.action) {
        if (initialApplied && !searching && state.action == null && focusRow in 0..state.sessions.size &&
            listState.layoutInfo.visibleItemsInfo.none { it.index == focusRow }
        ) {
            listState.animateScrollToItem(focusRow)
        }
    }

    LaunchedEffect(listState, state.sessions, initialApplied, searching, state.action) {
        if (!initialApplied || searching || state.action != null) return@LaunchedEffect
        snapshotFlow {
            Triple(
                listState.firstVisibleItemIndex,
                listState.firstVisibleItemScrollOffset,
                listState.isScrollInProgress,
            )
        }.filter { !it.third }
            .collect { (index, offset, _) ->
                val sessions = state.sessions
                if (sessions.isEmpty()) return@collect
                val clamped = index.coerceIn(0, sessions.lastIndex)
                visibleSessionId = sessions[clamped].id
                visibleOffset = offset
                currentActions.onPositionChanged(ListPosition(clamped, offset))
            }
    }

    val action = state.action
    if (action != null) {
        if (action.mode == SessionActionMode.Rename) {
            SessionTextEditor(
                title = "Rename",
                initial = action.title,
                state = state,
                actions = actions,
                onSave = actions.onRename,
                onBack = actions.onDismissActions,
            )
        } else {
            SessionActionsSheet(state, actions)
        }
        return
    }
    if (searching) {
        SessionTextEditor(
            title = if (state.query.isNotEmpty()) "${state.sessions.size} of ${state.totalCount}" else "Search",
            initial = state.query,
            state = state,
            actions = actions,
            onChange = actions.onQueryChange,
            onSave = {
                actions.onQueryChange(it)
                searching = false
            },
            onBack = {
                actions.onQueryChange("")
                searching = false
            },
            search = true,
        )
        return
    }

    WheelScroll(
        onUp = {
            if (focusRow > -2) moveFocus(focusRow - 1)
        },
        onDown = {
            val lastSession = state.sessions.lastIndex
            when {
                focusRow < lastSession -> moveFocus(focusRow + 1)
                focusRow == lastSession -> {
                    if (state.hasMore) requestPage() else moveFocus(footerIndex)
                }
                else -> requestPage()
            }
        },
        onClick = {
            when (focusRow) {
                -2 -> searching = true
                -1 -> if (!state.creating) actions.onNewSession()
                else -> {
                    val session = state.sessions.getOrNull(focusRow)
                    if (session != null) actions.onOpenSession(session.id) else actions.onOpenBots()
                }
            }
        },
        onBack = ::back,
        onLongBack = actions.onLongBack,
        onHoldStart = {
            state.sessions.getOrNull(focusRow)?.let { actions.onShowActions(it.id) }
        },
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
                        onClick = ::back,
                    ),
                center =
                    LightTopBarCenter.Text(
                        if (state.query.isNotEmpty()) "${state.sessions.size} of ${state.totalCount}" else "Sessions",
                    ),
                rightButton =
                    LightBarButton.Text(
                        text = if (focusRow == -2) "› SEARCH" else "SEARCH",
                        onClick = { searching = true },
                    ),
                modifier = Modifier.padding(bottom = 0.4f.verticalGridUnitsAsDp()),
            )
            StatusLine(status = state.status)
            state.actionError?.let { HintLine(it) }

            FocusRow(
                focused = focusRow == -1,
                enabled = !state.creating,
                onClick = actions.onNewSession,
            ) {
                LightText(
                    text = if (state.creating) "creating session…" else "New session",
                    variant = LightTextVariant.Copy,
                    maxLines = 1,
                )
            }

            when {
                state.query.isNotEmpty() && state.sessions.isEmpty() -> HintLine("no sessions match")
                state.loading && state.sessions.isEmpty() -> HintLine("loading sessions…")
                state.loadError != null -> HintLine(state.loadError)
                state.sessions.isEmpty() -> HintLine("No sessions yet · turn up for New session")
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
                        modifier =
                            Modifier.lightTapAndLongPress(
                                onTap = { actions.onOpenSession(session.id) },
                                onLongPress = { actions.onShowActions(session.id) },
                                onLongClickLabel = "Rename or delete",
                            ),
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
