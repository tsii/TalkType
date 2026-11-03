package de.voicekeyboard.ime

import de.voicekeyboard.asr.AsrEngine
import de.voicekeyboard.asr.Hypothesis
import de.voicekeyboard.audio.AudioSource
import de.voicekeyboard.audio.PcmBlock
import de.voicekeyboard.audio.PcmWindow
import de.voicekeyboard.dictation.DictationState
import de.voicekeyboard.dictation.StopOutcome
import de.voicekeyboard.editor.EditorSink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * F43/F44 (F41 repro /tmp/vk_repro_f41.txt): control path tests for the
 * automatic stop after a speech pause and the FGS kill path contract.
 *
 *  (1) loud → 2.5 s silence → AUTOMATIC regular finish (final, no ERR_*),
 *      the final window covers the whole loud audio (no characters lost);
 *  (2) loud → 1 s pause → loud: keeps RUNNING (no auto-stop, no errors);
 *  (3) never loud → ERR_SILENT after the 3 s start window (stock protection
 *      F31b), the kill path fires onAborted (FGS stop trigger, F44);
 *  (4) the consumer's kill paths (engine error, OOM) fire onAborted — the
 *      service stops the mic FGS through it (fgs_stopped_by), previously a
 *      ~58 s leak.
 *
 * Time model: virtual injectable clock (coordinator nowMs), 320-sample blocks
 * of 20 ms each, self-paced (FakeWavAudioSource pattern) — NO Thread.sleep in
 * the rhythm. Rule 9: numbers/codes only, never audio/text content.
 *
 * Robolectric (F43 approval): the coordinator's OOM kill path calls
 * android.util.Log.w — not mocked in a plain JVM unit test (the RuntimeException
 * flies out of the catch block before notifyAborted runs). Robolectric mocks
 * the framework (project standard, like LayoutCompletenessTest).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DictationAutoStopTest {

    /** Virtual ms clock; start ≠ 0 (0 = the lastBlockAtMs sentinel "never started"). */
    private class VClock {
        val now = AtomicLong(1_000_000L)
    }

    /**
     * Script source: [script] = (duration ms, loud?) segments; afterwards it
     * delivers further NULL blocks at [silentForever] (silence like AudioRecord —
     * watchdog material), otherwise the delivery ends (clock stands still, no
     * artificial stall). Loud = amplitude 12,000 (RMS ≈ 0.37 ≥ SILENCE_RMS 0.01),
     * silent = exactly 0.
     */
    private class ScriptSource(
        private val clock: VClock,
        private val script: List<Pair<Long, Boolean>>,
        private val silentForever: Boolean,
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
            if (t >= totalMs && !silentForever) {
                Thread.sleep(1) // script end: no block, virtual clock stands still
                return 0
            }
            val due = base + t
            while (true) { // self-paced: bring the clock to the due date
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

    /** Stub engine: counter + window length of the last decode (numbers only,
     *  rule 9); [failWith] throws on the next transcribe (kill path material). */
    private class RecordingEngine : AsrEngine {
        var calls = 0
            private set
        var lastWindowSamples = 0
            private set
        var failWith: Throwable? = null

        override fun load(modelDir: File) {}

        override suspend fun transcribe(
            window: PcmWindow, sessionId: Long, runId: Long,
        ): Hypothesis {
            failWith?.let { throw it }
            calls++
            lastWindowSamples = window.samples.size
            return Hypothesis(
                IntArray(0), "k", IntArray(0), sessionId, runId,
                window.samples.size, incomplete = false,
            )
        }

        override fun close() {}
    }

    /** Silent editor sink (content irrelevant, rule 9). */
    private class SilentSink : EditorSink {
        override fun replaceBeforeCursor(replaceLength: Int, newText: String) {}
        override fun textBeforeCursor(maxChars: Int): CharSequence? = null
        override fun ownLength(): Int = 0
    }

    // ---- (1) loud → 2.5 s silence → automatic regular finish ----

    @Test
    fun `(1) loud then 2,5 s silence ends automatically with a regular final`() {
        val clock = VClock()
        val src = ScriptSource(clock, listOf(3_000L to true), silentForever = true)
        val engine = RecordingEngine()
        val aborts = CopyOnWriteArrayList<String>()
        val scope = CoroutineScope(Dispatchers.Default)
        val c = DictationCoordinator(
            scope, { src }, { engine }, SilentSink(), nowMs = { clock.now.get() },
        )
        c.onAborted = { aborts.add(it) } // F45: the auto-stop must NOT be an abort
        try {
            assertTrue("start must succeed", c.start())
            awaitReason(c, "final")
            assertTrue(
                "auto-final: outcome must be FinalRan (is ${c.lastStopOutcome})",
                c.lastStopOutcome is StopOutcome.FinalRan,
            )
            assertTrue(
                "F45: auto-stop is a REGULAR final — onAborted must NOT fire (is $aborts)",
                aborts.isEmpty(),
            )
            assertNull("auto-final is NOT an error path: lastError must be null", c.lastError)
            assertEquals(DictationState.IDLE, c.state)
            assertFalse("session ends", c.isActive)
            assertTrue("final decode ran (engine_calls=${engine.calls})", engine.calls >= 1)
            assertTrue(
                "no spoken characters lost: final window >= 3 s of loud audio " +
                    "(is ${engine.lastWindowSamples} samples)",
                engine.lastWindowSamples >= 3 * 16_000,
            )
        } finally {
            scope.cancel()
        }
    }

    // ---- (2) loud → 1 s pause → loud: keeps running ----

    @Test
    fun `(2) loud 1 s pause loud keeps running - no auto-stop`() {
        val clock = VClock()
        val src = ScriptSource(
            clock, listOf(3_000L to true, 1_000L to false, 2_000L to true),
            silentForever = false,
        )
        val engine = RecordingEngine()
        val scope = CoroutineScope(Dispatchers.Default)
        val c = DictationCoordinator(
            scope, { src }, { engine }, SilentSink(), nowMs = { clock.now.get() },
        )
        try {
            assertTrue(c.start())
            // feed the whole script (6 s) through: audio_s measures the fed ticks.
            val deadline = System.currentTimeMillis() + 15_000
            while (c.audioSeconds < 5.99 && System.currentTimeMillis() < deadline) {
                Thread.sleep(5)
            }
            assertEquals("script fully fed", 6.0, c.audioSeconds, 0.01)
            assertEquals("(f) no auto-stop: abort_reason stays open", "-", c.lastAbortReason)
            assertNull("no ERR_STALL/ERR_SILENT", c.lastError)
            assertTrue("session keeps running (active)", c.isActive)
            assertEquals(DictationState.RECORDING, c.state)
            assertTrue("(a) partials ran (hyp_count=${c.hypCount})", c.hypCount >= 1)
            assertTrue("no final ran", c.lastStopOutcome is StopOutcome.IdleNoOp)
        } finally {
            c.abort("test_end")
            scope.cancel()
        }
    }

    // ---- (3) never loud → ERR_SILENT after the 3 s start window (stock protection F31b) ----

    @Test
    fun `(3) never loud stays ERR_SILENT after 3 s - and fires onAborted (F44)`() {
        val clock = VClock()
        val src = ScriptSource(clock, emptyList(), silentForever = true) // silence only
        val engine = RecordingEngine()
        val aborts = CopyOnWriteArrayList<String>()
        val scope = CoroutineScope(Dispatchers.Default)
        val c = DictationCoordinator(
            scope, { src }, { engine }, SilentSink(), nowMs = { clock.now.get() },
        )
        c.onAborted = { aborts.add(it) }
        try {
            assertTrue(c.start())
            awaitReason(c, "audio_error:silence")
            // F56 run finding (F50b family): abort() writes lastAbortReason
            // FIRST — the state transition (controller.abort()) runs afterwards
            // in the same call; under suite load the reason poll returned before
            // IDLE was reached. Poll for the TERMINAL state (expectation unchanged):
            val stateDeadline = System.currentTimeMillis() + 15_000
            while (c.state != DictationState.IDLE && System.currentTimeMillis() < stateDeadline) {
                Thread.sleep(5)
            }
            assertEquals(DictationState.IDLE, c.state)
            assertEquals("silence:no_signal", c.lastError)
            assertFalse(c.isActive)
            assertTrue(
                "F44: kill path fires onAborted (FGS stop trigger), is $aborts",
                aborts.contains("audio_error:silence"),
            )
        } finally {
            scope.cancel()
        }
    }

    // ---- (4) F44: the consumer's kill paths fire onAborted (FGS stop trigger) ----
    @Test
    fun `(4a) engine error kill path fires onAborted with an audio_error code`() {
        val clock = VClock()
        val src = ScriptSource(clock, listOf(4_000L to true), silentForever = false)
        val engine = RecordingEngine().apply { failWith = IllegalStateException("boom") }
        val aborts = CopyOnWriteArrayList<String>()
        val scope = CoroutineScope(Dispatchers.Default)
        val c = DictationCoordinator(
            scope, { src }, { engine }, SilentSink(), nowMs = { clock.now.get() },
        )
        c.onAborted = { aborts.add(it) }
        try {
            assertTrue(c.start())
            awaitReason(c, "audio_error:asr_partial_error")
            assertEquals(DictationState.IDLE, c.state)
            assertFalse("kill path ends the session", c.isActive)
            assertTrue(
                "F44: onAborted fired (FGS stop trigger), is $aborts",
                aborts.contains("audio_error:asr_partial_error"),
            )
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `(4b) OOM kill path fires onAborted with an oom code`() {
        val clock = VClock()
        val src = ScriptSource(clock, listOf(4_000L to true), silentForever = false)
        val engine = RecordingEngine().apply { failWith = OutOfMemoryError() }
        val aborts = CopyOnWriteArrayList<String>()
        val scope = CoroutineScope(Dispatchers.Default)
        val c = DictationCoordinator(
            scope, { src }, { engine }, SilentSink(), nowMs = { clock.now.get() },
        )
        c.onAborted = { aborts.add(it) }
        try {
            assertTrue(c.start())
            awaitContains(aborts, "oom")
            assertTrue(
                "OOM path visible (lastError=${c.lastError})",
                c.lastError?.startsWith("oom:") == true,
            )
            // AP7-W1 run finding (F50b family): the OOM catch sets lastStopOutcome
            // AFTER notifyAborted — the callback poll returns before the
            // assignment ran (IdleNoOp sentinel, never null). Poll for the
            // TERMINAL state, then assert (expectation unchanged):
            val deadline = System.currentTimeMillis() + 15_000
            while (c.lastStopOutcome !is StopOutcome.NoFinal &&
                System.currentTimeMillis() < deadline
            ) {
                Thread.sleep(5)
            }
            assertTrue(
                "OOM path reports NoFinal (is ${c.lastStopOutcome})",
                c.lastStopOutcome is StopOutcome.NoFinal,
            )
            assertFalse(c.isActive)
            assertEquals(DictationState.IDLE, c.state)
        } finally {
            scope.cancel()
        }
    }

    // ---- F45 (retest F41b, P1): FGS stop in the auto-stop final ----

    @Test
    fun `F45 auto-stop final stops the FGS with autostop - user stop untouched`() {
        // If the FGS is STILL running in the onStopped final, it was the auto-stop
        // (no user stop before) → stopFgs("autostop"), fgs_stopped_by="autostop".
        assertEquals(
            "auto-stop: FGS still running → reason autostop",
            "autostop",
            fgsStopOnDictationStopped(fgsRunning = true),
        )
        // user stop: stopFgs("stop") already ran synchronously → NO second reason
        // here (no overwriting of fgs_stopped_by).
        assertNull(
            "user stop: FGS already stopped, no autostop reason",
            fgsStopOnDictationStopped(fgsRunning = false),
        )
    }

    // ---- F49 (PLAN_V0130 §1 A1): hold gate for the F43 auto-stop ----

    @Test
    fun `(5) hold gate true suppresses the auto-stop until the source ends`() {
        val clock = VClock()
        // 3 s loud, then 6 s of streaming silence (> 2.5 s!) — script end at 9 s.
        val src = ScriptSource(clock, listOf(3_000L to true, 6_000L to false), silentForever = false)
        val engine = RecordingEngine()
        val scope = CoroutineScope(Dispatchers.Default)
        val c = DictationCoordinator(
            scope, { src }, { engine }, SilentSink(), nowMs = { clock.now.get() },
            autoStopSuppressed = { true }, // F49: hold gesture "not finished yet"
        )
        try {
            assertTrue(c.start())
            // let the script deliver completely (4 ticks of 2 s = 8 s fed;
            // the 1 s rest comes with the explicit stop). 6 s of silence > 2.5 s
            // MUST be suppressed — no auto-stop, no ERR.
            val deadline = System.currentTimeMillis() + 15_000
            while (c.audioSeconds < 7.99 && System.currentTimeMillis() < deadline) {
                Thread.sleep(5)
            }
            assertTrue("script delivered to the end (audio_s=${c.audioSeconds})", c.audioSeconds >= 7.99)
            assertTrue("gate closed: NO auto-stop despite 6 s of silence", c.isActive)
            assertEquals("no abort", "-", c.lastAbortReason)
            assertNull("no ERR despite silence > 2.5 s", c.lastError)
            c.stop() // regular finish as by the user
            awaitReason(c, "final")
            assertTrue("explicit stop: FinalRan (is ${c.lastStopOutcome})", c.lastStopOutcome is StopOutcome.FinalRan)
            assertTrue("remnants not lost at the stop (audio_s=${c.audioSeconds})", c.audioSeconds >= 8.9)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `(6) hold gate opens again - auto-stop fires after opening`() {
        val clock = VClock()
        val src = ScriptSource(clock, listOf(3_000L to true), silentForever = true)
        val engine = RecordingEngine()
        val gate = AtomicBoolean(true)
        val scope = CoroutineScope(Dispatchers.Default)
        val c = DictationCoordinator(
            scope, { src }, { engine }, SilentSink(), nowMs = { clock.now.get() },
            autoStopSuppressed = { gate.get() },
        )
        try {
            assertTrue(c.start())
            // let silence > 2.5 s run (the ticks keep feeding) — gate closed.
            val deadline = System.currentTimeMillis() + 15_000
            while (c.audioSeconds < 4.0 && System.currentTimeMillis() < deadline) {
                Thread.sleep(5)
            }
            assertTrue("suppressed: keeps running despite silence > 2.5 s", c.isActive)
            assertEquals("no abort with a closed gate", "-", c.lastAbortReason)
            gate.set(false) // F49 toggle: release preparation — the gate opens
            awaitReason(c, "final")
            assertTrue("after opening: FinalRan (is ${c.lastStopOutcome})", c.lastStopOutcome is StopOutcome.FinalRan)
            assertNull("regular final, no ERR", c.lastError)
        } finally {
            scope.cancel()
        }
    }

    // ---- helpers (polled in the test only; no sleep in the production rhythm) ----

    /** Waits until lastAbortReason == [expected] (hard deadline, no loosening). */
    private fun awaitReason(c: DictationCoordinator, expected: String, timeoutMs: Long = 15_000) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (c.lastAbortReason != expected && System.currentTimeMillis() < deadline) {
            Thread.sleep(5)
        }
        assertEquals("abort_reason (F43/F44)", expected, c.lastAbortReason)
    }

    /** Waits until [list] contains [expected] (kill path without its own abort reason). */
    private fun awaitContains(list: CopyOnWriteArrayList<String>, expected: String, timeoutMs: Long = 15_000) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!list.contains(expected) && System.currentTimeMillis() < deadline) {
            Thread.sleep(5)
        }
        assertTrue("onAborted reason '$expected' missing (is $list)", list.contains(expected))
    }
}
