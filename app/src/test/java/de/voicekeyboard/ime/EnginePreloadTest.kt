package de.voicekeyboard.ime

import de.voicekeyboard.asr.AsrEngine
import de.voicekeyboard.asr.Hypothesis
import de.voicekeyboard.audio.AudioSource
import de.voicekeyboard.audio.PcmBlock
import de.voicekeyboard.audio.PcmWindow
import de.voicekeyboard.dictation.DictationState
import de.voicekeyboard.editor.EditorSink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * W package (vc34, debugger recommendation): engine warming — pre-warming on
 * the first IME show (W1), residency until shutdown (W2) and the 🐞 export
 * fields engine_load_ms / engine_resident / warm_hit / load_trigger /
 * engine_loads.
 *
 *  (a) first show ⇒ preload EXACTLY 1× (the fake engine counts loads); second
 *      show ⇒ 0 more; parallel preload() ⇒ single-flight (EXACTLY 1 load);
 *  (b) dictation start after preload ⇒ warm_hit=1, load_trigger=ime_show,
 *      start_ms WITHOUT load time (the fake load is artificially slow); the
 *      cold start in comparison loads itself (mic) and includes the load
 *      time;
 *  (c) a failed preload does NOT block the later start (the lazy fallback in
 *      the dictation start stays untouched).
 *
 * Rule 9: numbers/codes only — never model paths/content. Pure JVM tests
 * (the coordinator is framework-free; the service trigger itself is the pure
 * decision [preloadDue], pattern editorRestartAction).
 */
class EnginePreloadTest {

    /** Fake engine: counts load() runs, throws on a second call (single-flight
     *  canary), can fail on the first load() and delay it artificially (cold
     *  start measurement). transcribe returns empty (0 samples). */
    private class CountingEngine(
        private val loadSleepMs: Long = 0,
        private val failFirstLoad: Boolean = false,
    ) : AsrEngine {
        val loadCalls = AtomicInteger(0)
        @Volatile private var failedOnce = false
        @Volatile private var loaded = false

        override fun load(modelDir: File) {
            loadCalls.incrementAndGet()
            if (loaded) error("doppeltes load() — Single-Flight verletzt")
            if (failFirstLoad && !failedOnce) {
                failedOnce = true
                throw IllegalStateException("load-boom")
            }
            if (loadSleepMs > 0) Thread.sleep(loadSleepMs)
            loaded = true
        }

        override suspend fun transcribe(
            window: PcmWindow,
            sessionId: Long,
            runId: Long,
        ): Hypothesis = Hypothesis(
            tokenIds = IntArray(0),
            text = "",
            frameTimesMs = IntArray(0),
            sessionId = sessionId,
            runId = runId,
            audioSamples = window.samples.size,
            incomplete = false,
        )

        override fun close() {}
    }

    /** Shortest living source: start ok, never any data (the session ends via stop). */
    private class SilentSource : AudioSource {
        override fun start(sessionId: Long): Boolean = true
        override fun read(dst: ShortArray, timeoutMs: Int): Int = 0
        override fun stopAndDrain(): List<PcmBlock> = emptyList()
        override fun release() {}
    }

    /** Trivial sink: start/stop without output needs no editor. */
    private class NoopSink : EditorSink {
        override fun replaceBeforeCursor(replaceLength: Int, newText: String) {}
        override fun textBeforeCursor(maxChars: Int): CharSequence? = null
        override fun ownLength(): Int = -1
    }

    private fun newScope(): CoroutineScope = CoroutineScope(Dispatchers.Default)

    private fun shutdown(scope: CoroutineScope) {
        (scope.coroutineContext[kotlinx.coroutines.Job])?.cancel()
    }

    /** Model directory (the fake engine does not read it — non-null suffices). */
    private fun dir(): File = File(System.getProperty("java.io.tmpdir"), "vk-preload-model")

    private fun coordinator(
        scope: CoroutineScope,
        engine: CountingEngine,
    ) = DictationCoordinator(
        scope, { SilentSource() }, { engine }, NoopSink(), modelDir = dir(),
    )

    /** stop() + wait for the asynchronous final (pattern awaitIdle). */
    private fun stopAndWait(c: DictationCoordinator) {
        c.stop()
        val deadline = System.currentTimeMillis() + 3000
        while (c.state != DictationState.IDLE && System.currentTimeMillis() < deadline) {
            Thread.sleep(10)
        }
        assertEquals("stop() worker must end in IDLE", DictationState.IDLE, c.state)
    }

    // --- W4 (a): first show ⇒ preload EXACTLY 1×; second show ⇒ 0 more;
    //     parallel calls ⇒ single-flight (EXACTLY 1 load, no second call). ---

