package de.voicekeyboard.ime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Review F12 (P2): the "no text recognized" verdict depended on a net count —
 * a correction of 5 by 5 characters counted 0, even though text was in the
 * field. These tests pin down the gross semantics and the session reset.
 */
class DictationOutputCounterTest {

    @Test
    fun `a new session has no output`() {
        val c = DictationOutputCounter()
        assertFalse(c.hasOutput)
        assertEquals(0, c.outputChars)
    }

    @Test
    fun `written characters count as output`() {
        val c = DictationOutputCounter()
        c.onCommit(newTextLength = 6)
        c.onCommit(newTextLength = 3)
        assertTrue(c.hasOutput)
        assertEquals(9, c.outputChars)
    }

    @Test
    fun `a correction does not clear the output - F12 P2`() {
        val c = DictationOutputCounter()
        c.onCommit(newTextLength = 5, replaceLength = 0)
        c.onCommit(newTextLength = 0, replaceLength = 5) // deleted only
        assertTrue(c.hasOutput)
        // correction: 5 characters out, 5 in — net 0, gross 10.
        val k = DictationOutputCounter()
        k.onCommit(newTextLength = 5)
        k.onCommit(newTextLength = 5, replaceLength = 5)
        assertEquals(10, k.outputChars)
        assertTrue(k.hasOutput)
    }

    @Test
    fun `pure deletion without new characters is no output`() {
        val c = DictationOutputCounter()
        c.onCommit(newTextLength = 0, replaceLength = 4)
        assertFalse(c.hasOutput)
    }

    @Test
    fun `reset between two recordings - an empty output fires again`() {
        val c = DictationOutputCounter()
        c.onCommit(newTextLength = 12)
        assertTrue(c.hasOutput)
        c.reset()
        assertFalse("a second empty recording must be empty again", c.hasOutput)
        assertEquals(0, c.outputChars)
    }

    @Test
    fun `negative and null lengths change nothing`() {
        val c = DictationOutputCounter()
        c.onCommit(newTextLength = -3)
        c.onCommit(newTextLength = 0)
        assertFalse(c.hasOutput)
    }
}
