package de.voicekeyboard.correct

import java.util.Locale

/**
 * T23c2 (V0.1.47, docs/PLAN_V0147.md §15.3, §14.6d): German compound splitter.
 * ACCEPTANCE ONLY — never a candidate source (an unknown but splittable word
 * gets NO suggestion).
 *
 * word = w1 (+linking s|es|n|en|er|e|∅) + … + wk, k <= 4 parts, every part
 * >= 3 chars and in an affected list (case-folded; head = last part). DP over
 * words <= 40 chars. Hyphenated words split at '-': every segment must be
 * in-list or itself a valid compound.
 *
 * Mode contract: CURRENT -> [lists] contains only the current language's
 * list; ALL_ACTIVE -> parts may come from ANY list in [lists] (§14.6d:
 * mixed compounds like Teammeeting = de Team + en meeting).
 */
object CompoundSplitter {

    private val LINKS = listOf("", "s", "es", "n", "en", "er", "e")
    private const val MAX_PARTS = 4
    /**
     * Part length 4 (§15.3 said >= 3, tightened by T23c calibration): the
     * subtitle lists contain 3-char junk fragments (fah, ads, los, kel, end)
     * that glue into fake compounds. All §15.5 golden parts are >= 4.
     */
    private const val MIN_PART_LEN = 4
    private const val MAX_WORD_LEN = 40
    /**
     * Real-compound guard (T23c calibration): acceptance needs >= 2 parts and a
     * minimum total length of 9 — otherwise subtitle junk decompositions like
     * morgem=mor+gem or bettre=bet+tre would swallow ordinary typos; single
     * list words are handled by the corrector's in-list check.
     */
    private const val MIN_COMPOUND_LEN = 9
    private const val UNREACHABLE = MAX_PARTS + 1

    /** true if [word] is an accepted compound — no correction. Hyphen segments
     *  keep the >= 3 char guard (they are typed words, not glued fragments). */
    fun split(word: String, lists: Map<String, WordList>, mode: WordCorrector.Mode): Boolean {
        if (mode == WordCorrector.Mode.CURRENT && lists.size > 1) {
            // caller contract violation would silently widen acceptance
            throw IllegalArgumentException("CURRENT mode expects a single-language map")
        }
        if (word.isEmpty() || word.length > MAX_WORD_LEN) return false
        if (word.contains('-')) {
            return word.split('-').all { seg ->
                seg.length >= 3 && seg.all { it.isLetter() } &&
                    (inAnyList(seg, lists) || dpValid(seg.lowercase(Locale.ROOT), lists))
            }
        }
        if (!word.all { it.isLetter() }) return false
        return dpValid(word.lowercase(Locale.ROOT), lists)
    }

    private fun dpValid(w: String, lists: Map<String, WordList>): Boolean {
        val n = w.length
        if (n < MIN_COMPOUND_LEN) return false
        val dp = IntArray(n + 1) { UNREACHABLE }
        dp[0] = 0
        for (i in 0 until n) {
            if (dp[i] >= MAX_PARTS) continue
            val links = if (i == 0) listOf("") else LINKS // no leading link
            for (link in links) {
                // the linking element must actually MATCH the word at [i, base)
                if (link.isNotEmpty() && !w.regionMatches(i, link, 0, link.length)) continue
                val base = i + link.length
                if (dp[i] + 1 > MAX_PARTS) continue
                for (len in MIN_PART_LEN..(n - base)) {
                    val end = base + len
                    if (end > n) break
                    if (dp[end] > dp[i] + 1 && inAnyList(w.substring(base, end), lists)) {
                        dp[end] = dp[i] + 1
                    }
                }
            }
        }
        return dp[n] in 2..MAX_PARTS // >= 2 parts (single list words are accepted earlier)
    }

    private fun inAnyList(part: String, lists: Map<String, WordList>): Boolean =
        lists.values.any { it.contains(part, Locale.ROOT) }
}
