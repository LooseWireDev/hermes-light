package dev.loosewire.hermeslight.ui

/**
 * Home focus cycle: transcript → composer → Send → top-bar Menu → attention strip → transcript.
 * Turns inside the transcript scroll it first; only its newest end crosses into the composer. The
 * oldest end never crosses focus.
 */
internal enum class HomeWheelFocus {
    Thread,
    Composer,
    Send,
    Menu,
    Status,
    ;

    fun previous(hasMessages: Boolean): HomeWheelFocus =
        when (this) {
            Thread -> Thread
            Composer -> if (hasMessages) Thread else Composer
            Send -> Composer
            Menu -> Send
            Status -> Menu
        }

    /** Called for Thread only after scrolling/paging newer messages has reached its end. */
    fun next(
        hasMessages: Boolean,
        hasStatus: Boolean,
    ): HomeWheelFocus =
        when (this) {
            Thread -> Composer
            Composer -> Send
            Send -> Menu
            Menu ->
                when {
                    hasStatus -> Status
                    hasMessages -> Thread
                    else -> Composer
                }
            Status -> if (hasMessages) Thread else Composer
        }
}
