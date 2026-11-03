package de.voicekeyboard.editor

/**
 * Simplified grapheme cluster boundaries without ICU (no `android.icu`, JVM-testable).
 *
 * Treated as **connected** (no break inside):
 * - Surrogate pairs (emoji, CJK-Ext)
 * - Combining characters (Unicode categories Mn/Mc/Me) — also `ä` as `a`+U+0308
 * - Variation selectors (U+FE00–U+FE0F, U+E0100–U+E01EF)
 * - ZWJ sequences (U+200D) and ZWNJ (U+200C)
 * - CR/LF pair, simple Hangul Jamo composition (L·V·T)
 *
 * No complete UAX #29 implementation: regional indicator pairs (flags)
 * and prepend characters are not specially handled — documented simplification
 * for M4 (DESIGN.md §6: align delete boundaries with grapheme clusters).
 */
object Graphemes {

    private const val ZWJ = '\u200D'
    private const val ZWNJ = '\u200C'

    /** Number of grapheme clusters in [s] (NFC/independent of normalization). */
    fun count(s: String): Int {
        var clusters = 0
        var i = 0
        while (i < s.length) {
            clusters++
            i = nextBoundary(s, i)
        }
        return clusters
    }

    /**
     * true if [index] (in UTF-16 code units, 0..length) lies on a
     * grapheme boundary. `index == 0` and `index == s.length` are boundaries.
     */
    fun isBoundary(s: String, index: Int): Boolean {
        require(index in 0..s.length) { "index $index outside 0..${s.length}" }
        if (index == 0 || index == s.length) return true
        val prev = s[index - 1]
        val next = s[index]
        if (Character.isHighSurrogate(prev)) return false // do not split a surrogate pair
        if (prev == '\r' && next == '\n') return false     // CR/LF
        if (isExtend(next)) return false                  // mark/VS/ZWJ attach to the predecessor
        if (prev == ZWJ) return false                     // ZWJ sequence connects the successor
        return !hangulJoins(prev, next)
    }

    /**
     * Start index of the last grapheme cluster in [s] (largest true boundary < length).
     * 0 for empty strings.
     */
    fun lastGraphemeBoundary(s: String): Int {
        if (s.isEmpty()) return 0
        var i = s.length - 1
        while (i > 0 && !isBoundary(s, i)) i--
        return i // 0 if the whole string is one cluster
    }

    /** Next boundary index strictly after [from] (at least from+1, at most length). */
    private fun nextBoundary(s: String, from: Int): Int {
        var i = from + 1
        while (i < s.length && !isBoundary(s, i)) i++
        return i
    }

    /** Extend/continue characters per (simplified) UAX #29: attach to the predecessor. */
    private fun isExtend(c: Char): Boolean {
        if (c == ZWJ || c == ZWNJ) return true
        if (c in '\uFE00'..'\uFE0F') return true // variation selector-16 & co.
        // Supplementary variation selectors (U+E0100–U+E01EF) are surrogate pairs and
        // are protected via the surrogate rule.
        return when (Character.getType(c)) {
            Character.NON_SPACING_MARK.toInt(),       // Mn
            Character.COMBINING_SPACING_MARK.toInt(),  // Mc
            Character.ENCLOSING_MARK.toInt()           // Me
            -> true
            else -> false
        }
    }

    /** Simple Hangul L·V·T composition (without old Jamo and extensions). */
    private fun hangulJoins(prev: Char, next: Char): Boolean {
        val prevIsLorV = prev in '\u1100'..'\u11A7'   // L and V Jamo
        val nextIsV = next in '\u1160'..'\u11A7'      // V (incl. filler)
        val prevIsVorT = prev in '\u1160'..'\u11FF'   // V and T
        val nextIsT = next in '\u11A8'..'\u11FF'       // T
        return (prevIsLorV && nextIsV) || (prevIsVorT && nextIsT)
    }
}
