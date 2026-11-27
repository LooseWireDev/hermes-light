package dev.loosewire.hermeslight.ui.common

import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.style.TextOverflow
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.gridUnitsAsDp
import com.thelightphone.sdk.ui.verticalGridUnitsAsDp
import dev.loosewire.hermeslight.ui.ThreadMessageKind
import dev.loosewire.hermeslight.ui.ThreadMessageUi
import dev.loosewire.hermeslight.ui.ThreadNoticeKind
import dev.loosewire.hermeslight.ui.ThreadNoticeUi
import dev.loosewire.hermeslight.ui.ThreadPosition
import dev.loosewire.hermeslight.ui.toolSummary
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch

/**
 * Wheel focus, long-message paging, expansion and position reporting for a transcript.
 *
 * The reader keeps a message id as its focus, not an index: when older messages are prepended
 * the marker and the user's place survive. A turn pages inside a message that is taller than
 * the viewport before it moves the focus, so a long reply is never skipped by one detent.
 */
class ThreadReader internal constructor(
    val listState: LazyListState,
    private val scope: CoroutineScope,
    private val heights: MutableMap<String, Int>,
) {
    var focusId by mutableStateOf<String?>(null)
        private set

    internal var messages: List<ThreadMessageUi> = emptyList()
    internal var onPositionChanged: (ThreadPosition) -> Unit = {}
    internal var onActivate: ((ThreadMessageUi) -> Unit)? = null

    private val expanded = mutableStateMapOf<String, Boolean>()
    private val timestamps = mutableStateMapOf<String, Boolean>()

    fun focusedMessage(): ThreadMessageUi? {
        val current = messages
        if (current.isEmpty()) return null
        val id = focusId
        return current.firstOrNull { it.id == id } ?: current.last()
    }

    fun ensureFocus() {
        val current = messages
        if (current.isEmpty()) return
        if (current.none { it.id == focusId }) focusId = current.last().id
    }

    fun focusOn(id: String) {
        focusId = id
    }

    fun isExpanded(id: String): Boolean = expanded[id] == true

    fun showsTimestamp(id: String): Boolean = timestamps[id] == true

    internal fun recordHeight(
        id: String,
        heightPx: Int,
    ) {
        if (heightPx > 0) heights[id] = heightPx
    }

    fun moveUp(): Boolean {
        val current = messages
        if (current.isEmpty()) return false
        ensureFocus()
        val index = current.indexOfFirst { it.id == focusId }
        if (index < 0) return false
        val info = visibleInfo(index)
        if (info == null) {
            scope.launch { listState.scrollToItem(index) }
            return true
        }
        if (info.offset < -1) {
            val distance = minOf(pagePixels(), -info.offset)
            scope.launch { listState.scrollBy(-distance.toFloat()) }
            return true
        }
        if (index == 0) return false
        val previous = index - 1
        focusId = current[previous].id
        scope.launch { scrollToAligned(current, previous, alignBottom = true) }
        return true
    }

    fun moveDown(): Boolean {
        val current = messages
        if (current.isEmpty()) return false
        ensureFocus()
        val index = current.indexOfFirst { it.id == focusId }
        if (index < 0) return false
        val info = visibleInfo(index)
        if (info == null) {
            scope.launch { listState.scrollToItem(index) }
            return true
        }
        val viewportEnd = listState.layoutInfo.viewportEndOffset
        val below = info.offset + info.size - viewportEnd
        if (below > 1) {
            val distance = minOf(pagePixels(), below)
            scope.launch { listState.scrollBy(distance.toFloat()) }
            return true
        }
        if (index >= current.lastIndex) return false
        val next = index + 1
        focusId = current[next].id
        scope.launch { scrollToAligned(current, next, alignBottom = false) }
        return true
    }

    fun activate(): Boolean {
        val message = focusedMessage() ?: return false
        val external = onActivate
        if (external != null) {
            external(message)
            return true
        }
        when (message.kind) {
            ThreadMessageKind.Tool,
            ThreadMessageKind.Reasoning,
            -> expanded[message.id] = !(expanded[message.id] == true)

            ThreadMessageKind.Approval,
            ThreadMessageKind.Clarify,
            -> Unit

            else -> {
                if (message.timestamp != null) {
                    timestamps[message.id] = !(timestamps[message.id] == true)
                }
            }
        }
        return true
    }

    internal suspend fun restore(position: ThreadPosition?) {
        val current = messages
        if (current.isEmpty()) return
        val index =
            position?.let { p ->
                current.indexOfFirst { it.id == p.messageId }.takeIf { it >= 0 }
                    ?: current.indexOfFirst { sameRow(it.id, p.messageId) }
            } ?: -1
        if (index >= 0 && position != null) {
            focusId = current[index].id
            listState.scrollToItem(index, position.offsetPx.coerceAtLeast(0))
        } else {
            focusId = current.last().id
            scrollToBottomInternal()
        }
    }

    internal fun scrollToBottom() {
        scope.launch { scrollToBottomInternal() }
    }

    private fun visibleInfo(index: Int) = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == index }

    private fun pagePixels(): Int {
        val info = listState.layoutInfo
        val viewport = (info.viewportEndOffset - info.viewportStartOffset).coerceAtLeast(1)
        return (viewport * 0.8f).toInt().coerceAtLeast(1)
    }

    private suspend fun scrollToAligned(
        current: List<ThreadMessageUi>,
        index: Int,
        alignBottom: Boolean,
    ) {
        listState.scrollToItem(index)
        if (!alignBottom) return
        val id = current[index].id
        var height = heights[id] ?: 0
        if (height <= 0) {
            withFrameNanos { }
            height = heights[id] ?: 0
        }
        val info = listState.layoutInfo
        val measured = info.visibleItemsInfo.firstOrNull { it.index == index }?.size ?: height
        val viewport = info.viewportEndOffset - info.viewportStartOffset
        val extra = measured - viewport
        if (extra > 0) {
            withFrameNanos { }
            listState.scrollBy(extra.toFloat())
        }
    }

    private suspend fun scrollToBottomInternal() {
        val current = messages
        if (current.isEmpty()) return
        scrollToAligned(current, current.lastIndex, alignBottom = true)
    }
}

