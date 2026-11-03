package de.voicekeyboard.ime

/**
 * F13c/P2-3 (docs/REVIEW_F13_STATUSBAR.md): wiring seam of the status row.
 *
 * B1 („starte Aufnahme…“ became sticky) was a SEAM FAILURE: the wrong place
 * chose the channel. This table makes the seam explicit and JVM-testable
 * (StatusRoutingTest) — the service calls [route] at every affected place:
 *
 *  - [StatusEvent.Starting] (start message)     → TRANSIENT (overtaken on
 *    RECORDING/FINALIZING/newer error; must not set a stickyError).
 *  - [StatusEvent.Hint] (hint/confirmation)     → TRANSIENT (display until the
 *    next updateStatus; never suppresses a later error/recording message).
 *  - [StatusEvent.Error] (blocking error)       → STICKY (R-2: stays until
 *    the next deliberate action or until a completed success resolves it).
 *
 * A regression (start text/hint again via the sticky channel) turns the
 * StatusRoutingTest red without a device having to run.
 */
internal enum class StatusChannel { TRANSIENT, STICKY }

internal sealed class StatusEvent {
    abstract val text: String

    /** Transient start message (e. g. „Starte Aufnahme…"). */
    data class Starting(override val text: String) : StatusEvent()

    /** Hint/confirmation without a blocking character (e. g. diagnosis copied). */
    data class Hint(override val text: String) : StatusEvent()

    /** Blocking error (R-2): stays sticky until the next action. */
    data class Error(override val text: String) : StatusEvent()
}

internal object StatusRouting {
    fun route(event: StatusEvent): StatusChannel = when (event) {
        is StatusEvent.Starting -> StatusChannel.TRANSIENT
        is StatusEvent.Hint -> StatusChannel.TRANSIENT
        is StatusEvent.Error -> StatusChannel.STICKY
    }
}
