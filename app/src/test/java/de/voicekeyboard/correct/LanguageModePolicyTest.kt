package de.voicekeyboard.correct

import de.voicekeyboard.correct.LanguageModePolicy.Mode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T23k (V0.1.47, docs/PLAN_V0147.md §14/§14.5): correction language mode
 * policy — pure decisions only (which lists, mode gating, list-less char
 * guard, en-'i' rule matrix); ranking stays in WordCorrector.
 */
class LanguageModePolicyTest {

    // ------------------------------------------------------------- pref -> mode

    @Test fun defaultIsCurrentAndUnknownPrefFallsBack() {
        assertEquals(Mode.CURRENT, LanguageModePolicy.modeFromPref(null))
        assertEquals(Mode.CURRENT, LanguageModePolicy.modeFromPref(""))
        assertEquals(Mode.CURRENT, LanguageModePolicy.modeFromPref("gibberish"))
        assertEquals(Mode.CURRENT, LanguageModePolicy.modeFromPref("current"))
        assertEquals(Mode.ALL_ACTIVE, LanguageModePolicy.modeFromPref("all"))
    }

    // -------------------------------------------------------- affected lists

    @Test fun currentAffectsOnlyTheCurrentLanguageList() {
        val available = setOf("de-de", "en-us")
        assertEquals(
            listOf("de-de"),
            LanguageModePolicy.affectedLists(Mode.CURRENT, "de-de", listOf("de-de", "en-us"), available),
        )
        // current language without a list -> rules only (no list affected)
        assertEquals(
            emptyList<String>(),
            LanguageModePolicy.affectedLists(Mode.CURRENT, "fr-fr", listOf("fr-fr"), available),
        )
    }

    @Test fun allActiveWithOneLanguageBehavesLikeCurrent() {
        val available = setOf("de-de", "en-us")
        val current = LanguageModePolicy.affectedLists(Mode.CURRENT, "de-de", listOf("de-de"), available)
        val all = LanguageModePolicy.affectedLists(Mode.ALL_ACTIVE, "de-de", listOf("de-de"), available)
        assertEquals(current, all)
        assertEquals(listOf("de-de"), all)
    }

    @Test fun allActiveIntersectsActiveLanguagesWithExistingLists() {
        val available = setOf("de-de", "en-us")
        assertEquals(
            listOf("de-de", "en-us"), // current language first (tie-break anchor)
            LanguageModePolicy.affectedLists(Mode.ALL_ACTIVE, "de-de", listOf("en-us", "de-de", "fr-fr"), available),
        )
        // active languages without lists are dropped (fr-fr), dedup keeps one entry
        assertEquals(
            listOf("en-us"),
            LanguageModePolicy.affectedLists(Mode.ALL_ACTIVE, "en-us", listOf("en-us", "fr-fr", "en-us"), available),
        )
    }

    // ------------------------------------------------------------ acceptance

    @Test fun acceptanceSearchesAnyAffectedListCaseInsensitively() {
        val lists = mapOf(
            "de-de" to loadTinyList("de-de", "Morgen", "Sonne"),
            "en-us" to loadTinyList("en-us", "the", "Meeting"),
        )
        assertTrue(
            LanguageModePolicy.accepts("morgen", Mode.CURRENT, "de-de", listOf("de-de"), lists),
        )
        // CURRENT never accepts from another list
        assertFalse(
            LanguageModePolicy.accepts("the", Mode.CURRENT, "de-de", listOf("de-de", "en-us"), lists),
        )
        // ALL_ACTIVE accepts from ANY affected list, case-insensitive per caseLocale
        assertTrue(
            LanguageModePolicy.accepts("MEETING", Mode.ALL_ACTIVE, "de-de", listOf("de-de", "en-us"), lists),
        )
        assertTrue(
            LanguageModePolicy.accepts("The", Mode.ALL_ACTIVE, "de-de", listOf("de-de", "en-us"), lists),
        )
        assertFalse(
            LanguageModePolicy.accepts("maison", Mode.ALL_ACTIVE, "de-de", listOf("de-de", "en-us"), lists),
        )
    }

    // -------------------------------------------------- §14.3 list-less guard

    @Test fun listlessCharGuardSkipsWordsWithExclusiveChars() {
        // chars that ONLY exist on layouts without lists (T23g feeds this from layout JSON)
        val listlessExclusiveChars = setOf('д', 'ж')
        assertTrue(LanguageModePolicy.skipsForListlessChars("да", listlessExclusiveChars))
        assertTrue(LanguageModePolicy.skipsForListlessChars("undд", listlessExclusiveChars))
        assertFalse(LanguageModePolicy.skipsForListlessChars("hallo", listlessExclusiveChars))
        assertFalse(LanguageModePolicy.skipsForListlessChars("hallo", emptySet()))
    }

    // ------------------------------------------------- §14.3 en-'i' matrix

    @Test fun englishIRuleMatrix() {
        assertTrue(LanguageModePolicy.appliesEnglishIRule(Mode.CURRENT, "en-us", listOf("en-us")))
        assertFalse(LanguageModePolicy.appliesEnglishIRule(Mode.CURRENT, "de-de", listOf("de-de", "en-us")))
        assertTrue(LanguageModePolicy.appliesEnglishIRule(Mode.ALL_ACTIVE, "de-de", listOf("de-de", "en-us")))
        assertFalse(LanguageModePolicy.appliesEnglishIRule(Mode.ALL_ACTIVE, "de-de", listOf("de-de", "fr-fr")))
        assertTrue(LanguageModePolicy.appliesEnglishIRule(Mode.ALL_ACTIVE, "en-us", listOf("en-us")))
    }

    // ---------------------------------------------------------------- helpers

    /** Minimal VKWL1 blob (same format as build_wordlist.py) for pure-JVM tests. */
    private fun loadTinyList(langId: String, vararg words: String): WordList {
        val out = java.io.ByteArrayOutputStream()
        out.write("VKWL1".toByteArray(Charsets.US_ASCII))
        val lang = langId.toByteArray(Charsets.US_ASCII)
        out.write(lang.size)
        out.write(lang)
        val count = words.size
        out.write(count and 0xFF)
        out.write((count shr 8) and 0xFF)
        out.write((count shr 16) and 0xFF)
        out.write((count shr 24) and 0xFF)
        for (w in words) {
            val data = w.toByteArray(Charsets.UTF_8)
            out.write(data.size and 0xFF)
            out.write((data.size shr 8) and 0xFF)
            out.write(data)
        }
        return WordList.load(out.toByteArray())
    }
}
