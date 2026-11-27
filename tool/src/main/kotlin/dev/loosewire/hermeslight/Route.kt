package dev.loosewire.hermeslight

internal enum class Route(
    val parent: Route? = null,
) {
    Home,
    Menu(Home),
    Sessions(Menu),
    Thread(Sessions),
    Bots(Menu),
    Setup(Menu),
}

/** Use the SDK stack, including missing parents when opening a thread directly from Home. */
internal fun Route.openFrom(
    from: Route,
    back: () -> Unit,
    forward: (Route) -> Unit,
) {
    if (this == from) return
    // Long-back keeps Setup as a child of the current screen.
    if (this == Route.Setup) {
        forward(this)
        return
    }
    var shared = from
    while (!isWithin(shared)) {
        back()
        shared = requireNotNull(shared.parent)
    }
    pushFrom(shared, forward)
}

private fun Route.isWithin(ancestor: Route): Boolean {
    var route: Route? = this
    while (route != null) {
        if (route == ancestor) return true
        route = route.parent
    }
    return false
}

private fun Route.pushFrom(
    ancestor: Route,
    forward: (Route) -> Unit,
) {
    if (this == ancestor) return
    requireNotNull(parent).pushFrom(ancestor, forward)
    forward(this)
}
