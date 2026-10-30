package de.voicekeyboard.dictation

import de.voicekeyboard.asr.AsrEngine
import de.voicekeyboard.asr.Hypothesis
import de.voicekeyboard.audio.PcmBlock
import de.voicekeyboard.audio.PcmWindow
import de.voicekeyboard.editor.EditorSink
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class DictationControllerTest {

    /** Fake-Sink: zeichnet Deltas auf, bildet Text am Cursor ab, kann fehlschlagen. */
    private class RecordingSink : EditorSink {
        var text: String = ""
        var failNext = false
        val calls = mutableListOf<Pair<Int, String>>()

        override fun replaceBeforeCursor(replaceLength: Int, newText: String) {
            if (failNext) {
                failNext = false
                throw IllegalStateException("sink error")
            }
            calls += replaceLength to newText
            text = text.dropLast(replaceLength) + newText
        }

        override fun textBeforeCursor(maxChars: Int): CharSequence? =
            text.takeLast(minOf(maxChars, text.length))

        override fun ownLength(): Int = text.length
    }

    /** Fake-ASR: liefert konfigurierbaren Text, kann Fehler und alte Sessions simulieren. */
    private class FakeEngine : AsrEngine {
        var nextText = ""
        var failNext = false
        var forceSessionId: Long? = null
        var calls = 0

        override fun load(modelDir: File) {}

        override suspend fun transcribe(
            window: PcmWindow,
            sessionId: Long,
            runId: Long,
        ): Hypothesis {
            calls++
            if (failNext) {
                failNext = false
                throw IllegalStateException("asr error")
            }
            return Hypothesis(
                tokenIds = IntArray(0),
                text = nextText,
                frameTimesMs = IntArray(0),
                sessionId = forceSessionId ?: sessionId, // Echo der aktuellen Session
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

    // --- Idempotenz (KONZEPT §2.1) ---

    @Test fun doubleStartIsNoOp() {
        val f = Fixture()
        assertTrue(f.controller.start())
        assertEquals(DictationState.RECORDING, f.controller.state)
        assertFalse(f.controller.start()) // doppeltes start(): kein Neustart
        assertEquals(DictationState.RECORDING, f.controller.state)
        runBlocking { f.controller.stop() }
        assertEquals(DictationState.IDLE, f.controller.state)
        runBlocking { f.controller.stop() } // stop() ohne Start: No-Op
        assertEquals(DictationState.IDLE, f.controller.state)
        assertEquals(0, f.engine.calls)
        assertNull(f.controller.lastErrorCode)
    }

    // --- min_audio_s = 2.0 gilt nur für Partials (ASR_CONTRACT §4.4) ---

    @Test fun partialsBelowMinAudioAreNotDecoded() {
        val f = Fixture()
        f.engine.nextText = "Hallo"
        f.controller.start()
        runBlocking { f.controller.feedAudio(seconds(1)) } // 1,0 s < 2,0 s
        assertEquals(0, f.engine.calls) // Guard: keine Halluzinations-Partial
        assertEquals("", f.sink.text)
        runBlocking { f.controller.feedAudio(seconds(1)) } // 2,0 s erreicht
        assertEquals(1, f.engine.calls)
        assertEquals("Hallo", f.sink.text)
    }

    @Test fun finalDecodeRunsEvenBelowMinAudio() {
        val f = Fixture()
        f.engine.nextText = "Kurz"
        f.controller.start()
        runBlocking { f.controller.feedAudio(seconds(1)) } // kein Partial (Guard)
        assertEquals(0, f.engine.calls)
        runBlocking { f.controller.stop() } // Release ⇒ immer final dekodieren
        assertEquals(1, f.engine.calls)
        assertEquals("Kurz", f.sink.text)
        assertEquals(DictationState.IDLE, f.controller.state)
    }

    // --- Finalisierung ersetzt den Partialtext vollständig ---

    @Test fun finalizationReplacesPartialTail() {
        val f = Fixture()
        f.controller.start()
        f.engine.nextText = "Das ist derzw"
        runBlocking { f.controller.feedAudio(seconds(2)) } // Partial
        assertEquals("Das ist derzw", f.sink.text)
        f.engine.nextText = "Das ist der zweite" // „zw“ muss ersetzt werden
        runBlocking { f.controller.stop() } // Final: ganze Differenz
        assertEquals("Das ist der zweite", f.sink.text)
        assertEquals(DictationState.IDLE, f.controller.state)
        // minimales Delta: nur „zw“ gelöscht, Rest angehängt
        assertEquals(listOf(0 to "Das ist derzw", 2 to " zweite"), f.sink.calls)
    }

    // --- Verspätete Ergebnisse alter Sessions werden verworfen ---

    @Test fun lateResultOfOldSessionIsDiscarded() {
        val f = Fixture()
        f.controller.start() // Session 1
        f.engine.nextText = "Alt"
        runBlocking { f.controller.feedAudio(seconds(2)) }
        assertEquals("Alt", f.sink.text)
        runBlocking { f.controller.stop() }

        val stale = Hypothesis(
            IntArray(0), "STALE", IntArray(0),
            sessionId = 1, runId = 99, audioSamples = 32_000, incomplete = false,
        )
        assertFalse(f.controller.applyPartial(stale)) // IDLE → verworfen
        assertEquals("Alt", f.sink.text)

        f.controller.start() // Session 2 (neue sessionId)
        assertEquals(2, f.controller.sessionId)
        assertFalse(f.controller.applyPartial(stale)) // Session-ID passt nicht
        assertEquals("Alt", f.sink.text) // nichts geschrieben

        f.engine.nextText = "Neu"
        runBlocking { f.controller.feedAudio(seconds(2)) } // Session 2 schreibt normal
        assertEquals("AltNeu", f.sink.text)
    }

    @Test fun lateFinalFromForcedSessionIdIsDiscarded() {
        val f = Fixture()
        f.controller.start() // Session 1
        f.engine.nextText = "Eins"
        runBlocking { f.controller.feedAudio(seconds(2)) }
        assertEquals("Eins", f.sink.text)
        f.engine.forceSessionId = 42 // Engine liefert Resultat fremder Session
        f.engine.nextText = "FALSCH"
        runBlocking { f.controller.stop() }
        assertEquals("Eins", f.sink.text) // verworfen, Partialtext bleibt
        assertEquals(DictationState.IDLE, f.controller.state)
    }

    // --- Fehlerpfad → Idle, Text bleibt, Recovery möglich ---

    @Test fun partialEngineErrorGoesToIdleAndRecovers() {
        val f = Fixture()
        f.controller.start()
        f.engine.failNext = true
        runBlocking { f.controller.feedAudio(seconds(2)) }
        assertEquals(DictationState.IDLE, f.controller.state)
        assertEquals("asr_partial_error", f.controller.lastErrorCode)
        assertTrue(f.sink.calls.isEmpty())

        assertTrue(f.controller.start()) // Sitzung kann neu starten
        f.engine.nextText = "Weiter"
        runBlocking { f.controller.feedAudio(seconds(2)) }
        assertEquals("Weiter", f.sink.text)
    }

    @Test fun finalEngineErrorKeepsPartialText() {
        val f = Fixture()
        f.controller.start()
        f.engine.nextText = "Teil"
        runBlocking { f.controller.feedAudio(seconds(2)) }
        assertEquals("Teil", f.sink.text)
        f.engine.failNext = true
        runBlocking { f.controller.stop() }
        assertEquals(DictationState.IDLE, f.controller.state)
        assertEquals("asr_final_error", f.controller.lastErrorCode)
        assertEquals("Teil", f.sink.text) // bereits Geschriebenes bleibt stehen
    }

    // --- Fremdeingabe im Editor stoppt Schreibzugriffe ---

    @Test fun foreignSelectionBlocksFurtherWrites() {
        val f = Fixture()
        f.controller.start()
        f.controller.onUpdateSelection(0, 0, 10, 10) // Baseline (Nutzer-Cursor)
        f.engine.nextText = "Hallo"
        runBlocking { f.controller.feedAudio(seconds(2)) }
        assertEquals("Hallo", f.sink.text)
        f.controller.onUpdateSelection(10, 10, 15, 15) // Callback unseres Edits
        f.controller.onUpdateSelection(15, 15, 9, 9)   // Nutzer greift ein
        f.engine.nextText = "Hallo Welt"
        runBlocking { f.controller.feedAudio(seconds(1)) } // 3,0 s → Partial-Versuch
        assertEquals("Hallo", f.sink.text) // kein Schreiben mehr
        assertEquals(DictationState.RECORDING, f.controller.state) // Aufnahme läuft weiter
    }

    @Test fun abortGoesToIdleWithoutFinalDecode() {
        val f = Fixture()
        f.controller.start()
        f.engine.nextText = "Start"
        runBlocking { f.controller.feedAudio(seconds(2)) }
        assertEquals("Start", f.sink.text)
        f.engine.nextText = "SOLLTE NICHT KOMMEN"
        f.controller.abort()
        assertEquals(DictationState.IDLE, f.controller.state)
        assertEquals(1, f.engine.calls) // kein Final-Decode beim Abbruch
        assertEquals("Start", f.sink.text)
    }

    // --- A-1: Editorwechsel während RECORDING bricht kontrolliert ab ---

    @Test fun editorChangeDuringRecordingAbortsToIdle() {
        val f = Fixture()
        f.controller.start()
        f.engine.nextText = "Start"
        runBlocking { f.controller.feedAudio(seconds(2)) }
        assertEquals("Start", f.sink.text)
        f.engine.nextText = "SOLLTE NICHT KOMMEN"
        f.controller.onEditorChanged() // Editor/App-Wechsel
        assertEquals(DictationState.IDLE, f.controller.state)
        // kein Final-Decode, kein Schreiben:
        runBlocking { f.controller.feedAudio(seconds(2)) } // Audio nach Wechsel: No-Op
        assertEquals(1, f.engine.calls)
        assertEquals("Start", f.sink.text) // neuer Editor bleibt leer von uns
        assertEquals("aborted", f.controller.lastErrorCode)
    }

    @Test fun editorChangeDuringFinalizingEndsIdle() {
        val f = Fixture()
        f.controller.start()
        f.engine.nextText = "Text"
        runBlocking { f.controller.feedAudio(seconds(2)) }
        // FINALIZING wird synchron durchlaufen — wir testen den Pfad über abort-Semantik:
        f.controller.onEditorChanged()
        assertEquals(DictationState.IDLE, f.controller.state)
    }

    @Test fun editorChangeWhenIdleIsNoOp() {
        val f = Fixture()
        f.controller.onEditorChanged()
        assertEquals(DictationState.IDLE, f.controller.state)
        assertNull(f.controller.lastErrorCode)
        assertTrue(f.controller.start()) // danach normal startbar
    }
}
