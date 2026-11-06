package de.voicekeyboard.correct

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Locale

/**
 * T23c (docs/PLAN_V0147.md §2, §14.6, §15.5): corrector goldens over the real
 * lists + layouts. Rule 9: assertion messages carry only counters/indices —
 * never typed text beyond the committed corpus files.
 *
 * Calibration (threshold 1.6, brakes per WordCorrector KDoc): 30 of the 32
 * fixable lexicon rows fix (>= 90 % gate); two documented wrong-fixes remain
 * (heuet->heuer, schient->schiebt: 'weit'-transposition 0.8 loses against an
 * adjacent-key substitution 0.7 — U1 context-scorer candidates).
 */
class CorrectorGoldenTest {

    private val deList = load("de-de")
    private val enList = load("en-us")
    private val lists = mapOf("de-de" to deList, "en-us" to enList)

    // ------------------------------------------------------- lexicon fix rate

    @Test fun lexiconFixRateAtLeast90Percent() {
        val rows = lexiconPairs("de.tsv", "de-de") + lexiconPairs("en.tsv", "en-us")
        val listFor = mapOf("de-de" to deList, "en-us" to enList)
        val fixable = rows.filter { !listFor.getValue(it.third).contains(it.first, Locale.ROOT) }
        assertTrue("fixable set too small: ${fixable.size}", fixable.size >= 30)
        val fixed = fixable.count { (typed, expected, layout) ->
            val c = WordCorrector.correct(typed, layout, lists, WordCorrector.Mode.CURRENT)
            c != null && c.text == expected
        }
        assertTrue("fix rate $fixed/${fixable.size} < 90%", fixed * 100 >= fixable.size * 90)
    }

    @Test fun collisionsStayInList() {
        // wirf/schon are real words in the de list -> no correction
        assertNull(WordCorrector.correct("wirf", "de-de", lists, WordCorrector.Mode.CURRENT))
        assertNull(WordCorrector.correct("schon", "de-de", lists, WordCorrector.Mode.CURRENT))
    }

    // -------------------------------------------------------------- clean FP

    @Test fun cleanCorpusWordsStayUncorrected() {
        var total = 0
        var fp = 0
        for ((tsv, layout) in listOf("de.tsv" to "de-de", "en.tsv" to "en-us")) {
            for (row in corpusRows(tsv).filter { it.third == "clean" }) {
                for (w in relevantWords(row.first)) {
                    total++
                    if (WordCorrector.correct(w, layout, lists, WordCorrector.Mode.CURRENT) != null) fp++
                }
            }
        }
        assertTrue("clean FP $fp/$total exceeds 1%", fp * 100 <= total)
    }

    // ----------------------------------------------------------------- morph

    @Test fun morphRowsCleanUnchangedTyposFixToInflectedForm() {
        var clean = 0
        var typo = 0
        for ((tsv, layout) in listOf("de.tsv" to "de-de", "en.tsv" to "en-us")) {
            for (row in corpusRows(tsv).filter { it.third == "morph" }) {
                if (row.first == row.second) {
                    clean++
                    for (w in relevantWords(row.first)) {
                        assertNull("morph clean fp", WordCorrector.correct(w, layout, lists, WordCorrector.Mode.CURRENT))
                    }
                } else {
                    typo++
                    val (typedWord, expectedWord) = singleDiff(row)
                        ?: error("morph row without single diff #${row.hashCode() % 1000}")
                    val c = WordCorrector.correct(typedWord, layout, lists, WordCorrector.Mode.CURRENT)
                    assertNotNull("morph typo not fixed (row idx)", c)
                    assertEquals("inflected form", expectedWord, c!!.text)
                }
            }
        }
        assertEquals("de15 + en5", 20, clean + typo)
    }

    // -------------------------------------------------------------- compound

