package dev.loosewire.hermeslight.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import com.thelightphone.sdk.ui.gridUnitsAsDp
import com.thelightphone.sdk.ui.lightTapAndLongPress
import com.thelightphone.sdk.ui.verticalGridUnitsAsDp
import dev.loosewire.hermeslight.ui.common.HintLine
import dev.loosewire.hermeslight.ui.common.StatusLine
import dev.loosewire.hermeslight.ui.common.ThreadNotices
import dev.loosewire.hermeslight.ui.common.ThreadView
import dev.loosewire.hermeslight.ui.common.WheelScroll
import dev.loosewire.hermeslight.ui.common.rememberThreadReader

/**
 * A full session transcript with explicit prompt and blocking-decision actions.
 *
 * Wheel-up at the oldest loaded row asks for an older window; wheel-down at the newest loaded
 * row asks for a newer window when a restored position left history on both sides. A failed
 * window load leaves the same boundary usable, so the next turn retries honestly.
 */
@Composable
fun ThreadScreen(
    state: ThreadUi,
    actions: ThreadActions,
) {
    val colors = LightThemeTokens.colors
    val error = state.error
    val reader =
        rememberThreadReader(
            sessionId = state.sessionId,
            messages = state.messages,
            position = state.position,
            onPositionChanged = actions.onPositionChanged,
            onAction = actions.onAction,
        )

    val firstDecision =
        state.messages.firstOrNull {
            it.action?.startsWith("approval:") == true || it.action?.startsWith("clarify:") == true
        }
    LaunchedEffect(firstDecision?.id) {
        if (firstDecision != null) {
            reader.focusOn(firstDecision.id)
            reader.listState.scrollToItem(state.messages.indexOf(firstDecision))
        }
    }

    WheelScroll(
        onUp = {
            if (!reader.moveUp() && state.hasOlder) {
                actions.onLoadOlder()
            }
        },
        onDown = {
            if (!reader.moveDown() && state.hasNewer) {
                actions.onLoadNewer()
            }
        },
        onClick = {
            val action = reader.focusedMessage()?.action
            when {
                action?.startsWith("approval:") == true || action?.startsWith("clarify:") == true -> reader.activate()
                state.running -> actions.onAction("stop")
                state.composer.isNotBlank() -> actions.onSubmit(state.composer)
                else -> reader.activate()
            }
        },
        onBack = actions.onBack,
        onLongBack = actions.onLongBack,
        onHoldStart = actions.onHoldStart,
        onHoldEnd = actions.onHoldEnd,
        onHoldCancel = actions.onHoldCancel,
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
                center =
                    LightTopBarCenter.Text(
                        state.title.ifBlank { state.botName.ifBlank { "Thread" } },
                    ),
                modifier = Modifier.padding(bottom = 0.4f.verticalGridUnitsAsDp()),
            )
            StatusLine(
                status = state.status,
                modifier =
                    Modifier.lightTapAndLongPress(
                        onTap = {},
                        onLongPress = actions.onDiagnostics,
                        onLongClickLabel = "Wheel diagnostics",
                    ),
            )
            state.voiceFeedback?.let { HintLine(it) }
            state.wheelDiagnostics?.let { HintLine(it) }

            when {
                state.loading && state.messages.isEmpty() -> HintLine("loading transcript…")
                state.loadingOlder -> HintLine("loading older…")
                error != null -> HintLine(error)
                state.hasNewer -> HintLine("turn down for newer messages")
            }
            if (state.loadingOlder && error != null) {
                HintLine("older load failed — turn at the top to retry")
            }

            ThreadNotices(notices = state.notices)
            ThreadView(
                messages = state.messages,
                emptyText = state.emptyText,
                reader = reader,
                agentLabel = state.botName,
                modifier =
                    Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(horizontal = 0.3f.gridUnitsAsDp()),
            )
        }
    }
}
