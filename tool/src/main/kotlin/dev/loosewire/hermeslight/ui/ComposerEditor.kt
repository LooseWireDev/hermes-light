package dev.loosewire.hermeslight.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import com.thelightphone.sdk.rememberKeyboardOptions
import com.thelightphone.sdk.ui.LightTextInputEditor
import com.thelightphone.sdk.ui.LightThemeTokens
import dev.loosewire.hermeslight.ui.common.BindScreenWheel
import dev.loosewire.hermeslight.ui.common.HintLine
import dev.loosewire.hermeslight.ui.common.consumeBackKey

/**
 * Uses the SDK keyboard in the current screen. SAVE and Back both write the draft and close
 * without submitting; the text then waits in the composer for an explicit Send. A live voice
 * take takes over the content area while the wheel binding and [androidx.compose.foundation.text.input.TextFieldState]
 * stay mounted, so release appends to the draft instead of sending.
 */
@Composable
fun ComposerEditor(
    state: ThreadUi,
    actions: ThreadActions,
    onClose: () -> Unit,
) {
    val text = rememberTextFieldState(state.composer)
    val keyboard = rememberKeyboardOptions()
    val currentActions = rememberUpdatedState(actions)
    DisposableEffect(Unit) {
        onDispose {
            currentActions.value.onHoldCancel()
            currentActions.value.onDraftChange(text.text.toString())
        }
    }
    LaunchedEffect(state.composer) {
        if (text.text.toString() != state.composer) {
            text.edit { replace(0, length, state.composer) }
        }
    }
    LaunchedEffect(text) {
        snapshotFlow { text.text.toString() }.collect { currentActions.value.onDraftChange(it) }
    }

    val voiceActive = state.voicePhase != VoicePhase.Idle
    val listening = state.voicePhase == VoicePhase.Listening

    fun save() {
        actions.onDraftChange(text.text.toString())
        onClose()
    }

    BindScreenWheel(
        onClick = { if (!voiceActive) save() },
        onUp = { if (listening) actions.onHoldCancel() },
        onDown = { if (listening) actions.onHoldCancel() },
        onHoldStart = {
            actions.onDraftChange(text.text.toString())
            actions.onHoldStart()
        },
        onHoldEnd = {
            actions.onDraftChange(text.text.toString())
            actions.onHoldEnd()
        },
        onHoldCancel = actions.onHoldCancel,
    )
    Column(
        Modifier
            .fillMaxSize()
            .background(LightThemeTokens.colors.background)
            .consumeBackKey { save() },
    ) {
        if (voiceActive) {
            ListeningPanel(
                phase = state.voicePhase,
                level = state.voiceLevel,
                partial = state.voicePartial,
                modifier = Modifier.weight(1f),
            )
        } else {
            state.voiceFeedback?.let { HintLine(it) }
            LightTextInputEditor(
                title = "Message",
                state = text,
                onSubmit = { save() },
                onBack = { save() },
                keyboardOptionsFlow = keyboard,
                submitLabel = "SAVE",
                singleLine = false,
                modifier = Modifier.weight(1f),
            )
        }
    }
}
