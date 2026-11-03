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
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * F13d (docs/REVIEW_F14_DIKTAT_KETTE.md): P1 — silent consumer death (an
 * editor-sync timeout from feedAudio killed the consumer job, capture kept
 * running, the unbounded channel grew, no report); P2-1 — onDictationStopped
 * read LIVE controller state that the next start() nulls; P2-2 — hyp_gap_ms
 * could go negative with a backwards-running clock.
 */
class ConsumerFaultTest {

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
        var failNextWithTimeout = false
        var nextText = "hallo"

        override fun load(modelDir: File) {}

        override suspend fun transcribe(window: PcmWindow, sessionId: Long, runId: Long): Hypothesis {
            if (failNextWithTimeout) {
                failNextWithTimeout = false
                throw IllegalStateException("Editor-Sync Timeout: test")
            }
            return Hypothesis(
                tokenIds = IntArray(0), text = nextText, frameTimesMs = IntArray(0),
                sessionId = sessionId, runId = runId,
                audioSamples = window.samples.size, incomplete = false,
            )
        }

        override fun close() {}
    }

    private class FakeSource : AudioSource {
        val released = AtomicBoolean(false)
        private val samplesPerRead = 4000
        override fun start(sessionId: Long): Boolean = true
        override fun read(dst: ShortArray, timeoutMs: Int): Int {
            val n = minOf(samplesPerRead, dst.size)
            // F31: fill the block with LOUD content (amplitude 12000 ≈ −9 dBFS) —
            // without content dst (0) would be silent and the F31 silence watchdog
            // would have brought a new (wrong) abort path into this test under
            // load > 3 s.
            java.util.Arrays.fill(dst, 0, n, 12_000.toShort())
            return n // immediately available — drives the consumer along briskly
        }
        override fun stopAndDrain(): List<PcmBlock> = emptyList()
        override fun release() { released.set(true) }
    }

    private fun seconds(n: Int): PcmBlock = PcmBlock(0, 0, ShortArray(n * 16_000))

    // (a) P1: feedAudio throws the editor-sync timeout IllegalStateException →
    //     the coordinator ends CONTROLLED: error code visible, capture no longer
    //     running (active=false), the outcome is visible as an Outcome.
    @Test
    fun `Editor-Sync-Timeout does not silently kill the consumer - coordinator ends controlled`() = runBlocking {
        val engine = FakeEngine()
        engine.failNextWithTimeout = true // the first transcription (after 2 s of audio) breaks the sync
        val sink = RecordingSink()
        val routeCalls = AtomicInteger(0)
        val scope = CoroutineScope(Dispatchers.Default)
        val coordinator = DictationCoordinator(
            scope = scope,
            audioSourceFactory = { FakeSource() },
            engineFactory = { engine },
            sink = sink,
            tracker = OwnershipTracker(),
            tickSamples = 4_000, // every fake read triggers a flush immediately
        )
        // routeObserver counts along that the route is released after the controlled end
        val coordWithRoute = DictationCoordinator(
            scope = scope,
            audioSourceFactory = { FakeSource() },
            engineFactory = { FakeEngine().apply { failNextWithTimeout = true } },
            sink = RecordingSink(),
            tracker = OwnershipTracker(),
            tickSamples = 4_000,
            routeObserver = { on -> if (on) routeCalls.incrementAndGet() else routeCalls.decrementAndGet() },
        )

        assertTrue(coordinator.start())
        // wait until the error has run through the chain (up to 5 s, then red):
        withTimeout(5_000) {
            while (coordinator.isActive) kotlinx.coroutines.delay(20)
        }
        assertFalse("capture must not continue after the consumer error", coordinator.isActive)
        assertTrue(
            "the error code must be visible (rule: every caught case sets a reason)",
            coordinator.visibleError != null,
        )
        // Flaky decision 2026-11-01 (BAU 23/24, F31, F31b, BAU 26): the outcome
        // is set on the worker side and lags the assert by a few coroutine
        // dispatches — under parallel suite load the immediate check (red at
        // 0.012 s test time) read too early. Deterministic: poll for the
        // outcome for up to 2 s in 10 ms steps. If it stays null longer, that
        // is a REAL product fault — the deadline is deliberately NOT raised.
        // F50 (load flaky stabilization): the old poll checked for null — but
        // lastStopOutcome already carries the IdleNoOp sentinel from
        // construction on (NEVER null), the poll was a no-op and the assert
        // raced ahead of the NoFinal write in the consumer catch under suite
        // load (then it read the initial IdleNoOp). Deterministic: poll for the
        // EXPECTED end state (fake synchronization via state, not real-time
        // patience); assertions unchanged, deadline unchanged (2 s — a real
        // product fault still times out red).
        withTimeout(2_000) {
            while (coordinator.lastStopOutcome !is StopOutcome.NoFinal) {
                kotlinx.coroutines.delay(10)
            }
        }
        // the outcome is visible as a StopOutcome — no silent death:
        val outcome = coordinator.lastStopOutcome
        assertTrue("outcome must carry NoFinal with an error code", outcome is StopOutcome.NoFinal)
        assertEquals("editor_sync_timeout", (outcome as StopOutcome.NoFinal).errorCode)
        // F56 (vc33 device finding): the FINE drift code must NOT be masked by
        // the generic consumer:<class> — visibleError and lastAbortRule
        // (stop_cause) carry editor_sync_timeout (F1 contract: mapVisibleError
        // shows the real cause; previously the device log showed
        // "visibleError=consumer:IllegalStateException" → else fallback).
        assertEquals("F56: visibleError unmasked", "editor_sync_timeout", coordinator.visibleError)
        assertEquals("F56: abort reason fine-grained (stop_cause family)", "editor_sync_timeout", coordinator.lastAbortReason)

        // second coordinator with routeObserver: the route is released at the end
        assertTrue(coordWithRoute.start())
        withTimeout(5_000) { while (coordWithRoute.isActive) kotlinx.coroutines.delay(20) }
        assertEquals("routeObserver must be off after the controlled end", 0, routeCalls.get())
        coordWithRoute.shutdown()
        coordinator.shutdown()
    }

    // (b) after the error stop() returns an Outcome that carries the error —
    //     NO IdleNoOp, no silent IDLE.
    @Test
    fun `stop after Editor-Sync-Timeout yields NoFinal with error code`() = runBlocking {
        val f = FakeEngine().apply { failNextWithTimeout = true }
        val controller = DictationController(f, RecordingSink())
        assertTrue(controller.start())
        var thrown: IllegalStateException? = null
        try {
            controller.feedAudio(seconds(3))
        } catch (e: IllegalStateException) {
            thrown = e // the throw is the contract: the coordinator can react
        }
        assertTrue(thrown != null)
        assertEquals(DictationState.IDLE, controller.state)
        assertTrue("fail() must preserve the error until it becomes visible", controller.failedThisSession)

        val outcome = controller.stop() // release after the fail
        assertTrue("no silent no-op", outcome !is StopOutcome.IdleNoOp)
        assertTrue(outcome is StopOutcome.NoFinal)
        assertEquals("editor_sync_timeout", (outcome as StopOutcome.NoFinal).errorCode)
    }

    // (c) P2-1: snapshot — after stop(), start() is called again (state
    //     nulled); the verdict on the OLD session stays correct in the outcome.
    @Test
    fun `Outcome is a snapshot - a new start does not falsify the old verdict`() = runBlocking {
        val f = FakeEngine().apply { failNextWithTimeout = true }
        val controller = DictationController(f, RecordingSink())
        assertTrue(controller.start())
        try {
            controller.feedAudio(seconds(3))
        } catch (e: IllegalStateException) {
            // Vertrag: Wurf nach oben
        }
        val oldOutcome = controller.stop() // NoFinal with error code (snapshot A)

        // "release quickly, press again immediately": session B starts and NULLS
        // the live state (failedThisSession=false, lastErrorCode=null):
        assertTrue(controller.start())
        assertFalse(controller.failedThisSession)
        assertEquals(null, controller.lastErrorCode)

        // the verdict on session A stays correct — ONLY the snapshot counts:
        assertTrue(oldOutcome is StopOutcome.NoFinal)
        assertTrue("no false checkmark for the old, failed session",
            oldOutcome !is StopOutcome.FinalRan)
        assertEquals("editor_sync_timeout", (oldOutcome as StopOutcome.NoFinal).errorCode)
        assertTrue("error stays visible in the snapshot", (oldOutcome as StopOutcome.NoFinal).failedWith)
    }

    // (d) P2-2: hyp_gap_ms is never negative — injected backwards-running clock.
    @Test
    fun `hyp_gap_ms is never negative even with a backwards-running clock`() = runBlocking {
        val ticks = AtomicInteger(0)
        // backwards clock: first measurement (hypothesis) 2e9 ns, then 1e9 ns.
        val backwardsClock = { if (ticks.incrementAndGet() == 1) 2_000_000_000L else 1_000_000_000L }
        val controller = DictationController(FakeEngine(), RecordingSink(), clock = backwardsClock)
        assertTrue(controller.start())
        controller.feedAudio(seconds(3)) // produces a hypothesis → lastHypAt = 2e9
        val gap = controller.currentHypGapMs()
        assertTrue("diagnostic value must be plausible, never negative (was $gap)", gap >= 0)
        assertEquals(" coerceAtLeast(0) clamps the backwards count to 0", 0L, gap)
    }

    // (e) normal path unchanged: the final runs, FinalRan without an error.
    @Test
    fun `normal path unchanged - FinalRan without error and with text`() = runBlocking {
        val sink = RecordingSink()
        val controller = DictationController(FakeEngine(), sink)
        assertTrue(controller.start())
        controller.feedAudio(seconds(3))
        val outcome = controller.stop()
        assertTrue(outcome is StopOutcome.FinalRan)
        assertFalse((outcome as StopOutcome.FinalRan).failedWith)
        assertEquals(null, outcome.finalError)
        assertTrue("text must have been written", sink.text.contains("hallo"))
    }

    // (f) F13e: editor_sync_timeout maps to the NEW key
    //     dictation_error_editor_busy (fixed target key, checked via R
    //     reflection — JVM without Android runtime); unknown codes keep falling
    //     through to the generic fallback (mapErrorTextRes == null).
    @Test
    fun `editor_sync_timeout maps to dictation_error_editor_busy - unknowns stay generic`() {
        val res = de.voicekeyboard.ime.VoiceKeyboardService.mapErrorTextRes("editor_sync_timeout")
        assertEquals(de.voicekeyboard.R.string.dictation_error_editor_busy, res)
        // fixed target key: the NAME dictation_error_editor_busy belongs to the
        // R constant (not just any generic text).
        val field = Class.forName("de.voicekeyboard.R\$string").fields
            .first { it.getInt(null) == res }
        assertEquals("dictation_error_editor_busy", field.name)
        // unknown codes stay with the generic fallback (no editor-busy):
        assertEquals(null, de.voicekeyboard.ime.VoiceKeyboardService.mapErrorTextRes("asr_partial_error"))
        assertEquals(null, de.voicekeyboard.ime.VoiceKeyboardService.mapErrorTextRes("unknown"))
        assertEquals(null, de.voicekeyboard.ime.VoiceKeyboardService.mapErrorTextRes("consumer=IllegalStateException"))
    }
}
