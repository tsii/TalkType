package de.voicekeyboard.dictation

import de.voicekeyboard.asr.AsrEngine
import de.voicekeyboard.asr.Hypothesis
import de.voicekeyboard.audio.AudioSource
import de.voicekeyboard.audio.PcmBlock
import de.voicekeyboard.audio.PcmWindow
import de.voicekeyboard.editor.EditorSink
import de.voicekeyboard.ime.DictationCoordinator
import de.voicekeyboard.ime.segmentBoundary
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.atomic.AtomicLong

/**
 * B1 (PLAN_V0130 §6.3 recommendation (c)): segmenter core — intermediate
 * segment finalization, §5.1-COMPLIANT (whole_buffer only segmented IN TIME).
 * Pattern [de.voicekeyboard.ime.DictationAutoStopTest]: virtual clock,
 * self-paced 320-sample blocks of 20 ms each, NO Thread.sleep in the rhythm.
 *
 * Corpus (rule-a case): "speech(3 s) — pause 1.0 s — speech(3 s) — pause"
 * with punctuation hypotheses and segmentFinalizeMs = 35,000:
 *  - (1) boundary at silence+punctuation (≥ 1.2 s pause), NOT at the
 *    1.0 s pause; each pause only ONCE; the session lives until UP; the UP
 *    final decodes ONLY the REST buffer (most important contract test).
 *  - (2) continuous case: 40 s of uninterrupted speech → forced segment
 *    (length rule b) at ~35 s; decode window per segment stays ≤ 38 s.
 *  - (3) without punctuation the silence rule (a) stays mute — no boundary.
 *  - (4) pure function [segmentBoundary]: truth table of both rules.
 *  - (5) B1 fix (review P1): a transient error in the segment final does NOT
 *    kill the session — retry boundary + correct UP rest final.
 *  - (6) B1 fix (review P2a): export lengths (audio_s/buffer_s) keep SESSION
 *    semantics after segments; hyp_last_buffer_s stays segment-local.
 *
 * Rule 9: numbers/codes only; fixture texts are test constants, never user data.
 */
class SegmentFinalizeTest {

    private class VClock {
        val now = AtomicLong(1_000_000L)
    }

    /** Script source (silence = zero blocks, streaming like AudioRecord).
     *  [pacingNanos]: optional wall-clock throttle per block (F56 repro,
     *  SegmentGoldenTest pattern: without pacing the µs-fast capture overtakes
     *  the consumer — lastHypPunct would not yet be set from a COMPLETED decode
     *  at the (a) check, the same race family as B1/B3). */
    private class ScriptSource(
        private val clock: VClock,
        private val script: List<Pair<Long, Boolean>>,
        private val pacingNanos: Long = 0L,
    ) : AudioSource {
        private val blockMs = 20L
        private val blockSamples = 320
        private val totalMs = script.sumOf { it.first }
        private var base = -1L
        private var nextIdx = 0L
        @Volatile private var released = false

        private fun loudAt(ms: Long): Boolean {
            var t = 0L
            for ((d, loud) in script) {
                t += d
                if (ms < t) return loud
            }
            return false
        }

        override fun start(sessionId: Long): Boolean {
            base = clock.now.get()
            return true
        }

        override fun read(dst: ShortArray, timeoutMs: Int): Int {
            if (released) return -1
            val t = nextIdx * blockMs
            if (t >= totalMs) {
                Thread.sleep(1) // script end: no block, virtual clock stands still
                return 0
            }
            val due = base + t
            while (true) {
                val cur = clock.now.get()
                if (cur >= due || clock.now.compareAndSet(cur, due)) break
            }
            val amp: Short = if (loudAt(t)) 12_000 else 0
            for (j in 0 until blockSamples) dst[j] = amp
            nextIdx++
            if (pacingNanos > 0) java.util.concurrent.locks.LockSupport.parkNanos(pacingNanos)
            return blockSamples
        }

        override fun stopAndDrain(): List<PcmBlock> = emptyList()
        override fun release() {
            released = true
        }
    }

