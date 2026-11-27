package dev.loosewire.hermeslight.ui.common

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.gridUnitsAsDp
import com.thelightphone.sdk.ui.lightClickable
import com.thelightphone.sdk.ui.verticalGridUnitsAsDp

private val FOCUS_RULE_WIDTH = 2.dp
private val FOCUS_INSET_UNITS = 0.8f

/**
 * One wheel-focusable row. Focus is a solid rule at the left edge, not an inverted card: on an
 * AMOLED panel the rule is the quietest mark that still reads at arm's length.
 */
@Composable
fun FocusRow(
    focused: Boolean,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = LightThemeTokens.colors
    val density = LocalDensity.current
    val ruleWidthPx = with(density) { FOCUS_RULE_WIDTH.toPx() }
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .then(
                    if (onClick != null) {
                        Modifier.lightClickable(enabled = enabled, onClick = onClick)
                    } else {
                        Modifier
                    },
                ).drawBehind {
                    if (focused) {
                        drawRect(
                            color = colors.content,
                            size = Size(ruleWidthPx, size.height),
                        )
                    }
                }.padding(
                    start = FOCUS_INSET_UNITS.gridUnitsAsDp(),
                    end = 1f.gridUnitsAsDp(),
                    top = 0.45f.verticalGridUnitsAsDp(),
                    bottom = 0.45f.verticalGridUnitsAsDp(),
                ),
        content = content,
    )
}

/** A quiet uppercase label that introduces a section, not a row. */
@Composable
fun SectionLabel(
    text: String,
    modifier: Modifier = Modifier,
) {
    LightText(
        text = text.uppercase(),
        variant = LightTextVariant.Superfine,
        maxLines = 1,
        modifier =
            modifier.padding(
                start = FOCUS_INSET_UNITS.gridUnitsAsDp(),
                end = 1f.gridUnitsAsDp(),
                top = 0.8f.verticalGridUnitsAsDp(),
                bottom = 0.2f.verticalGridUnitsAsDp(),
            ),
    )
}

/** A non-focusable explanatory line, used for loading, empty and failure states. */
@Composable
fun HintLine(
    text: String,
    modifier: Modifier = Modifier,
) {
    LightText(
        text = text,
        variant = LightTextVariant.Superfine,
        modifier =
            modifier.padding(
                start = FOCUS_INSET_UNITS.gridUnitsAsDp(),
                end = 1f.gridUnitsAsDp(),
                top = 0.35f.verticalGridUnitsAsDp(),
                bottom = 0.35f.verticalGridUnitsAsDp(),
            ),
    )
}

/** A divider with no Material ink: a single hairline in the secondary tone. */
@Composable
fun RuleLine(modifier: Modifier = Modifier) {
    val color = LightThemeTokens.colors.contentSecondary
    Spacer(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(horizontal = 1f.gridUnitsAsDp())
                .height(1.dp)
                .drawBehind {
                    drawRect(
                        color = color,
                        size = Size(size.width, 1.dp.toPx()),
                    )
                },
    )
}
