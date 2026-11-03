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
 * T10 (v0.1.39): Endet ein Diktat auf einem Satzzeichen, hängt das
 * SESSION-Finale automatisch EIN Leerzeichen in den Editor („Hallo.Guten
 * Morgen“ → „Hallo. Guten Morgen“) — reine Entscheidung [TrailingSpace.rule],
 * verankert am echten Final-Pfad ([DictationController.writeHypothesis] nur
 * mit `final=true`): NICHT bei Zwischensegment-Finalisierungen (F53
 * seg_final / finalizeSegment) und NICHT beim Abbruch (abort ohne Finale).
 *
 * Doppelschutz der Regel: der committete Text endet nicht schon auf
 * Whitespace UND am Cursor steht kein Leerzeichen; leere Session ⇒ nichts.
 * Satzzeichen (getestete Konstante [TrailingSpace.PUNCT]): `. ! ? : ; …` plus
 * nachgestellte Schlusszeichen [TrailingSpace.CLOSERS] `” “ " » ) ]` — dann
 * massgeblich das Zeichen davor (mehrstufig: „…“).
 */
class TrailingSpaceTest {

    // ---- reine Regel (≥ 8 Fälle) ----

    @Test
    fun `jedes Satzzeichen haengt genau ein Leerzeichen an`() {
        assertEquals(
            "PUNCT-Konstante (getestete Liste)",
            setOf('.', '!', '?', ':', ';', '…'),
            TrailingSpace.PUNCT,
        )
        for (c in TrailingSpace.PUNCT) {
            assertTrue("punctuation '$c' ⇒ Space", TrailingSpace.rule("Hallo$c", c))
        }
    }

    @Test
    fun `Schlusszeichen - massgeblich ist das Zeichen davor`() {
        assertEquals(
            "CLOSERS-Konstante (getestete Liste)",
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
    fun `Wortende haengt KEIN Space an`() {
        assertFalse("word", TrailingSpace.rule("Tag", 'g'))
        assertFalse("comma is not in the rule", TrailingSpace.rule("Hallo,", ','))
        assertFalse("hyphen", TrailingSpace.rule("Hallo-", '-'))
    }

    @Test
    fun `Doppelschutz - kein zweites Leerzeichen`() {
        assertFalse("text already ends on a Space", TrailingSpace.rule("Hallo. ", ' '))
        assertFalse("alreadyAtCursor is a Space (cursor side)", TrailingSpace.rule("Hallo.", ' '))
        assertFalse("tab at the end of the text", TrailingSpace.rule("Hallo.\t", null))
    }

    @Test
    fun `leere Session - kein Space`() {
        assertFalse("null (no output)", TrailingSpace.rule(null, null))
        assertFalse("empty string", TrailingSpace.rule("", null))
        assertFalse("whitespace only", TrailingSpace.rule("   ", ' '))
    }

    // ---- Verankerung am echten Final-Pfad (nur dort) ----

    /** Fake sink: wendet Deltas am Cursor an (wie DictationControllerTest). */
    private class RecordingSink : EditorSink {
        var text: String = ""
        override fun replaceBeforeCursor(replaceLength: Int, newText: String) {
            text = text.dropLast(replaceLength) + newText
        }
        override fun textBeforeCursor(maxChars: Int): CharSequence? =
            text.takeLast(minOf(maxChars, text.length))
        override fun ownLength(): Int = text.length
    }

    /** Fake ASR: konfigurierbarer Text, echo der Session-Id. */
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
    fun `Session-Finale haengt Space hinter Satzzeichen und meldet trail_sp 1`() {
        val f = Fixture()
        f.engine.nextText = "Hallo."
        f.controller.start()
        runBlocking { f.controller.feedAudio(seconds(2)) } // Partial: "Hallo."
        assertEquals("partial unchanged", "Hallo.", f.sink.text)
        val outcome = runBlocking { f.controller.stop() } // echtes Session-Finale
        assertEquals("final with the trailing space appended (T10)", "Hallo. ", f.sink.text)
        assertTrue("FinalRan", outcome is StopOutcome.FinalRan)
        assertTrue("trail_sp=1 in the final snapshot (VK-SESS)",
            (outcome as StopOutcome.FinalRan).trailingSpace)
    }

    @Test
    fun `Session-Finale ohne Satzzeichen - kein Space, trail_sp 0`() {
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
    fun `Zwischensegment-Finale (F53) haengt KEIN Space an`() {
        val f = Fixture()
        f.engine.nextText = "Ein Satz."
        f.controller.start()
        runBlocking { f.controller.feedAudio(seconds(2)) }
        val ok = runBlocking { f.controller.finalizeSegment() } // seg_final, KEIN Session-Finale
        assertTrue("mini-final ran", ok)
        assertEquals("seg_final without an appended space", "Ein Satz.", f.sink.text)
        // das SESSION-Finale danach hängt den Space (Regel greift am Ende genau einmal)
        val outcome = runBlocking { f.controller.stop() }
        assertEquals("final after it: one Space", "Ein Satz. ", f.sink.text)
        assertTrue("trail_sp=1", (outcome as StopOutcome.FinalRan).trailingSpace)
    }

    @Test
    fun `Abbruch-Pfad haengt KEIN Space an`() {
        val f = Fixture()
        f.engine.nextText = "Hallo."
        f.controller.start()
        runBlocking { f.controller.feedAudio(seconds(2)) } // Partial: "Hallo."
        assertEquals("Hallo.", f.sink.text)
        f.controller.abort() // KEIN Finale, kein Space
        assertEquals("abort without an appended space", "Hallo.", f.sink.text)
    }
}
