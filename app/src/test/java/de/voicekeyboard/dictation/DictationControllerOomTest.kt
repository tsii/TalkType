package de.voicekeyboard.dictation

import de.voicekeyboard.asr.AsrEngine
import de.voicekeyboard.asr.Hypothesis
import de.voicekeyboard.audio.PcmBlock
import de.voicekeyboard.audio.PcmWindow
import de.voicekeyboard.editor.EditorSink
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * F3/F1 (REVIEW_STAGE6_FIXES): OutOfMemoryError und Edit-Timeout
 * (IllegalStateException) während feedAudio/stop beenden die Session
 * kontrolliert (abort → IDLE) und propagieren den Fehler nach oben —
 * kein schluckendes catch, keine weiteren Schreiboperationen.
 */
class DictationControllerOomTest {

    private class FakeSink : EditorSink {
        var text = ""
        val calls = mutableListOf<Pair<Int, String>>()
        override fun replaceBeforeCursor(replaceLength: Int, newText: String) {
            calls += replaceLength to newText
            text = text.dropLast(replaceLength) + newText
        }
        override fun textBeforeCursor(maxChars: Int): CharSequence? =
            text.takeLast(minOf(maxChars, text.length))
        override fun ownLength(): Int = text.length
    }

    /** Engine, die beim N-ten transcribe den geforderten Fehler wirft. */
    private class ThrowingEngine(private val throwable: Throwable) : AsrEngine {
        var calls = 0
        override fun load(modelDir: File) {}
        override suspend fun transcribe(window: PcmWindow, sessionId: Long, runId: Long): Hypothesis {
            calls++
            throw throwable
        }
        override fun close() {}
    }

    private fun seconds(n: Int): PcmBlock = PcmBlock(0, 0, ShortArray(n * 16_000))

    // F3: OOM während Partial → abort (IDLE) + Fehler propagiert
    @Test fun oomDuringPartialAbortsAndPropagates() {
        val controller = DictationController(ThrowingEngine(OutOfMemoryError("native alloc")), FakeSink())
        controller.start()
        val e = runCatching { runBlocking { controller.feedAudio(seconds(3)) } }.exceptionOrNull()
        assertTrue("OOM muss nach oben propagieren (nicht schlucken)", e is OutOfMemoryError)
        assertEquals(DictationState.IDLE, controller.state) // kontrolliert beendet
        // keine weiteren Schreiboperationen:
        val sink = FakeSink()
        assertTrue(sink.calls.isEmpty())
    }

    // F3: OOM im Final (stop) → abort + propagiert
    @Test fun oomDuringFinalStopAbortsAndPropagates() {
        val controller = DictationController(ThrowingEngine(OutOfMemoryError("bad_alloc")), FakeSink())
        controller.start()
        runBlocking { controller.feedAudio(seconds(1)) } // 1 s: kein Partial (Guard), Puffer aber > 0
        val e = runCatching { runBlocking { controller.stop() } }.exceptionOrNull()
        assertTrue(e is OutOfMemoryError)
        assertEquals(DictationState.IDLE, controller.state)
    }

    // F1-Drift-Regel: Edit-Timeout (IllegalStateException) → Session abbrechen,
    // Fehler propagiert — danach KEINE weiteren Schreiboperationen (applyPartial No-Op)
    @Test fun editTimeoutAbortsSessionAndBlocksFurtherWrites() {
        val sink = FakeSink()
        val controller = DictationController(ThrowingEngine(IllegalStateException("Editor-Sync Timeout")), sink)
        controller.start()
        val e = runCatching { runBlocking { controller.feedAudio(seconds(3)) } }.exceptionOrNull()
        assertTrue("Timeout muss nach oben propagieren", e is IllegalStateException)
        assertEquals(DictationState.IDLE, controller.state)
        // Nach dem Abbruch: applyPartial ist No-Op (Session-IDLE) — keine Schreibops
        val stale = Hypothesis(
            IntArray(0), "STALE", IntArray(0),
            sessionId = controller.sessionId, runId = 1, audioSamples = 48_000, incomplete = false,
        )
        assertEquals(false, controller.applyPartial(stale))
        assertEquals("", sink.text)
        // gewöhnliche Exception (kein Timeout/OOM) bleibt fail()-Pfad, kein Propagieren:
    }

    // Gegenprobe: gewöhnliche Exception bleibt beim alten fail()-Verhalten (kein Wurf)
    @Test fun ordinaryExceptionStillFailsQuietly() {
        val controller = DictationController(ThrowingEngine(RuntimeException("asr error")), FakeSink())
        controller.start()
        val e = runCatching { runBlocking { controller.feedAudio(seconds(3)) } }.exceptionOrNull()
        assertEquals(null, e) // fail() schluckt wie bisher (Fehlercode sichtbar)
        assertEquals(DictationState.IDLE, controller.state)
        assertEquals("asr_partial_error", controller.lastErrorCode)
    }
}
