package de.voicekeyboard.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GraphemesTest {

    // ä as a + U+0308 (combining mark, NFD)

    @Test fun decomposedAccentIsOneGrapheme() {
        val s = "a\u0308"
        assertEquals(1, Graphemes.count(s))
        assertEquals(0, Graphemes.lastGraphemeBoundary(s))
        assertFalse(Graphemes.isBoundary(s, 1))
        assertTrue(Graphemes.isBoundary(s, 2))
    }

    // surrogate pairs (emoji)

    @Test fun surrogatePairIsOneGrapheme() {
        val s = "\uD83D\uDE00" // 😀
        assertEquals(1, Graphemes.count(s))
        assertEquals(0, Graphemes.lastGraphemeBoundary(s))
        assertFalse(Graphemes.isBoundary(s, 1))
        assertTrue(Graphemes.isBoundary(s, 2))
    }

    // emoji ZWJ sequence (family)

    @Test fun zwjSequenceIsOneGrapheme() {
        val s = "\uD83D\uDC68\u200D\uD83D\uDC69\u200D\uD83D\uDC66" // 👨ZWJ👩ZWJ👦
        assertEquals(1, Graphemes.count(s))
        // there is not a single boundary inside the sequence
        for (i in 1 until s.length) {
            assertFalse("index $i must not be a boundary", Graphemes.isBoundary(s, i))
        }
        assertTrue(Graphemes.isBoundary(s, s.length))
        assertEquals(0, Graphemes.lastGraphemeBoundary(s))
    }

    // Devanagari-like combination: base + Mc character (U+093F)

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

    // variation selector (❤ + U+FE0F)

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
        assertEquals(3, Graphemes.lastGraphemeBoundary(s)) // start of "b"
    }

    @Test fun crLfIsOneGrapheme() {
        assertEquals(1, Graphemes.count("\r\n"))
        assertFalse(Graphemes.isBoundary("\r\n", 1))
    }

    @Test fun hangulJamoComposes() {
        val s = "\u1100\u1161\u11A8" // L + V + T → one cluster
        assertEquals(1, Graphemes.count(s))
    }
}
