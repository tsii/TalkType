package de.voicekeyboard.ime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * E9.1/E9.2 (LANGUAGE_EMOJI_DECISIONS.md): Zyklus-Logik des Sprachwechsels
 * und Serialisierung von active_languages — reine JVM-Tests (LanguageCycle
 * enthält keine Android-Typen).
 */
class LanguageCycleTest {

    private val canonical = LanguageCycle.CANONICAL

    @Test
    fun `zyklus ueber drei aktive sprachen in kanonischer reihenfolge`() {
        // Reihenfolge folgt CANONICAL, nicht der Eingabereihenfolge
        val active = listOf("ru-ru", "de-de", "en-us")
        assertEquals("en-us", LanguageCycle.nextActiveLanguage(active, "de-de"))
        assertEquals("ru-ru", LanguageCycle.nextActiveLanguage(active, "en-us"))
        // Ruecklauf ans Ende (wrap)
        assertEquals("de-de", LanguageCycle.nextActiveLanguage(active, "ru-ru"))
    }

    @Test
    fun `kanonische reihenfolge nicht die eingabereihenfolge`() {
        // umgekehrt eingegeben: en-us ist in CANONICAL vor ru-ru
        val next = LanguageCycle.nextActiveLanguage(listOf("ru-ru", "en-us"), "en-us")
        assertEquals("ru-ru", next)
        val next2 = LanguageCycle.nextActiveLanguage(listOf("ru-ru", "en-us"), "ru-ru")
        assertEquals("en-us", next2)
    }

    @Test
    fun `einzelsprache liefert null kein stiller no-op`() {
        assertNull(LanguageCycle.nextActiveLanguage(listOf("de-de"), "de-de"))
        assertNull(LanguageCycle.nextActiveLanguage(listOf("tr-tr"), "tr-tr"))
    }

    @Test
    fun `unbekannte aktuelle sprache liefert erste aktive`() {
        assertEquals("de-de", LanguageCycle.nextActiveLanguage(listOf("de-de", "fr-fr"), "xx-xx"))
        // erste aktive = kanonisch erste, nicht listenposition
        assertEquals("en-us", LanguageCycle.nextActiveLanguage(listOf("ru-ru", "en-us"), "xx-xx"))
    }

    @Test
    fun `initialsprache current wenn aktiv sonst erste`() {
        assertEquals("ru-ru", LanguageCycle.initialLanguage(listOf("de-de", "ru-ru"), "ru-ru"))
        assertEquals("de-de", LanguageCycle.initialLanguage(listOf("de-de", "ru-ru"), "fr-fr"))
        assertEquals("de-de", LanguageCycle.initialLanguage(listOf("de-de", "ru-ru"), null))
        // leere aktive Liste: Default
        assertEquals("de-de", LanguageCycle.initialLanguage(emptyList(), null))
    }

    @Test
    fun `parse sortiert dedupliziert und verwirft muell`() {
        // Sortierung in kanonische Reihenfolge
        assertEquals(
            listOf("de-de", "en-us", "ru-ru"),
            LanguageCycle.parseActive("ru-ru,en-us,de-de"),
        )
        // Duplikate
        assertEquals(listOf("de-de"), LanguageCycle.parseActive("de-de,de-de,de-de"))
        // Leerstrings/Whitespace/unbekannte IDs
        assertEquals(
            listOf("de-de", "fi-fi"),
            LanguageCycle.parseActive(" de-de , , xx-xx,, fi-fi,"),
        )
        // komplett unbekannt/leer => Default (eine Sprache immer aktiv erzwungen)
        assertEquals(LanguageCycle.DEFAULT_ACTIVE, LanguageCycle.parseActive(null))
        assertEquals(LanguageCycle.DEFAULT_ACTIVE, LanguageCycle.parseActive(""))
        assertEquals(LanguageCycle.DEFAULT_ACTIVE, LanguageCycle.parseActive("xx-xx,yy-yy"))
    }

    @Test
    fun `serialisierung kanonisch geordnet und roundtrip-stabil`() {
        val ser = LanguageCycle.serializeActive(listOf("el-gr", "de-de", "de-de", "zz"))
        assertEquals("de-de,el-gr", ser)
        assertEquals(listOf("de-de", "el-gr"), LanguageCycle.parseActive(ser))
        // jede echte Teilmenge: parse ∘ serialize ist idempotent
        for (id in canonical) {
            val roundtrip = LanguageCycle.parseActive(LanguageCycle.serializeActive(listOf(id)))
            assertEquals(listOf(id), roundtrip)
        }
    }

    @Test
    fun `kanonische liste und endonyme decken 15 layouts ab`() {
        assertEquals(15, LanguageCycle.CANONICAL.size)
        assertEquals(15, LanguageCycle.ENDONYMS.size)
        assertEquals(canonical.toSet(), LanguageCycle.ENDONYMS.keys.toSet())
        // Endonym != ISO-Tag (Klartext-Anzeige, kein Code)
        for ((_, pair) in LanguageCycle.ENDONYMS) {
            assertEquals(2, pair.toList().size)
        }
        assertEquals("Русский", LanguageCycle.endonym("ru-ru"))
    }
}
