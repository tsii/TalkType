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
 * LayoutArtifactTest (LAYOUT_REQUIREMENTS §6.8, Grundregel 5a–5f Rev 3):
 * Prüft JEDE Taste JEDES Assets (roher JSON-Baum, bewusst ohne LayoutParser, damit
 * auch Verstöße erkannt werden, die der Parser durchrüsten würde):
 *
 * - 5a: bei kind=="text" gilt label == value (Codepoint-Gleichheit, nicht gefaltet).
 * - 5b: label UND value bestehen aus genau einem Codepoint (nur kind=="text").
 * - 5c: keine Artefakte "=\" (zwei Codepoints) und "⌇" (U+2307, nicht in Z);
 *       `& \ < >` sind KEINE Artefakte (Rev-3-Korrektur, §2.1.3) und erlaubt.
 * - 5d: kein Steuer-/Kombinations-/Leerraum-Zeichen im Text-Label (Cc, Cf,
 *       U+0300–U+036F, Zs/Zl/Zp).
 * - 5e: keine leeren Labels/Values (alle kinds); alternates nur nicht-leere Einzelzeichen.
 * - 5f: bei kind=="action" muss value in der Action-Whitelist stehen
 *       (Whitelist hier IM TEST geführt, unabhängig vom Parser).
 *
 * Gegenproben: Inline-präparierte Minimal-Layouts müssen den Test ROT machen.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LayoutArtifactTest {

    /** Action-Whitelist — im Test geführt (Auftrag: „Whitelist im Test"). */
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
        val where = "$source, Ebene '$layerName' rows[$r][$k] (id='${key.optString("id")}')"
        val label = key.optString("label", "")
        val value = key.optString("value", "")
        // 5e: keine leeren Labels/Values (alle kinds)
        if (label.isEmpty()) fail("$where: leeres label (Regel 5e)")
        if (value.isEmpty()) fail("$where: leeres value (Regel 5e)")
        // 5c: verbotene Artefakte (auch auf Action-Tasten ist das ein Artefakt)
        for (bad in forbiddenArtifacts) {
            if (label.contains(bad) || value.contains(bad)) {
                fail("$where: Artefakt '${bad}' in label/value (Regel 5c, §2.1.3)")
            }
        }
        val kind = key.optString("kind", "")
        when (kind) {
            "text" -> {
                // 5a: label == value
                if (label != value) {
                    fail("$where: label '$label' != value '$value' (Regel 5a)")
                }
                // 5b: genau ein Codepoint
                if (label.codePointCount(0, label.length) != 1 ||
                    value.codePointCount(0, value.length) != 1
                ) {
                    val cps = label.codePoints().mapToObj { "U+%04X".format(it) }
                        .reduce { a, b -> "$a $b" }.orElse("<leer>")
                    fail("$where: label '$label' hat nicht genau 1 Codepoint ($cps; Regel 5b)")
                }
                // 5d: kein Steuer-/Kombinations-/Leerraum-Zeichen
                label.codePoints().forEach { cp ->
                    val type = Character.getType(cp)
                    val combining = cp in 0x0300..0x036F
                    val whitespace = type == Character.SPACE_SEPARATOR.toInt() ||
                        type == Character.LINE_SEPARATOR.toInt() ||
                        type == Character.PARAGRAPH_SEPARATOR.toInt()
                    if (cp < 0x20 || cp == 0x7F || combining || whitespace ||
                        type == Character.FORMAT.toInt()
                    ) {
                        fail("$where: verbotenes Zeichen U+%04X im label '%s' (Regel 5d)".format(cp, label))
                    }
                }
            }
            "action" -> {
                // 5f: erlaubte Action-Namen (Whitelist im Test)
                if (value !in actionWhitelist) {
                    fail("$where: unbekannte Action '$value' (Whitelist: " +
                        "${actionWhitelist.sorted()}; Regel 5f)")
                }
            }
            "dead" -> { /* Struktur prüft LayoutParserTest; hier nur 5e-Artefakte oben */ }
            else -> fail("$where: unbekanntes kind '$kind'")
        }
        // alternates: nur nicht-leere Einzelzeichen
        if (key.has("alternates")) {
            val arr = key.getJSONArray("alternates")
            for (i in 0 until arr.length()) {
                val alt = arr.getString(i)
                if (alt.isEmpty()) fail("$where: leeres alternates-Element (Regel 5e)")
                if (alt.codePointCount(0, alt.length) != 1) {
                    fail("$where: alternates-Element '$alt' hat nicht genau 1 Codepoint (Regel 5b)")
                }
                for (bad in forbiddenArtifacts) {
                    if (alt.contains(bad)) fail("$where: Artefakt '$bad' in alternates (Regel 5c)")
                }
            }
        }
    }

    @Test
    fun `keine Artefakte in irgendeinem Asset - Regel 5a bis 5f`() {
        val context = RuntimeEnvironment.getApplication()
        val files = context.assets.list("layouts")?.filter { it.endsWith(".json") }
            ?: throw AssertionError("layouts-Verzeichnis nicht lesbar")
        assertTrue("Keine Layout-Assets gefunden", files.isNotEmpty())
        for (file in files) {
            val json = context.assets.open("layouts/$file").bufferedReader().use { it.readText() }
            checkNoArtifacts(json, file)
        }
        println("LayoutArtifactTest: ${files.size} Assets geprüft (Regel 5a–5f)")
    }

    // ---------- Gegenproben (Inline-Minimal-Layouts, KEINE Asset-Änderung) ----------

    /** Minimaler Layout-Rumpf für die Negativtests (nur eine Taste pro Ebene). */
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
            assertTrue("$what — Meldung soll '${expectedInMessage}' enthalten: ${e.message}",
                e.message?.contains(expectedInMessage) == true)
        }
        assertTrue("$what wurde NICHT erkannt (Tautologie-Verdacht)", caught)
    }

    @Test
    fun `negativ - label ungleich value wird erkannt 5a`() {
        val key = """{"id":"k","label":"a","width":1,"kind":"text","value":"b"}"""
        expectCaught(minimalLayout(key), "Regel 5a", "label != value")
    }

    @Test
    fun `negativ - zwei Codepoints im Label werden erkannt 5b`() {
        val key = """{"id":"k","label":"ab","width":1,"kind":"text","value":"ab"}"""
        expectCaught(minimalLayout(key), "Regel 5b", "2 Codepoints")
    }

    @Test
    fun `negativ - Artefakt Backslash-Label wird erkannt 5c`() {
        val key = """{"id":"k","label":"=\\<","width":1,"kind":"text","value":"=\\<"}"""
        expectCaught(minimalLayout(key), "Regel 5c", "Artefakt =\\>")
    }

    @Test
    fun `negativ - Kombinationszeichen im Label wird erkannt 5d`() {
        val combining = "\u0301" // COMBINING ACUTE ACCENT (genau 1 Codepoint, aber U+0300–U+036F)
        val key = """{"id":"k","label":"$combining","width":1,"kind":"text","value":"$combining"}"""
        expectCaught(minimalLayout(key), "Regel 5d", "Kombinationszeichen")
    }

    @Test
    fun `negativ - leeres Label wird erkannt 5e`() {
        val key = """{"id":"k","label":"","width":1,"kind":"action","value":"space"}"""
        expectCaught(minimalLayout(key), "Regel 5e", "leeres Label")
    }

    @Test
    fun `negativ - unbekannte Action wird erkannt 5f`() {
        val key = """{"id":"k","label":"⚠","width":1,"kind":"action","value":"launch-missiles"}"""
        expectCaught(minimalLayout(key), "Regel 5f", "unbekannte Action")
    }
}
