package de.voicekeyboard.ime

import de.voicekeyboard.asr.AsrEngine
import de.voicekeyboard.asr.Hypothesis
import de.voicekeyboard.audio.AudioErrorListener
import de.voicekeyboard.audio.AudioSource
import de.voicekeyboard.audio.PcmBlock
import de.voicekeyboard.audio.PcmWindow
import de.voicekeyboard.dictation.DictationState
import de.voicekeyboard.editor.EditorSink
import kotlinx.coroutines.CoroutineScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** Fake: plays pre-defined blocks asynchronously; optionally with an error. */
private class FakeAudioSource(
    blocks: List<ShortArray>,
    private val failOnStart: Boolean = false,
    private val errorAfterBlocks: Boolean = false,
) : AudioSource {
    val startCalls = AtomicInteger(0)
    val released = AtomicInteger(0)
    val queue = ConcurrentLinkedQueue<ShortArray>()
    private val started = AtomicBoolean(false)
    private val stopping = AtomicBoolean(false)
    var errorListener: AudioErrorListener? = null

    init {
        blocks.forEach { queue.add(it) }
    }

    override fun start(sessionId: Long): Boolean {
        if (failOnStart) return false
        if (!started.compareAndSet(false, true)) return false
        startCalls.incrementAndGet()
        if (errorAfterBlocks) {
            Thread {
                Thread.sleep(120)
                errorListener?.onError("invalidOperation", "test")
            }.apply { isDaemon = true }.start()
        }
        return true
    }

    private var pending: ShortArray? = null

    override fun read(dst: ShortArray, timeoutMs: Int): Int {
        if (stopping.get()) return -1
        var head = pending
        if (head == null) head = queue.poll()
        if (head != null) {
            val n = minOf(dst.size, head.size)
            System.arraycopy(head, 0, dst, 0, n)
            pending = if (n < head.size) head.copyOfRange(n, head.size) else null
            return n
        }
        Thread.sleep(2)
        return 0 // timeout/no data
    }

    override fun stopAndDrain(): List<PcmBlock> {
        stopping.set(true)
        val out = mutableListOf<PcmBlock>()
        while (true) {
            val rest = queue.poll() ?: break
            out.add(PcmBlock(-1, -1, rest))
        }
        return out
    }

    override fun release() {
        released.incrementAndGet()
    }
}

/** Fake engine: counts calls, sees the audio windows. */
private class FakeAsrEngine : AsrEngine {
    val transcribeCalls = AtomicInteger(0)
    val seenSampleCounts = CopyOnWriteArrayList<Int>()
    @Volatile var failOnTranscribe = false
    override fun load(modelDir: File) {}
    override suspend fun transcribe(window: PcmWindow, sessionId: Long, runId: Long): Hypothesis {
        transcribeCalls.incrementAndGet()
        if (failOnTranscribe) throw IllegalStateException("engine-boom")
        seenSampleCounts.add(window.samples.size)
        val text = "x".repeat((window.audioSeconds * 3).toInt().coerceAtLeast(1))
        return Hypothesis(
            IntArray(1), text, IntArray(1), sessionId, runId,
            window.samples.size, incomplete = false,
        )
    }

    val closeCalls = java.util.concurrent.atomic.AtomicInteger(0)
    override fun close() {
        closeCalls.incrementAndGet()
    }
}

/** Fake sink: logs all write operations. */
private class FakeSink : EditorSink {
    val writes = CopyOnWriteArrayList<String>()
    override fun replaceBeforeCursor(replaceLength: Int, newText: String) {
        writes.add("repl:$replaceLength:$newText")
    }

    override fun textBeforeCursor(maxChars: Int): CharSequence? =
        writes.joinToString(separator = "").takeLast(maxChars).ifEmpty { null }

    override fun ownLength(): Int = 0
}

/** F28b1 fake: every read advances a virtual clock by [stepMs]; optionally
 *  delivers one block per read (paced case) or 0 forever (stall case). */
