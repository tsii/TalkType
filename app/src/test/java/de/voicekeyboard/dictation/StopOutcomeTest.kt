package de.voicekeyboard.dictation

import de.voicekeyboard.asr.AsrEngine
import de.voicekeyboard.asr.Hypothesis
import de.voicekeyboard.audio.PcmBlock
import de.voicekeyboard.audio.PcmWindow
import de.voicekeyboard.editor.EditorSink
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * F13b (docs/STREAMING_FREEZE_ANALYSE.md, C1): an error during recording
 * must NEVER pass without consequences. stop() explicitly reports whether a
 * final ran ([StopOutcome]); fail() preserves the error state until the next
 * start ([DictationController.failedThisSession]); discarded blocks are
 * counted ([DictationController.droppedBlocks]).
 */
class StopOutcomeTest {

    private class RecordingSink : EditorSink {
        var text: String = ""
        override fun replaceBeforeCursor(replaceLength: Int, newText: String) {
            text = text.dropLast(replaceLength) + newText
        }
        override fun textBeforeCursor(maxChars: Int): CharSequence? =
            text.takeLast(minOf(maxChars, text.length))
        override fun ownLength(): Int = text.length
    }

    private class FakeEngine : AsrEngine {
        var failNextPartials = false
        var nextText = "hallo"
        var partialCalls = 0

        override fun load(modelDir: File) {}

        override suspend fun transcribe(window: PcmWindow, sessionId: Long, runId: Long): Hypothesis {
            partialCalls++
            if (failNextPartials) {
                failNextPartials = false
                throw IllegalStateException("asr error")
            }
            return Hypothesis(
                tokenIds = IntArray(0),
                text = nextText,
                frameTimesMs = IntArray(0),
                sessionId = sessionId,
                runId = runId,
                audioSamples = window.samples.size,
                incomplete = false,
            )
        }

        override fun close() {}
    }

    private class Fixture {
        val engine = FakeEngine()
        val sink = RecordingSink()
        val controller = DictationController(engine, sink)
    }

    private fun seconds(n: Int): PcmBlock = PcmBlock(0, 0, ShortArray(n * 16_000))

    // (a) partial exception -> state IDLE, stop() reports "no final",
    //     the error code stays visible (failedThisSession).
    @Test
    fun `partial exception leads to NoFinal and a visible error code`() = runBlocking {
        val f = Fixture()
        assertTrue(f.controller.start())
        f.engine.failNextPartials = true
        f.controller.feedAudio(seconds(3)) // triggers fail(ASR_PARTIAL_ERROR)
        assertEquals(DictationState.IDLE, f.controller.state)
        assertTrue("the error must be preserved until it becomes visible", f.controller.failedThisSession)
        assertEquals("asr_partial_error", f.controller.lastErrorCode)

        val outcome = f.controller.stop() // release after the silent fail
        assertTrue("stop() must report 'no final' to the caller", outcome is StopOutcome.NoFinal)
        assertEquals("asr_partial_error", (outcome as StopOutcome.NoFinal).errorCode)
        assertEquals(DictationState.IDLE, f.controller.state)
    }

    // (b) blocks arriving after the error increment the drop counter (no silent nothing).
    @Test
    fun `blocks after the error are counted as discarded`() = runBlocking {
        val f = Fixture()
        assertTrue(f.controller.start())
        f.engine.failNextPartials = true
        f.controller.feedAudio(seconds(3)) // error → IDLE
        assertEquals(0, f.controller.droppedBlocks)
        repeat(4) { f.controller.feedAudio(seconds(1)) } // all discarded
        assertEquals("dropped blocks must be countable", 4, f.controller.droppedBlocks)
        assertEquals(DictationState.IDLE, f.controller.state)
    }

    // (c) a new start clears the error and the counters (a short dictation
    //     afterwards works).
    @Test
    fun `a new start clears the error state and counters`() = runBlocking {
        val f = Fixture()
        assertTrue(f.controller.start())
        f.engine.failNextPartials = true
        f.controller.feedAudio(seconds(3)) // error
        f.controller.feedAudio(seconds(1)) // 1 discarded
        assertTrue(f.controller.failedThisSession)
        assertEquals(1, f.controller.droppedBlocks)

        // user presses mic again: everything clean
        assertTrue(f.controller.start())
        assertFalse(f.controller.failedThisSession)
        assertNull(f.controller.lastErrorCode)
        assertEquals(0, f.controller.droppedBlocks)
        f.controller.feedAudio(seconds(3)) // runs normally again
        assertEquals(DictationState.RECORDING, f.controller.state)
        val outcome = f.controller.stop()
        assertTrue(outcome is StopOutcome.FinalRan)
        assertNull((outcome as StopOutcome.FinalRan).finalError)
        assertTrue(f.sink.text.contains("hallo"))
    }

    // (d) normal flow unchanged: the final runs, no error message, text stays.
    @Test
    fun `normal flow stays unchanged - FinalRan without error`() = runBlocking {
        val f = Fixture()
        assertTrue(f.controller.start())
        f.controller.feedAudio(seconds(3))
        assertEquals(DictationState.RECORDING, f.controller.state)
        val outcome = f.controller.stop()
        assertTrue(outcome is StopOutcome.FinalRan)
        assertNull((outcome as StopOutcome.FinalRan).finalError)
        assertFalse(f.controller.failedThisSession)
        assertEquals(0, f.controller.droppedBlocks)
        assertTrue(f.sink.text.isNotEmpty())
    }

    // (e) stop() without a prior recording stays a no-op (no fake error).
    @Test
    fun `stop without a recording stays a NoOp without a fake error`() = runBlocking {
        val f = Fixture()
        val outcome = f.controller.stop()
        assertTrue(outcome is StopOutcome.IdleNoOp)
        assertFalse(f.controller.failedThisSession)
        assertNull(f.controller.lastErrorCode)
        assertEquals(DictationState.IDLE, f.controller.state)
    }
}
