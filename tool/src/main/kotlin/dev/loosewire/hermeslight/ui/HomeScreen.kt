package dev.loosewire.hermeslight.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.gridUnitsAsDp
import com.thelightphone.sdk.ui.verticalGridUnitsAsDp
import dev.loosewire.hermeslight.ui.common.FocusRow
import dev.loosewire.hermeslight.ui.common.SectionLabel
import dev.loosewire.hermeslight.ui.common.StatusLine
import dev.loosewire.hermeslight.ui.common.ThreadNotices
import dev.loosewire.hermeslight.ui.common.ThreadView
import dev.loosewire.hermeslight.ui.common.WheelScroll
import dev.loosewire.hermeslight.ui.common.rememberThreadReader

private enum class HomeZone {
    Deck,
    Thread,
}

/**
 * Home: Menu action, deck on top, transcript below, one status line.
 *
 * Wheel contract here: the thread's top crosses into the deck's bottom; the deck's top opens
 * Menu. With no messages, the deck is the initial zone, so one wheel-up reaches Menu.
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
        )
    var zone by remember(thread.sessionId) {
        mutableStateOf(if (thread.messages.isEmpty()) HomeZone.Deck else HomeZone.Thread)
    }
    var deckIndex by remember(thread.sessionId, state.deck.tiles.size) { mutableIntStateOf(0) }
    var interacted by remember(thread.sessionId) { mutableStateOf(false) }

    LaunchedEffect(thread.sessionId, thread.messages.isNotEmpty()) {
        if (!interacted && thread.messages.isNotEmpty()) {
            reader.ensureFocus()
            zone = HomeZone.Thread
        }
    }

    fun enterDeck() {
        deckIndex =
            state.deck.tiles.lastIndex
                .coerceAtLeast(0)
        zone = HomeZone.Deck
    }

    WheelScroll(
        onUp = {
            interacted = true
            when (zone) {
                HomeZone.Thread -> {
                    if (!reader.moveUp()) enterDeck()
                }

                HomeZone.Deck -> {
                    if (deckIndex > 0) {
                        deckIndex--
                    } else {
                        actions.onOpenMenu()
                    }
                }
            }
        },
        onDown = {
            interacted = true
            when (zone) {
                HomeZone.Thread -> {
                    if (!reader.moveDown() && thread.hasNewer) {
                        actions.threadActions.onLoadNewer()
                    }
                }

                HomeZone.Deck -> {
                    if (deckIndex < state.deck.tiles.lastIndex) {
                        deckIndex++
                    } else if (thread.messages.isNotEmpty()) {
                        reader.ensureFocus()
                        zone = HomeZone.Thread
                    }
                }
            }
        },
        onClick = {
            interacted = true
            when (zone) {
                HomeZone.Thread -> reader.activate()
                HomeZone.Deck -> {
                    val tile = state.deck.tiles.getOrNull(deckIndex)
                    if (tile != null && tile.actionable) actions.onOpenDeckTile(tile.id)
                }
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
            LightTopBar(leftButton = LightBarButton.Text("MENU", onClick = actions.onOpenMenu))
            SectionLabel("Deck")
            if (state.deck.tiles.isEmpty()) {
                FocusRow(focused = zone == HomeZone.Deck) {
                    LightText(
                        text = state.deck.emptyText,
                        variant = LightTextVariant.Paragraph,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            } else {
                state.deck.tiles.forEachIndexed { index, tile ->
                    val focused = zone == HomeZone.Deck && deckIndex == index
                    FocusRow(
                        focused = focused,
                        enabled = tile.actionable,
                        onClick = { actions.onOpenDeckTile(tile.id) },
                    ) {
                        LightText(
                            text = tile.label.uppercase(),
                            variant = LightTextVariant.Superfine,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        LightText(
                            text = tile.value,
                            variant = LightTextVariant.Copy,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        tile.detail?.takeIf { it.isNotBlank() }?.let { detail ->
                            LightText(
                                text = detail,
                                variant = LightTextVariant.Superfine,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(0.3f.verticalGridUnitsAsDp()))
            ThreadNotices(
                notices = thread.notices,
                modifier = Modifier.padding(bottom = 0.2f.verticalGridUnitsAsDp()),
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
                showFocus = zone == HomeZone.Thread,
            )
            StatusLine(status = thread.status)
        }
    }
}
