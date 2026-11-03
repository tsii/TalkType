package de.voicekeyboard.dictation

import de.voicekeyboard.asr.AsrEngine
import de.voicekeyboard.asr.Hypothesis
import de.voicekeyboard.audio.AudioSource
import de.voicekeyboard.audio.PcmBlock
import de.voicekeyboard.audio.PcmWindow
import de.voicekeyboard.editor.EditorSink
import de.voicekeyboard.editor.OwnershipTracker
import de.voicekeyboard.ime.DictationCoordinator
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
 * B3 (PLAN_V0130 §6.3-B3): GOLDEN PROOF PER SEGMENT — the chaining of the
 * segment commits is SEAM-INVARIANTLY exactly golden, no matter WHERE the
 * (a) seam falls (consumer lag is scheduling-dependent, B1 finding).
 *
 * Deterministic transcription stub ([RunScannerEngine]): the audio encodes
 * words as amplitude runs (6000→"alpha.", 9000→"bravo.", 12000→"charlie."
 * — period inside the word, seam-invariant); the stub transcribes a window
 * by scanning runs:
 *  - a word run that TOUCHES the window end is CUT-SUSPECT and is DISCARDED
 *    (the following segment emits it as a leading rest) — exactly this makes
 *    the chain loss- and duplicate-free across ALL seam positions; corpus
 *    start and end are padded with silence so only cut runs can sit at the
 *    edges.
 *  - cut positions are quantized to 2 s (flush pace) — the corpus word
 *    boundaries deliberately do NOT lie on 2 s multiples.
 *
 * Scenarios (segmentFinalizeMs = 35,000, hold gate: F43 off):
 *  - (a) chain "alpha—pause 1.5 s—bravo—pause 1.5 s—charlie": chaining ==
 *    "alpha. bravo. charlie." EXACTLY (exactly one space per seam, no
 *    duplicates/losses — independent of the (racy) (a) seam position).
 *  - (b) UP finalizes ONLY the rest (charlie) — total text still correct,
 *    FinalRan.
 *  - (c) (b) forced segment at 36 s without sentence pauses (trade-off
 *    documented): exactly one space at the (b) seam, no word duplication.
 *  - (d) 2× the identical corpus ⇒ identical chaining (determinism).
 *
 * Rule 9: fixture words are test constants, never user data.
 */
class SegmentGoldenTest {

    private class VClock {
        val now = AtomicLong(1_000_000L)
    }

    /** Script source: sections (ms, amplitude); 0 = silence; 320-sample blocks.
     *  [paceUs]: TEST-HARNESS pacing per block — without it the capture
     *  overtakes the consumer µs-fast in self-paced mode, the (a) pause is over
     *  BEFORE the first decode has set lastHypPunct (B1 finding,
     *  SegmentGolden-a run: segments=0). With ~500 µs/block the consumer
     *  decodes (2 s flush ≈ 50 ms real) BEFORE the sentence pause (~120 ms
     *  real) — the (a) chain becomes deterministically sharp. */
    private class AmpScriptSource(
        private val clock: VClock,
        private val script: List<Pair<Long, Short>>,
        private val paceUs: Long = 500L,
    ) : AudioSource {
        private val blockMs = 20L
        private val blockSamples = 320
        private val totalMs = script.sumOf { it.first }
        private var base = -1L
        private var nextIdx = 0L
        @Volatile private var released = false

        private fun ampAt(ms: Long): Short {
            var t = 0L
            for ((d, a) in script) {
                t += d
                if (ms < t) return a
            }
            return 0
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
            if (paceUs > 0) java.util.concurrent.locks.LockSupport.parkNanos(paceUs * 1_000)
            val amp = ampAt(t)
            java.util.Arrays.fill(dst, 0, blockSamples, amp)
            nextIdx++
            return blockSamples
        }

        override fun stopAndDrain(): List<PcmBlock> = emptyList()
        override fun release() {
            released = true
        }
    }

