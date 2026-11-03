package de.voicekeyboard.commit

import de.voicekeyboard.editor.DeltaTyper
import de.voicekeyboard.editor.EditorSink
import de.voicekeyboard.editor.ReviseKind
import de.voicekeyboard.editor.TextDelta
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * E8 test cases T1–T6 from docs/COMMIT_RULE_DECISION_2.md §5 — pure JVM,
 * pipeline CommitPolicy + DeltaTyper (without emulator). T1 is the measured
 * device case (s1_realEngine_realWav).
 */
class CommitE8Test {

    /** Fake sink as in DeltaTyperTest: applies deltas at the cursor. */
    private open class FakeSink : EditorSink {
        var text: String = ""
        val calls = mutableListOf<TextDelta>()

        override fun replaceBeforeCursor(replaceLength: Int, newText: String) {
            calls += TextDelta(replaceLength, newText)
            text = text.dropLast(replaceLength) + newText
        }

        override fun textBeforeCursor(maxChars: Int): CharSequence? =
            text.takeLast(minOf(maxChars, text.length))

        override fun ownLength(): Int = text.length
    }

    /** Small pipeline wrapper: one hypothesis → policy → typer (like the controller). */
    private class Pipeline(
        val sink: FakeSink = FakeSink(),
        val policy: CommitPolicy = CommitPolicy(),
        val typer: DeltaTyper = DeltaTyper(sink),
    ) {
        var audioSamples = 0L

        fun feed(text: String, budgetExceeded: Boolean = false, advanceMs: Long = 16_000) {
            audioSamples += advanceMs
            val committed = policy.onHypothesis(text, audioSamples, typer.committedLength, budgetExceeded)
            typer.commitUpTo(committed)
            typer.reviseOutcome(text)
        }
    }

    private val GOLDEN =
        "So, jetzt bin ich gespannt, ob die Dikti-Funktion auf dem Telefon funktioniert."

    // T1 — the measured device case: "… Vikti" twice, then correct up to Golden.
    // Expectation: final text = Golden; no null stall; rollback ≤ 60 codepoints.
    @Test fun t1_viktiCaseEndsAtGolden() {
        val p = Pipeline()
        // build-up phase as measured: the error is written first …
        p.feed("So, jetzt bin ich gespannt, ob die Vikti")
        p.feed("So, jetzt bin ich gespannt, ob die Vikti-Funktion")
        p.feed("So, jetzt bin ich gespannt, ob die Vikti-Funktion auf dem")
        // … and later corrected by the model:
        p.feed("So, jetzt bin ich gespannt, ob die Dikti-Funktion auf dem")
        p.feed("So, jetzt bin ich gespannt, ob die Dikti-Funktion auf dem Telefon")
        p.feed(GOLDEN)
        p.feed(GOLDEN)
        assertEquals(GOLDEN, p.sink.text)
        // no divergence end state
        assertFalse(p.typer.divergent)
    }

    // T1 addendum: the rollback really exists (W3c) — divergence is corrected in ONE
    // delta, committedLength shrinks to the common prefix.
    @Test fun t1_rollbackIsAtomicAndAllowsShrinking() {
        val sink = FakeSink()
        val typer = DeltaTyper(sink)
        typer.reviseOutcome("ob die Vikti-Funktion")
        typer.commitUpTo("ob die Vikti-".length) // faulty commit (D2/shrinking needed)
        assertTrue(typer.committedLength > 0)
        // correct hypothesis diverges within the committed area:
        val outcome = typer.reviseOutcome("ob die Dikti-Funktion")
        assertEquals("W3c: rollback writes (no stall)", ReviseKind.DELTA, outcome.kind)
        assertEquals("ob die Dikti-Funktion", sink.text)
        assertEquals(7, typer.committedLength) // W3a: shrunk to common=7
        assertFalse(typer.divergent)
        // ONE delta: replaceLength = removed UTF-16 portion
        val last = sink.calls.last()
        assertEquals("Vikti-Funktion".length, last.replaceLength)
    }

    // T2 — user types in between (ownership lost): no more writing (W1)
    @Test fun t2_ownershipLostWritesNothing() {
        val sink = object : FakeSink() {
            var lost = false
            override fun ownLength(): Int = if (lost) 0 else text.length
        }
        val typer = DeltaTyper(sink)
        typer.reviseOutcome("So, jetzt bin")
        val callsBefore = sink.calls.size
        sink.lost = true // foreign text: ownership gone
        assertEquals(ReviseKind.W1, typer.reviseOutcome("So, jetzt bin ich").kind)
        assertEquals(callsBefore, sink.calls.size) // nothing written
        assertTrue(typer.divergent)
        assertEquals("So, jetzt bin", sink.text) // unchanged
    }

    // T3 — divergence of 75 codepoints: no rollback (W3d), divergent=true
    @Test fun t3_tooLargeDivergenceLeavesText() {
        val sink = FakeSink()
        val typer = DeltaTyper(sink)
        typer.reviseOutcome("a".repeat(75))
        typer.commitUpTo(70)
        assertEquals("W3d: 70 > 60 ⇒ no rollback", ReviseKind.W3d, typer.reviseOutcome("b".repeat(75)).kind)
        assertTrue(typer.divergent)
        assertEquals("a".repeat(75), sink.text) // text stays
    }

