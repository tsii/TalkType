package de.voicekeyboard.ime

import de.voicekeyboard.asr.AsrEngine
import de.voicekeyboard.asr.Hypothesis
import de.voicekeyboard.audio.AudioSource
import de.voicekeyboard.audio.PcmBlock
import de.voicekeyboard.audio.PcmWindow
import de.voicekeyboard.editor.EditorSink
import de.voicekeyboard.editor.OwnershipTracker
import kotlinx.coroutines.cancel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * AP7-E1 (docs/PLAN_C2_FABLE_R2.md §0.2.5 P1, vc33 T1 emulator evidence):
 * a `restartInput()` of the target app on our FIRST partial write killed the
 * session regardless of restarting=true (onStartInput → onEditorChanged →
 * abort("editor_change")) — the confirmed user cause of death (~2 s, the
 * first grammatically closed fragment). Contract after the fix:
 *  (a) restartInput during RECORDING ⇒ the session lives, the hypothesis
 *      keeps writing afterwards, no abort;
 *  (b) a real editor change (different field/package) ⇒ abort as before;
 *  (c) restart + text deleted by the app ⇒ controlled abort (no ghost
 *      writing into a changed editor);
 *  (d) counters restart_inputs/rebases for the 🐞 export.
 *
 * Rule 9: fixture texts are test constants, never user data.
 */
class EditorRestartSurvivalTest {

    /** Direct source: loud blocks, WALL-CLOCK-PACED (≈12× real time: 10 ms per
     *  read) — INTEG-1 root finding: without throttling the source ran at µs
     *  speed, every flush decoded the growing whole-buffer (2× copyOf) ⇒
     *  heap churn ⇒ test JVM death mid-run (7 empty suite XMLs) and timing
     *  flakes in LOAD-dependent foreign suites (run 2: SegmentGolden/HoldGate
     *  red despite green substance).
     *  [maxBlocks]: afterwards 0-blocks (ScriptSource pattern) — the buffer
     *  freezes; needed for wall-clock pauses in the test (E1b window > 400 ms). */
    private class FakeSource(private val maxBlocks: Int = Int.MAX_VALUE) : AudioSource {
        var reads = 0
        override fun start(sessionId: Long): Boolean = true
        override fun read(dst: ShortArray, timeoutMs: Int): Int {
            if (reads >= maxBlocks) {
                Thread.sleep(1) // no block, no allocation (ERR_STALL: 10 s away)
                return 0
            }
            Thread.sleep(10) // pacing: limit the heap churn of the whole-buffer decodes
            reads++
            val n = minOf(4_000, dst.size)
            java.util.Arrays.fill(dst, 0, n, 12_000.toShort()) // loud
            return n
        }
        override fun stopAndDrain(): List<PcmBlock> = emptyList()
        override fun release() {}
    }

    private class FakeEngine : AsrEngine {
        var nextText = "hallo"
        var calls = 0
            private set
        override fun load(modelDir: File) {}
        override suspend fun transcribe(window: PcmWindow, sessionId: Long, runId: Long): Hypothesis {
            calls++
            return Hypothesis(
                IntArray(0), nextText, IntArray(0), sessionId, runId,
                window.samples.size, incomplete = false,
            )
        }
        override fun close() {}
    }

    /** Real write sink (ownership chain real, content never logged). */
    private class RecordingSink : EditorSink {
        var text: String = ""
        override fun replaceBeforeCursor(replaceLength: Int, newText: String) {
            text = text.dropLast(replaceLength) + newText
        }
        override fun textBeforeCursor(maxChars: Int): CharSequence? =
            text.takeLast(minOf(maxChars, text.length))
        override fun ownLength(): Int = text.length
        // AP7-E1c: the fixture text is entirely our own span (like ownSpan in
        // the production sink after our writes).
        override fun ownText(): String = text
    }

    private class Ctx(
        val coordinator: DictationCoordinator,
        val engine: FakeEngine,
        val sink: RecordingSink,
        val scope: kotlinx.coroutines.CoroutineScope,
    )

