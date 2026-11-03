package de.voicekeyboard.ime

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * F50/F50a (PLAN_V0130 §1 A2): reine Start-Gate-Entscheidungen der IME —
 * JVM-testbar ohne Robolectric (Muster `MicToggleTest` für die anderen
 * extrahierten Funktionen des Service).
 *
 *  - [micStartGate]: Start-Serialisierung (V4-Race). Invariante: zu jedem
 *    Zeitpunkt läuft höchstens EINE Start-Coroutine; eine abgelaufene wird
 *    abgebrochen oder ihr Ergebnis verworfen (kein Zombie-Diktat, kein
 *    Doppelstart, kein Überschreiben von `lastStartMs`).
 *  - [micUpDuringStart]: UP im STARTING-Fenster = nachgereichter Stop mit
 *    transientem Hint (F50a) — beseitigt das stille Flag (C2-Symptom 1),
 *    ohne das KEEP_RUNNING-Design (P0-Befund2(a)) zu ändern.
 */
class StartGateTest {

    // ---- F50: zweiter DOWN vor Frist (Start läuft) → IGNORE ----

    @Test
    fun `zweiter DOWN vor Frist - Start laeuft noch - wird ignoriert`() {
        // F11 P1: Der Start lädt den ~652-MB-Encoder — ein zweiter Mic-Druck
        // während startingUp darf KEINEN Zweittermin erzeugen.
        assertEquals(
            MicStartGateAction.IGNORE,
            micStartGate(startingUp = true, sessionRecording = false, oldStartJobActive = true),
        )
        // Auch ohne aktiven Job-Handle gilt: startingUp dichtet die Geste ab.
        assertEquals(
            MicStartGateAction.IGNORE,
            micStartGate(startingUp = true, sessionRecording = false, oldStartJobActive = false),
        )
    }

    // ---- F50: Frist abgelaufen, alter Job läuft noch → SUPERSEDE ----

    @Test
    fun `Frist abgelaufen - alter Start-Job laeuft noch - Supersede-Pfad`() {
        // Die 15-s-Frist hat startingUp freigegeben, die alte Start-Coroutine
        // hängt aber noch (natives engine.load) → der zweite DOWN löst sie ab:
        // cancel + abort("start_superseded") + stopFgs("superseded"), dann ein
        // frischer Start mit NEUEM Coordinator (kein Zombie-Diktat, V4).
        assertEquals(
            MicStartGateAction.SUPERSEDE,
            micStartGate(startingUp = false, sessionRecording = false, oldStartJobActive = true),
        )
    }

    // ---- F50: laufende Diktat-Session → IGNORE (kein Neustart über DOWN) ----

    @Test
    fun `laufende Session - DOWN ist kein Neustart - IGNORE`() {
        assertEquals(
            MicStartGateAction.IGNORE,
            micStartGate(startingUp = false, sessionRecording = true, oldStartJobActive = false),
        )
        // Even mit hängendem Rest-Job: die laufende Aufnahme gewinnt.
        assertEquals(
            MicStartGateAction.IGNORE,
            micStartGate(startingUp = false, sessionRecording = true, oldStartJobActive = true),
        )
    }

    // ---- F50: normaler Fall → FRESH_START ----

    @Test
    fun `kein Start aktiv - frischer Start`() {
        assertEquals(
            MicStartGateAction.FRESH_START,
            micStartGate(startingUp = false, sessionRecording = false, oldStartJobActive = false),
        )
    }

    // ---- B2 (PLAN §6.2 Option A): Hold startet mit 900-s-Grenze ----

    @Test
    fun `B2 Hold und Toggle starten mit 900-s-Obergrenze`() {
        // Die reine Entscheidung [sessionMaxDurationMs] steuert die Quelle
        // (AudioRecordSource-Parameter, Quellen-Code unangetastet): 15 min
        // statt der 300-s-Quellen-Default — für Halte- UND Toggle-Sessions
        // (Toggle praktisch irrelevant: F43 stoppt an der ersten Pause).
        assertEquals("Hold: 900 s", 900_000L, sessionMaxDurationMs(holdGesture = true))
        assertEquals("Toggle: derselbe Wert", 900_000L, sessionMaxDurationMs(holdGesture = false))
    }

    // ---- F50a: UP während STARTING → pending-stop + Hint ----

    @Test
    fun `UP waehrend STARTING - pending-stop-Flag mit Hint, sonst regulärer Stopp`() {
        // UP im (Kalt-)Start-Fenster: Stop wird nachgereicht (cancelStartRequested)
        // PLUS transienter Hint „Loslassen vorgemerkt — Aufnahme endet gleich"
        // — das bisher stille Flag war der Verwirrungspunkt (C2-Symptom 1).
        assertEquals(
            MicUpDuringStartAction.PENDING_STOP_HINT,
            micUpDuringStart(startingUp = true),
        )
        // Kein Start im Gange → das Loslassen ist der reguläre Stopp.
        assertEquals(
            MicUpDuringStartAction.REGULAR_STOP,
            micUpDuringStart(startingUp = false),
        )
    }
}
