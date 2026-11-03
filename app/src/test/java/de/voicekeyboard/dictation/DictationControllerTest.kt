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

    /** Fake sink: records deltas, applies text at the cursor, can fail. */
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

    /** Fake ASR: returns configurable text, can simulate errors and old sessions. */
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
                sessionId = forceSessionId ?: sessionId, // echo of the current session
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

    // --- idempotency (KONZEPT §2.1) ---

    @Test fun doubleStartIsNoOp() {
        val f = Fixture()
        assertTrue(f.controller.start())
        assertEquals(DictationState.RECORDING, f.controller.state)
        assertFalse(f.controller.start()) // double start(): no restart
        assertEquals(DictationState.RECORDING, f.controller.state)
        runBlocking { f.controller.stop() }
        assertEquals(DictationState.IDLE, f.controller.state)
        runBlocking { f.controller.stop() } // stop() without start: no-op
        assertEquals(DictationState.IDLE, f.controller.state)
        assertEquals(0, f.engine.calls)
        assertNull(f.controller.lastErrorCode)
    }

    // --- min_audio_s = 2.0 applies only to partials (ASR_CONTRACT §4.4) ---

    @Test fun partialsBelowMinAudioAreNotDecoded() {
        val f = Fixture()
        f.engine.nextText = "Hallo"
        f.controller.start()
        runBlocking { f.controller.feedAudio(seconds(1)) } // 1.0 s < 2.0 s
        assertEquals(0, f.engine.calls) // guard: no hallucination partial
        assertEquals("", f.sink.text)
        runBlocking { f.controller.feedAudio(seconds(1)) } // 2.0 s reached
        assertEquals(1, f.engine.calls)
        assertEquals("Hallo", f.sink.text)
    }

    @Test fun finalDecodeRunsEvenBelowMinAudio() {
        val f = Fixture()
        f.engine.nextText = "Kurz"
        f.controller.start()
        runBlocking { f.controller.feedAudio(seconds(1)) } // no partial (guard)
        assertEquals(0, f.engine.calls)
        runBlocking { f.controller.stop() } // release ⇒ always decode the final
        assertEquals(1, f.engine.calls)
        assertEquals("Kurz", f.sink.text)
        assertEquals(DictationState.IDLE, f.controller.state)
    }

    // --- finalization replaces the partial text completely ---

    @Test fun finalizationReplacesPartialTail() {
        val f = Fixture()
        f.controller.start()
        f.engine.nextText = "Das ist derzw"
        runBlocking { f.controller.feedAudio(seconds(2)) } // partial
        assertEquals("Das ist derzw", f.sink.text)
        f.engine.nextText = "Das ist der zweite" // "zw" must be replaced
        runBlocking { f.controller.stop() } // final: the whole difference
        assertEquals("Das ist der zweite", f.sink.text)
        assertEquals(DictationState.IDLE, f.controller.state)
        // minimal delta: only "zw" deleted, the rest appended
        assertEquals(listOf(0 to "Das ist derzw", 2 to " zweite"), f.sink.calls)
    }

    // --- late results of old sessions are discarded ---

    @Test fun lateResultOfOldSessionIsDiscarded() {
        val f = Fixture()
        f.controller.start() // session 1
        f.engine.nextText = "Alt"
        runBlocking { f.controller.feedAudio(seconds(2)) }
        assertEquals("Alt", f.sink.text)
        runBlocking { f.controller.stop() }

        val stale = Hypothesis(
            IntArray(0), "STALE", IntArray(0),
            sessionId = 1, runId = 99, audioSamples = 32_000, incomplete = false,
        )
        assertFalse(f.controller.applyPartial(stale)) // IDLE → discarded
        assertEquals("Alt", f.sink.text)

        f.controller.start() // session 2 (new sessionId)
        assertEquals(2, f.controller.sessionId)
        assertFalse(f.controller.applyPartial(stale)) // session ID doesn't match
        assertEquals("Alt", f.sink.text) // nothing written

        f.engine.nextText = "Neu"
        runBlocking { f.controller.feedAudio(seconds(2)) } // session 2 writes normally
        assertEquals("AltNeu", f.sink.text)
    }

    @Test fun lateFinalFromForcedSessionIdIsDiscarded() {
        val f = Fixture()
        f.controller.start() // session 1
        f.engine.nextText = "Eins"
        runBlocking { f.controller.feedAudio(seconds(2)) }
        assertEquals("Eins", f.sink.text)
        f.engine.forceSessionId = 42 // engine returns a result of a foreign session
        f.engine.nextText = "FALSCH"
        runBlocking { f.controller.stop() }
        assertEquals("Eins", f.sink.text) // discarded, the partial text stays
        assertEquals(DictationState.IDLE, f.controller.state)
    }

    // --- error path → idle, text stays, recovery possible ---

    @Test fun partialEngineErrorGoesToIdleAndRecovers() {
        val f = Fixture()
        f.controller.start()
        f.engine.failNext = true
        runBlocking { f.controller.feedAudio(seconds(2)) }
        assertEquals(DictationState.IDLE, f.controller.state)
        assertEquals("asr_partial_error", f.controller.lastErrorCode)
        assertTrue(f.sink.calls.isEmpty())

        assertTrue(f.controller.start()) // the session can restart
        f.engine.nextText = "Weiter"
        runBlocking { f.controller.feedAudio(seconds(2)) }
        assertEquals("Weiter", f.sink.text)
    }

    // F26b (C2 export): buffer level of the last accepted hypothesis + reset on start()
    @Test fun `last hypothesis remembers the buffer level and start resets it`() {
        val f = Fixture()
        f.controller.start()
        assertEquals("before the first hypothesis 0", 0f, f.controller.lastHypothesisBufferSeconds, 1e-4f)
        f.engine.nextText = "Eins"
        runBlocking { f.controller.feedAudio(seconds(2)) } // 2.0 s buffer -> partial
        assertEquals("2.0 s buffer at the decode", 2.0f, f.controller.lastHypothesisBufferSeconds, 1e-4f)
        runBlocking { f.controller.stop() }
        assertTrue(f.controller.start()) // new session: field back to 0
        assertEquals("after start() 0", 0f, f.controller.lastHypothesisBufferSeconds, 1e-4f)
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
        assertEquals("Teil", f.sink.text) // what was already written stays
    }

    // --- foreign input in the editor stops write access ---

    @Test fun foreignSelectionBlocksFurtherWrites() {
        val f = Fixture()
        f.controller.start()
        f.controller.onUpdateSelection(0, 0, 10, 10) // baseline (user cursor)
        f.engine.nextText = "Hallo"
        runBlocking { f.controller.feedAudio(seconds(2)) }
        assertEquals("Hallo", f.sink.text)
        f.controller.onUpdateSelection(10, 10, 15, 15) // callback of our edit
        f.controller.onUpdateSelection(15, 15, 9, 9)   // user intervenes (cursor)
        f.engine.nextText = "Hallo Welt"
        runBlocking { f.controller.feedAudio(seconds(1)) } // 3.0 s → partial attempt
        // A2b (PLAN_V0133 §A2b): a pure cursor grab leaves the editor CONTENT
        // untouched — the actualBeforeCursor suffix proof re-baselines and the
        // hypothesis is written (acceptance: "the session does not end via
        // W1/W3d itself"; foreign content untouched byte by byte).
        assertEquals("Hallo Welt", f.sink.text)
        assertEquals(DictationState.RECORDING, f.controller.state)

        // FOREIGN/DIVERGENT content stays W1: no sink access, lost stays.
        f.sink.text = "fremd" // user replaces everything
        f.controller.onUpdateSelection(0, 0, 2, 5) // Range ⇒ lost=true
        f.engine.nextText = "Hallo Welt mehr"
        runBlocking { f.controller.feedAudio(seconds(1)) } // 4.0 s → partial attempt
        assertEquals("no writing with foreign content", "fremd", f.sink.text)
        assertEquals(DictationState.RECORDING, f.controller.state) // recording keeps running
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
        assertEquals(1, f.engine.calls) // no final decode on abort
        assertEquals("Start", f.sink.text)
    }

    // --- A-1: an editor change during RECORDING aborts in a controlled way ---

    @Test fun editorChangeDuringRecordingAbortsToIdle() {
        val f = Fixture()
        f.controller.start()
        f.engine.nextText = "Start"
        runBlocking { f.controller.feedAudio(seconds(2)) }
        assertEquals("Start", f.sink.text)
        f.engine.nextText = "SOLLTE NICHT KOMMEN"
        f.controller.onEditorChanged() // editor/app change
        assertEquals(DictationState.IDLE, f.controller.state)
        // no final decode, no writing:
        runBlocking { f.controller.feedAudio(seconds(2)) } // audio after the change: no-op
        assertEquals(1, f.engine.calls)
        assertEquals("Start", f.sink.text) // the new editor stays empty from us
        assertEquals("aborted", f.controller.lastErrorCode)
    }

    @Test fun editorChangeDuringFinalizingEndsIdle() {
        val f = Fixture()
        f.controller.start()
        f.engine.nextText = "Text"
        runBlocking { f.controller.feedAudio(seconds(2)) }
        // FINALIZING is passed through synchronously — we test the path via abort semantics:
        f.controller.onEditorChanged()
        assertEquals(DictationState.IDLE, f.controller.state)
    }

    @Test fun editorChangeWhenIdleIsNoOp() {
        val f = Fixture()
        f.controller.onEditorChanged()
        assertEquals(DictationState.IDLE, f.controller.state)
        assertNull(f.controller.lastErrorCode)
        assertTrue(f.controller.start()) // startable normally afterwards
    }

    // --- F17b/C2: decode time diagnostics (decodeMsMax/P95/bufferSeconds) ---

    /** Fake engine that advances the injected clock by the given ms per transcribe. */
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
            clockMs.value += t // simulates decode work on the injected clock
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
        runBlocking { controller.feedAudio(seconds(2)) } // 2.0 s ⇒ one partial decode
        assertEquals(100L, controller.decodeMsMax())
        assertEquals(controller.decodeMsMax(), controller.decodeMsP95()) // one run: max == p95
        assertEquals(2.0, controller.bufferSeconds(), 0.0)
    }

    @Test fun decodeStatsMaxP95AndBufferSecondsGrow() {
        val (controller, _) = timedFixture(listOf(100L, 200L, 900L))
        controller.start()
        runBlocking {
            controller.feedAudio(seconds(2)) // decode 1: 100 ms at a 2.0 s buffer
            assertEquals(2.0, controller.bufferSeconds(), 0.0)
            controller.feedAudio(seconds(2)) // decode 2: 200 ms at a 4.0 s buffer
            assertEquals(4.0, controller.bufferSeconds(), 0.0)
            controller.feedAudio(seconds(2)) // decode 3: 900 ms at a 6.0 s buffer
        }
        assertEquals(3, controller.hypothesesThisSession)
        assertEquals(900L, controller.decodeMsMax())
        assertTrue("p95 > 0: ${controller.decodeMsP95()}", controller.decodeMsP95() > 0L)
        assertEquals(6.0, controller.bufferSeconds(), 0.0) // grows with the fed audio
    }

    // --- F21: cost-aware decode pace (only WHEN, never WHAT — the final stays sacred) ---

    @Test fun pacingSkipsSlowEnginePartialsButFinalAlwaysRuns() {
        val now = MutableLong(0L)
        val engine = TimedFakeEngine(now, listOf(4000L)) // every run "needs" 4000 ms
        val sink = RecordingSink()
        val controller = DictationController(
            engine, sink, clock = { now.value * 1_000_000 }, decodePacing = true,
        )
        controller.start()
        runBlocking {
            controller.feedAudio(seconds(2)) // decode 1: interval base 2000, 2.0 s reached
            assertEquals(1, engine.calls)
            assertEquals(6000L, controller.decodePaceMs()) // 2x4000=8000 -> clamped to max 6000
        }
        // after the 4000 ms run target = 6000 ms (clamp) — the next partial only at an 8.0 s buffer.
        runBlocking {
            repeat(5) { controller.feedAudio(seconds(1)) } // 3.0..7.0 s: all too early
            assertEquals("call count does NOT grow per block", 1, engine.calls)
            assertEquals(5, controller.decodeSkips())
            controller.feedAudio(seconds(1)) // 8.0 s: 8.0-2.0 = 6.0 s >= 6.0 s -> decode 2
            assertEquals(2, engine.calls)
            assertEquals(6000L, controller.decodePaceMs()) // 2x4000=8000 -> clamped to max 6000
            // FINAL untouched: stop() always decodes, final text complete
            controller.stop()
        }
        assertEquals(3, engine.calls) // the final was the 3rd transcribe
        assertEquals("t", sink.text)
        assertEquals(DictationState.IDLE, controller.state)
    }

    @Test fun pacingOffDefaultStillDecodesEveryBlock() {
        val (engine, controller) = pacingOffFixture() // default: pacing OFF
        controller.start()
        runBlocking {
            controller.feedAudio(seconds(2)) // all blocks >= 2.0 s -> each one decoded
            controller.feedAudio(seconds(1))
            controller.feedAudio(seconds(1))
            controller.feedAudio(seconds(1))
            controller.feedAudio(seconds(1))
        }
        assertEquals(5, engine.calls) // one transcribe per block (behavior as before)
        assertEquals(0, controller.decodeSkips())
        assertEquals(0L, controller.decodePaceMs()) // pacing off -> interval 0
    }

    /** Default controller (pacing OFF) with a preset-clock engine. */
    private fun pacingOffFixture(): Pair<TimedFakeEngine, DictationController> {
        val now = MutableLong(0L)
        val engine = TimedFakeEngine(now, listOf(4000L))
        return engine to DictationController(engine, RecordingSink(), clock = { now.value * 1_000_000 })
    }

    // --- AP3-P2 (review): the write-denial detector only counts after the
    // first successful commit — before that isOwned=false is NORMAL (the
    // baseline is not established yet). Detector 1 (selection flip) untouched. ---

    @Test fun `AP3-P2 denial BEFORE the first commit does not count - afterwards with a seconds value`() {
        // (A) BEFORE the first commit: unexpected selection callback (lost=true),
        // the first hypothesis is denied — NORMAL CASE, NO detector hit:
        val f = Fixture()
        f.controller.start()
        f.controller.onUpdateSelection(0, 0, 1, 2) // SELECTION RANGE (1≠2) → tracker lost
        f.engine.nextText = "Hallo"
        runBlocking { f.controller.feedAudio(seconds(2)) } // decode runs, write denied
        assertEquals("no commit ran", "", f.sink.text)
        assertEquals("denial before the commit does NOT count", 0, f.controller.ownershipDeniedCount)
        assertEquals("owner_lost_at_s stays -1", -1.0, f.controller.ownershipDeniedAtS, 1e-9)

        // (B) AFTER the first commit: commit first (isOwned ok ⇒ hadOutput),
        // THEN real FOREIGN CONTENT — P2 (REVIEW_A2_A4): only REAL denials count;
        // a mere selection with an intact suffix is re-baselined and written
        // (no detector hit). The next hypothesis denied ⇒ hit with a seconds value:
        val f2 = Fixture()
        f2.controller.start()
        f2.engine.nextText = "Hallo"
        runBlocking { f2.controller.feedAudio(seconds(2)) } // first commit ("Hallo")
        assertEquals("the commit ran", "Hallo", f2.sink.text)
        f2.sink.text = "fremd ersetzt alles" // suffix gone — re-baseline fails
        f2.controller.onUpdateSelection(0, 0, 1, 2) // SELECTION RANGE (1≠2) → lost
        f2.engine.nextText = "Hallo Welt"
        runBlocking { f2.controller.feedAudio(seconds(2)) } // 4.0 s — denial
        assertEquals("REAL denial after the commit counts", 1, f2.controller.ownershipDeniedCount)
        assertEquals("seconds value (session audio)", 4.0, f2.controller.ownershipDeniedAtS, 0.05)
        assertEquals("no writing into the foreign editor", "fremd ersetzt alles", f2.sink.text)
    }
}
