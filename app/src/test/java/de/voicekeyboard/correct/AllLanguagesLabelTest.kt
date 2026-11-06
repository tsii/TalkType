package de.voicekeyboard.correct

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * T23k (V0.1.47, docs/PLAN_V0147.md §14.4/§14.5): "All languages" space-bar
 * label — 1:1 with tools/labels/all_languages.tsv, <= 14 chars, language
 * subtag resolution with fallback.
 */
class AllLanguagesLabelTest {

    @Test fun labelsMatchTheSourceTsvOneToOne() {
        val tsv = resolveTsv().readLines()
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .associate {
                val p = it.split('\t')
                p[0] to p[1]
            }
        assertTrue("tsv must have 15 rows, was ${tsv.size}", tsv.size == 15)
        assertEquals(tsv, AllLanguagesLabel.entries)
    }

    @Test fun everyLabelFitsTheSpaceBar() {
        for ((tag, label) in AllLanguagesLabel.entries) {
            assertTrue("$tag label too long: ${label.length}", label.length <= 14)
        }
    }

    @Test fun languageSubtagResolution() {
        assertEquals("Alle Sprachen", AllLanguagesLabel.resolve("de-DE"))
        assertEquals("Todos idiomas", AllLanguagesLabel.resolve("pt-BR")) // subtag -> pt
        assertEquals("All languages", AllLanguagesLabel.resolve("en-US"))
    }

    @Test fun caseAndSeparatorHandling() {
        assertEquals("Alle Sprachen", AllLanguagesLabel.resolve("DE-de"))
        assertEquals("Alle Sprachen", AllLanguagesLabel.resolve("de_DE")) // Locale.toString style
        assertEquals("All languages", AllLanguagesLabel.resolve("EN_us"))
        assertEquals("Todos idiomas", AllLanguagesLabel.resolve("PT-br"))
    }

    @Test fun unknownLanguageFallsBackToEnglish() {
        assertEquals("All languages", AllLanguagesLabel.resolve("ja")) // not in the table
        assertEquals("All languages", AllLanguagesLabel.resolve("zh-CN"))
        assertEquals("All languages", AllLanguagesLabel.resolve(""))
        assertEquals("All languages", AllLanguagesLabel.resolve("xx-XX"))
    }

    private fun resolveTsv(): File {
        var base = File(System.getProperty("user.dir") ?: ".")
        repeat(6) {
            val f = File(base, "tools/labels/all_languages.tsv")
            if (f.isFile) return f
            base = base.parentFile ?: return@repeat
        }
        throw IllegalStateException("tools/labels/all_languages.tsv not found")
    }
}