    /**
     * Deterministic transcription stub: amplitude runs → words.
     * A run that TOUCHES the window end counts as cut and is discarded (the
     * following segment delivers the rest as a leading run) — thereby the
     * SEGMENT CHAINING is exact across all seam positions.
     */
    private class RunScannerEngine(
        private val words: Map<Short, String>,
        private val minRunSamples: Int = 4_800, // 0.3 s — filter for split rests
    ) : AsrEngine {
        var calls = 0
            private set
        val windowSeconds = ArrayList<Double>()

        override fun load(modelDir: File) {}

        override suspend fun transcribe(
            window: PcmWindow, sessionId: Long, runId: Long,
        ): Hypothesis {
            calls++
            val s = window.samples
            windowSeconds.add(s.size / 16_000.0)
            val out = StringBuilder()
            var i = 0
            var runStart = 0
            var runAmp = if (s.isNotEmpty()) s[0] else 0
            var appended = 0
            while (i < s.size) {
                if (kotlin.math.abs(s[i].toInt() - runAmp.toInt()) > 100) {
                    // run [runStart, i) finished — NOT touching the window end
                    if (i - runStart >= minRunSamples) {
                        words[runAmp]?.let { w ->
                            if (appended > 0) out.append(' ')
                            out.append(w)
                            appended++
                        }
                    }
                    runStart = i
                    runAmp = s[i]
                }
                i++
            }
            // The last run necessarily TOUCHES the window end and is NOT
            // emitted (cut-suspect: the following segment delivers the rest
            // as a leading run) — the corpus end padding ensures that in the
            // FINAL window the last run is silence and the words before it are
            // closed by amplitude changes (and therefore emitted).
            val text = out.toString()
            return Hypothesis(
                IntArray(0), text, IntArray(0), sessionId, runId,
                s.size, incomplete = false,
            )
        }

        override fun close() {}
    }

    /** Real write sink: mirrors the editor text state (never logged, rule 9). */
    private class RecordingSink : EditorSink {
        val text = StringBuilder()
        override fun replaceBeforeCursor(replaceLength: Int, newText: String) {
            text.delete(text.length - replaceLength, text.length)
            text.append(newText)
        }
        override fun textBeforeCursor(maxChars: Int): CharSequence = text.takeLast(maxOf(0, maxChars))
        override fun ownLength(): Int = text.length
    }

    private class Ctx(
        val coordinator: DictationCoordinator,
        val engine: RunScannerEngine,
        val sink: RecordingSink,
        val scope: CoroutineScope,
        val totalMs: Long,
    )

    /** Words as amplitudes — period inside the word (seam-invariant). */
    private val words = mapOf(
        6_000.toShort() to "alpha.",
        9_000.toShort() to "bravo.",
        12_000.toShort() to "charlie.",
    )

    /** (a)/(b)/(d) corpus: pad—alpha—pause—bravo—pause—charlie—pad (~13.6 s). */
    private val chainScript = listOf(
        500L to 0.toShort(),          // start padding: no run at the edge
        3_200L to 6_000.toShort(),    // alpha 0.5–3.7 s
        1_500L to 0.toShort(),        // pause 1 (sentence pause, rule a)
        2_800L to 9_000.toShort(),    // bravo 5.2–8.0 s — boundary NOT on the 2 s grid
        1_600L to 0.toShort(),        // pause 2
        2_900L to 12_000.toShort(),   // charlie 9.6–12.5 s
        500L to 0.toShort(),          // end padding: last run in the window = silence
    )

    /** (c) corpus: 46 s monologue without sentence pauses (amplitude changes only). */
    private val monologScript = listOf(
        500L to 0.toShort(),
        15_000L to 6_000.toShort(),   // alpha 0.5–15.5
        15_000L to 9_000.toShort(),   // bravo 15.5–30.5
        15_000L to 12_000.toShort(),  // charlie 30.5–45.5 (36 s cut in the middle)
        500L to 0.toShort(),
    )

