package de.voicekeyboard.correct

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T23c2 (docs/PLAN_V0147.md §15.3, §14.6d): compound splitter, ACCEPTANCE ONLY.
 * Parts >= 3 chars from the affected list(s), linking s|es|n|en|er|e|∅,
 * k <= 4 parts, hyphen split, DP over <= 40 chars.
 */
class CompoundSplitterTest {

    private val de = loadList("de-de")
    private val en = loadList("en-us")
    private val deOnly = mapOf("de-de" to de)
    private val allActive = mapOf("de-de" to de, "en-us" to en)

    @Test fun validGermanCompoundsAreAccepted() {
        assertTrue(CompoundSplitter.split("Fahrradschloss", deOnly, WordCorrector.Mode.CURRENT))
        assertTrue(CompoundSplitter.split("Wandkalenderhalter", deOnly, WordCorrector.Mode.CURRENT))
        assertTrue(CompoundSplitter.split("Steuererklärungsfrist", deOnly, WordCorrector.Mode.CURRENT))
        assertTrue(CompoundSplitter.split("Küchentischlampe", deOnly, WordCorrector.Mode.CURRENT))
    }

    @Test fun hyphenWordsSplitAtDash() {
        assertTrue(CompoundSplitter.split("Fahrrad-Schloss", deOnly, WordCorrector.Mode.CURRENT))
        assertTrue(CompoundSplitter.split("Team-Meeting", deOnly, WordCorrector.Mode.CURRENT))
        assertFalse(CompoundSplitter.split("Fahrrad-Schlos", deOnly, WordCorrector.Mode.CURRENT))
    }

    @Test fun mixedCompoundUsesAnyActiveList() {
        // Team (de) + meeting (de list too, but ALL_ACTIVE allows any list)
        assertTrue(CompoundSplitter.split("Teammeeting", allActive, WordCorrector.Mode.ALL_ACTIVE))
        // CURRENT with only the en list: German parts are not accepted
        assertFalse(CompoundSplitter.split("Fahrradschloss", mapOf("en-us" to en), WordCorrector.Mode.CURRENT))
    }

    @Test fun typoInsideCompoundIsRejected() {
        assertFalse(CompoundSplitter.split("Fahrradschlos", deOnly, WordCorrector.Mode.CURRENT))
        assertFalse(CompoundSplitter.split("Wandkelenderhalter", deOnly, WordCorrector.Mode.CURRENT))
        assertFalse(CompoundSplitter.split("Steuererklärungsfrrist", deOnly, WordCorrector.Mode.CURRENT))
    }

    @Test fun guardsHold() {
        assertFalse(CompoundSplitter.split("ab", deOnly, WordCorrector.Mode.CURRENT)) // parts < 3
        assertFalse(CompoundSplitter.split("a-b", deOnly, WordCorrector.Mode.CURRENT)) // segments < 3
        val long = "Donaudampfschifffahrtsgesellschaftskapitaensmützenteller"
        assertFalse(CompoundSplitter.split(long, deOnly, WordCorrector.Mode.CURRENT)) // > 40 chars
        assertFalse(CompoundSplitter.split("", deOnly, WordCorrector.Mode.CURRENT))
    }

    private fun loadList(lang: String): WordList {
        val f = resolveAsset("wordlists/$lang.bin")
        return WordList.load(f.readBytes())
    }

    private fun resolveAsset(rel: String): java.io.File {
        var base = java.io.File(System.getProperty("user.dir") ?: ".")
        repeat(6) {
            for (p in listOf("src/main/assets/$rel", "app/src/main/assets/$rel")) {
                val f = java.io.File(base, p)
                if (f.isFile) return f
            }
            base = base.parentFile ?: return@repeat
        }
        throw IllegalStateException("assets/$rel not found")
    }
}
