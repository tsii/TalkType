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
 * The language-specific punctuation from §2.2 (column „Satzzeichen (direkt)") must
 * be reachable per asset:
 *
 * - SZ = an own key on `symbols` OR `symbolsShift` OR an alternate (any layer).
 *   (Rev 3: „direkt" = an own key on symbols OR symbolsShift; the stage-3 order
 *   defines SZ as reachable — own key or alternate.)
 * - `{…}` or-variants in §2.2 are encoded as alternative sets: at least ONE
 *   variant must be fully reachable.
 * - the error message names the asset, the character and the required stage („SZ").
 *
 * Negative tests (counter-checks, no asset revert in the files — inline mutation):
 * prepared JSON strings must turn the test RED.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PunctuationCoverageTest {

    /**
     * One SZ requirement: list of variants (sets of characters).
     * Satisfied when at least ONE variant is fully reachable.
     */
    private data class SzRequirement(val variants: List<Set<String>>)

    /** §2.2 column „Satzzeichen (direkt)" — every row with § reference from LAYOUT_REQUIREMENTS.md. */
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
        // §2.2 (Rev 3, Audit-D): « », · (U+00B7, NFC-stable — U+0387 forbidden), ; (U+037E
        // dropped, since canonically identical with ; from group 1), €
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

    /** Reachable = an own text key on symbols/symbolsShift OR an alternate on any layer. */
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
                fail("Asset '$assetId': SZ character requirement ${req.variants} not fulfilled — " +
                    "no variant fully reachable (neither an own key on " +
                    "symbols/symbolsShift nor an alternate; required stage: SZ, " +
                    "LAYOUT_REQUIREMENTS §2.2 punctuation column)")
            }
        }
    }

    @Test
    fun `all existing assets cover the punctuation matrix from section 2 2`() {
        var checked = 0
        for (assetId in szMatrix.keys.sorted()) {
            val json = asset("$assetId.json") ?: continue
            checkSzCoverage(json, assetId)
            checked++
        }
        assertTrue("no asset checked — szMatrix/assets inconsistent", checked > 0)
        println("PunctuationCoverageTest: $checked assets checked")
    }

    // ---------- counter-checks (negative tests with inline mutation, NO asset change) ----------

    @Test
    fun `negative - a missing double quote high in the pl-pl SZ matrix is detected`() {
        val json = asset("pl-pl.json") ?: throw AssertionError("pl-pl.json not readable")
        val root = JSONObject(json)
        // revert ” (the fix from the audit) on the symbolsShift layer → the test MUST turn red.
        findKeyById(root, "symbolsShift", "question")?.apply {
            put("label", "¿"); put("value", "¿")
        } ?: throw AssertionError("key 'question' not found")
        // T4 (v0.1.35, PLAN_LONGPRESS_SYMBOLS §2): ” is now ADDITIONALLY an alternate on
        // the dquote key (symbols) — by definition (header) SZ also applies via
        // alternates; the revert must therefore remove BOTH occurrences, otherwise
        // ” would stay reachable and the counter-check would have become a tautology.
        findKeyById(root, "symbols", "dquote")?.apply {
            put("alternates", JSONArray(listOf("„", "“", "‘", "’", "‚")))
        } ?: throw AssertionError("key 'dquote' not found")
        var caught = false
        try {
            checkSzCoverage(root.toString(), "pl-pl")
        } catch (e: AssertionError) {
            caught = true
            assertTrue("the error message must name the character: ${e.message}",
                e.message?.contains("”") == true)
            assertTrue("the error message must name the asset: ${e.message}",
                e.message?.contains("pl-pl") == true)
        }
        assertTrue("the revert of ” in pl-pl was NOT detected (tautology suspicion)", caught)
    }

    @Test
    fun `negative - a missing en dash in the de-de SZ matrix is detected`() {
        val json = asset("de-de.json") ?: throw AssertionError("de-de.json not readable")
        val root = JSONObject(json)
        findKeyById(root, "symbolsShift", "9")?.apply {
            put("label", "µ"); put("value", "µ") // revert the fix
        } ?: throw AssertionError("key '9' not found")
        // T4 (v0.1.35): – is now ADDITIONALLY an alternate on the hyphen key (symbols)
        // — SZ also applies via alternates, the revert therefore removes BOTH occurrences.
        findKeyById(root, "symbols", "hyphen")?.apply {
            put("alternates", JSONArray(listOf("—")))
        } ?: throw AssertionError("key 'hyphen' not found")
        var caught = false
        try {
            checkSzCoverage(root.toString(), "de-de")
        } catch (e: AssertionError) {
            caught = true
            assertTrue("the error message must name –: ${e.message}", e.message?.contains("–") == true)
        }
        assertTrue("the revert of – in de-de was NOT detected", caught)
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
