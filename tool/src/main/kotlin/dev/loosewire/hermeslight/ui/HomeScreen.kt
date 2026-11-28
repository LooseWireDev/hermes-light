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
import com.thelightphone.sdk.ui.LightTopBarCenter
import com.thelightphone.sdk.ui.gridUnitsAsDp
import dev.loosewire.hermeslight.ui.common.HintLine
import dev.loosewire.hermeslight.ui.common.StatusLine
import dev.loosewire.hermeslight.ui.common.ThreadView
import dev.loosewire.hermeslight.ui.common.WheelScroll
import dev.loosewire.hermeslight.ui.common.rememberThreadReader

/**
 * Home: a single Menu action, conditional attention strip, transcript, composer and connection
 * status. The thread's position and older/newer callbacks are shared with the full Thread screen.
 *
 * While a voice take is live the whole content area is the listening panel; the wheel bindings
 * stay on [WheelScroll], so release and turn-to-cancel keep reaching their handlers.
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
        mutableStateOf(if (thread.messages.isEmpty()) HomeWheelFocus.Composer else HomeWheelFocus.Thread)
    }
    val wheelFocus =
        if (focus == HomeWheelFocus.Status && state.statusStrip == null) HomeWheelFocus.Menu else focus
    LaunchedEffect(state.statusStrip == null) {
        if (state.statusStrip == null && focus == HomeWheelFocus.Status) focus = HomeWheelFocus.Menu
    }

    // A saved editor draft, a completed voiced take, or a fresh draft focuses Send. Entering Home
    // with no draft change keeps the reader's current focus.
    var seenComposer by remember(thread.sessionId) { mutableStateOf(thread.composer) }
    LaunchedEffect(thread.composer) {
        if (thread.composer != seenComposer) {
            seenComposer = thread.composer
            if (thread.composer.isNotBlank()) focus = HomeWheelFocus.Send
        }
    }
    LaunchedEffect(thread.composerFocusRevision) {
        if (thread.composerFocusRevision > 0 && thread.composer.isNotBlank()) {
            focus = HomeWheelFocus.Send
        }
    }

    val voiceActive = thread.voicePhase != VoicePhase.Idle
    val listening = thread.voicePhase == VoicePhase.Listening

    fun sendDraft() {
        if (thread.composer.isNotBlank()) actions.threadActions.onSubmit(thread.composer)
    }

    WheelScroll(
        onUp = {
            if (voiceActive) {
                if (listening) actions.threadActions.onHoldCancel()
            } else if (wheelFocus == HomeWheelFocus.Thread) {
                if (!reader.moveUp() && thread.hasOlder) actions.threadActions.onLoadOlder()
            } else {
                focus = wheelFocus.previous(hasMessages = thread.messages.isNotEmpty())
            }
        },
        onDown = {
            if (voiceActive) {
                if (listening) actions.threadActions.onHoldCancel()
            } else if (wheelFocus != HomeWheelFocus.Thread || !reader.moveDown()) {
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
            if (!voiceActive) {
                when (wheelFocus) {
                    HomeWheelFocus.Thread -> reader.activate()
                    HomeWheelFocus.Composer -> actions.threadActions.onAction("compose")
                    HomeWheelFocus.Send -> sendDraft()
                    HomeWheelFocus.Menu -> actions.onOpenMenu()
                    HomeWheelFocus.Status -> state.statusStrip?.let { actions.onOpenStatus(it.sessionId) }
                }
            }
        },
        onBack = actions.onBack,
        onLongBack = actions.onLongBack,
        onHoldStart = actions.threadActions.onHoldStart,
        onHoldEnd = actions.threadActions.onHoldEnd,
        onHoldCancel = actions.threadActions.onHoldCancel,
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
                        if (!voiceActive && wheelFocus == HomeWheelFocus.Menu) "› Menu" else "Menu",
                        onClick = actions.onOpenMenu,
                    ),
                center =
                    LightTopBarCenter.Text(
                        thread.title.ifBlank { thread.botName.ifBlank { "Hermes" } },
                    ),
            )
            if (voiceActive) {
                ListeningPanel(
                    phase = thread.voicePhase,
                    level = thread.voiceLevel,
                    partial = thread.voicePartial,
                    modifier = Modifier.weight(1f),
                )
            } else {
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
                thread.voiceFeedback?.let { HintLine(it) }
                MessageComposer(
                    draft = thread.composer,
                    running = thread.running,
                    fieldFocused = wheelFocus == HomeWheelFocus.Composer,
                    sendFocused = wheelFocus == HomeWheelFocus.Send,
                    onOpenEditor = { actions.threadActions.onAction("compose") },
                    onSend = { sendDraft() },
                )
                StatusLine(status = thread.status)
            }
        }
    }
}
