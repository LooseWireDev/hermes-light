package dev.loosewire.hermeslight.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import com.thelightphone.sdk.rememberKeyboardOptions
import com.thelightphone.sdk.ui.LightTextInputEditor
import com.thelightphone.sdk.ui.LightThemeTokens
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
    DisposableEffect(Unit) {
        onDispose { if (!submitted[0]) actions.onDraftChange(text.text.toString()) }
    }
    Box(
        Modifier
            .fillMaxSize()
            .background(LightThemeTokens.colors.background)
            .consumeBackKey(onClose),
    ) {
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
            modifier = Modifier.fillMaxSize(),
        )
    }
}
