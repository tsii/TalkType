package de.voicekeyboard.commit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CommitPolicyTest {

    @Test fun singleHypothesisCommitsNothing() {
        val p = CommitPolicy()
        assertEquals(0, p.onHypothesis("Hallo Welt", 16_000, 0, budgetExceeded = false))
        assertEquals(0, p.onHypothesis("Hallo Welt", 32_000, 0, budgetExceeded = false)) // C1: N=3
    }

    @Test fun twoAgreeingHypothesesDoNotCommitAnymore() {
        // E8.2 C1 (N=3): 2 agreements commit NOTHING anymore — the measured
        // "wrong the same way twice" case is killed.
        val p = CommitPolicy()
        p.onHypothesis("Hallo Welt ABC", 16_000, 0, budgetExceeded = false)
        assertEquals(0, p.onHypothesis("Hallo Welt ABC", 32_000, 0, budgetExceeded = false))
        // only the THIRD agreement releases — C2 holds back the last 2 words
        val c = p.onHypothesis("Hallo Welt ABC", 48_000, 0, budgetExceeded = false)
        assertEquals(6, c) // "Hallo " — Welt/ABC remain unprotected (C2)
    }

    @Test fun wordBoundaryNoHalfWord() {
        val p = CommitPolicy()
        p.onHypothesis("Das ist der erste", 16_000, 0, budgetExceeded = false)
        p.onHypothesis("Das ist der erste", 32_000, 0, budgetExceeded = false)
        // "derzw" is NOT committed; "der" also stays a tail (audio edge);
        // C2 holds back the last 2 words ⇒ commits only the "Das ist " area up
        // to the word boundary of the released remainder
        val c = p.onHypothesis("Das ist der erste", 48_000, 0, budgetExceeded = false)
        assertEquals(8, c) // "Das ist " — C2 holds back "der", "erste"
    }

    @Test fun committedPrefixNeverShrinksFromPolicy() {
        val p = CommitPolicy()
        p.onHypothesis("Hallo Welt ABC", 16_000, 0, budgetExceeded = false)
        p.onHypothesis("Hallo Welt ABC", 32_000, 0, budgetExceeded = false)
        val committed = p.onHypothesis("Hallo Welt ABC", 48_000, 0, budgetExceeded = false)
        assertEquals(6, committed) // "Hallo " — C2 holds back Welt/ABC
        // a shorter/contradictory hypothesis must never shrink the prefix
        assertEquals(6, p.onHypothesis("Hallo", 64_000, committed, budgetExceeded = false))
    }

    @Test fun sameAudioStateDoesNotAdvance() {
        val p = CommitPolicy()
        p.onHypothesis("Hallo Welt ABC", 16_000, 0, budgetExceeded = false)
        assertEquals(0, p.onHypothesis("Hallo Welt ABC", 16_000, 0, budgetExceeded = false))
        p.onHypothesis("Hallo Welt ABC", 32_000, 0, budgetExceeded = false)
        assertEquals(0, p.onHypothesis("Hallo Welt ABC", 32_000, 0, budgetExceeded = false)) // C1: N=3
    }

    @Test fun budgetExceededBlocksNewCommits() {
        val p = CommitPolicy()
        p.onHypothesis("Hallo Welt ABC", 16_000, 0, budgetExceeded = false)
        // token budget exhausted ⇒ hypothesis possibly truncated ⇒ commit nothing new
        assertEquals(0, p.onHypothesis("Hallo Welt ABC!", 32_000, 0, budgetExceeded = true))
    }

    @Test fun streamingProgressively() {
        val p = CommitPolicy()
        // E8.2: N=3 agreements + C2 (hold back the last 2 words)
        var c = p.onHypothesis("So, da bin ich mal gespannt", 16_000, 0, budgetExceeded = false)
        assertEquals(0, c) // 1st hypothesis
        c = p.onHypothesis("So, da bin ich mal gespannt", 32_000, c, budgetExceeded = false)
        assertEquals(0, c) // 2nd — C1: N=3 not yet reached
        c = p.onHypothesis("So, da bin ich mal gespannt", 48_000, c, budgetExceeded = false)
        assertEquals(15, c) // 3rd — C2 holds back "mal", "gespannt" ⇒ "So, da bin ich "
        c = p.onHypothesis("So, da bin ich mal gespannt, ob", 64_000, c, budgetExceeded = false)
        assertEquals(15, c) // agreement counting restarts (divergent) — hold stays at 15
        c = p.onHypothesis("So, da bin ich mal gespannt, ob", 80_000, c, budgetExceeded = false)
        assertEquals(15, c) // 2nd agreement — no progress (N=3)
        c = p.onHypothesis("So, da bin ich mal gespannt, ob", 96_000, c, budgetExceeded = false)
        assertEquals(19, c) // 3rd — "So, da bin ich mal " (C2 holds back "gespannt,", "ob")
    }

    @Test fun committablePrefixAlwaysEndsWithSpace() {
        // invariant from COMMIT_RULE_DECISION: every commit length > 0 points at
        // a space — never into the middle of a word.
        val p = CommitPolicy()
        val hyps = listOf(
            "So, da bin ich" to 16_000L,
            "So, da bin ich mal" to 32_000L,
            "So, da bin ich mal gespannt" to 48_000L,
            "So, da bin ich mal gespannt." to 64_000L,
        )
        var c = 0
        for ((text, audio) in hyps) {
            c = p.onHypothesis(text, audio, c, budgetExceeded = false)
            assertTrue("length $c must be 0 or point at a space", c == 0 || text[c - 1] == ' ')
        }
        // E8.2: the window-3 prefix grows with the hypotheses; C2 holds back the
        // last 2 words each time. After [t2,t3,t4] the prefix is "So, da bin ich mal",
        // committed "So, da bin ".
        assertEquals(11, c) // "So, da bin " (C2 holds back "ich", "mal")
    }

    @Test fun resetRestartsAgreement() {
        val p = CommitPolicy()
        p.onHypothesis("Hallo Welt ABC", 16_000, 0, budgetExceeded = false)
        p.onHypothesis("Hallo Welt ABC", 32_000, 0, budgetExceeded = false)
        p.onHypothesis("Hallo Welt ABC", 48_000, 0, budgetExceeded = false)
        p.reset()
        assertEquals(0, p.onHypothesis("Hallo Welt ABC", 64_000, 0, budgetExceeded = false)) // C1 anew
    }
}
