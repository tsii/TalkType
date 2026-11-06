package de.voicekeyboard.correct

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest
import java.util.Locale

/**
 * T23b (V0.1.47, docs/PLAN_V0147.md §15): word list format + licence gate.
 * Decodes the shipped VKWL1 assets with the production reader
 * (correct/WordList.kt), asserts budgets, header fields, corpus probes,
 * stoplist subtraction, NOTICE (CC-BY-SA) and build determinism
 * (tools/build_wordlist.py). Rule 9: no typed text in assertion messages —
 * only row indices and counters.
 */
class WordListFormatTest {

    private val deBudget = 1_572_864 // §15.2: 1.5 MB
    private val enBudget = 1_048_576 // §15.2: 1.0 MB
    private val deCutoff = 100_000   // SWEEP_de.md decision
    private val enCutoff = 150_000   // SWEEP_en.md decision

    // --------------------------------------------------------- assets + header

    @Test fun germanListHeaderAndBudget() {
        val bytes = asset("de-de.bin").readBytes()
        assertTrue("de asset over budget", bytes.size <= deBudget)
        // plain blob (under budget -> no gzip)
        assertEquals("VKWL1", String(bytes, 0, 5, Charsets.US_ASCII))
        assertEquals(5, bytes[5].toInt())
        assertEquals("de-de", String(bytes, 6, 5, Charsets.US_ASCII))
        val count = (bytes[11].toInt() and 0xff) or ((bytes[12].toInt() and 0xff) shl 8) or
            ((bytes[13].toInt() and 0xff) shl 16) or ((bytes[14].toInt() and 0xff) shl 24)
        assertEquals(deCutoff, count)
        val list = WordList.load(bytes)
        assertEquals("de-de", list.languageId)
        assertEquals(deCutoff, list.size)
        assertEquals("ich", list.wordAt(0))
        assertEquals(0, list.rank("ich"))
    }

    @Test fun englishListIsGzippedAndWithinBudget() {
        val bytes = asset("en-us.bin").readBytes()
        assertTrue("en asset over budget", bytes.size <= enBudget)
        // plain 150k would exceed 1.0 MB -> whole blob gzip-wrapped (SWEEP_en.md)
        assertTrue("en asset not gzipped", bytes[0] == 0x1f.toByte() && bytes[1] == 0x8b.toByte())
        val list = WordList.load(bytes)
        assertEquals("en-us", list.languageId)
        assertEquals(enCutoff, list.size)
        assertEquals("you", list.wordAt(0))
        assertEquals(0, list.rank("you"))
    }

    @Test fun unknownWordHasNoRank() {
        val list = WordList.load(asset("de-de.bin").readBytes())
        assertEquals(-1, list.rank("zzzzqqqxxx", Locale.ROOT))
        assertFalse(list.contains("zzzzqqqxxx", Locale.ROOT))
        // case-insensitive membership (folded)
        assertTrue(list.contains("ARZT", Locale.GERMAN))
    }

    // -------------------------------------------------------- corpus probes (§2)

    /** Clean-corpus words (>= 50 samples de) must be present — FP proxy. */
    @Test fun germanCleanCorpusWordsAreContained() {
        val list = WordList.load(asset("de-de.bin").readBytes())
        val cleanWords = corpusRows("de.tsv").filter { it.third == "clean" }
            .flatMap { relevantWords(it.second) }
            .distinct()
        assertTrue("clean sample too small: ${cleanWords.size}", cleanWords.size >= 50)
        cleanWords.forEachIndexed { i, w ->
            assertTrue("de clean word #$i not in list (FP)", list.contains(w, Locale.GERMAN))
        }
    }

    @Test fun englishCleanCorpusWordsAreContained() {
        val list = WordList.load(asset("en-us.bin").readBytes())
        val cleanWords = corpusRows("en.tsv").filter { it.third == "clean" }
            .flatMap { relevantWords(it.second) }
            .distinct()
        assertTrue("clean sample too small: ${cleanWords.size}", cleanWords.size >= 10)
        cleanWords.forEachIndexed { i, w ->
            assertTrue("en clean word #$i not in list (FP)", list.contains(w, Locale.US))
        }
    }

    /** >= 75 % of the lexicon typo forms must be ABSENT (15/20 analog; 32 rows). */
    @Test fun germanLexiconTypoFormsAreAbsent() {
        val list = WordList.load(asset("de-de.bin").readBytes())
        val typos = lexiconTypoWords("de.tsv")
        assertEquals(32, typos.size)
        val absent = typos.count { !list.contains(it, Locale.GERMAN) }
        assertTrue("only $absent/32 typo forms absent", absent >= 24)
    }

    @Test fun englishLexiconTypoFormsAreAbsent() {
        val list = WordList.load(asset("en-us.bin").readBytes())
        val typos = lexiconTypoWords("en.tsv")
        assertEquals(2, typos.size)
        typos.forEachIndexed { i, w ->
            assertFalse("en typo #$i in list", list.contains(w, Locale.US))
        }
    }

    @Test fun stoplistWordsAreAbsent() {
        val de = WordList.load(asset("de-de.bin").readBytes())
        val en = WordList.load(asset("en-us.bin").readBytes())
        stoplistWords("stop_de.txt").forEachIndexed { i, w ->
            assertFalse("de stoplist #$i in list", de.contains(w, Locale.GERMAN))
        }
        stoplistWords("stop_en.txt").forEachIndexed { i, w ->
            assertFalse("en stoplist #$i in list", en.contains(w, Locale.US))
        }
    }

