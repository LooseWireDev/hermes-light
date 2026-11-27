package dev.loosewire.hermeslight.ui.common

import android.os.SystemClock
import android.view.KeyEvent
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.onPreviewKeyEvent
import com.thelightphone.lp3Keyboard.ui.LightDeviceKeys
import dev.loosewire.hermeslight.hw.ThreadWheelViewModel

/** One SDK-owned handler, shared by native delivery and the Compose preview path. */
internal val LocalScreenWheel = compositionLocalOf<ThreadWheelViewModel?> { null }

@Composable
internal fun BindScreenWheel(
    onClick: () -> Unit,
    onUp: () -> Unit = {},
    onDown: () -> Unit = {},
    onHoldStart: (() -> Unit)? = null,
    onHoldEnd: (() -> Unit)? = null,
    onHoldCancel: (() -> Unit)? = null,
) {
    val wheel = LocalScreenWheel.current
    SideEffect {
        wheel?.bind(
            onClick = onClick,
            onHoldStart = onHoldStart,
            onHoldEnd = onHoldEnd,
            onHoldCancel = onHoldCancel,
            onTurn = { steps -> if (steps > 0) onUp() else onDown() },
        )
    }
    DisposableEffect(wheel) {
        onDispose { wheel?.unbind() }
    }
}

private const val LONG_BACK_MS = 700L

private val ROTARY_UP = LightDeviceKeys.RotaryTurnUp.keyCode
private val ROTARY_DOWN = LightDeviceKeys.RotaryTurnDown.keyCode
private val ROTARY_CLICK = LightDeviceKeys.RotaryButtonPress.keyCode

private fun isUpKey(keyCode: Int): Boolean =
    keyCode == ROTARY_UP || keyCode == KeyEvent.KEYCODE_DPAD_UP || keyCode == KeyEvent.KEYCODE_PAGE_UP

private fun isDownKey(keyCode: Int): Boolean =
    keyCode == ROTARY_DOWN || keyCode == KeyEvent.KEYCODE_DPAD_DOWN || keyCode == KeyEvent.KEYCODE_PAGE_DOWN

private fun isClickKey(keyCode: Int): Boolean =
    keyCode == ROTARY_CLICK ||
        keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
        keyCode == KeyEvent.KEYCODE_ENTER ||
        keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER ||
        keyCode == KeyEvent.KEYCODE_SPACE

private fun isBackKey(keyCode: Int): Boolean = keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_ESCAPE

/**
 * The wheel contract for a whole screen.
 *
 * The screen is the focus owner, so turns and clicks reach Compose before LightActivity can
 * forward them to the server. BACK is consumed here as well: the SDK activity's back callback
 * bypasses screen navigation, so the screen must decide one-level-up itself.
 *
 * Turn up/down moves focus; click activates the focused row; a long press of BACK opens Setup;
 * a short BACK goes one level up (Home consumes it and stays). Keyboard DPAD/ENTER and Escape
 * are additive and follow the same rules.
 */
@Composable
fun WheelScroll(
    onUp: () -> Unit,
    onDown: () -> Unit,
    onClick: () -> Unit,
    onBack: () -> Unit,
    onLongBack: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    onHoldStart: (() -> Unit)? = null,
    onHoldEnd: (() -> Unit)? = null,
    onHoldCancel: (() -> Unit)? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    val wheel = LocalScreenWheel.current
    BindScreenWheel(onClick, onUp, onDown, onHoldStart, onHoldEnd, onHoldCancel)
    val up = rememberUpdatedState(onUp)
    val down = rememberUpdatedState(onDown)
    val click = rememberUpdatedState(onClick)
    val back = rememberUpdatedState(onBack)
    val longBack = rememberUpdatedState(onLongBack)
    val focusRequester = remember { FocusRequester() }
    val backDownAt = remember { LongArray(1) }
    val longBackFired = remember { BooleanArray(1) }

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    Box(
        modifier =
            modifier
                .fillMaxSize()
                .onPreviewKeyEvent { event ->
                    val native = event.nativeKeyEvent
                    val keyCode = native.keyCode
                    when {
                        wheel?.dispatch(native) == true -> true
                        isUpKey(keyCode) -> {
                            if (native.action == KeyEvent.ACTION_DOWN && native.repeatCount == 0) {
                                up.value()
                            }
                            true
                        }

                        isDownKey(keyCode) -> {
                            if (native.action == KeyEvent.ACTION_DOWN && native.repeatCount == 0) {
                                down.value()
                            }
                            true
                        }

                        isClickKey(keyCode) -> {
                            if (native.action == KeyEvent.ACTION_UP) {
                                click.value()
                            }
                            true
                        }

                        isBackKey(keyCode) -> {
                            val longAction = longBack.value
                            when (native.action) {
                                KeyEvent.ACTION_DOWN -> {
                                    if (native.repeatCount == 0) {
                                        backDownAt[0] = SystemClock.elapsedRealtime()
                                        longBackFired[0] = false
                                    } else if (longAction != null && !longBackFired[0] &&
                                        SystemClock.elapsedRealtime() - backDownAt[0] >= LONG_BACK_MS
                                    ) {
                                        longBackFired[0] = true
                                        longAction()
                                    }
                                }

                                KeyEvent.ACTION_UP -> {
                                    val held = SystemClock.elapsedRealtime() - backDownAt[0]
                                    if (!longBackFired[0] && longAction != null && held >= LONG_BACK_MS) {
                                        longBackFired[0] = true
                                        longAction()
                                    } else if (!longBackFired[0]) {
                                        back.value()
                                    }
                                }
                            }
                            true
                        }

                        else -> false
                    }
                }.focusRequester(focusRequester)
                .focusable(),
        content = content,
    )
}

/**
 * BACK for a screen rendered outside [WheelScroll] (the Setup text editor). The focused field
 * keeps receiving every other key, including typing and rotary turns.
 */
fun Modifier.consumeBackKey(onBack: () -> Unit): Modifier =
    onPreviewKeyEvent { event ->
        val native = event.nativeKeyEvent
        if (isBackKey(native.keyCode)) {
            if (native.action == KeyEvent.ACTION_UP) {
                onBack()
            }
            true
        } else {
            false
        }
    }
