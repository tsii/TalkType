package de.voicekeyboard.dictation

import de.voicekeyboard.asr.AsrEngine
import de.voicekeyboard.asr.Hypothesis
import de.voicekeyboard.audio.AudioSource
import de.voicekeyboard.audio.PcmBlock
import de.voicekeyboard.audio.PcmWindow
import de.voicekeyboard.editor.EditorSink
import de.voicekeyboard.ime.DictationCoordinator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.atomic.AtomicLong

/**
 * F35 "route D" (docs/KORPUS_DIKTAT_TESTPLAN.md §1 D / §3): 30/60 s paced run
 * with the corpus fixture across the coordinator seam (AudioSource → consumer
 * → feedAudio, 320-sample blocks, pattern FakeWavAudioSource/StallTestSource)
 * and a stub engine (no ONNX) with a constant 150 ms latency on an injectable
 * virtual clock — NO Thread.sleep inside the pace. Seven assertions (a)–(g)
 * per run; rule 9: counters/durations only, never audio/text content (not in
 * messages either).
 *
 * Time model: the virtual clock drives block due dates and watchdogs (nowMs);
 * the controller's hypothesis/decode clock is real nanoTime (not injectable
 * via the coordinator) — decode_ms_* therefore measures real time, the
 * 150 ms stub latency is invisible there (actual finding, reported).
 */
class KorpusTaktTest {

    /** Virtual clock (ms); start ≠ 0 (0 = the watchdog's "never started" sentinel). */
    private class VClock {
        val now = AtomicLong(1_000_000L)
    }

    /**
     * Time-accurate fixture source (FakeWavAudioSource pattern): block i is due
     * at session start + i×20 ms of VIRTUAL time; if the clock has already
     * advanced further due to decode latency, due blocks are caught up
     * immediately (capture runs parallel to inference — backlog model). After
     * the fixture ends read returns 0 WITHOUT clock progress (no artificial
     * ERR_STALL).
     */
    private class KorpusSource(
        private val samples: ShortArray,
        private val clock: VClock,
    ) : AudioSource {
        private val blockMs = KorpusFixture.BLOCK_SAMPLES * 1000L / KorpusFixture.SAMPLE_RATE
        private var nextIdx = 0L
        private var baseMs = 0L
        @Volatile private var released = false

        override fun start(sessionId: Long): Boolean {
            baseMs = clock.now.get()
            return true
        }

        override fun read(dst: ShortArray, timeoutMs: Int): Int {
            if (released) return -1
            val from = (nextIdx * KorpusFixture.BLOCK_SAMPLES).toInt()
            if (from >= samples.size) {
                Thread.sleep(1) // fixture finished: no block, virtual clock stands still
                return 0
            }
            val due = baseMs + nextIdx * blockMs
            while (true) { // capture pace: bring the clock to the due date (CAS-safe)
                val cur = clock.now.get()
                if (cur >= due || clock.now.compareAndSet(cur, due)) break
            }
            val n = minOf(dst.size, KorpusFixture.BLOCK_SAMPLES, samples.size - from)
            System.arraycopy(samples, from, dst, 0, n)
            nextIdx++
            return n
        }

        override fun stopAndDrain(): List<PcmBlock> = emptyList()
        override fun release() {
            released = true
        }
    }

    /** Stub engine (no ONNX): a constant [latencyMs] per transcribe on its OWN
     *  injectable clock + a monotonically growing call counter (counters only,
     *  no content).
     *
     *  F36 repair (test model only): the latency must NOT advance the
     *  coordinator's watchdog clock — in production inference runs PARALLEL to
     *  real-time capture (audio keeps arriving during the decode, the stall
     *  watchdog sees no gap). If the stub wrote the latency onto the shared
     *  clock it would model "audio stutters during the decode" — the ERR_STALL
     *  watchdog would then rightly flag it (actual finding in the 60 s case:
     *  capture pumped the fixture empty ahead of the consumer, 30 decodes x
     *  150 ms = 4,500 ms of virtual silence > STALL_LIMIT_MS). The 150 ms are
     *  therefore deterministically checkable on [latencyClock]: progress ==
     *  latencyMs x calls. */
    private class KorpusStubEngine(
        private val latencyClock: VClock,
        private val latencyMs: Long = 150L,
        /** B1 (600 s run): latency α·T per window (I3: 38.5 ms per audio second)
         *  instead of a constant 150 ms — models the COST-progressive
         *  whole_buffer decode per SEGMENT (buffer ≤ ~37 s). 0 = constant. */
        private val alphaPerAudioSecond: Double = 0.0,
    ) : AsrEngine {
        var calls = 0
            private set

        override fun load(modelDir: File) {}

        override suspend fun transcribe(
            window: PcmWindow, sessionId: Long, runId: Long,
        ): Hypothesis {
            val latency =
                if (alphaPerAudioSecond > 0.0) {
                    (alphaPerAudioSecond * window.samples.size / 16_000.0).toLong()
                } else {
                    latencyMs
                }
            latencyClock.now.addAndGet(latency)
            calls++
            return Hypothesis(
                IntArray(0), "k", IntArray(0), sessionId, runId,
                window.samples.size, incomplete = false,
            )
        }

        override fun close() {}
    }