private fun sameRow(
    left: String,
    right: String,
): Boolean = left == right || left.substringBefore(':') == right.substringBefore(':')

@Composable
fun rememberThreadReader(
    sessionId: String?,
    messages: List<ThreadMessageUi>,
    position: ThreadPosition?,
    onPositionChanged: (ThreadPosition) -> Unit,
    onActivate: ((ThreadMessageUi) -> Unit)? = null,
): ThreadReader {
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val heights = remember(sessionId) { mutableMapOf<String, Int>() }
    val reader = remember(sessionId) { ThreadReader(listState, scope, heights) }
    val currentMessages = rememberUpdatedState(messages)
    val currentPosition = rememberUpdatedState(position)
    val currentPositionCallback = rememberUpdatedState(onPositionChanged)
    val currentActivate = rememberUpdatedState(onActivate)
    var restored by remember(sessionId) { mutableStateOf(false) }
    var restoreSettled by remember(sessionId) { mutableStateOf(false) }
    var anchorId by remember(sessionId) { mutableStateOf<String?>(null) }
    var anchorOffset by remember(sessionId) { mutableStateOf(0) }
    var knownFirstId by remember(sessionId) { mutableStateOf<String?>(null) }
    var atBottom by remember(sessionId) { mutableStateOf(false) }

    SideEffect {
        reader.messages = currentMessages.value
        reader.onPositionChanged = currentPositionCallback.value
        reader.onActivate = currentActivate.value
    }

    LaunchedEffect(sessionId, messages.isNotEmpty()) {
        if (restored || messages.isEmpty()) return@LaunchedEffect
        restored = true
        reader.restore(currentPosition.value)
        restoreSettled = true
    }

    // Remember the first visible row before a prepend so the reading anchor can be restored.
    LaunchedEffect(listState, sessionId) {
        snapshotFlow {
            listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset
        }.collect { (index, offset) ->
            val id = reader.messages.getOrNull(index)?.id
            if (id != null) {
                anchorId = id
                anchorOffset = offset
            }
        }
    }

    // Older pages arrive at the front. Re-anchor by message id instead of by shuffled index.
    LaunchedEffect(messages.firstOrNull()?.id, messages.size) {
        val first = messages.firstOrNull()?.id
        val previous = knownFirstId
        knownFirstId = first
        if (previous != null && first != previous) {
            val id = anchorId
            if (id != null) {
                val index = messages.indexOfFirst { it.id == id }
                if (index >= 0) listState.scrollToItem(index, anchorOffset)
            }
        }
    }

    LaunchedEffect(listState, sessionId, messages, restoreSettled) {
        if (!restoreSettled) return@LaunchedEffect
        snapshotFlow { listState.isScrollInProgress }
            .filter { !it }
            .collect {
                val current = reader.messages
                if (current.isEmpty()) return@collect
                val index = listState.firstVisibleItemIndex.coerceIn(0, current.lastIndex)
                currentPositionCallback.value(
                    ThreadPosition(
                        messageId = current[index].id,
                        offsetPx = listState.firstVisibleItemScrollOffset,
                    ),
                )
            }
    }

    LaunchedEffect(listState, messages) {
        snapshotFlow {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()
            last != null &&
                last.index == messages.lastIndex &&
                last.offset + last.size <= info.viewportEndOffset + 1
        }.collect { atBottom = it }
    }

    var latestId by remember(sessionId) { mutableStateOf<String?>(null) }
    LaunchedEffect(messages.lastOrNull()?.id) {
        val id = messages.lastOrNull()?.id ?: return@LaunchedEffect
        val previous = latestId
        latestId = id
        if (previous != null && previous != id && atBottom) {
            reader.scrollToBottom()
        }
    }

    return reader
}

