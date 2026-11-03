package de.voicekeyboard.ime

import de.voicekeyboard.asr.AsrEngine
import de.voicekeyboard.asr.Hypothesis
import de.voicekeyboard.audio.PcmBlock
import de.voicekeyboard.audio.PcmWindow
import de.voicekeyboard.dictation.DictationController
import de.voicekeyboard.dictation.DictationState
import de.voicekeyboard.dictation.StopOutcome
import de.voicekeyboard.editor.EditorSink
import de.voicekeyboard.editor.OwnershipTracker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicLong

/**
 * A2b (PLAN_V0133 §1 A2b) — write-stop family:
 *  1. a W3d burst blocks only ONE hypothesis (the session stays RECORDING),
 *     write_w3d=1, afterwards write_delta grows;
 *  2. a final after W3d commits the COMPLETE final target state;
 *  3. W1 with an E1c suffix proof (actualBeforeCursor, fresherExpected=
 *     sink.ownText()) re-baselines and keeps writing; foreign text ⇒ W1
 *     without sink access, lost stays;
 *  4. E1c prefix rule: fresher sink text over an older tracker expectation;
 *     lost=false ONLY after a positive suffix proof (ownEditFresh alone
 *     does NOT suffice).
 *
 * Only numbers/codes/lengths in assumptions and assertions (rule 9) — no
 * transcript contents in logs.
 */
class SchreibstoppTest {

    /** A2b: sink with a foreign-text PREFIX and a separate own span. */
    private class OwnSpanSink : EditorSink {
        var foreign: String = ""
        var own: String = ""
        val calls = mutableListOf<Pair<Int, String>>()

        override fun replaceBeforeCursor(replaceLength: Int, newText: String) {
            calls += replaceLength to newText
            own = own.dropLast(replaceLength) + newText
        }

        override fun textBeforeCursor(maxChars: Int): CharSequence? =
            (foreign + own).takeLast(minOf(maxChars, foreign.length + own.length))

        override fun ownLength(): Int = own.length

        override fun ownText(): String = own
    }

    /** A2b: engine with a script queue (one text per decode run). */
    private class ScriptedEngine(texts: List<String>) : AsrEngine {
        private val queue = ConcurrentLinkedQueue(texts)

        override fun load(modelDir: File) {}

        override suspend fun transcribe(window: PcmWindow, sessionId: Long, runId: Long): Hypothesis =
            Hypothesis(
                IntArray(1), queue.poll() ?: "", IntArray(1),
                sessionId, runId, window.samples.size, incomplete = false,
            )

        override fun close() {}
    }

    private fun hyp(text: String, sessionId: Long) =
        Hypothesis(IntArray(1), text, IntArray(1), sessionId, 1L, 16_000, incomplete = false)

    private fun newController(sink: EditorSink, tracker: OwnershipTracker) =
        DictationController(ScriptedEngine(emptyList()), sink, tracker = tracker)

    /** 0.2 s of audio: advances the agreement audio position without triggering
     *  the 2-s partial guard (LocalAgreement does NOT count equal audio
     *  positions as a second confirmation — DESIGN §5.4). */
    private suspend fun DictationController.tick() =
        feedAudio(PcmBlock(0, 0, ShortArray(3_200)))

    // A2b red test 1: a controller session with a W3d burst stays RECORDING; the
    // NEXT hypothesis writes; write_w3d=1, afterwards write_delta grows.
    @Test fun `A2b W3d burst blocks only one hypothesis and the counters move along`() {
        val sink = OwnSpanSink()
        val c = newController(sink, OwnershipTracker())
        assertTrue(c.start())
        assertEquals("last_write_audio_s=-1 before the first DELTA", -1.0, c.lastWriteAudioS, 1e-9)

        val t = "aa bb cc dd ee ff gg hh ii jj kk ll mm nn"
        kotlinx.coroutines.runBlocking {
            repeat(3) {
                c.tick()
                assertTrue(c.applyPartial(hyp(t, c.sessionId)))
            }
        }
        assertTrue("agreement must commit the prefix", c.committedLen > 0)
        val callsAtCommit = sink.calls.size

        // W3d burst: the hypothesis diverges early in the committed region —
        // rollback forbidden (>= 2 spaces in the delete region)
        val d = "xy aa bb cc dd ee ff gg hh ii jj kk ll mm nn"
        kotlinx.coroutines.runBlocking {
            c.tick()
            assertTrue(c.applyPartial(hyp(d, c.sessionId)))
        }
        assertEquals("the session does NOT end via W3d itself", DictationState.RECORDING, c.state)
        assertEquals("exactly ONE W3d", 1, c.writeW3dCount)
        assertEquals("no write attempt in the blocked step", callsAtCommit, sink.calls.size)
        assertEquals(t, sink.own)
        assertEquals("w3d", c.writeReason)

        // NEXT hypothesis writes (A2a latch): write_delta grows
        kotlinx.coroutines.runBlocking {
            c.tick()
            assertTrue(c.applyPartial(hyp(d, c.sessionId)))
        }
        assertEquals("write_delta grows (T#1 was already DELTA 1)", 2, c.writeDeltaCount)
        assertEquals(d, sink.own)
        assertEquals("W3d stays at exactly 1", 1, c.writeW3dCount)
        assertEquals(DictationState.RECORDING, c.state)
        assertTrue("last_write_audio_s set after the first DELTA", c.lastWriteAudioS >= 0.0)

        // further writing lets write_delta keep growing
        kotlinx.coroutines.runBlocking {
            c.tick()
            assertTrue(c.applyPartial(hyp(d + " oo", c.sessionId)))
        }
        assertEquals(3, c.writeDeltaCount)
        assertEquals(d + " oo", sink.own)
    }

