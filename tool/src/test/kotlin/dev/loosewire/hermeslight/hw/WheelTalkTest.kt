package dev.loosewire.hermeslight.hw

import com.thelightphone.lp3Keyboard.ui.LightDeviceKeys
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val CLICK = 319
private const val TURN_UP = 317
private const val TURN_DOWN = 318
private const val FOCUS = 80
private const val CAMERA = 27

private const val BACK = 4
private const val HOME = 3
private const val VOLUME_UP = 24
private const val VOLUME_DOWN = 25
private const val UNRELATED = 42

private class FakeTask(
    val at: Long,
    val order: Long,
    val action: () -> Unit,
) : WheelTimeout {
    var cancelled = false

    override fun cancel() {
        cancelled = true
    }
}

/** Deterministic time: tasks fire at exactly their due instant, in scheduling order. */
private class FakeScheduler : WheelScheduler {
    private var clock = 0L
    private var order = 0L
    private val tasks = mutableListOf<FakeTask>()

    val now: Long get() = clock

    override fun schedule(
        delayMs: Long,
        action: () -> Unit,
    ): WheelTimeout {
        require(delayMs >= 0)
        val task = FakeTask(clock + delayMs, order++, action)
        tasks += task
        return task
    }

    /** Advance time but deliberately leave due tasks unrun, like a blocked main looper. */
    fun delayBy(ms: Long) {
        require(ms >= 0)
        clock += ms
    }

    fun advanceBy(ms: Long) {
        require(ms >= 0)
        val target = clock + ms
        while (true) {
            val next =
                tasks
                    .filter { !it.cancelled && it.at <= target }
                    .minWithOrNull(compareBy<FakeTask> { it.at }.thenBy { it.order })
                    ?: break
            tasks.remove(next)
            clock = next.at
            next.action()
        }
        clock = target
    }

    fun pending(): Int = tasks.count { !it.cancelled }
}

private class Harness(voiceEnabled: Boolean = true) {
    val scheduler = FakeScheduler()
    var clicks = 0
    var starts = 0
    var ends = 0
    var cancels = 0
    val turns = mutableListOf<Int>()

    val talk =
        WheelTalk(
            onClick = { clicks++ },
            onHoldStart = { starts++ },
            onHoldEnd = { ends++ },
            onHoldCancel = { cancels++ },
            onTurn = { turns += it },
            scheduler = scheduler,
            nowMs = { scheduler.now },
            voiceEnabled = voiceEnabled,
        )

    fun down(code: Int): Boolean = talk.dispatch(code, down = true)

    fun up(code: Int): Boolean = talk.dispatch(code, down = false)
}

class WheelTalkTest {
    @BeforeTest
    fun resetDiagnostics() {
        WheelDiagnostics.reset()
    }

    @Test
    fun `SDK mapping wins and all four wheel labels plus focus and camera classify`() {
        assertEquals(WheelKey.Click, WheelTalk.keyOf(LightDeviceKeys.RotaryButtonPress.keyCode))
        assertEquals(WheelKey.Up, WheelTalk.keyOf(LightDeviceKeys.RotaryTurnUp.keyCode))
        assertEquals(WheelKey.Down, WheelTalk.keyOf(LightDeviceKeys.RotaryTurnDown.keyCode))
        assertEquals(WheelKey.Focus, WheelTalk.keyOf(LightDeviceKeys.ShutterHalfPressed.keyCode))
        assertEquals(WheelKey.Camera, WheelTalk.keyOf(LightDeviceKeys.ShutterPressed.keyCode))
    }

    @Test
    fun `raw fallback codes classify when the SDK map is absent`() {
        assertEquals(WheelKey.Up, WheelTalk.keyOf(19))
        assertEquals(WheelKey.Down, WheelTalk.keyOf(20))
        assertEquals(WheelKey.Click, WheelTalk.keyOf(66))
        assertEquals(WheelKey.Focus, WheelTalk.keyOf(80))
        assertEquals(WheelKey.Camera, WheelTalk.keyOf(27))
        assertNull(WheelTalk.keyOf(UNRELATED))
    }