private class StallTestSource(
    private val deliver: Boolean,
    private val stepMs: Long,
    private val advance: (Long) -> Unit,
    private val silent: Boolean = false, // F34: silent blocks (RMS 0 < SILENCE_RMS)
) : AudioSource {
    val reads = AtomicInteger(0)
    // F31: block loudness above the SILENCE threshold (0.01) — this test checks
    // the pace (stall), not the silence; silent blocks would now trigger the
    // F31 silence abort and make the paced case untestable.
    // F34: silent=true delivers zeros (RMS 0) for the ERR_SILENT case.
    private val data: ShortArray =
        if (silent) ShortArray(1600) { 0 } else ShortArray(1600) { 12_000 }

    override fun start(sessionId: Long): Boolean = true

    override fun read(dst: ShortArray, timeoutMs: Int): Int {
        reads.incrementAndGet()
        advance(stepMs)
        if (!deliver) return 0 // timeout/no data (stall case)
        val n = minOf(dst.size, data.size)
        System.arraycopy(data, 0, dst, 0, n)
        Thread.sleep(2)
        return n
    }

    override fun stopAndDrain(): List<PcmBlock> = emptyList()
    override fun release() {}
}

/** A1 (PLAN_V0133 §1): cold-start fake source — read #0 delivers NO block
 *  (start latency [firstStepMs] after src.start()); afterwards the next
 *  [deliverReads] reads each deliver one LOUD block (RMS > SILENCE_RMS) at
 *  the [stepMs] pace; deliverReads <= 0 = endless. After the delivery phase
 *  the source returns 0 permanently (source death) — EVERY read advances
 *  the injected clock, even in death (the watchdog window runs virtually). */
private class ColdStartSource(
    private val firstStepMs: Long,
    private val stepMs: Long,
    private val deliverReads: Int,
    private val advance: (Long) -> Unit,
) : AudioSource {
    private val index = AtomicInteger(0)
    private val data = ShortArray(1600) { 12_000 } // loud: RMS ≈ 0.37 > SILENCE_RMS

    override fun start(sessionId: Long): Boolean = true

    override fun read(dst: ShortArray, timeoutMs: Int): Int {
        val i = index.getAndIncrement()
        advance(if (i == 0) firstStepMs else stepMs)
        val deliver = i >= 1 && (deliverReads <= 0 || i < 1 + deliverReads)
        if (!deliver) {
            Thread.sleep(2)
            return 0 // timeout/no data (start latency or source death)
        }
        val n = minOf(dst.size, data.size)
        System.arraycopy(data, 0, dst, 0, n)
        Thread.sleep(2)
        return n
    }

    override fun stopAndDrain(): List<PcmBlock> = emptyList()
    override fun release() {}
}

/** A1 (PLAN_V0133 §1): fake engine whose load() advances the injected clock
 *  by [loadMs] — models the cold start (model load ~4.5 s) on the watchdog
 *  clock. transcribe/close like FakeAsrEngine (lengths only, rule 9). */
private class SlowLoadEngine(
    private val loadMs: Long,
    private val advance: (Long) -> Unit,
) : AsrEngine {
    override fun load(modelDir: File) {
        advance(loadMs)
    }

    override suspend fun transcribe(window: PcmWindow, sessionId: Long, runId: Long): Hypothesis {
        val text = "x".repeat((window.audioSeconds * 3).toInt().coerceAtLeast(1))
        return Hypothesis(
            IntArray(1), text, IntArray(1), sessionId, runId,
            window.samples.size, incomplete = false,
        )
    }

    override fun close() {}
}

class DictationCoordinatorTest {

