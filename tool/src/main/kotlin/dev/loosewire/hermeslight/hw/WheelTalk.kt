/*
 * Copyright 2026 LooseWireDev
 *
 * Ported from BrightHermes (owner): app/src/main/kotlin/com/gios/brighthermes/hw/WheelTalk.kt.
 * The hold/click/cancel state machine is the SDK-independent core; only key classification and
 * the default scheduler touch Android. See NOTICE.
 */
package dev.loosewire.hermeslight.hw

import android.os.Handler
import android.os.Looper
import com.thelightphone.lp3Keyboard.ui.LightDeviceKeys

/** One semantic control behind the phone's physical keys. */
enum class WheelKey {
    Click,
    Up,
    Down,
    Focus,
    Camera,
}

/** Cancels a scheduled hold decision that has not fired yet. */
fun interface WheelTimeout {
    fun cancel()
}

/** The one delayed decision WheelTalk needs; injected so unit tests control time. */
fun interface WheelScheduler {
    fun schedule(
        delayMs: Long,
        action: () -> Unit,
    ): WheelTimeout
}

/** Handler-backed production scheduler; the timer must run on the main thread. */
class MainThreadWheelScheduler(
    private val handler: Handler = Handler(Looper.getMainLooper()),
) : WheelScheduler {
    override fun schedule(
        delayMs: Long,
        action: () -> Unit,
    ): WheelTimeout {
        val runnable = Runnable { action() }
        handler.postDelayed(runnable, delayMs)
        return WheelTimeout { handler.removeCallbacks(runnable) }
    }
}

/**
 * Wheel keys seen since launch, for the diagnostics readout.
 *
 * [clicks] counts DOWN events on the click key, not completed clicks: a hold that started proves
 * the key reached the app too, which is exactly what the BrightControl diagnosis needs.
 */
data class WheelCounters(
    val clicks: Int = 0,
    val turns: Int = 0,
    val holds: Int = 0,
    val cancels: Int = 0,
)

/**
 * Process-wide key-arrival counters: "hold does nothing" is either a key swallowed before this
 * app or a key that arrived and did nothing, and the phone cannot tell them apart without this.
 */
object WheelDiagnostics {
    /** Turns that make a missing click worth naming, not merely a wheel that has not been used. */
    const val WARNING_TURNS = 12

    private var clicks = 0
    private var turns = 0
    private var holds = 0
    private var cancels = 0

    fun clickSeen() {
        clicks++
    }

    fun turnSeen() {
        turns++
    }

    fun holdSeen() {
        holds++
    }

    fun cancelSeen() {
        cancels++
    }

    fun snapshot(): WheelCounters = WheelCounters(clicks, turns, holds, cancels)

    /** Test hook and a way to restart the readout after a fresh install/probe. */
    fun reset() {
        clicks = 0
        turns = 0
        holds = 0
        cancels = 0
    }

    /**
     * One line naming the one cause that matches "turns arrive, the press does not".
     *
     * Null until turns prove the wheel works; a phone nobody has touched says nothing.
     */
    fun warning(): String? {
        if (turns < WARNING_TURNS || clicks > 0) return null
        return "Wheel turns reach this app but its click does not. In BrightControl, set the " +
            "per-app rule for dev.loosewire.hermeslight to Off (hands-off)."
    }
}

/**
 * The wheel, pointed at this app: turns scroll, a click acts, a **hold is talk**.
 *
 * A press that lasts [HOLD_MS] with no turn becomes push-to-talk. Release ends the take. A turn
 * while the wheel is pressed spends the press: it is neither a click nor a hold, so it neither
 * scrolls nor sends, even before [HOLD_MS] has passed. A bare turn scrolls.
 *
 * [dispatch] is deliberately Android-free: callers pass a key code, a down/up flag and the repeat
 * count, so the split and cancel rules are unit-tested with a fake [WheelScheduler]. The
 * [ThreadWheelViewModel] supplies real [android.view.KeyEvent]s.
 */
