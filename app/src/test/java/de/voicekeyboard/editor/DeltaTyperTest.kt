package de.voicekeyboard.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class DeltaTyperTest {

    /** Fake sink: models text, cursor at the end, calls and controllable failures. */
    private class FakeSink : EditorSink {
        var text: String = ""
        var failNext = false
        val calls = mutableListOf<Pair<Int, String>>()

        override fun replaceBeforeCursor(replaceLength: Int, newText: String) {
            if (failNext) {
                failNext = false
                throw IllegalStateException("sink error")
            }
            calls += replaceLength to newText
            text = text.dropLast(replaceLength) + newText
        }

        override fun textBeforeCursor(maxChars: Int): CharSequence? =
            text.takeLast(minOf(maxChars, text.length))

        override fun ownLength(): Int = text.length
    }

    @Test fun firstInsertIsPureAppend() {
        val sink = FakeSink()
        val typer = DeltaTyper(sink)
        val outcome = typer.reviseOutcome("Hallo")
        assertEquals(ReviseKind.DELTA, outcome.kind)
        assertEquals(TextDelta(0, "Hallo"), outcome.delta)
        assertEquals("Hallo", sink.text)
    }

    // (e) minimal delta: the common prefix stays in place

    @Test fun minimalDeltaKeepsCommonPrefix() {
        val sink = FakeSink()
        val typer = DeltaTyper(sink)
        typer.reviseOutcome("Hallo Welt")
        val outcome = typer.reviseOutcome("Hallo Walt")
        assertEquals(ReviseKind.DELTA, outcome.kind)
        assertEquals(TextDelta(3, "alt"), outcome.delta) // only "elt"→"alt", "Hallo W" stays in place
        assertEquals("Hallo Walt", sink.text)
        assertEquals(listOf(0 to "Hallo Welt", 3 to "alt"), sink.calls)
    }

    @Test fun pureAppendHasNoBackspace() {
        val sink = FakeSink()
        val typer = DeltaTyper(sink)
        typer.reviseOutcome("Hallo")
        assertEquals(TextDelta(0, " Welt"), deltaOf(typer.reviseOutcome("Hallo Welt")))
        assertEquals("Hallo Welt", sink.text)
    }

    @Test fun surrogatePairsAreNotSplit() {
        val sink = FakeSink()
        val typer = DeltaTyper(sink)
        typer.reviseOutcome("Hallo \uD83E\uDD16")
        // the common prefix ends on a high surrogate → one character stepped back,
        // so the surrogate pair is not deleted/inserted split
        val outcome = typer.reviseOutcome("Hallo \uD83D\uDE00")
        assertEquals(ReviseKind.DELTA, outcome.kind)
        assertEquals(TextDelta(2, "\uD83D\uDE00"), outcome.delta)
        assertEquals("Hallo \uD83D\uDE00", sink.text)
    }

    // (f) idempotency: the same hypothesis again → no additional delta

    @Test fun sameHypothesisTwiceProducesNoSecondDelta() {
        val sink = FakeSink()
        val typer = DeltaTyper(sink)
        typer.reviseOutcome("Hallo Welt")
        val callsAfterFirst = sink.calls.size
        assertEquals(ReviseKind.SAME, typer.reviseOutcome("Hallo Welt").kind)
        assertEquals(callsAfterFirst, sink.calls.size)
        assertEquals("Hallo Welt", typer.currentText)
    }

    // (c) max_revision_chars = 60: 61-character revision → commit forced

    @Test fun sixtyCharsRevisionStillAllowed() {
        val sink = FakeSink()
        val typer = DeltaTyper(sink)
        typer.reviseOutcome("a".repeat(60))
        val outcome = typer.reviseOutcome("b".repeat(60))
        assertEquals(ReviseKind.DELTA, outcome.kind)
        assertEquals(TextDelta(60, "b".repeat(60)), outcome.delta)
        assertEquals(0, typer.committedLength) // normal revision, nothing frozen
        assertEquals("b".repeat(60), sink.text)
    }

    @Test fun sixtyOneCharsRevisionForcesCommit() {
        val sink = FakeSink()
        val typer = DeltaTyper(sink)
        typer.reviseOutcome("a".repeat(61))
        val outcome = typer.reviseOutcome("b".repeat(61))
        // no deletion: the existing text is committed, only appended (no flicker)
        assertEquals(ReviseKind.DELTA, outcome.kind)
        assertEquals(TextDelta(0, "b".repeat(61)), outcome.delta)
        assertEquals(61, typer.committedLength)
        assertEquals("a".repeat(61) + "b".repeat(61), sink.text)
        // afterwards the stock is untouchable: a further revision only appends
        assertEquals(TextDelta(0, "!"), deltaOf(typer.reviseOutcome("a".repeat(61) + "b".repeat(61) + "!")))
    }

    // (d) the committed prefix is not touched in a later revision (negative test)

    @Test fun divergentCommittedPrefixIsRolledBack_E8() {
        // E8.1 W3c: divergence within our own, ownership-proven area is
        // corrected in ONE atomic revision (D1 "discard" is abolished).
        val sink = FakeSink()
        val typer = DeltaTyper(sink)
        typer.reviseOutcome("abc def")
        typer.commitUpTo(4) // "abc " (including the space) is committed
        val callsBefore = sink.calls.size

        // the hypothesis diverges in the committed area → rollback (W3c), no null
        val outcome = typer.reviseOutcome("xyz def")
        assertEquals(ReviseKind.DELTA, outcome.kind)
        assertEquals(TextDelta(7, "xyz def"), outcome.delta) // whole own area from common=0
        assertEquals(callsBefore + 1, sink.calls.size)
        assertEquals("xyz def", sink.text)
        assertEquals(0, typer.committedLength) // W3a: shrunk to common=0
        assertFalse(typer.divergent)

        // an allowed tail revision still works
        assertEquals(TextDelta(3, "DEF"), deltaOf(typer.reviseOutcome("xyz DEF")))
        assertEquals("xyz DEF", sink.text)
    }

    @Test fun commitUpToClampsButMayShrink_E8_W3a() {
        // E8.1 W3a (D2 abolished): commitUpTo may shrink — the rollback
        // resets the protected area to the common prefix.
        val sink = FakeSink()
        val typer = DeltaTyper(sink)
        typer.reviseOutcome("abcdefgh")
        typer.commitUpTo(5)
        typer.commitUpTo(3) // shrinking allowed (W3a)
        assertEquals(3, typer.committedLength)
        typer.commitUpTo(100) // beyond our own text length → clamped
        assertEquals(8, typer.committedLength)
        typer.commitUpTo(0) // 0 is allowed too (final-decode reset)
        assertEquals(0, typer.committedLength)
    }

    // (e) sink error: exception upward, internal state stays consistent

    @Test fun sinkErrorLeavesStateConsistent() {
        val sink = FakeSink()
        val typer = DeltaTyper(sink)
        typer.reviseOutcome("Hallo")
        val callsBefore = sink.calls.size

        sink.failNext = true
        var threw = false
        try {
            typer.reviseOutcome("Hallo Welt")
        } catch (expected: IllegalStateException) {
            threw = true
        }
        assertTrue(threw)
        // state unchanged: currentText still matches the editor content
        assertEquals("Hallo", typer.currentText)
        assertEquals("Hallo", sink.text)
        assertEquals(callsBefore, sink.calls.size)

        // repeat with a working sink: the same revision runs cleanly
        assertEquals(TextDelta(0, " Welt"), deltaOf(typer.reviseOutcome("Hallo Welt")))
        assertEquals("Hallo Welt", sink.text)
    }

    @Test fun ownershipMismatchIsRejected() {
        val sink = FakeSink()
        val typer = DeltaTyper(sink)
        typer.reviseOutcome("Hallo")
        val callsBefore = sink.calls.size
        // the user has typed: our own length no longer matches → no writing
        sink.text = "HalloX"
        assertEquals(ReviseKind.W1, typer.reviseOutcome("Hallo Welt").kind)
        assertEquals(callsBefore, sink.calls.size)
        assertEquals("Hallo", typer.currentText) // internal state unchanged
    }

    @Test fun resetStartsFreshSession() {
        val sink = FakeSink()
        val typer = DeltaTyper(sink)
        typer.reviseOutcome("Alt")
        typer.commitUpTo(3)
        typer.reset()
        assertEquals("", typer.currentText)
        assertEquals(0, typer.committedLength)
        assertEquals(TextDelta(0, "Neu"), deltaOf(typer.reviseOutcome("Neu")))
    }

    // (d) S4-02 counter-check: the sink throws in the >60 branch → committedLength
    //     unchanged, the same revision retryable afterwards.

    @Test fun sinkErrorInForcedCommitKeepsStateRetryable() {
        val sink = FakeSink()
        val typer = DeltaTyper(sink)
        typer.reviseOutcome("a".repeat(61))
        val callsBefore = sink.calls.size

        sink.failNext = true
        var threw = false
        try {
            typer.reviseOutcome("b".repeat(61))
        } catch (expected: IllegalStateException) {
            threw = true
        }
        assertTrue(threw)
        // committedLength was NOT frozen in advance — state completely unchanged
        assertEquals(0, typer.committedLength)
        assertEquals("a".repeat(61), typer.currentText)
        assertEquals(callsBefore, sink.calls.size)

        // the same revision is retryable: normal forced-commit flow
        assertEquals(TextDelta(0, "b".repeat(61)), deltaOf(typer.reviseOutcome("b".repeat(61))))
        assertEquals(61, typer.committedLength)
        assertEquals("a".repeat(61) + "b".repeat(61), sink.text)
    }

    @Test fun commonPrefixLengthHelper() {
        assertEquals(0, DeltaTyper.commonPrefixLength("a", "b"))
        assertEquals(3, DeltaTyper.commonPrefixLength("abcX", "abcY"))
        assertEquals(2, DeltaTyper.commonPrefixLength("ab", "ab"))
    }

    // ---- A2a (PLAN_V0133 §1 A2a): basic return {SAME, W1, W3d, DELTA} ----

    /** A2a helper: DELTA with exactly this ONE sink step (kind checked too). */
    private fun deltaOf(outcome: ReviseOutcome): TextDelta {
        assertEquals(ReviseKind.DELTA, outcome.kind)
        return outcome.delta!!
    }

    /** A2a: sink with a foreign-text PREFIX — ownLength counts only our own span;
     *  textBeforeCursor returns the whole text (foreign prefix + our own). */
    private class ForeignPrefixSink : EditorSink {
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
    }

    // A2a red test 1: reviseOutcome() returns CLOSED results —
    // SAME, W1, W3d, DELTA; SAME for an identical target. The null ambiguity
    // ("null" for three different cases) is forbidden in the new production
    // path; [ReviseOutcome.delta] is set only for DELTA (exactly one
    // sink step).
    @Test fun `A2a reviseOutcome returns closed results SAME W1 W3d DELTA`() {
        val sink = FakeSink()
        val typer = DeltaTyper(sink)

        // SAME: identical target — no sink call
        typer.reviseOutcome("Hallo")
        val callsAfterDelta = sink.calls.size
        val same = typer.reviseOutcome("Hallo")
        assertEquals(ReviseKind.SAME, same.kind)
        assertNull("SAME writes nothing", same.delta)
        assertEquals(callsAfterDelta, sink.calls.size)

        // DELTA: minimal tail revision — exactly one sink step
        val written = typer.reviseOutcome("Hallo Welt")
        assertEquals(ReviseKind.DELTA, written.kind)
        assertEquals(TextDelta(0, " Welt"), written.delta)
        assertEquals("Hallo Welt", sink.text)

        // W1: ownership not proven — no writing, divergence visible
        sink.text = "Hallo WeltX" // user types in between
        val w1 = typer.reviseOutcome("Hallo Welt!")
        assertEquals(ReviseKind.W1, w1.kind)
        assertNull("W1 writes nothing", w1.delta)
        assertTrue(typer.divergent)
        assertEquals("Hallo WeltX", sink.text)

        // W3d: divergence > 60 codepoints in the committed area — visible
        // once, no writing (red test 2 checks the resolution)
        val sink2 = FakeSink()
        val typer2 = DeltaTyper(sink2)
        typer2.reviseOutcome("a".repeat(75))
        typer2.commitUpTo(70)
        val w3d = typer2.reviseOutcome("b".repeat(75))
        assertEquals(ReviseKind.W3d, w3d.kind)
        assertNull("W3d writes nothing (visible once)", w3d.delta)
        assertTrue(typer2.divergent)
        assertEquals("a".repeat(75), sink2.text)
    }

    // A2a red test 2: W3d case with 75 codepoints, committedLength=70 — the
    // FIRST blocked attempt reports W3d; the NEXT hypothesis revises our own
    // span ATOMICALLY once ownership is proven again (one sink step, the
    // 60 limit exceeded once) and reports DELTA. No permanent standstill.
    @Test fun `A2a W3d blocks only one hypothesis and the next revises atomically`() {
        val sink = FakeSink()
        val typer = DeltaTyper(sink)
        typer.reviseOutcome("a".repeat(75))
        typer.commitUpTo(70)
        val callsBefore = sink.calls.size

        assertEquals(ReviseKind.W3d, typer.reviseOutcome("b".repeat(75)).kind)
        assertEquals("no write attempt in the blocked step", callsBefore, sink.calls.size)
        assertEquals("a".repeat(75), sink.text)

        // next hypothesis (ownership proven again — editor unchanged):
        val outcome = typer.reviseOutcome("b".repeat(75))
        assertEquals(ReviseKind.DELTA, outcome.kind)
        assertEquals("the WHOLE own span in ONE step", TextDelta(75, "b".repeat(75)), outcome.delta)
        assertEquals("exactly one new sink call", callsBefore + 1, sink.calls.size)
        assertEquals("b".repeat(75), sink.text)
        assertEquals("b".repeat(75), typer.currentText)
        assertEquals("W3a analog: committedLength shrunk to common", 0, typer.committedLength)
        assertFalse("the divergence is resolved", typer.divergent)
    }

    // A2a red test 3: reviseFinal() after a W3d block — the WHOLE own
    // span is revised in EXACTLY ONE sink step, a foreign prefix stays
    // untouched; ownership is checked FIRST (W1 without any write attempt).
    @Test fun `A2a reviseFinal after W3d revises the whole own span in one step`() {
        val sink = ForeignPrefixSink()
        val typer = DeltaTyper(sink)
        typer.reviseOutcome("a".repeat(75))
        typer.commitUpTo(70)
        sink.foreign = "Nutzer " // foreign text BEFORE our own span
        assertEquals(ReviseKind.W3d, typer.reviseOutcome("b".repeat(75)).kind)
        val callsBefore = sink.calls.size

        val final = typer.reviseFinal("b".repeat(70) + " Ende")
        assertEquals(ReviseKind.DELTA, final.kind)
        assertEquals("one sink step for the ENTIRE own span", 1, sink.calls.size - callsBefore)
        assertEquals("only own characters deleted (75 UTF-16)", TextDelta(75, "b".repeat(70) + " Ende"), final.delta)
        assertEquals("Fremdpraefix unangetastet", "Nutzer ", sink.foreign)
        assertEquals("Nutzer " + "b".repeat(70) + " Ende", sink.foreign + sink.own)

        // ownership first: without proof NO final write attempt
        sink.own = sink.own + "X" // user types at the end — ownership gone
        val w1 = typer.reviseFinal("anderes Finale")
        assertEquals(ReviseKind.W1, w1.kind)
        assertEquals("no additional sink call", callsBefore + 1, sink.calls.size)
        assertTrue(typer.divergent)
    }

    // A2a red test 4: SAME flood — 100 identical hypotheses after an
    // initial DELTA yield 100× SAME; sink calls and written characters stay
    // EXACTLY at the level after the initial delta (0 additional sink
    // accesses; SAME produces no sink call).
    @Test fun `A2a 100 identical hypotheses stay SAME without additional sink calls`() {
        val sink = FakeSink()
        val typer = DeltaTyper(sink)
        assertEquals(ReviseKind.DELTA, typer.reviseOutcome("Hallo Welt").kind)
        val callsAfterDelta = sink.calls.size
        val charsAfterDelta = sink.text.length
        repeat(100) {
            assertEquals("identische Hypothese $it", ReviseKind.SAME, typer.reviseOutcome("Hallo Welt").kind)
        }
        assertEquals("no additional sink calls", callsAfterDelta, sink.calls.size)
        assertEquals("no additionally written characters", charsAfterDelta, sink.text.length)
        assertEquals("Hallo Welt", typer.currentText)
    }
}