    /** Minimal sink: counts only; NEVER logs text (rule 9). */
    private class RecordingSink : EditorSink {
        val text = StringBuilder()
        override fun replaceBeforeCursor(replaceLength: Int, newText: String) {
            text.delete(text.length - replaceLength, text.length)
            text.append(newText)
        }
        override fun textBeforeCursor(maxChars: Int): CharSequence = text.takeLast(maxOf(0, maxChars))
        override fun ownLength(): Int = text.length
    }

    @Test fun korpusTakt30s() = korpusLauf(30)

    @Test fun korpusTakt60s() = korpusLauf(60)

    // ---- B1 (PLAN_V0130 §6.3 (c)): 600 s corpus with segmenter (35 s cap) ----

    /** B1: 600 s of dictation with intermediate segment finalization — the
     *  virtual clock runs as in the 30/60 s run; the stub latency is α·T PER
     *  SEGMENT window (I3 curve: α = 38.5 ms/s, window ≤ ~37 s ⇒ latency ≤
     *  ~1.4 s).
     *  Assertions (virtual): no abort, hyp_gap ≤ 2000 ms, segment count
     *  ≈ ceil(600/35) ± tolerance, decode window per segment bounded. */
    @Test fun korpusTakt600sSegmentiert() {
        val totalSeconds = 600
        val totalSamples = totalSeconds * KorpusFixture.SAMPLE_RATE
        val fixture = KorpusFixture.build(totalSamples)
        assertEquals("fixture exactly the target length", totalSamples, fixture.size)
        val clock = VClock()
        val latencyClock = VClock()
        val engine = KorpusStubEngine(latencyClock, alphaPerAudioSecond = 38.5)
        val scope = CoroutineScope(Dispatchers.Default)
        val coordinator = DictationCoordinator(
            scope, { KorpusSource(fixture, clock) }, { engine }, RecordingSink(),
            nowMs = { clock.now.get() },
            autoStopSuppressed = { true }, // hold contract: no F43 auto-stop
            segmentFinalizeMs = 35_000L, // B1: hard 35 s segment cap
        )
        try {
            assertTrue(coordinator.start())
            var lastHyp = -1
            var gapMaxMs = -1L
            var maxBufferS = 0.0
            val deadline = System.currentTimeMillis() + 180_000
            while (System.currentTimeMillis() < deadline) {
                Thread.sleep(5)
                val hyp = coordinator.hypCount
                assertTrue("(a) hyp_count monotonic", hyp >= lastHyp)
                lastHyp = hyp
                if (hyp > 0) gapMaxMs = maxOf(gapMaxMs, coordinator.hypGapMs())
                // B1 fix (P2a): buffer_s is SESSION-based — the segment-local
                // decode window shows up in hyp_last_buffer_s.
                maxBufferS = maxOf(maxBufferS, coordinator.lastHypothesisBufferSeconds().toDouble())
                assertEquals("(b) no abort during the run", "-", coordinator.lastAbortReason)
                assertNull("(c) no ERR_STALL/ERR_SILENT", coordinator.lastError)
                assertTrue(
                    "(d) decode window per segment <= 40 s (actual=${coordinator.lastHypothesisBufferSeconds()})",
                    coordinator.lastHypothesisBufferSeconds() <= 40.0,
                )
                if (coordinator.audioMs >= totalSeconds * 1000L - 1 || !coordinator.isActive) break
            }
            // session measure: audioMs (fed session ms) — coordinator.audioSeconds
            // is segment-LOCAL since B1 (controller buffer, reset per mini final).
            assertEquals(
                "fixture fully fed (session audio_ms)", (totalSeconds * 1000L).toDouble(),
                coordinator.audioMs.toDouble(), 100.0,
            )
            coordinator.stop()
            val endDeadline = System.currentTimeMillis() + 30_000
            while (coordinator.lastAbortReason == "-" && System.currentTimeMillis() < endDeadline) {
                Thread.sleep(5)
            }
            assertTrue(
                "(e) hypothesis latency contract: hyp_gap_ms <= 2000 virtual (actual=$gapMaxMs)",
                gapMaxMs in 0L..2000L,
            )
            val segments = coordinator.segmentFinalizations()
            assertTrue(
                "(f) segment count ≈ ceil(600/35)=18 ± tolerance (actual=$segments)",
                segments in 15..19,
            )
            assertTrue(
                "(g) decode window per segment <= 40 s over the run (actual=$maxBufferS)",
                maxBufferS <= 40.0,
            )
            assertTrue("(h) no abort at the end", coordinator.lastAbortReason == "final")
            assertNull("(i) no ERR", coordinator.lastError)
            assertTrue(
                "(j) session counter monotonic: hyp_count > 0", coordinator.hypCount > 0,
            )
            System.out.println(
                "KORPUSIST case=600s-segmentiert segments=$segments" +
                    " audio_s=${"%.1f".format(coordinator.audioMs / 1000.0)}" +
                    " seg_window_s_max=$maxBufferS gap_max_ms=$gapMaxMs" +
                    " hyp_count=${coordinator.hypCount} engine_calls=${engine.calls}" +
                    " cap_blocks=${coordinator.capBlocks()}",
            )
        } finally {
            (scope.coroutineContext[Job])?.cancel()
        }
    }

