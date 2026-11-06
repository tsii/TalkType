package de.voicekeyboard.correct

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * T23a (V0.1.47, docs/PLAN_V0147.md §1(b), §2): TypingRules — pure rules, no
 * lexicon. Rule-class tests + gold corpus (testdata/typing_goldens/{de,en}.tsv)
 * + must-not-touch + idempotency + 15-layout smoke.
 *
 * Rule 9: no typed text beyond the test data files — assertion messages carry
 * only row index and class, never content.
 */
class TypingRulesTest {

    // ---------------------------------------------------- sentence capital

    @Test fun sentenceCapitalAtTextStart() {
        assertEquals("Heute ist es warm", TypingRules.apply("heute ist es warm", "de-de"))
    }

    @Test fun sentenceCapitalAfterDot() {
        assertEquals("Es ist warm. Das gefällt mir", TypingRules.apply("es ist warm. das gefällt mir", "de-de"))
    }

    @Test fun sentenceCapitalAfterQuestion() {
        assertEquals("Wirklich? Das war neu", TypingRules.apply("wirklich? das war neu", "de-de"))
    }

    @Test fun sentenceCapitalAfterBang() {
        assertEquals("Stopp! Das reicht", TypingRules.apply("stopp! das reicht", "de-de"))
    }

    @Test fun sentenceCapitalAfterLineBreak() {
        assertEquals("Erste zeile\nZweite zeile", TypingRules.apply("erste zeile\nzweite zeile", "de-de"))
        assertEquals("Gut.\nUnd dann", TypingRules.apply("gut.\nund dann", "de-de"))
    }

    @Test fun sentenceCapitalSkippedForUnknownLanguage() {
        assertEquals("heute ist es warm", TypingRules.apply("heute ist es warm", "xx-xx"))
        // language-neutral spacing rules still run
        assertEquals("a, b", TypingRules.apply("a ,b", "xx-xx"))
    }

    // ------------------------------------------------------------- spacing

    @Test fun spaceBeforePunctuationRemoved() {
        assertEquals("Hallo, Welt", TypingRules.apply("Hallo , Welt", "de-de"))
        assertEquals("Wir fahren morgen. Dann", TypingRules.apply("Wir fahren morgen . Dann", "de-de"))
    }

    @Test fun spaceAfterPunctuationInserted() {
        assertEquals("Hallo, Welt", TypingRules.apply("Hallo,Welt", "de-de"))
        assertEquals("Wow! Super", TypingRules.apply("wow!super", "de-de"))
        assertEquals("Achtung: Feuer", TypingRules.apply("Achtung:Feuer", "de-de"))
    }

    @Test fun digitsAfterPunctuationStay() {
        assertEquals("Die Zahl 1,5 ist klein", TypingRules.apply("Die Zahl 1,5 ist klein", "de-de"))
        assertEquals("Um 14:30 Uhr", TypingRules.apply("Um 14:30 Uhr", "de-de"))
    }

    @Test fun dotDirectlyFollowedByLetterStays() {
        // domain/abbreviation protection (§2): no re-spacing after '.', no caps
        assertEquals("Die Seite beispiel.de bleibt", TypingRules.apply("Die Seite beispiel.de bleibt", "de-de"))
    }

    @Test fun multipleSpacesCollapse() {
        assertEquals("A b c", TypingRules.apply("a  b   c", "de-de"))
        assertEquals("Zeile 1\nZeile 2", TypingRules.apply("zeile 1 \n zeile 2", "de-de"))
        assertEquals("A\n\nB", TypingRules.apply("a\n\nb", "de-de"))
    }

    // ----------------------------------------------------------- en: i -> I

    @Test fun englishStandaloneIIsCapitalized() {
        assertEquals("I think I can", TypingRules.apply("i think i can", "en-us"))
    }

    @Test fun germanStandaloneIStays() {
        assertEquals("Ich bin i", TypingRules.apply("ich bin i", "de-de"))
    }

    // --------------------------------------------------- doubled leading caps

