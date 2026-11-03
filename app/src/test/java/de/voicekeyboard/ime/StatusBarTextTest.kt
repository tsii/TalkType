package de.voicekeyboard.ime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * F13a (B1, docs/STREAMING_FREEZE_ANALYSE.md): Reinlogische Textauswahl der
 * IME-Statuszeile — „starte Aufnahme…" ist TRANSIENT und darf weder sticky
 * gesetzt noch einen vorhandenen Fehler verdecken.
 *
 * Klärheitsregel (fixiert): ein Fehler (sticky/visible) ist NEUER als der
 * transiente Text, wenn der Aufrufer `errorsNewerThanTransient = true` übergibt
 * (Zeitstempelvergleich beim Aufrufer) — dann gewinnt der Fehler, sonst der
 * transiente Text.
 */
class StatusBarTextTest {

    private val start = "Starte Aufnahme…"
    private val idle = "Bereit"
    private val recordingText = "Diktat… (intern)"
    private val finalizingText = "Verarbeite…"

    private fun inputs(
        extra: String? = null,
        transientText: String? = null,
        stickyError: String? = null,
        visibleError: String? = null,
        recording: Boolean = false,
        finalizing: Boolean = false,
        errorsNewerThanTransient: Boolean = false,
        success: Boolean = false,
        transientHint: Boolean = false,
        diagSuffix: Boolean = false,
    ) = StatusBarText.Inputs(
        extra = extra,
        transientText = transientText,
        stickyError = stickyError,
        visibleError = visibleError,
        recording = recording,
        finalizing = finalizing,
        recordingText = recordingText,
        finalizingText = finalizingText,
        idleText = idle,
        errorsNewerThanTransient = errorsNewerThanTransient,
        success = success,
        transientHint = transientHint,
        diagSuffix = diagSuffix,
    )

    // 1. Starttext + laufende Aufnahme -> Aufnahmetext; NIE „starte…" nach dem 3. Aufruf.
    @Test
    fun `Starttext weicht der laufenden Aufnahme - nie wieder starte nach dem dritten Aufruf`() {
        val first = StatusBarText.select(inputs(transientText = start, recording = false))
        assertEquals(start, first.text) // vor dem Start ist der transiente Text korrekt
        val second = StatusBarText.select(inputs(transientText = start, recording = true))
        assertEquals(recordingText, second.text) // Aufnahme verdrängt den Starttext
        assertTrue("Service muss den transienten Text freigeben", second.transientConsumed)
        // 3. Aufruf: transient ist weg (Service hat ihn gelöscht) — bleibt der Aufnahmetext
        val third = StatusBarText.select(inputs(recording = true))
        assertEquals(recordingText, third.text)
        assertFalse(third.text.contains(start))
    }

    // 2. Starttext + vorhandener (neuerer) Fehler -> Fehler sichtbar.
    @Test
    fun `Starttext verdeckt keinen vorhandenen Fehler`() {
        val r = StatusBarText.select(
            inputs(transientText = start, stickyError = "Berechtigung nötig", errorsNewerThanTransient = true),
        )
        assertEquals("Berechtigung nötig", r.text)
        assertFalse(r.stickyConsumed) // Fehler bleibt bestehen (R-2)
    }

    // 3. Kleiner Fehler (visibleError) nach Aufnahmebeginn -> wird angezeigt.
    @Test
    fun `visibleError nach Aufnahmebeginn wird angezeigt obwohl der Starttext lief`() {
        val r = StatusBarText.select(
            inputs(transientText = start, visibleError = "Audio-Start fehlgeschlagen", errorsNewerThanTransient = true),
        )
        assertEquals("Audio-Start fehlgeschlagen", r.text)
        assertTrue(r.transientConsumed) // Starttext ist erledigt
    }

    // 4. stickyError bleibt bei reinem updateStatus() (ohne extra) erhalten (R-2).
    @Test
    fun `stickyError bleibt bei reinem updateStatus erhalten`() {
        val whileRecording = StatusBarText.select(inputs(stickyError = "Berechtigung nötig", recording = true))
        assertEquals("Berechtigung nötig", whileRecording.text)
        assertFalse(whileRecording.stickyConsumed)
        val atIdle = StatusBarText.select(inputs(stickyError = "Berechtigung nötig"))
        assertEquals("Berechtigung nötig", atIdle.text) // ohne Erfolg bleibt er stehen
        assertFalse(atIdle.stickyConsumed)
    }

    // 5. Erfolg (idle) löscht stickyError — bestehendes R-2b-Verhalten.
    @Test
    fun `Erfolg loescht stickyError`() {
        val r = StatusBarText.select(inputs(stickyError = "Berechtigung nötig", success = true))
        assertEquals(idle, r.text)
        assertTrue("Aufrufer DARF stickyError löschen", r.stickyConsumed)
    }

