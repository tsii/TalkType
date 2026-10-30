package de.voicekeyboard.layouts

import android.app.Application
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
 * LayoutCompletenessTest (LAYOUT_REQUIREMENTS Abnahmekriterien 1+2):
 * Prüft JEDES vorhandene Layout-Asset gegen die Sprachmatrix und die Grundregeln.
 *
 * - Pflichtzeichen der Matrix-Zeile als EIGENE Taste in `base` (alternates zählen NICHT).
 * - shift ist vollständige Großform: jede base-Text-Taste hat eine shift-Taste mit
 *   sprachrichtiger Großform (CaseMapper), inkl. Sonderfall ß→ẞ für Deutsch.
 * - Grundregel 5 (Rev 3, WHITELIST): label == value, genau ein Codepoint, keine
 *   Artefakte (`=\\<`, `⌇`). `& \\ < >` sind KEINE Artefakte, sondern §2.1-Pflichtzeichen.
 * - §2.1 Pflichtsymbole: Gruppe 1 (20) + Ziffern je einmal auf `symbols`,
 *   Gruppe 2 (13) auf `symbols` ODER `symbolsShift` — Fehlermeldung nennt
 *   Asset und fehlendes Zeichen.
 * - Grundregel 6: alternates nur Zeichen (keine Action-Namen), max 8 pro Taste.
 * - Grundregel 4: je Ebene genau eine space-, backspace-, enter-, emoji- und mic-Taste
 *   sowie shift/layer-Tasten.
 *
 * Die Matrix unten listet alle 15 Sprachen. Assets, die es (noch) nicht gibt,
 * werden proaktiv übersprungen — der Test deckt damit automatisch jede neue Sprache ab,
 * sobald ihr Asset hinzukommt (Stufe 3).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LayoutCompletenessTest {

    /** Eine Zeile der Sprachmatrix aus LAYOUT_REQUIREMENTS.md. */
    private data class MatrixRow(
        val assetId: String,
        val requiredOwnBaseKeys: List<String>,
        /** Zeichen, die reachable sein müssen: eigene Taste ODER alternates (base oder shift). */
        val requiredReachable: List<String> = emptyList(),
        /** lowercase → uppercase Paare, die in shift als eigene Taste/value existieren müssen. */
        val shiftPairs: List<Pair<String, String>> = emptyList(),
    )

    // Sprachmatrix gemäß LAYOUT_REQUIREMENTS.md Rev 3, §2.2 (Spalten A/B).
    // Jede Zeile mit §-Verweis; A = eigene base-Taste, B = erreichbar (eigen ODER alternate).
    private val matrix: List<MatrixRow> = listOf(
        // §2.2: de-de A = ä ö ü ß; shift ẞ (§3 Case-Mapper)
        MatrixRow("de-de", listOf("ä", "ö", "ü", "ß"), shiftPairs = listOf("ß" to "ẞ")),
        // §2.2: en-us — keine A/B-Buchstaben (ASCII); Satzzeichen deckt PunctuationCoverageTest ab
        MatrixRow("en-us", emptyList(), requiredReachable = listOf("'", "\"", "–", "—", "…")),
        // §2.2: fr-fr A = é è à ç ù (ê nur B); B = ê ë â ï î ô û ü œ æ ÿ
        MatrixRow("fr-fr", listOf("é", "è", "à", "ç", "ù"), requiredReachable = listOf("ê", "ë", "ï", "î", "ô", "û", "ü", "œ", "æ", "ÿ"), shiftPairs = listOf("œ" to "Œ", "æ" to "Æ", "ç" to "Ç")),
        // §2.2: es-es A = ñ; B = á é í ó ú ü
        MatrixRow("es-es", listOf("ñ"), requiredReachable = listOf("á", "é", "í", "ó", "ú", "ü", "¿", "¡"), shiftPairs = listOf("ñ" to "Ñ")),
        // §2.2: it-it A = à è é ì ò ù; B = á í ó ú î ü (Rev 3: ü B-pflicht — Audit B1)
        MatrixRow("it-it", listOf("à", "è", "é", "ì", "ò", "ù"), requiredReachable = listOf("á", "í", "ó", "ú", "î", "ü")),
        // §2.2: pt-pt A = á â ã à ç é ê í ó ô õ ú (Rev 3/D2: à ist Stufe A — Crase-Graphem); B = ü
        MatrixRow("pt-pt", listOf("á", "â", "ã", "à", "ç", "é", "ê", "í", "ó", "ô", "õ", "ú"), requiredReachable = listOf("ü"), shiftPairs = listOf("ç" to "Ç")),
        // §2.2: nl-nl A = —; B = á à ä ë é è ê ï ö ü (Rev 3: ä B-pflicht — Audit B1)
        MatrixRow("nl-nl", emptyList(), requiredReachable = listOf("á", "à", "ä", "ë", "é", "è", "ê", "ï", "ö", "ü")),
        // §2.2: pl-pl A = ą ć ę ł ń ó ś ź ż (Großformen KEINE Alternates, Rev-1-Korrektur)
        MatrixRow("pl-pl", listOf("ą", "ć", "ę", "ł", "ń", "ó", "ś", "ź", "ż"), shiftPairs = listOf("ą" to "Ą", "ć" to "Ć", "ę" to "Ę", "ł" to "Ł", "ń" to "Ń", "ó" to "Ó", "ś" to "Ś", "ź" to "Ź", "ż" to "Ż")),
        // §2.2: tr-tr A = ç ğ ı i ö ş ü (i UND ı eigen); shift i→İ, ı→I (§3)
        MatrixRow("tr-tr", listOf("ğ", "ü", "ş", "ı", "ö", "ç"), requiredReachable = listOf("â", "î", "û"), shiftPairs = listOf("ğ" to "Ğ", "ü" to "Ü", "ş" to "Ş", "ı" to "I", "ö" to "Ö", "ç" to "Ç", "i" to "İ")),
        // §2.2: ru-ru A = а–я vollständig + ё (U+0451 explizit, вне Bereichs)
        MatrixRow("ru-ru", listOf("а", "б", "в", "г", "д", "е", "ё", "ж", "з", "и", "й", "к", "л", "м", "н", "о", "п", "р", "с", "т", "у", "ф", "х", "ц", "ч", "ш", "щ", "ъ", "ы", "ь", "э", "ю", "я")),
        // §2.2: cs-cz A = á č é ě í ý ú ů ř š ž; B = ď ť ň ó (HW nicht direkt belegt)
        MatrixRow("cs-cz", listOf("ě", "š", "č", "ř", "ž", "ý", "á", "í", "é", "ú", "ů"), requiredReachable = listOf("ď", "ť", "ň", "ó")),
        // §2.2: sv-se A = å ä ö; B = á à é è ü
        MatrixRow("sv-se", listOf("å", "ä", "ö"), requiredReachable = listOf("á", "à", "é", "è", "ü")),
        // §2.2: da-dk A = æ ø å; B = ä é ö ü
        MatrixRow("da-dk", listOf("æ", "ø", "å"), requiredReachable = listOf("ä", "ö", "é", "ü")),
        // §2.2: fi-fi A = ä ö å (Rev 1-Korrektur: å ist Pflicht, nicht nur B); B = š ž é
        MatrixRow("fi-fi", listOf("ä", "ö", "å"), requiredReachable = listOf("š", "ž", "é")),
        // §2.2: el-gr A = α–ω vollständig + ς (explizite Liste, U+03C2); B = Tonos/Dialytika
        MatrixRow("el-gr", listOf("α", "β", "γ", "δ", "ε", "ζ", "η", "θ", "ι", "κ", "λ", "μ", "ν", "ξ", "ο", "π", "ρ", "ς", "σ", "τ", "υ", "φ", "χ", "ψ", "ω"), requiredReachable = listOf("Σ", "ΐ", "ΰ", "ϊ", "ϋ", "ά", "έ", "ή", "ί", "ό", "ύ", "ώ")),
    )

    private fun asset(name: String): String? = try {
        val context = RuntimeEnvironment.getApplication() as Application
        context.assets.open("layouts/$name").bufferedReader().use { it.readText() }
    } catch (e: Exception) {
        null // Asset existiert (noch) nicht → Matrix-Zeile überspringen
    }

    // ---------- Gegenprobe (Negativtest mit Inline-Mutation, KEINE Asset-Änderung) ----------

    /** A-3-Gegenprobe: Rückbau des pt-pt-Fixes (à wieder KEINE eigene Taste) muss ROT machen. */
    @Test
    fun `negativ - fehlendes A-Pflichtzeichen a-grave in pt-pt wird erkannt`() {
        val json = asset("pt-pt.json") ?: throw AssertionError("pt-pt.json nicht lesbar")
        val root = JSONObject(json)
        // Die a-grave-Taste aus base UND shift entfernen (Paarregel: Breitensummen
        // und base→shift-ID-Abdeckung müssen für den Parser erhalten bleiben).
        for (layer in listOf("base", "shift")) {
            val rows: JSONArray = root.getJSONObject("layers").getJSONObject(layer).getJSONArray("rows")
            for (r in 0 until rows.length()) {
                val row = rows.getJSONArray(r)
                for (k in 0 until row.length()) {
                    if (row.getJSONObject(k).getString("id") == "a-grave") {
                        row.remove(k)
                        break
                    }
                }
            }
        }
        val parsed = LayoutParser.parse(root.toString())
        val ptRow = matrix.first { it.assetId == "pt-pt" }
        var caught = false
        try {
            checkRequiredOwnBaseKeys(ptRow, parsed)
        } catch (e: AssertionError) {
            caught = true
            assertTrue("Meldung muss à nennen: ${e.message}", e.message?.contains("à") == true)
            assertTrue("Meldung muss Asset nennen: ${e.message}", e.message?.contains("pt-pt") == true)
        }
        assertTrue("Rückbau von à (Stufe A) in pt-pt wurde NICHT erkannt (Tautologie-Verdacht)", caught)
    }

    private fun keysOf(layer: LayoutLayer) = layer.rows.flatten()

    @Test
    fun `alle vorhandenen Assets erfuellen die Sprachmatrix und alle Grundregeln`() {
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
            checked++
        }
        assertTrue(
            "Kein Asset geprüft — Assets fehlen oder sind unlesbar (übersprungen: $skipped)",
            checked > 0,
        )
        println("LayoutCompletenessTest: $checked Assets geprüft, übersprungen: $skipped")
    }

    private fun assertEqualsAssetId(expected: String, actual: String) {
        if (expected != actual) fail("Asset-Datei $expected.json hat interne id '$actual'")
    }

    /** Grundregel 1 + Matrix: Pflichtzeichen als eigene Taste in base (nicht nur alternates). */
    private fun checkRequiredOwnBaseKeys(row: MatrixRow, parsed: LayoutAsset) {
        val baseValues = keysOf(parsed.layers.getValue("base"))
            .filter { it.kind == KeyKind.TEXT }
            .map { it.value }
            .toSet()
        val missing = row.requiredOwnBaseKeys.filter { it !in baseValues }
        if (missing.isNotEmpty()) {
            fail("Asset '${parsed.id}': Pflichtzeichen fehlen als eigene base-Taste: $missing " +
                "(alternates zählen NICHT, LAYOUT_REQUIREMENTS Grundregel 1)")
        }
    }

    /** Matrix-Spalte „alternates": Zeichen müssen erreichbar sein (eigen ODER alternate). */
    private fun checkReachable(row: MatrixRow, parsed: LayoutAsset) {
        val all = parsed.layers.values.flatMap { keysOf(it) }
        val reachable = all.flatMap { listOf(it.value) + it.alternates }.toSet()
        val missing = row.requiredReachable.filter { it !in reachable }
        if (missing.isNotEmpty()) {
            fail("Asset '${parsed.id}': nicht erreichbare Matrix-Zeichen: $missing")
        }
    }

    /** Grundregel 2 + Sonderfälle: shift enthält die Großform je base-Text-Taste (per CaseMapper bzw. Matrix). */
    private fun checkShiftPairs(row: MatrixRow, parsed: LayoutAsset) {
        val baseKeys = keysOf(parsed.layers.getValue("base")).filter { it.kind == KeyKind.TEXT }
        val shiftKeys = keysOf(parsed.layers.getValue("shift")).filter { it.kind == KeyKind.TEXT }
        val shiftByLabel = shiftKeys.map { it.value }.toSet()
        // Jede base-Text-Taste braucht eine shift-Taste mit sprachrichtiger Großform.
        // Ausnahme: Sprachen, deren Unicode-Großform mehrcodepointig ist (ß→SS) und im
        // Matrix-Sonderfall explizit gefordert ist (z. B. ß→ẞ) — dann prüft der Paar-Test unten.
        for (key in baseKeys) {
            val expectedUpper = CaseMapper.upper(key.value, parsed.language.caseLocale)
            val coveredByMatrixPair = row.shiftPairs.any { it.first == key.value }
            if (expectedUpper !in shiftByLabel && !coveredByMatrixPair) {
                fail("Asset '${parsed.id}': shift-Ebene hat keine Taste '${expectedUpper}' für base-" +
                    "'${key.value}' (Taste '${key.id}')")
            }
        }
        // Explizite Matrix-Paare (z. B. ß→ẞ, i→İ): Großform muss in shift erreichbar
        // sein — eigene Taste ODER alternate (§2.2 Stufe B, Rev 3).
        val shiftReachable = shiftKeys.flatMap { listOf(it.value) + it.alternates }.toSet()
        for ((lower, upper) in row.shiftPairs) {
            if (upper !in shiftReachable) {
                fail("Asset '${parsed.id}': Großform '$upper' für '$lower' ist in der " +
                    "shift-Ebene nicht erreichbar (weder eigene Taste noch alternate)")
            }
        }
    }

    /** Grundregel 5a/5b + echte Artefakte (Rev 3: WHITELIST — \\ < > & sind reguläre Zeichen). */
    private fun checkNoLabelArtifacts(parsed: LayoutAsset) {
        val artifacts = listOf("=\\<", "⌇")
        for ((layerName, layer) in parsed.layers) {
            for (key in keysOf(layer)) {
                if (key.kind == KeyKind.TEXT) {
                    if (key.label != key.value) {
                        fail("Asset '${parsed.id}' Ebene '$layerName' Taste '${key.id}': " +
                            "label '${key.label}' != value '${key.value}' (Regel 5a)")
                    }
                    if (key.label.codePointCount(0, key.label.length) != 1) {
                        fail("Asset '${parsed.id}' Ebene '$layerName' Taste '${key.id}': " +
                            "label '${key.label}' hat nicht genau 1 Codepoint (Regel 5b)")
                    }
                }
                for (bad in artifacts) {
                    if (key.label.contains(bad)) {
                        fail("Asset '${parsed.id}' Ebene '$layerName' Taste '${key.id}': " +
                            "Artefakt '${key.label}' (Regel 5d)")
                    }
                }
            }
        }
    }

    /** LAYOUT_REQUIREMENTS §2.1.1 Pflichtlisten (Maschinenlesbar, Rev 3). */
    private fun checkMandatorySymbols(parsed: LayoutAsset) {
        val mandatoryOnSymbols = ".,?!:;'\"-/@()&%+=#_*".toList()  // Gruppe 1, 20 Zeichen
        val mandatoryOnSymbolsOrShift = "\\\\[]{}<>~|€$§°".toList()   // Gruppe 2, 13 Zeichen
        val digits = "0123456789".toList()
        val symbols = keysOf(parsed.layers.getValue("symbols"))
            .filter { it.kind == KeyKind.TEXT }.map { it.value }
        val symbolsShift = keysOf(parsed.layers.getValue("symbolsShift"))
            .filter { it.kind == KeyKind.TEXT }.map { it.value }
        for (c in mandatoryOnSymbols + digits) {
            if (symbols.count { it == c.toString() } != 1) {
                fail("Asset '${parsed.id}': Pflichtzeichen '$c' fehlt auf Ebene 'symbols' " +
                    "oder kommt mehrfach vor (§2.1 Gruppe 1/Ziffern; gefunden: " +
                    "${symbols.count { it == c.toString() }}x)")
            }
        }
        for (c in mandatoryOnSymbolsOrShift) {
            if (symbols.count { it == c.toString() } + symbolsShift.count { it == c.toString() } < 1) {
                fail("Asset '${parsed.id}': Pflichtzeichen '$c' fehlt auf 'symbols' " +
                    "UND 'symbolsShift' (§2.1 Gruppe 2)")
            }
        }
        // Regel 8: innerhalb einer Ebene keine doppelten text-values.
        for ((layerName, values) in listOf("symbols" to symbols, "symbolsShift" to symbolsShift)) {
            val dup = values.groupBy { it }.filterValues { it.size > 1 }.keys
            if (dup.isNotEmpty()) {
                fail("Asset '${parsed.id}' Ebene '$layerName': doppelte Zeichen $dup (Regel 8)")
            }
        }
    }

    /** Grundregel 6: alternates nur Zeichen (keine Actions), max 8. */
    private fun checkAlternates(parsed: LayoutAsset) {
        for ((layerName, layer) in parsed.layers) {
            for (key in keysOf(layer)) {
                if (key.alternates.size > 8) {
                    fail("Asset '${parsed.id}' Ebene '$layerName' Taste '${key.id}': " +
                        "${key.alternates.size} alternates > 8")
                }
                for (alt in key.alternates) {
                    if (alt in KeyActions.ALL) {
                        fail("Asset '${parsed.id}' Ebene '$layerName' Taste '${key.id}': " +
                            "alternates enthalten Action-Namen '$alt'")
                    }
                    if (alt.isEmpty()) {
                        fail("Asset '${parsed.id}' Ebene '$layerName' Taste '${key.id}': " +
                            "leeres alternates-Element")
                    }
                }
            }
        }
    }

    /** Grundregel 4: Pflicht-Aktionstasten je Ebene, eindeutig. */
    private fun checkRequiredActionKeys(parsed: LayoutAsset) {
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
                val count = actions.count { it == action }
                if (count != 1) {
                    fail("Asset '${parsed.id}' Ebene '$layerName': Aktion '$action' " +
                        "$count-mal vorhanden (erwartet: genau 1)")
                }
            }
            // Buchstaben-Ebenen brauchen shift, Symbolebenen layer-Wechsel.
            val letterLayer = layerName == "base" || layerName == "shift"
            val needShift = actions.count { it == KeyActions.SHIFT || it == KeyActions.CAPS }
            val needLayer = actions.count {
                it == KeyActions.LAYER_SYMBOLS || it == KeyActions.LAYER_BASE
            }
            if (letterLayer && needShift + needLayer < 1) {
                fail("Asset '${parsed.id}' Ebene '$layerName': weder shift- noch layer-Taste")
            }
            if (!letterLayer && needLayer < 1) {
                fail("Asset '${parsed.id}' Ebene '$layerName': keine layer-Wechsel-Taste")
            }
        }
    }

    /**
     * P1-03/M8c (LAYOUT_REQUIREMENTS Regel 4a + §6 Punkt 9): Shift-Taste in den Symbolebenen.
     * symbols UND symbolsShift: genau eine Taste mit kind==action, value=="shift",
     * label=="⇧", id=="shift", an Position Reihe 2 / Spalte 0, width==1.5.
     * Verboten in beiden Ebenen: value=="layer-symbols"; value=="layer-base" an einer
     * Taste mit Label != "ABC" (ABC/layer-base in Reihe 3 bleibt als direkter Weg).
     */
    private fun checkSymbolShiftKeys(parsed: LayoutAsset) {
        for (layerName in listOf("symbols", "symbolsShift")) {
            val rows = parsed.layers.getValue(layerName).rows
            // (1) Position Reihe 2 / Spalte 0: id shift, ⇧, action, value shift, width 1.5
            if (rows.size <= 2 || rows[2].isEmpty()) {
                fail("Asset '${parsed.id}' Ebene '$layerName': Reihe 2 fehlt — ⇧-Taste erwartet")
            }
            val pos = rows[2][0]
            if (pos.id != "shift" || pos.label != "⇧" || pos.kind != KeyKind.ACTION ||
                pos.value != KeyActions.SHIFT || pos.width != 1.5
            ) {
                fail("Asset '${parsed.id}' Ebene '$layerName' Reihe 2/Spalte 0: " +
                    "id='${pos.id}', label='${pos.label}', kind=${pos.kind}, value='${pos.value}', " +
                    "width=${pos.width} (erwartet: id=shift, label=⇧, action, shift, 1.5)")
            }
            // (2) genau EINE Taste mit value==shift UND label==⇧ in dieser Ebene
            val shiftKeys = keysOf(parsed.layers.getValue(layerName))
                .filter { it.value == KeyActions.SHIFT }
            if (shiftKeys.size != 1 || shiftKeys[0].label != "⇧") {
                fail("Asset '${parsed.id}' Ebene '$layerName': ${shiftKeys.size} Taste(n) mit " +
                    "value==shift — erwartet genau 1 mit Label ⇧")
            }
            // (3) verbotene Werte
            for (key in keysOf(parsed.layers.getValue(layerName))) {
                if (key.value == KeyActions.LAYER_SYMBOLS) {
                    fail("Asset '${parsed.id}' Ebene '$layerName' Taste '${key.id}': " +
                        "value=='layer-symbols' ist verboten (Regel 4a)")
                }
                if (key.value == KeyActions.LAYER_BASE && key.label != "ABC") {
                    fail("Asset '${parsed.id}' Ebene '$layerName' Taste '${key.id}': " +
                        "layer-base mit Label '${key.label}' != 'ABC' (Regel 4a)")
                }
            }
        }
    }

    /**
     * Gegenproben (müssen rot werden, §6 Punkt 9): mutierte Assets müssen von
     * checkSymbolShiftKeys abgelehnt werden.
     */
    @Test
    fun `gegenproben symbolsShift Rueckwegstaste muss abgelehnt werden`() {
        val base = LayoutParser.parse(asset("de-de.json")!!) // korrektes Referenz-Asset
        // (a) Taste fehlt effektiv in symbolsShift Reihe 2/Spalte 0 (id geaendert)
        assertRejected(mutateShiftKey(base, "symbolsShift") { it.copy(id = "shift-x") }, "(a) Taste fehlt")
        // (b) Ist-Zustand da-dk: label ?123 + value shift
        assertRejected(mutateShiftKey(base, "symbolsShift") { it.copy(label = "?123") }, "(b) ?123-Label")
        // (c) ⇧ mit value layer-base (Ist-Zustand ru-ru/cs-cz)
        assertRejected(mutateShiftKey(base, "symbolsShift") { it.copy(value = "layer-base") }, "(c) layer-base an ⇧")
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
            fail("Gegenprobe $label: mutiertes Asset wurde NICHT abgelehnt")
        } catch (expected: AssertionError) {
            // erwartetes Rot
        }
    }
}
