package de.voicekeyboard.asr.qwen

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * T22e (PLAN_V0146 §4/§6): DETOKENIZE goldens for the Qwen3-ASR model set.
 *
 * The fixture `qwen/tokenizer_goldens.json` is generated HOST-side by
 * tools/qwen_ref/gen_tokenizer_goldens.py (.venv-asr, HF 'tokenizers' 0.23.3,
 * decode reference = `decode(ids, skip_special_tokens=True)`) against the
 * SAME tokenizer.json that ships in the model package later (T22b') —
 * `qwen/tokenizer.json` in the test resources is its verbatim copy.
 *
 * [QwenTokenizer] is DECODE-ONLY (no BPE encoding on the device):
 *  - every golden pair must decode EXACTLY (byte-level 'Ġ'/'Ċ', UTF-8
 *    re-assembly of Umlaute/ß/€/emoji),
 *  - special/chat tokens (added_tokens, e.g. <|im_start|>/<|im_end|>) are
 *    FILTERED out of the transcription text,
 *  - empty → empty, unknown ids are skipped.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35]) // project pattern: SDK 36 needs Java 21, the test JVM is 17
class QwenTokenizerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var tokenizer: QwenTokenizer
    private lateinit var pairs: List<Golden>

    private data class Golden(val label: String, val ids: List<Long>, val text: String)

    @Before
    fun setUp() {
        // model dir like the later app package: <dir>/tokenizer.json
        val dir = tmp.newFolder()
        javaClass.getResourceAsStream("/qwen/tokenizer.json")!!.use { input ->
            File(dir, "tokenizer.json").outputStream().use { input.copyTo(it) }
        }
        tokenizer = QwenTokenizer.load(dir)

        val goldens = JSONObject(
            javaClass.getResourceAsStream("/qwen/tokenizer_goldens.json")!!
                .readBytes().toString(Charsets.UTF_8),
        )
        val arr: JSONArray = goldens.getJSONArray("pairs")
        pairs = (0 until arr.length()).map { i ->
            val p = arr.getJSONObject(i)
            Golden(
                label = p.getString("label"),
                ids = p.getJSONArray("ids").let { ids -> (0 until ids.length()).map { ids.getLong(it) } },
                text = p.getString("text"),
            )
        }
    }

    private fun golden(label: String): Golden =
        pairs.firstOrNull { it.label == label }
            ?: error("golden '$label' missing from tokenizer_goldens.json")

    // ---- the core contract: EXACT decode against the Python reference ----

    @Test
    fun `every golden pair decodes exactly - at least 16 pairs`() {
        assertTrue("fixture too small: ${pairs.size}", pairs.size >= 16)
        for (p in pairs) {
            assertEquals("golden '${p.label}'", p.text, tokenizer.detokenize(p.ids))
        }
    }

    @Test
    fun `byte level reassembly - umlauts eszett euro and emoji are exact`() {
        // UTF-8 re-assembly over multi-byte pieces (no 'Ġ'/'Ċ' string hacks):
        assertEquals(golden("umlauts").text, tokenizer.detokenize(golden("umlauts").ids))
        assertEquals(golden("german_sentence").text, tokenizer.detokenize(golden("german_sentence").ids))
        assertEquals(golden("euro").text, tokenizer.detokenize(golden("euro").ids))
        assertEquals(golden("emoji").text, tokenizer.detokenize(golden("emoji").ids))
        // spot-check the visible characters really survived:
        assertTrue(golden("umlauts").text.contains("ß"))
        assertTrue(golden("euro").text.contains("€"))
        assertTrue(golden("emoji").text.contains("🎉"))
    }

    @Test
    fun `byte level whitespace - leading multiple and newlines are exact`() {
        for (label in listOf("leading_spaces", "multiple_spaces", "trailing_spaces", "newline", "paragraphs", "tab")) {
            val p = golden(label)
            assertEquals("golden '$label'", p.text, tokenizer.detokenize(p.ids))
        }
        assertTrue(golden("leading_spaces").text.startsWith("   "))
        assertTrue(golden("newline").text.contains("\n"))
    }

    @Test
    fun `special tokens are filtered out of the text`() {
        for (label in listOf("special_mix", "special_only")) {
            val p = golden(label)
            // the ids REALLY contain added/special tokens (Qwen: >= 151643):
            assertTrue("$label must contain special ids", p.ids.any { it >= 151_643L })
            val decoded = tokenizer.detokenize(p.ids)
            assertEquals("golden '$label'", p.text, decoded)
            assertFalse("no special marker may leak", decoded.contains("<|"))
        }
        // special ids dropped entirely:
        assertEquals("system", tokenizer.detokenize(golden("special_only").ids))
    }

    @Test
    fun `long sequence of at least 48 tokens decodes exactly`() {
        val p = golden("long_sequence")
        assertTrue("fixture must pin >= 48 tokens (is ${p.ids.size})", p.ids.size >= 48)
        assertEquals(p.text, tokenizer.detokenize(p.ids))
    }

    // ---- edge cases ----

    @Test
    fun `empty ids decode to empty text`() {
        assertEquals("", tokenizer.detokenize(emptyList()))
        val empty = golden("empty")
        assertEquals(empty.text, tokenizer.detokenize(empty.ids))
    }

    @Test
    fun `unknown ids are skipped without breaking the text`() {
        val p = golden("ascii_sentence")
        // id outside vocab AND added_tokens: skipped (engine contract, defensive)
        val withUnknown = listOf(999_999_999L) + p.ids + listOf(-1L, 151_643L + 100_000L)
        assertEquals(p.text, tokenizer.detokenize(withUnknown))
    }
}