    // ----------------------------------------------------------- licence NOTICE

    @Test fun noticeDocumentsCcBySaAndSource() {
        val notice = asset("NOTICE.txt").readText()
        assertTrue("CC-BY-SA missing", notice.contains("CC-BY-SA"))
        assertTrue("licence URL missing", notice.contains("https://creativecommons.org/licenses/by-sa/4.0/"))
        assertTrue("source URL missing", notice.contains("https://github.com/hermitdave/FrequencyWords"))
        assertTrue("Bearbeitungsvermerk missing", notice.contains("Bearbeitungsvermerk"))
    }

    // ------------------------------------------------------------- determinism

    /** Script runs 2x -> byte-identical sha, both equal to the shipped asset. */
    @Test fun rebuildIsByteIdentical() {
        val root = repoRoot()
        val out1 = File.createTempFile("vkwl-t23b-1", ".bin").apply { deleteOnExit() }
        val out2 = File.createTempFile("vkwl-t23b-2", ".bin").apply { deleteOnExit() }
        val out3 = File.createTempFile("vkwl-t23b-3", ".bin").apply { deleteOnExit() }
        runBuilder(root, "de-de", "tools/wordlists/de_full.txt", "tools/wordlists/stop_de.txt", deCutoff, out1)
        runBuilder(root, "de-de", "tools/wordlists/de_full.txt", "tools/wordlists/stop_de.txt", deCutoff, out2)
        runBuilder(root, "en-us", "tools/wordlists/en_full.txt", "tools/wordlists/stop_en.txt", enCutoff, out3)
        val sha1 = sha256(out1.readBytes())
        val sha2 = sha256(out2.readBytes())
        assertEquals("de rebuild not deterministic", sha1, sha2)
        assertEquals("de rebuild != shipped asset", sha256(asset("de-de.bin").readBytes()), sha1)
        assertEquals("en rebuild != shipped asset", sha256(asset("en-us.bin").readBytes()), sha256(out3.readBytes()))
    }

    // ------------------------------------------------------------- helpers

    private fun runBuilder(root: File, lang: String, input: String, stop: String, top: Int, out: File) {
        val proc = ProcessBuilder(
            "python3", File(root, "tools/build_wordlist.py").absolutePath,
            "build", lang, File(root, input).absolutePath,
            "--stop", File(root, stop).absolutePath,
            "--top", top.toString(),
            "--out", out.absolutePath,
        ).redirectErrorStream(true).start()
        assertTrue("build script timed out", proc.waitFor(180, java.util.concurrent.TimeUnit.SECONDS))
        assertEquals("build script failed: ${proc.inputStream.bufferedReader().readText().take(200)}", 0, proc.exitValue())
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    /** typed/expected/class rows of a typing_goldens tsv ('#' lines ignored). */
    private fun corpusRows(name: String): List<Triple<String, String, String>> =
        resolve("testdata/typing_goldens", name).readLines()
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .map {
                val p = it.split('\t')
                Triple(p[0], p[1], p[2])
            }

    /**
     * Typo words of the lexicon rows: the single differing word between typed
     * and expected (mirrors the build_wordlist.py sweep probe; folded).
     */
    private fun lexiconTypoWords(name: String): List<String> =
        corpusRows(name).filter { it.third == "lexicon" }.mapNotNull { (typed, expected, _) ->
            val t = relevantWords(typed)
            val e = relevantWords(expected)
            if (t.size != e.size) return@mapNotNull null
            val diff = t.zip(e).filter { it.first != it.second }
            if (diff.size != 1) null else diff.first().first
        }

    /**
     * Corrector-relevant words (mirrors TypingRules §2 must-not-touch): no
     * URL/e-mail tokens, letter-only, length >= 2, no short ALLCAPS, no inner
     * capitals; folded with Locale.ROOT like the builder.
     */
    private fun relevantWords(text: String): List<String> {
        val out = mutableListOf<String>()
        for (token in text.split(Regex("\\s+"))) {
            if (token.contains("://") || token.contains('@')) continue
            for (m in Regex("[\\p{L}]+").findAll(token)) {
                val w = m.value
                if (w.length < 2) continue
                if (w.length <= 5 && w.all { it.isUpperCase() }) continue
                if (w.drop(2).any { it.isUpperCase() }) continue
                if (w[0].isLowerCase() && w.drop(1).any { it.isUpperCase() }) continue
                out.add(w.lowercase(Locale.ROOT))
            }
        }
        return out
    }

    private fun stoplistWords(name: String): List<String> =
        resolve("tools/wordlists", name).readLines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }

    private fun asset(name: String): File = resolve("app/src/main/assets/wordlists", name)

    /** Walk-up resolver (max 6 levels, like KorpusFixture/TypingRulesTest). */
    private fun resolve(relDir: String, name: String): File {
        var base = File(System.getProperty("user.dir") ?: ".")
        repeat(6) {
            val cand = File(base, relDir)
            if (File(cand, name).isFile) return File(cand, name)
            base = base.parentFile ?: return@repeat
        }
        throw IllegalStateException("$relDir/$name not found")
    }

    private fun repoRoot(): File {
        var base = File(System.getProperty("user.dir") ?: ".")
        repeat(6) {
            if (File(base, "tools/build_wordlist.py").isFile) return base
            base = base.parentFile ?: return@repeat
        }
        throw IllegalStateException("repo root not found")
    }
}