    @Test fun doubledLeadingCapitalsFixed() {
        assertEquals("Die Zeitung ist neu", TypingRules.apply("DIe Zeitung ist neu", "de-de"))
        assertEquals("Dein Auto", TypingRules.apply("DEin Auto", "de-de"))
    }

    @Test fun shortAllCapsStay() {
        assertEquals("ABC ist kurz", TypingRules.apply("ABC ist kurz", "de-de"))
    }

    // ------------------------------------------------------- repeated word

    @Test fun directlyRepeatedWordDropped() {
        // repeat rule runs before the sentence capital: 'die die' survives as pair
        assertEquals("Die Katze schläft", TypingRules.apply("die die Katze schläft", "de-de"))
        assertEquals("Wir fahren morgen los", TypingRules.apply("Wir fahren fahren morgen los", "de-de"))
    }

    @Test fun repeatedWordOnlyExactSameLineAndLength() {
        // case differs -> no drop
        assertEquals("Heute die Die Katze", TypingRules.apply("heute die Die Katze", "de-de"))
        // punctuation between -> no drop
        assertEquals("Heute die, die Katze", TypingRules.apply("heute die, die Katze", "de-de"))
        // length < 2 -> no drop
        assertEquals("A a b", TypingRules.apply("a a b", "de-de"))
    }

    // ------------------------------------------------------ must-not-touch

    @Test fun wordsWithDigitsStay() {
        assertEquals("Heute b2b tag", TypingRules.apply("heute b2b tag", "de-de"))
    }

    @Test fun urlsAndEmailsStay() {
        assertEquals(
            "Die Seite https://beispiel.de bleibt",
            TypingRules.apply("Die Seite https://beispiel.de bleibt", "de-de"),
        )
        assertEquals(
            "Die Mail max@beispiel.de kam",
            TypingRules.apply("Die Mail max@beispiel.de kam", "de-de"),
        )
    }

    @Test fun shortAllCapsNotDroppedAsRepeat() {
        assertEquals("Er sagte HALLO HALLO Welt", TypingRules.apply("Er sagte HALLO HALLO Welt", "de-de"))
    }

    @Test fun innerCapitalsStay() {
        assertEquals("Das iPhone ist neu", TypingRules.apply("Das iPhone ist neu", "de-de"))
    }

    @Test fun wordInteriorsNeverTouched() {
        // no lexicon in TypingRules — interior typos stay
        assertEquals("Das guets Wetter bleibt", TypingRules.apply("Das guets Wetter bleibt", "de-de"))
    }

    // ------------------------------------------------------------ idempotenz

    @Test fun applyIsIdempotent() {
        val inputs = listOf(
            "", "   ", "hallo  Welt", "DIe die Katze", "wow!super mega",
            "i i think", "Die Seite https://x.de ,und so", "HALLO HALLO Welt",
            "a\nb  c", "z.B. und so", "die ,die Katze", "wir fahren fahren fahren los",
        )
        inputs.forEachIndexed { idx, x ->
            val once = TypingRules.apply(x, "de-de")
            assertEquals("idempotenz input#$idx", once, TypingRules.apply(once, "de-de"))
        }
    }

    // -------------------------------------------------- 15-layout smoke (§1b)

    @Test fun allCaseBearingLayoutsCapitalize() {
        val layouts = layoutIds()
        assertEquals(
            "TypingRules.CASE_BEARING_LAYOUT_IDS must mirror assets/layouts",
            layouts.toSet(),
            TypingRules.CASE_BEARING_LAYOUT_IDS,
        )
        for (id in layouts) {
            assertEquals("layout smoke $id", "Abc def", TypingRules.apply("abc def", id))
        }
    }

    @Test fun cyrillicAndGreekSentenceCapitals() {
        assertEquals("Привет мир. Как дела", TypingRules.apply("привет мир. как дела", "ru-ru"))
        assertEquals("Καλημέρα κόσμε. Ευχαριστώ", TypingRules.apply("καλημέρα κόσμε. ευχαριστώ", "el-gr"))
    }

    // ------------------------------------------------- gold corpus (§2, T23a)

