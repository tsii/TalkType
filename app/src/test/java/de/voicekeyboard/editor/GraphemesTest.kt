package de.voicekeyboard.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GraphemesTest {

    // ä als a + U+0308 (Kombinationszeichen, NFD)

    @Test fun decomposedAccentIsOneGrapheme() {
        val s = "a\u0308"
        assertEquals(1, Graphemes.count(s))
        assertEquals(0, Graphemes.lastGraphemeBoundary(s))
        assertFalse(Graphemes.isBoundary(s, 1))
        assertTrue(Graphemes.isBoundary(s, 2))
    }

    // Surrogatpaare (Emoji)

    @Test fun surrogatePairIsOneGrapheme() {
        val s = "\uD83D\uDE00" // 😀
        assertEquals(1, Graphemes.count(s))
        assertEquals(0, Graphemes.lastGraphemeBoundary(s))
        assertFalse(Graphemes.isBoundary(s, 1))
        assertTrue(Graphemes.isBoundary(s, 2))
    }

    // Emoji-ZWJ-Sequenz (Familie)

    @Test fun zwjSequenceIsOneGrapheme() {
        val s = "\uD83D\uDC68\u200D\uD83D\uDC69\u200D\uD83D\uDC66" // 👨ZWJ👩ZWJ👦
        assertEquals(1, Graphemes.count(s))
        // innerhalb der Sequenz gibt es keine einzige Grenze
        for (i in 1 until s.length) {
            assertFalse("Index $i darf keine Grenze sein", Graphemes.isBoundary(s, i))
        }
        assertTrue(Graphemes.isBoundary(s, s.length))
        assertEquals(0, Graphemes.lastGraphemeBoundary(s))
    }

    // Devanagari-artige Kombination: Basis + Mc-Zeichen (U+093F)

    @Test fun devanagariCombiningMarkJoinsBase() {
        val s = "\u0915\u093F" // क + ि
        assertEquals(1, Graphemes.count(s))
        assertFalse(Graphemes.isBoundary(s, 1))
    }

    @Test fun devanagariClusterCountsOnePerBase() {
        val s = "\u0915\u093F\u0915\u093F" // कि कि
        assertEquals(2, Graphemes.count(s))
        assertTrue(Graphemes.isBoundary(s, 2))
    }

    // Variation Selector (❤ + U+FE0F)

    @Test fun variationSelectorJoinsBase() {
        val s = "❤\uFE0F"
        assertEquals(1, Graphemes.count(s))
        assertEquals(0, Graphemes.lastGraphemeBoundary(s))
        assertFalse(Graphemes.isBoundary(s, 1))
    }

    @Test fun plainTextBoundaries() {
        assertEquals(3, Graphemes.count("abc"))
        assertEquals(2, Graphemes.lastGraphemeBoundary("abc"))
        assertTrue(Graphemes.isBoundary("abc", 1))
        assertTrue(Graphemes.isBoundary("", 0))
        assertEquals(0, Graphemes.lastGraphemeBoundary(""))
    }

    @Test fun mixedEmojiAndText() {
        val s = "a\uD83D\uDE00b" // a 😀 b
        assertEquals(3, Graphemes.count(s))
        assertEquals(3, Graphemes.lastGraphemeBoundary(s)) // Start von „b“
    }

    @Test fun crLfIsOneGrapheme() {
        assertEquals(1, Graphemes.count("\r\n"))
        assertFalse(Graphemes.isBoundary("\r\n", 1))
    }

    @Test fun hangulJamoComposes() {
        val s = "\u1100\u1161\u11A8" // L + V + T → 하나 Cluster
        assertEquals(1, Graphemes.count(s))
    }
}
