package de.voicekeyboard.ime

import de.voicekeyboard.asr.AsrEngine
import de.voicekeyboard.asr.Hypothesis
import de.voicekeyboard.audio.FakeWavAudioSource
import de.voicekeyboard.audio.PcmWindow
import de.voicekeyboard.dictation.DictationState
import de.voicekeyboard.dictation.StopOutcome
import de.voicekeyboard.editor.EditorSink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * F30 (docs/EMULATOR_DIKTAT_HARNESS.md, option C): four deterministic
 * abort scenarios with the debug-only [FakeWavAudioSource] (debug source
 * folder, hence this test lives in testDebug, not in test — testReleaseUnitTest
 * must not reference the debug class) and the REAL DictationCoordinator;
 * the engine is a test double (no ONNX, no Android audio).
 *
 * Time: a virtual ms clock shared by the source AND the coordinator (nowMs).
 * The test thread "pumps" the clock; the source delivers 10-ms blocks from
 * their due date — NO Thread.sleep in the rhythm.
 *
 * Note (honest): the hypothesis interval gate (2 s) in the controller uses
 * real-time nanoTime (not injectable via the coordinator) — scenario (a)
 * therefore pumps ~4 s REAL TIME so that ≥ 2 intervals are deterministically
 * exceeded; the remaining scenarios need no partials.
 */
class FakeAudioScenarioTest {

    /** Virtual ms clock (start != 0: 0 is the coordinator sentinel "never started"). */
    private class VirtualClock(startMs: Long = 1_000_000L) {
        private val lock = Any()
        @Volatile private var t = startMs
        fun now(): Long = synchronized(lock) { t }
        fun advanceTo(targetMs: Long) {
            synchronized(lock) { if (targetMs > t) t = targetMs }
        }
    }

    /** Fake engine: counts decodes, returns constant text (no content relevant). */
    private class FakeAsrEngine : AsrEngine {
        val transcribeCalls = AtomicInteger(0)
        override fun load(modelDir: File) {}
        override suspend fun transcribe(window: PcmWindow, sessionId: Long, runId: Long): Hypothesis {
            transcribeCalls.incrementAndGet()
            val text = "x".repeat((window.audioSeconds * 3).toInt().coerceAtLeast(1))
            return Hypothesis(
                IntArray(1), text, IntArray(1), sessionId, runId,
                window.samples.size, incomplete = false,
            )
        }

        override fun close() {}
    }

    private class FakeSink : EditorSink {
        override fun replaceBeforeCursor(replaceLength: Int, newText: String) {}
        override fun textBeforeCursor(maxChars: Int): CharSequence? = null
        override fun ownLength(): Int = 0
    }

    private fun wav(): File {
        val candidates = listOf(
            File("testdata/de_smoke.wav"),
            File("../testdata/de_smoke.wav"),
            File(System.getProperty("user.dir"), "testdata/de_smoke.wav"),
        )
        return candidates.firstOrNull { it.isFile }
            ?: error("de_smoke.wav not found (user.dir=${System.getProperty("user.dir")})")
    }

    /** Pumps virtual time in steps (the real-time delay lets the
     *  capture/consumer threads run; the rhythm stays virtual). */
    private suspend fun VirtualClock.pumpTo(targetMs: Long, stepMs: Long, realDelayMs: Long) {
        while (now() < targetMs) {
            advanceTo(now() + stepMs)
            delay(realDelayMs)
        }
    }

    private fun newScope() = CoroutineScope(Dispatchers.Default)

    /** Minimal s16le-mono WAV writer (44-byte header + data) for the
     *  silence/mix test files (rule 9: test files local, no content logged). */
    private fun writeWav(samples: ShortArray): File {
        val data = ByteArray(samples.size * 2)
        for (i in samples.indices) {
            // Int op (the short bit-op overload is not resolvable in this
            // Kotlin environment — evidenced empirically by compile errors):
            data[i * 2] = (samples[i].toInt() and 0xff).toByte()
            data[i * 2 + 1] = (samples[i].toInt() shr 8).toByte()
        }
        val file = File.createTempFile("vk_f31", ".wav")
        file.outputStream().use { out ->
            fun le32(v: Int) {
                out.write(v and 0xff); out.write((v shr 8) and 0xff)
                out.write((v shr 16) and 0xff); out.write((v shr 24) and 0xff)
            }
            out.write("RIFF".toByteArray()); le32(36 + data.size)
            out.write("WAVE".toByteArray())
            out.write("fmt ".toByteArray()); le32(16)
            out.write(1); out.write(0)             // PCM
            out.write(1); out.write(0)             // mono
            out.write(16); out.write(0); out.write(0); out.write(0) // 16000 Hz
            le32(32000)                             // ByteRate
            out.write(2); out.write(0)              // BlockAlign
            out.write(16); out.write(0)             // bit depth
            out.write("data".toByteArray()); le32(data.size)
            out.write(data)
        }
        return file
    }

