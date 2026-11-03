package de.voicekeyboard.ime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * E9.1/E9.2 (LANGUAGE_EMOJI_DECISIONS.md): cycle logic of the language switch
 * and serialization of active_languages — pure JVM tests (LanguageCycle
 * contains no Android types).
 */
class LanguageCycleTest {

    private val canonical = LanguageCycle.CANONICAL

    @Test
    fun `cycle over three active languages in canonical order`() {
        // the order follows CANONICAL, not the input order
        val active = listOf("ru-ru", "de-de", "en-us")
        assertEquals("en-us", LanguageCycle.nextActiveLanguage(active, "de-de"))
        assertEquals("ru-ru", LanguageCycle.nextActiveLanguage(active, "en-us"))
        // backwards run to the end (wrap)
        assertEquals("de-de", LanguageCycle.nextActiveLanguage(active, "ru-ru"))
    }

    @Test
    fun `canonical order not the input order`() {
        // entered in reverse: en-us is before ru-ru in CANONICAL
        val next = LanguageCycle.nextActiveLanguage(listOf("ru-ru", "en-us"), "en-us")
        assertEquals("ru-ru", next)
        val next2 = LanguageCycle.nextActiveLanguage(listOf("ru-ru", "en-us"), "ru-ru")
        assertEquals("en-us", next2)
    }

    @Test
    fun `single language returns null no silent no-op`() {
        assertNull(LanguageCycle.nextActiveLanguage(listOf("de-de"), "de-de"))
        assertNull(LanguageCycle.nextActiveLanguage(listOf("tr-tr"), "tr-tr"))
    }

    @Test
    fun `unknown current language returns the first active`() {
        assertEquals("de-de", LanguageCycle.nextActiveLanguage(listOf("de-de", "fr-fr"), "xx-xx"))
        // first active = canonically first, not list position
        assertEquals("en-us", LanguageCycle.nextActiveLanguage(listOf("ru-ru", "en-us"), "xx-xx"))
    }

    @Test
    fun `initial language current if active otherwise first`() {
        assertEquals("ru-ru", LanguageCycle.initialLanguage(listOf("de-de", "ru-ru"), "ru-ru"))
        assertEquals("de-de", LanguageCycle.initialLanguage(listOf("de-de", "ru-ru"), "fr-fr"))
        assertEquals("de-de", LanguageCycle.initialLanguage(listOf("de-de", "ru-ru"), null))
        // empty active list: default
        assertEquals("de-de", LanguageCycle.initialLanguage(emptyList(), null))
    }

    @Test
    fun `parse sorts deduplicates and discards garbage`() {
        // sorting into canonical order
        assertEquals(
            listOf("de-de", "en-us", "ru-ru"),
            LanguageCycle.parseActive("ru-ru,en-us,de-de"),
        )
        // duplicates
        assertEquals(listOf("de-de"), LanguageCycle.parseActive("de-de,de-de,de-de"))
        // empty strings/whitespace/unknown IDs
        assertEquals(
            listOf("de-de", "fi-fi"),
            LanguageCycle.parseActive(" de-de , , xx-xx,, fi-fi,"),
        )
        // completely unknown/empty => default (one language always forced active)
        assertEquals(LanguageCycle.DEFAULT_ACTIVE, LanguageCycle.parseActive(null))
        assertEquals(LanguageCycle.DEFAULT_ACTIVE, LanguageCycle.parseActive(""))
        assertEquals(LanguageCycle.DEFAULT_ACTIVE, LanguageCycle.parseActive("xx-xx,yy-yy"))
    }

    @Test
    fun `serialization canonically ordered and roundtrip-stable`() {
        val ser = LanguageCycle.serializeActive(listOf("el-gr", "de-de", "de-de", "zz"))
        assertEquals("de-de,el-gr", ser)
        assertEquals(listOf("de-de", "el-gr"), LanguageCycle.parseActive(ser))
        // every real subset: parse ∘ serialize is idempotent
        for (id in canonical) {
            val roundtrip = LanguageCycle.parseActive(LanguageCycle.serializeActive(listOf(id)))
            assertEquals(listOf(id), roundtrip)
        }
    }

    @Test
    fun `canonical list and endonyms cover 15 layouts`() {
        assertEquals(15, LanguageCycle.CANONICAL.size)
        assertEquals(15, LanguageCycle.ENDONYMS.size)
        assertEquals(canonical.toSet(), LanguageCycle.ENDONYMS.keys.toSet())
        // endonym != ISO tag (plain-text display, no code)
        for ((_, pair) in LanguageCycle.ENDONYMS) {
            assertEquals(2, pair.toList().size)
        }
        assertEquals("Русский", LanguageCycle.endonym("ru-ru"))
    }
}
