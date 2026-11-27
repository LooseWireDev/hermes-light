package dev.loosewire.hermeslight.ui.common

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.gridUnitsAsDp
import dev.loosewire.hermeslight.ui.StatusLineModel
import dev.loosewire.hermeslight.ui.StatusTone

/**
 * The one honest link line. Every screen renders exactly one of these, fed by the application
 * layer: connecting, connected, reconnecting (n), or the error text.
 */
@Composable
fun StatusLine(
    status: StatusLineModel,
    modifier: Modifier = Modifier,
) {
    val colors = LightThemeTokens.colors
    val glyph =
        when (status.tone) {
            StatusTone.Idle -> "·"
            StatusTone.Busy -> "◌"
            StatusTone.Ok -> "●"
            StatusTone.Warn -> "!"
            StatusTone.Error -> "×"
        }
    val textColor = if (status.tone == StatusTone.Error) colors.content else colors.contentSecondary
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(horizontal = 1f.gridUnitsAsDp(), vertical = 0.35f.gridUnitsAsDp()),
        verticalAlignment = Alignment.Top,
    ) {
        LightText(
            text = glyph,
            variant = LightTextVariant.Superfine,
            color = textColor,
        )
        Spacer(Modifier.width(0.35f.gridUnitsAsDp()))
        LightText(
            text = status.text,
            variant = LightTextVariant.Superfine,
            color = textColor,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
