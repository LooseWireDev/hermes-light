package dev.loosewire.hermeslight.ui

/** Home actions are reached from the newest end only; the oldest end never crosses focus. */
internal enum class HomeWheelFocus {
    Thread,
    Menu,
    Status,
    ;

    fun previous(hasMessages: Boolean): HomeWheelFocus =
        when (this) {
            Thread -> Thread
            Menu -> if (hasMessages) Thread else Menu
            Status -> Menu
        }

    /** Called for Thread only after scrolling/paging newer messages has reached its end. */
    fun next(
        hasMessages: Boolean,
        hasStatus: Boolean,
    ): HomeWheelFocus =
        when (this) {
            Thread -> Menu
            Menu ->
                when {
                    hasStatus -> Status
                    hasMessages -> Thread
                    else -> Menu
                }
            Status -> if (hasMessages) Thread else Menu
        }
}