    @Test
    fun `both halves of every wheel key are claimed and Back Home and volume pass through`() {
        val harness = Harness()
        for (code in listOf(CLICK, TURN_UP, TURN_DOWN, FOCUS, CAMERA, 19, 20, 66, 80, 27)) {
            assertTrue(harness.talk.dispatch(code, down = true), "DOWN $code")
            assertTrue(harness.talk.dispatch(code, down = false), "UP $code")
        }
        for (code in listOf(BACK, HOME, VOLUME_UP, VOLUME_DOWN, UNRELATED)) {
            assertFalse(harness.talk.dispatch(code, down = true), "DOWN $code")
            assertFalse(harness.talk.dispatch(code, down = false), "UP $code")
        }
    }

    @Test
    fun `a short press is a click and the canceled hold never fires later`() {
        val harness = Harness()

        assertTrue(harness.down(CLICK))
        harness.scheduler.advanceBy(WheelTalk.HOLD_MS - 1)
        assertEquals(0, harness.starts)
        assertTrue(harness.up(CLICK))

        assertEquals(1, harness.clicks)
        assertEquals(0, harness.starts)
        assertEquals(0, harness.ends)
        assertEquals(0, harness.scheduler.pending())
        harness.scheduler.advanceBy(WheelTalk.HOLD_MS)
        assertEquals(1, harness.clicks)
        assertEquals(0, harness.starts)
    }

    @Test
    fun `a press held to exactly HOLD_MS starts a take and release sends it`() {
        val harness = Harness()

        assertTrue(harness.down(CLICK))
        harness.scheduler.advanceBy(WheelTalk.HOLD_MS)
        assertEquals(1, harness.starts)

        assertTrue(harness.up(CLICK))
        assertEquals(1, harness.ends)
        assertEquals(0, harness.clicks)
        assertEquals(0, harness.cancels)
    }

    @Test
    fun `a turn while holding cancels the take without scrolling or sending`() {
        val harness = Harness()

        harness.down(CLICK)
        harness.scheduler.advanceBy(WheelTalk.HOLD_MS)
        assertEquals(1, harness.starts)

        assertTrue(harness.talk.dispatch(TURN_UP, down = true))
        assertTrue(harness.talk.dispatch(TURN_UP, down = false))
        assertEquals(1, harness.cancels)
        assertTrue(harness.turns.isEmpty())

        harness.up(CLICK)
        assertEquals(0, harness.ends)
        assertEquals(0, harness.clicks)
        assertEquals(1, harness.cancels)
    }

    @Test
    fun `a turn before the threshold cancels the press without scrolling and the release does nothing`() {
        val harness = Harness()

        harness.down(CLICK)
        harness.scheduler.advanceBy(WheelTalk.HOLD_MS - 1)
        assertTrue(harness.talk.dispatch(TURN_DOWN, down = true))
        assertTrue(harness.talk.dispatch(TURN_DOWN, down = false))

        assertEquals(1, harness.cancels)
        assertEquals(0, harness.starts)
        assertTrue(harness.turns.isEmpty())
        assertEquals(0, harness.scheduler.pending())

        harness.scheduler.advanceBy(1)
        assertEquals(0, harness.starts)
        harness.up(CLICK)
        assertEquals(0, harness.ends)
        assertEquals(0, harness.clicks)
        assertEquals(1, harness.cancels)
    }

    @Test
    fun `a bare turn scrolls one step and claims both its halves`() {
        val harness = Harness()

        assertTrue(harness.talk.dispatch(TURN_UP, down = true))
        assertTrue(harness.talk.dispatch(TURN_UP, down = false))
        assertTrue(harness.talk.dispatch(TURN_DOWN, down = true))
        assertTrue(harness.talk.dispatch(TURN_DOWN, down = false))

        assertEquals(listOf(1, -1), harness.turns)
        assertEquals(0, harness.clicks)
        assertEquals(0, harness.cancels)
    }

    @Test
    fun `reset during a hold cancels and a late release sends nothing`() {
        val harness = Harness()

        harness.down(CLICK)
        harness.scheduler.advanceBy(WheelTalk.HOLD_MS)
        assertEquals(1, harness.starts)

        harness.talk.reset()
        assertEquals(1, harness.cancels)
        assertTrue(harness.up(CLICK))

        assertEquals(0, harness.ends)
        assertEquals(0, harness.clicks)
    }

    @Test
    fun `reset before the threshold drops the press and its pending hold`() {
        val harness = Harness()

        harness.down(CLICK)
        harness.talk.reset()
        harness.scheduler.advanceBy(WheelTalk.HOLD_MS)

        assertEquals(0, harness.starts)
        assertEquals(0, harness.cancels)
        assertEquals(0, harness.scheduler.pending())
        assertTrue(harness.up(CLICK))
        assertEquals(0, harness.clicks)
    }

