/*
 * Copyright 2026 LooseWireDev
 *
 * Ported from BrightHermes (owner): app/src/main/kotlin/com/gios/brighthermes/hw/WheelTalk.kt.
 * The hold/click/cancel state machine is unchanged; only the wheel interface is the SDK's
 * device-key map. See NOTICE.
 */
package dev.loosewire.hermeslight.hw

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import com.thelightphone.lp3Keyboard.ui.LightDeviceKeys

/**
 * The wheel, pointed at this app: turns scroll, a click switches the deck, a **hold is talk**.
 *
 * Ported from Roll's `LightControls`, which splits a wheel press into click and press-and-turn
 * on one fact: a held `WHEEL_CLICK` produces **no key repeat**. DOWN arrives, then whatever the
 * wheel does while held, then UP. So by the time UP lands you know whether the press was only a
 * press. Roll needs nothing more, because its modifier is "turned while held". A hold that does
 * nothing but last needs a clock, so this adds one: [HOLD_MS] after DOWN, with no turn and no UP,
 * the press becomes push-to-talk. Release sends. A turn during the press is a press-and-turn and
 * spends the click, exactly as in Roll — turning the wheel while holding it is physically not a
 * click, and it is not a hold either.
 *
 * Why the wheel and not the camera button: on LightOS the SDK's device-key map delivers the wheel
 * press, its turns and the shutter presses to the focused screen. The wheel is the control that
 * survives, because its *turns* always come through.
 *
 * [Witness] answers the question that cost Roll days: did the click get here at all. A hold
 * that never starts has two causes and one symptom, and the readout tells them apart.
 */
class WheelTalk(
    private val onClick: () -> Unit,
    private val onHoldStart: () -> Unit,
    private val onHoldEnd: () -> Unit,
    /** A turn of the wheel while holding: throw the take away. The UP that follows sends nothing. */
    private val onHoldCancel: () -> Unit = {},
    /** A turn outside a hold: +1 up, -1 down. [whileHeld] means press-and-turn. */
    private val onTurn: (steps: Int, whileHeld: Boolean) -> Unit = { _, _ -> },
    private val handler: Handler = Handler(Looper.getMainLooper()),
) {
    private enum class Key {
        Click,
        Up,
        Down,
        Focus,
        Camera,
    }

    private var held = false

    /** Whether this press has already been spent — as a press-and-turn or as a hold. */
    private var spent = false

    private var holding = false

    private val becomeHold =
        Runnable {
            if (held && !spent) {
                spent = true
                holding = true
                onHoldStart()
            }
        }

    /** True if [event] was one of ours and has been dealt with. */
    fun dispatch(event: KeyEvent): Boolean {
        val key = keyOf(event) ?: return false
        val down = event.action == KeyEvent.ACTION_DOWN
        when (key) {
            Key.Click -> {
                if (down) {
                    if (event.repeatCount == 0) {
                        Witness.seen()
                        held = true
                        spent = false
                        holding = false
                        handler.removeCallbacks(becomeHold)
                        handler.postDelayed(becomeHold, HOLD_MS)
                    }
                } else {
                    handler.removeCallbacks(becomeHold)
                    held = false
                    when {
                        holding -> {
                            holding = false
                            onHoldEnd()
                        }
                        !spent -> onClick()
                    }
                }
            }
            Key.Up, Key.Down -> {
                // One notch is a complete DOWN+UP pair, so act on DOWN and swallow the UP.
                if (!down) return true
                Witness.turned()
                if (held && holding) {
                    // Turning while talking is the way out: cancel, and let the UP fall through
                    // as nothing. Not sent to the list either — you were not scrolling.
                    holding = false
                    onHoldCancel()
                    return true
                }
                if (held && !holding) {
                    // Press-and-turn. Not a click, not a hold.
                    spent = true
                    handler.removeCallbacks(becomeHold)
                }
                onTurn(if (key == Key.Up) 1 else -1, held)
            }
            // The camera button, for a phone whose key map lets it through. Same gesture:
            // hold to talk, let go to send. Repeats cannot happen on these keys, but guard anyway.
            Key.Focus, Key.Camera -> {
                if (down && event.repeatCount > 0) return true
                if (key == Key.Focus) {
                    if (down && !holding) {
                        holding = true
                        onHoldStart()
                    } else if (!down && holding) {
                        holding = false
                        onHoldEnd()
                    }
                }
            }
        }
        return true
    }

    /** The activity is going away mid-press. */
    fun reset() {
        handler.removeCallbacks(becomeHold)
        held = false
        spent = false
        holding = false
    }

    private fun keyOf(event: KeyEvent): Key? =
        when (LightDeviceKeys.mapping[event.keyCode]) {
            LightDeviceKeys.RotaryButtonPress -> Key.Click
            LightDeviceKeys.RotaryTurnUp -> Key.Up
            LightDeviceKeys.RotaryTurnDown -> Key.Down
            LightDeviceKeys.ShutterHalfPressed -> Key.Focus
            LightDeviceKeys.ShutterPressed -> Key.Camera
            else -> null
        }

    /**
     * Whether a wheel click has ever reached this app since it opened, and how long ago.
     *
     * Roll's `WheelClickWitness`, kept because the diagnosis it enables is the same here: a hold
     * that does nothing is either a key swallowed before this screen or a key that arrived and
     * did nothing, and from the phone the two are identical without this.
     */
    object Witness {
        @Volatile
        private var lastAt = 0L

        @Volatile
        private var turns = 0

        @Volatile
        private var since = SystemClock.elapsedRealtime()

        fun seen() {
            lastAt = SystemClock.elapsedRealtime()
        }

        fun turned() {
            turns++
        }

        fun watchFrom() {
            since = SystemClock.elapsedRealtime()
            lastAt = 0L
            turns = 0
        }

        fun secondsAgo(): Long? = lastAt.takeIf { it != 0L }?.let { (SystemClock.elapsedRealtime() - it) / 1000L }

        fun watchingForSeconds(): Long = (SystemClock.elapsedRealtime() - since) / 1000L

        /**
         * One line for the hint under the input, or null when there is nothing to say.
         *
         * Only when the *turns* are arriving and the click never has. A phone that has not
         * touched the wheel at all says nothing.
         */
        fun warning(): String? {
            if (turns < 12 || secondsAgo() != null) return null
            return "Wheel turns reach this app but its click does not."
        }
    }

    companion object {
        /** How long a press has to last, with no turn, before it is a hold. Under a human "click". */
        const val HOLD_MS = 320L
    }
}
