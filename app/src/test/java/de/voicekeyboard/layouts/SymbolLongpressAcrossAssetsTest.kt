package de.voicekeyboard.layouts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * T4 (v0.1.35, docs/PLAN_LONGPRESS_SYMBOLS.md §2/§4): Langdruck-Menüs auf den
 * Symbolebenen — sprachneutral IDENTISCH in allen 15 Assets. Gegenproben-
 * Charakter: der Test liest die JSON direkt über den Parser und fällt bei
 * Löschung, Umbau oder Erweiterung einer Menge.
 *
 *  - Ziffern (symbols r0, Label 0–9; IDs je Asset "1"/"s49"/"one"…) bleiben OHNE Menü;
 *  - exakte Mengen je id nach Plan §2 (symbols: –— \| [{< ]}> ‘’‚ „“”‘’‚ … ¿ ¡ ‰ ±;
 *    symbolsShift: $ → €£¥, NUR wo der Schlüssel existiert — 8 Assets haben kein $);
 *  - alles Übrige ohne Menü (Ausnahme Bestand: el-gr semicolon → [·], vor T4
 *    vorhanden — griechischer Sonderweg, bleibt unangetastet);
 *  - Hygiene je Alternate: ≤ 8, 1 Codepoint, NFC, ≠ eigenes Zeichen, keine
 *    Duplikate; KEIN hint auf Symbol-Tasten (Plan §1 — withHint würde ihn sonst
 *    als Zelle anhängen);
 *  - Parität: jede Plan-id trägt über alle 15 Assets dieselbe Menge.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SymbolLongpressAcrossAssetsTest {

    private val assetIds = listOf(
        "cs-cz", "da-dk", "de-de", "el-gr", "en-us", "es-es", "fi-fi", "fr-fr",
        "it-it", "nl-nl", "pl-pl", "pt-pt", "ru-ru", "sv-se", "tr-tr",
    )

    private fun asset(id: String) =
        LayoutParser.parse(java.io.File("src/main/assets/layouts/$id.json").readText())

    /** Plan §2, symbols-Hauptebene: id → exakte alternates-Menge (sprachneutral). */
    private val symbolsMenus = mapOf(
        "hyphen" to listOf("–", "—"),
        "slash" to listOf("\\", "|"),
        "paren-left" to listOf("[", "{", "<"),
        "paren-right" to listOf("]", "}", ">"),
        "apostrophe" to listOf("‘", "’", "‚"),
        "dquote" to listOf("„", "“", "”", "‘", "’", "‚"),
        "dot" to listOf("…"),
        "question" to listOf("¿"),
        "exclam" to listOf("¡"),
        "percent" to listOf("‰"),
        "plus" to listOf("±"),
    )

    @Test
    fun symbolLangdruckMenusGeltenSprachneutralInAllen15Assets() {
        assertEquals("Layout-Assets zahlen", 15, assetIds.size)
        // Mengen je Plan-id über alle Assets einsammeln (Parität am Ende).
        val perId = mutableMapOf<String, MutableSet<List<String>>>()
        for (id in assetIds) {
            val asset = asset(id)
            val symbols = asset.layers.getValue("symbols").rows.flatten()
            val shift = asset.layers.getValue("symbolsShift").rows.flatten()

            // (a) Ziffern bleiben OHNE Menü (Plan §2 [D]: Fraktions-Menüs bewusst raus).
            for (key in symbols) {
                if (key.label.length == 1 && key.label[0] in '0'..'9') {
                    assertTrue("$id/symbols Ziffer ${key.label} ohne Menü", key.alternates.isEmpty())
                }
            }

            // (b) exakte Mengen je id (symbols) — Plan §2 wörtlich.
            val byId = symbols.associateBy { it.id }
            for ((keyId, expected) in symbolsMenus) {
                assertEquals("$id/symbols/$keyId", expected, byId.getValue(keyId).alternates)
                perId.getOrPut(keyId) { mutableSetOf() }.add(byId.getValue(keyId).alternates)
            }

            // (c) symbolsShift: $ (id "dollar") → [€ £ ¥] — nur wo der Schlüssel
            //     existiert (8 Assets haben kein $: da-de/el-gr/fi-fi/… führen €
            //     als Direkttaste; alles übrige bleibt ohne Menü).
            val dollar = shift.firstOrNull { it.id == "dollar" }
            if (dollar != null) {
                assertEquals("$id/symbolsShift/dollar", listOf("€", "£", "¥"), dollar.alternates)
            }

            // (d) ALLES ÜBRIGE ohne Menü — Ausnahme Bestand el-gr/semicolon → [·]
            //     (vor T4 vorhanden, griechischer Sonderweg, unangetastet).
            for (key in symbols) {
                if (key.id !in symbolsMenus && !(id == "el-gr" && key.id == "semicolon")) {
                    assertTrue("$id/symbols/${key.id} darf kein Menü haben (${key.alternates})",
                        key.alternates.isEmpty())
                }
            }
            for (key in shift) {
                if (key.id != "dollar") {
                    assertTrue("$id/symbolsShift/${key.id} darf kein Menü haben (${key.alternates})",
                        key.alternates.isEmpty())
                }
            }

            // (e) Hygiene je Alternate (Plan §1): ≤ 8, 1 Codepoint, NFC, ≠ eigenes
            //     Zeichen, keine Duplikate; KEIN hint auf Symbol-Tasten.
            for (layerKeys in listOf(symbols, shift)) {
                for (key in layerKeys) {
                    assertTrue("$id ${key.id} ≤ 8 alternates", key.alternates.size <= 8)
                    assertNull("$id ${key.id} kein hint auf Symbol-Taste (Plan §1)", key.hint)
                    assertEquals("$id ${key.id} keine Duplikate",
                        key.alternates.size, key.alternates.toSet().size)
                    for (alt in key.alternates) {
                        assertEquals("$id ${key.id} alternate '$alt' ist 1 Codepoint",
                            1, alt.codePointCount(0, alt.length))
                        assertTrue("$id ${key.id} alternate '$alt' NFC",
                            java.text.Normalizer.isNormalized(alt, java.text.Normalizer.Form.NFC))
                        assertTrue("$id ${key.id} alternate '$alt' ≠ eigenes Zeichen",
                            alt != key.value && alt != key.label)
                    }
                }
            }
        }

        // (f) Parität: jede Plan-id hat über alle 15 Assets DIESELBE Menge.
        for ((keyId, sets) in perId) {
            assertEquals("Parität $keyId über 15 Assets", 1, sets.size)
        }
    }
}
