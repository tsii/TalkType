package de.voicekeyboard.ime

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * AP3 (docs/PLAN_C2_FABLE_R2.md §4, Export v2 Teil 1): Prioritäten der
 * vereinheitlichten Stopp-Ursache [stopCauseOf] — reine Funktion, JVM-testbar,
 * nur Codes (Regel 9). Die Priorität ist im KDoc der Funktion verankert und
 * hier als Vertrag festgenagelt.
 */
class StopCauseTest {

    // 1. Nutzer-Stopp: Loslassen nach Halten — physisches Ereignis (micRel)
    //    schlägt "final" ( reguläres Gesamtfinal ohne anderes Ereignis).
    @Test
    fun `Halte-Ende ist holdup nicht final`() {
        assertEquals(
            "holdup",
            stopCauseOf(explicitCause = "-", micRel = "holdup:5400", lastAbortReason = "final", startSrc = "hold"),
        )
    }

    // 2. Toggle-Schalter: micRel "up" bei Toggle-Start ⇒ "toggle".
    @Test
    fun `Mikro-Schalter-Stopp ist toggle`() {
        assertEquals(
            "toggle",
            stopCauseOf(explicitCause = "-", micRel = "up:1200", lastAbortReason = "final", startSrc = "toggle"),
        )
        // … bei Hold-Start bleibt "up" (Statusleisten-UP).
        assertEquals(
            "up",
            stopCauseOf(explicitCause = "-", micRel = "up:1200", lastAbortReason = "final", startSrc = "hold"),
        )
    }

    // 3. FEINE Abbruchdetails schlagen generische und physische Quellen:
    //    editor_change / michold_cancel haben Vorrang vor micRel und final.
    @Test
    fun `Editorwechsel und Hold-Cancel sind feine Details mit Vorrang`() {
        assertEquals(
            "editor_change",
            stopCauseOf(explicitCause = "-", micRel = "editor:3200", lastAbortReason = "editor_change", startSrc = "hold"),
        )
        assertEquals(
            "michold_cancel",
            stopCauseOf(explicitCause = "-", micRel = "holdcancel:1500", lastAbortReason = "michold_cancel", startSrc = "hold"),
        )
    }

    // 4. Audio-Quellen-Enden mit EIGENEM Code: maxDuration/systemStop statt
    //    generischem audio_error:<code>.
    @Test
    fun `maxDuration und systemStop haben eigene Codes`() {
        assertEquals(
            "maxDuration",
            stopCauseOf(explicitCause = "-", micRel = "", lastAbortReason = "audio_error:maxDuration", startSrc = "hold"),
        )
        assertEquals(
            "systemStop",
            stopCauseOf(explicitCause = "-", micRel = "", lastAbortReason = "audio_error:systemStop", startSrc = "toggle"),
        )
        // andere Audio-Fehler bleiben audio_error:<code>
        assertEquals(
            "audio_error:silence",
            stopCauseOf(explicitCause = "-", micRel = "", lastAbortReason = "audio_error:silence", startSrc = "toggle"),
        )
    }

    // 5. W1b-SYMPTOMFALL (Fable §0.1): Auto-Stop während des Haltens —
    //    explicit "autostop" schlägt ALLES (auch micRel holdup mit ms ≫ audio_s);
    //    die Signatur im Export: stop_cause=autostop + start_src=hold +
    //    gate_at_stop=1 (Gate mitten im Halten verloren/offen) + mic_rel
    //    holdup:<ms≫audio_s>. gate_at_stop ist ein SEPARATES Feld — hier der
    //    Pure-Teil der Signatur.
    @Test
    fun `W1b - autostop schlaegt micRel holdup trotz Halten`() {
        assertEquals(
            "autostop",
            stopCauseOf(explicitCause = "autostop", micRel = "holdup:540000", lastAbortReason = "final", startSrc = "hold"),
        )
    }

    // 6. Hidden (IME-Fenster weg) und weitere Abbruchgründe (abort:<r>).
    @Test
    fun `hidden und abort-Sonstige`() {
        assertEquals(
            "hidden",
            stopCauseOf(explicitCause = "-", micRel = "hidden:9100", lastAbortReason = "final", startSrc = "toggle"),
        )
        assertEquals(
            "abort:start_superseded",
            stopCauseOf(explicitCause = "-", micRel = "", lastAbortReason = "start_superseded", startSrc = "hold"),
        )
    }

    // 7. Defaults: reguläres Final ohne Ereignis ⇒ final; kein Stopp ⇒ idle_noop.
    @Test
    fun `final und idle_noop Defaults`() {
        assertEquals(
            "final",
            stopCauseOf(explicitCause = "-", micRel = "", lastAbortReason = "final", startSrc = "toggle"),
        )
        assertEquals(
            "idle_noop",
            stopCauseOf(explicitCause = "-", micRel = "", lastAbortReason = "-", startSrc = "-"),
        )
        assertEquals(
            "idle_noop",
            stopCauseOf(explicitCause = "-", micRel = "", lastAbortReason = "idle_noop", startSrc = "hold"),
        )
    }
}
