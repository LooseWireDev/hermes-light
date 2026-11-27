package dev.loosewire.hermeslight

import dev.loosewire.hermeslight.ui.HomeWheelFocus
import kotlin.test.Test
import kotlin.test.assertEquals

class HomeWheelFocusTest {
    @Test
    fun `empty Home rests on the Menu action without focusing an absent strip or thread`() {
        var focus = HomeWheelFocus.Menu
        assertEquals(HomeWheelFocus.Menu, focus.previous(hasMessages = false))
        focus = focus.next(hasMessages = false, hasStatus = false)
        assertEquals(HomeWheelFocus.Menu, focus)
    }

    @Test
    fun `empty Home with attention can reach status and return to Menu`() {
        val status = HomeWheelFocus.Menu.next(hasMessages = false, hasStatus = true)
        assertEquals(HomeWheelFocus.Status, status)
        assertEquals(HomeWheelFocus.Menu, status.previous(hasMessages = false))
        assertEquals(HomeWheelFocus.Menu, status.next(hasMessages = false, hasStatus = true))
    }

    @Test
    fun `newest end exposes the Menu action and pending status then returns to the thread`() {
        var focus = HomeWheelFocus.Thread
        val journey = mutableListOf(focus)
        repeat(3) {
            focus = focus.next(hasMessages = true, hasStatus = true)
            journey.add(focus)
        }
        assertEquals(
            listOf(
                HomeWheelFocus.Thread,
                HomeWheelFocus.Menu,
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
        focus = focus.previous(hasMessages = true)
        assertEquals(HomeWheelFocus.Menu, focus)
        focus = focus.previous(hasMessages = true)
        assertEquals(HomeWheelFocus.Thread, focus)
        assertEquals(HomeWheelFocus.Thread, focus.previous(hasMessages = true))
    }

    @Test
    fun `idle skips status and the Menu can still reach status on an active Home`() {
        assertEquals(HomeWheelFocus.Thread, HomeWheelFocus.Menu.next(hasMessages = true, hasStatus = false))
        assertEquals(HomeWheelFocus.Menu, HomeWheelFocus.Thread.next(hasMessages = false, hasStatus = false))
        assertEquals(HomeWheelFocus.Status, HomeWheelFocus.Menu.next(hasMessages = false, hasStatus = true))
    }
}
