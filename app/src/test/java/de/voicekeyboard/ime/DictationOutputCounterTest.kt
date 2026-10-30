package de.voicekeyboard.ime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Review F12 (P2): das „Kein Text erkannt"-Urteil hing an einer Netto-Zählung —
 * eine Korrektur von 5 durch 5 Zeichen zählte 0, obwohl Text im Feld stand.
 * Diese Tests fixieren die Brutto-Semantik und den Sitzungs-Reset.
 */
class DictationOutputCounterTest {

    @Test
    fun `neue Sitzung hat keine Ausgabe`() {
        val c = DictationOutputCounter()
        assertFalse(c.hasOutput)
        assertEquals(0, c.outputChars)
    }

    @Test
    fun `geschriebene Zeichen zaehlen als Ausgabe`() {
        val c = DictationOutputCounter()
        c.onCommit(newTextLength = 6)
        c.onCommit(newTextLength = 3)
        assertTrue(c.hasOutput)
        assertEquals(9, c.outputChars)
    }

    @Test
    fun `korrektur tilgt die Ausgabe nicht - F12 P2`() {
        val c = DictationOutputCounter()
        c.onCommit(newTextLength = 5, replaceLength = 0)
        c.onCommit(newTextLength = 0, replaceLength = 5) // nur gelöscht
        assertTrue(c.hasOutput)
        // Korrektur: 5 Zeichen raus, 5 rein — netto 0, brutto 10.
        val k = DictationOutputCounter()
        k.onCommit(newTextLength = 5)
        k.onCommit(newTextLength = 5, replaceLength = 5)
        assertEquals(10, k.outputChars)
        assertTrue(k.hasOutput)
    }

    @Test
    fun `reines Loeschen ohne neue Zeichen ist keine Ausgabe`() {
        val c = DictationOutputCounter()
        c.onCommit(newTextLength = 0, replaceLength = 4)
        assertFalse(c.hasOutput)
    }

    @Test
    fun `reset zwischen zwei Aufnahmen - Leerausgabe feuert erneut`() {
        val c = DictationOutputCounter()
        c.onCommit(newTextLength = 12)
        assertTrue(c.hasOutput)
        c.reset()
        assertFalse("zweite leere Aufnahme muss erneut leer sein", c.hasOutput)
        assertEquals(0, c.outputChars)
    }

    @Test
    fun `negative und null Laengen ändern nichts`() {
        val c = DictationOutputCounter()
        c.onCommit(newTextLength = -3)
        c.onCommit(newTextLength = 0)
        assertFalse(c.hasOutput)
    }
}
