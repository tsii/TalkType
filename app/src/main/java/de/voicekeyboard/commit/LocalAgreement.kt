package de.voicekeyboard.commit

/**
 * LocalAgreement per KONZEPT.md §2.6: only when `commitAgreements` (default 2)
 * consecutive transcriptions yield the same prefix is it considered
 * stable and released up to the **word boundary**.
 *
 * - A single run can be a hallucination (KONZEPT §3.4) → only
 *   2 confirmations (different audio states) release.
 * - Commit only up to the word boundary: the last word always stays correctable
 *   (no half-spoken word frozen; details of the boundary rule in
 *   [stablePrefix]).
 * - Deterministic (precondition: ASR with temperature=0, KONZEPT §2.5).
 * - No intermediate string in the hot path: the comparison runs character-wise over the
 *   stored hypotheses; only the end result is allocated.
 */
class LocalAgreement(private val requiredAgreements: Int = DEFAULT_AGREEMENTS) {

    init {
        require(requiredAgreements in 1..MAX_AGREEMENTS) {
            "requiredAgreements must lie in 1..$MAX_AGREEMENTS (KONZEPT §2.6 / E8.2), was $requiredAgreements"
        }
    }

    private val texts = arrayOfNulls<String>(requiredAgreements)
    private val audioStates = LongArray(requiredAgreements)
    private var count = 0
    private var head = 0 // index of the oldest entry

    /** New recording session: discard the history. */
    fun reset() {
        java.util.Arrays.fill(texts, null)
        count = 0
        head = 0
    }

    /**
     * Submits a new transcription hypothesis ([text], decoded from
     * [audioSamples] audio). **The same audio position does not count as a second
     * confirmation** (DESIGN.md §5.4) — it overwrites the last entry.
     *
     * @return newly stable prefix up to the word boundary, or `null` if the
     *   history is not yet filled or nothing is stable.
     */
    /**
     * Like [accept], but returns the RAW common prefix (without the word boundary
     * trim) — E8.2 applies C2/C3/C4 only in the policy.
     */
    fun acceptRaw(text: String, audioSamples: Long): String? {
        val cut = stablePrefix(text, audioSamples, wordCut = false)
        return cut
    }

    fun accept(text: String, audioSamples: Long): String? =
        stablePrefix(text, audioSamples, wordCut = true)

    private fun stablePrefix(text: String, audioSamples: Long, wordCut: Boolean): String? {
        // last entry = write slot - 1 (also applies while the ring is not yet full)
        val lastIndex = (head + texts.size - 1) % texts.size
        if (count > 0 && audioStates[lastIndex] == audioSamples) {
            // Same audio state: replace the snapshot, no new confirmation.
            texts[lastIndex] = text
            audioStates[lastIndex] = audioSamples
            return null
        }
        texts[head] = text
        audioStates[head] = audioSamples
        head = (head + 1) % texts.size
        if (count < texts.size) count++
        return if (count < requiredAgreements) null else stablePrefix(wordCut)
    }

    /**
     * Common prefix of the last [requiredAgreements] hypotheses, trimmed
     * to the word boundary (KONZEPT §2.6, binding reading from
     * docs/COMMIT_RULE_DECISION.md — review S4-01):
     *
     * Committed is **exclusively** up to and including the last
     * space **inside** the common prefix; the word after it
     * always stays revisable — even if it looks like a complete word
     * (at the audio edge „word finished“ is not provable, a space is).
     * Therefore a word corrected later can never lie in the
     * protected area (otherwise session stall in DeltaTyper).
     *
     * - Invariant: the return value **always ends on `' '`** or is null.
     * - No space in the common prefix ⇒ no commit (null).
     * - Surrogate pairs are never split (S4-03): if the common prefix ends
     *   on a high surrogate, the boundary is moved back by one before
     *   cutting — a lone high surrogate never arises.
     */
    private fun stablePrefix(wordCut: Boolean): String? {
        val oldest = texts[(head + texts.size - count) % texts.size]!!
        var len = oldest.length
        for (i in 1 until count) {
            val t = texts[(head + texts.size - count + i) % texts.size]!!
            val m = minOf(len, t.length)
            var l = 0
            while (l < m && oldest[l] == t[l]) l++
            len = l
            if (len == 0) return null // contradictory prefix ⇒ no commit
        }

        // S4-03: the common prefix must not end on a high surrogate
        // (analogous to DeltaTyper.commonPrefixLength) — BEFORE substring/lastIndexOf.
        if (len > 0 && len < oldest.length && Character.isHighSurrogate(oldest[len - 1])) {
            len--
            if (len == 0) return null
        }

        if (!wordCut) return oldest.substring(0, len) // E8.2: raw prefix

        // S4-01 / COMMIT_RULE_DECISION: last OCCUPIED separator inside the
        // common prefix; the space is committed along, behind it
        // begins the always revisable last word.
        val cut = oldest.lastIndexOf(' ', len - 1)
        return if (cut < 0) null else oldest.substring(0, cut + 1)
    }

    companion object {
        /** Display threshold (partials): 2 confirmations — unchanged (E8.2 separates
         *  the commit from the display threshold; the commit threshold N=3 lives in
         *  [CommitPolicy.COMMIT_AGREEMENTS]). */
        const val DEFAULT_AGREEMENTS = 2

        /** E8.2: the commit variant may demand up to 3 agreements (C1, N=3). */
        const val MAX_AGREEMENTS = 3
    }
}
