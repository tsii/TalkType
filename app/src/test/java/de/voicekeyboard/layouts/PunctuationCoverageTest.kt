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
 * PunctuationCoverageTest (LAYOUT_REQUIREMENTS §6.3, Rev 3):
 * Die sprachspezifischen Satzzeichen aus §2.2 (Spalte „Satzzeichen (direkt)") müssen
 * je Asset erreichbar sein:
 *
 * - SZ = eigene Taste auf `symbols` ODER `symbolsShift` ODER Alternate (beliebige Ebene).
 *   (Rev 3: „direkt" = eigene Taste auf symbols ODER symbolsShift; der Auftrag von
 *   Stufe 3 definiert SZ als erreichbar — eigene Taste oder Alternate.)
 * - `{…}`-Oder-Varianten in §2.2 sind als Alternativmengen kodiert: mindestens EINE
 *   Variante muss vollständig erreichbar sein.
 * - Fehlermeldung nennt Asset, Zeichen und geforderte Stufe („SZ").
 *
 * Negative Tests (Gegenprobe, kein Asset-Rückbau in Dateien — Inline-Mutation):
 * präparierte JSON-Strings müssen den Test ROT machen.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PunctuationCoverageTest {

    /**
     * Eine SZ-Anforderung: Liste von Varianten (Mengen von Zeichen).
     * Erfüllt, wenn mindestens EINE Variante vollständig erreichbar ist.
     */
    private data class SzRequirement(val variants: List<Set<String>>)

    /** §2.2 Spalte „Satzzeichen (direkt)" — jede Zeile mit §-Verweis aus LAYOUT_REQUIREMENTS.md. */
    private val szMatrix: Map<String, List<SzRequirement>> = mapOf(
        // §2.2: „ “ {oder » «}, –, €, §, °
        "de-de" to listOf(
            SzRequirement(listOf(setOf("„", "“"), setOf("»", "«"))),
            SzRequirement(listOf(setOf("–"))),
            SzRequirement(listOf(setOf("€"))),
            SzRequirement(listOf(setOf("§"))),
            SzRequirement(listOf(setOf("°"))),
        ),
        // §2.2: ' – — … £ $ °
        "en-us" to listOf(
            SzRequirement(listOf(setOf("'"))),
            SzRequirement(listOf(setOf("–"))),
            SzRequirement(listOf(setOf("—"))),
            SzRequirement(listOf(setOf("…"))),
            SzRequirement(listOf(setOf("£"))),
            SzRequirement(listOf(setOf("$"))),
            SzRequirement(listOf(setOf("°"))),
        ),
        // §2.2: « », ’, €, °
        "fr-fr" to listOf(
            SzRequirement(listOf(setOf("«"))),
            SzRequirement(listOf(setOf("»"))),
            SzRequirement(listOf(setOf("’"))),
            SzRequirement(listOf(setOf("€"))),
            SzRequirement(listOf(setOf("°"))),
        ),
        // §2.2: ¿ ¡, « », º ª, €
        "es-es" to listOf(
            SzRequirement(listOf(setOf("¿"))),
            SzRequirement(listOf(setOf("¡"))),
            SzRequirement(listOf(setOf("«"))),
            SzRequirement(listOf(setOf("»"))),
            SzRequirement(listOf(setOf("º"))),
            SzRequirement(listOf(setOf("ª"))),
            SzRequirement(listOf(setOf("€"))),
        ),
        // §2.2: « » {oder “ ”}, ’, €
        "it-it" to listOf(
            SzRequirement(listOf(setOf("«", "»"), setOf("“", "”"))),
            SzRequirement(listOf(setOf("’"))),
            SzRequirement(listOf(setOf("€"))),
        ),
        // §2.2: « », º ª, €, §
        "pt-pt" to listOf(
            SzRequirement(listOf(setOf("«"))),
            SzRequirement(listOf(setOf("»"))),
            SzRequirement(listOf(setOf("º"))),
            SzRequirement(listOf(setOf("ª"))),
            SzRequirement(listOf(setOf("€"))),
            SzRequirement(listOf(setOf("§"))),
        ),
        // §2.2: ‘ ’ „ ” {oder “ ”}, €
        "nl-nl" to listOf(
            SzRequirement(listOf(setOf("‘", "’"), setOf("„", "”"), setOf("“", "”"))),
            SzRequirement(listOf(setOf("€"))),
        ),
        // §2.2: „ ”, –, €
        "pl-pl" to listOf(
            SzRequirement(listOf(setOf("„"))),
            SzRequirement(listOf(setOf("”"))),
            SzRequirement(listOf(setOf("–"))),
            SzRequirement(listOf(setOf("€"))),
        ),
        // §2.2: ‘ ’, €
        "tr-tr" to listOf(
            SzRequirement(listOf(setOf("‘"))),
            SzRequirement(listOf(setOf("’"))),
            SzRequirement(listOf(setOf("€"))),
        ),
        // §2.2: « », –, №, €
        "ru-ru" to listOf(
            SzRequirement(listOf(setOf("«"))),
            SzRequirement(listOf(setOf("»"))),
            SzRequirement(listOf(setOf("–"))),
            SzRequirement(listOf(setOf("№"))),
            SzRequirement(listOf(setOf("€"))),
        ),
        // §2.2: „ “, –, €, °
        "cs-cz" to listOf(
            SzRequirement(listOf(setOf("„"))),
            SzRequirement(listOf(setOf("“"))),
            SzRequirement(listOf(setOf("–"))),
            SzRequirement(listOf(setOf("€"))),
            SzRequirement(listOf(setOf("°"))),
        ),
        // §2.2: ” ” {oder » «}, –, §, €
        "sv-se" to listOf(
            SzRequirement(listOf(setOf("”"), setOf("»", "«"))),
            SzRequirement(listOf(setOf("–"))),
            SzRequirement(listOf(setOf("§"))),
            SzRequirement(listOf(setOf("€"))),
        ),
        // §2.2: » « {oder ” ”}, –, §, €
        "da-dk" to listOf(
            SzRequirement(listOf(setOf("»", "«"), setOf("”"))),
            SzRequirement(listOf(setOf("–"))),
            SzRequirement(listOf(setOf("§"))),
            SzRequirement(listOf(setOf("€"))),
        ),
        // §2.2: ” ” {oder » «}, –, §, €
        "fi-fi" to listOf(
            SzRequirement(listOf(setOf("”"), setOf("»", "«"))),
            SzRequirement(listOf(setOf("–"))),
            SzRequirement(listOf(setOf("§"))),
            SzRequirement(listOf(setOf("€"))),
        ),
        // §2.2 (Rev 3, Audit-D): « », · (U+00B7, NFC-stabil — U+0387 verboten), ; (U+037E
        // entfällt, da kanonisch identisch mit ; aus Gruppe 1), €
        "el-gr" to listOf(
            SzRequirement(listOf(setOf("«"))),
            SzRequirement(listOf(setOf("»"))),
            SzRequirement(listOf(setOf("·"))),
            SzRequirement(listOf(setOf("€"))),
        ),
    )

    private fun asset(name: String): String? = try {
        val context = org.robolectric.RuntimeEnvironment.getApplication()
        context.assets.open("layouts/$name").bufferedReader().use { it.readText() }
    } catch (e: Exception) {
        null
    }

    /** Erfassbar = eigene Text-Taste auf symbols/symbolsShift ODER Alternate auf einer beliebigen Ebene. */
    private fun checkSzCoverage(assetJson: String, assetId: String) {
        val parsed = LayoutParser.parse(assetJson)
        val ownOnSymbolLayers = parsed.layers
            .filterKeys { it == "symbols" || it == "symbolsShift" }
            .values.flatMap { it.rows.flatten() }
            .filter { it.kind == KeyKind.TEXT }
            .map { it.value }.toSet()
        val alternates = parsed.layers.values
            .flatMap { it.rows.flatten() }
            .flatMap { it.alternates }.toSet()
        val reachable = ownOnSymbolLayers + alternates
        for (req in szMatrix.getValue(assetId)) {
            val satisfied = req.variants.any { variant -> variant.all { it in reachable } }
            if (!satisfied) {
                fail("Asset '$assetId': SZ-Zeichen-Anforderung ${req.variants} nicht erfüllt — " +
                    "keine Variante vollständig erreichbar (weder eigene Taste auf " +
                    "symbols/symbolsShift noch alternate; geforderte Stufe: SZ, " +
                    "LAYOUT_REQUIREMENTS §2.2 Satzzeichen-Spalte)")
            }
        }
    }

    @Test
    fun `alle vorhandenen Assets decken die Satzzeichen-Matrix aus §2 2 ab`() {
        var checked = 0
        for (assetId in szMatrix.keys.sorted()) {
            val json = asset("$assetId.json") ?: continue
            checkSzCoverage(json, assetId)
            checked++
        }
        assertTrue("Kein Asset geprüft — szMatrix/assets inkonsistent", checked > 0)
        println("PunctuationCoverageTest: $checked Assets geprüft")
    }

    // ---------- Gegenproben (Negativtests mit Inline-Mutation, KEINE Asset-Änderung) ----------

    @Test
    fun `negativ - fehlendes ” in pl-pl SZ-Matrix wird erkannt`() {
        val json = asset("pl-pl.json") ?: throw AssertionError("pl-pl.json nicht lesbar")
        val root = JSONObject(json)
        // ” (Fix aus dem Audit) auf der symbolsShift-Ebene wieder rückbauen → Test MUSS rot werden.
        findKeyById(root, "symbolsShift", "question")?.apply {
            put("label", "¿"); put("value", "¿")
        } ?: throw AssertionError("Taste 'question' nicht gefunden")
        var caught = false
        try {
            checkSzCoverage(root.toString(), "pl-pl")
        } catch (e: AssertionError) {
            caught = true
            assertTrue("Fehlermeldung muss das Zeichen nennen: ${e.message}",
                e.message?.contains("”") == true)
            assertTrue("Fehlermeldung muss das Asset nennen: ${e.message}",
                e.message?.contains("pl-pl") == true)
        }
        assertTrue("Rückbau von ” in pl-pl wurde NICHT erkannt (Tautologie-Verdacht)", caught)
    }

    @Test
    fun `negativ - fehlendes – in de-de SZ-Matrix wird erkannt`() {
        val json = asset("de-de.json") ?: throw AssertionError("de-de.json nicht lesbar")
        val root = JSONObject(json)
        findKeyById(root, "symbolsShift", "9")?.apply {
            put("label", "µ"); put("value", "µ") // Fix rückbauen
        } ?: throw AssertionError("Taste '9' nicht gefunden")
        var caught = false
        try {
            checkSzCoverage(root.toString(), "de-de")
        } catch (e: AssertionError) {
            caught = true
            assertTrue("Fehlermeldung muss – nennen: ${e.message}", e.message?.contains("–") == true)
        }
        assertTrue("Rückbau von – in de-de wurde NICHT erkannt", caught)
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