    // A2b red test 2: a final after W3d commits the COMPLETE final target state
    // — no old partial hypothesis is left standing.
    @Test fun `A2b final after W3d commits the complete target state`() {
        val sink = OwnSpanSink()
        val finalText = "zz yu aa bb cc dd ee ff gg hh ii jj kk ll mm nn oo pp"
        val c = DictationController(ScriptedEngine(listOf(finalText)), sink)
        assertTrue(c.start())
        val t = "aa bb cc dd ee ff gg hh ii jj kk ll mm nn"
        kotlinx.coroutines.runBlocking {
            repeat(3) {
                c.tick()
                assertTrue(c.applyPartial(hyp(t, c.sessionId)))
            }
        }
        val d = "xy aa bb cc dd ee ff gg hh ii jj kk ll mm nn"
        kotlinx.coroutines.runBlocking {
            c.tick() // W3d blocker (once)
            assertTrue(c.applyPartial(hyp(d, c.sessionId)))
            c.tick() // latch: DELTA, lock consumed
            assertTrue(c.applyPartial(hyp(d, c.sessionId)))
            repeat(3) { // rebuild the agreement
                c.tick()
                assertTrue(c.applyPartial(hyp(d, c.sessionId)))
            }
        }
        assertTrue("the committed prefix is back before the final", c.committedLen > 0)
        val sinkBeforeFinal = sink.own

        val outcome = kotlinx.coroutines.runBlocking { c.stop() }
        assertTrue("the final must run", outcome is StopOutcome.FinalRan)
        assertEquals("the COMPLETE final target state", finalText, sink.own)
        assertTrue("the old partial must not stay standing", sinkBeforeFinal != sink.own)
        assertEquals("no second W3d in the final", 1, c.writeW3dCount)
        assertEquals("delta", c.writeReason)
        assertEquals(DictationState.IDLE, c.state)

        // the counters are zeroed on start (the next session fresh)
        assertTrue(c.start())
        assertEquals(0, c.writeSameCount)
        assertEquals(0, c.writeW1Count)
        assertEquals(0, c.writeW3dCount)
        assertEquals(0, c.writeDeltaCount)
        assertEquals(-1.0, c.lastWriteAudioS, 1e-9)
        assertEquals("-", c.writeReason)
    }

    // A2b red test 3: W1 with an actualBeforeCursor suffix proof and
    // fresherExpected=sink.ownText() may re-baseline and keep writing afterwards.
    // Foreign/differing editor content ⇒ W1, no sink access, lost stays.
    @Test fun `A2b W1 re-baseline with suffix proof writes - foreign text stays W1`() {
        val sink = OwnSpanSink()
        val clock = AtomicLong(1_000_000L)
        val tracker = OwnershipTracker(nowMs = { clock.get() })
        val c = newController(sink, tracker)
        assertTrue(c.start())
        assertTrue(c.applyPartial(hyp("hallo welt", c.sessionId)))
        assertEquals("hallo welt", sink.own)

        // the user types BEFORE our text (the suffix stays provable); the
        // tracker reports foreign input (range selection) — without the fix a
        // W1 stop.
        sink.foreign = "User "
        c.onUpdateSelection(0, 0, 2, 5) // range (2 != 5) ⇒ lost=true
        assertTrue("lost set", tracker.ownershipLost)
        clock.addAndGet(500) // freshness window deterministically closed

        assertTrue(
            "re-baseline with a suffix proof ⇒ the same hypothesis is written",
            c.applyPartial(hyp("hallo welt mehr", c.sessionId)),
        )
        assertEquals("written", "hallo welt mehr", sink.own)
        assertEquals("foreign prefix untouched byte-wise", "User ", sink.foreign)
        assertFalse("lost cleared after the suffix proof", tracker.ownershipLost)
        assertEquals("no W3d counted", 0, c.writeW3dCount)
        assertEquals("write_delta grew (initial write + rebase write)", 2, c.writeDeltaCount)

        // counter-check: the app REPLACES our text (no suffix) — lost stays,
        // NO sink access, the counter reports W1.
        sink.own = "GANZ ANDERER TEXT"
        c.onUpdateSelection(0, 0, 2, 5) // range ⇒ lost true again
        clock.addAndGet(500)
        val callsBeforeForeign = sink.calls.size
        val deltaBefore = c.writeDeltaCount
        assertFalse(
            "foreign text ⇒ the hypothesis is not executed",
            c.applyPartial(hyp("hallo welt nochmehr", c.sessionId)),
        )
        assertEquals("no sink access", callsBeforeForeign, sink.calls.size)
        assertEquals("W1 counted", 1, c.writeW1Count)
        assertEquals("write_delta unchanged", deltaBefore, c.writeDeltaCount)
        assertTrue("lost stays set", tracker.ownershipLost)
        assertEquals("foreign text untouched byte-wise", "GANZ ANDERER TEXT", sink.own)
        assertEquals("w1", c.writeReason)
    }

