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
        // F23b-T (Nutzerentscheidung L1-Paket): de-de hat KEINE eigenen ä/ö/ü/ß-Tasten
        // mehr — Stock-Vorbild: rowkeys_qwertz1.xml keySpec q..p (KEIN ü, dazu
        // keyHintLabel 1..0) und rowkeys_qwertz3.xml keySpec y x c v b n m (KEIN ß).
        // Die Umlaute sind als ERSTE Alternative erreichbar (eigener Test unten);
        // shiftPairs prüft ẞ gegen die Shift-Ebene (eigener Wert ODER alternate,
        // checkShiftPairs) — nach F23b über die S-Alternates, nicht mehr ueber eszett.
        MatrixRow("de-de", emptyList(), shiftPairs = listOf("ß" to "ẞ")),
        // §2.2: en-us — keine A/B-Buchstaben (ASCII); Satzzeichen deckt PunctuationCoverageTest ab
        MatrixRow("en-us", emptyList(), requiredReachable = listOf("'", "\"", "–", "—", "…")),
        // §2.2 fr-fr + F27c/D26: die Akzente sind KEINE eigenen Tasten mehr, sondern
        // Alternates ihrer Grundtasten (Stock-Geometrie) — daher nur erreichbar (B).
        MatrixRow("fr-fr", emptyList(), requiredReachable = listOf("é", "è", "à", "ç", "ù", "ê", "ë", "ï", "î", "ô", "û", "ü", "œ", "æ", "ÿ"), shiftPairs = listOf("œ" to "Œ", "æ" to "Æ", "ç" to "Ç")),
        // §2.2 es-es + F27c/D26: ñ ist Alternate von n (keine eigene Taste mehr).
        MatrixRow("es-es", emptyList(), requiredReachable = listOf("ñ", "á", "é", "í", "ó", "ú", "ü", "¿", "¡"), shiftPairs = listOf("ñ" to "Ñ")),
        // §2.2 it-it + F27c/D26: à è é ì ò ù sind Alternates (Rev 3: ü B-pflicht — Audit B1)
        MatrixRow("it-it", emptyList(), requiredReachable = listOf("à", "è", "é", "ì", "ò", "ù", "á", "í", "ó", "ú", "î", "ü")),
        // F24d (R8-Beschluss wie de-de): pt-pt hat KEINE eigenen Akzent-Tasten mehr —
        // AOSP qwerty-Satz traegt a/e/i/o/u/c die Akzente als moreKeys
        // (docs/AOSP_ROWKEYS_BELEGE.md: "keine / 12 Akzent-Tasten + 5. Reihe (uns) -> Rebuild").
        // Erreichbarkeit als ERSTE Alternative prueft der eigene Fall unten;
        // shiftPairs prueft Ç gegen die shift-Ebene (eigener Wert ODER alternate).
        MatrixRow("pt-pt", emptyList(), requiredReachable = listOf("ü"), shiftPairs = listOf("ç" to "Ç")),
        // §2.2: nl-nl A = —; B = á à ä ë é è ê ï ö ü (Rev 3: ä B-pflicht — Audit B1)
        MatrixRow("nl-nl", emptyList(), requiredReachable = listOf("á", "à", "ä", "ë", "é", "è", "ê", "ï", "ö", "ü")),
        // §2.2 pl-pl + F27c/D26: ą ć ę ł ń ó ś ź ż sind Alternates (Großformen via shiftPairs)
        MatrixRow("pl-pl", emptyList(), requiredReachable = listOf("ą", "ć", "ę", "ł", "ń", "ó", "ś", "ź", "ż"), shiftPairs = listOf("ą" to "Ą", "ć" to "Ć", "ę" to "Ę", "ł" to "Ł", "ń" to "Ń", "ó" to "Ó", "ś" to "Ś", "ź" to "Ź", "ż" to "Ż")),
        // §2.2 tr-tr + F27c/D26: ç ğ ı ö ş ü sind Alternates (i bleibt eigene Taste, ı→i);
        // shift i→İ, ı→I (§3)
        MatrixRow("tr-tr", emptyList(), requiredReachable = listOf("ğ", "ü", "ş", "ı", "ö", "ç", "â", "î", "û"), shiftPairs = listOf("ğ" to "Ğ", "ü" to "Ü", "ş" to "Ş", "ı" to "I", "ö" to "Ö", "ç" to "Ç", "i" to "İ")),
        // §2.2 ru-ru + F27c/D27: а–я vollständig als eigene Tasten; ё ist Alternate von е
        MatrixRow("ru-ru", listOf("а", "б", "в", "г", "д", "е", "ж", "з", "и", "й", "к", "л", "м", "н", "о", "п", "р", "с", "т", "у", "ф", "х", "ц", "ч", "ш", "щ", "ъ", "ы", "ь", "э", "ю", "я"), requiredReachable = listOf("ё")),
        // §2.2 cs-cz + F27c/D26: alle Akzente sind Alternates; B = ď ť ň ó (HW nicht direkt belegt)
        MatrixRow("cs-cz", emptyList(), requiredReachable = listOf("ě", "š", "č", "ř", "ž", "ý", "á", "í", "é", "ú", "ů", "ď", "ť", "ň", "ó")),
        // §2.2 sv-se + F27c/D26: å ä ö sind Alternates; B = á à é è ü
        MatrixRow("sv-se", emptyList(), requiredReachable = listOf("å", "ä", "ö", "á", "à", "é", "è", "ü")),
        // §2.2 da-dk + F27c/D26: æ ø å sind Alternates; B = ä é ö ü
        MatrixRow("da-dk", emptyList(), requiredReachable = listOf("æ", "ø", "å", "ä", "ö", "é", "ü")),
        // §2.2 fi-fi + F27c/D26: ä ö å sind Alternates (Rev 1-Korrektur: å Pflicht); B = š ž é
        MatrixRow("fi-fi", emptyList(), requiredReachable = listOf("ä", "ö", "å", "š", "ž", "é")),
        // §2.2: el-gr A = α–ω vollständig + ς (explizite Liste, U+03C2); B = Tonos/Dialytika
        // F24d-T2: shiftPairs ";"→":" — AOSP rowkeys_greek1.xml zeigt base ';' und shift ':'
        // (docs/AOSP_ROWKEYS_BELEGE.md, el-gr); CaseMapper.upper(";") ist ";", daher
        // Matrix-Sonderfall — exakt derselbe Mechanismus wie ß→ẞ (Zeile 57).
        MatrixRow("el-gr", listOf("α", "β", "γ", "δ", "ε", "ζ", "η", "θ", "ι", "κ", "λ", "μ", "ν", "ξ", "ο", "π", "ρ", "ς", "σ", "τ", "υ", "φ", "χ", "ψ", "ω"), requiredReachable = listOf("Σ", "ΐ", "ΰ", "ϊ", "ϋ", "ά", "έ", "ή", "ί", "ό", "ύ", "ώ"), shiftPairs = listOf(";" to ":")),
    )

    /**
     * F23b-T: de-de — ä/ö/ü/ß sind nicht mehr eigene Tasten (AOSP rowkeys_qwertz1.xml/
     * rowkeys_qwertz3.xml, s. MatrixRow-Kommentar), sondern ERSTE Alternative ihrer
     * Basisbuchstaben; ẞ bleibt über die S-Alternates der shift-Ebene erreichbar.
     * Die Assets wandert F23b — dieser Fall ist bis dahin EXPECTED ROT.
     */
    @Test
    fun `de-de - Umlaute und szett sind erste Alternative`() {
        val json = asset("de-de.json") ?: throw AssertionError("de-de.json nicht lesbar")
        val parsed = LayoutParser.parse(json)
        fun firstAlt(layer: String, id: String): String? =
            keysOf(parsed.layers.getValue(layer)).firstOrNull { it.id == id }?.alternates?.firstOrNull()
        org.junit.Assert.assertEquals("base a: alternates[0] muss ä sein", "ä", firstAlt("base", "a"))
        org.junit.Assert.assertEquals("base o: alternates[0] muss ö sein", "ö", firstAlt("base", "o"))
        org.junit.Assert.assertEquals("base u: alternates[0] muss ü sein", "ü", firstAlt("base", "u"))
        org.junit.Assert.assertEquals("base s: alternates[0] muss ß sein", "ß", firstAlt("base", "s"))
        val sShift = keysOf(parsed.layers.getValue("shift")).firstOrNull { it.id == "s" }
        assertTrue("shift S muss ẞ in alternates fuehren (sonst ist ẞ verloren)",
            sShift != null && sShift.alternates.contains("ẞ"))
    }

    /**
     * F54 (Nutzer-Auftrag 02:4x): Punkt-Langdruckmenü über ALLE 15 Layouts —
     * ‹/› und ihre cursor-Zellen sind RAUS; einheitliche Zellreihenfolge
     * `,` `!` `?` `:` `;` `(` `)`; sprachliche Präfixe bleiben VORN (ru-ru:
     * @); hoechstens 8 Menüzellen (MAX_CELLS); keine cursor-left/right-
     * Aktionen mehr im period-Key. (Die FUSSZEILEN-Cursor-TASTEN sind ein
     * anderes Feature und bleiben unberührt.)
     */
    @Test
    fun `F54 - period-Alternates aller 15 Layouts - Zielzustand ohne cursor-Aktionen`() {
        val standard = listOf(",", "!", "?", ":", ";", "(", ")")
        val layouts = listOf(
            "cs-cz", "da-dk", "de-de", "el-gr", "en-us", "es-es", "fi-fi",
            "fr-fr", "it-it", "nl-nl", "pl-pl", "pt-pt", "ru-ru", "sv-se", "tr-tr",
        )
        for (name in layouts) {
            val raw = asset("$name.json") ?: throw AssertionError("$name.json nicht lesbar")
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
                            "$name/$layerName: period-Alternates == Zielzustand (Sprachpräfix + , ! ? : ; ( )",
                            expected, list,
                        )
                        assertTrue(
                            "$name/$layerName: hoechstens ${de.voicekeyboard.keyboard.LongPressMenu.MAX_CELLS} Zellen",
                            list.size <= de.voicekeyboard.keyboard.LongPressMenu.MAX_CELLS,
                        )
                        assertTrue("$name: keine ‹ mehr", "‹" !in list)
                        assertTrue("$name: keine › mehr", "›" !in list)
                        if (key.has("cellActions")) {
                            val acts = key.getJSONArray("cellActions").join(",")
                            assertTrue("$name/$layerName: keine cursor-Aktionen im period-Key",
                                "cursor-left" !in acts && "cursor-right" !in acts)
                        }
                    }
                }
                assertTrue("$name/$layerName: period-Key muss existieren", seen == 1)
            }
        }
    }

    private fun asset(name: String): String? = try {
        val context = RuntimeEnvironment.getApplication() as Application
        context.assets.open("layouts/$name").bufferedReader().use { it.readText() }
    } catch (e: Exception) {
        null // Asset existiert (noch) nicht → Matrix-Zeile überspringen
    }

    // ---------- Gegenprobe (Negativtest mit Inline-Mutation, KEINE Asset-Änderung) ----------

    /** A-3-Gegenprobe: Rückbau des pt-pt-Fixes (à wieder KEINE eigene Taste) muss ROT machen. */
    /**
     * F24d (AOSP qwerty-Satz, R8 wie de-de): die portugiesischen Akzente sind keine
     * eigenen Tasten mehr, sondern ERSTE Alternative ihrer Grundtaste — base a->á, c->ç,
     * e->é, i->í, o->õ, u->ú; shift dieselben Großformen. Ü bleibt als Stufe-B-Zeichen
     * alternate des U (IST-Uebernahme aus der ehemaligen u-acute-Taste).
     */
    private fun firstAltOf(parsed: LayoutAsset, layer: String, id: String): String? =
        keysOf(parsed.layers.getValue(layer)).firstOrNull { it.id == id }?.alternates?.firstOrNull()

    private fun checkPtAccentsAsFirstAlternate(parsed: LayoutAsset) {
        for ((id, acc) in listOf("a" to "á", "c" to "ç", "e" to "é", "i" to "í", "o" to "õ", "u" to "ú")) {
            org.junit.Assert.assertEquals("Asset 'pt-pt' base '$id': alternates[0] muss '$acc' sein", acc, firstAltOf(parsed, "base", id))
        }
        for ((id, acc) in listOf("a" to "Á", "c" to "Ç", "e" to "É", "i" to "Í", "o" to "Õ", "u" to "Ú")) {
            org.junit.Assert.assertEquals("Asset 'pt-pt' shift '$id': alternates[0] muss '$acc' sein", acc, firstAltOf(parsed, "shift", id))
        }
    }

    @Test
    fun `pt-pt - Akzente sind erste Alternative statt eigener Taste`() {
        val json = asset("pt-pt.json") ?: throw AssertionError("pt-pt.json nicht lesbar")
        checkPtAccentsAsFirstAlternate(LayoutParser.parse(json))
    }

    /**
     * F24d-Umdeutung des ehemaligen a-grave-Gegenprobenfalls: die FRUEHERE Regel
     * ("à als fehlende EIGENE base-Taste muss rot werden") existiert nicht mehr —
     * der Fall prueft jetzt die NEUE Regel: verliert base-a seine Akzentkette
     * (alternates geloescht), muss der Akzent-Alternativen-Fall rot werden.
     */
    @Test
    fun `negativ - pt-pt ohne Akzentkette an a faellt der neue Fall rot`() {
        val json = asset("pt-pt.json") ?: throw AssertionError("pt-pt.json nicht lesbar")
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
        assertTrue("Gegenprobe: base-Taste 'a' nicht gefunden", removed)
        val parsed = LayoutParser.parse(root.toString())
        var caught = false
        try {
            checkPtAccentsAsFirstAlternate(parsed)
        } catch (e: AssertionError) {
            caught = true
            assertTrue("Meldung muss á nennen: ${e.message}", e.message?.contains("á") == true)
            assertTrue("Meldung muss Asset nennen: ${e.message}", e.message?.contains("pt-pt") == true)
        }
        assertTrue("Verlust der Akzentkette an 'a' wurde NICHT erkannt (Tautologie-Verdacht)", caught)
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
            checkGeometry(parsed)
            checked++
        }
        assertTrue(
            "Kein Asset geprüft — Assets fehlen oder sind unlesbar (übersprungen: $skipped)",
            checked > 0,
        )
        println("LayoutCompletenessTest: $checked Assets geprüft, übersprungen: $skipped")
    }

    /**
     * F27d→F36/V2 Stock-Geometrie als eigenständiges Gate: alle 15 Assets tragen
     * base+shift das V2-Zielbild — Summen 10/10/10/13 (ru-ru r2: 12, D27), kein
     * ⌫ in r0, F40: ⌫ genau 1x am r1-ENDE, @ genau 1x in r2 vor ⏎ (ru-ru:
     * Ausnahme, kein Tausch), Buchstabenblock-Mitte 5u (ru-ru 6u).
     */
    @Test
    fun `stockGeometrie gilt für alle 15 Assets`() {
        val ids = listOf(
            "cs-cz", "da-dk", "de-de", "el-gr", "en-us", "es-es", "fi-fi", "fr-fr",
            "it-it", "nl-nl", "pl-pl", "pt-pt", "ru-ru", "sv-se", "tr-tr",
        )
        for (id in ids) {
            val json = asset("$id.json")
            assertTrue("Asset $id fehlt oder ist unlesbar", json != null)
            checkGeometry(LayoutParser.parse(json!!))
        }
    }

    /**
     * F29-ABSCHLUSS (ru-ru-Sonderfall, base UND shift): 11 Buchstaben in r1 lassen
     * eine @-Taste nicht zu (0.5 + 11 + 1 = 12.5u > 12; r2 ist D27-gesperrt und voll).
     * Statt eigener @-Taste: @ als alternates[0] an der Punkt-Taste der Fussreihe;
     * die Cursor-Zellen bleiben auf '‹'/'›' (cellActions auf Index 4/5).
     * D27-Entscheidung 2026-11-01 (F33-Nachbesprechung): ru-ru r2 bleibt ⇧1+10+⏎1
     * (Vollreihe, Mitte = View-Mitte 540 px; Sol-F33-Formel B=min(N,7) greift
     * hier nicht) — dieses Test und die ru-ru-Ausnahmen in checkGeometry sind
     * der verankerte Soll-Zustand.
     */
    @Test
    fun `ru-ru - kein at-Objekt in r1, @ ist alternates-Null am period`() {
        val parsed = LayoutParser.parse(asset("ru-ru.json")!!) // ru-ru-Referenz-Asset
        for (layerName in listOf("base", "shift")) {
            val rows = parsed.layers.getValue(layerName).rows
            assertTrue(
                "$layerName: kein 'at'-Objekt in r1",
                rows[1].none { it.id == "at" && it.value == "@" },
            )
            val period = rows[3].first { it.id == "period" }
            assertEquals("$layerName: @ ist alternates[0] am period", "@", period.alternates.first())
            assertEquals("$layerName: F54-Standardzellen hinter @", listOf(",", "!", "?", ":", ";", "(", ")"), period.alternates.drop(1))
            assertEquals("$layerName: F54 — keine cursor-Aktionen mehr", 0, period.cellActions.size)
        }
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
                // ru-ru D27: die Buchstaben-Ebenen (base/shift) haben KEIN backspace
                // (r2 = shift1+10+enter1 ist voll; r0 = 11+Margin hat keinen Platz).
                val expected =
                    if (isRu && (layerName == "base" || layerName == "shift") && action == KeyActions.BACKSPACE) 0
                    else 1
                val count = actions.count { it == action }
                if (count != expected) {
                    fail("Asset '${parsed.id}' Ebene '$layerName': Aktion '$action' " +
                        "$count-mal vorhanden (erwartet: genau $expected)")
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
     * F27d/F29→F36/V2 + F40 (docs/DESIGN_TASTATUR_V2.md, 1u = 108 px; Nutzer-
     * Feedback v0.1.27): base/shift tragen das V2-Raster — r0 = 10 Buchstaben à
     * 1,0 OHNE ⌫; r1 = Rand 0,5 + Buchstabenblock 8,5u + ⌫ 1,0 (F40: am r1-ENDE);
     * r2 = T8 (v0.1.38, Planer-Freigabe) Y-Reihen-Ausrichtung: KEIN @ mehr
     * (erreichbar über M-Langdruck-Menü), 13 7er-Assets ⇧ 0,89352 + 7 à 1,10185
     * + ⏎ 1,39352 (Y-Mitte 13/9 = 1,44444 unter A/S, M-Mitte 145/18 = 8,05556
     * unter K/L; Blockmitte wandert 540→513 px — dokumentierte Abweichung
     * DESIGN_TASTATUR_V2 §10: Nutzer-Zentrierung schlägt Uniformität; r2 ist
     * jetzt die breiteste Buchstabenreihe, 119-px-Zellen); cs-cz ⇧ 1,889 +
     * 6 à 1,03704 + ⏎ 1,88889 (6er, keine M-Mittigkeit — dokumentierte Ausnahme);
     * Footer r3 UNVERÄNDERT Σ13 (eigene Einheit u_footer). Rundung: 5 Dezimalen,
     * Summen-Toleranz 0,001u (≤ 0,11 px). Sonderfälle: cs-cz r1 = 10 Buchstaben
     * à 0,85 + ⌫ 1,0 (derselbe 8,5u-Block); ru-ru r0/r1 = Rand 0,11111 (12 px)
     * je Seite + 11 à 0,88889 (96 px, Spec §6), r2 bleibt D27-Vollreihe ⇧1 +
     * 10 à 1,0 + ⏎1 = Σ12 (Spec §6 „unverändert“; KEIN Tausch — kein @ in r1,
     * ⌫ nur auf den Symbolebenen, @ am period). fr-fr ist strukturell Standard
     * (r1 hat IST 9 Buchstaben + ⌫, m liegt in r2 — Spec §6 „10 Buchstaben“ passt
     * nicht aufs Asset, keine Taste wandert).
     */
    private fun checkGeometry(parsed: LayoutAsset) {
        val isRu = parsed.id == "ru-ru"
        val isCs = parsed.id == "cs-cz"
        for (layerName in listOf("base", "shift")) {
            val rows = parsed.layers.getValue(layerName).rows
            if (rows.size < 4) {
                fail("Asset '${parsed.id}' $layerName: weniger als 4 Reihen (V2-Geometrie)")
            }
            val expectSums = if (isRu) listOf(10.0, 10.0, 12.0, 13.0) else listOf(10.0, 10.0, 10.0, 13.0)
            val sums = rows.take(4).map { row -> row.sumOf { it.width } }
            for (i in expectSums.indices) {
                if (kotlin.math.abs(sums[i] - expectSums[i]) > 0.001) {
                    fail("Asset '${parsed.id}' $layerName: Zeilensumme r$i=" +
                        "${"%.5f".format(sums[i])}u != ${expectSums[i]}u (F36/V2)")
                }
            }
            // ⌫ verlässt r0 (alle Assets); F40: ⌫ sitzt genau 1x am r1-ENDE (ru-ru:
            // keiner in den Buchstaben-Ebenen, D27) und NICHT mehr in r2.
            val bsR0 = rows[0].count { it.kind == KeyKind.ACTION && it.value == KeyActions.BACKSPACE }
            if (bsR0 != 0) fail("Asset '${parsed.id}' $layerName: r0 darf KEIN backspace tragen (F36/V2)")
            val bsR2 = rows[2].count { it.kind == KeyKind.ACTION && it.value == KeyActions.BACKSPACE }
            if (bsR2 != 0) fail("Asset '${parsed.id}' $layerName: r2 darf KEIN backspace tragen (F40 — ⌫ sitzt am r1-Ende)")
            val enterR2 = rows[2].count { it.kind == KeyKind.ACTION && it.value == KeyActions.ENTER }
            val enterR3 = rows[3].count { it.kind == KeyKind.ACTION && it.value == KeyActions.ENTER }
            if (enterR2 != 1) fail("Asset '${parsed.id}' $layerName: enter $enterR2-mal in r2 (erwartet 1)")
            if (enterR3 != 0) fail("Asset '${parsed.id}' $layerName: enter in r3 verboten (V2-Geometrie)")
            // r1: Rand 0,5 + Buchstabenblock + @ 1,0; ru-ru: Vollreihe mit 12-px-Seitenrändern, kein @.
            val r1 = rows[1]
            if (isRu) {
                if (r1[0].kind != KeyKind.MARGIN || r1.last().kind != KeyKind.MARGIN ||
                    kotlin.math.abs(r1[0].width - 0.11111) > 0.001 ||
                    kotlin.math.abs(r1.last().width - 0.11111) > 0.001
                ) fail("Asset 'ru-ru' $layerName: r1 braucht 0,11111u-Seitenränder (12 px, Spec §6)")
                if (r1.any { it.kind == KeyKind.TEXT && it.value == "@" }) {
                    fail("Asset 'ru-ru' $layerName: r1 darf kein @-Key tragen (D27, @ am period)")
                }
                val ruLetters1 = r1.filter { it.kind == KeyKind.TEXT }
                if (ruLetters1.size != 11) {
                    fail("Asset 'ru-ru' $layerName: r1 braucht 11 Buchstaben (sind ${ruLetters1.size})")
                }
                for (k in ruLetters1) {
                    if (kotlin.math.abs(k.width - 0.88889) > 0.001) {
                        fail("Asset 'ru-ru' $layerName r1 '${k.id}': width ${k.width} != 0,88889 (96 px, Spec §6)")
                    }
                }
            } else {
                if (r1.isEmpty() || r1[0].kind != KeyKind.MARGIN ||
                    kotlin.math.abs(r1[0].width - 0.5) > 0.0001
                ) {
                    fail("Asset '${parsed.id}' $layerName: r1[0] muss Margin 0,5 sein (F36/V2)")
                }
                val atInR1 = r1.count { it.kind == KeyKind.TEXT && it.value == "@" }
                if (atInR1 != 0) {
                    fail("Asset '${parsed.id}' $layerName: r1 darf KEIN @ tragen (T8: @ ist keine Direkttaste mehr, erreichbar über M-Menü)")
                }
                val bsR1 = r1.filter { it.kind == KeyKind.ACTION && it.value == KeyActions.BACKSPACE }
                if (bsR1.size != 1 || r1.last() !== bsR1[0] ||
                    kotlin.math.abs(bsR1[0].width - 1.0) > 0.0001
                ) {
                    fail("Asset '${parsed.id}' $layerName: ⌫ genau 1x am r1-ENDE (width 1,0) erwartet (F40)")
                }
                val letters = r1.filter { it.kind == KeyKind.TEXT && it.value != "@" }
                val wantN = if (isCs) 10 else 9
                val wantW = if (isCs) 0.85 else 0.94444 // 10 bzw. 9 Buchstaben à 8,5u-Block
                if (letters.size != wantN) {
                    fail("Asset '${parsed.id}' $layerName: r1 braucht $wantN Buchstaben (sind ${letters.size})")
                }
                for (k in letters) {
                    if (kotlin.math.abs(k.width - wantW) > 0.001) {
                        fail("Asset '${parsed.id}' $layerName r1 '${k.id}': width ${k.width} != $wantW (F36/V2)")
                    }
                }
                if (r1.last().kind == KeyKind.MARGIN) {
                    fail("Asset '${parsed.id}' $layerName: r1 ohne rechten Rand (Σ10, F36/V2)")
                }
            }
            // r2 T8 (v0.1.38, Planer-Freigabe): @-Taste RAUS (Nutzer-Auftrag — @
            // erreichbar über M-Langdruck-Menü), ⇧ schmaler, ⏎ breiter — Y mittig
            // unter A/S, M mittig unter K/L (Begründung der Umschreibung: die
            // alten Invarianten ⇧1,889/0,889/@-Pflicht/⏎1,0/Blockmitte 5u sind
            // mit dem Nutzer-Auftrag unvereinbar; Zentren-Invarianten wandern
            // hierher UND nach LetterRowAlignmentAcrossAssetsTest).
            //  - 13 7er-Assets: ⇧ 0,89352 + 7 à 1,10185 + ⏎ 1,39352;
            //    Y-Mitte 13/9 = 1,44444, M-Mitte 145/18 = 8,05556 (KEY_GAP=0 ⇒ px-exakt:
            //    96,5 + 7×119 + 150,5 = 1080 px).
            //  - cs-cz (6er, kein y): ⇧ 1,88889 + 6 à 1,03704 + ⏎ 1,88889 (⇧=⏎
            //    symmetrisch); M-Mittigkeit im 6er-Raster arithmetisch unerreichbar
            //    ⇒ dokumentierte Ausnahme (nur Struktur, keine Zentren).
            //  - ru-ru: D27-Vollreihe bleibt (kein @ auf Buchstaben-Ebenen).
            val r2 = rows[2]
            val r2Letters = r2.filter { it.kind == KeyKind.TEXT }
            val wantShift = when { isRu -> 1.0; isCs -> 1.88889; else -> 0.89352 }
            if (r2.isEmpty() || r2[0].kind != KeyKind.ACTION || r2[0].value != KeyActions.SHIFT ||
                kotlin.math.abs(r2[0].width - wantShift) > 0.001
            ) {
                fail("Asset '${parsed.id}' $layerName: r2[0] muss shift width $wantShift sein (T8, ist " +
                    "${r2.firstOrNull()?.let { it.value + "/" + it.width }})")
            }
            val wantL = when {
                isRu -> 1.0     // D27: 10 à 1,0 in der Σ12-Reihe
                isCs -> 1.03704 // 112 px
                else -> 1.10185 // 119 px — r2 ist jetzt die breiteste Buchstabenreihe (T8)
            }
            val wantNL = if (isRu) 10 else if (isCs) 6 else 7
            if (r2Letters.size != wantNL) {
                fail("Asset '${parsed.id}' $layerName: r2 braucht $wantNL Buchstaben (sind ${r2Letters.size})")
            }
            for (k in r2Letters) {
                if (kotlin.math.abs(k.width - wantL) > 0.001) {
                    fail("Asset '${parsed.id}' $layerName r2 '${k.id}': width ${k.width} != $wantL (T8)")
                }
            }
            val enter = r2.firstOrNull { it.kind == KeyKind.ACTION && it.value == KeyActions.ENTER }
            val wantEnter = when { isRu -> 1.0; isCs -> 1.88889; else -> 1.39352 }
            if (enter == null || kotlin.math.abs(enter.width - wantEnter) > 0.001) {
                fail("Asset '${parsed.id}' $layerName: ⏎ width $wantEnter in r2 erwartet (T8)")
            }
            if (!isRu) {
                // T8: KEINE @-Direkttaste mehr in r2 — @ läuft über das M-Menü.
                if (r2.any { it.kind == KeyKind.TEXT && it.value == "@" }) {
                    fail("Asset '${parsed.id}' $layerName: @-Direkttaste in r2 verboten (T8: @ → M-Menü)")
                }
                val m = r2Letters.lastOrNull()
                if (m == null || m.alternates.none { it == "@" }) {
                    fail("Asset '${parsed.id}' $layerName: M-Taste muss @ im Langdruck-Menü tragen (T8)")
                }
            }
            if (r2.last().kind == KeyKind.MARGIN) {
                fail("Asset '${parsed.id}' $layerName: r2 ohne rechten Rand (F36/V2)")
            }
            // T8-Zentren (nur 7er; cs: 6er-Ausnahme, ru: D27): Y mittig unter
            // A/S ⇒ c(r2[0]) = 13/9; M mittig unter K/L ⇒ c(r2[6]) = 145/18
            // (Herleitung: r1-Mitte c1(i) = 0,5 + (i+0,5)·0,94444). Ersetzt die
            // F36-Blockmitte 5u — die wandert auf 4,75u (540→513 px, dokumentierte
            // Abweichung DESIGN_TASTATUR_V2 §10).
            if (!isRu && !isCs) {
                val r1Letters = rows[1].filter { it.kind == KeyKind.TEXT }
                if (r1Letters.size != 9) {
                    fail("Asset '${parsed.id}' $layerName: r1 braucht 9 Buchstaben (sind ${r1Letters.size})")
                }
                var x1 = 0.5
                val c1 = r1Letters.map { x1 += it.width; x1 - it.width / 2 }
                var x2 = r2[0].width
                val c2 = r2Letters.map { x2 += it.width; x2 - it.width / 2 }
                if (kotlin.math.abs(c2[0] - 13.0 / 9) > 0.01 ||
                    kotlin.math.abs(c2[6] - 145.0 / 18) > 0.01
                ) {
                    fail("Asset '${parsed.id}' $layerName: T8-Zentren verletzt — " +
                        "Y ${"%.4f".format(c2[0])} != 13/9, M ${"%.4f".format(c2[6])} != 145/18")
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
