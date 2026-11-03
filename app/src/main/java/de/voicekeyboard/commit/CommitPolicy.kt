package de.voicekeyboard.commit

/**
 * E8.2 commit policy (COMMIT_RULE_DECISION_2.md §3): N=3 agreements for a commit,
 * M=2 words holdback, C3 ≥3 words, C4 word boundary (stays), C5 budget,
 * C6 recount after rollback (via a shrinkable currentCommitted + reset).
 *
 * The **display** operation (LocalAgreement with N=2) stays untouched — here
 * only the commit threshold is raised to 3.
 */
class CommitPolicy(
    private val requiredAgreements: Int = COMMIT_AGREEMENTS,
    private val holdbackWords: Int = COMMIT_HOLDBACK_WORDS,
    private val minWords: Int = COMMIT_MIN_WORDS,
) {

    private val agreement = LocalAgreement(requiredAgreements)

    /** New recording session: discard the agreement history (also after a rollback). */
    fun reset() = agreement.reset()

    /**
     * Submits a hypothesis and returns the newly allowed commit length
     * (E8.2 C1–C6). Order: C1 (N=3 agreements) takes effect in [LocalAgreement];
     * then C2 (hold the last M=2 words of the RAW stable prefix),
     * C3 (≥ 3 committable words), C4 word boundary (C4 cut on the remainder),
     * C5 budget (early abort at the top).
     */
    fun onHypothesis(
        hypothesisText: String,
        audioSamples: Long,
        currentCommitted: Int,
        budgetExceeded: Boolean,
    ): Int {
        if (budgetExceeded) return currentCommitted // C5: cut off ⇒ no proof
        val raw = agreement.acceptRaw(hypothesisText, audioSamples) ?: return currentCommitted
        // C2: hold the last holdbackWords words of the raw stable prefix.
        val kept = trimHoldbackWords(raw) ?: return currentCommitted // C3: too few words
        // C4: only up to and including the last space in the remaining prefix
        val cut = kept.lastIndexOf(' ')
        if (cut < 0) return currentCommitted
        val newLength = cut + 1
        return if (newLength > currentCommitted) newLength else currentCommitted
    }

    /**
     * C2/C3: removes the last [holdbackWords] words from [raw] (separated by
     * spaces). Returns null if fewer than [minWords] words remain afterwards
     * (C3) or nothing viable exists.
     */
    private fun trimHoldbackWords(raw: String): String? {
        // C3: at least minWords complete words in the stable prefix
        // (minWords complete words ⇒ minWords−1 word boundaries; the last
        // word may end without a separator).
        val spaces = raw.count { it == ' ' }
        if (spaces < minWords - 1) return null
        var end = raw.length
        var remaining = holdbackWords
        while (remaining > 0) {
            val ls = raw.lastIndexOf(' ', end - 1)
            if (ls < 0) return null
            end = ls // index of the space before the held word
            remaining--
        }
        if (end < 0) return null
        return raw.substring(0, end + 1) // the separator stays with the committed part
    }

    companion object {
        /** E8.2 C1: commit only from 3 consecutive agreements on. */
        const val COMMIT_AGREEMENTS = 3

        /** E8.2 C2: the last 2 words stay unprotected. */
        const val COMMIT_HOLDBACK_WORDS = 2

        /** E8.2 C3: at least 3 words in the stable prefix. */
        const val COMMIT_MIN_WORDS = 3
    }
}
