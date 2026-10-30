package de.voicekeyboard.ime

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * F13c/P2-3 (docs/REVIEW_F13_STATUSBAR.md): Die Verdrahtungsnaht — WELCHER
 * Service-Anlass in welchen Statuskanal geht, ist hier als reine Tabelle
 * fixiert. B1 war ein Nahtfehler (Starttext lief über den Sticky-Kanal), den
 * die StatusBarText-Tests allein nicht sehen konnten: ein Rückfall (Starttext
 * oder Hinweis wieder als STICKY geroutet) macht DIESEN Test rot.
 */
class StatusRoutingTest {

    @Test
    fun `Startmeldung geht auf den transienten Kanal - nie sticky`() {
        assertEquals(StatusChannel.TRANSIENT, StatusRouting.route(StatusEvent.Starting("Starte Aufnahme…")))
    }

    @Test
    fun `Hinweise und Bestaetigungen gehen auf den transienten Kanal`() {
        assertEquals(StatusChannel.TRANSIENT, StatusRouting.route(StatusEvent.Hint("Diagnose kopiert — Teilen-Dialog geöffnet")))
        assertEquals(StatusChannel.TRANSIENT, StatusRouting.route(StatusEvent.Hint("Emoji während Diktat gesperrt")))
        assertEquals(StatusChannel.TRANSIENT, StatusRouting.route(StatusEvent.Hint("Sprache gewechselt: Polski")))
    }

    @Test
    fun `Fehler gehen auf den Sticky-Kanal - R-2 bis zur naechsten bewussten Aktion`() {
        assertEquals(StatusChannel.STICKY, StatusRouting.route(StatusEvent.Error("Modell nicht geladen – Setup öffnen")))
        assertEquals(StatusChannel.STICKY, StatusRouting.route(StatusEvent.Error("Start dauert zu lange – erneut versuchen.")))
        assertEquals(StatusChannel.STICKY, StatusRouting.route(StatusEvent.Error("Mikrofonfreigabe nötig")))
    }

    @Test
    fun `jede Event-Art ist zugeordnet - kein Fall faellt durch`() {
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
