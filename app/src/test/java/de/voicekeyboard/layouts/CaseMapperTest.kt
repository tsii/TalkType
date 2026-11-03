package de.voicekeyboard.layouts

import org.junit.Assert.assertEquals
import org.junit.Test

class CaseMapperTest {

    @Test
    fun `de-DE mapping is standard Unicode`() {
        // JDK/Unicode default: ß → SS (multi-character capable); ẞ lives in the layout's shift label.
        assertEquals("ÄÖÜSS", CaseMapper.upper("äöüß", "de-DE"))
        assertEquals("STRASSE", CaseMapper.upper("straße", "de-DE"))
        assertEquals("ä", CaseMapper.lower("Ä", "de-DE"))
    }

    @Test
    fun `tr i maps to İ and ı to I`() {
        assertEquals("İ", CaseMapper.upper("i", "tr-TR"))
        assertEquals("I", CaseMapper.upper("ı", "tr-TR"))
        assertEquals("İSTANBUL", CaseMapper.upper("istanbul", "tr-TR"))
    }

    @Test
    fun `tr I maps to ı and İ to i`() {
        assertEquals("ı", CaseMapper.lower("I", "tr-TR"))
        assertEquals("i", CaseMapper.lower("İ", "tr-TR"))
    }

    @Test
    fun `en-US mapping unaffected by special cases`() {
        assertEquals("HELLO", CaseMapper.upper("hello", "en-US"))
    }
}