    @Test fun `preload loads exactly once - second show and parallel calls start no further load`() {
        val scope = newScope()
        val engine = CountingEngine()
        val c = coordinator(scope, engine)
        try {
            // first show (service: preloadDue(false) ⇒ exactly ONE preload call)
            assertTrue("the first show preload loads", preloadDue(false))
            assertTrue(c.preload())
            assertEquals("exactly 1 load on the first show", 1, engine.loadCalls.get())
            assertTrue("Engine resident (W2: until shutdown)", c.engineResident)
            assertEquals("ime_show", c.loadTrigger)

            // second show: the service trigger stays a no-op (flag), the
            // coordinator is likewise resident-idempotent — 0 further loads.
            assertFalse("second show: the trigger stays a no-op", preloadDue(true))
            assertTrue("second show: resident, no further load", c.preload())
            assertEquals("no further load on the second show", 1, engine.loadCalls.get())

            // parallel preload() (8 threads): single-flight gate — EXACTLY 1 load;
            // the fake engine throws on a second call (canary above).
            val engine2 = CountingEngine()
            val c2 = coordinator(scope, engine2)
            try {
                val threads = (1..8).map { kotlin.concurrent.thread { c2.preload() } }
                threads.forEach { it.join() }
                assertEquals("parallel load() impossible (single-flight)", 1, engine2.loadCalls.get())
                assertTrue("all callers see resident", c2.engineResident)
            } finally {
                c2.shutdown()
            }
        } finally {
            c.shutdown()
            shutdown(scope)
        }
    }

    // --- W4 (b): a dictation start after preload is WARM — warm_hit=1,
    //     load_trigger=ime_show, start_ms WITHOUT load time (fake load 500 ms). ---

    @Test fun `dictation start after preload is warm - warm_hit, trigger ime_show, start_ms without load time`() {
        val scope = newScope()
        val engine = CountingEngine(loadSleepMs = 500)
        val c = coordinator(scope, engine)
        try {
            assertTrue(c.preload())
            assertEquals(1, engine.loadCalls.get())
            assertTrue("engine_load_ms measures the fake load time (was ${c.engineLoadMs})", c.engineLoadMs >= 500)

            assertTrue("warm start", c.start())
            assertTrue(
                "start_ms WITHOUT load time — the engine was resident (was ${c.lastStartMs})",
                c.lastStartMs < 500,
            )
            assertEquals("no further load in the warm start", 1, engine.loadCalls.get())
            assertTrue("warm_hit: the start found the engine resident", c.warmHit)
            assertEquals("load_trigger stays ime_show", "ime_show", c.loadTrigger)
            assertEquals("engine_loads counts the one load", 1, c.engineLoads)
            stopAndWait(c)

            // comparison COLD START: without preload the start loads itself
            // (lazy fallback untouched, trigger mic) — start_ms INCLUDES the
            // load time.
            val engine2 = CountingEngine(loadSleepMs = 500)
            val c2 = coordinator(scope, engine2)
            try {
                assertTrue(c2.start())
                assertFalse("the cold start is not warm", c2.warmHit)
                assertEquals("mic", c2.loadTrigger)
                assertTrue(
                    "the cold start includes the load time (was ${c2.lastStartMs})",
                    c2.lastStartMs >= 500,
                )
                assertEquals("engine_loads counts the start load", 1, c2.engineLoads)
                stopAndWait(c2)
            } finally {
                c2.shutdown()
            }
        } finally {
            c.shutdown()
            shutdown(scope)
        }
    }

    // --- W4 (c): a failed preload does NOT block the later start —
    //     the lazy fallback in the dictation start loads afterwards (trigger mic). ---

    @Test fun `a failed preload does not block the later dictation start`() {
        val scope = newScope()
        val engine = CountingEngine(failFirstLoad = true)
        val c = coordinator(scope, engine)
        try {
            assertFalse("preload fails in a controlled way (false, no exception upward)", c.preload())
            assertFalse("engine not resident", c.engineResident)
            assertEquals("the counter counts only SUCCESSFUL loads", 0, c.engineLoads)

            // the later dictation start loads lazily — the failure does NOT block it.
            assertTrue("the start is NOT blocked by the preload failure", c.start())
            assertEquals("second load run (the first failed)", 2, engine.loadCalls.get())
            assertTrue("resident after the start", c.engineResident)
            assertEquals("trigger of the SUCCESSFUL load() = mic", "mic", c.loadTrigger)
            assertFalse("cold start: no warm_hit", c.warmHit)
            assertEquals("engine_loads counts only the success", 1, c.engineLoads)
            stopAndWait(c)
        } finally {
            c.shutdown()
            shutdown(scope)
        }
    }
}
