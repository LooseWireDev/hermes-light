package dev.loosewire.hermeslight

import dev.loosewire.hermeslight.ui.MenuDestination
import kotlin.test.Test
import kotlin.test.assertEquals

class NavigationTest {
    @Test
    fun `history opens a thread with exactly one parent per back press`() {
        val stack = mutableListOf(Route.Home)
        stack.open(Route.Menu)
        stack.open(requireNotNull(MenuDestination.History.route))
        stack.open(Route.Thread)
        assertEquals(listOf(Route.Home, Route.Menu, Route.Sessions, Route.Thread), stack)
        stack.removeAt(stack.lastIndex)
        assertEquals(Route.Sessions, stack.last())
        stack.removeAt(stack.lastIndex)
        assertEquals(Route.Menu, stack.last())
        stack.removeAt(stack.lastIndex)
        assertEquals(listOf(Route.Home), stack)
    }

    @Test
    fun `direct Home thread activation includes history and menu in the SDK stack`() {
        val stack = mutableListOf(Route.Home)
        stack.open(Route.Thread)
        assertEquals(listOf(Route.Home, Route.Menu, Route.Sessions, Route.Thread), stack)
    }

    @Test
    fun `Chat pops Menu instead of creating a new Home`() {
        val stack = mutableListOf(Route.Home, Route.Menu)
        val opened = mutableListOf<Route>()
        requireNotNull(MenuDestination.Chat.route).openFrom(
            from = stack.last(),
            back = { stack.removeAt(stack.lastIndex) },
            forward = { opened.add(it) },
        )
        assertEquals(listOf(Route.Home), stack)
        assertEquals(emptyList(), opened)
    }

    @Test
    fun `bot selection opens filtered history with the same back chain`() {
        val stack = mutableListOf(Route.Home, Route.Menu)
        stack.open(requireNotNull(MenuDestination.Bots.route))
        assertEquals(listOf(Route.Home, Route.Menu, Route.Bots), stack)
        stack.open(Route.Sessions)
        stack.open(Route.Thread)
        assertEquals(listOf(Route.Home, Route.Menu, Route.Sessions, Route.Thread), stack)
    }

    @Test
    fun `Settings returns to Menu and long back Setup returns to its caller`() {
        val stack = mutableListOf(Route.Home, Route.Menu)
        stack.open(requireNotNull(MenuDestination.Settings.route))
        assertEquals(listOf(Route.Home, Route.Menu, Route.Setup), stack)
        stack.removeAt(stack.lastIndex)
        assertEquals(Route.Menu, stack.last())
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