    /** One run: feed the fixture time-accurately, 7 assertions (a)–(g), print the actual line. */
    private fun korpusLauf(totalSeconds: Int) {
        val totalSamples = totalSeconds * KorpusFixture.SAMPLE_RATE
        val fixture = KorpusFixture.build(totalSamples)
        assertEquals("fixture exactly the target length", totalSamples, fixture.size)
        val clock = VClock()
        val latencyClock = VClock()
        val engine = KorpusStubEngine(latencyClock)
        val scope = CoroutineScope(Dispatchers.Default)
        val coordinator = DictationCoordinator(
            scope, { KorpusSource(fixture, clock) }, { engine }, RecordingSink(),
            nowMs = { clock.now.get() },
        )
        try {
            assertTrue(coordinator.start())
            var lastHyp = -1
            var gapMaxMs = -1L
            val deadline = System.currentTimeMillis() + 60_000
            while (System.currentTimeMillis() < deadline) {
                Thread.sleep(2)
                val hyp = coordinator.hypCount
                assertTrue("(a) hyp_count monotonic: $hyp < $lastHyp", hyp >= lastHyp)
                lastHyp = hyp
                if (hyp > 0) gapMaxMs = maxOf(gapMaxMs, coordinator.hypGapMs())
                assertEquals("(f) abort_reason open during the run", "-", coordinator.lastAbortReason)
                assertNull("(g) no ERR_STALL/ERR_SILENT during the run", coordinator.lastError)
                if (coordinator.audioSeconds >= totalSeconds - 0.001 || !coordinator.isActive) break
            }
            assertEquals(
                "fixture fully fed (audio_s)", totalSeconds.toDouble(),
                coordinator.audioSeconds, 0.001,
            )
            coordinator.stop()
            val endDeadline = System.currentTimeMillis() + 30_000
            while (coordinator.lastAbortReason == "-" && System.currentTimeMillis() < endDeadline) {
                Thread.sleep(5)
            }
            // ---- seven assertions (end state; during the run: see the loop) ----
            assertTrue("(a) hyp_count > 0: ${coordinator.hypCount}", coordinator.hypCount > 0)
            assertTrue("(a) hyp_count monotonic at the end", coordinator.hypCount >= lastHyp)
            assertTrue("(b) hyp_gap_ms < 4000 in the run: actual=$gapMaxMs", gapMaxMs in 0L..3999L)
            assertEquals(
                "(c) buffer_s == audio_s ± 0,5", coordinator.audioSeconds,
                coordinator.bufferSeconds(), 0.5,
            )
            assertTrue("(d) decode_ms_max < 1000: actual=${coordinator.decodeMsMax()}", coordinator.decodeMsMax() < 1000)
            assertTrue("(e) cap_blocks > 0: actual=${coordinator.capBlocks()}", coordinator.capBlocks() > 0)
            assertTrue("(e) loud_s > 0: actual=${coordinator.loudS()}", coordinator.loudS() > 0.0)
            assertEquals("(f) abort_reason set only at the end", "final", coordinator.lastAbortReason)
            assertNull("(g) no ERR_STALL/ERR_SILENT", coordinator.lastError)
            // stub contract: constant 150 ms latency per decode on its own clock
            // (deterministic, numbers only) — minus the 1,000,000 start value.
            assertEquals(
                "stub latency = 150 ms x calls",
                150L * engine.calls,
                latencyClock.now.get() - 1_000_000L,
            )
            // actual line (numbers only) — lands in the test XML, NOT in product logs.
            System.out.println(
                "KORPUSIST case=${totalSeconds}s parts=" +
                    KorpusFixture.partSeconds().joinToString("/") { "%.4f".format(it) } +
                    " audio_s=${"%.1f".format(coordinator.audioSeconds)}" +
                    " buffer_s=${"%.1f".format(coordinator.bufferSeconds())}" +
                    " hyp_count=${coordinator.hypCount} gap_max_ms=$gapMaxMs" +
                    " decode_ms_max=${coordinator.decodeMsMax()}" +
                    " cap_blocks=${coordinator.capBlocks()}" +
                    " loud_s=${"%.1f".format(coordinator.loudS())} engine_calls=${engine.calls}",
            )
        } finally {
            (scope.coroutineContext[Job])?.cancel()
        }
    }
}
