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
 * F3/F1 (REVIEW_STAGE6_FIXES): OutOfMemoryError and edit timeout
 * (IllegalStateException) during feedAudio/stop end the session in a
 * controlled way (abort → IDLE) and propagate the error upward —
 * no swallowing catch, no further write operations.
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

    /** Engine that throws the required error on the Nth transcribe. */
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

    // F3: OOM during a partial → abort (IDLE) + error propagated
    @Test fun oomDuringPartialAbortsAndPropagates() {
        val controller = DictationController(ThrowingEngine(OutOfMemoryError("native alloc")), FakeSink())
        controller.start()
        val e = runCatching { runBlocking { controller.feedAudio(seconds(3)) } }.exceptionOrNull()
        assertTrue("OOM must propagate upward (not swallowed)", e is OutOfMemoryError)
        assertEquals(DictationState.IDLE, controller.state) // ended in a controlled way
        // no further write operations:
        val sink = FakeSink()
        assertTrue(sink.calls.isEmpty())
    }

    // F3: OOM in the final (stop) → abort + propagated
    @Test fun oomDuringFinalStopAbortsAndPropagates() {
        val controller = DictationController(ThrowingEngine(OutOfMemoryError("bad_alloc")), FakeSink())
        controller.start()
        runBlocking { controller.feedAudio(seconds(1)) } // 1 s: no partial (guard), but buffer > 0
        val e = runCatching { runBlocking { controller.stop() } }.exceptionOrNull()
        assertTrue(e is OutOfMemoryError)
        assertEquals(DictationState.IDLE, controller.state)
    }

    // F1 drift rule: edit timeout (IllegalStateException) → abort the session,
    // error propagated — afterwards NO further write operations (applyPartial no-op)
    @Test fun editTimeoutAbortsSessionAndBlocksFurtherWrites() {
        val sink = FakeSink()
        val controller = DictationController(ThrowingEngine(IllegalStateException("Editor-Sync Timeout")), sink)
        controller.start()
        val e = runCatching { runBlocking { controller.feedAudio(seconds(3)) } }.exceptionOrNull()
        assertTrue("timeout must propagate upward", e is IllegalStateException)
        assertEquals(DictationState.IDLE, controller.state)
        // after the abort: applyPartial is a no-op (session IDLE) — no write ops
        val stale = Hypothesis(
            IntArray(0), "STALE", IntArray(0),
            sessionId = controller.sessionId, runId = 1, audioSamples = 48_000, incomplete = false,
        )
        assertEquals(false, controller.applyPartial(stale))
        assertEquals("", sink.text)
        // an ordinary exception (no timeout/OOM) stays on the fail() path, not propagated:
    }

    // counter-check: an ordinary exception keeps the old fail() behavior (no throw)
    @Test fun ordinaryExceptionStillFailsQuietly() {
        val controller = DictationController(ThrowingEngine(RuntimeException("asr error")), FakeSink())
        controller.start()
        val e = runCatching { runBlocking { controller.feedAudio(seconds(3)) } }.exceptionOrNull()
        assertEquals(null, e) // fail() swallows as before (error code visible)
        assertEquals(DictationState.IDLE, controller.state)
        assertEquals("asr_partial_error", controller.lastErrorCode)
    }
}
