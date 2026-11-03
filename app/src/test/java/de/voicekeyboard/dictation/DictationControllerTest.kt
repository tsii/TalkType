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

    // F26b (C2-Export): Bufferstand der letzten akzeptierten Hypothese + Reset bei start()
    @Test fun `letzte Hypothese merkt den Bufferstand und start nullt ihn`() {
        val f = Fixture()
        f.controller.start()
        assertEquals("vor der ersten Hypothese 0", 0f, f.controller.lastHypothesisBufferSeconds, 1e-4f)
        f.engine.nextText = "Eins"
        runBlocking { f.controller.feedAudio(seconds(2)) } // 2.0 s Puffer -> Partial
        assertEquals("2.0 s Puffer beim Decode", 2.0f, f.controller.lastHypothesisBufferSeconds, 1e-4f)
        runBlocking { f.controller.stop() }
        assertTrue(f.controller.start()) // neue Sitzung: Feld zurueck auf 0
        assertEquals("nach start() 0", 0f, f.controller.lastHypothesisBufferSeconds, 1e-4f)
        runBlocking { f.controller.stop() }
    }

    @Test fun finalEngineErrorKeepsPartialText() {
        val f = Fixture()
        f.controller.start()
        f.engine.nextText = "Teil"
        runBlocking { f.controller.feedAudio(seconds(2)) }
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
        f.controller.onUpdateSelection(15, 15, 9, 9)   // Nutzer greift ein (Cursor)
        f.engine.nextText = "Hallo Welt"
        runBlocking { f.controller.feedAudio(seconds(1)) } // 3,0 s → Partial-Versuch
        // A2b (PLAN_V0133 §A2b): reiner Cursor-Griff lässt den Editor-INHALT
        // unangetastet — der actualBeforeCursor-Suffixbeweis re-baselined und
        // die Hypothese wird geschrieben (Abnahme: „Session endet nicht
        // durch W1/W3d selbst"; Fremdtext byteweise unangetastet).
        assertEquals("Hallo Welt", f.sink.text)
        assertEquals(DictationState.RECORDING, f.controller.state)

        // FREMDER/ABWEICHENDER Inhalt bleibt W1: kein Sink-Zugriff, lost bleibt.
        f.sink.text = "fremd" // Nutzer ersetzt alles
        f.controller.onUpdateSelection(0, 0, 2, 5) // Range ⇒ lost=true
        f.engine.nextText = "Hallo Welt mehr"
        runBlocking { f.controller.feedAudio(seconds(1)) } // 4,0 s → Partial-Versuch
        assertEquals("kein Schreiben bei fremdem Inhalt", "fremd", f.sink.text)
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

    // --- F17b/C2: Decode-Zeit-Diagnose (decodeMsMax/P95/bufferSeconds) ---

    /** Fake-Engine, die die injizierte Uhr pro transcribe um vorgegebene ms weiterstellt. */
    private class TimedFakeEngine(
        private val clockMs: MutableLong,
        private val decodeMs: List<Long>,
    ) : AsrEngine {
        var calls = 0
        override fun load(modelDir: File) {}
        override suspend fun transcribe(
            window: PcmWindow, sessionId: Long, runId: Long,
        ): Hypothesis {
            val t = decodeMs[minOf(calls, decodeMs.lastIndex)]
            calls++
            clockMs.value += t // simuliert Decode-Arbeit auf der injizierten Uhr
            return Hypothesis(
                tokenIds = IntArray(0), text = "t", frameTimesMs = IntArray(0),
                sessionId = sessionId, runId = runId,
                audioSamples = window.samples.size, incomplete = false,
            )
        }
        override fun close() {}
    }

    private class MutableLong(var value: Long = 0L)

    private fun timedFixture(decodeMs: List<Long>): Pair<DictationController, TimedFakeEngine> {
        val now = MutableLong(0L)
        val engine = TimedFakeEngine(now, decodeMs)
        return DictationController(engine, RecordingSink(), clock = { now.value * 1_000_000 }) to engine
    }

    @Test fun decodeStatsEmptyWhenNeverDecoded() {
        val (controller, engine) = timedFixture(listOf(100L, 200L, 900L))
        assertEquals(0L, controller.decodeMsMax())
        assertEquals(0L, controller.decodeMsP95())
        assertEquals(0.0, controller.bufferSeconds(), 0.0)
        assertEquals(0, engine.calls)
    }

    @Test fun decodeStatsSingleRunMaxEqualsP95() {
        val (controller, _) = timedFixture(listOf(100L))
        controller.start()
        runBlocking { controller.feedAudio(seconds(2)) } // 2,0 s ⇒ ein Partial-Decode
        assertEquals(100L, controller.decodeMsMax())
        assertEquals(controller.decodeMsMax(), controller.decodeMsP95()) // ein Lauf: max == p95
        assertEquals(2.0, controller.bufferSeconds(), 0.0)
    }

    @Test fun decodeStatsMaxP95AndBufferSecondsGrow() {
        val (controller, _) = timedFixture(listOf(100L, 200L, 900L))
        controller.start()
        runBlocking {
            controller.feedAudio(seconds(2)) // Decode 1: 100 ms bei 2,0 s Puffer
            assertEquals(2.0, controller.bufferSeconds(), 0.0)
            controller.feedAudio(seconds(2)) // Decode 2: 200 ms bei 4,0 s Puffer
            assertEquals(4.0, controller.bufferSeconds(), 0.0)
            controller.feedAudio(seconds(2)) // Decode 3: 900 ms bei 6,0 s Puffer
        }
        assertEquals(3, controller.hypothesesThisSession)
        assertEquals(900L, controller.decodeMsMax())
        assertTrue("p95 > 0: ${controller.decodeMsP95()}", controller.decodeMsP95() > 0L)
        assertEquals(6.0, controller.bufferSeconds(), 0.0) // steigt mit gefuettertem Audio
    }

    // --- F21: cost-aware Decode-Takt (nur WANN, nie WAS — Finale bleibt heilig) ---

    @Test fun pacingSkipsSlowEnginePartialsButFinalAlwaysRuns() {
        val now = MutableLong(0L)
        val engine = TimedFakeEngine(now, listOf(4000L)) // jeder Lauf "braucht" 4000 ms
        val sink = RecordingSink()
        val controller = DictationController(
            engine, sink, clock = { now.value * 1_000_000 }, decodePacing = true,
        )
        controller.start()
        runBlocking {
            controller.feedAudio(seconds(2)) // Decode 1: Intervall base 2000, 2,0 s erreicht
            assertEquals(1, engine.calls)
            assertEquals(6000L, controller.decodePaceMs()) // 2x4000=8000 -> auf max 6000 kappt
        }
        // Nach 4000 ms Laufziel = 6000 ms (Kappung) — erst bei 8,0 s Puffer wieder Partial.
        runBlocking {
            repeat(5) { controller.feedAudio(seconds(1)) } // 3,0..7,0 s: alles zu frueh
            assertEquals("Aufrufzahl steigt NICHT pro Block", 1, engine.calls)
            assertEquals(5, controller.decodeSkips())
            controller.feedAudio(seconds(1)) // 8,0 s: 8,0-2,0 = 6,0 s >= 6,0 s -> Decode 2
            assertEquals(2, engine.calls)
            assertEquals(6000L, controller.decodePaceMs()) // 2x4000=8000 -> auf max 6000 kappt
            // FINALE unangetastet: stop() dekodiert immer, Endtext vollständig
            controller.stop()
        }
        assertEquals(3, engine.calls) // Finale war der 3. transcribe
        assertEquals("t", sink.text)
        assertEquals(DictationState.IDLE, controller.state)
    }

    @Test fun pacingOffDefaultStillDecodesEveryBlock() {
        val (engine, controller) = pacingOffFixture() // Default: pacing AUS
        controller.start()
        runBlocking {
            controller.feedAudio(seconds(2)) // alle Blocks >= 2,0 s -> jeder dekodiert
            controller.feedAudio(seconds(1))
            controller.feedAudio(seconds(1))
            controller.feedAudio(seconds(1))
            controller.feedAudio(seconds(1))
        }
        assertEquals(5, engine.calls) // ein transcribe je Block (Verhalten wie bisher)
        assertEquals(0, controller.decodeSkips())
        assertEquals(0L, controller.decodePaceMs()) // Pacing aus -> Intervall 0
    }

    /** Default-Controller (pacing AUS) mit vorgeberuhr-Engine. */
    private fun pacingOffFixture(): Pair<TimedFakeEngine, DictationController> {
        val now = MutableLong(0L)
        val engine = TimedFakeEngine(now, listOf(4000L))
        return engine to DictationController(engine, RecordingSink(), clock = { now.value * 1_000_000 })
    }

    // --- AP3-P2 (Review): Schreibverweigerungs-Detektor zählt erst nach dem
    // ersten erfolgreichen Commit — vorher ist isOwned=false NORMAL (Baseline
    // noch nicht etabliert). Detektor 1 (Selection-Flip) unberührt. ---

    @Test fun `AP3-P2 Verweigerung VOR erstem Commit zaehlt nicht danach mit Sekundenwert`() {
        // (A) VOR dem ersten Commit: unerwarteter Selection-Callback (lost=true),
        // die erste Hypothese wird verweigert — NORMALFALL, KEIN Detektor-Treffer:
        val f = Fixture()
        f.controller.start()
        f.controller.onUpdateSelection(0, 0, 1, 2) // SELEKTIONSBEREICH (1≠2) → Tracker lost
        f.engine.nextText = "Hallo"
        runBlocking { f.controller.feedAudio(seconds(2)) } // Decode läuft, Schreiben verweigert
        assertEquals("kein Commit gelaufen", "", f.sink.text)
        assertEquals("Verweigerung vor Commit zählt NICHT", 0, f.controller.ownershipDeniedCount)
        assertEquals("owner_lost_at_s bleibt -1", -1.0, f.controller.ownershipDeniedAtS, 1e-9)

        // (B) NACH dem ersten Commit: erst committen (isOwned ok ⇒ hadOutput),
        // DANN echter FREMDINHALT — P2 (REVIEW_A2_A4): nur ECHTE Verweigerungen
        // zählen; eine bloße Selektion mit intaktem Suffix wird re-baselined und
        // geschrieben (kein Detektor-Treffer). Nächste Hypothese verweigert ⇒
        // Treffer mit Sekundenwert:
        val f2 = Fixture()
        f2.controller.start()
        f2.engine.nextText = "Hallo"
        runBlocking { f2.controller.feedAudio(seconds(2)) } // erster Commit ("Hallo")
        assertEquals("Commit ist gelaufen", "Hallo", f2.sink.text)
        f2.sink.text = "fremd ersetzt alles" // Suffix weg — Re-Baseline scheitert
        f2.controller.onUpdateSelection(0, 0, 1, 2) // SELEKTIONSBEREICH (1≠2) → lost
        f2.engine.nextText = "Hallo Welt"
        runBlocking { f2.controller.feedAudio(seconds(2)) } // 4,0 s — Verweigerung
        assertEquals("ECHTE Verweigerung nach Commit zählt", 1, f2.controller.ownershipDeniedCount)
        assertEquals("Sekundenwert (Session-Audio)", 4.0, f2.controller.ownershipDeniedAtS, 0.05)
        assertEquals("kein Schreiben in den fremden Editor", "fremd ersetzt alles", f2.sink.text)
    }
}
