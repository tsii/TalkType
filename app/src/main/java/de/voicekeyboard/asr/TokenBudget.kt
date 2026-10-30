package de.voicekeyboard.asr

import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * Token-Budget nach ASR_CONTRACT.md §6 / KONZEPT.md 3.1:
 * B(s) = max(256, min(4096, ceil(12 * s) + 64))
 *
 * 12 Tokens/s liegt ~2,3× über der höchsten gemessenen Emissionsrate (5,3 Tokens/s
 * bei Zifferndiktat). Das Budget ist KEIN Runaway-Schutz — Schleifenschutz ist
 * ausschließlich das Pro-Frame-Limit in TdtGreedy (§3c).
 */
object TokenBudget {

    const val MIN_BUDGET = 256
    const val MAX_BUDGET = 4096
    const val TOKENS_PER_SECOND = 12.0
    const val BASE_TOKENS = 64

    /** Budget für die GESAMTLÄNGE des übergebenen Audios (inkl. Links-Kontext, KONZEPT 3.1). */
    fun budgetTokens(audioSeconds: Double): Int =
        max(MIN_BUDGET, min(MAX_BUDGET, ceil(TOKENS_PER_SECOND * audioSeconds).toInt() + BASE_TOKENS))

    /** true, wenn ein weiterer Token das Budget überschreiten würde. */
    fun budgetExceeded(emittedTokens: Int, tokenBudget: Int): Boolean = emittedTokens >= tokenBudget
}
