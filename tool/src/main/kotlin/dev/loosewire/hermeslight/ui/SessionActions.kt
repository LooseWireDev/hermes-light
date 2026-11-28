package dev.loosewire.hermeslight.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import com.thelightphone.sdk.rememberKeyboardOptions
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextInputEditor
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import com.thelightphone.sdk.ui.gridUnitsAsDp
import dev.loosewire.hermeslight.ui.common.BindScreenWheel
import dev.loosewire.hermeslight.ui.common.FocusRow
import dev.loosewire.hermeslight.ui.common.HintLine
import dev.loosewire.hermeslight.ui.common.RuleLine
import dev.loosewire.hermeslight.ui.common.WheelScroll
import dev.loosewire.hermeslight.ui.common.consumeBackKey
import kotlinx.coroutines.launch

/** In-screen sheet, not a navigation destination. Delete only advances to confirmation. */
@Composable
internal fun SessionActionsSheet(
    state: SessionsUi,
    actions: SessionsActions,
) {
    val action = state.action ?: return
    val confirming = action.mode == SessionActionMode.ConfirmDelete
    var focus by remember(action.id, action.mode) { mutableIntStateOf(0) }
    val list = rememberLazyListState()
    val scope = rememberCoroutineScope()
    LaunchedEffect(focus, action.mode) { list.scrollToItem(focus) }

    fun activate() {
        if (state.actionBusy) return
        when {
            confirming -> actions.onConfirmDelete()
            focus == 0 -> actions.onBeginRename()
            else -> actions.onBeginDelete()
        }
    }

    WheelScroll(
        onUp = {
            if (confirming) scope.launch { list.scrollBy(-120f) } else focus = 0
        },
        onDown = {
            if (confirming) scope.launch { list.scrollBy(120f) } else focus = 1
        },
        onClick = ::activate,
        onBack = actions.onDismissActions,
    ) {
        Column(Modifier.fillMaxSize().background(LightThemeTokens.colors.background)) {
            LightTopBar(
                leftButton = LightBarButton.LightIcon(LightIcons.BACK, onClick = actions.onDismissActions),
                center = LightTopBarCenter.Text(if (confirming) "Delete session" else "Session"),
            )
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.BottomCenter) {
                Column(Modifier.fillMaxWidth()) {
                    RuleLine()
                    LightText(
                        text = action.title.ifBlank { "Untitled session" },
                        variant = LightTextVariant.Copy,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(1f.gridUnitsAsDp()),
                    )
                    LazyColumn(state = list, modifier = Modifier.weight(1f, fill = false).fillMaxWidth()) {
                        if (confirming) {
                            item {
                                FocusRow(
                                    focused = true,
                                    enabled = !state.actionBusy,
                                    onClick = ::activate,
                                ) {
                                    LightText(
                                        text =
                                            if (state.actionBusy) {
                                                "deleting…"
                                            } else {
                                                "Delete “${action.title}”? This cannot be undone."
                                            },
                                        variant = LightTextVariant.Copy,
                                    )
                                }
                            }
                        } else {
                            item {
                                FocusRow(focused = focus == 0, onClick = actions.onBeginRename) {
                                    LightText("Rename", LightTextVariant.Copy)
                                }
                            }
                            item {
                                FocusRow(focused = focus == 1, onClick = actions.onBeginDelete) {
                                    LightText("Delete", LightTextVariant.Copy)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** The SDK keyboard and WheelTalk stay mounted through dictation; nothing here submits a prompt. */
@Composable
internal fun SessionTextEditor(
    title: String,
    initial: String,
    state: SessionsUi,
    actions: SessionsActions,
    onSave: (String) -> Unit,
    onBack: () -> Unit,
    onChange: (String) -> Unit = {},
    search: Boolean = false,
) {
    val text = rememberTextFieldState(initial)
    val keyboard = rememberKeyboardOptions()
    val currentActions by rememberUpdatedState(actions)
    val currentChange by rememberUpdatedState(onChange)
    val voiceActive = state.voicePhase != VoicePhase.Idle
    val listening = state.voicePhase == VoicePhase.Listening
    DisposableEffect(Unit) {
        onDispose { currentActions.onHoldCancel() }
    }
    LaunchedEffect(text) {
        snapshotFlow { text.text.toString() }.collect { currentChange(it) }
    }

    fun save() {
        if (!state.actionBusy && !voiceActive) onSave(text.text.toString())
    }

    fun back() {
        if (!state.actionBusy) {
            actions.onHoldCancel()
            onBack()
        }
    }

    BindScreenWheel(
        onClick = ::save,
        onUp = { if (listening) actions.onHoldCancel() },
        onDown = { if (listening) actions.onHoldCancel() },
        onHoldStart = {
            if (!state.actionBusy) {
                actions.onHoldStart { transcript ->
                    val suffix = if (text.text.isNotEmpty() && !text.text.last().isWhitespace()) " $transcript" else transcript
                    text.edit { replace(length, length, suffix) }
                }
            }
        },
        onHoldEnd = actions.onHoldEnd,
        onHoldCancel = actions.onHoldCancel,
    )
    Column(
        Modifier
            .fillMaxSize()
            .background(LightThemeTokens.colors.background)
            .consumeBackKey(::back),
    ) {
        when {
            state.actionBusy -> HintLine("renaming…")
            voiceActive ->
                ListeningPanel(
                    phase = state.voicePhase,
                    level = state.voiceLevel,
                    partial = state.voicePartial,
                    modifier = Modifier.weight(1f),
                )
            else -> {
                state.voiceFeedback?.let { HintLine(it) }
                state.actionError?.let { HintLine(it) }
                if (search && state.query.isNotEmpty() && state.sessions.isEmpty()) HintLine("no sessions match")
                LightTextInputEditor(
                    title = title,
                    state = text,
                    onSubmit = { save() },
                    onBack = ::back,
                    keyboardOptionsFlow = keyboard,
                    submitLabel = if (search) "DONE" else "SAVE",
                    leftBottomBarItem =
                        if (search) {
                            LightBarButton.Text("CLEAR", onClick = { text.edit { replace(0, length, "") } })
                        } else {
                            null
                        },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}
