package de.voicekeyboard.layouts

import android.app.Application
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * LayoutCompletenessTest (LAYOUT_REQUIREMENTS acceptance criteria 1+2):
 * Checks EVERY existing layout asset against the language matrix and the basic rules.
 *
 * - mandatory characters of the matrix row as an OWN key in `base` (alternates do NOT count).
 * - shift is the complete uppercase form: every base text key has a shift key with
 *   the language-correct uppercase form (CaseMapper), including the special case ß→ẞ for German.
 * - basic rule 5 (Rev 3, WHITELIST): label == value, exactly one codepoint, no
 *   artifacts (`=\\<`, `⌇`). `& \\ < >` are NOT artifacts but §2.1 mandatory characters.
 * - §2.1 mandatory symbols: group 1 (20) + digits once each on `symbols`,
 *   group 2 (13) on `symbols` OR `symbolsShift` — the error message names
 *   the asset and the missing character.
 * - basic rule 6: alternates only characters (no action names), max 8 per key.
 * - basic rule 4: exactly one space, backspace, enter, emoji and mic key per layer
 *   as well as shift/layer keys.
 *
 * The matrix below lists all 15 languages. Assets that do not exist (yet) are
 * proactively skipped — the test thereby automatically covers every new language
 * as soon as its asset is added (stage 3).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LayoutCompletenessTest {

    /** One row of the language matrix from LAYOUT_REQUIREMENTS.md. */
    private data class MatrixRow(
        val assetId: String,
        val requiredOwnBaseKeys: List<String>,
        /** Characters that must be reachable: own key OR alternates (base or shift). */
        val requiredReachable: List<String> = emptyList(),
        /** lowercase → uppercase pairs that must exist in shift as an own key/value. */
        val shiftPairs: List<Pair<String, String>> = emptyList(),
    )

    // Language matrix per LAYOUT_REQUIREMENTS.md Rev 3, §2.2 (columns A/B).
    // Every row with a § reference; A = own base key, B = reachable (own OR alternate).
    private val matrix: List<MatrixRow> = listOf(
        // F23b-T (user decision L1 package): de-de has NO own ä/ö/ü/ß keys
        // anymore — stock model: rowkeys_qwertz1.xml keySpec q..p (NO ü, plus
        // keyHintLabel 1..0) and rowkeys_qwertz3.xml keySpec y x c v b n m (NO ß).
        // The umlauts are reachable as the FIRST alternate (own test below);
        // shiftPairs checks ẞ against the shift layer (own value OR alternate,
        // checkShiftPairs) — after F23b via the S alternates, no longer via eszett.
        MatrixRow("de-de", emptyList(), shiftPairs = listOf("ß" to "ẞ")),
        // §2.2: en-us — no A/B letters (ASCII); punctuation is covered by PunctuationCoverageTest
        MatrixRow("en-us", emptyList(), requiredReachable = listOf("'", "\"", "–", "—", "…")),
        // §2.2 fr-fr + F27c/D26: the accents are NO own keys anymore but
        // alternates of their base keys (stock geometry) — hence only reachable (B).
        MatrixRow("fr-fr", emptyList(), requiredReachable = listOf("é", "è", "à", "ç", "ù", "ê", "ë", "ï", "î", "ô", "û", "ü", "œ", "æ", "ÿ"), shiftPairs = listOf("œ" to "Œ", "æ" to "Æ", "ç" to "Ç")),
        // §2.2 es-es + F27c/D26: ñ is an alternate of n (no own key anymore).
        MatrixRow("es-es", emptyList(), requiredReachable = listOf("ñ", "á", "é", "í", "ó", "ú", "ü", "¿", "¡"), shiftPairs = listOf("ñ" to "Ñ")),
        // §2.2 it-it + F27c/D26: à è é ì ò ù are alternates (Rev 3: ü B-mandatory — audit B1)
        MatrixRow("it-it", emptyList(), requiredReachable = listOf("à", "è", "é", "ì", "ò", "ù", "á", "í", "ó", "ú", "î", "ü")),
        // F24d (R8 decision like de-de): pt-pt has NO own accent keys anymore —
        // the AOSP qwerty row carries a/e/i/o/u/c accents as moreKeys
        // (docs/AOSP_ROWKEYS_BELEGE.md: "none / 12 accent keys + 5th row (ours) -> rebuild").
        // Reachability as the FIRST alternate is checked by the own case below;
        // shiftPairs checks Ç against the shift layer (own value OR alternate).
        MatrixRow("pt-pt", emptyList(), requiredReachable = listOf("ü"), shiftPairs = listOf("ç" to "Ç")),
        // §2.2: nl-nl A = —; B = á à ä ë é è ê ï ö ü (Rev 3: ä B-mandatory — audit B1)
        MatrixRow("nl-nl", emptyList(), requiredReachable = listOf("á", "à", "ä", "ë", "é", "è", "ê", "ï", "ö", "ü")),
        // §2.2 pl-pl + F27c/D26: ą ć ę ł ń ó ś ź ż are alternates (uppercase forms via shiftPairs)
        MatrixRow("pl-pl", emptyList(), requiredReachable = listOf("ą", "ć", "ę", "ł", "ń", "ó", "ś", "ź", "ż"), shiftPairs = listOf("ą" to "Ą", "ć" to "Ć", "ę" to "Ę", "ł" to "Ł", "ń" to "Ń", "ó" to "Ó", "ś" to "Ś", "ź" to "Ź", "ż" to "Ż")),
        // §2.2 tr-tr + F27c/D26: ç ğ ı ö ş ü are alternates (i stays an own key, ı→i);
        // shift i→İ, ı→I (§3)
        MatrixRow("tr-tr", emptyList(), requiredReachable = listOf("ğ", "ü", "ş", "ı", "ö", "ç", "â", "î", "û"), shiftPairs = listOf("ğ" to "Ğ", "ü" to "Ü", "ş" to "Ş", "ı" to "I", "ö" to "Ö", "ç" to "Ç", "i" to "İ")),
        // §2.2 ru-ru + F27c/D27: а–я complete as own keys; ё is an alternate of е
        MatrixRow("ru-ru", listOf("а", "б", "в", "г", "д", "е", "ж", "з", "и", "й", "к", "л", "м", "н", "о", "п", "р", "с", "т", "у", "ф", "х", "ц", "ч", "ш", "щ", "ъ", "ы", "ь", "э", "ю", "я"), requiredReachable = listOf("ё")),
        // §2.2 cs-cz + F27c/D26: all accents are alternates; B = ď ť ň ó (HW not directly assigned)
        MatrixRow("cs-cz", emptyList(), requiredReachable = listOf("ě", "š", "č", "ř", "ž", "ý", "á", "í", "é", "ú", "ů", "ď", "ť", "ň", "ó")),
        // §2.2 sv-se + F27c/D26: å ä ö are alternates; B = á à é è ü
        MatrixRow("sv-se", emptyList(), requiredReachable = listOf("å", "ä", "ö", "á", "à", "é", "è", "ü")),
        // §2.2 da-dk + F27c/D26: æ ø å are alternates; B = ä é ö ü
        MatrixRow("da-dk", emptyList(), requiredReachable = listOf("æ", "ø", "å", "ä", "ö", "é", "ü")),
        // §2.2 fi-fi + F27c/D26: ä ö å are alternates (Rev 1 correction: å mandatory); B = š ž é
        MatrixRow("fi-fi", emptyList(), requiredReachable = listOf("ä", "ö", "å", "š", "ž", "é")),
        // §2.2: el-gr A = α–ω complete + ς (explicit list, U+03C2); B = tonos/dialytika
        // F24d-T2: shiftPairs ";"→":" — AOSP rowkeys_greek1.xml shows base ';' and shift ':'
        // (docs/AOSP_ROWKEYS_BELEGE.md, el-gr); CaseMapper.upper(";") is ";", hence
        // a matrix special case — exactly the same mechanism as ß→ẞ (line 57).
        MatrixRow("el-gr", listOf("α", "β", "γ", "δ", "ε", "ζ", "η", "θ", "ι", "κ", "λ", "μ", "ν", "ξ", "ο", "π", "ρ", "ς", "σ", "τ", "υ", "φ", "χ", "ψ", "ω"), requiredReachable = listOf("Σ", "ΐ", "ΰ", "ϊ", "ϋ", "ά", "έ", "ή", "ί", "ό", "ύ", "ώ"), shiftPairs = listOf(";" to ":")),
    )

    /**
     * F23b-T: de-de — ä/ö/ü/ß are no longer own keys (AOSP rowkeys_qwertz1.xml/
     * rowkeys_qwertz3.xml, see the MatrixRow comment), but the FIRST alternate of their
     * base letters; ẞ stays reachable via the S alternates of the shift layer.
     * The asset is migrated by F23b — this case is EXPECTED RED until then.
     */
    @Test
    fun `de-de - umlauts and eszett are the first alternate`() {
        val json = asset("de-de.json") ?: throw AssertionError("de-de.json not readable")
        val parsed = LayoutParser.parse(json)
        fun firstAlt(layer: String, id: String): String? =
            keysOf(parsed.layers.getValue(layer)).firstOrNull { it.id == id }?.alternates?.firstOrNull()
        org.junit.Assert.assertEquals("base a: alternates[0] must be ä", "ä", firstAlt("base", "a"))
        org.junit.Assert.assertEquals("base o: alternates[0] must be ö", "ö", firstAlt("base", "o"))
        org.junit.Assert.assertEquals("base u: alternates[0] must be ü", "ü", firstAlt("base", "u"))
        org.junit.Assert.assertEquals("base s: alternates[0] must be ß", "ß", firstAlt("base", "s"))
        val sShift = keysOf(parsed.layers.getValue("shift")).firstOrNull { it.id == "s" }
        assertTrue("shift S must carry ẞ in alternates (otherwise ẞ is lost)",
            sShift != null && sShift.alternates.contains("ẞ"))
    }

    /**
     * F54 (user order 02:4x): period long-press menu across ALL 15 layouts —
     * ‹/› and their cursor cells are OUT; uniform cell order
     * `,` `!` `?` `:` `;` `(` `)`; language prefixes stay at the FRONT (ru-ru:
     * @); at most 8 menu cells (MAX_CELLS); no more cursor-left/right
     * actions on the period key. (The FOOTER-row cursor KEYS are a
     * different feature and stay untouched.)
     */
    @Test
    fun `F54 - period alternates of all 15 layouts - target state without cursor actions`() {
        val standard = listOf(",", "!", "?", ":", ";", "(", ")")
        val layouts = listOf(
            "cs-cz", "da-dk", "de-de", "el-gr", "en-us", "es-es", "fi-fi",
            "fr-fr", "it-it", "nl-nl", "pl-pl", "pt-pt", "ru-ru", "sv-se", "tr-tr",
        )
        for (name in layouts) {
            val raw = asset("$name.json") ?: throw AssertionError("$name.json not readable")
            val root = JSONObject(raw)
            for (layerName in listOf("base", "shift")) {
                val layer = root.getJSONObject("layers").getJSONObject(layerName)
                val rows = layer.getJSONArray("rows")
                var seen = 0
                for (r in 0 until rows.length()) {
                    val row = rows.getJSONArray(r)
                    for (k in 0 until row.length()) {
                        val key = row.getJSONObject(k)
                        if (key.optString("id") != "period") continue
                        seen++
                        val alts = key.getJSONArray("alternates")
                        val list = (0 until alts.length()).map { alts.getString(it) }
                        val expected = if (name == "ru-ru") listOf("@") + standard else standard
                        assertEquals(
                            "$name/$layerName: period alternates == target state (language prefix + , ! ? : ; ( )",
                            expected, list,
                        )
                        assertTrue(
                            "$name/$layerName: at most ${de.voicekeyboard.keyboard.LongPressMenu.MAX_CELLS} cells",
                            list.size <= de.voicekeyboard.keyboard.LongPressMenu.MAX_CELLS,
                        )
                        assertTrue("$name: no more ‹", "‹" !in list)
                        assertTrue("$name: no more ›", "›" !in list)
                        if (key.has("cellActions")) {
                            val acts = key.getJSONArray("cellActions").join(",")
                            assertTrue("$name/$layerName: no cursor actions on the period key",
                                "cursor-left" !in acts && "cursor-right" !in acts)
                        }
                    }
                }
                assertTrue("$name/$layerName: period key must exist", seen == 1)
            }
        }
    }

    private fun asset(name: String): String? = try {
        val context = RuntimeEnvironment.getApplication() as Application
        context.assets.open("layouts/$name").bufferedReader().use { it.readText() }
    } catch (e: Exception) {
        null // asset does not exist (yet) → skip the matrix row
    }

    // ---------- counter-check (negative test with inline mutation, NO asset change) ----------

    /** A-3 counter-check: reverting the pt-pt fix (à again NO own key) must turn RED. */
    /**
     * F24d (AOSP qwerty row, R8 like de-de): the Portuguese accents are no
     * own keys anymore but the FIRST alternate of their base key — base a->á, c->ç,
     * e->é, i->í, o->õ, u->ú; shift the same uppercase forms. Ü stays a stage-B
     * alternate of U (as-is takeover from the former u-acute key).
     */
    private fun firstAltOf(parsed: LayoutAsset, layer: String, id: String): String? =
        keysOf(parsed.layers.getValue(layer)).firstOrNull { it.id == id }?.alternates?.firstOrNull()

    private fun checkPtAccentsAsFirstAlternate(parsed: LayoutAsset) {
        for ((id, acc) in listOf("a" to "á", "c" to "ç", "e" to "é", "i" to "í", "o" to "õ", "u" to "ú")) {
            org.junit.Assert.assertEquals("Asset 'pt-pt' base '$id': alternates[0] must be '$acc'", acc, firstAltOf(parsed, "base", id))
        }
        for ((id, acc) in listOf("a" to "Á", "c" to "Ç", "e" to "É", "i" to "Í", "o" to "Õ", "u" to "Ú")) {
            org.junit.Assert.assertEquals("Asset 'pt-pt' shift '$id': alternates[0] must be '$acc'", acc, firstAltOf(parsed, "shift", id))
        }
    }

    @Test
    fun `pt-pt - accents are the first alternate instead of an own key`() {
        val json = asset("pt-pt.json") ?: throw AssertionError("pt-pt.json not readable")
        checkPtAccentsAsFirstAlternate(LayoutParser.parse(json))
    }

    /**
     * F24d reinterpretation of the former a-grave counter-check case: the EARLIER rule
     * ("à as a missing OWN base key must turn red") no longer exists —
     * the case now checks the NEW rule: if base-a loses its accent chain
     * (alternates deleted), the accent-alternates case must turn red.
     */
    @Test
    fun `negative - pt-pt without an accent chain on a turns the new case red`() {
        val json = asset("pt-pt.json") ?: throw AssertionError("pt-pt.json not readable")
        val root = JSONObject(json)
        val rows: JSONArray = root.getJSONObject("layers").getJSONObject("base").getJSONArray("rows")
        var removed = false
        outer@ for (r in 0 until rows.length()) {
            val row = rows.getJSONArray(r)
            for (k in 0 until row.length()) {
                if (row.getJSONObject(k).getString("id") == "a") {
                    row.getJSONObject(k).remove("alternates")
                    removed = true
                    break@outer
                }
            }
        }
        assertTrue("counter-check: base key 'a' not found", removed)
        val parsed = LayoutParser.parse(root.toString())
        var caught = false
        try {
            checkPtAccentsAsFirstAlternate(parsed)
        } catch (e: AssertionError) {
            caught = true
            assertTrue("the message must name á: ${e.message}", e.message?.contains("á") == true)
            assertTrue("the message must name the asset: ${e.message}", e.message?.contains("pt-pt") == true)
        }
        assertTrue("the loss of the accent chain on 'a' was NOT detected (tautology suspicion)", caught)
    }

    private fun keysOf(layer: LayoutLayer) = layer.rows.flatten()

    @Test
    fun `all existing assets fulfill the language matrix and all basic rules`() {
        var checked = 0
        val skipped = mutableListOf<String>()
        for (row in matrix) {
            val json = asset("${row.assetId}.json")
            if (json == null) {
                skipped += row.assetId
                continue
            }
            val parsed = LayoutParser.parse(json)
            assertEqualsAssetId(row.assetId, parsed.id)
            checkRequiredOwnBaseKeys(row, parsed)
            checkReachable(row, parsed)
            checkShiftPairs(row, parsed)
            checkSymbolShiftKeys(parsed)
            checkNoLabelArtifacts(parsed)
            checkMandatorySymbols(parsed)
            checkAlternates(parsed)
            checkRequiredActionKeys(parsed)
            checkGeometry(parsed)
            checked++
        }
        assertTrue(
            "no asset checked — assets missing or unreadable (skipped: $skipped)",
            checked > 0,
        )
        println("LayoutCompletenessTest: $checked assets checked, skipped: $skipped")
    }

    /**
     * F27d→F36/V2 stock geometry as a standalone gate: all 15 assets carry
     * base+shift the V2 target image — sums 10/10/10/13 (ru-ru r2: 12, D27), no
     * ⌫ in r0, F40: ⌫ exactly 1x at the r1 END, @ exactly 1x in r2 before ⏎ (ru-ru:
     * exception, no swap), letter block center 5u (ru-ru 6u).
     */
    @Test
    fun `stock geometry holds for all 15 assets`() {
        val ids = listOf(
            "cs-cz", "da-dk", "de-de", "el-gr", "en-us", "es-es", "fi-fi", "fr-fr",
            "it-it", "nl-nl", "pl-pl", "pt-pt", "ru-ru", "sv-se", "tr-tr",
        )
        for (id in ids) {
            val json = asset("$id.json")
            assertTrue("asset $id missing or unreadable", json != null)
            checkGeometry(LayoutParser.parse(json!!))
        }
    }

    /**
     * F29 conclusion (ru-ru special case, base AND shift): 11 letters in r1 do not
     * allow an @ key (0.5 + 11 + 1 = 12.5u > 12; r2 is D27-locked and full).
     * Instead of an own @ key: @ as alternates[0] on the footer's period key;
     * the cursor cells stay on '‹'/'›' (cellActions at index 4/5).
     * D27 decision 2026-11-01 (F33 follow-up discussion): ru-ru r2 stays ⇧1+10+⏎1
     * (full row, center = view center 540 px; the Sol F33 formula B=min(N,7) does
     * not apply here) — this test and the ru-ru exceptions in checkGeometry are
     * the anchored target state.
     */
    @Test
    fun `ru-ru - no at object in r1, @ is alternates zero on period`() {
        val parsed = LayoutParser.parse(asset("ru-ru.json")!!) // ru-ru reference asset
        for (layerName in listOf("base", "shift")) {
            val rows = parsed.layers.getValue(layerName).rows
            assertTrue(
                "$layerName: no 'at' object in r1",
                rows[1].none { it.id == "at" && it.value == "@" },
            )
            val period = rows[3].first { it.id == "period" }
            assertEquals("$layerName: @ is alternates[0] on period", "@", period.alternates.first())
            assertEquals("$layerName: F54 standard cells behind @", listOf(",", "!", "?", ":", ";", "(", ")"), period.alternates.drop(1))
            assertEquals("$layerName: F54 — no more cursor actions", 0, period.cellActions.size)
        }
    }

    private fun assertEqualsAssetId(expected: String, actual: String) {
        if (expected != actual) fail("asset file $expected.json has internal id '$actual'")
    }

    /** Basic rule 1 + matrix: mandatory characters as an own key in base (not only alternates). */
    private fun checkRequiredOwnBaseKeys(row: MatrixRow, parsed: LayoutAsset) {
        val baseValues = keysOf(parsed.layers.getValue("base"))
            .filter { it.kind == KeyKind.TEXT }
            .map { it.value }
            .toSet()
        val missing = row.requiredOwnBaseKeys.filter { it !in baseValues }
        if (missing.isNotEmpty()) {
            fail("Asset '${parsed.id}': mandatory characters missing as own base keys: $missing " +
                "(alternates do NOT count, LAYOUT_REQUIREMENTS basic rule 1)")
        }
    }

    /** Matrix column „alternates": characters must be reachable (own OR alternate). */
    private fun checkReachable(row: MatrixRow, parsed: LayoutAsset) {
        val all = parsed.layers.values.flatMap { keysOf(it) }
        val reachable = all.flatMap { listOf(it.value) + it.alternates }.toSet()
        val missing = row.requiredReachable.filter { it !in reachable }
        if (missing.isNotEmpty()) {
            fail("Asset '${parsed.id}': unreachable matrix characters: $missing")
        }
    }

    /** Basic rule 2 + special cases: shift contains the uppercase form for every base text key (via CaseMapper respectively the matrix). */
    private fun checkShiftPairs(row: MatrixRow, parsed: LayoutAsset) {
        val baseKeys = keysOf(parsed.layers.getValue("base")).filter { it.kind == KeyKind.TEXT }
        val shiftKeys = keysOf(parsed.layers.getValue("shift")).filter { it.kind == KeyKind.TEXT }
        val shiftByLabel = shiftKeys.map { it.value }.toSet()
        // Every base text key needs a shift key with the language-correct uppercase form.
        // Exception: languages whose Unicode uppercase form is multi-codepoint (ß→SS) and
        // is explicitly required in the matrix special case (e.g. ß→ẞ) — then the pair test below checks.
        for (key in baseKeys) {
            val expectedUpper = CaseMapper.upper(key.value, parsed.language.caseLocale)
            val coveredByMatrixPair = row.shiftPairs.any { it.first == key.value }
            if (expectedUpper !in shiftByLabel && !coveredByMatrixPair) {
                fail("Asset '${parsed.id}': the shift layer has no key '${expectedUpper}' for base-" +
                    "'${key.value}' (key '${key.id}')")
            }
        }
        // Explicit matrix pairs (e.g. ß→ẞ, i→İ): the uppercase form must be reachable
        // in shift — own key OR alternate (§2.2 stage B, Rev 3).
        val shiftReachable = shiftKeys.flatMap { listOf(it.value) + it.alternates }.toSet()
        for ((lower, upper) in row.shiftPairs) {
            if (upper !in shiftReachable) {
                fail("Asset '${parsed.id}': the uppercase form '$upper' for '$lower' is not " +
                    "reachable in the shift layer (neither an own key nor an alternate)")
            }
        }
    }

    /** Basic rules 5a/5b + real artifacts (Rev 3: WHITELIST — \\ < > & are regular characters). */
    private fun checkNoLabelArtifacts(parsed: LayoutAsset) {
        val artifacts = listOf("=\\<", "⌇")
        for ((layerName, layer) in parsed.layers) {
            for (key in keysOf(layer)) {
                if (key.kind == KeyKind.TEXT) {
                    if (key.label != key.value) {
                        fail("Asset '${parsed.id}' layer '$layerName' key '${key.id}': " +
                            "label '${key.label}' != value '${key.value}' (rule 5a)")
                    }
                    if (key.label.codePointCount(0, key.label.length) != 1) {
                        fail("Asset '${parsed.id}' layer '$layerName' key '${key.id}': " +
                            "label '${key.label}' does not have exactly 1 codepoint (rule 5b)")
                    }
                }
                for (bad in artifacts) {
                    if (key.label.contains(bad)) {
                        fail("Asset '${parsed.id}' layer '$layerName' key '${key.id}': " +
                            "artifact '${key.label}' (rule 5d)")
                    }
                }
            }
        }
    }

    /** LAYOUT_REQUIREMENTS §2.1.1 mandatory lists (machine-readable, Rev 3). */
    private fun checkMandatorySymbols(parsed: LayoutAsset) {
        val mandatoryOnSymbols = ".,?!:;'\"-/@()&%+=#_*".toList()  // group 1, 20 characters
        val mandatoryOnSymbolsOrShift = "\\\\[]{}<>~|€$§°".toList()   // group 2, 13 characters
        val digits = "0123456789".toList()
        val symbols = keysOf(parsed.layers.getValue("symbols"))
            .filter { it.kind == KeyKind.TEXT }.map { it.value }
        val symbolsShift = keysOf(parsed.layers.getValue("symbolsShift"))
            .filter { it.kind == KeyKind.TEXT }.map { it.value }
        for (c in mandatoryOnSymbols + digits) {
            if (symbols.count { it == c.toString() } != 1) {
                fail("Asset '${parsed.id}': mandatory character '$c' missing on layer 'symbols' " +
                    "or occurs more than once (§2.1 group 1/digits; found: " +
                    "${symbols.count { it == c.toString() }}x)")
            }
        }
        for (c in mandatoryOnSymbolsOrShift) {
            if (symbols.count { it == c.toString() } + symbolsShift.count { it == c.toString() } < 1) {
                fail("Asset '${parsed.id}': mandatory character '$c' missing on 'symbols' " +
                    "AND 'symbolsShift' (§2.1 group 2)")
            }
        }
        // rule 8: no duplicate text values within a layer.
        for ((layerName, values) in listOf("symbols" to symbols, "symbolsShift" to symbolsShift)) {
            val dup = values.groupBy { it }.filterValues { it.size > 1 }.keys
            if (dup.isNotEmpty()) {
                fail("Asset '${parsed.id}' layer '$layerName': duplicate characters $dup (rule 8)")
            }
        }
    }

    /** Basic rule 6: alternates only characters (no actions), max 8. */
    private fun checkAlternates(parsed: LayoutAsset) {
        for ((layerName, layer) in parsed.layers) {
            for (key in keysOf(layer)) {
                if (key.alternates.size > 8) {
                    fail("Asset '${parsed.id}' layer '$layerName' key '${key.id}': " +
                        "${key.alternates.size} alternates > 8")
                }
                for (alt in key.alternates) {
                    if (alt in KeyActions.ALL) {
                        fail("Asset '${parsed.id}' layer '$layerName' key '${key.id}': " +
                            "alternates contain action name '$alt'")
                    }
                    if (alt.isEmpty()) {
                        fail("Asset '${parsed.id}' layer '$layerName' key '${key.id}': " +
                            "empty alternates element")
                    }
                }
            }
        }
    }

    /** Basic rule 4: mandatory action keys per layer, unique. */
    private fun checkRequiredActionKeys(parsed: LayoutAsset) {
        val isRu = parsed.id == "ru-ru"
        val mustHaveOnce = listOf(
            KeyActions.SPACE to "space",
            KeyActions.BACKSPACE to "backspace",
            KeyActions.ENTER to "enter",
            KeyActions.EMOJI to "emoji",
            KeyActions.MIC to "mic",
        )
        for ((layerName, layer) in parsed.layers) {
            val actions = keysOf(layer).filter { it.kind == KeyKind.ACTION }.map { it.value }
            for ((action, _) in mustHaveOnce) {
                // ru-ru D27: the letter layers (base/shift) have NO backspace
                // (r2 = shift1+10+enter1 is full; r0 = 11+margin has no space).
                val expected =
                    if (isRu && (layerName == "base" || layerName == "shift") && action == KeyActions.BACKSPACE) 0
                    else 1
                val count = actions.count { it == action }
                if (count != expected) {
                    fail("Asset '${parsed.id}' layer '$layerName': action '$action' " +
                        "present $count times (expected: exactly $expected)")
                }
            }
            // letter layers need shift, symbol layers a layer switch.
            val letterLayer = layerName == "base" || layerName == "shift"
            val needShift = actions.count { it == KeyActions.SHIFT || it == KeyActions.CAPS }
            val needLayer = actions.count {
                it == KeyActions.LAYER_SYMBOLS || it == KeyActions.LAYER_BASE
            }
            if (letterLayer && needShift + needLayer < 1) {
                fail("Asset '${parsed.id}' layer '$layerName': neither a shift nor a layer key")
            }
            if (!letterLayer && needLayer < 1) {
                fail("Asset '${parsed.id}' layer '$layerName': no layer-switch key")
            }
        }
    }

    /**
     * P1-03/M8c (LAYOUT_REQUIREMENTS rule 4a + §6 item 9): shift key in the symbol layers.
     * symbols AND symbolsShift: exactly one key with kind==action, value=="shift",
     * label=="⇧", id=="shift", at position row 2 / column 0, width==1.5.
     * Forbidden in both layers: value=="layer-symbols"; value=="layer-base" on a
     * key with label != "ABC" (ABC/layer-base in row 3 stays as a direct way).
     */
    private fun checkSymbolShiftKeys(parsed: LayoutAsset) {
        for (layerName in listOf("symbols", "symbolsShift")) {
            val rows = parsed.layers.getValue(layerName).rows
            // (1) position row 2 / column 0: id shift, ⇧, action, value shift, width 1.5
            if (rows.size <= 2 || rows[2].isEmpty()) {
                fail("Asset '${parsed.id}' layer '$layerName': row 2 missing — ⇧ key expected")
            }
            val pos = rows[2][0]
            if (pos.id != "shift" || pos.label != "⇧" || pos.kind != KeyKind.ACTION ||
                pos.value != KeyActions.SHIFT || pos.width != 1.5
            ) {
                fail("Asset '${parsed.id}' layer '$layerName' row 2/column 0: " +
                    "id='${pos.id}', label='${pos.label}', kind=${pos.kind}, value='${pos.value}', " +
                    "width=${pos.width} (expected: id=shift, label=⇧, action, shift, 1.5)")
            }
            // (2) exactly ONE key with value==shift AND label==⇧ in this layer
            val shiftKeys = keysOf(parsed.layers.getValue(layerName))
                .filter { it.value == KeyActions.SHIFT }
            if (shiftKeys.size != 1 || shiftKeys[0].label != "⇧") {
                fail("Asset '${parsed.id}' layer '$layerName': ${shiftKeys.size} key(s) with " +
                    "value==shift — expected exactly 1 with label ⇧")
            }
            // (3) forbidden values
            for (key in keysOf(parsed.layers.getValue(layerName))) {
                if (key.value == KeyActions.LAYER_SYMBOLS) {
                    fail("Asset '${parsed.id}' layer '$layerName' key '${key.id}': " +
                        "value=='layer-symbols' is forbidden (rule 4a)")
                }
                if (key.value == KeyActions.LAYER_BASE && key.label != "ABC") {
                    fail("Asset '${parsed.id}' layer '$layerName' key '${key.id}': " +
                        "layer-base with label '${key.label}' != 'ABC' (rule 4a)")
                }
            }
        }
    }

    /**
     * F27d/F29→F36/V2 + F40 (docs/DESIGN_TASTATUR_V2.md, 1u = 108 px; user
     * feedback v0.1.27): base/shift carry the V2 grid — r0 = 10 letters at
     * 1.0 each WITHOUT ⌫; r1 = margin 0.5 + letter block 8.5u + ⌫ 1.0 (F40: at the
     * r1 END); r2 = T11 (docs/PLAN_T11_T12.md §2, D1 = variant B) boundary grid:
     * NO more @ (reachable via the M long-press menu), the 13 7-key assets
     * ⇧ 1.22224 + 8 at 0.94444 (7 letters + period, 102-px cells) + ⏎ 1.22224,
     * center chain symmetric about 5.0 (every center pair sums to 10);
     * cs-cz (B') ⇧ 2.025 + 7 at 0.85 (6 letters + period) + ⏎ 2.025; footer r3
     * Σ13 STAYS but the period moves up to r2 (space 6.5→7.5, 5 keys; its own
     * unit u_footer). Rounding: 5 decimals, sum tolerance
     * 0.001u (≤ 0.11 px). Special cases: cs-cz r1 = 10 letters at 0.85 + ⌫ 1.0
     * (the same 8.5u block); ru-ru r0/r1 = margin 0.11111 (12 px) per side +
     * 11 at 0.88889 (96 px, spec §6), r2 stays the D27 full row ⇧1 + 10 at
     * 1.0 + ⏎1 = Σ12 (spec §6 "unchanged"; NO swap — no @ in r1, ⌫ only on
     * the symbol layers, @ on period). fr-fr is structurally standard (r1 has
     * As-is 9 letters + ⌫, m lies in r2 — spec §6 "10 letters" does not match
     * the asset, no key moves).
     */
    private fun checkGeometry(parsed: LayoutAsset) {
        val isRu = parsed.id == "ru-ru"
        val isCs = parsed.id == "cs-cz"
        for (layerName in listOf("base", "shift")) {
            val rows = parsed.layers.getValue(layerName).rows
            if (rows.size < 4) {
                fail("Asset '${parsed.id}' $layerName: fewer than 4 rows (V2 geometry)")
            }
            val expectSums = if (isRu) listOf(10.0, 10.0, 12.0, 13.0) else listOf(10.0, 10.0, 10.0, 13.0)
            val sums = rows.take(4).map { row -> row.sumOf { it.width } }
            for (i in expectSums.indices) {
                if (kotlin.math.abs(sums[i] - expectSums[i]) > 0.001) {
                    fail("Asset '${parsed.id}' $layerName: row sum r$i=" +
                        "${"%.5f".format(sums[i])}u != ${expectSums[i]}u (F36/V2)")
                }
            }
            // ⌫ leaves r0 (all assets); F40: ⌫ sits exactly 1x at the r1 END (ru-ru:
            // none in the letter layers, D27) and NOT anymore in r2.
            val bsR0 = rows[0].count { it.kind == KeyKind.ACTION && it.value == KeyActions.BACKSPACE }
            if (bsR0 != 0) fail("Asset '${parsed.id}' $layerName: r0 must carry NO backspace (F36/V2)")
            val bsR2 = rows[2].count { it.kind == KeyKind.ACTION && it.value == KeyActions.BACKSPACE }
            if (bsR2 != 0) fail("Asset '${parsed.id}' $layerName: r2 must carry NO backspace (F40 — ⌫ sits at the r1 end)")
            val enterR2 = rows[2].count { it.kind == KeyKind.ACTION && it.value == KeyActions.ENTER }
            val enterR3 = rows[3].count { it.kind == KeyKind.ACTION && it.value == KeyActions.ENTER }
            if (enterR2 != 1) fail("Asset '${parsed.id}' $layerName: enter $enterR2 times in r2 (expected 1)")
            if (enterR3 != 0) fail("Asset '${parsed.id}' $layerName: enter forbidden in r3 (V2 geometry)")
            // r1: margin 0.5 + letter block + @ 1.0; ru-ru: full row with 12-px side margins, no @.
            val r1 = rows[1]
            if (isRu) {
                if (r1[0].kind != KeyKind.MARGIN || r1.last().kind != KeyKind.MARGIN ||
                    kotlin.math.abs(r1[0].width - 0.11111) > 0.001 ||
                    kotlin.math.abs(r1.last().width - 0.11111) > 0.001
                ) fail("Asset 'ru-ru' $layerName: r1 needs 0.11111u side margins (12 px, spec §6)")
                if (r1.any { it.kind == KeyKind.TEXT && it.value == "@" }) {
                    fail("Asset 'ru-ru' $layerName: r1 must carry no @ key (D27, @ on period)")
                }
                val ruLetters1 = r1.filter { it.kind == KeyKind.TEXT }
                if (ruLetters1.size != 11) {
                    fail("Asset 'ru-ru' $layerName: r1 needs 11 letters (has ${ruLetters1.size})")
                }
                for (k in ruLetters1) {
                    if (kotlin.math.abs(k.width - 0.88889) > 0.001) {
                        fail("Asset 'ru-ru' $layerName r1 '${k.id}': width ${k.width} != 0.88889 (96 px, spec §6)")
                    }
                }
            } else {
                if (r1.isEmpty() || r1[0].kind != KeyKind.MARGIN ||
                    kotlin.math.abs(r1[0].width - 0.5) > 0.0001
                ) {
                    fail("Asset '${parsed.id}' $layerName: r1[0] must be margin 0.5 (F36/V2)")
                }
                val atInR1 = r1.count { it.kind == KeyKind.TEXT && it.value == "@" }
                if (atInR1 != 0) {
                    fail("Asset '${parsed.id}' $layerName: r1 must carry NO @ (T8: @ is not a direct key anymore, reachable via the M menu)")
                }
                val bsR1 = r1.filter { it.kind == KeyKind.ACTION && it.value == KeyActions.BACKSPACE }
                if (bsR1.size != 1 || r1.last() !== bsR1[0] ||
                    kotlin.math.abs(bsR1[0].width - 1.0) > 0.0001
                ) {
                    fail("Asset '${parsed.id}' $layerName: ⌫ exactly 1x at the r1 END (width 1.0) expected (F40)")
                }
                val letters = r1.filter { it.kind == KeyKind.TEXT && it.value != "@" }
                val wantN = if (isCs) 10 else 9
                val wantW = if (isCs) 0.85 else 0.94444 // 10 respectively 9 letters of the 8.5u block
                if (letters.size != wantN) {
                    fail("Asset '${parsed.id}' $layerName: r1 needs $wantN letters (has ${letters.size})")
                }
                for (k in letters) {
                    if (kotlin.math.abs(k.width - wantW) > 0.001) {
                        fail("Asset '${parsed.id}' $layerName r1 '${k.id}': width ${k.width} != $wantW (F36/V2)")
                    }
                }
                if (r1.last().kind == KeyKind.MARGIN) {
                    fail("Asset '${parsed.id}' $layerName: r1 without right margin (Σ10, F36/V2)")
                }
            }
            // r2 T11 (docs/PLAN_T11_T12.md §2, D1 = VARIANT B): boundary grid —
            // 8 equal cells (7 letters + the NEW period key id 'period' before ⏎)
            // on the r1 boundary raster, ⇧ and ⏎ take the symmetric remainder.
            //  - the 13 7-key assets: ⇧ 1.22224 + 8 at 0.94444 (102 px) + ⏎ 1.22224
            //    (px: 132 + 8×102 + 132 = 1080; centers 1.69446..8.30554).
            //  - cs-cz (B'): ⇧ 2.025 + 7 at 0.85 (6 letters + period) + ⏎ 2.025.
            //  - period carries the F54 menu , ! ? : ; ( ) (moved up from the
            //    footer, base AND shift); M keeps its @ alternate (T8).
            //  - ru-ru: the D27 full row stays (no period key in r2, 6-key footer).
            val r2 = rows[2]
            val r2Texts = r2.filter { it.kind == KeyKind.TEXT }
            val wantShift = when { isRu -> 1.0; isCs -> 2.025; else -> 1.22224 }
            if (r2.isEmpty() || r2[0].kind != KeyKind.ACTION || r2[0].value != KeyActions.SHIFT ||
                kotlin.math.abs(r2[0].width - wantShift) > 0.001
            ) {
                fail("Asset '${parsed.id}' $layerName: r2[0] must be shift width $wantShift (T11, is " +
                    "${r2.firstOrNull()?.let { it.value + "/" + it.width }})")
            }
            val wantL = when {
                isRu -> 1.0    // D27: 10 at 1.0 in the Σ12 row
                isCs -> 0.85   // 91.8 px
                else -> 0.94444 // 102 px — boundary raster of the home row
            }
            val wantNT = if (isRu) 10 else if (isCs) 7 else 8 // letters + period
            if (r2Texts.size != wantNT) {
                fail("Asset '${parsed.id}' $layerName: r2 needs $wantNT text keys incl. period (has ${r2Texts.size})")
            }
            for (k in r2Texts) {
                if (kotlin.math.abs(k.width - wantL) > 0.001) {
                    fail("Asset '${parsed.id}' $layerName r2 '${k.id}': width ${k.width} != $wantL (T11)")
                }
            }
            val enter = r2.firstOrNull { it.kind == KeyKind.ACTION && it.value == KeyActions.ENTER }
            val wantEnter = when { isRu -> 1.0; isCs -> 2.025; else -> 1.22224 }
            if (enter == null || kotlin.math.abs(enter.width - wantEnter) > 0.001) {
                fail("Asset '${parsed.id}' $layerName: ⏎ width $wantEnter expected in r2 (T11)")
            }
            if (!isRu) {
                // T11: NO more direct @ key in r2 — @ runs via the M menu.
                if (r2.any { it.kind == KeyKind.TEXT && it.value == "@" }) {
                    fail("Asset '${parsed.id}' $layerName: direct @ key forbidden in r2 (@ → M menu)")
                }
                // T11: the period key sits exactly 1× in r2 (slot before ⏎).
                val period = r2.filter { it.id == "period" }
                if (period.size != 1 || period[0].kind != KeyKind.TEXT || period[0].value != ".") {
                    fail("Asset '${parsed.id}' $layerName: exactly 1 period key (text, '.') expected in r2 (T11)")
                }
                // M lookup excludes the period key (id 'period'): the M key is
                // the LAST LETTER, not the last text key of r2.
                val m = r2Texts.lastOrNull { it.id != "period" }
                if (m == null || m.alternates.none { it == "@" }) {
                    fail("Asset '${parsed.id}' $layerName: the M key must carry @ in its long-press menu (T8)")
                }
            }
            if (r2.last().kind == KeyKind.MARGIN) {
                fail("Asset '${parsed.id}' $layerName: r2 without right margin (F36/V2)")
            }
            // T11 invariant (variant B): the FULL r2 center chain is symmetric
            // about 5.0 — every center pair c[i] + c[n-1-i] = 10 (⇧ = ⏎). Replaces
            // the T8 Y/M centering (13/9, 145/18) — documented deviation in
            // DESIGN_TASTATUR_V2 §10.
            if (!isRu) {
                var x2 = 0.0
                val c2 = r2.map { x2 += it.width; x2 - it.width / 2 }
                for (i in c2.indices) {
                    if (kotlin.math.abs(c2[i] + c2[c2.size - 1 - i] - 10.0) > 0.001) {
                        fail("Asset '${parsed.id}' $layerName: T11 symmetry violated — " +
                            "c[$i]=${"%.5f".format(c2[i])} + c[${c2.size - 1 - i}]=${"%.5f".format(c2[c2.size - 1 - i])} != 10")
                    }
                }
            }
        }
    }

    /**
     * Counter-checks (must turn red, §6 item 9): mutated assets must be
     * rejected by checkSymbolShiftKeys.
     */
    @Test
    fun `counter-checks symbolsShift back key must be rejected`() {
        val base = LayoutParser.parse(asset("de-de.json")!!) // correct reference asset
        // (a) key effectively missing in symbolsShift row 2/column 0 (id changed)
        assertRejected(mutateShiftKey(base, "symbolsShift") { it.copy(id = "shift-x") }, "(a) key missing")
        // (b) da-dk as-is state: label ?123 + value shift
        assertRejected(mutateShiftKey(base, "symbolsShift") { it.copy(label = "?123") }, "(b) ?123 label")
        // (c) ⇧ with value layer-base (as-is state ru-ru/cs-cz)
        assertRejected(mutateShiftKey(base, "symbolsShift") { it.copy(value = "layer-base") }, "(c) layer-base on ⇧")
    }

    private fun mutateShiftKey(
        asset: LayoutAsset,
        layerName: String,
        transform: (LayoutKey) -> LayoutKey,
    ): LayoutAsset {
        val rows = asset.layers.getValue(layerName).rows
            .map { row -> row.toMutableList() }
            .toMutableList()
        rows[2][0] = transform(rows[2][0])
        val newLayers = asset.layers.toMutableMap()
        newLayers[layerName] = LayoutLayer(layerName, rows)
        return asset.copy(layers = newLayers)
    }

    private fun assertRejected(mutated: LayoutAsset, label: String) {
        try {
            checkSymbolShiftKeys(mutated)
            fail("counter-check $label: the mutated asset was NOT rejected")
        } catch (expected: AssertionError) {
            // expected red
        }
    }
}
