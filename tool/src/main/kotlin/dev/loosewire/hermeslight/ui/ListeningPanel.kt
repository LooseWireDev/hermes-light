package dev.loosewire.hermeslight.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.gridUnitsAsDp
import com.thelightphone.sdk.ui.verticalGridUnitsAsDp
import kotlinx.coroutines.delay
import kotlin.math.sqrt

private const val BAR_COUNT = 13
private const val BAR_INTERVAL_MS = 90L

/**
 * The full content-area voice takeover: listening state, the partial transcript, the moving
 * level meter and the release hint. The thread, status strip and composer are not composed behind
 * it. The wheel bindings stay mounted in the calling screen, so release and turn-to-cancel still
 * reach their handlers mid-gesture.
 */
@Composable
fun ListeningPanel(
    phase: VoicePhase,
    level: Float,
    partial: String,
    modifier: Modifier = Modifier,
) {
    if (phase == VoicePhase.Idle) return
    val colors = LightThemeTokens.colors
    val transcribing = phase == VoicePhase.Transcribing
    val bars = remember { mutableStateListOf<Float>().apply { repeat(BAR_COUNT) { add(0f) } } }
    val latestLevel = rememberUpdatedState(level.coerceIn(0f, 1f))
    val scroll = rememberScrollState()

    // Oldest reading leaves the left; each tick is one 90 ms column. Transcribing freezes the
    // waveform at the last speech level instead of pretending the microphone is still live.
    LaunchedEffect(phase) {
        if (phase != VoicePhase.Listening) return@LaunchedEffect
        while (true) {
            delay(BAR_INTERVAL_MS)
            bars.removeAt(0)
            bars.add(displayLevel(latestLevel.value))
        }
    }
    LaunchedEffect(partial, scroll.maxValue) {
        if (scroll.maxValue > 0) scroll.scrollTo(scroll.maxValue)
    }

    Column(
        modifier =
            modifier
                .fillMaxSize()
                .background(colors.background)
                .padding(horizontal = 1f.gridUnitsAsDp(), vertical = 0.5f.verticalGridUnitsAsDp()),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier =
                    Modifier
                        .width(0.6f.gridUnitsAsDp())
                        .height(0.6f.gridUnitsAsDp())
                        .background(if (transcribing) colors.contentSecondary else colors.content),
            )
            Spacer(Modifier.width(0.6f.gridUnitsAsDp()))
            LightText(
                text = if (transcribing) "TRANSCRIBING" else "LISTENING",
                variant = LightTextVariant.Detail,
                color = colors.content,
            )
        }
        Spacer(Modifier.height(1f.verticalGridUnitsAsDp()))
        Box(
            modifier =
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(scroll),
        ) {
            LightText(
                text = partial.ifBlank { if (transcribing) "…" else "" },
                variant = LightTextVariant.ParagraphWide,
                color = if (partial.isBlank()) colors.contentSecondary else colors.content,
            )
        }
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(top = 0.5f.verticalGridUnitsAsDp()),
            verticalArrangement = Arrangement.spacedBy(0.5f.verticalGridUnitsAsDp()),
        ) {
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(3f.verticalGridUnitsAsDp()),
                horizontalArrangement = Arrangement.spacedBy(0.25f.gridUnitsAsDp()),
                verticalAlignment = Alignment.Bottom,
            ) {
                bars.forEach { value ->
                    Box(
                        modifier =
                            Modifier
                                .width(0.45f.gridUnitsAsDp())
                                .height((0.5f + value * 2.5f).verticalGridUnitsAsDp())
                                .background(colors.content),
                    )
                }
            }
            LightText(
                text = if (transcribing) "transcribing…" else "let go to send · turn the wheel to cancel",
                variant = LightTextVariant.Superfine,
                color = colors.contentSecondary,
            )
        }
    }
}

/**
 * Speech RMS on this microphone is around 0.03, so a linear meter would look dead. Gain and a
 * square root give an arm's-length reaction without changing the level contract itself.
 */
private fun displayLevel(level: Float): Float = sqrt((level * 4f).coerceIn(0f, 1f))
