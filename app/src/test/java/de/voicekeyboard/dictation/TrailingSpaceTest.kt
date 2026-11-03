package de.voicekeyboard.dictation

import de.voicekeyboard.asr.AsrEngine
import de.voicekeyboard.asr.Hypothesis
import de.voicekeyboard.audio.PcmBlock
import de.voicekeyboard.audio.PcmWindow
import de.voicekeyboard.editor.EditorSink
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * T10 (v0.1.39): if a dictation ends on punctuation, the SESSION final
 * automatically appends ONE space in the editor („Hallo.Guten
 * Morgen“ → „Hallo. Guten Morgen“) — pure decision [TrailingSpace.rule],
 * anchored on the real final path ([DictationController.writeHypothesis] only
 * with `final=true`): NOT on intermediate-segment finalizations (F53
 * seg_final / finalizeSegment) and NOT on abort (abort without a final).
 *
 * Double guard of the rule: the committed text does not already end on
 * whitespace AND no space sits at the cursor; empty session ⇒ nothing.
 * Punctuation (tested constant [TrailingSpace.PUNCT]): `. ! ? : ; …` plus
 * trailing closing characters [TrailingSpace.CLOSERS] `” “ " » ) ]` — then
 * the character before them is relevant (multi-level: „…“).
 */
class TrailingSpaceTest {

    // ---- pure rule (≥ 8 cases) ----

    @Test
    fun `every punctuation appends exactly one space`() {
        assertEquals(
            "PUNCT constant (tested list)",
            setOf('.', '!', '?', ':', ';', '…'),
            TrailingSpace.PUNCT,
        )
        for (c in TrailingSpace.PUNCT) {
            assertTrue("punctuation '$c' ⇒ Space", TrailingSpace.rule("Hallo$c", c))
        }
    }

    @Test
    fun `closers - the character before them is relevant`() {
        assertEquals(
            "CLOSERS constant (tested list)",
            setOf('”', '“', '"', '»', ')', ']'),
            TrailingSpace.CLOSERS,
        )
        assertTrue("!”", TrailingSpace.rule("Hallo!”", '”'))
        assertTrue("multi-level …“", TrailingSpace.rule("Ende…“", '“'))
        assertTrue("»", TrailingSpace.rule("Ende.»", '»'))
        assertTrue(")", TrailingSpace.rule("Ende.)", ')'))
        assertTrue("]", TrailingSpace.rule("Ende.]", ']'))
        assertTrue("straight quote", TrailingSpace.rule("Ende.\"", '"'))
        assertFalse("ONLY a closing mark without punctuation before it ⇒ no Space",
            TrailingSpace.rule("”", '”'))
    }

    @Test
    fun `word end appends NO space`() {
        assertFalse("word", TrailingSpace.rule("Tag", 'g'))
        assertFalse("comma is not in the rule", TrailingSpace.rule("Hallo,", ','))
        assertFalse("hyphen", TrailingSpace.rule("Hallo-", '-'))
    }

    @Test
    fun `double guard - no second space`() {
        assertFalse("text already ends on a Space", TrailingSpace.rule("Hallo. ", ' '))
        assertFalse("alreadyAtCursor is a Space (cursor side)", TrailingSpace.rule("Hallo.", ' '))
        assertFalse("tab at the end of the text", TrailingSpace.rule("Hallo.\t", null))
    }

    @Test
    fun `empty session - no space`() {
        assertFalse("null (no output)", TrailingSpace.rule(null, null))
        assertFalse("empty string", TrailingSpace.rule("", null))
        assertFalse("whitespace only", TrailingSpace.rule("   ", ' '))
    }

    // ---- anchoring on the real final path (only there) ----

    /** Fake sink: applies deltas at the cursor (like DictationControllerTest). */
    private class RecordingSink : EditorSink {
        var text: String = ""
        override fun replaceBeforeCursor(replaceLength: Int, newText: String) {
            text = text.dropLast(replaceLength) + newText
        }
        override fun textBeforeCursor(maxChars: Int): CharSequence? =
            text.takeLast(minOf(maxChars, text.length))
        override fun ownLength(): Int = text.length
    }

    /** Fake ASR: configurable text, echoes the session id. */
    private class FakeEngine : AsrEngine {
        var nextText = ""
        override fun load(modelDir: File) {}
        override suspend fun transcribe(
            window: PcmWindow,
            sessionId: Long,
            runId: Long,
        ): Hypothesis = Hypothesis(
            tokenIds = IntArray(0),
            text = nextText,
            frameTimesMs = IntArray(0),
            sessionId = sessionId,
            runId = runId,
            audioSamples = window.samples.size,
            incomplete = false,
        )
        override fun close() {}
    }

    private class Fixture {
        val engine = FakeEngine()
        val sink = RecordingSink()
        val controller = DictationController(engine, sink)
    }

    private fun seconds(n: Int): PcmBlock = PcmBlock(0, 0, ShortArray(n * 16_000))

    @Test
    fun `session final appends space after punctuation and reports trail_sp 1`() {
        val f = Fixture()
        f.engine.nextText = "Hallo."
        f.controller.start()
        runBlocking { f.controller.feedAudio(seconds(2)) } // Partial: "Hallo."
        assertEquals("partial unchanged", "Hallo.", f.sink.text)
        val outcome = runBlocking { f.controller.stop() } // real session final
        assertEquals("final with the trailing space appended (T10)", "Hallo. ", f.sink.text)
        assertTrue("FinalRan", outcome is StopOutcome.FinalRan)
        assertTrue("trail_sp=1 in the final snapshot (VK-SESS)",
            (outcome as StopOutcome.FinalRan).trailingSpace)
    }

    @Test
    fun `session final without punctuation - no space, trail_sp 0`() {
        val f = Fixture()
        f.engine.nextText = "Hallo"
        f.controller.start()
        runBlocking { f.controller.feedAudio(seconds(2)) }
        val outcome = runBlocking { f.controller.stop() }
        assertEquals("no punctuation ⇒ no Space", "Hallo", f.sink.text)
        assertTrue("FinalRan", outcome is StopOutcome.FinalRan)
        assertFalse("trail_sp=0", (outcome as StopOutcome.FinalRan).trailingSpace)
    }

    @Test
    fun `intermediate-segment final (F53) appends NO space`() {
        val f = Fixture()
        f.engine.nextText = "Ein Satz."
        f.controller.start()
        runBlocking { f.controller.feedAudio(seconds(2)) }
        val ok = runBlocking { f.controller.finalizeSegment() } // seg_final, NOT the session final
        assertTrue("mini-final ran", ok)
        assertEquals("seg_final without an appended space", "Ein Satz.", f.sink.text)
        // the SESSION final afterwards appends the space (the rule fires exactly once at the end)
        val outcome = runBlocking { f.controller.stop() }
        assertEquals("final after it: one Space", "Ein Satz. ", f.sink.text)
        assertTrue("trail_sp=1", (outcome as StopOutcome.FinalRan).trailingSpace)
    }

    @Test
    fun `abort path appends NO space`() {
        val f = Fixture()
        f.engine.nextText = "Hallo."
        f.controller.start()
        runBlocking { f.controller.feedAudio(seconds(2)) } // partial: "Hallo."
        assertEquals("Hallo.", f.sink.text)
        f.controller.abort() // NO final, no space
        assertEquals("abort without an appended space", "Hallo.", f.sink.text)
    }
}
