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
 * NfcLabelTest (LAYOUT_REQUIREMENTS §6.4, Grundregel 7):
 * ALLE `label`, `value` und `alternates` ALLER vorhandenen Layout-Assets müssen
 * NFC-normalisiert sein (java.text.Normalizer.Form.NFC).
 * Ein NFD-Label (z. B. „a“ + U+0308 statt „ä“) unterläuft die Gleichheitstests und
 * rendert auf dem Gerät anders — deshalb Pflicht.
 * Verstoß-Meldung nennt Asset, Taste und die betroffenen Codepoints (U+XXXX).
 *
 * Gegenprobe: präparierter JSON-String mit NFD-Zeichen muss den Test ROT machen.
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
        s.codePoints().mapToObj { "U+%04X".format(it) }.reduce { a, b -> "$a $b" }.orElse("<leer>")

    private fun checkNfc(assetJson: String, assetFile: String) {
        val parsed = LayoutParser.parse(assetJson)
        for ((layerName, layer) in parsed.layers) {
            for (row in layer.rows) for (key in row) {
                for ((field, text) in listOf("label" to key.label, "value" to key.value)) {
                    if (!Normalizer.isNormalized(text, Normalizer.Form.NFC)) {
                        fail("Asset '${parsed.id}' ($assetFile), Ebene '$layerName', Taste " +
                            "'${key.id}': $field '${text}' ist nicht NFC-normalisiert " +
                            "(${codepoints(text)}; Grundregel 7, LAYOUT_REQUIREMENTS §1)")
                    }
                }
                for (alt in key.alternates) {
                    if (!Normalizer.isNormalized(alt, Normalizer.Form.NFC)) {
                        fail("Asset '${parsed.id}' ($assetFile), Ebene '$layerName', Taste " +
                            "'${key.id}': alternate '${alt}' ist nicht NFC-normalisiert " +
                            "(${codepoints(alt)}; Grundregel 7, LAYOUT_REQUIREMENTS §1)")
                    }
                }
            }
        }
    }

    @Test
    fun `alle Assets sind vollstaendig NFC-normalisiert`() {
        val dir = RuntimeEnvironment.getApplication().assets.list("layouts")
            ?: throw AssertionError("layouts-Verzeichnis nicht lesbar")
        val files = dir.filter { it.endsWith(".json") }
        assertTrue("Keine Layout-Assets gefunden", files.isNotEmpty())
        for (file in files) {
            val json = asset(file) ?: throw AssertionError("$file nicht lesbar")
            checkNfc(json, file)
        }
        println("NfcLabelTest: ${files.size} Assets geprüft (labels, values, alternates)")
    }

    // ---------- Gegenprobe (Negativtest mit Inline-Mutation, KEINE Asset-Änderung) ----------

    @Test
    fun `negativ - NFD-Alternate wird erkannt`() {
        val json = asset("de-de.json") ?: throw AssertionError("de-de.json nicht lesbar")
        val root = JSONObject(json)
        // Alternate "a" + U+0301 (NFD-á) auf der 'a'-Tase einschleusen → Test MUSS rot werden.
        val aKey = findKeyById(root, "base", "a") ?: throw AssertionError("Taste 'a' nicht gefunden")
        aKey.put("alternates", JSONArray(listOf("a\u0301")))
        var caught = false
        try {
            checkNfc(root.toString(), "de-de.json")
        } catch (e: AssertionError) {
            caught = true
            assertTrue("Meldung muss U+0301 nennen: ${e.message}", e.message?.contains("U+0301") == true)
            assertTrue("Meldung muss die Taste nennen: ${e.message}", e.message?.contains("'a'") == true)
        }
        assertTrue("NFD-Verstoß wurde NICHT erkannt (Tautologie-Verdacht)", caught)
    }

    // Zweite Gegenprobe: NFD im Label selbst (Regel 7 für label — nicht nur alternates).
    @Test
    fun `negativ - NFD-Label wird erkannt`() {
        val json = asset("de-de.json") ?: throw AssertionError("de-de.json nicht lesbar")
        val root = JSONObject(json)
        val zKey = findKeyById(root, "base", "z") ?: throw AssertionError("Taste 'z' nicht gefunden")
        zKey.put("label", "\u007A\u0301") // z + combining acute
        zKey.put("value", "\u007A\u0301")
        var caught = false
        try {
            checkNfc(root.toString(), "de-de.json")
        } catch (e: AssertionError) {
            caught = true
            assertTrue("Meldung muss U+0301 nennen: ${e.message}", e.message?.contains("U+0301") == true)
        }
        assertTrue("NFD-Label-Verstoß wurde NICHT erkannt", caught)
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