    // P2 (REVIEW_A2_A4): a SUCCESSFUL W1 re-baseline is NOT an ownership loss —
    // ownershipDeniedCount/-AtS count only REAL denials (counter and stamp
    // had drifted behind the re-baseline attempt into the rejection branch).
    @Test fun `P2 a successful W1 re-baseline counts no ownership loss`() {
        val sink = OwnSpanSink()
        val clock = AtomicLong(1_000_000L)
        val tracker = OwnershipTracker(nowMs = { clock.get() })
        val c = newController(sink, tracker)
        assertTrue(c.start())
        assertTrue(c.applyPartial(hyp("hallo welt", c.sessionId)))
        assertEquals("before the foreign grab: never lost", -1.0, c.ownershipDeniedAtS, 1e-9)

        // ownership gone, but the suffix provable: the re-baseline succeeds,
        // the hypothesis writes — that is NOT a denial, no ownership-loss stamp:
        sink.foreign = "User "
        c.onUpdateSelection(0, 0, 2, 5) // range ⇒ lost=true
        clock.addAndGet(500)
        assertTrue("re-baseline succeeds (suffix proof)", c.applyPartial(hyp("hallo welt mehr", c.sessionId)))
        assertEquals("a successful re-baseline does NOT count", 0, c.ownershipDeniedCount)
        assertEquals("no ownership-loss stamp", -1.0, c.ownershipDeniedAtS, 1e-9)

        // a real foreign case: counter and stamp set as before
        sink.own = "GANZ ANDERER TEXT"
        c.onUpdateSelection(0, 0, 2, 5)
        clock.addAndGet(500)
        assertFalse(c.applyPartial(hyp("hallo welt nochmehr", c.sessionId)))
        assertEquals("a real denial counts", 1, c.ownershipDeniedCount)
        assertTrue("stamp set", c.ownershipDeniedAtS >= 0.0)
    }

    // A2b red test 4: E1c coordinate — fresher sink text takes precedence
    // under the prefix rule over an older tracker expectation; lost=false
    // ONLY after a positive suffix proof; the ownEditFresh special case
    // alone does NOT count.
    @Test fun `A2b E1c prefix rule - lost only after a suffix proof`() {
        val clock = AtomicLong(1_000_000L)

        // (a) the prefix rule lifts the older expectation; the proof clears lost:
        val t = OwnershipTracker(nowMs = { clock.get() })
        t.onUpdateSelection(0, 0, 0, 0) // system baseline
        t.onOwnEdit("abc def ghi") // older (longer) tracker expectation
        t.onUpdateSelection(0, 0, 2, 5) // range: foreign input ⇒ lost=true
        assertTrue("lost after foreign input", t.ownershipLost)
        clock.addAndGet(500) // freshness window closed

        // without the lift the suffix test would run against the old
        // expectation ("abc def" is not a suffix of "user abc def ghi") — the
        // fresher sink state (prefix relative, shorter) takes precedence:
        val lifted = t.rebaseline("user abc def", "abc def")
        assertTrue("prefix rule: a fresh sink state raises the expectation", lifted)
        assertFalse("clear lost=ONLY after a positive suffix proof", t.ownershipLost)

        // (b) ownEditFresh alone does NOT count as a suffix proof:
        val t2 = OwnershipTracker(nowMs = { clock.get() })
        t2.onUpdateSelection(0, 0, 0, 0)
        t2.onOwnEdit("alt") // window runs
        t2.onUpdateSelection(0, 0, 2, 5) // range ⇒ lost=true (window still fresh)
        assertTrue("RE-SYNC stays promised (true)", t2.rebaseline("FREMDES ZEUG"))
        assertTrue("ownEditFresh alone does NOT clear lost", t2.ownershipLost)

        // (c) actual==null is no proof:
        val t3 = OwnershipTracker(nowMs = { clock.get() })
        t3.onUpdateSelection(0, 0, 0, 0)
        t3.onOwnEdit("abc")
        t3.onUpdateSelection(0, 0, 2, 5)
        clock.addAndGet(500)
        assertFalse("a null read window stays refusable", t3.rebaseline(null))
        assertTrue("no lost-clearing with actual==null", t3.ownershipLost)
    }
}
