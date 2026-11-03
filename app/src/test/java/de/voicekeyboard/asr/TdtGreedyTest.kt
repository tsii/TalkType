package de.voicekeyboard.asr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TdtGreedyTest {

    companion object {
        const val BLANK = AsrContract.BLANK // 8192

        /** JointOut with token argmax [token], duration argmax [dur]. */
        fun out(token: Int, dur: Int, s1: FloatArray, s2: FloatArray): JointOut {
            val t = FloatArray(AsrContract.VOCAB_SIZE)
            val d = FloatArray(AsrContract.NUM_DURATIONS)
            t[token] = 5f
            d[dur] = 5f
            return JointOut(t, d, s1, s2)
        }

        fun marker(v: Int) = FloatArray(AsrContract.PRED_HIDDEN * 2) { (v + it % 7).toFloat() }
    }

    /** Fake that plays back (token, dur) per step and records the received states. */
    class ScriptJoint(private val script: List<Pair<Int, Int>>) : JointStep {
        var calls = 0
        val seenStates1 = ArrayList<FloatArray>()
        val seenStates2 = ArrayList<FloatArray>()
        val seenFrames = ArrayList<Int>()
        private var freshCounter = 100

        override fun step(frameIndex: Int, lastLabel: Int, states1: FloatArray, states2: FloatArray): JointOut {
            seenStates1.add(states1.copyOf())
            seenStates2.add(states2.copyOf())
            seenFrames.add(frameIndex)
            val (token, dur) = script[calls]
            calls++
            val s1 = marker(freshCounter)
            val s2 = marker(freshCounter)
            freshCounter++
            return out(token, dur, s1, s2)
        }
    }

    private fun bigBudget() = 4096

        /** Real filter rule for all IDs used in the test (ASR_CONTRACT §2.4):
         * Vocabulary.isSpecial — unknown IDs and <...> tokens (78, 244) are
         * special; the regular tokens 100/200/300/500 are not. */
        private val miniVocab = Vocabulary.parse(
            "hello 100\nworld 200\ntest 300\nfoo 500\nbar 650\nbaz 700\n<|de|> 78\n<|spltoken0|> 244\n"
        )
        private val IS_SPECIAL: (Int) -> Boolean = { miniVocab.isSpecial(it) }

    // (i) simple sequence Blank/Non-Blank
    @Test fun simpleSequence() {
        val j = ScriptJoint(listOf(BLANK to 1, 100 to 1, 200 to 1))
        val r = TdtGreedy.decode(j, frames = 3, tokenBudget = bigBudget(), isSpecial = IS_SPECIAL)
        assertEquals(listOf(100, 200), r.tokenIds.toList())
        assertFalse(r.incomplete)
        assertNull(r.reason)
        assertEquals(3, r.steps)
        assertEquals(listOf(1, 2), r.frameTimes.toList())
    }

    // (ii) Non-Blank with dur=0 multiple times on the same frame
    @Test fun multipleNonBlankOnSameFrame() {
        val j = ScriptJoint(listOf(100 to 0, 200 to 0, 300 to 1))
        val r = TdtGreedy.decode(j, frames = 1, tokenBudget = bigBudget(), isSpecial = IS_SPECIAL)
        assertEquals(listOf(100, 200, 300), r.tokenIds.toList())
        assertEquals(listOf(0, 0, 0), r.frameTimes.toList())
        // frame index at the joint: frame 0 twice (dur=0 holds), then no further call (t=1 = frames)
        assertEquals(listOf(0, 0, 0), j.seenFrames.toList())
    }

    // (iii) Non-Blank with dur=0 back to back → forced advance after 10 (per-frame limit, §3c)
    @Test fun emittedLimitForcesFrameAdvance() {
        val j = ScriptJoint(List(60) { 500 to 0 })
        val r = TdtGreedy.decode(j, frames = 3, tokenBudget = bigBudget(), isSpecial = IS_SPECIAL)
        assertFalse("no endless run, not step-limited", r.incomplete)
        assertNull(r.reason)
        // forced advance AFTER 10 emissions: frame 0 gets exactly 10, the 11th token
        // falls onto frame 1 (no endless run, §3c).
        assertEquals(10, r.frameTimes.count { it == 0 })
        assertEquals(10, r.frameTimes.count { it == 1 })
        assertEquals(10, r.frameTimes.count { it == 2 })
        assertEquals(30, r.tokenIds.size)
        assertEquals(30, r.steps)
        assertTrue(r.steps <= 3 * (AsrContract.MAX_SYMBOLS_PER_FRAME + 2))
    }

    // (iv) Blank with dur=3 skips 3 frames
    @Test fun blankSkipsThreeFrames() {
        val j = ScriptJoint(listOf(BLANK to 3, 100 to 1))
        val r = TdtGreedy.decode(j, frames = 4, tokenBudget = bigBudget(), isSpecial = IS_SPECIAL)
        assertEquals(2, j.calls)
        assertEquals(listOf(100), r.tokenIds.toList())
        assertEquals(listOf(3), r.frameTimes.toList())
    }

    // (v) Budget-Cut → incomplete, reason="budget"
    @Test fun budgetCutSetsIncomplete() {
        val j = ScriptJoint(List(100) { 100 to 1 })
        val r = TdtGreedy.decode(j, frames = 100, tokenBudget = 4, isSpecial = IS_SPECIAL)
        assertTrue(r.incomplete)
        assertEquals("budget", r.reason)
        assertEquals(4, r.tokenIds.size)
    }

    // (vi) tie in argmax → smallest index; stepLimit safety net
    @Test fun argmaxTieSmallestIndex() {
        // all 0.0: token argmax = 0 (smallest index), duration argmax = 0.
        // Token 0 is a special token → not emitted; dur=0 + Non-Blank + emitted<10
        // → no frame progress → the step limit must kick in as a safety net.
        val flat = JointStep { _, _, _, _ ->
            JointOut(
                FloatArray(AsrContract.VOCAB_SIZE),
                FloatArray(AsrContract.NUM_DURATIONS),
                FloatArray(1280), FloatArray(1280)
            )
        }
        val r = TdtGreedy.decode(flat, frames = 2, tokenBudget = bigBudget(), isSpecial = IS_SPECIAL)
        assertEquals(0, r.tokenIds.size) // special token 0 never emitted
        // dur=0 + Non-Blank (special token counts as an emission) → per-frame limit
        // forces t+1 after 10 steps → regular end after 2 frames, no stepLimit
        assertFalse(r.incomplete)
        assertNull(r.reason)
        assertEquals(20, r.steps)

        // two candidates of equal height → the smaller index wins
        val tie = FloatArray(AsrContract.VOCAB_SIZE)
        tie[700] = 2f; tie[650] = 2f
        val j2 = JointStep { _, _, _, _ ->
            JointOut(tie.copyOf(), floatArrayOf(0f, 5f), FloatArray(1280), FloatArray(1280))
        }
        val r2 = TdtGreedy.decode(j2, frames = 1, tokenBudget = bigBudget(), isSpecial = IS_SPECIAL)
        assertEquals(listOf(650), r2.tokenIds.toList())
    }

    // (vii) special tokens 0..4 are not emitted
    @Test fun specialTokensNotEmitted() {
        val j = ScriptJoint(listOf(0 to 1, 3 to 1, 4 to 1, 100 to 1, BLANK to 1))
        val r = TdtGreedy.decode(j, frames = 5, tokenBudget = bigBudget(), isSpecial = IS_SPECIAL)
        assertEquals(listOf(100), r.tokenIds.toList())
        assertFalse(r.incomplete)
        assertNull(r.reason)
    }

    // (e) SB1-01: complete special-token filter (ASR_CONTRACT §2.4/§7-4):
    // language tokens (<|de|>, ID 78) and <|spltoken0|> (ID 244) must NOT
    // end up in tokenIds when the caller wires up Vocabulary.isSpecial.
    @Test fun specialTokenLanguageTokenNotEmitted() {
        val j = ScriptJoint(listOf(78 to 1, 100 to 1))
        val r = TdtGreedy.decode(j, frames = 2, tokenBudget = bigBudget(), isSpecial = IS_SPECIAL)
        assertEquals(listOf(100), r.tokenIds.toList())
        assertFalse(r.incomplete)
    }

    @Test fun specialTokenSplTokenNotEmitted() {
        val j = ScriptJoint(listOf(244 to 1, 100 to 1))
        val r = TdtGreedy.decode(j, frames = 2, tokenBudget = bigBudget(), isSpecial = IS_SPECIAL)
        assertEquals(listOf(100), r.tokenIds.toList())
        assertFalse(r.incomplete)
    }

    // (viii) states are NOT advanced on Blank (§3a)
    @Test fun statesNotAdvancedOnBlank() {
        val j = ScriptJoint(listOf(BLANK to 1, BLANK to 1, 100 to 1, 200 to 1))
        val r = TdtGreedy.decode(j, frames = 4, tokenBudget = bigBudget(), isSpecial = IS_SPECIAL)
        assertEquals(listOf(100, 200), r.tokenIds.toList())
        assertEquals(4, j.calls)
        // step 2 (Blank) must receive the same zero states as step 1 (no advancing)
        assertTrue("states after blank must stay zero", j.seenStates1[1].all { it == 0f })
        assertTrue(j.seenStates2[1].all { it == 0f })
        // step 3 (Non-Blank) also receives the zero states; only afterwards do they take over
        assertTrue(j.seenStates1[2].all { it == 0f })
        // step 4 (Non-Blank after emission) receives the fresh states from step 3 (marker 102)
        assertEquals(j.seenStates1[3].toList(), marker(102).toList())
        assertEquals(j.seenStates2[3].toList(), marker(102).toList())
    }

    // (h) frames clamping
    @Test fun negativeFramesClamped() {
        val j = ScriptJoint(emptyList())
        val r = TdtGreedy.decode(j, frames = -5, tokenBudget = bigBudget(), isSpecial = IS_SPECIAL)
        assertEquals(0, r.tokenIds.size)
        assertEquals(0, r.steps)
        assertFalse(r.incomplete)
        assertNull(r.reason)
    }

    // (g) step-limit safety net: with a rule-compliant joint every step update
    // forces progress (dur>0, Blank or emitted>=10) — the limit is pure
    // protection. Proof of the step bound via a long dur=0 stream:
    @Test fun stepsStayBelowSafetyNet() {
        val always = JointStep { _, _, _, _ -> out(100, 0, FloatArray(1280), FloatArray(1280)) }
        val frames = 5
        val r = TdtGreedy.decode(always, frames = frames, tokenBudget = 1000, isSpecial = IS_SPECIAL)
        // 10 emissions per frame; on frame 4 the 11th token no longer runs after the
        // forced advance (t=5=frames) — end at 50 tokens.
        assertEquals(50, r.tokenIds.size)
        assertEquals(50, r.steps)
        assertTrue(r.steps <= frames * (AsrContract.MAX_SYMBOLS_PER_FRAME + 2))
        assertFalse(r.incomplete)
        assertNull(r.reason)
    }

    @Test fun zeroFrames() {
        val j = ScriptJoint(emptyList())
        val r = TdtGreedy.decode(j, frames = 0, tokenBudget = bigBudget(), isSpecial = IS_SPECIAL)
        assertEquals(0, r.steps)
        assertFalse(r.incomplete)
    }
}