    @Test fun compoundRowsValidAcceptedTypoNoSuggestion() {
        val valid = corpusRows("de.tsv").filter { it.third == "compound" }
        val typo = corpusRows("de.tsv").filter { it.third == "compound_typo" }
        assertEquals(5, valid.size)
        assertEquals(3, typo.size)
        for (row in valid) {
            val word = longestWord(row.first)
            assertNull("valid compound must not be corrected", WordCorrector.correct(word, "de-de", lists, WordCorrector.Mode.CURRENT))
            assertTrue("valid compound must split", CompoundSplitter.split(word, mapOf("de-de" to deList), WordCorrector.Mode.CURRENT))
        }
        for (row in typo) {
            val word = longestWord(row.first)
            assertNull("typo compound: no suggestion in 0.1.47 (§15.5)", WordCorrector.correct(word, "de-de", lists, WordCorrector.Mode.CURRENT))
            assertFalse("typo compound must not split", CompoundSplitter.split(word, mapOf("de-de" to deList), WordCorrector.Mode.CURRENT))
        }
    }

    // -------------------------------------------------------------------- mix

    @Test fun mixRowsPerMode() {
        val rows = mixRows()
        assertEquals(13, rows.size)
        for ((idx, r) in rows.withIndex()) {
            val mode = if (r.mode == "cur") WordCorrector.Mode.CURRENT else WordCorrector.Mode.ALL_ACTIVE
            val c = WordCorrector.correct(r.typed, "de-de", lists, mode)
            if (r.typed == r.expected) {
                assertNull("mix row $idx must stay unchanged", c)
            } else {
                assertNotNull("mix row $idx not fixed", c)
                assertEquals("mix row $idx", r.expected, c!!.text)
            }
        }
    }

    @Test fun noAnglicisingInAllActive() {
        val c = WordCorrector.correct("Projetk", "de-de", lists, WordCorrector.Mode.ALL_ACTIVE)
        assertNotNull(c)
        assertEquals("Projekt", c!!.text) // not "project"
    }

    @Test fun frequentRunnerUpBrakeBlocksMial() {
        assertNull(WordCorrector.correct("Mial", "de-de", lists, WordCorrector.Mode.ALL_ACTIVE))
    }

    // ----------------------------------------------------------------- umlaut

    @Test fun germanUmlautRulesApplyOnlyToDeCandidates() {
        assertEquals("für", WordCorrector.correct("fuer", "de-de", lists, WordCorrector.Mode.CURRENT)?.text)
        assertEquals("wäre", WordCorrector.correct("waere", "de-de", lists, WordCorrector.Mode.CURRENT)?.text)
        // en layout/list: the German umlaut form is never reachable
        val c = WordCorrector.correct("fuer", "en-us", lists, WordCorrector.Mode.CURRENT)
        assertTrue(c == null || c.text != "für")
    }

    // ---------------------------------------------------------- must-not-touch

    @Test fun mustNotTouchShapesReturnNull() {
        for (w in listOf("b2b", "iPhone", "ABC", "https://beispiel.de", "max@web.de", "ab", "3d")) {
            assertNull(w, WordCorrector.correct(w, "de-de", lists, WordCorrector.Mode.ALL_ACTIVE))
        }
    }

    // ---------------------------------------------------------------- latency

    @Test fun twentyWordRegionLatencyP95Below10Ms() {
        val region = listOf(
            "heute", "ist", "das", "Wetter", "schön",
            "wir", "treffen", "uns", "am", "Bahnhof",
            "morgem", "Tosch", "Bud", "kalr", "nihct",
            "die", "Katze", "schläft", "auf", "dem",
        )
        assertEquals(20, region.size)
        repeat(20) { region.forEach { WordCorrector.correct(it, "de-de", lists, WordCorrector.Mode.CURRENT) } }
        val times = LongArray(100) {
            val t0 = System.nanoTime()
            region.forEach { w -> WordCorrector.correct(w, "de-de", lists, WordCorrector.Mode.CURRENT) }
            System.nanoTime() - t0
        }
        java.util.Arrays.sort(times)
        val p95 = times[94]
        assertTrue("p95 ${p95 / 1_000_000.0} ms >= 10 ms", p95 < 10_000_000L)
    }

