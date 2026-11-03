package de.voicekeyboard.ime

import de.voicekeyboard.asr.AsrEngine
import de.voicekeyboard.asr.Hypothesis
import de.voicekeyboard.audio.AudioSource
import de.voicekeyboard.audio.PcmBlock
import de.voicekeyboard.audio.PcmWindow
import de.voicekeyboard.dictation.StopOutcome
import de.voicekeyboard.editor.EditorSink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.atomic.AtomicLong

/**
 * F49a (PLAN_V0130 §1 A1, minimal repro): hold gate for the F43 auto-stop on
 * the REAL corpus run — "speech — 2.0 s pause — speech — 3.0 s pause — speech"
 * (2 s loud each) × {gate=false, gate=true}:
 *
 *  - Gate=false: the 2 s pause survives (< 2.5 s), the 3 s pause ends
 *    automatically REGULARLY (oracle: auto-stop 2.5 s into the pause, the third
 *    speech is never fed), lastError null.
 *  - Gate=true: NO auto-stop — both pauses survived, run until the source end
 *    (11 s), an explicit stop finalizes regularly.
 *  - Both: ownership never lost (write path + hyp_count) and the
 *    silence≠stall contract intact (streaming silence NEVER triggers
 *    ERR_STALL/ERR_SILENT).
 *
 * Pattern [DictationAutoStopTest]: virtual clock, self-paced 320-sample blocks
 * of 20 ms each, NO Thread.sleep in the rhythm. Rule 9: numbers/codes only.
 */
class DictationHoldGateTest {

    private class VClock {
        val now = AtomicLong(1_000_000L)
    }

    /** Script source (silence = null blocks, streaming like AudioRecord). */
    private class ScriptSource(
        private val clock: VClock,
        private val script: List<Pair<Long, Boolean>>,
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
            return blockSamples
        }