    // 6. transient-Text wird NICHT sticky: 5 Aufrufe später, laengere Aufnahme, ist er weg.
    @Test
    fun `transienter Text wird nicht sticky - nach fuenf Aufrufen ist er weg`() {
        val r1 = StatusBarText.select(inputs(transientText = start))
        assertEquals(start, r1.text)
        assertFalse("Starttext darf kein stickyError werden", r1.extraIsSticky)
        var last = r1
        repeat(5) {
            last = StatusBarText.select(inputs(recording = true)) // Service: transient gelöscht
            assertEquals(recordingText, last.text)
        }
        assertFalse(last.text.contains(start))
        assertFalse(last.stickyConsumed)
    }

    // Ergänzung: extra als FEHLER ist sticky; extra == transientText ist es NICHT.
    @Test
    fun `extra ist nur sticky wenn es kein transienter Text ist`() {
        val error = StatusBarText.select(inputs(extra = "Fehler X", transientText = start))
        assertTrue("Fehler-extra muss als sticky gekennzeichnet werden", error.extraIsSticky)
        assertEquals("Fehler X", error.text)
        val startExtra = StatusBarText.select(inputs(extra = start, transientText = start))
        assertFalse("Starttext-extra ist transient, NICHT sticky", startExtra.extraIsSticky)
        assertEquals(start, startExtra.text)
    }

    // F52 (PLAN_V0130 A3, 🐞: status_res): Art des gewählten Texts je Zweig —
    // nur Codes/Ressourcen-Namen (Regel 9); RAW darf NIE aus select kommen
    // (er ist der verbotene Rückfall, Assertion unten).
    @Test
    fun `F52 kind je Zweig - RAW nie - statusResCode nur Codes`() {
        // STARTING: transiente Startmeldung ohne Hint-Flag.
        assertEquals(
            StatusKind.STARTING,
            StatusBarText.select(inputs(transientText = start)).kind,
        )
        // HINT: derselbe transiente Kanal, aber mit Hint-Flag (F50a-Hint etc.).
        assertEquals(
            StatusKind.HINT,
            StatusBarText.select(inputs(transientText = "Loslassen vorgemerkt…", transientHint = true)).kind,
        )
        // RECORDING ohne / DIAG mit Debug-Suffix (Strukturmarker, kein Inhalt).
        assertEquals(StatusKind.RECORDING, StatusBarText.select(inputs(recording = true)).kind)
        assertEquals(
            StatusKind.DIAG,
            StatusBarText.select(inputs(recording = true, diagSuffix = true)).kind,
        )
        // FINALIZING / IDLE / ERROR (extra und visibleError).
        assertEquals(StatusKind.FINALIZING, StatusBarText.select(inputs(finalizing = true)).kind)
        assertEquals(StatusKind.IDLE, StatusBarText.select(inputs()).kind)
        assertEquals(StatusKind.IDLE, StatusBarText.select(inputs(success = true)).kind)
        assertEquals(StatusKind.ERROR, StatusBarText.select(inputs(extra = "Fehler X")).kind)
        assertEquals(
            StatusKind.ERROR,
            StatusBarText.select(inputs(visibleError = "editor_sync_timeout")).kind,
        )
        // RAW: darf aus select NIE kommen — Assertion über alle obigen Fälle
        // hinaus: statusResCode ist die einzige Stelle, die RAW benennen kann.
        for (r in listOf(
            StatusBarText.select(inputs(transientText = start)),
            StatusBarText.select(inputs(recording = true)),
            StatusBarText.select(inputs(finalizing = true)),
            StatusBarText.select(inputs()),
            StatusBarText.select(inputs(extra = "Fehler X")),
        )) {
            assertTrue("RAW ist der nie erlaubte Rückfall", r.kind != StatusKind.RAW)
        }
        // statusResCode: nur Codes, ERROR trägt den Ressourcen-Namen (Regel 9).
        assertEquals("starting", StatusBarText.statusResCode(StatusKind.STARTING))
        assertEquals("recording", StatusBarText.statusResCode(StatusKind.RECORDING))
        assertEquals("finalizing", StatusBarText.statusResCode(StatusKind.FINALIZING))
        assertEquals("idle", StatusBarText.statusResCode(StatusKind.IDLE))
        assertEquals("hint", StatusBarText.statusResCode(StatusKind.HINT))
        assertEquals("diag", StatusBarText.statusResCode(StatusKind.DIAG))
        assertEquals(
            "error:dictation_error_editor_busy",
            StatusBarText.statusResCode(StatusKind.ERROR, "dictation_error_editor_busy"),
        )
        assertEquals("error:unknown", StatusBarText.statusResCode(StatusKind.ERROR, null))
        assertEquals("raw", StatusBarText.statusResCode(StatusKind.RAW))
    }
}
