package dev.loosewire.hermeslight

import dev.loosewire.hermeslight.ui.HomeWheelFocus
import kotlin.test.Test
import kotlin.test.assertEquals

class HomeWheelFocusTest {
    @Test
    fun `empty Home can reach both actions without focusing an absent strip or thread`() {
        var focus = HomeWheelFocus.History
        assertEquals(HomeWheelFocus.History, focus.previous(hasMessages = false))
        focus = focus.next(hasMessages = false, hasStatus = false)
        assertEquals(HomeWheelFocus.Bots, focus)
        focus = focus.next(hasMessages = false, hasStatus = false)
        assertEquals(HomeWheelFocus.History, focus)
    }

    @Test
    fun `newest end exposes actions and pending status then returns to the thread`() {
        var focus = HomeWheelFocus.Thread
        val journey = mutableListOf(focus)
        repeat(4) {
            focus = focus.next(hasMessages = true, hasStatus = true)
            journey.add(focus)
        }
        assertEquals(
            listOf(
                HomeWheelFocus.Thread,
                HomeWheelFocus.History,
                HomeWheelFocus.Bots,
                HomeWheelFocus.Status,
                HomeWheelFocus.Thread,
            ),
            journey,
        )
    }

    @Test
    fun `oldest thread boundary stays in the thread regardless of further up turns`() {
        var focus = HomeWheelFocus.Thread
        repeat(10) { focus = focus.previous(hasMessages = true) }
        assertEquals(HomeWheelFocus.Thread, focus)
    }

    @Test
    fun `reverse traversal returns to the newest thread without crossing its oldest end`() {
        var focus = HomeWheelFocus.Status
        repeat(3) { focus = focus.previous(hasMessages = true) }
        assertEquals(HomeWheelFocus.Thread, focus)
        assertEquals(HomeWheelFocus.Thread, focus.previous(hasMessages = true))
    }

    @Test
    fun `idle skips status and empty active Home can still reach status`() {
        assertEquals(HomeWheelFocus.Thread, HomeWheelFocus.Bots.next(hasMessages = true, hasStatus = false))
        assertEquals(HomeWheelFocus.Status, HomeWheelFocus.Bots.next(hasMessages = false, hasStatus = true))
        assertEquals(HomeWheelFocus.History, HomeWheelFocus.Status.next(hasMessages = false, hasStatus = true))
    }
}
