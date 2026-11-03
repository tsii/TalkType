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
import java.text.Normalizer

/**
 * NfcLabelTest (LAYOUT_REQUIREMENTS §6.4, basic rule 7):
 * ALL `label`, `value` and `alternates` of ALL existing layout assets must
 * be NFC-normalized (java.text.Normalizer.Form.NFC).
 * An NFD label (e.g. "a" + U+0308 instead of "ä") slips past the equality tests and
 * renders differently on the device — hence mandatory.
 * Violation messages name the asset, key and the affected codepoints (U+XXXX).
 *
 * Counter-check: a prepared JSON string with NFD characters must turn the test RED.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class NfcLabelTest {

    private fun asset(name: String): String? = try {
        RuntimeEnvironment.getApplication()
            .assets.open("layouts/$name").bufferedReader().use { it.readText() }
    } catch (e: Exception) {
        null
    }

    private fun codepoints(s: String): String =
        s.codePoints().mapToObj { "U+%04X".format(it) }.reduce { a, b -> "$a $b" }.orElse("<empty>")

    private fun checkNfc(assetJson: String, assetFile: String) {
        val parsed = LayoutParser.parse(assetJson)
        for ((layerName, layer) in parsed.layers) {
            for (row in layer.rows) for (key in row) {
                for ((field, text) in listOf("label" to key.label, "value" to key.value)) {
                    if (!Normalizer.isNormalized(text, Normalizer.Form.NFC)) {
                        fail("Asset '${parsed.id}' ($assetFile), layer '$layerName', key " +
                            "'${key.id}': $field '${text}' is not NFC-normalized " +
                            "(${codepoints(text)}; basic rule 7, LAYOUT_REQUIREMENTS §1)")
                    }
                }
                for (alt in key.alternates) {
                    if (!Normalizer.isNormalized(alt, Normalizer.Form.NFC)) {
                        fail("Asset '${parsed.id}' ($assetFile), layer '$layerName', key " +
                            "'${key.id}': alternate '${alt}' is not NFC-normalized " +
                            "(${codepoints(alt)}; basic rule 7, LAYOUT_REQUIREMENTS §1)")
                    }
                }
            }
        }
    }

    @Test
    fun `all assets are fully NFC-normalized`() {
        val dir = RuntimeEnvironment.getApplication().assets.list("layouts")
            ?: throw AssertionError("layouts directory not readable")
        val files = dir.filter { it.endsWith(".json") }
        assertTrue("no layout assets found", files.isNotEmpty())
        for (file in files) {
            val json = asset(file) ?: throw AssertionError("$file not readable")
            checkNfc(json, file)
        }
        println("NfcLabelTest: ${files.size} assets checked (labels, values, alternates)")
    }

    // ---------- counter-check (negative test with inline mutation, NO asset change) ----------

    @Test
    fun `negative - an NFD alternate is detected`() {
        val json = asset("de-de.json") ?: throw AssertionError("de-de.json not readable")
        val root = JSONObject(json)
        // smuggle an alternate "a" + U+0301 (NFD-á) onto the 'a' key → the test MUST turn red.
        val aKey = findKeyById(root, "base", "a") ?: throw AssertionError("key 'a' not found")
        aKey.put("alternates", JSONArray(listOf("a\u0301")))
        var caught = false
        try {
            checkNfc(root.toString(), "de-de.json")
        } catch (e: AssertionError) {
            caught = true
            assertTrue("the message must name U+0301: ${e.message}", e.message?.contains("U+0301") == true)
            assertTrue("the message must name the key: ${e.message}", e.message?.contains("'a'") == true)
        }
        assertTrue("NFD violation was NOT detected (tautology suspicion)", caught)
    }

    // second counter-check: NFD in the label itself (rule 7 for label — not only alternates).
    @Test
    fun `negative - an NFD label is detected`() {
        val json = asset("de-de.json") ?: throw AssertionError("de-de.json not readable")
        val root = JSONObject(json)
        val zKey = findKeyById(root, "base", "z") ?: throw AssertionError("key 'z' not found")
        zKey.put("label", "\u007A\u0301") // z + combining acute
        zKey.put("value", "\u007A\u0301")
        var caught = false
        try {
            checkNfc(root.toString(), "de-de.json")
        } catch (e: AssertionError) {
            caught = true
            assertTrue("the message must name U+0301: ${e.message}", e.message?.contains("U+0301") == true)
        }
        assertTrue("NFD label violation was NOT detected", caught)
    }

    private fun findKeyById(root: JSONObject, layer: String, keyId: String): JSONObject? {
        val rows: JSONArray = root.getJSONObject("layers").getJSONObject(layer).getJSONArray("rows")
        for (r in 0 until rows.length()) {
            val row = rows.getJSONArray(r)
            for (k in 0 until row.length()) {
                val key = row.getJSONObject(k)
                if (key.getString("id") == keyId) return key
            }
        }
        return null
    }
}
