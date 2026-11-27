package dev.loosewire.hermeslight.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.gridUnitsAsDp
import com.thelightphone.sdk.ui.lightClickable
import com.thelightphone.sdk.ui.verticalGridUnitsAsDp

/** No layout node, padding or reserved height exists when attention is absent. */
@Composable
internal fun HomeStatusStrip(
    state: StatusStripUi?,
    focused: Boolean = false,
    onOpen: (String?) -> Unit,
) {
    if (state == null) return
    LightText(
        text = if (focused) "› ${state.summary}" else state.summary,
        variant = LightTextVariant.Detail,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier =
            Modifier
                .fillMaxWidth()
                .lightClickable { onOpen(state.sessionId) }
                .padding(
                    horizontal = 0.8f.gridUnitsAsDp(),
                    vertical = 0.25f.verticalGridUnitsAsDp(),
                ),
    )
}