    // T4 — 2 agreements on a wrong word, 3rd correct:
    //      C1 (N=3) prevents "Vikti" from ever being committed.
    @Test fun t4_twoAgreementsDoNotCommitWrongWord() {
        val p = Pipeline()
        p.feed("ob die Vikti")
        p.feed("ob die Vikti-Funktion") // 2nd agreement — the OLD behavior would commit
        assertEquals(0, p.typer.committedLength) // C1 (N=3): "Vikti" is NOT committed
        p.feed("ob die Vikti-Funktion auf")
        // correct hypotheses follow; the error was never pinned — no rollback needed
        p.feed("ob die Dikti-Funktion auf")
        p.feed("ob die Dikti-Funktion auf")
        assertEquals("ob die Dikti-Funktion auf", p.sink.text)
        assertFalse(p.typer.divergent)
        // "Vikti" was NEVER in the protected area — at most "ob " is committed
        assertTrue(p.typer.committedLength <= "ob ".length)
    }

    // T5 — stable edge with 3 words: only the 1st word committed (C2/C3)
    @Test fun t5_holdbackKeepsLastTwoWordsUncommitted() {
        val p = Pipeline()
        // 3 identical hypotheses with 3 words
        p.feed("eins zwei drei")
        p.feed("eins zwei drei")
        p.feed("eins zwei drei")
        // C2 (M=2) + C3 (≥3 words): only "eins " is committed
        assertEquals(5, p.typer.committedLength) // "eins "
        // the last 2 words remain revisable:
        p.feed("eins zwei vier")
        assertEquals("eins zwei vier", p.sink.text)
    }

    // T6 — rollback across a sentence terminator: no rollback (W3d boundary)
    @Test fun t6_noRollbackAcrossSentenceTerminator() {
        val sink = FakeSink()
        val typer = DeltaTyper(sink)
        typer.reviseOutcome("Das ist der erste Teil. Und noch")
        typer.commitUpTo("Das ist der erste Teil. Un".length) // faulty commit WITH terminator
        // correct hypothesis diverges BEFORE the sentence terminator (common < committed):
        assertEquals(
            "sentence terminator inside the deletion area ⇒ W3d",
            ReviseKind.W3d,
            typer.reviseOutcome("Das ist der erste Teil! Und noch mehr").kind,
        )
        assertTrue(typer.divergent)
        assertEquals("Das ist der erste Teil. Und noch", sink.text) // stays
    }
    // T7 — units (B1): limit in CODEPOINTS, delete length in UTF-16.
    // own = 15×😀 (30 UTF-16) + 45 BMP = 60 codepoints / 75 UTF-16 units.
    // Divergence at the start ⇒ rollback allowed (60 ≤ 60), replaceLength == 75.
    @Test fun t7_emojiUnitsCodepointLimitUtf16Delete() {
        val sink = FakeSink()
        val typer = DeltaTyper(sink)
        val emoji = "\uD83D\uDE00" // 😀 = 2 UTF-16
        val own = emoji.repeat(15) + "a".repeat(45) // 60 Codepoints, 75 UTF-16
        typer.reviseOutcome(own)
        typer.commitUpTo(own.length) // everything committed
        val target = "b" + own.substring(1) // divergence at the start
        val outcome = typer.reviseOutcome(target)
        assertEquals("60 codepoints <= 60 ⇒ W3c allowed", ReviseKind.DELTA, outcome.kind)
        assertEquals(75, outcome.delta!!.replaceLength) // UTF-16 units, not codepoints
        assertEquals(target, sink.text)
    }

    // T8 — short session (B3): 1 word, never committed; final writes exactly once.
    @Test fun t8_shortSessionFinalWritesExactlyOnce() {
        val p = Pipeline()
        p.feed("Danke.")
        assertEquals(0, p.typer.committedLength) // C3: <3 words ⇒ never committed
        assertEquals("Danke.", p.sink.text)
        // final decode (same text, same state): no duplicate, no remainder
        p.feed("Danke.")
        assertEquals("Danke.", p.sink.text)
    }

    // T9 (correctly built): sink with foreign-text prefix; ownLength counts only the own span
    private class ForeignPrefixSink : EditorSink {
        var foreign = ""
        var own = ""
        val calls = mutableListOf<TextDelta>()
        override fun replaceBeforeCursor(replaceLength: Int, newText: String) {
            calls += TextDelta(replaceLength, newText)
            own = own.dropLast(replaceLength) + newText
        }
        override fun textBeforeCursor(maxChars: Int): CharSequence? =
            (foreign + own).takeLast(minOf(maxChars, foreign.length + own.length))
        override fun ownLength(): Int = own.length
    }

    @Test fun t9_rollbackDeletesOnlyOwnChars() {
        val sink = ForeignPrefixSink()
        val typer = DeltaTyper(sink)
        typer.reviseOutcome("Vikti-Funktion") // own area
        typer.commitUpTo("Vikti-".length)
        sink.foreign = "Hallo " // user types to the LEFT of it — ownStart shifts
        // ownership stays valid: ownLength == own.length, the context suffix matches
        val outcome = typer.reviseOutcome("Dikti-Funktion")
        assertEquals("W3c despite foreign text before it", ReviseKind.DELTA, outcome.kind)
        assertEquals("Hallo Dikti-Funktion", sink.foreign + sink.own)
        // the rollback deleted exclusively own characters:
        assertEquals("Hallo ", sink.foreign)
        assertEquals("Dikti-Funktion", sink.own)
        assertEquals("Vikti-Funktion".length, outcome.delta!!.replaceLength)
    }
}