    /** [maxBlocks]: the source freezes afterwards (0-blocks) — only for tests
     *  with a wall-clock pause ((c): E1b window > 400 ms), otherwise the whole
     *  buffer grows to a heap OOM at µs source speed. (a)/(e) need the source
     *  to KEEP RUNNING for the follow-up hypothesis ⇒ default unlimited. */
    private fun startSession(maxBlocks: Int = Int.MAX_VALUE): Ctx {
        val engine = FakeEngine()
        val sink = RecordingSink()
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default)
        val coordinator = DictationCoordinator(
            scope, { FakeSource(maxBlocks) }, { engine }, sink,
            tracker = OwnershipTracker(),
            tickSamples = 4_000, // every read ⇒ an immediate decode tick
            autoStopSuppressed = { true }, // hold: no F43 noise
        )
        assertTrue(coordinator.start())
        // the first partial write is deterministically there (2 s min + 4 s block):
        val deadline = System.currentTimeMillis() + 10_000
        while (sink.text.isEmpty() && System.currentTimeMillis() < deadline) {
            Thread.sleep(5)
        }
        assertTrue("first partial written (actual='${sink.text.length} characters')", sink.text.isNotEmpty())
        return Ctx(coordinator, engine, sink, scope)
    }

    // (a) restartInput during RECORDING: re-baseline, the session lives, the
    //     NEXT hypothesis keeps writing — the core contract of the fix.
    @Test
    fun `a) restartInput during RECORDING - session lives and keeps writing`() {
        val ctx = startSession()
        try {
            val c = ctx.coordinator
            val before = ctx.sink.text
            // service action with restarting=true, the same field (AP7-E1):
            assertTrue("re-baseline keeps ownership (text untouched)", c.rebaselineOwnership())
            assertEquals("rebases gezaehlt", 1, c.rebases())
            // the app's re-bind callback: an open baseline takes it SILENTLY
            // (no lost). FURTHER unexpected callbacks remain H-E2/P2 and
            // therefore AP7-E2 (suffix resync) — deliberately ONE callback here.
            c.onUpdateSelection(0, 0, 0, 0)
            // next hypothesis (the app did restartInput, we keep writing):
            ctx.engine.nextText = before + " welt"
            val deadline = System.currentTimeMillis() + 10_000
            while (ctx.sink.text == before && System.currentTimeMillis() < deadline) {
                Thread.sleep(5)
            }
            assertEquals("hypothesis CONTINUED after the restart", before + " welt", ctx.sink.text)
            // THE REPRO CORE: no abort, the session stays RECORDING until the stop.
            assertTrue("no abort — session lives", c.isActive)
            assertEquals("no abort reason", "-", c.lastAbortReason)
            c.stop()
        } finally {
            // NO leak: FakeSource never ends and scope.cancel() does NOT stop
            // the capture thread — without shutdown() it kept pumping into the
            // channel believed dead (unscaled) and tore down the test JVM heap
            // (run 1: OOM, FakeAudioScenarioTest not runnable).
            ctx.coordinator.shutdown()
            ctx.scope.cancel()
        }
    }

    // (b) a real editor change ⇒ abort as before — the pure decision.
    @Test
    fun `b) a real package-field change stays an abort - pure decision`() {
        // restarting=true + IDENTICAL field ⇒ REBASELINE (the app's restartInput):
        assertEquals(
            EditorRestartAction.REBASELINE,
            editorRestartAction(
                restarting = true, sessionActive = true,
                baselinePkg = "com.app", baselineFieldId = 42,
                infoPkg = "com.app", infoFieldId = 42,
            ),
        )
        // restarting=true + DIFFERENT field ⇒ ABORT (a real editor change):
        assertEquals(
            EditorRestartAction.ABORT,
            editorRestartAction(
                restarting = true, sessionActive = true,
                baselinePkg = "com.app", baselineFieldId = 42,
                infoPkg = "com.app", infoFieldId = 77,
            ),
        )
        // restarting=true + DIFFERENT package ⇒ ABORT:
        assertEquals(
            EditorRestartAction.ABORT,
            editorRestartAction(
                restarting = true, sessionActive = true,
                baselinePkg = "com.app", baselineFieldId = 42,
                infoPkg = "com.other", infoFieldId = 42,
            ),
        )
        // restarting=false (a real re-bind) ⇒ ABORT — conservative:
        assertEquals(
            EditorRestartAction.ABORT,
            editorRestartAction(
                restarting = false, sessionActive = true,
                baselinePkg = "com.app", baselineFieldId = 42,
                infoPkg = "com.app", infoFieldId = 42,
            ),
        )
        // no active session ⇒ NOTHING (cosmetic path, no abort needed):
        assertEquals(
            EditorRestartAction.NOTHING,
            editorRestartAction(
                restarting = true, sessionActive = false,
                baselinePkg = "com.app", baselineFieldId = 42,
                infoPkg = "com.app", infoFieldId = 42,
            ),
        )
    }

    // AP7-E1-P2 (review): fieldId sentinels — BOTH sides ≤ 0
    // ("unknown"; Android delivers 0/-1 per editor) ⇒ ONLY the package match
    // decides: the same app ⇒ REBASELINE (otherwise -1 vs 0 would be a
    // WRONG abort in real apps).
    @Test
    fun `P2 both fieldId sentinels - the package decides REBASELINE`() {
        assertEquals(
            "both ≤ 0, same package ⇒ REBASELINE (no -1-vs-0 false abort)",
            EditorRestartAction.REBASELINE,
            editorRestartAction(
                restarting = true, sessionActive = true,
                baselinePkg = "com.app", baselineFieldId = 0,
                infoPkg = "com.app", infoFieldId = -1,
            ),
        )
    }

    // AP7-E1-P2: ONE sentinel does not block (unclear side ⇒ the package match
    // suffices); a DIFFERENT package stays ABORT — even with sentinel fields.
    @Test
    fun `P2 one sentinel does not block - a different package stays an abort`() {
        assertEquals(
            "one side ≤ 0, other > 0, same package ⇒ REBASELINE",
            EditorRestartAction.REBASELINE,
            editorRestartAction(
                restarting = true, sessionActive = true,
                baselinePkg = "com.app", baselineFieldId = 0,
                infoPkg = "com.app", infoFieldId = 42,
            ),
        )
        assertEquals(
            "different package ⇒ ABORT — also with sentinel fields",
            EditorRestartAction.ABORT,
            editorRestartAction(
                restarting = true, sessionActive = true,
                baselinePkg = "com.app", baselineFieldId = 0,
                infoPkg = "com.other", infoFieldId = -1,
            ),
        )
    }

    // (c) restart + text deleted by the app ⇒ controlled abort, no ghost
    //     writing (the re-baseline detects the loss at the suffix).
    @Test
    fun `c) restart with deleted text - controlled abort, no ghost writing`() {
        val ctx = startSession(maxBlocks = 64) // the source freezes for the 450-ms pause
        try {
            val c = ctx.coordinator
            val before = ctx.sink.text
            // AP7-E1b: NO fresh own edit — consume the sequence request
            // (app callback on our write) + let the freshness window age:
            c.onUpdateSelection(0, 0, 0, 0)
            Thread.sleep(450) // > OWN_EDIT_FRESH_MS (400) — okay on the test side
            // the app DELETED our text during restartInput:
            ctx.sink.text = ""
            assertFalse("re-baseline refused (our text is gone, no fresh edit)", c.rebaselineOwnership())
            // service path: controlled abort, no further writing:
            c.abort("editor_rebaseline_lost")
            assertFalse("session ended in a controlled way", c.isActive)
            assertEquals("abort reason fine-grained (stop_cause family)", "editor_rebaseline_lost", c.lastAbortReason)
            assertEquals("no ghost writing — editor stays empty", "", ctx.sink.text)
            assertTrue("previous text existed (evidence plausibility)", before.isNotEmpty())
        } finally {
            // NO leak: FakeSource never ends and scope.cancel() does NOT stop
            // the capture thread — without shutdown() it kept pumping into the
            // dead-looking channel (unbounded) and tore down the test JVM heap
            // (run 1: OOM, FakeAudioScenarioTest not runnable).
            ctx.coordinator.shutdown()
            ctx.scope.cancel()
        }
    }

    // (e) AP7-E1b (vc33 final build, restart:20 cell): restartInput fires IN
    //     THE MIDDLE of our own delete/commit sequence (the app watcher counts
    //     OUR write; the actual text is half: delete done, commit open). NO
    //     false abort — RE-SYNC; our commit sequence completes, the NEXT
    //     hypothesis keeps writing correctly. The 5th restart killer is
    //     thereby closed.
    @Test
    fun `e) restart in the middle of our own edit sequence - RE-SYNC session lives and keeps writing`() {
        val ctx = startSession()
        try {
            val c = ctx.coordinator
            // mid-sequence actual state: delete done ("hallo" → "hal"), commit
            // open — the session's ownEditPending stands (no app callback came).
            assertTrue("the sequence request stands (mid-edit mark)", ctx.engine.calls >= 1)
            ctx.sink.text = "hal"
            assertTrue("E1b: frischer eigener Edit ⇒ RE-SYNC statt Verlust", c.rebaselineOwnership())
            assertTrue("session lives (no false abort)", c.isActive)
            assertEquals("no abort reason", "-", c.lastAbortReason)
            // our commit sequence completes the text (trailing write):
            ctx.sink.text = ctx.sink.text + "lo"
            val before = ctx.sink.text
            // the next hypothesis writes from the re-anchored state onwards:
            ctx.engine.nextText = before + " welt"
            val deadline = System.currentTimeMillis() + 10_000
            while (ctx.sink.text == before && System.currentTimeMillis() < deadline) {
                Thread.sleep(5)
            }
            assertEquals("hypothesis continued correctly after RE-SYNC", before + " welt", ctx.sink.text)
            assertTrue("session lives until the stop", c.isActive)
            c.stop()
        } finally {
            ctx.coordinator.shutdown()
            ctx.scope.cancel()
        }
    }

    // (f) AP7-E1b boundary: foreign loss WITHOUT a fresh own edit ⇒ the abort
    //     remains (tracker level, injected clock — the 400-ms window exact).
    @Test
    fun `f) foreign loss without a fresh own edit - abort remains`() {
        val clock = java.util.concurrent.atomic.AtomicLong(1_000_000L)
        val t = de.voicekeyboard.editor.OwnershipTracker(nowMs = { clock.get() })
        t.onUpdateSelection(0, 0, 0, 0) // system baseline
        t.onOwnEdit("hallo")            // our write — the window runs
        t.onUpdateSelection(0, 0, 0, 0) // AP7-E1b: consume the sequence request
        clock.addAndGet(500)            // > 400 ms: NOT fresh anymore
        assertFalse("suffix gone + no fresh edit ⇒ the loss stays", t.rebaseline(""))
        // for the counter-proof: WITHIN the window ⇒ RE-SYNC (E1b):
        val t2 = de.voicekeyboard.editor.OwnershipTracker(nowMs = { clock.get() })
        t2.onUpdateSelection(0, 0, 0, 0)
        t2.onOwnEdit("hallo")
        t2.onUpdateSelection(0, 0, 0, 0) // sequence request consumed
        clock.addAndGet(100)            // ≤ 400 ms: still fresh
        assertTrue("fresh (100 ms) ⇒ RE-SYNC despite a partial actual text", t2.rebaseline("hal"))
        assertFalse("no lost after RE-SYNC", t2.ownershipLost)
        // ownEditPending (without consumption) suffices as well — mid-sequence:
        val t3 = de.voicekeyboard.editor.OwnershipTracker(nowMs = { clock.get() })
        t3.onUpdateSelection(0, 0, 0, 0)
        t3.onOwnEdit("hallo")
        assertTrue("ownEditPending ⇒ RE-SYNC", t3.rebaseline("hal"))
        assertFalse(t3.ownershipLost)
    }

    // (g) AP7-E1c (vc33 acceptance P0 blocker): restartInput fires WITHIN our
    //     commit — the sink (ownSpan, edit thread) is already at the new state,
    //     the tracker (worker side, onOwnEdit AFTER the round trip) lags behind
    //     by exactly this write. Without E1c: the suffix test against a stale
    //     expectation ⇒ false abort `editor_rebaseline_lost`
    //     (observed live: 5th restart, t=8.5 s, restart:20).
    @Test
    fun `g) restart in the commit window with a stale tracker - E1c re-sync via sink ownText`() {
        // harness safeguard (the same discipline as (c)): the 450-ms wall-clock
        // pause needs a FROZEN source, otherwise heap OOM.
        val ctx = startSession(maxBlocks = 64)
        try {
            val c = ctx.coordinator
            val written = ctx.sink.text
            // deliberately switch off the E1b freshness (like (c)/(f)) — only E1c
            // can still rescue here: consume the sequence request,
            // let the freshness window age.
            c.onUpdateSelection(0, 0, 0, 0)
            // the NEXT commit is already in the sink (ownSpan updated),
            // the worker-side onOwnEdit has NOT run (tracker stale):
            ctx.sink.text = written + " welt"
            Thread.sleep(450) // > OWN_EDIT_FRESH_MS — no E1b lifeline
            assertTrue(
                "E1c: sink state (prefix-relative, longer) raises the expectation — no false abort",
                c.rebaselineOwnership(),
            )
            assertTrue("session lives", c.isActive)
            assertEquals("no abort reason", "-", c.lastAbortReason)
            // counter-proof at tracker level (without the sink state): the same
            // situation was the killer — prefix kinship in BOTH directions
            // (W3c rollback):
            val clock = java.util.concurrent.atomic.AtomicLong(1_000_000L)
            val t = de.voicekeyboard.editor.OwnershipTracker(nowMs = { clock.get() })
            t.onUpdateSelection(0, 0, 0, 0)
            t.onOwnEdit("hallo")
            t.onUpdateSelection(0, 0, 0, 0)
            clock.addAndGet(500)
            assertFalse("old (without E1c): lengthened actual text ⇒ false loss", t.rebaseline("hallo welt"))
            val t2 = de.voicekeyboard.editor.OwnershipTracker(nowMs = { clock.get() })
            t2.onUpdateSelection(0, 0, 0, 0)
            t2.onOwnEdit("hallo welt")
            t2.onUpdateSelection(0, 0, 0, 0)
            clock.addAndGet(500)
            assertTrue("rollback direction: shorter sink state, prefix ⇒ RE-SYNC", t2.rebaseline("hallo", "hallo"))
            c.stop()
        } finally {
            ctx.coordinator.shutdown()
            ctx.scope.cancel()
        }
    }

    // (d) counters: rebases per re-baseline, monotonic; the coordinator's
    //     restart_inputs counter stays untouched (noteRestartInput, AP3).
    @Test
    fun `d) rebases counter monotonic - restart_inputs untouched`() {
        val ctx = startSession()
        try {
            val c = ctx.coordinator
            assertEquals(0, c.rebases())
            assertEquals(0, c.restartInputs())
            assertTrue(c.rebaselineOwnership())
            assertTrue(c.rebaselineOwnership())
            assertEquals("zwei Re-Baselines gezaehlt", 2, c.rebases())
            assertEquals("restart_inputs only via onStartInput (AP3)", 0, c.restartInputs())
            c.noteRestartInput()
            assertEquals(1, c.restartInputs())
        } finally {
            // NO leak: FakeSource never ends and scope.cancel() does NOT stop
            // the capture thread — without shutdown() it kept pumping into the
            // channel believed dead (unscaled) and tore down the test JVM heap
            // (run 1: OOM, FakeAudioScenarioTest not runnable).
            ctx.coordinator.shutdown()
            ctx.scope.cancel()
        }
    }

    // (h) AP7-E1d: a read window into the re-bind (act=-1) is NOT proof of loss —
    //     pure retry decision + tracker sequence: the baseline opens immediately,
    //     the re-bind callback SILENT, the retry with a living connection keeps
    //     ownership.
    @Test
    fun `h) null read window - retry decision pure and the baseline stays pure`() {
        // pure decision: only (not-kept AND act=-1 AND active) retries:
        assertTrue(rebaseRetryDue(kept = false, actLen = -1, sessionActive = true))
        assertFalse("echter Verlust (act gelesen) bricht sofort ab", rebaseRetryDue(false, 75, true))
        assertFalse("a held baseline needs no retry", rebaseRetryDue(true, -1, true))
        assertFalse("an inactive session does not retry", rebaseRetryDue(false, -1, false))
        // tracker sequence: null-read ⇒ rejectable, but baseline reopen ⇒
        // re-bind callback without lost ⇒ the retry read keeps ownership:
        val t = de.voicekeyboard.editor.OwnershipTracker(nowMs = { 0L }) // old: never fresh
        t.onUpdateSelection(0, 0, 0, 0) // system baseline
        t.onOwnEdit("hallo")
        t.onUpdateSelection(0, 0, 0, 0) // own callback consumed
        assertFalse("null read: still refusable", t.rebaseline(null))
        t.reopenSelectionBaseline()
        t.onUpdateSelection(0, 0, 0, 0) // re-bind callback: consumed SILENTLY
        assertFalse("no lost through a re-bind inside the retry window", t.ownershipLost)
        assertTrue("a retry with a living connection keeps ownership", t.rebaseline("hallo"))
        assertFalse(t.ownershipLost)
    }
}
