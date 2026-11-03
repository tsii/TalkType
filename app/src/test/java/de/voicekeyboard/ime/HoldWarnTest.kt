package de.voicekeyboard.ime

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * B2 (PLAN_V0130 §6.2 Option A + §6.3-B2): reine Warn-Entscheidung
 * [holdWarnDue] für die 900-s-Obergrenze — Muster [StartGateTest] (extrahierte
 * Service-Entscheidungen, JVM ohne Robolectric).
 *
 * Vertrag: Warnung GENAU EINMAL pro Session, ab 13 min Session-Audio
 * (780 000 ms = 900 s Obergrenze minus 2 min Vorlauf); das warned-Flag
 * blockt Wiederholungen und wird nur vom Session-Ende-Pfad zurückgesetzt.
 */
class HoldWarnTest {

    // ---- (1) unter 13 min: keine Warnung ----

    @Test
    fun `unter 13 min keine Warnung - auch nicht knapp davor`() {
        assertFalse("bei 0 ms", holdWarnDue(sessionMs = 0L, warned = false))
        assertFalse("bei 10 min (600 s)", holdWarnDue(sessionMs = 600_000L, warned = false))
        assertFalse("knapp davor (779 999 ms)", holdWarnDue(sessionMs = 779_999L, warned = false))
    }

    // ---- (2) ab 13 min: genau einmal fällig ----

    @Test
    fun `ab 780 s wird die Warnung faellig - exakt an der Schwelle`() {
        assertTrue("exakt 780 000 ms", holdWarnDue(sessionMs = 780_000L, warned = false))
        assertTrue("weit darüber (899 s)", holdWarnDue(sessionMs = 899_999L, warned = false))
    }

    // ---- (3) warned-Flag blockt Wiederholungen ----

    @Test
    fun `warned-Flag blockt Wiederholung - auch weiter steigende Session`() {
        assertFalse(
            "nach der Warnung (14 min): nicht erneut",
            holdWarnDue(sessionMs = 840_000L, warned = true),
        )
        assertFalse(
            "kurz vor Obergrenze (899 s): nicht erneut",
            holdWarnDue(sessionMs = 899_999L, warned = true),
        )
    }

    // ---- (4) Session-Ende setzt zurück: nächste Session warnt wieder ----

    @Test
    fun `Session-Ende setzt zurueck - naechste Session warnt wieder genau einmal`() {
        // Erste Session: Warnung bei 780 s …
        assertTrue(holdWarnDue(sessionMs = 780_000L, warned = false))
        // … Flag gesetzt, keine Wiederholung:
        assertFalse(holdWarnDue(sessionMs = 850_000L, warned = true))
        // Session-ENDE: Flag zurück (Service: onDictationStopped/abort/onAborted)
        // — die NÄCHSTE Session beginnt bei 0 und warnt erneut ab 780 s:
        assertFalse("neue Session Anfang: noch nicht", holdWarnDue(sessionMs = 0L, warned = false))
        assertTrue("neue Session ab 780 s: wieder genau einmal", holdWarnDue(sessionMs = 780_000L, warned = false))
    }

    // ---- (5) Defensive: absurde Eingaben, Flag dominiert immer ----

    @Test
    fun `Hysterese-frei - Schwelle scharf, Flag dominiert, absurd nie`() {
        // Schwelle scharf: 779 999 nein / 780 000 ja (keine Gleit-Logik).
        assertFalse(holdWarnDue(sessionMs = 779_999L, warned = false))
        assertTrue(holdWarnDue(sessionMs = 780_000L, warned = false))
        // Flag dominiert IMMER — auch bei absurd großen Werten nie erneut.
        assertFalse(holdWarnDue(sessionMs = Long.MAX_VALUE / 2, warned = true))
        // Negative/restliche Eingaben unterhalb der Schwelle: nie.
        assertFalse(holdWarnDue(sessionMs = -1L, warned = false))
    }
}
