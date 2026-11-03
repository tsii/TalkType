package de.voicekeyboard.layouts

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * LayoutArtifactTest (LAYOUT_REQUIREMENTS §6.8, basic rules 5a–5f Rev 3):
 * Checks EVERY key of EVERY asset (raw JSON tree, deliberately without LayoutParser,
 * so that violations the parser would harden away are detected too):
 *
 * - 5a: for kind=="text" label == value holds (codepoint equality, not folded).
 * - 5b: label AND value consist of exactly one codepoint (only kind=="text").
 * - 5c: no artifacts "=\" (two codepoints) and "⌇" (U+2307, not in Z);
 *       `& \ < >` are NOT artifacts (Rev-3 correction, §2.1.3) and are allowed.
 * - 5d: no control/combining/whitespace character in the text label (Cc, Cf,
 *       U+0300–U+036F, Zs/Zl/Zp).
 * - 5e: no empty labels/values (all kinds); alternates only non-empty single characters.
 * - 5f: for kind=="action" the value must be in the action whitelist
 *       (the whitelist is maintained HERE IN THE TEST, independent of the parser).
 *
 * Counter-checks: inline-prepared minimal layouts must turn the test RED.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LayoutArtifactTest {

    /** Action whitelist — maintained in the test (order: "whitelist in the test"). */
    private val actionWhitelist = setOf(
        "shift", "caps", "layer-symbols", "layer-base", "backspace", "space", "enter",
        "language", "emoji", "mic", "cursor-left", "cursor-right",
    )

    private val forbiddenArtifacts = listOf("=\\<", "⌇")

    private fun checkNoArtifacts(assetJson: String, source: String) {
        val root = JSONObject(assetJson)
        val layers = root.getJSONObject("layers")
        for (layerName in layers.keys().asSequence().toList()) {
            val rows: JSONArray = layers.getJSONObject(layerName).getJSONArray("rows")
            for (r in 0 until rows.length()) {
                val row = rows.getJSONArray(r)
                for (k in 0 until row.length()) {
                    checkKey(row.getJSONObject(k), layerName, r, k, source)
                }
            }
        }
    }

    private fun checkKey(key: JSONObject, layerName: String, r: Int, k: Int, source: String) {
        val where = "$source, layer '$layerName' rows[$r][$k] (id='${key.optString("id")}')"
        val kind = key.optString("kind", "")
        // F27a1 MARGIN: margin surface — only width, no label/glyph/touch. Empty
        // label/value are CORRECT here; 5a–5f do not apply (the parser checks
        // separately that MARGIN carries no value/alternates).
        if (kind == "margin") return
        val label = key.optString("label", "")
        val value = key.optString("value", "")
        // 5e: no empty labels/values (all kinds)
        if (label.isEmpty()) fail("$where: empty label (rule 5e)")
        if (value.isEmpty()) fail("$where: empty value (rule 5e)")
        // 5c: forbidden artifacts (on action keys too this is an artifact)
        for (bad in forbiddenArtifacts) {
            if (label.contains(bad) || value.contains(bad)) {
                fail("$where: artifact '${bad}' in label/value (rule 5c, §2.1.3)")
            }
        }
        when (kind) {
            "text" -> {
                // 5a: label == value
                if (label != value) {
                    fail("$where: label '$label' != value '$value' (rule 5a)")
                }
                // 5b: exactly one codepoint
                if (label.codePointCount(0, label.length) != 1 ||
                    value.codePointCount(0, value.length) != 1
                ) {
                    val cps = label.codePoints().mapToObj { "U+%04X".format(it) }
                        .reduce { a, b -> "$a $b" }.orElse("<empty>")
                    fail("$where: label '$label' does not have exactly 1 codepoint ($cps; rule 5b)")
                }
                // 5d: no control/combining/whitespace character
                label.codePoints().forEach { cp ->
                    val type = Character.getType(cp)
                    val combining = cp in 0x0300..0x036F
                    val whitespace = type == Character.SPACE_SEPARATOR.toInt() ||
                        type == Character.LINE_SEPARATOR.toInt() ||
                        type == Character.PARAGRAPH_SEPARATOR.toInt()
                    if (cp < 0x20 || cp == 0x7F || combining || whitespace ||
                        type == Character.FORMAT.toInt()
                    ) {
                        fail("$where: forbidden character U+%04X in label '%s' (rule 5d)".format(cp, label))
                    }
                }
            }
            "action" -> {
                // 5f: allowed action names (whitelist in the test)
                if (value !in actionWhitelist) {
                    fail("$where: unknown action '$value' (whitelist: " +
                        "${actionWhitelist.sorted()}; rule 5f)")
                }
            }
            "dead" -> { /* structure is checked by LayoutParserTest; here only the 5e artifacts above */ }
            else -> fail("$where: unknown kind '$kind'")
        }
        // alternates: only non-empty single characters
        if (key.has("alternates")) {
            val arr = key.getJSONArray("alternates")
            for (i in 0 until arr.length()) {
                val alt = arr.getString(i)
                if (alt.isEmpty()) fail("$where: empty alternates element (rule 5e)")
                if (alt.codePointCount(0, alt.length) != 1) {
                    fail("$where: alternates element '$alt' does not have exactly 1 codepoint (rule 5b)")
                }
                for (bad in forbiddenArtifacts) {
                    if (alt.contains(bad)) fail("$where: artifact '$bad' in alternates (rule 5c)")
                }
            }
        }
    }

    @Test
    fun `no artifacts in any asset - rule 5a to 5f`() {
        val context = RuntimeEnvironment.getApplication()
        val files = context.assets.list("layouts")?.filter { it.endsWith(".json") }
            ?: throw AssertionError("layouts directory not readable")
        assertTrue("no layout assets found", files.isNotEmpty())
        for (file in files) {
            val json = context.assets.open("layouts/$file").bufferedReader().use { it.readText() }
            checkNoArtifacts(json, file)
        }
        println("LayoutArtifactTest: ${files.size} assets checked (rules 5a–5f)")
    }

    // ---------- counter-checks (inline minimal layouts, NO asset change) ----------

    /** Minimal layout skeleton for the negative tests (only one key per layer). */
    private fun minimalLayout(keyJson: String): String = """
        {
          "schemaVersion": 1,
          "id": "xx-fake",
          "language": {"tag": "xx-XX", "label": "Fake", "caseLocale": "xx-XX", "direction": "ltr"},
          "deadKeys": {},
          "layers": {
            "base":         {"rows": [[ $keyJson ]]},
            "shift":        {"rows": [[ $keyJson ]]},
            "symbols":      {"rows": [[ $keyJson ]]},
            "symbolsShift": {"rows": [[ $keyJson ]]}
          }
        }
    """.trimIndent()

    private fun expectCaught(json: String, expectedInMessage: String, what: String) {
        var caught = false
        try {
            checkNoArtifacts(json, "inline")
        } catch (e: AssertionError) {
            caught = true
            assertTrue("$what — message should contain '${expectedInMessage}': ${e.message}",
                e.message?.contains(expectedInMessage) == true)
        }
        assertTrue("$what was NOT detected (tautology suspicion)", caught)
    }

    @Test
    fun `negative - label not equal to value is detected 5a`() {
        val key = """{"id":"k","label":"a","width":1,"kind":"text","value":"b"}"""
        expectCaught(minimalLayout(key), "rule 5a", "label != value")
    }

    @Test
    fun `negative - two codepoints in the label are detected 5b`() {
        val key = """{"id":"k","label":"ab","width":1,"kind":"text","value":"ab"}"""
        expectCaught(minimalLayout(key), "rule 5b", "2 codepoints")
    }

    @Test
    fun `negative - backslash-label artifact is detected 5c`() {
        val key = """{"id":"k","label":"=\\<","width":1,"kind":"text","value":"=\\<"}"""
        expectCaught(minimalLayout(key), "rule 5c", "artifact =\\>")
    }

    @Test
    fun `negative - combining character in the label is detected 5d`() {
        val combining = "\u0301" // COMBINING ACUTE ACCENT (exactly 1 codepoint, but U+0300–U+036F)
        val key = """{"id":"k","label":"$combining","width":1,"kind":"text","value":"$combining"}"""
        expectCaught(minimalLayout(key), "rule 5d", "combining character")
    }

    @Test
    fun `negative - empty label is detected 5e`() {
        val key = """{"id":"k","label":"","width":1,"kind":"action","value":"space"}"""
        expectCaught(minimalLayout(key), "rule 5e", "empty label")
    }

    @Test
    fun `negative - unknown action is detected 5f`() {
        val key = """{"id":"k","label":"⚠","width":1,"kind":"action","value":"launch-missiles"}"""
        expectCaught(minimalLayout(key), "rule 5f", "unknown action")
    }
}
