package de.voicekeyboard.asr

/**
 * Pure TDT greedy decoder per ASR_CONTRACT.md §3 (Rev 2).
 * No Android dependencies — the joint step is injected via [JointStep].
 *
 * Binding rules:
 *  (a) adopt the PredNet states ONLY on non-blank (§3a).
 *  (b) t += dur when dur > 0 (also on blank); otherwise t += 1 only on blank
 *      or an exhausted per-frame symbol budget (§3b/§3c).
 *  (c) `emitted` is a per-frame counter, reset on frame change (§3c).
 *  (d) argmax deterministic (smallest index on a tie), no softmax.
 *  (e) special tokens (via [isSpecial] predicate, default: IDs 0..4) and BLANK
 *      are not emitted. The caller (ParakeetAsrEngine) wires
 *      the complete rule from ASR_CONTRACT §2.4/§7-4: Vocabulary.isSpecial
 *      (<...> pattern 0..233 and 244..273, plus ID 8192); digits 234..243
 *      stay allowed.
 *  (f) token budget: on reaching it incomplete=true, reason="budget".
 *  (g) safety net: global step limit frames*(MAX_SYMBOLS_PER_FRAME+2).
 *  (h) frames is clamped to >= 0.
 */
data class JointOut(
    val tokenLogits: FloatArray,   // [8193]
    val durationLogits: FloatArray, // [5]
    val states1: FloatArray,       // [2*PRED_HIDDEN]
    val states2: FloatArray,       // [2*PRED_HIDDEN]
)

fun interface JointStep {
    /**
     * One joint call for frame [frameIndex] with the last label [lastLabel]
     * and the current PredNet states (they change only on non-blank).
     */
    fun step(frameIndex: Int, lastLabel: Int, states1: FloatArray, states2: FloatArray): JointOut
}

data class DecodeResult(
    val tokenIds: IntArray,
    val frameTimes: IntArray, // frame index per emitted token
    val incomplete: Boolean,
    val steps: Int,
    val reason: String?,
)

object TdtGreedy {

    const val REASON_BUDGET = "budget"
    const val REASON_STEP_LIMIT = "stepLimit"

    fun decode(joint: JointStep, frames: Int, tokenBudget: Int, isSpecial: (Int) -> Boolean): DecodeResult {
        if (frames < 0) return decode(joint, 0, tokenBudget, isSpecial) // (h) clamping
        val maxSteps = frames * (AsrContract.MAX_SYMBOLS_PER_FRAME + 2) // (g)
        val tokens = ArrayList<Int>(minOf(tokenBudget, 1024))
        val times = ArrayList<Int>(64)

        var state1 = FloatArray(2 * AsrContract.PRED_HIDDEN)
        var state2 = FloatArray(2 * AsrContract.PRED_HIDDEN)
        var lastLabel = AsrContract.BLANK
        var emitted = 0   // per-frame counter (c)
        var t = 0
        var steps = 0

        fun argmax(v: FloatArray, from: Int, until: Int): Int { // (d) smallest index on a tie
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
                // (a) adopt the states ONLY on non-blank
                state1 = out.states1
                state2 = out.states2
                lastLabel = token
                emitted++
                // (e) do not emit special tokens per predicate; blank cannot be here
                if (!isSpecial(token)) {
                    if (tokens.size >= tokenBudget) { // (f)
                        return DecodeResult(tokens.toIntArray(), times.toIntArray(), true, steps, REASON_BUDGET)
                    }
                    tokens.add(token)
                    times.add(t)
                }
            }

            if (dur > 0) {                    // (b) also applies on blank
                t += dur
                emitted = 0                  // (c) frame change
            } else if (token == AsrContract.BLANK || emitted >= AsrContract.MAX_SYMBOLS_PER_FRAME) {
                t += 1
                emitted = 0
            }
        }
        return DecodeResult(tokens.toIntArray(), times.toIntArray(), false, steps, null)
    }
}