    private fun runChain(
        script: List<Pair<Long, Short>>,
        stopWhenAudioMs: Long? = null,
    ): Ctx {
        val totalMs = script.sumOf { it.first }
        val clock = VClock()
        val src = AmpScriptSource(clock, script)
        val engine = RunScannerEngine(words)
        val sink = RecordingSink()
        val scope = CoroutineScope(Dispatchers.Default)
        val coordinator = DictationCoordinator(
            scope, { src }, { engine }, sink,
            tracker = OwnershipTracker(),
            nowMs = { clock.now.get() },
            autoStopSuppressed = { true }, // hold contract: F43 off — seams only via the segmenter
            segmentFinalizeMs = 35_000L,
        )
        assertTrue(coordinator.start())
        val ctx = Ctx(coordinator, engine, sink, scope, totalMs)
        val target = stopWhenAudioMs ?: totalMs
        val deadline = System.currentTimeMillis() + 30_000
        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(5)
            if (coordinator.audioMs >= target - 1 || !coordinator.isActive) break
        }
        return ctx
    }

    private fun finishAndText(ctx: Ctx): String {
        ctx.coordinator.stop()
        val deadline = System.currentTimeMillis() + 15_000
        while (ctx.coordinator.lastAbortReason != "final" && System.currentTimeMillis() < deadline) {
            Thread.sleep(5)
        }
        assertEquals("abort_reason", "final", ctx.coordinator.lastAbortReason)
        return ctx.sink.text.toString()
    }

    // ---- (a) chaining == golden, exact, seam-invariant ----

    @Test
    fun `(a) segment chaining is exactly golden - one space per seam, no duplicates`() {
        val ctx = runChain(chainScript)
        try {
            val c = ctx.coordinator
            // at least one (a) boundary at the sentence pauses; the exact count
            // depends on the seam position (1–2 cuts cover the chain).
            assertTrue(
                "at least one segment boundary (actual=${c.segmentFinalizations()})",
                c.segmentFinalizations() in 1..2,
            )
            assertNull("no ERR", c.lastError)
            val text = finishAndText(ctx)
            // T10 (v0.1.39) added: the SESSION final appends ONE space after
            // punctuation at the end (golden + trail_sp).
            assertEquals(
                "chain == golden total text (exact, one space per seam, T10 final space)",
                "alpha. bravo. charlie. ",
                text,
            )
            assertFalse("no double spaces (seam rule)", text.contains("  "))
        } finally {
            ctx.scope.cancel()
        }
    }

    // ---- (b) UP finalizes only the rest — total text unchanged and correct ----

    @Test
    fun `(b) UP finalizes only the rest buffer - total text stays golden`() {
        val ctx = runChain(chainScript)
        try {
            val c = ctx.coordinator
            val text = finishAndText(ctx)
            assertTrue("FinalRan (is ${c.lastStopOutcome})", c.lastStopOutcome is StopOutcome.FinalRan)
            assertEquals("total text unchanged and correct (+ T10 final space)", "alpha. bravo. charlie. ", text)
            // the UP final window is NEVER the whole session (rest contract);
            // (if the seam is right at the end, the empty rest is skipped — the
            // last window is then the mini final, likewise < session.)
            val lastWindow = ctx.engine.windowSeconds.last()
            assertTrue(
                "UP/mini window segment-local (actual=$lastWindow)",
                lastWindow <= 13.6,
            )
            assertNull("no ERR", c.lastError)
        } finally {
            ctx.scope.cancel()
        }
    }

    // ---- (c) (b) forced segment without sentence pauses: seam trade-off mastered ----

    @Test
    fun `(c) b forced segment at 36 s - one space at the seam, no duplication`() {
        // trade-off (documented, PLAN §6.3 (b) column): the forced cut hits NO
        // sentence pause — the seam falls into the middle of the word field.
        // the scanner discards the cut rest in the old segment and emits it in
        // the new one as a leading run: exactly ONE space at the (b) seam, no
        // word duplication, no loss.
        val ctx = runChain(monologScript)
        try {
            val c = ctx.coordinator
            val text = finishAndText(ctx)
            assertEquals(
                "forced segment: (b) seam with exactly one space (+ T10 final space)",
                "alpha. bravo. charlie. ",
                text,
            )
            assertEquals("exactly ONE (b) cut (36 s, deterministic)", 1, c.segmentFinalizations())
            assertFalse("no double spaces", text.contains("  "))
            assertNull("no ERR", c.lastError)
        } finally {
            ctx.scope.cancel()
        }
    }

    // ---- (d) determinism: 2× the identical corpus ⇒ identical chaining ----

    @Test
    fun `(d) twice the identical corpus - identical chaining (determinism)`() {
        val text1 = runChain(chainScript).let { ctx ->
            try {
                finishAndText(ctx)
            } finally {
                ctx.scope.cancel()
            }
        }
        val text2 = runChain(chainScript).let { ctx ->
            try {
                finishAndText(ctx)
            } finally {
                ctx.scope.cancel()
            }
        }
        assertEquals("2nd run == 1st run (determinism)", text1, text2)
        assertEquals("both runs == golden (+ T10 final space)", "alpha. bravo. charlie. ", text1)
    }
}
