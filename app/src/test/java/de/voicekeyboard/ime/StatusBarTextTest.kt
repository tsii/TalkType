package de.voicekeyboard.ime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * F13a (B1, docs/STREAMING_FREEZE_ANALYSE.md): purely logical text selection
 * of the IME status row — „starte Aufnahme…“ is TRANSIENT and must neither be
 * set sticky nor cover an existing error.
 *
 * Clarity rule (fixed): an error (sticky/visible) is NEWER than the transient
 * text if the caller passes `errorsNewerThanTransient = true` (timestamp
 * comparison at the caller) — then the error wins, otherwise the transient
 * text.
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

    // 1. start text + running recording -> recording text; NEVER „starte…“ after the 3rd call.
    @Test
    fun `start text yields to the running recording - never starte again after the third call`() {
        val first = StatusBarText.select(inputs(transientText = start, recording = false))
        assertEquals(start, first.text) // before the start the transient text is correct
        val second = StatusBarText.select(inputs(transientText = start, recording = true))
        assertEquals(recordingText, second.text) // the recording displaces the start text
        assertTrue("the service must release the transient text", second.transientConsumed)
        // 3rd call: transient gone (the service deleted it) — the recording text remains
        val third = StatusBarText.select(inputs(recording = true))
        assertEquals(recordingText, third.text)
        assertFalse(third.text.contains(start))
    }

    // 2. start text + existing (newer) error -> error visible.
    @Test
    fun `start text covers no existing error`() {
        val r = StatusBarText.select(
            inputs(transientText = start, stickyError = "Berechtigung nötig", errorsNewerThanTransient = true),
        )
        assertEquals("Berechtigung nötig", r.text)
        assertFalse(r.stickyConsumed) // the error persists (R-2)
    }

    // 3. small error (visibleError) after the recording start -> is displayed.
    @Test
    fun `visibleError after the recording start is displayed although the start text was running`() {
        val r = StatusBarText.select(
            inputs(transientText = start, visibleError = "Audio-Start fehlgeschlagen", errorsNewerThanTransient = true),
        )
        assertEquals("Audio-Start fehlgeschlagen", r.text)
        assertTrue(r.transientConsumed) // the start text is done
    }

    // 4. stickyError survives a pure updateStatus() (without extra) (R-2).
    @Test
    fun `stickyError survives a pure updateStatus`() {
        val whileRecording = StatusBarText.select(inputs(stickyError = "Berechtigung nötig", recording = true))
        assertEquals("Berechtigung nötig", whileRecording.text)
        assertFalse(whileRecording.stickyConsumed)
        val atIdle = StatusBarText.select(inputs(stickyError = "Berechtigung nötig"))
        assertEquals("Berechtigung nötig", atIdle.text) // without success it stays
        assertFalse(atIdle.stickyConsumed)
    }

    // 5. success (idle) clears stickyError — existing R-2b behavior.
    @Test
    fun `success clears stickyError`() {
        val r = StatusBarText.select(inputs(stickyError = "Berechtigung nötig", success = true))
        assertEquals(idle, r.text)
        assertTrue("the caller MAY clear stickyError", r.stickyConsumed)
    }

    // 6. the transient text does NOT become sticky: 5 calls later, longer recording, it is gone.
    @Test
    fun `transient text does not become sticky - after five calls it is gone`() {
        val r1 = StatusBarText.select(inputs(transientText = start))
        assertEquals(start, r1.text)
        assertFalse("the start text must not become a stickyError", r1.extraIsSticky)
        var last = r1
        repeat(5) {
            last = StatusBarText.select(inputs(recording = true)) // service: transient deleted
            assertEquals(recordingText, last.text)
        }
        assertFalse(last.text.contains(start))
        assertFalse(last.stickyConsumed)
    }

    // Addition: extra as an ERROR is sticky; extra == transientText is NOT.
    @Test
    fun `extra is only sticky if it is not a transient text`() {
        val error = StatusBarText.select(inputs(extra = "Fehler X", transientText = start))
        assertTrue("the error extra must be marked sticky", error.extraIsSticky)
        assertEquals("Fehler X", error.text)
        val startExtra = StatusBarText.select(inputs(extra = start, transientText = start))
        assertFalse("the start-text extra is transient, NOT sticky", startExtra.extraIsSticky)
        assertEquals(start, startExtra.text)
    }

    // F52 (PLAN_V0130 A3, 🐞: status_res): kind of the selected text per branch —
    // codes/resource names only (rule 9); RAW must NEVER come out of select
    // (it is the forbidden fallback, assertion below).
    @Test
    fun `F52 kind per branch - never RAW - statusResCode only codes`() {
        // STARTING: transient start message without a hint flag.
        assertEquals(
            StatusKind.STARTING,
            StatusBarText.select(inputs(transientText = start)).kind,
        )
        // HINT: the same transient channel, but with the hint flag (F50a hint etc.).
        assertEquals(
            StatusKind.HINT,
            StatusBarText.select(inputs(transientText = "Loslassen vorgemerkt…", transientHint = true)).kind,
        )
        // RECORDING without / DIAG with debug suffix (structure marker, no content).
        assertEquals(StatusKind.RECORDING, StatusBarText.select(inputs(recording = true)).kind)
        assertEquals(
            StatusKind.DIAG,
            StatusBarText.select(inputs(recording = true, diagSuffix = true)).kind,
        )
        // FINALIZING / IDLE / ERROR (extra and visibleError).
        assertEquals(StatusKind.FINALIZING, StatusBarText.select(inputs(finalizing = true)).kind)
        assertEquals(StatusKind.IDLE, StatusBarText.select(inputs()).kind)
        assertEquals(StatusKind.IDLE, StatusBarText.select(inputs(success = true)).kind)
        assertEquals(StatusKind.ERROR, StatusBarText.select(inputs(extra = "Fehler X")).kind)
        assertEquals(
            StatusKind.ERROR,
            StatusBarText.select(inputs(visibleError = "editor_sync_timeout")).kind,
        )
        // RAW: must NEVER come out of select — an assertion beyond all cases
        // above: statusResCode is the only place that may name RAW.
        for (r in listOf(
            StatusBarText.select(inputs(transientText = start)),
            StatusBarText.select(inputs(recording = true)),
            StatusBarText.select(inputs(finalizing = true)),
            StatusBarText.select(inputs()),
            StatusBarText.select(inputs(extra = "Fehler X")),
        )) {
            assertTrue("RAW is the never-allowed fallback", r.kind != StatusKind.RAW)
        }
        // statusResCode: codes only, ERROR carries the resource name (rule 9).
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