    // ---------------------------------------------------------------- helpers

    private fun load(lang: String): WordList = WordList.load(resolveAsset("wordlists/$lang.bin").readBytes())

    /** lexicon rows -> (typedWord, expectedWord, layout) for single-diff rows. */
    private fun lexiconPairs(tsv: String, layout: String): List<Triple<String, String, String>> =
        corpusRows(tsv).filter { it.third == "lexicon" }.mapNotNull { (typed, expected, _) ->
            singleDiffWords(typed, expected)?.let { Triple(it.first, it.second, layout) }
        }

    /** original-case word pair of a single-diff row (null if not exactly one diff). */
    private fun singleDiff(row: Triple<String, String, String>): Pair<String, String>? =
        singleDiffWords(row.first, row.second)

    private fun singleDiffWords(typed: String, expected: String): Pair<String, String>? {
        val t = words(typed)
        val e = words(expected)
        if (t.size != e.size) return null
        val diff = t.zip(e).filter { it.first.lowercase(Locale.ROOT) != it.second.lowercase(Locale.ROOT) }
        if (diff.size != 1) return null
        return diff.first()
    }

    private fun words(s: String): List<String> = Regex("[\\p{L}]+").findAll(s).map { it.value }.toList()

    private fun relevantWords(text: String): List<String> {
        val out = mutableListOf<String>()
        for (token in text.split(Regex("\\s+"))) {
            if (token.contains("://") || token.contains('@')) continue
            for (m in Regex("[\\p{L}]+").findAll(token)) {
                val w = m.value
                if (w.length < 3) continue
                if (w.length <= 5 && w.all { it.isUpperCase() }) continue
                if (w.drop(1).any { it.isUpperCase() }) continue
                out.add(w)
            }
        }
        return out
    }

    private fun longestWord(text: String): String =
        Regex("[\\p{L}-]+").findAll(text).maxByOrNull { it.value.length }?.value
            ?: error("no word in row")

    private data class MixRow(val typed: String, val expected: String, val cls: String, val mode: String)

    private fun mixRows(): List<MixRow> =
        resolveCorpus("mix.tsv").readLines()
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .map { line ->
                val p = line.split('\t')
                assertTrue("mix row must have 4 columns", p.size == 4)
                MixRow(p[0], p[1], p[2], p[3])
            }

    private fun corpusRows(name: String): List<Triple<String, String, String>> =
        resolveCorpus(name).readLines()
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .map {
                val p = it.split('\t')
                Triple(p[0], p[1], p[2])
            }

    private fun resolveCorpus(name: String): File = resolve("testdata/typing_goldens", name)

    private fun resolveAsset(rel: String): File = resolve("src/main/assets", rel)

    private fun resolve(relDir: String, name: String): File {
        var base = File(System.getProperty("user.dir") ?: ".")
        repeat(6) {
            val cand = File(base, relDir)
            if (File(cand, name).isFile) return File(cand, name)
            base = base.parentFile ?: return@repeat
        }
        throw IllegalStateException("$relDir/$name not found")
    }

    // ------------------------------------------------ REVIEW_V0147 VK-4 golden

    /**
     * VK-4 (P2): the REGION pipeline (regionCorrection) must fix a typo
     * directly before sentence punctuation — the bare word corrector rejects
     * "kalr." (non-letter), so the production tokenizer strips punctuation
     * islands like the golden harness. Rows of class 'region' in de.tsv are
     * data-driven goldens for THIS pipeline (not the bare corrector).
     */
    @Test fun regionGoldenFixesTypoBeforeSentencePunctuation() {
        val rows = corpusRows("de.tsv").filter { it.third == "region" }
        assertEquals("region golden rows (VK-4)", 1, rows.size)
        for ((typed, expected, _) in rows) {
            val fixed = de.voicekeyboard.ime.regionCorrection(
                typed, "de-de", "de-de", lists, LanguageModePolicy.Mode.CURRENT,
            )
            assertEquals("region golden row: expected != regionCorrection(typed)", expected, fixed)
        }
    }
}
