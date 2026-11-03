package de.voicekeyboard.commit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Test

class LocalAgreementTest {

    // (a) 2 identical hypotheses → prefix released; 1 hypothesis → nothing

    @Test fun singleHypothesisYieldsNothing() {
        val la = LocalAgreement()
        assertNull(la.accept("So, da bin ich", 16_000))
    }

    @Test fun twoAgreeingHypothesesReleasePrefix() {
        val la = LocalAgreement()
        la.accept("So, da bin ich", 16_000)
        // the separator after "ich" lies OUTSIDE the common prefix (h2 grows
        // with ' mal') ⇒ commits only "So, da bin " (COMMIT_RULE_DECISION T-case)
        assertEquals("So, da bin ", la.accept("So, da bin ich mal", 32_000))
    }

    // (b) word boundary: "Das ist der" vs "Das ist derzw" → only "Das ist " (strict reading)

    @Test fun wordBoundaryDoesNotCommitPartialWord() {
        val la = LocalAgreement()
        la.accept("Das ist der", 16_000)
        // "der" is an unconfirmed word at the audio edge — it stays a tail
        assertEquals("Das ist ", la.accept("Das ist derzw", 32_000))
    }

    @Test fun growingWordCutsBackToLastSpace() {
        val la = LocalAgreement()
        la.accept("Das ist derzwo", 16_000) // warm-up: fill the history
        // the common prefix "Das ist derzw" ends in the middle of a word (both
        // hypotheses continue there with a different letter) → only up to the last space
        assertEquals("Das ist ", la.accept("Das ist derzwei", 32_000))
    }

    @Test fun contradictoryPrefixYieldsNothing() {
        val la = LocalAgreement()
        la.accept("abc def", 16_000)
        assertNull(la.accept("xyz", 32_000))
    }

    @Test fun sameAudioStateDoesNotCountTwice() {
        val la = LocalAgreement()
        la.accept("So ist", 16_000)
        assertNull(la.accept("So ist", 16_000)) // the same snapshot is not a 2nd confirmation
        // "ist" stays a tail (no confirmed separator after it within the common prefix)
        assertEquals("So ", la.accept("So ist es", 32_000))
    }

    // (a) S4-01 counter-check: two identical mid-word truncations NEVER commit
    //     the partial word — "derz" at the audio edge is not provable.

    @Test fun identicalMidWordTruncationsCommitNoHalfWord() {
        val la = LocalAgreement()
        la.accept("Das ist derz", 16_000)
        val result = la.accept("Das ist derz", 32_000)
        // the old reading (endsAtWordBoundary) would have frozen "Das ist derz" completely
        assertEquals("Das ist ", result)
        // invariant: committed prefix always ends with a space
        assertTrue(result!!.endsWith(" "))
        assertFalse(result.contains("derz"))
    }

    // (b) "Das ist der " (with space): commit up to and including the space

    @Test fun trailingSpaceInsidePrefixIsCommitted() {
        val la = LocalAgreement()
        la.accept("Das ist der ", 16_000)
        // the separator AFTER "der" lies WITHIN the common prefix ⇒ "der" is confirmed
        assertEquals("Das ist der ", la.accept("Das ist der Zwiebel", 32_000))
    }

    // (c) S4-03 counter-check: divergence WITHIN a surrogate pair

    @Test fun surrogateDivergenceCutsBeforeThePair() {
        val la = LocalAgreement()
        la.accept("Hallo \uD83E\uDD16", 16_000)
        val result = la.accept("Hallo \uD83E\uDD17", 32_000)
        // the common prefix ends on the high surrogate → pull the boundary back
        // ⇒ no lone high surrogate, cut at the space
        assertEquals("Hallo ", result)
        assertTrue(result!!.endsWith(" "))
        assertFalse(Character.isHighSurrogate(result.last()))
    }

    @Test fun committedPrefixInvarianteAlwaysEndsWithSpaceOrNull() {
        val la = LocalAgreement()
        val texts = listOf(
            "So, da bin", "So, da bin ich", "So, da bin ich mal", "So, da bin ich mal gespannt",
        )
        var audio = 16_000L
        for (t in texts) {
            val r = la.accept(t, audio)
            if (r != null) assertTrue("prefix must end on a space: '$r'", r.endsWith(" "))
            audio += 16_000
        }
    }

    @Test fun resetRequiresTwoAgain() {
        val la = LocalAgreement()
        la.accept("Eins zwei", 16_000)
        la.accept("Eins zwei drei", 32_000)
        la.reset()
        assertNull(la.accept("Eins zwei", 48_000))
        assertEquals("Eins ", la.accept("Eins zwei drei", 64_000))
    }

    @Test fun unicodeSurrogatePairStaysIntact() {
        val la = LocalAgreement()
        la.accept("Schöne \uD83E\uDD16 Tastatur", 16_000)
        // cut at the space before the growing word; \uD83E\uDD16 stays intact
        assertEquals("Schöne \uD83E\uDD16 ", la.accept("Schöne \uD83E\uDD16 Tastaturen", 32_000))
    }
}
