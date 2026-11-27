package dev.loosewire.hermeslight.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.gridUnitsAsDp
import dev.loosewire.hermeslight.ui.common.StatusLine
import dev.loosewire.hermeslight.ui.common.ThreadView
import dev.loosewire.hermeslight.ui.common.WheelScroll
import dev.loosewire.hermeslight.ui.common.rememberThreadReader

/**
 * Home: History/Bots actions, conditional attention strip, transcript and connection status.
 * The thread's position and older/newer callbacks are shared with the full Thread screen.
 */
@Composable
fun HomeScreen(
    state: HomeUi,
    actions: HomeActions,
) {
    val colors = LightThemeTokens.colors
    val thread = state.thread
    val reader =
        rememberThreadReader(
            sessionId = thread.sessionId,
            messages = thread.messages,
            position = thread.position,
            onPositionChanged = actions.threadActions.onPositionChanged,
            onActivate = { message -> actions.onOpenThread(message.id) },
            onAction = actions.threadActions.onAction,
        )
    var focus by remember(thread.sessionId) {
        mutableStateOf(if (thread.messages.isEmpty()) HomeWheelFocus.History else HomeWheelFocus.Thread)
    }
    val wheelFocus =
        if (focus == HomeWheelFocus.Status && state.statusStrip == null) HomeWheelFocus.History else focus
    LaunchedEffect(state.statusStrip == null) {
        if (state.statusStrip == null && focus == HomeWheelFocus.Status) focus = HomeWheelFocus.History
    }

    WheelScroll(
        onUp = {
            if (wheelFocus == HomeWheelFocus.Thread) {
                if (!reader.moveUp() && thread.hasOlder) actions.threadActions.onLoadOlder()
            } else {
                focus = wheelFocus.previous(hasMessages = thread.messages.isNotEmpty())
            }
        },
        onDown = {
            if (wheelFocus != HomeWheelFocus.Thread || !reader.moveDown()) {
                if (wheelFocus == HomeWheelFocus.Thread && thread.hasNewer) {
                    actions.threadActions.onLoadNewer()
                } else {
                    focus =
                        wheelFocus.next(
                            hasMessages = thread.messages.isNotEmpty(),
                            hasStatus = state.statusStrip != null,
                        )
                }
            }
        },
        onClick = {
            when (wheelFocus) {
                HomeWheelFocus.Thread -> reader.activate()
                HomeWheelFocus.History -> actions.onOpenHistory()
                HomeWheelFocus.Bots -> actions.onOpenBots()
                HomeWheelFocus.Status -> state.statusStrip?.let { actions.onOpenStatus(it.sessionId) }
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
                    LightBarButton.Text(
                        if (wheelFocus == HomeWheelFocus.History) "› History" else "History",
                        onClick = actions.onOpenHistory,
                    ),
                rightButton =
                    LightBarButton.Text(
                        if (wheelFocus == HomeWheelFocus.Bots) "› Bots" else "Bots",
                        onClick = actions.onOpenBots,
                    ),
            )
            HomeStatusStrip(
                state.statusStrip,
                focused = wheelFocus == HomeWheelFocus.Status,
                onOpen = actions.onOpenStatus,
            )
            ThreadView(
                messages = thread.messages,
                emptyText = thread.emptyText,
                reader = reader,
                agentLabel = thread.botName,
                modifier =
                    Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(horizontal = 0.3f.gridUnitsAsDp()),
                showFocus = wheelFocus == HomeWheelFocus.Thread,
            )
            StatusLine(status = thread.status)
        }
    }
}