class WheelTalk(
    private val onClick: () -> Unit,
    private val onHoldStart: () -> Unit = {},
    private val onHoldEnd: () -> Unit = {},
    /** A turn while pressed: discard the press/take. The UP that follows acts on nothing. */
    private val onHoldCancel: () -> Unit = {},
    /** A bare turn, or a pressed turn on a screen with voice disabled: +1 up, -1 down. */
    private val onTurn: (steps: Int) -> Unit = {},
    private val scheduler: WheelScheduler = MainThreadWheelScheduler(),
    private val holdMs: Long = HOLD_MS,
    /** Monotonic milliseconds; injected so tests can hold time still while a callback is late. */
    private val nowMs: () -> Long = { System.nanoTime() / 1_000_000L },
    /**
     * False on list screens: no hold timer, so a press always releases as a click and turns always
     * scroll. A turn still spends the press, keeping a press-and-turn from becoming a click.
     */
    var voiceEnabled: Boolean = true,
) {
    private var timeout: WheelTimeout? = null
    private var held = false
    private var pressedAt = 0L

    /** Whether this press has already been spent — as a turn, a hold, or a cancel. */
    private var spent = false

    private var holding = false
    private var shutterHolding = false

    /** Whether the current press already emitted [onHoldCancel]; later turns at it are no-ops. */
    private var cancelled = false

    /** True if [keyCode] was one of ours and has been dealt with, on both DOWN and UP. */
    fun dispatch(
        keyCode: Int,
        down: Boolean,
        repeatCount: Int = 0,
    ): Boolean {
        when (keyOf(keyCode) ?: return false) {
            WheelKey.Click -> if (down) clickDown(repeatCount) else clickUp()
            WheelKey.Up -> if (down) turn(up = true)
            WheelKey.Down -> if (down) turn(up = false)
            WheelKey.Focus, WheelKey.Camera -> shutter(down, repeatCount)
        }
        return true
    }

    /** The activity is going away mid-press: cancel an active take, never send. */
    fun reset() {
        cancelTimeout()
        val wasHolding = holding || shutterHolding
        held = false
        spent = false
        holding = false
        shutterHolding = false
        cancelled = false
        if (wasHolding) {
            WheelDiagnostics.cancelSeen()
            onHoldCancel()
        }
    }

    private fun clickDown(repeatCount: Int) {
        if (repeatCount != 0) return
        // A DOWN without the matching UP (screen change, key loss) ends the old press first.
        if (held || holding || shutterHolding) reset()
        WheelDiagnostics.clickSeen()
        held = true
        pressedAt = nowMs()
        spent = false
        cancelled = false
        if (!voiceEnabled) return
        timeout =
            scheduler.schedule(holdMs) {
                timeout = null
                beginHold()
            }
    }

    private fun clickUp() {
        // An orphan UP (after reset/hide, or a duplicate) must not fabricate a click.
        if (!held) return
        cancelTimeout()
        // A late main-looper callback loses to the clock: a long press is still a take, not a click.
        if (!holding && !spent && voiceEnabled && nowMs() - pressedAt >= holdMs) beginHold()
        held = false
        when {
            holding -> {
                holding = false
                onHoldEnd()
            }
            !spent -> onClick()
        }
        cancelled = false
    }

    private fun beginHold() {
        if (held && !spent && !cancelled) {
            spent = true
            holding = true
            WheelDiagnostics.holdSeen()
            onHoldStart()
        }
    }

    private fun turn(up: Boolean) {
        WheelDiagnostics.turnSeen()
        val step = if (up) 1 else -1
        if (voiceEnabled && (held || shutterHolding)) {
            if (!cancelled) {
                cancelled = true
                spent = true
                cancelTimeout()
                holding = false
                shutterHolding = false
                WheelDiagnostics.cancelSeen()
                onHoldCancel()
            }
            return
        }
        if (held) {
            // List mode: no take exists, so the turn scrolls, but it still spends the click.
            spent = true
            cancelTimeout()
        }
        onTurn(step)
    }

    private fun shutter(
        down: Boolean,
        repeatCount: Int,
    ) {
        if (!voiceEnabled) return
        if (down && repeatCount > 0) return
        if (down) {
            // One press at a time: a canceled wheel press or an existing take owns this key stream.
            if (!shutterHolding && !holding && !held && !cancelled) {
                shutterHolding = true
                WheelDiagnostics.holdSeen()
                onHoldStart()
            }
        } else if (shutterHolding) {
            shutterHolding = false
            onHoldEnd()
        } else if (cancelled) {
            // The cancel belonged to this shutter press; its UP ends the gesture.
            cancelled = false
        }
    }

    private fun cancelTimeout() {
        timeout?.cancel()
        timeout = null
    }

    companion object {
        /** How long a press has to last, with no turn, before it is a hold. Under a human "click". */
        const val HOLD_MS = 320L

        /**
         * The SDK device-key map first (`RotaryTurnUp/Down` 317/318, `RotaryButtonPress` 319,
         * `ShutterHalfPressed` 80, `ShutterPressed` 27), then the raw Android codes a debuggable
         * emulator or a differently-configured phone can send: DPAD_UP 19, DPAD_DOWN 20,
         * ENTER 66, FOCUS 80, CAMERA 27.
         */
        fun keyOf(keyCode: Int): WheelKey? {
            when (LightDeviceKeys.mapping[keyCode]) {
                LightDeviceKeys.RotaryButtonPress -> return WheelKey.Click
                LightDeviceKeys.RotaryTurnUp -> return WheelKey.Up
                LightDeviceKeys.RotaryTurnDown -> return WheelKey.Down
                LightDeviceKeys.ShutterHalfPressed -> return WheelKey.Focus
                LightDeviceKeys.ShutterPressed -> return WheelKey.Camera
                else -> Unit
            }
            return when (keyCode) {
                19 -> WheelKey.Up
                20 -> WheelKey.Down
                66 -> WheelKey.Click
                80 -> WheelKey.Focus
                27 -> WheelKey.Camera
                else -> null
            }
        }
    }
}
