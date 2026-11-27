package dev.loosewire.hermeslight.ui

/** Home actions are reached from the newest end only; the oldest end never crosses focus. */
internal enum class HomeWheelFocus {
    Thread,
    History,
    Bots,
    Status,
    ;

    fun previous(hasMessages: Boolean): HomeWheelFocus =
        when (this) {
            Thread -> Thread
            History -> if (hasMessages) Thread else History
            Bots -> History
            Status -> Bots
        }

    /** Called for Thread only after scrolling/paging newer messages has reached its end. */
    fun next(
        hasMessages: Boolean,
        hasStatus: Boolean,
    ): HomeWheelFocus =
        when (this) {
            Thread -> History
            History -> Bots
            Bots ->
                when {
                    hasStatus -> Status
                    hasMessages -> Thread
                    else -> History
                }
            Status -> if (hasMessages) Thread else History
        }
}