    /** Stub with CONFIGURABLE text (punctuation yes/no); window per call.
     *  B1 fix (P1, test 5): [failFinalizeAttempts] SEGMENT finalize attempts
     *  throw a transient IllegalStateException — a finalize repeats the window
     *  size of the immediately preceding partial decode (partials grow strictly
     *  monotonically within a segment, a finalize repeats the size). */
    private class TextStubEngine(
        private val text: String,
        private val failFinalizeAttempts: Int = 0,
    ) : AsrEngine {
        var calls = 0
            private set
        var failedFinalizes = 0
            private set
        val windowSeconds = ArrayList<Double>()
        private var lastSize = -1
        private var failRemaining = failFinalizeAttempts

        override fun load(modelDir: File) {}

        override suspend fun transcribe(
            window: PcmWindow, sessionId: Long, runId: Long,
        ): Hypothesis {
            val size = window.samples.size
            val isFinalize = size == lastSize
            lastSize = size
            if (isFinalize && failRemaining > 0) {
                failRemaining--
                failedFinalizes++
                throw IllegalStateException("transient segment decode (B1-Fix Test)")
            }
            calls++
            windowSeconds.add(size / 16_000.0)
            return Hypothesis(
                IntArray(0), text, IntArray(0), sessionId, runId,
                window.samples.size, incomplete = false,
            )
        }

        override fun close() {}
    }

    /** Real write sink (ownership proof via write counter, content never logged). */
    private class RecordingSink : EditorSink {
        var writes = 0
            private set
        val text = StringBuilder()
        override fun replaceBeforeCursor(replaceLength: Int, newText: String) {
            text.delete(text.length - replaceLength, text.length)
            text.append(newText)
            writes++
        }
        override fun textBeforeCursor(maxChars: Int): CharSequence = text.takeLast(maxOf(0, maxChars))
        override fun ownLength(): Int = text.length
    }

    private class Ctx(
        val coordinator: DictationCoordinator,
        val engine: TextStubEngine,
        val sink: RecordingSink,
        val scope: CoroutineScope,
    )

