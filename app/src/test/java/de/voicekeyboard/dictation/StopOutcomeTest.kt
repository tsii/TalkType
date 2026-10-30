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
 * F13b (docs/STREAMING_FREEZE_ANALYSE.md, C1): Ein Fehler während der Aufnahme
 * darf NIE folgenlos bleiben. stop() meldet ausdrücklich, ob ein Finale gelaufen
 * ist ([StopOutcome]); fail() bewahrt den Fehlerzustand bis zum nächsten Start
 * ([DictationController.failedThisSession]); verworfene Blocks werden gezählt
 * ([DictationController.droppedBlocks]).
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

    // (a) Partials-Ausnahme -> state IDLE, stop() meldet „kein Finale",
    //     Fehlercode bleibt sichtbar (failedThisSession).
    @Test
    fun `Partials-Ausnahme fuehrt zu NoFinal und sichtbarem Fehlercode`() = runBlocking {
        val f = Fixture()
        assertTrue(f.controller.start())
        f.engine.failNextPartials = true
        f.controller.feedAudio(seconds(3)) // löst fail(ASR_PARTIAL_ERROR) aus
        assertEquals(DictationState.IDLE, f.controller.state)
        assertTrue("Fehler muss bis zur Sichtbarkeit bewahrt werden", f.controller.failedThisSession)
        assertEquals("asr_partial_error", f.controller.lastErrorCode)

        val outcome = f.controller.stop() // Loslassen nach dem stillen Fail
        assertTrue("stop() muss dem Aufrufer „kein Finale“ melden", outcome is StopOutcome.NoFinal)
        assertEquals("asr_partial_error", (outcome as StopOutcome.NoFinal).errorCode)
        assertEquals(DictationState.IDLE, f.controller.state)
    }

    // (b) Nach dem Fehler eingehende Blocks erhöhen den Drop-Zähler (kein stilles Nichts).
    @Test
    fun `Blocks nach dem Fehler werden gezaehlt verworfen`() = runBlocking {
        val f = Fixture()
        assertTrue(f.controller.start())
        f.engine.failNextPartials = true
        f.controller.feedAudio(seconds(3)) // Fehler → IDLE
        assertEquals(0, f.controller.droppedBlocks)
        repeat(4) { f.controller.feedAudio(seconds(1)) } // alle verworfen
        assertEquals("verworfene Blocks müssen zählbar sein", 4, f.controller.droppedBlocks)
        assertEquals(DictationState.IDLE, f.controller.state)
    }

    // (c) Neuer Start löscht Fehler und Zähler (Kurz-Diktat danach funktioniert).
    @Test
    fun `Neuer Start loescht Fehlerzustand und Zaehler`() = runBlocking {
        val f = Fixture()
        assertTrue(f.controller.start())
        f.engine.failNextPartials = true
        f.controller.feedAudio(seconds(3)) // Fehler
        f.controller.feedAudio(seconds(1)) // 1 verworfen
        assertTrue(f.controller.failedThisSession)
        assertEquals(1, f.controller.droppedBlocks)

        // Nutzer drückt Mic erneut: alles sauber
        assertTrue(f.controller.start())
        assertFalse(f.controller.failedThisSession)
        assertNull(f.controller.lastErrorCode)
        assertEquals(0, f.controller.droppedBlocks)
        f.controller.feedAudio(seconds(3)) // läuft wieder normal
        assertEquals(DictationState.RECORDING, f.controller.state)
        val outcome = f.controller.stop()
        assertTrue(outcome is StopOutcome.FinalRan)
        assertNull((outcome as StopOutcome.FinalRan).finalError)
        assertTrue(f.sink.text.contains("hallo"))
    }

    // (d) Normaler Ablauf unverändert: Finale läuft, keine Fehlermeldung, Text bleibt.
    @Test
    fun `Normaler Ablauf bleibt unveraendert - FinalRan ohne Fehler`() = runBlocking {
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

    // (e) stop() ohne vorherige Aufnahme bleibt No-Op (kein Fake-Fehler).
    @Test
    fun `stop ohne Aufnahme bleibt NoOp ohne Fake-Fehler`() = runBlocking {
        val f = Fixture()
        val outcome = f.controller.stop()
        assertTrue(outcome is StopOutcome.IdleNoOp)
        assertFalse(f.controller.failedThisSession)
        assertNull(f.controller.lastErrorCode)
        assertEquals(DictationState.IDLE, f.controller.state)
    }
}
