package de.voicekeyboard.editor

/**
 * Vereinfachte Graphemcluster-Grenzen ohne ICU (kein `android.icu`, JVM-testbar).
 *
 * Behandelt als **zusammenhängend** (kein Umbruch innerhalb):
 * - Surrogatpaare (Emoji, CJK-Ext)
 * - Kombinationszeichen (Unicode-Kategorien Mn/Mc/Me) — auch `ä` als `a`+U+0308
 * - Variation Selectors (U+FE00–U+FE0F, U+E0100–U+E01EF)
 * - ZWJ-Sequenzen (U+200D) und ZWNJ (U+200C)
 * - CR/LF-Paar, einfache Hangul-Jamo-Komposition (L·V·T)
 *
 * Keine vollständige UAX #29-Implementierung: Regional-Indicator-Paare (Flaggen)
 * und Prepend-Zeichen sind nicht speziell behandelt — dokumentierte Vereinfachung
 * für M4 (DESIGN.md §6: Löschgrenzen an Graphemclustern ausrichten).
 */
object Graphemes {

    private const val ZWJ = '\u200D'
    private const val ZWNJ = '\u200C'

    /** Anzahl Graphemcluster in [s] (NFC/unabhängig von Normalisierung). */
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
     * true, wenn [index] (in UTF-16-Codeeinheiten, 0..length) auf einer
     * Graphemgrenze liegt. `index == 0` und `index == s.length` sind Grenzen.
     */
    fun isBoundary(s: String, index: Int): Boolean {
        require(index in 0..s.length) { "index $index außerhalb 0..${s.length}" }
        if (index == 0 || index == s.length) return true
        val prev = s[index - 1]
        val next = s[index]
        if (Character.isHighSurrogate(prev)) return false // Surrogatpaar nicht trennen
        if (prev == '\r' && next == '\n') return false     // CR/LF
        if (isExtend(next)) return false                  // Mark/VS/ZWJ hängen am Vorgänger
        if (prev == ZWJ) return false                     // ZWJ-Sequenz verbindet Nachfolger
        return !hangulJoins(prev, next)
    }

    /**
     * Start-Index des letzten Graphemclusters in [s] (größte echte Grenze < length).
     * Für leere Strings 0.
     */
    fun lastGraphemeBoundary(s: String): Int {
        if (s.isEmpty()) return 0
        var i = s.length - 1
        while (i > 0 && !isBoundary(s, i)) i--
        return i // 0, falls der ganze String ein Cluster ist
    }

    /** Nächstes Grenz-Index strictly nach [from] (mindestens from+1, höchstens length). */
    private fun nextBoundary(s: String, from: Int): Int {
        var i = from + 1
        while (i < s.length && !isBoundary(s, i)) i++
        return i
    }

    /** Extend/Continue-Zeichen nach (vereinfachtem) UAX #29: hängen am Vorgänger. */
    private fun isExtend(c: Char): Boolean {
        if (c == ZWJ || c == ZWNJ) return true
        if (c in '\uFE00'..'\uFE0F') return true // Variation Selector-16 & Co.
        // Supplementary Variation Selectors (U+E0100–U+E01EF) sind Surrogatpaare und
        // werden über die Surrogatregel geschützt.
        return when (Character.getType(c)) {
            Character.NON_SPACING_MARK.toInt(),       // Mn
            Character.COMBINING_SPACING_MARK.toInt(),  // Mc
            Character.ENCLOSING_MARK.toInt()           // Me
            -> true
            else -> false
        }
    }

    /** Einfache Hangul-L·V·T-Komposition (ohne alte Jamo und Extensions). */
    private fun hangulJoins(prev: Char, next: Char): Boolean {
        val prevIsLorV = prev in '\u1100'..'\u11A7'   // L- und V-Jamo
        val nextIsV = next in '\u1160'..'\u11A7'      // V (inkl. Filler)
        val prevIsVorT = prev in '\u1160'..'\u11FF'   // V und T
        val nextIsT = next in '\u11A8'..'\u11FF'       // T
        return (prevIsLorV && nextIsV) || (prevIsVorT && nextIsT)
    }
}