    private fun newScope() = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.Dispatchers.Default,
    )

    private fun shutdown(scope: CoroutineScope) {
        (scope.coroutineContext[kotlinx.coroutines.Job])?.cancel()
    }

    private fun block(value: Byte, n: Int) = ShortArray(n) { ((value + it % 3).toInt()).toShort() }

    // (1) a double start is idempotent (no restart of the source)
    @Test fun `double start is idempotent`() {
        val src = FakeAudioSource(listOf(ShortArray(16000)))
        val scope = newScope()
        val c = DictationCoordinator(scope, { src }, { FakeAsrEngine() }, FakeSink())
        assertTrue(c.start())
        assertFalse("a second start() must return false", c.start())
        assertEquals("the source may be started only once", 1, src.startCalls.get())
        c.stop()
        awaitIdle(c)
        shutdown(scope)
    }

    // (2) stop without a start is a no-op
    @Test fun `stop without a start is a NoOp`() {
        val c = DictationCoordinator(newScope(), { FakeAudioSource(emptyList()) }, { FakeAsrEngine() }, FakeSink())
        c.stop()
        assertEquals(DictationState.IDLE, c.state)
        assertNull(c.lastError)
    }

    // (F26a) the abort reason is remembered; a later stop does NOT overwrite it
    @Test fun `abort reason is remembered and not overwritten by stop`() {
        val scope = newScope()
        val c = DictationCoordinator(scope, { FakeAudioSource(listOf(ShortArray(16000))) }, { FakeAsrEngine() }, FakeSink())
        assertEquals("-", c.lastAbortReason)
        assertTrue(c.start())
        assertEquals("start() resets to -", "-", c.lastAbortReason)
        c.abort("editor_change")
        assertEquals("editor_change", c.lastAbortReason)
        c.stop() // active=false — must not overwrite the abort reason
        assertEquals("editor_change", c.lastAbortReason)
        awaitIdle(c)
        shutdown(scope)
    }

    /** Waits (test only!) until the asynchronous stop() worker is finished. */
    private fun awaitIdle(c: DictationCoordinator, timeoutMs: Long = 3000) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (c.state != DictationState.IDLE && System.currentTimeMillis() < deadline) {
            Thread.sleep(10)
        }
        assertEquals("stop() worker must end in IDLE", DictationState.IDLE, c.state)
    }

    // (3) blocks reach the controller in order — provable via the
    //     sample counts of the transcribe windows (monotonically growing, complete)
    @Test fun `blocks reach the controller completely and in order`() {
        // 3 blocks of 8000 → ticker 8000: one feedAudio per block aggregate
        val src = FakeAudioSource(listOf(block(1, 8000), block(2, 8000), block(3, 8000)))
        val engine = FakeAsrEngine()
        val c = DictationCoordinator(newScope(), { src }, { engine }, FakeSink(), tickSamples = 8000)
        assertTrue(c.start())
        // Partials need ≥2 s per the controller → fill the total preventively:
        // simply wait until the consumer has consumed everything and then stop() (final).
        val deadline = System.currentTimeMillis() + 2000
        while (System.currentTimeMillis() < deadline && !src.queue.isEmpty()) Thread.sleep(10)
        c.stop()
        awaitIdle(c)
        // the final transcribes the WHOLE BUFFER: 24000 samples in ONE window
        assertTrue("at least one transcribe", engine.transcribeCalls.get() >= 1)
        assertEquals(
            "the final must run over all 3 blocks (24000 samples)",
            24000,
            engine.seenSampleCounts.max(),
        )
        assertNull(c.lastError)
        shutdown(newScope())
    }

    // (4) stop after < 2 s of audio still final-decodes (the guard applies only to partials)
    @Test fun `stop after a short recording still final-decodes`() {
        val src = FakeAudioSource(listOf(ShortArray(8000))) // 0.5 s
        val engine = FakeAsrEngine()
        val c = DictationCoordinator(newScope(), { src }, { engine }, FakeSink())
        assertTrue(c.start())
        val before = engine.transcribeCalls.get()
        Thread.sleep(200) // no partial possible (< 2 s guard)
        assertEquals("no partial before 2 s", before, engine.transcribeCalls.get())
        c.stop()
        awaitIdle(c)
        assertTrue("the final decode must run", engine.transcribeCalls.get() > before)
        assertEquals(DictationState.IDLE, c.state)
        shutdown(newScope())
    }

    // (5) AudioSource error → abort state, editor unchanged
    @Test fun `AudioSource error leads to abort without writing`() {
        val src = FakeAudioSource(emptyList(), errorAfterBlocks = true)
        val sink = FakeSink()
        val c = DictationCoordinator(newScope(), { src }, { FakeAsrEngine() }, sink)
        src.errorListener = c.errorListener // wiring as in the IME
        assertTrue(c.start())
        val latch = CountDownLatch(1)
        val watcher = Thread {
            val deadline = System.currentTimeMillis() + 3000
            while (c.lastError == null && System.currentTimeMillis() < deadline) Thread.sleep(10)
            latch.countDown()
        }.apply { start() }
        latch.await(5, TimeUnit.SECONDS)
        assertEquals(DictationState.IDLE, c.state)
        assertNotEquals("error code must be set", null, c.lastError)
        assertEquals("the editor must not have received anything", 0, sink.writes.size)
        shutdown(newScope())
    }

    // (6) editor change during RECORDING → no writing afterwards
    @Test fun `editor change during RECORDING stops writing`() {
        // 2.5 s of audio, ticker 8000 → partials write
        val src = FakeAudioSource(listOf(ShortArray(20000)))
        val engine = FakeAsrEngine()
        val sink = FakeSink()
        val c = DictationCoordinator(newScope(), { src }, { engine }, sink, tickSamples = 8000)
        assertTrue(c.start())
        val deadline = System.currentTimeMillis() + 3000
        while (sink.writes.isEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(10)
        c.onEditorChanged() // discard ownership
        val writesAtChange = sink.writes.size
        // further partials must NOT write anymore
        val deadline2 = System.currentTimeMillis() + 800
        while (System.currentTimeMillis() < deadline2) Thread.sleep(50)
        assertEquals("no writing after the editor switch", writesAtChange.toLong(), sink.writes.size.toLong())
        c.abort()
        shutdown(newScope())
    }

    // (7) the R-2 error state survives stop()/release (visibleError sticky)
    @Test fun `error state stays visible after stop`() {
        // the engine throws on every transcribe ⇒ controller.lastErrorCode set
        val src = FakeAudioSource(listOf(ShortArray(16000))) // 1 s → the partial guard does not apply,
        // but feedAudio only decodes from 2 s on — we force the error via the final:
        val engine = FakeAsrEngine().apply { failOnTranscribe = true }
        val c = DictationCoordinator(newScope(), { src }, { engine }, FakeSink(), tickSamples = 16000)
        assertTrue(c.start())
        c.stop()
        awaitIdle(c)
        assertTrue("the error must be visible", c.visibleError != null)
        // "release" (repeated updateStatus equivalent): visibleError stays active
        assertTrue("sticky: the error stays until the next deliberate action", c.visibleError != null)
    }

    // (8) success after an error clears the error (new session, no exception)
    @Test fun `success after an error clears visibleError`() {
        val engine = FakeAsrEngine().apply { failOnTranscribe = true }
        val c = DictationCoordinator(newScope(), { FakeAudioSource(listOf(ShortArray(16000))) }, { engine }, FakeSink())
        assertTrue(c.start())
        c.stop()
        awaitIdle(c)
        assertTrue(c.visibleError != null)
        // fix the error cause + a new conscious action (mic down → start)
        engine.failOnTranscribe = false
        val c2start = c.start() // new session, new audio
        if (c2start) {
            c.stop()
            awaitIdle(c)
        }
        // after a success visibleError (controller.lastErrorCode) is null again
        assertTrue("no error after a successful final", c.visibleError == null)
    }

    // (9) shutdown() is idempotent and closes exactly once (N-3/C-4)
    @Test fun `shutdown is idempotent and closes exactly once`() {
        val engine = FakeAsrEngine()
        val c = DictationCoordinator(newScope(), { FakeAudioSource(emptyList()) }, { engine }, FakeSink())
        c.shutdown()
        c.shutdown() // second call: no-op
        c.shutdown() // third call: no-op
        assertEquals("engine closed exactly once", 1, engine.closeCalls.get())
    }

    // (10) editor writes run on the injected executor (N-1, provable without a Looper)
    @Test fun `editor writes run on the injected executor`() {
        val executorThread = java.util.concurrent.atomic.AtomicReference<String?>(null)
        val directExecutor = java.util.concurrent.Executor { runnable ->
            executorThread.set(Thread.currentThread().name)
            runnable.run()
        }
        val sink = de.voicekeyboard.editor.InputConnectionSink(
            connectionProvider = { null }, // editor gone → EditorUnavailableException, but the executor ran
            editExecutor = directExecutor,
        )
        try {
            sink.replaceBeforeCursor(0, "x")
            org.junit.Assert.fail("EditorUnavailableException expected")
        } catch (e: de.voicekeyboard.editor.EditorUnavailableException) {
            // proven: replaceBeforeCursor was executed via the executor
            org.junit.Assert.assertNotNull("the executor must have run", executorThread.get())
        }
    }

    // F28b1 (a): no blocks > 3000 ms → stall error EXACTLY ONCE + clean
    // session end. (Exactly-once structurally: single-thread loop, return after
    // firing — the read counter proves the loop has ended and no second error
    // can arise.)
    @Test fun `F28b1 without blocks over 3000 ms triggers exactly one stall abort`() {
        // the virtual clock starts at 1,000,000 (NOT 0 — 0 is the
        // "never started" sentinel of lastBlockAtMs; the production clock
        // (nanoTime/1e6) never returns 0).
        val base = 1_000_000L
        val now = AtomicLong(base)
        val src = StallTestSource(deliver = false, stepMs = 1000, advance = { now.addAndGet(it) })
        val scope = newScope()
        val c = DictationCoordinator(scope, { src }, { FakeAsrEngine() }, FakeSink(), nowMs = { now.get() })
        assertTrue(c.start())
        val deadline = System.currentTimeMillis() + 3000
        while (c.lastAbortReason != "audio_error:stall" && System.currentTimeMillis() < deadline) {
            Thread.sleep(5)
        }
        assertEquals("stall must abort the session", "audio_error:stall", c.lastAbortReason)
        assertEquals("controlled error code, no audio content", "stall:no_audio", c.lastError)
        // abort() sets the reason BEFORE controller.abort() makes it IDLE — wait briefly.
        val deadline2 = System.currentTimeMillis() + 3000
        while (c.state != DictationState.IDLE && System.currentTimeMillis() < deadline2) {
            Thread.sleep(5)
        }
        assertEquals(DictationState.IDLE, c.state)
        assertFalse("the session must have ended", c.isActive)
        assertTrue("stall duration readable after the abort (export)", c.capStallMs() >= 3000)
        assertEquals("no blocks in this session", 0, c.capBlocks())
        val readsAtAbort = src.reads.get()
        Thread.sleep(50)
        assertEquals("capture loop ended — no repeated error", readsAtAbort, src.reads.get())
        shutdown(scope)
    }

    // F28b1 (b): blocks at a 500 ms pace → the stall clock is constantly reset,
    // no stall error, the session survives virtually far more than 3000 ms.
    @Test fun `F28b1 blocks at a 500 ms pace trigger no stall`() {
        // the virtual clock starts at 1,000,000 (see test a) — 0 is reserved.
        val base = 1_000_000L
        val now = AtomicLong(base)
        val src = StallTestSource(deliver = true, stepMs = 500, advance = { now.addAndGet(it) })
        val scope = newScope()
        val c = DictationCoordinator(scope, { src }, { FakeAsrEngine() }, FakeSink(), nowMs = { now.get() })
        assertTrue(c.start())
        val deadline = System.currentTimeMillis() + 3000
        while (now.get() < base + 10_000 && System.currentTimeMillis() < deadline) Thread.sleep(10)
        assertTrue("virtual time must exceed 3 s by far", now.get() >= base + 10_000)
        assertFalse("no stall abort", c.lastAbortReason == "audio_error:stall")
        assertNull("no error code", c.lastError)
        assertTrue("session keeps running", c.isActive)
        assertTrue("stall stays below the boundary (500-ms clock)", c.capStallMs() <= 1000)
        assertTrue("blocks are being counted", c.capBlocks() >= 15)
        c.stop()
        awaitIdle(c)
        shutdown(scope)
    }

    // F28b2 (a): start() resets the session end diagnostics fresh — the export
    // never shows events/states of the PREVIOUS session.
    @Test fun `F28b2 start clears mic_rel and sets status_shown to ready`() {
        val scope = newScope()
        val c = DictationCoordinator(scope, { FakeAudioSource(listOf(ShortArray(16000))) }, { FakeAsrEngine() }, FakeSink())
        // simulate old values (e.g. fields that survived the last session)
        c.setMicRel("holdcancel:999")
        c.setStatusShown("error")
        assertTrue(c.start())
        assertEquals("mic_rel is cleared on start()", "", c.micRel())
        assertEquals("status_shown is set to ready on start()", "ready", c.statusShown())
        c.stop()
        awaitIdle(c)
        shutdown(scope)
    }

    // F28b2 (b): setter/getter round trip — the controlled codes arrive
    // unchanged in the export (rule 9: codes/ms only, no content).
    @Test fun `F28b2 setter-getter round trip returns the codes unchanged`() {
        val c = DictationCoordinator(newScope(), { FakeAudioSource(emptyList()) }, { FakeAsrEngine() }, FakeSink())
        c.setMicRel("up:1234")
        assertEquals("up:1234", c.micRel())
        c.setMicRel("holdcancel:5000")
        assertEquals("holdcancel:5000", c.micRel())
        for (code in listOf("ready", "dictat", "finalizing", "error")) {
            c.setStatusShown(code)
            assertEquals(code, c.statusShown())
        }
        // sessionStartedAtMs: 0 without a session, > 0 after start()
        assertEquals(0L, c.sessionStartedAtMs)
    }

    // F34/P2-1 (REVIEW_F29_F33, user bug "green hangs"): a watchdog abort
    // (ERR_STALL) must fire the service observer (onAborted) — it sets the
    // mic view to neutral. Tests the coordinator boundary transition: the
    // observer fires exactly once with the reason, afterwards the session is
    // IDLE, and the view derivation (micUiRecording — what VKS syncMicUi reads)
    // is neutral.
    @Test fun `F34 ERR_STALL abort fires onAborted and the mic state is neutral`() {
        val base = 1_000_000L
        val now = AtomicLong(base)
        val src = StallTestSource(deliver = false, stepMs = 1000, advance = { now.addAndGet(it) })
        val scope = newScope()
        val c = DictationCoordinator(scope, { src }, { FakeAsrEngine() }, FakeSink(), nowMs = { now.get() })
        val aborts = java.util.concurrent.CopyOnWriteArrayList<String>()
        c.onAborted = { aborts.add(it) }
        assertTrue(c.start())
        val deadline = System.currentTimeMillis() + 3000
        while (c.lastAbortReason != "audio_error:stall" && System.currentTimeMillis() < deadline) {
            Thread.sleep(5)
        }
        assertEquals("stall must abort the session", "audio_error:stall", c.lastAbortReason)
        assertEquals("onAborted fires exactly once with the reason", listOf("audio_error:stall"), aborts)
        assertEquals("session is IDLE", DictationState.IDLE, c.state)
        assertFalse("the mic view after a watchdog abort is neutral", micUiRecording(c.state))
        shutdown(scope)
    }

    // F34/P2-1 (b): ERR_SILENT (the session was never loud, blocks flow) — the
    // same end path as ERR_STALL: the observer fires, the view derivation
    // becomes neutral. (Blocks are delivered loudly, but all below the
    // SILENCE threshold.)
    @Test fun `F34 ERR_SILENT abort (never loud) fires onAborted and the mic state is neutral`() {
        val base = 1_000_000L
        val now = AtomicLong(base)
        val src = StallTestSource(deliver = true, stepMs = 500, silent = true, advance = { now.addAndGet(it) })
        val scope = newScope()
        val c = DictationCoordinator(scope, { src }, { FakeAsrEngine() }, FakeSink(), nowMs = { now.get() })
        val aborts = java.util.concurrent.CopyOnWriteArrayList<String>()
        c.onAborted = { aborts.add(it) }
        assertTrue(c.start())
        val deadline = System.currentTimeMillis() + 3000
        while (c.lastAbortReason != "audio_error:silence" && System.currentTimeMillis() < deadline) {
            Thread.sleep(5)
        }
        assertEquals("silence must abort the session", "audio_error:silence", c.lastAbortReason)
        assertTrue("blocks flowed (i.e. silence, not stall)", c.capBlocks() >= 5)
        assertEquals("onAborted fires exactly once with the reason", listOf("audio_error:silence"), aborts)
        assertEquals("session is IDLE", DictationState.IDLE, c.state)
        assertFalse("the mic view after a silence abort is neutral", micUiRecording(c.state))
        shutdown(scope)
    }

    // ==================================================================
    // T21a (v0.1.45, measurement bridge, PLAN_V0145 §T21a): debug-only bypass
    // of the ERR_SILENT start watchdog. On a dead mic source (emulator
    // -no-audio) the app-side abort (~3 s) fired BEFORE the OS-FGS layer was
    // observable. With [silentGuardBypassed] active the coordinator must NOT
    // abort on ERR_SILENT — the session keeps RECORDING. Other codes (ERR_STALL,
    // real audio_error) abort NORMALLY. Production wires the flag from the
    // operator switch (DictationPermissions.silentGuardBypassed).
    // ==================================================================

    @Test fun `T21a silent bypass - ERR_SILENT skipped, session keeps recording`() {
        val base = 1_000_000L
        val now = AtomicLong(base)
        val src = StallTestSource(deliver = true, stepMs = 500, silent = true, advance = { now.addAndGet(it) })
        val scope = newScope()
        val sessLines = CopyOnWriteArrayList<String>()
        val c = DictationCoordinator(
            scope, { src }, { FakeAsrEngine() }, FakeSink(),
            nowMs = { now.get() },
            silentGuardBypassed = { true },
            sessLog = { sessLines.add(it) },
        )
        val aborts = CopyOnWriteArrayList<String>()
        c.onAborted = { aborts.add(it) }
        assertTrue(c.start())
        // 20 reads = 10 s on the virtual clock: at least 3 start windows (3 s
        // each) elapsed — the stock would have aborted after the FIRST one
        // (see the F34 test above).
        val deadline = System.currentTimeMillis() + 3000
        while ((c.state != DictationState.RECORDING || c.capBlocks() < 20) &&
            System.currentTimeMillis() < deadline
        ) {
            Thread.sleep(5)
        }
        assertEquals("no abort despite silence (bypass active)", "-", c.lastAbortReason)
        assertTrue("onAborted never fired", aborts.isEmpty())
        assertEquals("session keeps RECORDING", DictationState.RECORDING, c.state)
        assertTrue("the VK-SESS bypass line was logged", sessLines.isNotEmpty())
        assertTrue(
            "VK-SESS line: cause=silent_bypass, numbers only (rule 9): $sessLines",
            sessLines.all { it.startsWith("cause=silent_bypass ") },
        )
        c.stop()
        shutdown(scope)
    }

    @Test fun `T21a silent bypass does not swallow ERR_STALL - dead source still aborts`() {
        val base = 1_000_000L
        val now = AtomicLong(base)
        val src = StallTestSource(deliver = false, stepMs = 1000, advance = { now.addAndGet(it) })
        val scope = newScope()
        val c = DictationCoordinator(
            scope, { src }, { FakeAsrEngine() }, FakeSink(),
            nowMs = { now.get() },
            silentGuardBypassed = { true },
            sessLog = { },
        )
        val aborts = CopyOnWriteArrayList<String>()
        c.onAborted = { aborts.add(it) }
        assertTrue(c.start())
        val deadline = System.currentTimeMillis() + 3000
        while (c.lastAbortReason != "audio_error:stall" && System.currentTimeMillis() < deadline) {
            Thread.sleep(5)
        }
        assertEquals("stall still aborts despite the silent bypass", "audio_error:stall", c.lastAbortReason)
        assertEquals("onAborted fires exactly once with the reason", listOf("audio_error:stall"), aborts)
        assertEquals("session is IDLE", DictationState.IDLE, c.state)
        shutdown(scope)
    }

    // A1 (PLAN_V0133 §1, red test 1): cold start — engine.load() advances the
    // injected clock by 4,500 ms (model load), src.start() is successful,
    // the first PCM buffer only arrives 100 ms later. The watchdog baselines
    // must hang on the REAL capture start: isActive=true, lastError=null,
    // NO audio_error:stall and NO audio_error:silence (before the fix:
    // ERR_STALL in the first empty poll, because the clocks were zeroed
    // before engine.load()).
    @Test fun `A1 cold start with 4500ms load and 100ms start latency stays active without stall and silence`() {
        val base = 1_000_000L // NOT 0 — 0 is the lastBlockAtMs sentinel "never started"
        val now = AtomicLong(base)
        val src = ColdStartSource(firstStepMs = 100, stepMs = 500, deliverReads = 0, advance = { now.addAndGet(it) })
        val engine = SlowLoadEngine(loadMs = 4500, advance = { now.addAndGet(it) })
        val scope = newScope()
        val c = DictationCoordinator(
            scope, { src }, { engine }, FakeSink(),
            modelDir = File("vk-test-model"), // the fake engine ignores the directory
            nowMs = { now.get() },
        )
        assertTrue(c.start())
        // run virtually > 4 s beyond the capture start — beyond both the
        // 3 s stall AND the 3 s silence windows (really bounded by the deadline)
        val deadline = System.currentTimeMillis() + 3000
        while (now.get() < base + 4500 + 4000 && System.currentTimeMillis() < deadline) Thread.sleep(10)
        assertTrue("virtual time must exceed the capture start by >3 s", now.get() >= base + 4500 + 4000)
        assertTrue("session must be active after a cold start", c.isActive)
        assertNull("no error code after the start latency", c.lastError)
        assertEquals("no abort reason — neither stall nor silence", "-", c.lastAbortReason)
        assertTrue("the first PCM buffer must have arrived", c.capBlocks() >= 1)
        c.stop()
        awaitIdle(c)
        shutdown(scope)
    }

    // A1 (PLAN_V0133 §1, red test 2): cold start with a 4,500 ms load — the
    // source first delivers blocks after a successful start and then 3,000+ ms
    // none at all (real source death). Expectation: EXACTLY ONE audio_error:stall,
    // onAborted exactly 1×, controller IDLE — and the abort only comes after the
    // source death, not already in the first empty poll after the model load
    // (before the fix: ERR_STALL before the first block, capBlocks=0).
    @Test fun `A1 source death 3000ms after capture start triggers exactly one stall abort`() {
        val base = 1_000_000L
        val now = AtomicLong(base)
        val src = ColdStartSource(firstStepMs = 100, stepMs = 500, deliverReads = 3, advance = { now.addAndGet(it) })
        val engine = SlowLoadEngine(loadMs = 4500, advance = { now.addAndGet(it) })
        val scope = newScope()
        val c = DictationCoordinator(
            scope, { src }, { engine }, FakeSink(),
            modelDir = File("vk-test-model"),
            nowMs = { now.get() },
        )
        val aborts = CopyOnWriteArrayList<String>()
        c.onAborted = { aborts.add(it) }
        assertTrue(c.start())
        val deadline = System.currentTimeMillis() + 3000
        while (c.lastAbortReason != "audio_error:stall" && System.currentTimeMillis() < deadline) {
            Thread.sleep(5)
        }
        assertEquals("source death must end the session via the stall path", "audio_error:stall", c.lastAbortReason)
        assertEquals("controlled error code, no audio content", "stall:no_audio", c.lastError)
        assertEquals("onAborted fires exactly once with the reason", listOf("audio_error:stall"), aborts)
        assertEquals("controller is IDLE", DictationState.IDLE, c.state)
        assertFalse("the session has ended", c.isActive)
        assertTrue("the source must have delivered blocks before dying (no early abort)", c.capBlocks() >= 1)
        shutdown(scope)
    }
}
