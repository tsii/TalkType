package de.voicekeyboard.ime

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * F13c/P2-3 (docs/REVIEW_F13_STATUSBAR.md): the wiring seam — WHICH service
 * occasion goes into which status channel is fixed here as a pure table.
 * B1 was a seam error (the start text ran over the sticky channel), which
 * the StatusBarText tests alone could not see: a regression (start text or
 * hint routed as STICKY again) turns THIS test red.
 */
class StatusRoutingTest {

    @Test
    fun `the start message goes on the transient channel - never sticky`() {
        assertEquals(StatusChannel.TRANSIENT, StatusRouting.route(StatusEvent.Starting("Starte Aufnahme…")))
    }

    @Test
    fun `hints and confirmations go on the transient channel`() {
        assertEquals(StatusChannel.TRANSIENT, StatusRouting.route(StatusEvent.Hint("Diagnose kopiert — Teilen-Dialog geöffnet")))
        assertEquals(StatusChannel.TRANSIENT, StatusRouting.route(StatusEvent.Hint("Emoji während Diktat gesperrt")))
        assertEquals(StatusChannel.TRANSIENT, StatusRouting.route(StatusEvent.Hint("Sprache gewechselt: Polski")))
    }

    @Test
    fun `errors go on the sticky channel - R-2 until the next deliberate action`() {
        assertEquals(StatusChannel.STICKY, StatusRouting.route(StatusEvent.Error("Modell nicht geladen – Setup öffnen")))
        assertEquals(StatusChannel.STICKY, StatusRouting.route(StatusEvent.Error("Start dauert zu lange – erneut versuchen.")))
        assertEquals(StatusChannel.STICKY, StatusRouting.route(StatusEvent.Error("Mikrofonfreigabe nötig")))
    }

    @Test
    fun `every event kind is assigned - no case falls through`() {
        for (event in listOf(
            StatusEvent.Starting("s"), StatusEvent.Hint("h"), StatusEvent.Error("e"),
        )) {
            when (event) {
                is StatusEvent.Starting -> assertEquals(StatusChannel.TRANSIENT, StatusRouting.route(event))
                is StatusEvent.Hint -> assertEquals(StatusChannel.TRANSIENT, StatusRouting.route(event))
                is StatusEvent.Error -> assertEquals(StatusChannel.STICKY, StatusRouting.route(event))
            }
        }
    }
}