    @Test fun goldCorpusDe() {
        val rows = loadRows("de.tsv")
        val byClass = rows.groupBy { it.cls }
        assertEquals("de.tsv total rows", 84, rows.size) // T23a 60 + T23c 15 morph + 5 compound + 3 compound_typo + VK-4 1 region
        assertEquals(20, byClass["clean"]?.size)
        assertEquals(4, byClass["caps"]?.size)
        assertEquals(2, byClass["spacing"]?.size)
        assertEquals(2, byClass["repeat"]?.size)
        assertEquals(32, byClass["lexicon"]?.size)
        assertEquals(15, byClass["morph"]?.size)
        assertEquals(5, byClass["compound"]?.size)
        assertEquals(3, byClass["compound_typo"]?.size)
        assertEquals(1, byClass["region"]?.size) // VK-4 region pipeline golden
        assertGolden(rows, "de-de")
    }

    @Test fun goldCorpusEn() {
        val rows = loadRows("en.tsv")
        val byClass = rows.groupBy { it.cls }
        assertEquals("en.tsv total rows", 15, rows.size) // T23a 10 + T23c 5 morph
        assertEquals(5, byClass["clean"]?.size)
        assertEquals(1, byClass["caps"]?.size)
        assertEquals(1, byClass["spacing"]?.size)
        assertEquals(1, byClass["repeat"]?.size)
        assertEquals(2, byClass["lexicon"]?.size)
        assertEquals(5, byClass["morph"]?.size)
        assertGolden(rows, "en-us")
    }

    /**
     * Rule rows (caps/spacing/repeat) and clean rows must satisfy
     * expected == apply(typed, lang); T23c classes (lexicon/morph/compound)
     * are corrector goldens and only checked for idempotency.
     */
    private fun assertGolden(rows: List<Row>, languageId: String) {
        val ruleClasses = setOf("clean", "caps", "spacing", "repeat")
        rows.forEachIndexed { idx, row ->
            if (row.cls in ruleClasses) {
                assertEquals(
                    "$languageId golden row $idx class=${row.cls}: expected != apply(typed)",
                    row.expected,
                    TypingRules.apply(row.typed, languageId),
                )
            }
            val once = TypingRules.apply(row.typed, languageId)
            assertEquals(
                "$languageId golden row $idx class=${row.cls}: not idempotent",
                once,
                TypingRules.apply(once, languageId),
            )
        }
    }

    // ------------------------------------------------------------- tsv loader

    private data class Row(val typed: String, val expected: String, val cls: String)

    /** Parses typed<TAB>expected<TAB>class; '#' lines and blank lines are ignored. */
    private fun loadRows(name: String): List<Row> {
        val file = resolve(File("testdata/typing_goldens"), name)
        val rows = file.readLines()
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .mapIndexed { idx, line ->
                val parts = line.split('\t')
                assertTrue("bad tsv row $idx in $name: ${parts.size} columns", parts.size == 3)
                Row(parts[0], parts[1], parts[2])
            }
        assertTrue("empty corpus $name", rows.isNotEmpty())
        return rows
    }

    /** Walk-up resolver for testdata/ and assets/layouts (max 6 levels, like KorpusFixture). */
    private fun resolve(dir: File, name: String): File {
        var base = File(System.getProperty("user.dir") ?: ".")
        repeat(6) {
            val cand = File(base, dir.path)
            if (File(cand, name).isFile) return File(cand, name)
            base = base.parentFile ?: return@repeat
        }
        throw IllegalStateException("${dir.path}/$name not found")
    }

    /** Real layout ids from src/main/assets/layouts (see TypingRulesTest.allCaseBearingLayoutsCapitalize). */
    private fun layoutIds(): List<String> {
        var base = File(System.getProperty("user.dir") ?: ".")
        repeat(6) {
            for (rel in listOf("src/main/assets/layouts", "app/src/main/assets/layouts")) {
                val d = File(base, rel)
                if (d.isDirectory) {
                    val names = d.listFiles { f -> f.name.endsWith(".json") }
                        ?: emptyArray()
                    return names.map { it.name.removeSuffix(".json") }.sorted()
                }
            }
            base = base.parentFile ?: return@repeat
        }
        throw IllegalStateException("assets/layouts not found")
    }
}
