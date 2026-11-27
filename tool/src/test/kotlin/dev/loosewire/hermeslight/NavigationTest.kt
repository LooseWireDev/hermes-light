package dev.loosewire.hermeslight

import kotlin.test.Test
import kotlin.test.assertEquals

class NavigationTest {
    @Test
    fun `history opens a thread with exactly one parent per back press`() {
        val stack = mutableListOf(Route.Home)
        stack.open(Route.Sessions)
        stack.open(Route.Thread)
        assertEquals(listOf(Route.Home, Route.Sessions, Route.Thread), stack)
        stack.removeAt(stack.lastIndex)
        assertEquals(Route.Sessions, stack.last())
        stack.removeAt(stack.lastIndex)
        assertEquals(listOf(Route.Home), stack)
    }

    @Test
    fun `direct Home thread activation includes history in the SDK stack`() {
        val stack = mutableListOf(Route.Home)
        stack.open(Route.Thread)
        assertEquals(listOf(Route.Home, Route.Sessions, Route.Thread), stack)
    }

    @Test
    fun `Home back stays on the existing Home`() {
        val stack = mutableListOf(Route.Home)
        stack.open(Route.Home)
        assertEquals(listOf(Route.Home), stack)
    }

    @Test
    fun `bot selection opens filtered history with the same back chain`() {
        val stack = mutableListOf(Route.Home)
        stack.open(Route.Bots)
        assertEquals(listOf(Route.Home, Route.Bots), stack)
        stack.open(Route.Sessions)
        stack.open(Route.Thread)
        assertEquals(listOf(Route.Home, Route.Sessions, Route.Thread), stack)
    }

    @Test
    fun `long back Setup returns to Home or its calling thread`() {
        val stack = mutableListOf(Route.Home)
        stack.open(Route.Setup)
        assertEquals(listOf(Route.Home, Route.Setup), stack)
        stack.removeAt(stack.lastIndex)
        assertEquals(Route.Home, stack.last())
        stack.open(Route.Thread)
        stack.open(Route.Setup)
        stack.removeAt(stack.lastIndex)
        assertEquals(Route.Thread, stack.last())
    }

    private fun MutableList<Route>.open(destination: Route) {
        destination.openFrom(
            from = last(),
            back = { removeAt(lastIndex) },
            forward = { add(it) },
        )
    }
}