    private fun runScript(
        script: List<Pair<Long, Boolean>>,
        engineText: String,
        segmentFinalizeMs: Long,
        stopWhen: (ctx: Ctx) -> Boolean,
        waitMs: Long = 20_000,
        failFinalizes: Int = 0,
    ): Ctx {
        val clock = VClock()
        val src = ScriptSource(clock, script)
        val engine = TextStubEngine(engineText, failFinalizes)
        val sink = RecordingSink()
        val scope = CoroutineScope(Dispatchers.Default)
        val coordinator = DictationCoordinator(
            scope, { src }, { engine }, sink, nowMs = { clock.now.get() },
            autoStopSuppressed = { true }, // hold mode: F43 off — the session lives until UP
            segmentFinalizeMs = segmentFinalizeMs,
        )
        assertTrue(coordinator.start())
        val ctx = Ctx(coordinator, engine, sink, scope)
        val deadline = System.currentTimeMillis() + waitMs
        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(5)
            if (stopWhen(ctx)) break
            if (!coordinator.isActive) break
        }
        return ctx
    }

    private fun awaitReason(c: DictationCoordinator, expected: String, timeoutMs: Long = 15_000) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (c.lastAbortReason != expected && System.currentTimeMillis() < deadline) {
            Thread.sleep(5)
        }
        assertEquals("abort_reason", expected, c.lastAbortReason)
    }

    // ---- (1) silence+punctuation boundary; the 1.0 s pause does NOT cut ----

    @Test
    fun `(1) sentence pause with punctuation cuts - 1,0 s pause does not - UP finalizes only the rest buffer`() {
        // speech(3 s) — pause 1.0 s — speech(3 s) — pause forever.
        val ctx = runScript(
            script = listOf(3_000L to true, 1_000L to false, 3_000L to true, 6_000L to false),
            engineText = "Ein Satz.", // ends with punctuation (rule a)
            segmentFinalizeMs = 35_000L,
            stopWhen = { it.coordinator.segmentFinalizations() >= 1 },
        )
        try {
            val c = ctx.coordinator
            assertTrue("boundary at the 2nd pause (rule a)", c.segmentFinalizations() >= 1)
            assertTrue("session lives until UP (no auto-stop, no ERR)", c.isActive)
            assertNull("no ERR", c.lastError)
            assertEquals("no abort", "-", c.lastAbortReason)
            // the 1.0 s pause did NOT cut (rule-a minimum pause 1.2 s):
            // the boundary came at the SECOND pause. The exact seam position in
            // the buffer depends on SCHEDULING (consumer lag catching up with
            // the capture request — honorable at fed 10…13 s, possibly only in
            // the stop drain) — therefore only INVARIANT assertions here; the
            // deterministic seam/rest contract is carried by test (2) (the
            // consumer honors the length rule at its OWN position, not racy).
            val maxWindow = ctx.engine.windowSeconds.max()
            assertTrue(
                "no decode window beyond the 13-s session (actual=$maxWindow)",
                maxWindow <= 13.0,
            )
            // each pause only ONCE: another 4+ s of silence => NO second boundary.
            val deadline = System.currentTimeMillis() + 5_000
            var segmentsAfterPause = c.segmentFinalizations()
            while (System.currentTimeMillis() < deadline) {
                Thread.sleep(20)
                segmentsAfterPause = c.segmentFinalizations()
            }
            assertEquals("no repeated boundary in the same pause", 1, segmentsAfterPause)
            // UP => regular session final; text committed.
            c.stop()
            awaitReason(c, "final")
            assertTrue("FinalRan (is ${c.lastStopOutcome})", c.lastStopOutcome is StopOutcome.FinalRan)
            assertTrue("text committed (actual=${ctx.sink.writes} writes)", ctx.sink.writes >= 1)
            assertTrue("text in whole pieces, not empty", ctx.sink.text.isNotBlank())
            assertNull("no ERR at the end", c.lastError)
        } finally {
            ctx.scope.cancel()
        }
    }

    // ---- (2) continuous case: 40 s without pauses => forced segment at 35 s ----

    @Test
    fun `(2) 40 s of uninterrupted speech - length rule cuts at 35 s - window stays bounded`() {
        var maxBufferS = 0.0
        var firstBoundaryAtAudioS = -1.0
        val ctx = runScript(
            // 40 s of uninterrupted speech + ONLY 1.0 s of trailing silence
            // (< rule-a minimum pause 1.2 s): the FIRST cut can only come from
            // the length rule (b) (35 s) — not from the trailing silence
            // (B1 finding from earlier runs).
            script = listOf(40_000L to true, 1_000L to false),
            engineText = "Ein Satz.",
            segmentFinalizeMs = 35_000L,
            stopWhen = { ctx ->
                // segment-local decode window (buffer_s has been SESSION-based
                // since the B1 fix): hyp_last_buffer_s = last decode window.
                maxBufferS = maxOf(maxBufferS, ctx.coordinator.lastHypothesisBufferSeconds().toDouble())
                // session progress = audioMs (audioSeconds is the session length,
                // but the cut observation value must be consistent).
                if (ctx.coordinator.segmentFinalizations() >= 1 && firstBoundaryAtAudioS < 0) {
                    firstBoundaryAtAudioS = ctx.coordinator.audioMs / 1000.0
                }
                ctx.coordinator.segmentFinalizations() >= 1 && ctx.coordinator.audioMs >= 38_000L
            },
            waitMs = 60_000,
        )
        try {
            val c = ctx.coordinator
            assertTrue("forced segment ran", c.segmentFinalizations() >= 1)
            assertTrue(
                "first boundary at ~35 s (length rule, actual=$firstBoundaryAtAudioS)",
                firstBoundaryAtAudioS >= 33.0 && firstBoundaryAtAudioS <= 40.0,
            )
            assertTrue(
                "no (a) pre-cut: the 1.0-s end silence stays below the boundary",
                c.segmentFinalizations() <= 2,
            )
            assertTrue(
                "decode window per segment <= 38 s (actual=$maxBufferS)",
                maxBufferS <= 38.0,
            )
            assertTrue("session lives until UP", c.isActive)
            assertNull("no ERR", c.lastError)
            c.stop()
            awaitReason(c, "final")
            assertTrue("FinalRan (is ${c.lastStopOutcome})", c.lastStopOutcome is StopOutcome.FinalRan)
            val restWindow = ctx.engine.windowSeconds.last()
            assertTrue(
                "UP final over ONLY the rest buffer (actual=${"%.2f".format(restWindow)} s)",
                restWindow < 8.0,
            )
            // session counters monotonic: hyp_count/cap_blocks/loud_s never backwards.
            assertTrue("hyp_count > 0", c.hypCount > 0)
            assertTrue("cap_blocks > 0", c.capBlocks() > 0)
            assertTrue("loud_s > 0", c.loudS() > 0.0)
            assertTrue("at least 2 permanent commits (actual=${ctx.sink.writes})", ctx.sink.writes >= 2)
            // deterministic SEAM (byLength cuts at the consumer position):
            // segment-1 text + exactly ONE space + segment-2 text — the proof
            // that the second segment decoded over the RESET buffer (buffer
            // reset, race-free).
            // T10 (v0.1.39) added: the SESSION final appends ONE space
            // after punctuation at the end (the seg_final seam itself stays exact).
            assertEquals(
                "seam: prefix + segment-2 text (+ T10 final space)",
                "Ein Satz. Ein Satz. ",
                ctx.sink.text.toString(),
            )
        } finally {
            ctx.scope.cancel()
        }
    }

    // ---- (3) without punctuation the silence rule stays mute ----

    @Test
    fun `(3) silence without a punctuation ending does not cut - no boundary`() {
        val ctx = runScript(
            script = listOf(3_000L to true, 4_000L to false),
            engineText = "kein satz", // NO punctuation ending
            segmentFinalizeMs = 35_000L,
            stopWhen = { it.coordinator.audioMs >= 6_500L },
        )
        try {
            val c = ctx.coordinator
            // 4 s pause, but without punctuation: rule (a) mute; length rule
            // (35 s) not reached => ZERO boundaries.
            assertEquals("no segment boundary without punctuation", 0, c.segmentFinalizations())
            assertTrue("session lives", c.isActive)
            assertNull("no ERR", c.lastError)
            c.stop()
            awaitReason(c, "final")
            assertTrue(c.lastStopOutcome is StopOutcome.FinalRan)
        } finally {
            ctx.scope.cancel()
        }
    }

    // ---- (5) B1 fix (review P1): a transient finalize error does NOT kill the session ----

    @Test
    fun `(5) transient error in the segment final - session lives - retry and UP final correct`() {
        val ctx = runScript(
            // length rule (b) instead of the silence rule (a): the CONSUMER
            // honors (b) at its OWN position and RETRIES on its own after a
            // transient error (segmentStartedAtAudioMs does not move) —
            // deterministic. With (a) the retry hangs on the capture re-request
            // which never comes once the source is exhausted (B1 fix finding:
            // racy segments=0).
            script = listOf(75_000L to true, 1_000L to false),
            engineText = "Ein Satz.",
            segmentFinalizeMs = 35_000L,
            failFinalizes = 1, // the FIRST boundary attempt throws transiently
            stopWhen = { it.coordinator.segmentFinalizations() >= 1 && it.coordinator.audioMs >= 74_000L },
            waitMs = 120_000,
        )
        try {
            val c = ctx.coordinator
            // (i) the first finalize attempt failed TRANSIENTLY:
            assertTrue("one finalize attempt failed", ctx.engine.failedFinalizes == 1)
            // (ii) NO ERR, NO abort — the session survives the error.
            assertNull("lastError NOT set (transient discarded)", c.lastError)
            assertEquals("no abort", "-", c.lastAbortReason)
            assertTrue("session lives despite the transient segment error", c.isActive)
            // (iii) retry: the NEXT boundary attempt (next sentence pause)
            // fires again and cuts.
            assertTrue(
                "repeat boundary after the transient error (actual=${c.segmentFinalizations()})",
                c.segmentFinalizations() >= 1,
            )
            // (iv) UP finalizes the rest correctly (engine healed) — over
            //  ONLY the rest window.
            c.stop()
            awaitReason(c, "final")
            assertTrue("FinalRan (is ${c.lastStopOutcome})", c.lastStopOutcome is StopOutcome.FinalRan)
            val restWindow = ctx.engine.windowSeconds.last()
            assertTrue(
                "UP final over ONLY the rest buffer (actual=${"%.2f".format(restWindow)} s)",
                restWindow < 12.0,
            )
            assertTrue("text committed", ctx.sink.text.length >= "Ein Satz.".length)
            assertNull("no ERR from the transient error at the end either", c.lastError)
        } finally {
            ctx.scope.cancel()
        }
    }

    // ---- (6) B1 fix (review P2a): export lengths keep session semantics ----

    @Test
    fun `(6) export lengths after 2 segments are session audio length - not segment-local`() {
        val ctx = runScript(
            // 75 s of uninterrupted speech: length cuts at ~36 s and ~72 s.
            script = listOf(75_000L to true, 1_000L to false),
            engineText = "Ein Satz.",
            segmentFinalizeMs = 35_000L,
            stopWhen = { it.coordinator.segmentFinalizations() >= 2 && it.coordinator.audioMs >= 73_000L },
            waitMs = 120_000,
        )
        try {
            val c = ctx.coordinator
            assertEquals("two length cuts", 2, c.segmentFinalizations())
            // 🐞 source audio_s: AFTER 2 segments still the SESSION length
            // (segment-local it would be <= ~1–2 s).
            assertTrue(
                "audio_s session semantics after 2 segments (actual=${c.audioSeconds})",
                c.audioSeconds >= 70.0,
            )
            // 🐞 source buffer_s: the same tracked session length.
            assertEquals(
                "buffer_s == audio_s (same trailing source)",
                c.audioSeconds, c.bufferSeconds(), 0.5,
            )
            // hyp_last_buffer_s stays segment-local (decode window, unchanged).
            assertTrue(
                "hyp_last_buffer_s stays segment-local (actual=${c.lastHypothesisBufferSeconds()})",
                c.lastHypothesisBufferSeconds() <= 40f,
            )
            c.stop()
            awaitReason(c, "final")
        } finally {
            ctx.scope.cancel()
        }
    }

    // ---- (4) pure function: truth table of the two rules ----

    @Test
    fun `(4) segmentBoundary truth table - length rule and silence rule`() {
        val f = ::segmentBoundary
        // segmenter off => never.
        assertFalse(
            "segmentFinalizeMs=0 => off",
            f(1_010_000L, 1_000_000L, true, 20L, 1_000_000L, true, 0L),
        )
        // (b) length rule: 35 s reached => even without silence/punctuation.
        assertTrue(
            "length rule at 35 s",
            f(1_035_000L, 1_035_000L, true, 20L, 1_000_000L, false, 35_000L),
        )
        assertFalse(
            "length rule just below (34.9 s)",
            f(1_034_900L, 1_034_900L, true, 20L, 1_000_000L, false, 35_000L),
        )
        // (a) silence rule: sentence pause 1.2 s + punctuation + streaming + everLoud.
        assertTrue(
            "sentence pause 1.2 s with punctuation",
            f(1_002_200L, 1_001_000L, true, 20L, 1_000_000L, true, 35_000L),
        )
        assertFalse(
            "pause 1.19 s: not yet",
            f(1_002_190L, 1_001_000L, true, 20L, 1_000_000L, true, 35_000L),
        )
        assertFalse(
            "without punctuation: rule (a) silent",
            f(1_002_200L, 1_001_000L, true, 20L, 1_000_000L, false, 35_000L),
        )
        assertFalse(
            "dead source (capStall >= 2500): silence != stall",
            f(1_002_200L, 1_001_000L, true, 2_600L, 1_000_000L, true, 35_000L),
        )
        assertFalse(
            "never loud (everLoud false)",
            f(1_002_200L, 1_000_000L, false, 20L, 1_000_000L, true, 35_000L),
        )
    }

    /** F56: device-faithful stub (LatencyStubEngine pattern, JVM without sleep) —
     *  words = 2/s window, deterministically "wort<i>", '.' every 8th word. */
    private class DevicePunctStubEngine : AsrEngine {
        val windows = ArrayList<Double>()
        override fun load(modelDir: File) {}
        override suspend fun transcribe(
            window: PcmWindow, sessionId: Long, runId: Long,
        ): Hypothesis {
            val seconds = window.samples.size / 16_000.0
            windows.add(seconds)
            val words = kotlin.math.round(seconds * 2).toInt().coerceAtLeast(1)
            val sb = StringBuilder()
            for (i in 1..words) {
                if (i > 1) sb.append(' ')
                sb.append("wort").append(i)
                if (i % 8 == 0) sb.append('.') // punctuation every 8 words
            }
            return Hypothesis(
                IntArray(0), sb.toString(), IntArray(0), sessionId, runId,
                window.samples.size, incomplete = false,
            )
        }
        override fun close() {}
    }

    /** F56 expected text: "wort1 … wort<n>" with '.' every 8th word. */
    private fun punctWords(n: Int): String =
        (1..n).joinToString(" ") { i -> "wort$i" + if (i % 8 == 0) "." else "" }

    /**
     * F56 (vc33 device finding 2026-11-02): session abort "aborted:consumer:Illegal-
     * StateException" EXACTLY in the second of the FIRST mini final (seg=1,
     * out=189). Suspicion: seam collision mini final × running hypothesis.
     * JVM repro with a DEVICE PATTERN: growing windows, '.' every 8 words,
     * corpus 16 s loud — 2 s pause — 6 s loud — 8 s silence, 500 µs block
     * pacing, real DeltaTyper/sink chain across the coordinator.
     *
     * FINDING (fix run): the chain is DETERMINISTICALLY CLEAN — no abort,
     * no ISE; the only ISE type that CAN reach the consumer is the RETHROWN
     * editor-sync timeout (F1 drift, device: blocked main thread,
     * EDIT_TIMEOUT_MS=2000) — its code masking (visibleError shows
     * consumer:IllegalStateException instead of editor_sync_timeout) is what
     * the F56 coordinator patch fixes; this is the sequence regression part.
     */
    @Test
    fun `F56-Repro - the first mini final does not kill the session - chain deterministically clean`() {
        val clock = VClock()
        val src = ScriptSource(
            clock,
            listOf(
                16_000L to true, // segment 1: loud — the 16 s window ends with "wort32."
                2_000L to false, // sentence pause (≥ 1.2 s) → rule (a) fires
                6_000L to true, // segment 2 — script END: from here the source
                // delivers only zero blocks (no further sound can arrive), so
                // the final window is DETERMINISTICALLY 6 s (run-1 finding: 8 s
                // of trailing silence fired (a) a second time; run-2 finding: a
                // 1 s trailing silence is a stop race against the capture —
                // the stop wins, the silence drops away, window = 6 s).
            ),
            pacingNanos = 500_000L,
        )
        val engine = DevicePunctStubEngine()
        val sink = RecordingSink()
        val scope = CoroutineScope(Dispatchers.Default)
        val c = DictationCoordinator(
            scope, { src }, { engine }, sink, nowMs = { clock.now.get() },
            autoStopSuppressed = { true }, // hold: F43 off, the session lives
            segmentFinalizeMs = 35_000L,
        )
        try {
            assertTrue(c.start())
            // wait until the WHOLE script has been fed (deterministic VClock
            // position, NOT wall clock): all chunks flushed.
            // deterministic wait position: ALL loud chunks flushed
            // (audioMs = 24 s; the 1 s of rest silence only flows in the stop drain).
            val deadline = System.currentTimeMillis() + 20_000
            while (c.audioMs < 24_000L && System.currentTimeMillis() < deadline) {
                Thread.sleep(5)
            }
            assertEquals("pure corpus fed (actual=${c.audioMs} ms)", 24_000L, c.audioMs)
            // (1) THE REPRO CORE: the first mini final did NOT kill the session
            //     — no abort, no ISE, the mic keeps running.
            assertEquals("exactly ONE mini-final (rule a at the 2-s pause)", 1, c.segmentFinalizations())
            assertTrue("session lives after the mini-final (no abort)", c.isActive)
            assertNull("no error code (no ISE escaped)", c.lastError)
            assertEquals("no abort reason", "-", c.lastAbortReason)
            // (2) the mini final repeats the window of the last partial
            //     (device contract; 18 s = 16 s loud + 2 s pause in segment 1).
            assertTrue(
                "finalize window == last partial window (actual=${engine.windows})",
                engine.windows.count { it == 18.0 } >= 2,
            )
            assertTrue("no decode window over 18 s", engine.windows.max() <= 18.0)
            // (3) UP → regular final over ONLY the rest buffer (exactly the
            //     6 s of segment-2 loud — no block can still arrive after the
            //     script end) — text chain: segment 1 (W36) + ONE seam + the
            //     segment-2 rest (W12), exact, deterministic.
            c.stop()
            awaitReason(c, "final")
            assertTrue("FinalRan (is ${c.lastStopOutcome})", c.lastStopOutcome is StopOutcome.FinalRan)
            assertEquals(
                "golden: seam = EXACTLY ONE space, no duplicates",
                punctWords(36) + " " + punctWords(12),
                sink.text.toString(),
            )
            assertEquals("stop_cause=final", "final", c.stopCause())
        } finally {
            scope.cancel()
        }
    }
}
