package de.voicekeyboard.asr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TdtGreedyTest {

    companion object {
        const val BLANK = AsrContract.BLANK // 8192

        /** JointOut mit Token-Argmax [token], Duration-Argmax [dur]. */
        fun out(token: Int, dur: Int, s1: FloatArray, s2: FloatArray): JointOut {
            val t = FloatArray(AsrContract.VOCAB_SIZE)
            val d = FloatArray(AsrContract.NUM_DURATIONS)
            t[token] = 5f
            d[dur] = 5f
            return JointOut(t, d, s1, s2)
        }

        fun marker(v: Int) = FloatArray(AsrContract.PRED_HIDDEN * 2) { (v + it % 7).toFloat() }
    }

    /** Fake, der pro Schritt (token, dur) abspielt und die empfangenen States protokolliert. */
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

        /** Echte Filterregel für alle im Test genutzten IDs (ASR_CONTRACT §2.4):
         * Vocabulary.isSpecial — unbekannte IDs und <...>-Tokens (78, 244) sind
         * special; die regulären Tokens 100/200/300/500 nicht. */
        private val miniVocab = Vocabulary.parse(
            "hello 100\nworld 200\ntest 300\nfoo 500\nbar 650\nbaz 700\n<|de|> 78\n<|spltoken0|> 244\n"
        )
        private val IS_SPECIAL: (Int) -> Boolean = { miniVocab.isSpecial(it) }

    // (i) einfache Sequenz Blank/Non-Blank
    @Test fun simpleSequence() {
        val j = ScriptJoint(listOf(BLANK to 1, 100 to 1, 200 to 1))
        val r = TdtGreedy.decode(j, frames = 3, tokenBudget = bigBudget(), isSpecial = IS_SPECIAL)
        assertEquals(listOf(100, 200), r.tokenIds.toList())
        assertFalse(r.incomplete)
        assertNull(r.reason)
        assertEquals(3, r.steps)
        assertEquals(listOf(1, 2), r.frameTimes.toList())
    }

    // (ii) Non-Blank mit dur=0 mehrfach auf demselben Frame
    @Test fun multipleNonBlankOnSameFrame() {
        val j = ScriptJoint(listOf(100 to 0, 200 to 0, 300 to 1))
        val r = TdtGreedy.decode(j, frames = 1, tokenBudget = bigBudget(), isSpecial = IS_SPECIAL)
        assertEquals(listOf(100, 200, 300), r.tokenIds.toList())
        assertEquals(listOf(0, 0, 0), r.frameTimes.toList())
        // Frame-Index beim Joint: zweimal Frame 0 (dur=0 hält), dann kein weiterer Aufruf (t=1 = frames)
        assertEquals(listOf(0, 0, 0), j.seenFrames.toList())
    }

    // (iii) Non-Blank mit dur=0 hintereinander → Zwangsvorschub nach 10 (Pro-Frame-Limit, §3c)
    @Test fun emittedLimitForcesFrameAdvance() {
        val j = ScriptJoint(List(60) { 500 to 0 })
        val r = TdtGreedy.decode(j, frames = 3, tokenBudget = bigBudget(), isSpecial = IS_SPECIAL)
        assertFalse("no endless run, not step-limited", r.incomplete)
        assertNull(r.reason)
        // Zwangsvorschub NACH 10 Emissionen: Frame 0 bekommt genau 10, der 11. Token
        // fällt auf Frame 1 (kein Endloslauf, §3c).
        assertEquals(10, r.frameTimes.count { it == 0 })
        assertEquals(10, r.frameTimes.count { it == 1 })
        assertEquals(10, r.frameTimes.count { it == 2 })
        assertEquals(30, r.tokenIds.size)
        assertEquals(30, r.steps)
        assertTrue(r.steps <= 3 * (AsrContract.MAX_SYMBOLS_PER_FRAME + 2))
    }

    // (iv) Blank mit dur=3 überspringt 3 Frames
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

    // (vi) Gleichstand bei Argmax → kleinster Index; stepLimit-Sicherheitsnetz
    @Test fun argmaxTieSmallestIndex() {
        // alles 0.0: Token-Argmax = 0 (kleinster Index), dur-Argmax = 0.
        // Token 0 ist Sondertoken → nicht emittiert; dur=0 + Non-Blank + emitted<10
        // → kein Frame-Fortschritt → das Schrittlimit muss als Sicherheitsnetz greifen.
        val flat = JointStep { _, _, _, _ ->
            JointOut(
                FloatArray(AsrContract.VOCAB_SIZE),
                FloatArray(AsrContract.NUM_DURATIONS),
                FloatArray(1280), FloatArray(1280)
            )
        }
        val r = TdtGreedy.decode(flat, frames = 2, tokenBudget = bigBudget(), isSpecial = IS_SPECIAL)
        assertEquals(0, r.tokenIds.size) // Sondertoken 0 nie emittiert
        // dur=0 + Nicht-Blank (Sondertoken zählt als Emission) → Pro-Frame-Limit
        // erzwingt t+1 nach 10 Schritten → reguläres Ende nach 2 Frames, kein stepLimit
        assertFalse(r.incomplete)
        assertNull(r.reason)
        assertEquals(20, r.steps)

        // zwei gleichhohe Kandidaten → kleinerer Index gewinnt
        val tie = FloatArray(AsrContract.VOCAB_SIZE)
        tie[700] = 2f; tie[650] = 2f
        val j2 = JointStep { _, _, _, _ ->
            JointOut(tie.copyOf(), floatArrayOf(0f, 5f), FloatArray(1280), FloatArray(1280))
        }
        val r2 = TdtGreedy.decode(j2, frames = 1, tokenBudget = bigBudget(), isSpecial = IS_SPECIAL)
        assertEquals(listOf(650), r2.tokenIds.toList())
    }

    // (vii) Sondertoken 0..4 wird nicht emittiert
    @Test fun specialTokensNotEmitted() {
        val j = ScriptJoint(listOf(0 to 1, 3 to 1, 4 to 1, 100 to 1, BLANK to 1))
        val r = TdtGreedy.decode(j, frames = 5, tokenBudget = bigBudget(), isSpecial = IS_SPECIAL)
        assertEquals(listOf(100), r.tokenIds.toList())
        assertFalse(r.incomplete)
        assertNull(r.reason)
    }

    // (e) SB1-01: vollstaendiger Sondertoken-Filter (ASR_CONTRACT §2.4/§7-4):
    // Sprach-Tokens (<|de|>, ID 78) und <|spltoken0|> (ID 244) duerfen NICHT
    // in tokenIds landen, wenn der Aufrufer Vocabulary.isSpecial verdrahtet.
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

    // (viii) States werden bei Blank NICHT fortgeschrieben (§3a)
    @Test fun statesNotAdvancedOnBlank() {
        val j = ScriptJoint(listOf(BLANK to 1, BLANK to 1, 100 to 1, 200 to 1))
        val r = TdtGreedy.decode(j, frames = 4, tokenBudget = bigBudget(), isSpecial = IS_SPECIAL)
        assertEquals(listOf(100, 200), r.tokenIds.toList())
        assertEquals(4, j.calls)
        // Schritt 2 (Blank) muss dieselben Null-States erhalten wie Schritt 1 (kein Fortschreiben)
        assertTrue("states after blank must stay zero", j.seenStates1[1].all { it == 0f })
        assertTrue(j.seenStates2[1].all { it == 0f })
        // Schritt 3 (Non-Blank) erhält ebenfalls die Null-States; erst danach übernehmen
        assertTrue(j.seenStates1[2].all { it == 0f })
        // Schritt 4 (Non-Blank nach Emission) erhält die frischen States aus Schritt 3 (Marker 102)
        assertEquals(j.seenStates1[3].toList(), marker(102).toList())
        assertEquals(j.seenStates2[3].toList(), marker(102).toList())
    }

    // (h) frames-Klemmung
    @Test fun negativeFramesClamped() {
        val j = ScriptJoint(emptyList())
        val r = TdtGreedy.decode(j, frames = -5, tokenBudget = bigBudget(), isSpecial = IS_SPECIAL)
        assertEquals(0, r.tokenIds.size)
        assertEquals(0, r.steps)
        assertFalse(r.incomplete)
        assertNull(r.reason)
    }

    // (g) Schrittlimit-Sicherheitsnetz: bei regelkonformem Joint erzwingt jedes
    // Schrittupdate Fortschritt (dur>0, Blank oder emitted>=10) — das Limit ist
    // reiner Schutz. Nachweis der Schrittobegrenze über langen dur=0-Strom:
    @Test fun stepsStayBelowSafetyNet() {
        val always = JointStep { _, _, _, _ -> out(100, 0, FloatArray(1280), FloatArray(1280)) }
        val frames = 5
        val r = TdtGreedy.decode(always, frames = frames, tokenBudget = 1000, isSpecial = IS_SPECIAL)
        // 10 Emissionen pro Frame; auf Frame 4 läuft der 11. Token nach dem
        // Zwangsvorschuf (t=5=frames) nicht mehr — Ende bei 50 Tokens.
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