    @Test
    fun `a second DOWN without its UP cancels the old hold before starting the new press`() {
        val harness = Harness()

        harness.down(CLICK)
        harness.scheduler.advanceBy(WheelTalk.HOLD_MS)
        assertEquals(1, harness.starts)

        harness.down(CLICK)
        assertEquals(1, harness.cancels)
        harness.up(CLICK)

        assertEquals(1, harness.clicks)
        assertEquals(0, harness.ends)
    }

    @Test
    fun `an orphan UP after reset neither clicks nor ends a take`() {
        val harness = Harness()

        harness.down(CLICK)
        harness.talk.reset()
        assertTrue(harness.up(CLICK))
        assertTrue(harness.up(CLICK))

        assertEquals(0, harness.clicks)
        assertEquals(0, harness.ends)
        assertEquals(0, harness.cancels)
    }

    @Test
    fun `a delayed hold callback still ends as a take when UP lands at HOLD_MS`() {
        val harness = Harness()

        harness.down(CLICK)
        harness.scheduler.delayBy(WheelTalk.HOLD_MS)
        assertEquals(0, harness.starts)

        assertTrue(harness.up(CLICK))
        assertEquals(1, harness.starts)
        assertEquals(1, harness.ends)
        assertEquals(0, harness.clicks)
        assertEquals(0, harness.scheduler.pending())
    }

    @Test
    fun `camera does not start a second take while the canceled wheel press is still down`() {
        val harness = Harness()

        harness.down(CLICK)
        harness.scheduler.advanceBy(WheelTalk.HOLD_MS)
        assertEquals(1, harness.starts)
        harness.talk.dispatch(TURN_UP, down = true)
        assertEquals(1, harness.cancels)

        harness.down(CAMERA)
        assertEquals(1, harness.starts)
        harness.up(CAMERA)
        harness.up(CLICK)

        assertEquals(0, harness.ends)
        assertEquals(0, harness.clicks)
        assertEquals(1, harness.cancels)
    }

    @Test
    fun `a canceled shutter take does not restart on a stray DOWN before its UP`() {
        val harness = Harness()

        harness.down(FOCUS)
        assertEquals(1, harness.starts)
        harness.talk.dispatch(TURN_DOWN, down = true)
        assertEquals(1, harness.cancels)

        harness.down(FOCUS)
        assertEquals(1, harness.starts)
        harness.up(FOCUS)

        harness.down(CAMERA)
        assertEquals(2, harness.starts)
        harness.up(CAMERA)
        assertEquals(1, harness.ends)
    }

    @Test
    fun `focus and camera hold to talk immediately and release ends the take`() {
        val harness = Harness()

        harness.down(FOCUS)
        assertEquals(1, harness.starts)
        harness.up(FOCUS)
        assertEquals(1, harness.ends)

        harness.down(CAMERA)
        assertEquals(2, harness.starts)
        harness.up(CAMERA)
        assertEquals(2, harness.ends)
        assertEquals(0, harness.clicks)
    }

    @Test
    fun `list mode has no hold split and a pressed turn still scrolls without clicking`() {
        val harness = Harness(voiceEnabled = false)

        harness.down(CLICK)
        harness.scheduler.advanceBy(WheelTalk.HOLD_MS)
        assertEquals(0, harness.starts)

        harness.talk.dispatch(TURN_UP, down = true)
        assertEquals(listOf(1), harness.turns)
        harness.up(CLICK)
        assertEquals(0, harness.clicks)
        assertEquals(0, harness.ends)

        harness.down(CLICK)
        harness.up(CLICK)
        assertEquals(1, harness.clicks)
    }

    @Test
    fun `diagnostics counters separate presses from turns and warn when only turns arrive`() {
        val harness = Harness()
        repeat(WheelDiagnostics.WARNING_TURNS) {
            harness.talk.dispatch(TURN_UP, down = true)
            harness.talk.dispatch(TURN_UP, down = false)
        }

        val silent = WheelDiagnostics.snapshot()
        assertEquals(0, silent.clicks)
        assertEquals(WheelDiagnostics.WARNING_TURNS, silent.turns)
        assertNotNull(WheelDiagnostics.warning())

        harness.down(CLICK)
        harness.up(CLICK)

        val heard = WheelDiagnostics.snapshot()
        assertEquals(1, heard.clicks)
        assertNull(WheelDiagnostics.warning())
    }
}
