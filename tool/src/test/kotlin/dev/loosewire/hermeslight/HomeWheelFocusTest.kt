package dev.loosewire.hermeslight

import dev.loosewire.hermeslight.ui.HomeWheelFocus
import kotlin.test.Test
import kotlin.test.assertEquals

class HomeWheelFocusTest {
    @Test
    fun `empty Home rests on the composer and still reaches Send and Menu`() {
        var focus = HomeWheelFocus.Composer
        assertEquals(HomeWheelFocus.Composer, focus.previous(hasMessages = false))
        focus = focus.next(hasMessages = false, hasStatus = false)
        assertEquals(HomeWheelFocus.Send, focus)
        focus = focus.next(hasMessages = false, hasStatus = false)
        assertEquals(HomeWheelFocus.Menu, focus)
        focus = focus.next(hasMessages = false, hasStatus = false)
        assertEquals(HomeWheelFocus.Composer, focus)
    }

    @Test
    fun `empty Home with attention can reach status and return to the composer`() {
        val status = HomeWheelFocus.Menu.next(hasMessages = false, hasStatus = true)
        assertEquals(HomeWheelFocus.Status, status)
        assertEquals(HomeWheelFocus.Menu, status.previous(hasMessages = false))
        assertEquals(HomeWheelFocus.Composer, status.next(hasMessages = false, hasStatus = true))
    }

    @Test
    fun `newest end exposes composer, Send, Menu and pending status then returns to the thread`() {
        var focus = HomeWheelFocus.Thread
        val journey = mutableListOf(focus)
        repeat(5) {
            focus = focus.next(hasMessages = true, hasStatus = true)
            journey.add(focus)
        }
        assertEquals(
            listOf(
                HomeWheelFocus.Thread,
                HomeWheelFocus.Composer,
                HomeWheelFocus.Send,
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
        assertEquals(HomeWheelFocus.Send, focus)
        focus = focus.previous(hasMessages = true)
        assertEquals(HomeWheelFocus.Composer, focus)
        focus = focus.previous(hasMessages = true)
        assertEquals(HomeWheelFocus.Thread, focus)
        assertEquals(HomeWheelFocus.Thread, focus.previous(hasMessages = true))
    }

    @Test
    fun `idle skips status and the Menu can still reach status on an active Home`() {
        assertEquals(HomeWheelFocus.Thread, HomeWheelFocus.Menu.next(hasMessages = true, hasStatus = false))
        assertEquals(HomeWheelFocus.Composer, HomeWheelFocus.Thread.next(hasMessages = true, hasStatus = false))
        assertEquals(HomeWheelFocus.Status, HomeWheelFocus.Menu.next(hasMessages = false, hasStatus = true))
    }
}
