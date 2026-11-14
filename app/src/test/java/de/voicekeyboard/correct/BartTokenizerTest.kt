package de.voicekeyboard.correct

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * T24b/B3b (Gleis 3): the REAL BartTokenizer over tokenizer.json —
 * ByteLevel-BPE (en, roberta) + SentencePiece-Unigram (de, T5) with the
 * HF encode semantics of the GATE runner (specials INCLUDED:
 * en [<s> … </s>] via RobertaProcessing, de [… </s>] T5-style).
 *
 * Parity evidence: per-sentence n_input_tokens from reports/bart_u2_*.json
 * (the T24a GATE run) + the totals of BART_CORRECT_CONTRACT §5
 * (en 165 -> 147, de 226 -> 161). Round-trip decode(encode(x)) == x on the
 * golden inputs. The tokenizer.json files live on the HOST under
 * /home/dev/models/bart-export/ (export artifacts, NOT in the repo) — this
 * suite fails fast when they are absent (pinned GATE environment).
 */
class BartTokenizerTest {

    private val exportRoot = File("/home/dev/models/bart-export")

    private fun hostAssets(): File? {
        val f = File(exportRoot, "en-merged/tokenizer/tokenizer.json")
        return if (f.isFile) exportRoot else null
    }

    private fun tokenizer(lang: String): BartTokenizer {
        val dir = File(exportRoot, "$lang-merged/tokenizer")
        val json = File(dir, "tokenizer.json").readText()
        val meta = BartCorrectEngine.VocabMeta.load(File(dir, "vocab_meta.json"))
        return BartTokenizers.load(json, meta)
    }

    private fun report(lang: String): Map<String, Any?> =
        MiniJson.parseObject(resolve("reports/bart_u2_$lang.json").readText())

    @Suppress("UNCHECKED_CAST")
    private fun sentences(lang: String): List<Map<String, Any?>> =
        (report(lang)["sentences"] as List<Any?>).map { it as Map<String, Any?> }

    private fun goldenInputs(lang: String): List<String> =
        resolve("testdata/bart_goldens/$lang.tsv").readLines()
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .map { it.split('\t')[0] }

    /** Walk-up resolver (project pattern — the unit-test CWD is the app module). */
    private fun resolve(rel: String): File {
        var base = File(System.getProperty("user.dir") ?: ".")
        repeat(6) {
            val f = File(base, rel)
            if (f.isFile) return f
            base = base.parentFile ?: return@repeat
        }
        throw IllegalStateException("not found: $rel")
    }

    // -------------------------------------------------------------- parity

    @Test fun `EN per-sentence input token counts match the GATE report`() {
        assumeTrue("host export assets present", hostAssets() != null)
        val tk = tokenizer("en")
        val rows = sentences("en")
        assertEquals("20 goldens", 20, rows.size)
        var totalIn = 0L
        for ((i, row) in rows.withIndex()) {
            val input = row["input"] as String
            val expected = (row["n_input_tokens"] as Long).toInt()
            val ids = tk.encode(input)
            assertEquals("en sentence $i token count", expected, ids.size)
            assertTrue("en sentence $i starts with <s>", ids[0] == 0L)
            assertTrue("en sentence $i ends with </s>", ids[ids.size - 1] == 2L)
            totalIn += ids.size
        }
        assertEquals("contract §5: 165 input tokens over 20 en goldens", 165L, totalIn)
    }

    @Test fun `DE per-sentence input token counts match the GATE report - prefix included`() {
        assumeTrue("host export assets present", hostAssets() != null)
        val tk = tokenizer("de")
        val rows = sentences("de")
        assertEquals("20 goldens", 20, rows.size)
        var totalIn = 0L
        for ((i, row) in rows.withIndex()) {
            val input = row["input"] as String
            val expected = (row["n_input_tokens"] as Long).toInt()
            // the runner encodes prefix + input (report §5: +2 prefix tokens/sentence)
            val ids = tk.encode("correct: " + input)
            assertEquals("de sentence $i token count (prefix + </s>)", expected, ids.size)
            assertTrue("de sentence $i ends with </s>", ids[ids.size - 1] == 1L)
            totalIn += ids.size
        }
        assertEquals("contract §5: 226 input tokens over 20 de goldens", 226L, totalIn)
    }

    // ----------------------------------------------------------- round trip

    @Test fun `round trip decode(encode(x)) == x on the golden inputs`() {
        assumeTrue("host export assets present", hostAssets() != null)
        for (lang in listOf("en", "de")) {
            val tk = tokenizer(lang)
            for (input in goldenInputs(lang)) {
                val withPrefix = if (lang == "de") "correct: $input" else input
                assertEquals("round trip $lang", withPrefix, tk.decode(tk.encode(withPrefix)))
            }
        }
    }

    // ------------------------------------------------------------ structural

    @Test fun `special tokens are skipped in decode but produced in encode`() {
        assumeTrue("host export assets present", hostAssets() != null)
        val en = tokenizer("en")
        val ids = en.encode("hello world")
        assertEquals("<s> … </s> wrapper", 0L, ids[0])
        assertEquals(2L, ids[ids.size - 1])
        // decode skips the specials: round-trip text has no marker characters
        val text = en.decode(ids)
        assertEquals("hello world", text)

        val de = tokenizer("de")
        val deIds = de.encode("correct: hallo Welt")
        assertEquals("T5: </s> appended", 1L, deIds[deIds.size - 1])
        assertEquals("correct: hallo Welt", de.decode(deIds))
    }

    @Test fun `model dispatch - BPE vs Unigram classes`() {
        assumeTrue("host export assets present", hostAssets() != null)
        assertTrue(tokenizer("en") is BartTokenizers.ByteLevelBpe)
        assertTrue(tokenizer("de") is BartTokenizers.SentencePieceUnigram)
    }
}
