/*
 * Copyright 2026 LooseWireDev
 */
package dev.loosewire.hermeslight.hw

import android.view.KeyEvent
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SimpleLightScreen

/**
 * The one wheel state machine a LightScreen owns for its lifetime.
 *
 * The SDK forwards device keys to the visible screen, so the screen's view model is the only place
 * a hold can survive recomposition. Compose binds the current callbacks (and, on list screens,
 * leaves the hold callbacks null); activity key dispatch lands in [onKeyDown]/[onKeyUp], while
 * composables that see the key first call [dispatch].
 */
class ThreadWheelViewModel(
    scheduler: WheelScheduler = MainThreadWheelScheduler(),
    holdMs: Long = WheelTalk.HOLD_MS,
) : LightViewModel<Unit>() {
    private var onClick: (() -> Unit)? = null
    private var onHoldStart: (() -> Unit)? = null
    private var onHoldEnd: (() -> Unit)? = null
    private var onHoldCancel: (() -> Unit)? = null
    private var onTurn: ((steps: Int) -> Unit)? = null

    private val wheel =
        WheelTalk(
            onClick = { onClick?.invoke() },
            onHoldStart = { onHoldStart?.invoke() },
            onHoldEnd = { onHoldEnd?.invoke() },
            onHoldCancel = { onHoldCancel?.invoke() },
            onTurn = { steps -> onTurn?.invoke(steps) },
            scheduler = scheduler,
            holdMs = holdMs,
            voiceEnabled = false,
        )

    /** Wheel presses, turns, holds and cancels observed since launch. */
    val counters: WheelCounters
        get() = WheelDiagnostics.snapshot()

    /**
     * Binds this screen's current actions. Called again on every recomposition so the closures see
     * fresh state; no state is reset here.
     *
     * Passing no hold callbacks (or `voiceEnabled = false`) selects list mode: no hold timer, a
     * press always releases as a click, and turns scroll even while the wheel is pressed. A turn
     * still spends the press, so press-and-turn does not click on release.
     */
    fun bind(
        onClick: () -> Unit,
        onHoldStart: (() -> Unit)? = null,
        onHoldEnd: (() -> Unit)? = null,
        onHoldCancel: (() -> Unit)? = null,
        onTurn: (steps: Int) -> Unit = {},
        voiceEnabled: Boolean = onHoldStart != null,
    ) {
        this.onClick = onClick
        this.onHoldStart = onHoldStart
        this.onHoldEnd = onHoldEnd
        this.onHoldCancel = onHoldCancel
        this.onTurn = onTurn
        wheel.voiceEnabled = voiceEnabled && onHoldStart != null
    }

    /** Detach the composition and discard any press or take still in flight. */
    fun unbind() {
        wheel.reset()
        onClick = null
        onHoldStart = null
        onHoldEnd = null
        onHoldCancel = null
        onTurn = null
        wheel.voiceEnabled = false
    }

    /** Drop a press that outlived its UP (hide, pause, destroy); a running take is cancelled. */
    fun reset() = wheel.reset()

    /** True when this event was a wheel key and was handled; false passes Back/Home through. */
    fun dispatch(
        keyCode: Int,
        down: Boolean,
        repeatCount: Int = 0,
    ): Boolean = wheel.dispatch(keyCode, down, repeatCount)

    /** The Compose preview path, which sees the same [KeyEvent]s the activity would. */
    fun dispatch(event: KeyEvent): Boolean =
        dispatch(
            keyCode = event.keyCode,
            down = event.action == KeyEvent.ACTION_DOWN,
            repeatCount = event.repeatCount,
        )

    override fun onKeyDown(
        keyCode: Int,
        event: KeyEvent,
    ): Boolean = wheel.dispatch(keyCode, down = true, repeatCount = event.repeatCount)

    override fun onKeyUp(
        keyCode: Int,
        event: KeyEvent,
    ): Boolean = wheel.dispatch(keyCode, down = false, repeatCount = event.repeatCount)

    override fun onScreenHide(screen: SimpleLightScreen<Unit>) {
        reset()
    }

    override fun onAppPause() {
        reset()
    }

    override fun onCleared() {
        unbind()
        super.onCleared()
    }
}
