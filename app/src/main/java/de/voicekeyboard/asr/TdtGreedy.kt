package de.voicekeyboard.asr

/**
 * Reiner TDT-Greedy-Decoder nach ASR_CONTRACT.md §3 (Rev 2).
 * Keine Android-Abhängigkeiten — Joint-Schritt wird über [JointStep] injiziert.
 *
 * Verbindliche Regeln:
 *  (a) PredNet-States NUR bei Nicht-Blank übernehmen (§3a).
 *  (b) t += dur bei dur > 0 (auch bei Blank); sonst t += 1 nur bei Blank
 *      oder erschöpftem Pro-Frame-Symbolbudget (§3b/§3c).
 *  (c) `emitted` ist ein Pro-Frame-Zähler, Reset bei Frame-Wechsel (§3c).
 *  (d) Argmax deterministisch (kleinster Index bei Gleichstand), kein Softmax.
 *  (e) Sondertokens (per [isSpecial]-Prädikat, Default: IDs 0..4) und BLANK
 *      werden nicht emittiert. Der Aufrufer (ParakeetAsrEngine) verdrahtet
 *      die vollständige Regel aus ASR_CONTRACT §2.4/§7-4: Vocabulary.isSpecial
 *      (<...>-Muster 0..233 und 244..273, plus ID 8192); Ziffern 234..243
 *      bleiben erlaubt.
 *  (f) Token-Budget: bei Erreichen incomplete=true, reason="budget".
 *  (g) Sicherheitsnetz: globales Schrittlimit frames*(MAX_SYMBOLS_PER_FRAME+2).
 *  (h) frames wird auf >= 0 geklemmt.
 */
data class JointOut(
    val tokenLogits: FloatArray,   // [8193]
    val durationLogits: FloatArray, // [5]
    val states1: FloatArray,       // [2*PRED_HIDDEN]
    val states2: FloatArray,       // [2*PRED_HIDDEN]
)

fun interface JointStep {
    /**
     * Ein Joint-Aufruf für Frame [frameIndex] mit letztem Label [lastLabel]
     * und den aktuellen PredNet-States (nur bei Nicht-Blank ändern sie sich).
     */
    fun step(frameIndex: Int, lastLabel: Int, states1: FloatArray, states2: FloatArray): JointOut
}

data class DecodeResult(
    val tokenIds: IntArray,
    val frameTimes: IntArray, // Frame-Index je emittiertem Token
    val incomplete: Boolean,
    val steps: Int,
    val reason: String?,
)

object TdtGreedy {

    const val REASON_BUDGET = "budget"
    const val REASON_STEP_LIMIT = "stepLimit"

    fun decode(joint: JointStep, frames: Int, tokenBudget: Int, isSpecial: (Int) -> Boolean): DecodeResult {
        if (frames < 0) return decode(joint, 0, tokenBudget, isSpecial) // (h) Klemmung
        val maxSteps = frames * (AsrContract.MAX_SYMBOLS_PER_FRAME + 2) // (g)
        val tokens = ArrayList<Int>(minOf(tokenBudget, 1024))
        val times = ArrayList<Int>(64)

        var state1 = FloatArray(2 * AsrContract.PRED_HIDDEN)
        var state2 = FloatArray(2 * AsrContract.PRED_HIDDEN)
        var lastLabel = AsrContract.BLANK
        var emitted = 0   // Pro-Frame-Zähler (c)
        var t = 0
        var steps = 0

        fun argmax(v: FloatArray, from: Int, until: Int): Int { // (d) kleinster Index bei Gleichstand
            var best = from
            var bestVal = v[from]
            for (i in from + 1 until until) {
                if (v[i] > bestVal) { bestVal = v[i]; best = i }
            }
            return best - from
        }

        while (t < frames) {
            if (steps >= maxSteps) {
                return DecodeResult(tokens.toIntArray(), times.toIntArray(), true, steps, REASON_STEP_LIMIT)
            }
            val out = joint.step(t, lastLabel, state1, state2)
            steps++
            val v = out.tokenLogits
            require(v.size >= AsrContract.VOCAB_SIZE) { "tokenLogits too small: ${v.size}" }
            val token = argmax(v, 0, AsrContract.VOCAB_SIZE)
            val dur = argmax(out.durationLogits, 0, out.durationLogits.size)

            if (token != AsrContract.BLANK) {
                // (a) States NUR bei Nicht-Blank übernehmen
                state1 = out.states1
                state2 = out.states2
                lastLabel = token
                emitted++
                // (e) Sondertokens per Prädikat nicht emittieren; Blank kann hier nicht sein
                if (!isSpecial(token)) {
                    if (tokens.size >= tokenBudget) { // (f)
                        return DecodeResult(tokens.toIntArray(), times.toIntArray(), true, steps, REASON_BUDGET)
                    }
                    tokens.add(token)
                    times.add(t)
                }
            }

            if (dur > 0) {                    // (b) gilt auch bei Blank
                t += dur
                emitted = 0                  // (c) Frame-Wechsel
            } else if (token == AsrContract.BLANK || emitted >= AsrContract.MAX_SYMBOLS_PER_FRAME) {
                t += 1
                emitted = 0
            }
        }
        return DecodeResult(tokens.toIntArray(), times.toIntArray(), false, steps, null)
    }
}