/** The read-only not-actionable approval/clarify strip above a transcript. */
@Composable
fun ThreadNotices(
    notices: List<ThreadNoticeUi>,
    modifier: Modifier = Modifier,
) {
    if (notices.isEmpty()) return
    // Pinned above the reader, so it must never eat the 472dp panel: the two newest fit, the
    // rest are summarised. The desktop remains the only place to answer them.
    val shown = notices.takeLast(2)
    val hidden = notices.size - shown.size
    Column(modifier = modifier.fillMaxWidth()) {
        shown.forEach { notice ->
            val label =
                when (notice.kind) {
                    ThreadNoticeKind.Approval -> "APPROVAL"
                    ThreadNoticeKind.Clarify -> "CLARIFY"
                }
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(
                            start = 0.8f.gridUnitsAsDp(),
                            end = 1f.gridUnitsAsDp(),
                            top = 0.25f.verticalGridUnitsAsDp(),
                            bottom = 0.25f.verticalGridUnitsAsDp(),
                        ),
            ) {
                LightText(
                    text = "$label · ${notice.title}",
                    variant = LightTextVariant.Detail,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val origin = notice.origin?.takeIf { it.isNotBlank() }
                val detail = notice.detail?.takeIf { it.isNotBlank() }
                val line =
                    buildString {
                        append("awaiting desktop")
                        if (origin != null) append(" · ").append(origin)
                        if (detail != null) append(" · ").append(detail)
                    }
                LightText(
                    text = line,
                    variant = LightTextVariant.Superfine,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (hidden > 0) {
            LightText(
                text = "+$hidden more awaiting desktop",
                variant = LightTextVariant.Superfine,
                maxLines = 1,
                modifier =
                    Modifier.padding(
                        start = 0.8f.gridUnitsAsDp(),
                        end = 1f.gridUnitsAsDp(),
                        bottom = 0.25f.verticalGridUnitsAsDp(),
                    ),
            )
        }
        RuleLine()
    }
}

@Composable
fun ThreadView(
    messages: List<ThreadMessageUi>,
    emptyText: String,
    reader: ThreadReader,
    agentLabel: String = "Agent",
    modifier: Modifier = Modifier,
    showFocus: Boolean = true,
) {
    if (messages.isEmpty()) {
        Box(modifier = modifier.fillMaxSize()) {
            LightText(
                text = emptyText,
                variant = LightTextVariant.Paragraph,
                modifier =
                    Modifier
                        .align(Alignment.Center)
                        .padding(horizontal = 2f.gridUnitsAsDp()),
            )
        }
        return
    }

    LazyColumn(
        state = reader.listState,
        modifier = modifier.fillMaxSize(),
        contentPadding =
            PaddingValues(
                start = 0.2f.gridUnitsAsDp(),
                end = 0.2f.gridUnitsAsDp(),
                top = 0.5f.verticalGridUnitsAsDp(),
                bottom = 0.5f.verticalGridUnitsAsDp(),
            ),
        verticalArrangement =
            Arrangement.spacedBy(
                0.45f.verticalGridUnitsAsDp(),
                Alignment.Bottom,
            ),
    ) {
        items(items = messages, key = { it.id }) { message ->
            ThreadMessageRow(
                message = message,
                reader = reader,
                agentLabel = agentLabel,
                focused = showFocus && reader.focusId == message.id,
                modifier =
                    Modifier.onGloballyPositioned {
                        reader.recordHeight(message.id, it.size.height)
                    },
            )
        }
    }
}

@Composable
private fun ThreadMessageRow(
    message: ThreadMessageUi,
    reader: ThreadReader,
    agentLabel: String,
    focused: Boolean,
    modifier: Modifier = Modifier,
) {
    val colors = LightThemeTokens.colors
    val expanded = reader.isExpanded(message.id)
    val showsTimestamp = reader.showsTimestamp(message.id)
    val activate: () -> Unit = {
        reader.focusOn(message.id)
        reader.activate()
    }

    FocusRow(
        focused = focused,
        onClick = activate,
        modifier = modifier,
    ) {
        when (message.kind) {
            ThreadMessageKind.Tool -> {
                val summary = message.toolSummary()
                LightText(
                    text = summary,
                    variant = LightTextVariant.Detail,
                    color = colors.content,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (expanded) {
                    ExpandedText(message)
                    ExpandedTimestamp(message)
                }
            }

            ThreadMessageKind.Reasoning -> {
                LightText(
                    text = "· reasoning",
                    variant = LightTextVariant.Detail,
                    color = colors.content,
                )
                if (expanded) {
                    ExpandedText(message)
                    ExpandedTimestamp(message)
                }
            }

            ThreadMessageKind.Approval,
            ThreadMessageKind.Clarify,
            -> {
                val label = if (message.kind == ThreadMessageKind.Approval) "APPROVAL" else "CLARIFY"
                val title = message.title?.takeIf { it.isNotBlank() } ?: message.body
                LightText(
                    text = "$label · $title",
                    variant = LightTextVariant.Detail,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                LightText(
                    text = "awaiting desktop · no action on this phone",
                    variant = LightTextVariant.Superfine,
                )
            }

            ThreadMessageKind.Error -> {
                LightText(
                    text = "ERROR",
                    variant = LightTextVariant.Superfine,
                    color = colors.content,
                )
                LightText(
                    text = message.body,
                    variant = LightTextVariant.Paragraph,
                    color = colors.content,
                )
            }

            ThreadMessageKind.System -> {
                LightText(
                    text = "· ${message.body}",
                    variant = LightTextVariant.Detail,
                )
            }

            ThreadMessageKind.User,
            ThreadMessageKind.Assistant,
            -> {
                val label = if (message.kind == ThreadMessageKind.User) "YOU" else agentLabel.uppercase()
                LightText(
                    text = label,
                    variant = LightTextVariant.Superfine,
                )
                if (message.body.isNotBlank()) {
                    MarkdownText(text = message.body)
                }
            }
        }

        val media = message.mediaLabel?.takeIf { it.isNotBlank() }
        if (media != null) {
            LightText(
                text = media,
                variant = LightTextVariant.Detail,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        if (showsTimestamp && message.timestamp != null) {
            LightText(
                text = message.timestamp,
                variant = LightTextVariant.Superfine,
            )
        }
    }
}

@Composable
private fun ExpandedText(message: ThreadMessageUi) {
    val text = message.expandableText ?: message.body
    if (text.isBlank()) return
    LightText(
        text = text,
        variant = LightTextVariant.Detail,
        monospace = true,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun ExpandedTimestamp(message: ThreadMessageUi) {
    val timestamp = message.timestamp ?: return
    LightText(
        text = timestamp,
        variant = LightTextVariant.Superfine,
    )
}