    /** Silent WAV: zero samples (blocks keep running, RMS = 0 — the F31 case). */
    private fun silentWav(seconds: Int): File =
        writeWav(ShortArray(seconds * 16_000))

    /** Mixed WAV from segments (ms, loud?). "Loud" = 200 Hz sine, amplitude
     *  12000 ≈ −9 dBFS peak → block RMS ≈ −12 dBFS, far above the −40 threshold. */
    private fun segmentWav(segments: List<Pair<Int, Boolean>>): File {
        val n = segments.sumOf { it.first } * 16
        val s = ShortArray(n)
        val omega = 2.0 * Math.PI * 200.0 / 16_000.0
        var offsetMs = 0
        for ((ms, loud) in segments) {
            if (loud) {
                for (i in offsetMs * 16 until (offsetMs + ms) * 16) {
                    s[i] = (Math.sin(omega * i) * 12_000).toInt().toShort()
                }
            }
            offsetMs += ms
        }
        return writeWav(s)
    }

    /** Mix: [silentMs] silence, then [loudMs] sine (existing F31 tests). */
    private fun mixWav(silentMs: Int, loudMs: Int): File =
        segmentWav(listOf(silentMs to false, loudMs to true))

    /** Waits (only in the test!) until the async stop() worker reaches IDLE. */
    private fun awaitIdle(c: DictationCoordinator, timeoutMs: Long = 5000) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (c.state != DictationState.IDLE && System.currentTimeMillis() < deadline) {
            Thread.sleep(10)
        }
        assertEquals("the stop() worker must end in IDLE", DictationState.IDLE, c.state)
    }

    // (a) Continuously 10 s: no stall, cap_blocks > 0, hyp grows; UP → final.
    @Test
    fun `a continuously 10 s - no stall, cap_blocks positive, hyp grows`() = runBlocking {
        val clock = VirtualClock()
        val t0 = clock.now()
        val src = FakeWavAudioSource(wav(), nowMs = { clock.now() })
        val engine = FakeAsrEngine()
        val scope = newScope()
        val c = DictationCoordinator(scope, { src }, { engine }, FakeSink(), nowMs = { clock.now() })
        assertTrue("start must succeed", c.start())
        // 10 s virtual time in ~4 s real time (the 2-s interval gate runs on real time).
        clock.pumpTo(t0 + 10_000L, stepMs = 25L, realDelayMs = 10L)
        assertEquals("before the stop no abort cause (no ERR_STALL)", "-", c.lastAbortReason)
        assertTrue("cap_blocks > 0 (are ${c.capBlocks()})", c.capBlocks() > 500)
        assertTrue("hyp grows (are ${c.hypCount})", c.hypCount >= 2)
        c.stop() // "UP" after 10 s
        awaitIdle(c)
        assertEquals("user case: final stop", "final", c.lastAbortReason)
        scope.cancel()
    }

    // (b) Silence from session start: after 3 s watchdog → ERR_STALL "no_audio",
    //     session ended, 0 received blocks.
    @Test
    fun `b silence from start - after 3 s ERR_STALL no_audio and session ended`() = runBlocking {
        val clock = VirtualClock()
        val t0 = clock.now()
        val src = FakeWavAudioSource(wav(), silenceFromMs = t0, nowMs = { clock.now() })
        val scope = newScope()
        val c = DictationCoordinator(scope, { src }, { FakeAsrEngine() }, FakeSink(), nowMs = { clock.now() })
        assertTrue("start must succeed", c.start())
        clock.pumpTo(t0 + 3_400L, stepMs = 100L, realDelayMs = 10L)
        assertFalse("the session must have ended after the watchdog", c.isActive)
        assertEquals("watchdog cause (stall/no_audio path)", "audio_error:stall", c.lastAbortReason)
        assertEquals("in silence no blocks received", 0, c.capBlocks())
        scope.cancel()
    }

    // (c) Dropout 3.5 s (1000..4500 ms): the watchdog fires EXACTLY ONCE —
    //     in the gap, but BEFORE the gap end (the source would have resumed
    //     delivery at 4500; by then the session is already dead).
    @Test
    fun `c dropout 3 5 s - ERR_STALL exactly once, still before the gap end`() = runBlocking {
        val clock = VirtualClock()
        val t0 = clock.now()
        val src = FakeWavAudioSource(
            wav(), gapFromMs = t0 + 1_000L, gapToMs = t0 + 4_500L, nowMs = { clock.now() },
        )
        val scope = newScope()
        val c = DictationCoordinator(scope, { src }, { FakeAsrEngine() }, FakeSink(), nowMs = { clock.now() })
        assertTrue("start must succeed", c.start())
        clock.pumpTo(t0 + 900L, stepMs = 25L, realDelayMs = 5L)
        assertTrue("before the gap blocks are delivered (are ${c.capBlocks()})", c.capBlocks() >= 80)
        assertEquals("before the gap still no abort", "-", c.lastAbortReason)
        // gap phase: stop limit 3000 → fires between t0+4000 and t0+4500.
        clock.pumpTo(t0 + 4_200L, stepMs = 100L, realDelayMs = 10L)
        assertFalse("session ended in the gap (before the gap end 4500)", c.isActive)
        assertEquals("audio_error:stall", c.lastAbortReason)
        // "Exactly once": the capture loop has returned — further pumping
        // changes nothing (no second firing, no continued running).
        clock.pumpTo(t0 + 6_000L, stepMs = 100L, realDelayMs = 5L)
        assertEquals("a second firing is impossible", "audio_error:stall", c.lastAbortReason)
        assertFalse("the session stays ended", c.isActive)
        scope.cancel()
    }

    // (d) End after 1.2 s + UP: the USER CASE from the analysis — abort_reason
    //     "final", audio_s ≈ 1.2 s.
    @Test
    fun `d end after 1 2 s - abort_reason final and audio_s around 1 2 (user case)`() = runBlocking {
        val clock = VirtualClock()
        val t0 = clock.now()
        val src = FakeWavAudioSource(wav(), stopAfterMs = t0 + 1_200L, nowMs = { clock.now() })
        val scope = newScope()
        val c = DictationCoordinator(scope, { src }, { FakeAsrEngine() }, FakeSink(), nowMs = { clock.now() })
        assertTrue("start must succeed", c.start())
        clock.pumpTo(t0 + 1_200L, stepMs = 20L, realDelayMs = 5L)
        c.stop() // "UP" — held 1.2 s, as in the export
        awaitIdle(c)
        assertEquals("final stop (user case)", "final", c.lastAbortReason)
        assertTrue("audio_s ≈ 1.2 (is ${c.audioMs} ms)", c.audioMs in 1_150L..1_250L)
        assertTrue("the finale ran through", c.lastStopOutcome is StopOutcome.FinalRan)
        scope.cancel()
    }

    // (e) F31: blocks keep running, but are SILENT (the −56-dBFS user case):
    //     after 3000 ms exactly ONE ERR_SILENT, session ended, cap_blocks > 0
    //     (in contrast to ERR_STALL: here blocks DO arrive).
    @Test
    fun `e continuously silent - after 3 s exactly one ERR_SILENT, session ended`() = runBlocking {
        val clock = VirtualClock()
        val t0 = clock.now()
        val src = FakeWavAudioSource(silentWav(8), nowMs = { clock.now() })
        val scope = newScope()
        val c = DictationCoordinator(scope, { src }, { FakeAsrEngine() }, FakeSink(), nowMs = { clock.now() })
        assertTrue("start must succeed", c.start())
        clock.pumpTo(t0 + 3_400L, stepMs = 100L, realDelayMs = 10L)
        assertFalse("the session must have ended after the silence watchdog", c.isActive)
        assertEquals("silence watchdog cause", "audio_error:silence", c.lastAbortReason)
        assertTrue("blocks WERE there (silence ≠ stall, are ${c.capBlocks()})", c.capBlocks() > 0)
        // exactly once: the capture loop has returned — further pumping changes nothing.
        clock.pumpTo(t0 + 6_000L, stepMs = 100L, realDelayMs = 5L)
        assertEquals("a second firing is impossible", "audio_error:silence", c.lastAbortReason)
        assertFalse("the session stays ended", c.isActive)
        scope.cancel()
    }

    // (f) F31: 1.5 s silence, then LOUD (sine): no ERR_SILENT — the first
    //     1.5 s stay under the 3-s limit, after that the loud stream holds back
    //     the silence clock. loud_s counts the loud time.
    @Test
    fun `f silent then loud - no ERR_SILENT, loud_s grows`() = runBlocking {
        val clock = VirtualClock()
        val t0 = clock.now()
        val src = FakeWavAudioSource(mixWav(silentMs = 1_500, loudMs = 5_000), nowMs = { clock.now() })
        val scope = newScope()
        val c = DictationCoordinator(scope, { src }, { FakeAsrEngine() }, FakeSink(), nowMs = { clock.now() })
        assertTrue("start must succeed", c.start())
        clock.pumpTo(t0 + 1_500L, stepMs = 50L, realDelayMs = 10L)
        assertEquals("in the first 1.5 s of silence: still no abort (< 3 s)", "-", c.lastAbortReason)
        clock.pumpTo(t0 + 6_500L, stepMs = 100L, realDelayMs = 20L)
        assertTrue("the session keeps running (loud blocks hold the silence clock)", c.isActive)
        assertEquals("no ERR_SILENT after the signal", "-", c.lastAbortReason)
        assertTrue("loud_s counts the loud time (is ${c.loudS()} s)", c.loudS() in 3.0..5.2)
        c.stop()
        awaitIdle(c)
        assertEquals("final stop", "final", c.lastAbortReason)
        scope.cancel()
    }

    // (g) F31 distinction: NO blocks (source dead) → still ERR_STALL
    //     (not ERR_SILENT) — the capBlocks > 0 condition separates the cases.
    @Test
    fun `g no blocks - still ERR_STALL, no ERR_SILENT`() = runBlocking {
        val clock = VirtualClock()
        val t0 = clock.now()
        val src = FakeWavAudioSource(wav(), silenceFromMs = t0, nowMs = { clock.now() })
        val scope = newScope()
        val c = DictationCoordinator(scope, { src }, { FakeAsrEngine() }, FakeSink(), nowMs = { clock.now() })
        assertTrue("start must succeed", c.start())
        clock.pumpTo(t0 + 3_400L, stepMs = 100L, realDelayMs = 10L)
        assertFalse("session ended", c.isActive)
        assertEquals("without blocks it stays ERR_STALL", "audio_error:stall", c.lastAbortReason)
        assertEquals("no blocks → no silence measurement", 0, c.capBlocks())
        scope.cancel()
    }

    // (h) F31 measurement: continuously loud stream (sine) → loud_s ≈ send duration.
    @Test
    fun `h loud stream - loud_s measures the loud time`() = runBlocking {
        val clock = VirtualClock()
        val t0 = clock.now()
        val src = FakeWavAudioSource(mixWav(silentMs = 0, loudMs = 3_000), nowMs = { clock.now() })
        val scope = newScope()
        val c = DictationCoordinator(scope, { src }, { FakeAsrEngine() }, FakeSink(), nowMs = { clock.now() })
        assertTrue("start must succeed", c.start())
        clock.pumpTo(t0 + 3_000L, stepMs = 50L, realDelayMs = 5L)
        assertTrue("blocks ran (are ${c.capBlocks()})", c.capBlocks() >= 280)
        // due-date off-by-one: block i is due FROM sessionStart+i×10 (inclusive)
        // → at the end of pumping 301 blocks are possible (3.01 s); the band below is derived.
        assertTrue("loud_s ≈ 3 (is ${c.loudS()} s)", c.loudS() in 2.9..3.05)
        c.stop()
        awaitIdle(c)
        scope.cancel()
    }

    // (i) F31b/REVIEW-P1 → F41/F43: 4 s LOUD, then stream silence →
    //     NO ERR_SILENT (silence DETECTION never starts after the first loud
    //     block — F31b stays), BUT the recording ends AUTOMATICALLY and
    //     REGULARLY after TRAILING_SILENCE_MS = 2.5 s of pause (final decode,
    //     no ERR_*) — before, the user had to end it manually (F41 repro
    //     symptom 1: 30 s of silence, the session kept running, FGS as well).
    @Test
    fun `i 4 s loud then 5 s pause - auto-final after 2 5 s of silence, no ERR_SILENT`() = runBlocking {
        val clock = VirtualClock()
        val t0 = clock.now()
        val src = FakeWavAudioSource(segmentWav(listOf(4_000 to true, 5_000 to false)), nowMs = { clock.now() })
        val scope = newScope()
        val c = DictationCoordinator(scope, { src }, { FakeAsrEngine() }, FakeSink(), nowMs = { clock.now() })
        assertTrue("start must succeed", c.start())
        clock.pumpTo(t0 + 4_000L, stepMs = 50L, realDelayMs = 10L)
        assertEquals("after 4 s loud: no abort", "-", c.lastAbortReason)
        clock.pumpTo(t0 + 6_400L, stepMs = 100L, realDelayMs = 20L)
        assertTrue("pause < 2.5 s: the session keeps running (F43)", c.isActive)
        assertEquals("no ERR_SILENT after the first loud block", "-", c.lastAbortReason)
        clock.pumpTo(t0 + 9_000L, stepMs = 100L, realDelayMs = 20L)
        awaitIdle(c)
        assertFalse("pause ≥ 2.5 s: automatic regular finish (F43)", c.isActive)
        assertEquals("auto-final, no ERR_SILENT/ERR_STALL", "final", c.lastAbortReason)
        assertTrue("auto-final: outcome FinalRan", c.lastStopOutcome is StopOutcome.FinalRan)
        scope.cancel()
    }

    // (j) F31b→F41/F43: 2.5 s silent, 0.5 s LOUD, then 5 s silent → NO ERR_SILENT
    //     (the everLoud FLAG gated and continues to permanently gate the silence
    //     DETECTION), but the stream silence now ends the session REGULARLY
    //     after 2.5 s (F43 auto-final) instead of running on forever.
    @Test
    fun `j silent loud silent - no ERR_SILENT, silence ends automatically regularly (F43)`() = runBlocking {
        val clock = VirtualClock()
        val t0 = clock.now()
        val src = FakeWavAudioSource(
            segmentWav(listOf(2_500 to false, 500 to true, 5_000 to false)), nowMs = { clock.now() },
        )
        val scope = newScope()
        val c = DictationCoordinator(scope, { src }, { FakeAsrEngine() }, FakeSink(), nowMs = { clock.now() })
        assertTrue("start must succeed", c.start())
        clock.pumpTo(t0 + 3_000L, stepMs = 100L, realDelayMs = 10L)
        assertTrue("at the 3-s limit: the session runs (loud block at 2.5 s)", c.isActive)
        clock.pumpTo(t0 + 5_400L, stepMs = 100L, realDelayMs = 20L)
        assertTrue("silence < 2.5 s: the session keeps running (F43)", c.isActive)
        clock.pumpTo(t0 + 8_000L, stepMs = 100L, realDelayMs = 20L)
        awaitIdle(c)
        assertFalse("silence ≥ 2.5 s: automatic regular finish (F43)", c.isActive)
        assertEquals("no ERR_SILENT (flag-gated) — regular auto-final", "final", c.lastAbortReason)
        assertTrue("auto-final: outcome FinalRan", c.lastStopOutcome is StopOutcome.FinalRan)
        scope.cancel()
    }

    // (k) F31b timing: continuously silent (NEVER loud) — BELOW the 3-s limit
    //     the session keeps running, AFTERWARDS exactly one ERR_SILENT (limit at ~3 s).
    @Test
    fun `k never loud - below 3 s the session runs, afterwards exactly one ERR_SILENT`() = runBlocking {
        val clock = VirtualClock()
        val t0 = clock.now()
        val src = FakeWavAudioSource(silentWav(8), nowMs = { clock.now() })
        val scope = newScope()
        val c = DictationCoordinator(scope, { src }, { FakeAsrEngine() }, FakeSink(), nowMs = { clock.now() })
        assertTrue("start must succeed", c.start())
        clock.pumpTo(t0 + 2_900L, stepMs = 50L, realDelayMs = 5L)
        assertTrue("below the 3-s limit: the session keeps running", c.isActive)
        assertEquals("below the limit: no abort", "-", c.lastAbortReason)
        clock.pumpTo(t0 + 3_400L, stepMs = 100L, realDelayMs = 10L)
        assertFalse("above the limit: session ended", c.isActive)
        assertEquals("ERR_SILENT (never loud, blocks running)", "audio_error:silence", c.lastAbortReason)
        // exactly once:
        clock.pumpTo(t0 + 5_000L, stepMs = 100L, realDelayMs = 5L)
        assertEquals("a second firing is impossible", "audio_error:silence", c.lastAbortReason)
        scope.cancel()
    }
}
