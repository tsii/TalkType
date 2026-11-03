package de.voicekeyboard.asr

import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * Token budget per ASR_CONTRACT.md §6 / KONZEPT.md 3.1:
 * B(s) = max(256, min(4096, ceil(12 * s) + 64))
 *
 * 12 tokens/s lies ~2.3× above the highest measured emission rate (5.3 tokens/s
 * for digit dictation). The budget is NOT a runaway protection — loop protection is
 * exclusively the per-frame limit in TdtGreedy (§3c).
 */
object TokenBudget {

    const val MIN_BUDGET = 256
    const val MAX_BUDGET = 4096
    const val TOKENS_PER_SECOND = 12.0
    const val BASE_TOKENS = 64

    /** Budget for the TOTAL LENGTH of the given audio (incl. left context, KONZEPT 3.1). */
    fun budgetTokens(audioSeconds: Double): Int =
        max(MIN_BUDGET, min(MAX_BUDGET, ceil(TOKENS_PER_SECOND * audioSeconds).toInt() + BASE_TOKENS))

    /** true if one more token would exceed the budget. */
    fun budgetExceeded(emittedTokens: Int, tokenBudget: Int): Boolean = emittedTokens >= tokenBudget
}
