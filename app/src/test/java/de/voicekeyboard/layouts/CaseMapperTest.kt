package de.voicekeyboard.layouts

import org.junit.Assert.assertEquals
import org.junit.Test

class CaseMapperTest {

    @Test
    fun `de-DE Mapping ist Standard-Unicode`() {
        // JDK/Unicode-Default: ß → SS (mehrzeichenfähig); ẞ liegt im Shift-Label des Layouts.
        assertEquals("ÄÖÜSS", CaseMapper.upper("äöüß", "de-DE"))
        assertEquals("STRASSE", CaseMapper.upper("straße", "de-DE"))
        assertEquals("ä", CaseMapper.lower("Ä", "de-DE"))
    }

    @Test
    fun `tr i wird zu İ und ı zu I`() {
        assertEquals("İ", CaseMapper.upper("i", "tr-TR"))
        assertEquals("I", CaseMapper.upper("ı", "tr-TR"))
        assertEquals("İSTANBUL", CaseMapper.upper("istanbul", "tr-TR"))
    }

    @Test
    fun `tr I wird zu ı und İ zu i`() {
        assertEquals("ı", CaseMapper.lower("I", "tr-TR"))
        assertEquals("i", CaseMapper.lower("İ", "tr-TR"))
    }

    @Test
    fun `en-US Mapping unberührt von Sonderfällen`() {
        assertEquals("HELLO", CaseMapper.upper("hello", "en-US"))
    }
}