        override fun stopAndDrain(): List<PcmBlock> = emptyList()
        override fun release() {
            released = true
        }
    }

    private class RecordingEngine : AsrEngine {
        var calls = 0
            private set

        override fun load(modelDir: File) {}

        override suspend fun transcribe(
            window: PcmWindow, sessionId: Long, runId: Long,
        ): Hypothesis {
            calls++
            return Hypothesis(
                IntArray(0), "k", IntArray(0), sessionId, runId,
                window.samples.size, incomplete = false,
            )
        }

        override fun close() {}
    }

    /** Real write sink (ownership proof: committed characters, never content). */
    private class RecordingSink : EditorSink {
        val text = StringBuilder()
        override fun replaceBeforeCursor(replaceLength: Int, newText: String) {
            text.delete(text.length - replaceLength, text.length)
            text.append(newText)
        }
        override fun textBeforeCursor(maxChars: Int): CharSequence = text.takeLast(maxOf(0, maxChars))
        override fun ownLength(): Int = text.length
    }

    /** Corpus: 2 s speech — 2.0 s pause — 2 s speech — 3.0 s pause — 2 s speech
     *  (TOTAL 11 s; 16 kHz = 176,000 samples; 5 full 2-s ticks = 10 s fed,
     *  the 1-s rest comes with the explicit stop). */
    private val corpus = listOf(
        2_000L to true, 2_000L to false, 2_000L to true, 3_000L to false, 2_000L to true,
    )

    private class Ctx(
        val coordinator: DictationCoordinator,
        val engine: RecordingEngine,
        val sink: RecordingSink,
        val scope: CoroutineScope,
    )

    /** Runs the corpus with the given F49 gate until the auto final (gate=false)
     *  or source end (gate=true; 5 ticks of 2 s = 10 s fed, the rest at the stop). */
    private fun runCorpus(suppressed: Boolean): Ctx {
        val clock = VClock()
        val src = ScriptSource(clock, corpus)
        val engine = RecordingEngine()
        val sink = RecordingSink()
        val scope = CoroutineScope(Dispatchers.Default)
        val c = DictationCoordinator(
            scope, { src }, { engine }, sink, nowMs = { clock.now.get() },
            autoStopSuppressed = { suppressed },
        )
        assertTrue("start must succeed", c.start())
        val deadline = System.currentTimeMillis() + 20_000
        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(5)
            if (c.lastAbortReason == "final") break // auto final (gate=false) or explicit stop
            if (c.audioSeconds >= 9.99) break // source end: 5 ticks fed (11-s corpus)
            if (!c.isActive) break // kill path (must not happen here)
        }
        return Ctx(c, engine, sink, scope)
    }

    private fun awaitReason(c: DictationCoordinator, expected: String, timeoutMs: Long = 15_000) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (c.lastAbortReason != expected && System.currentTimeMillis() < deadline) {
            Thread.sleep(5)
        }
        assertEquals("abort_reason", expected, c.lastAbortReason)
    }

    // ---- (1) gate=false: oracle — auto-stop at the 3-s pause ----

    @Test
    fun `gate=false - auto-stop at the 3-s pause, 2-s pause survives, no ERR`() {
        val ctx = runCorpus(suppressed = false)
        try {
            val c = ctx.coordinator
            // the auto final has already occurred (no explicit stop needed).
            assertEquals("regular auto-final", "final", c.lastAbortReason)
            assertTrue("Outcome FinalRan (is ${c.lastStopOutcome})", c.lastStopOutcome is StopOutcome.FinalRan)
            assertNull("no ERR (silence is not a stall/silence trap)", c.lastError)
            // Oracle: auto-stop 2.5 s INTO the 3-s pause (after 8 s of corpus), the
            // third speech (from 9 s on) is NEVER fed.
            assertTrue(
                "Auto-Stop nach der 3. Sprache, in der Pause (audio_s=${c.audioSeconds})",
                c.audioSeconds >= 8.0 && c.audioSeconds <= 9.5,
            )
            assertTrue("no ERR_STALL/ERR_SILENT abort", c.lastAbortReason.startsWith("audio_error").not())
        } finally {
            ctx.scope.cancel()
        }
    }

    // ---- (2) gate=true: no auto-stop, run until the source end ----

    @Test
    fun `gate=true - no auto-stop, both pauses survived, until the source end`() {
        val ctx = runCorpus(suppressed = true)
        try {
            val c = ctx.coordinator
            assertTrue("gate closed: session keeps running after a 2-s AND 3-s pause", c.isActive)
            assertEquals("no abort until the source ends", "-", c.lastAbortReason)
            assertNull("no ERR despite the 3-s pause", c.lastError)
            assertTrue(
                "whole corpus fed (audio_s=${c.audioSeconds})",
                c.audioSeconds >= 9.99,
            )
            c.stop() // regular finish
            awaitReason(c, "final")
            assertTrue("explicit stop: FinalRan (is ${c.lastStopOutcome})", c.lastStopOutcome is StopOutcome.FinalRan)
            assertTrue(
                "remnants not lost at the stop: audio_s=${c.audioSeconds} ≈ 11.0",
                c.audioSeconds >= 10.9,
            )
        } finally {
            ctx.scope.cancel()
        }
    }

    // ---- (3) both gates: ownership never lost ----

    @Test
    fun `both gates - ownership never lost (write path runs, hyp_count positive)`() {
        for (suppressed in listOf(false, true)) {
            val ctx = runCorpus(suppressed = suppressed)
            try {
                val c = ctx.coordinator
                if (suppressed) { // first finish regularly, then evaluate
                    c.stop()
                    awaitReason(c, "final")
                }
                assertTrue(
                    "gate=$suppressed: Hypothesen erzeugt (hyp_count=${c.hypCount})",
                    c.hypCount >= 1 && ctx.engine.calls >= 1,
                )
                assertTrue(
                    "gate=$suppressed: the write path committed (${ctx.sink.text.length} characters, digits only)",
                    ctx.sink.text.isNotEmpty(),
                )
            } finally {
                ctx.scope.cancel()
            }
        }
    }

    // ---- (4) both gates: silence≠stall contract intact ----

    @Test
    fun `both gates - streaming silence never triggers ERR_STALL and capStall stays below the limit`() {
        for (suppressed in listOf(false, true)) {
            val ctx = runCorpus(suppressed = suppressed)
            try {
                val c = ctx.coordinator
                if (suppressed) {
                    c.stop()
                    awaitReason(c, "final")
                }
                assertNull("gate=$suppressed: no ERR_STALL/ERR_SILENT (lastError=${c.lastError})", c.lastError)
                assertTrue(
                    "gate=$suppressed: capStallMs=${c.capStallMs()} < STALL_LIMIT (blocks streamed)",
                    c.capStallMs() < DictationCoordinator.STALL_LIMIT_MS,
                )
            } finally {
                ctx.scope.cancel()
            }
        }
    }
}
