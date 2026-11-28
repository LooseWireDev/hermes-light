package dev.loosewire.hermeslight.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextField
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.gridUnitsAsDp
import com.thelightphone.sdk.ui.verticalGridUnitsAsDp
import dev.loosewire.hermeslight.ui.common.FocusRow

/**
 * The one composer row shared by Home and Thread. The field is the SDK's read-only
 * [LightTextField]; activating it opens [ComposerEditor] through the screen's `compose` action,
 * so the caller never owns editor visibility. The adjacent action submits the current draft
 * (queued by the application while a turn runs) and carries its own wheel-focus rule.
 */
@Composable
fun MessageComposer(
    draft: String,
    running: Boolean,
    fieldFocused: Boolean,
    sendFocused: Boolean,
    onOpenEditor: () -> Unit,
    onSend: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val canSend = draft.isNotBlank()
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(horizontal = 0.3f.gridUnitsAsDp(), vertical = 0.2f.verticalGridUnitsAsDp()),
        verticalAlignment = Alignment.Bottom,
    ) {
        FocusRow(
            focused = fieldFocused,
            onClick = onOpenEditor,
            modifier = Modifier.weight(1f),
        ) {
            LightTextField(
                label = "Message",
                value = draft,
                placeholder = "hold the wheel to talk",
                onClick = onOpenEditor,
            )
        }
        Spacer(Modifier.width(0.3f.gridUnitsAsDp()))
        FocusRow(
            focused = sendFocused,
            enabled = canSend,
            onClick = if (canSend) onSend else null,
            modifier = Modifier.width(6.5f.gridUnitsAsDp()),
        ) {
            LightText(
                text = if (running) "QUEUE" else "SEND",
                variant = LightTextVariant.Detail,
                maxLines = 1,
            )
        }
    }
}
