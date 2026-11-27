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

/** Uses the SDK keyboard in the current screen; leaving the editor preserves the draft. */
@Composable
fun ComposerEditor(
    state: ThreadUi,
    actions: ThreadActions,
    onClose: () -> Unit,
) {
    val text = rememberTextFieldState(state.composer)
    val keyboard = rememberKeyboardOptions()
    val submitted = remember { BooleanArray(1) }
    val currentActions = rememberUpdatedState(actions)
    DisposableEffect(Unit) {
        onDispose {
            currentActions.value.onHoldCancel()
            if (!submitted[0]) currentActions.value.onDraftChange(text.text.toString())
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
    BindScreenWheel(
        onClick = {
            if (state.running) {
                actions.onAction("stop")
            } else {
                submitted[0] = true
                actions.onSubmit(text.text.toString())
                onClose()
            }
        },
        onHoldStart = {
            actions.onDraftChange(text.text.toString())
            actions.onHoldStart()
        },
        onHoldEnd = {
            actions.onDraftChange(text.text.toString())
            actions.onHoldSendAndClose {
                submitted[0] = true
                onClose()
            }
        },
        onHoldCancel = actions.onHoldCancel,
    )
    Column(
        Modifier
            .fillMaxSize()
            .background(LightThemeTokens.colors.background)
            .consumeBackKey(onClose),
    ) {
        state.voiceFeedback?.let { HintLine(it) }
        LightTextInputEditor(
            title = if (state.running) "Queue message" else "Message",
            state = text,
            onSubmit = {
                submitted[0] = true
                val value = it.toString()
                actions.onSubmit(value)
                onClose()
            },
            onBack = onClose,
            keyboardOptionsFlow = keyboard,
            submitLabel = if (state.running) "QUEUE" else "SEND",
            singleLine = false,
            modifier = Modifier.weight(1f),
        )
    }
}
