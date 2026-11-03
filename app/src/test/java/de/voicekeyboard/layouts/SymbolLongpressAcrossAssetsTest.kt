package de.voicekeyboard.layouts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * T4 (v0.1.35, docs/PLAN_LONGPRESS_SYMBOLS.md §2/§4): long-press menus on the
 * symbol layers — language-neutral IDENTICAL in all 15 assets. Counter-check
 * character: the test reads the JSON directly via the parser and fails on
 * deletion, restructuring or extension of a set.
 *
 *  - digits (symbols r0, label 0–9; IDs per asset "1"/"s49"/"one"…) stay WITHOUT a menu;
 *  - exact sets per id per plan §2 (symbols: –— \| [{< ]}> ‘’‚ „“”‘’‚ … ¿ ¡ ‰ ±;
 *    symbolsShift: $ → €£¥, ONLY where the key exists — 8 assets have no $);
 *  - everything else without a menu (inventory exception: el-gr semicolon → [·],
 *    present before T4 — Greek special path, stays untouched);
 *  - hygiene per alternate: ≤ 8, 1 codepoint, NFC, ≠ own character, no
 *    duplicates; NO hint on symbol keys (plan §1 — withHint would otherwise
 *    append it as a cell);
 *  - parity: every plan id carries the same set across all 15 assets.
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

    /** Plan §2, symbols main layer: id → exact alternates set (language-neutral). */
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
        assertEquals("layout assets count", 15, assetIds.size)
        // collect the sets per plan id across all assets (parity at the end).
        val perId = mutableMapOf<String, MutableSet<List<String>>>()
        for (id in assetIds) {
            val asset = asset(id)
            val symbols = asset.layers.getValue("symbols").rows.flatten()
            val shift = asset.layers.getValue("symbolsShift").rows.flatten()

            // (a) digits stay WITHOUT a menu (plan §2 [D]: fraction menus deliberately out).
            for (key in symbols) {
                if (key.label.length == 1 && key.label[0] in '0'..'9') {
                    assertTrue("$id/symbols digit ${key.label} without a menu", key.alternates.isEmpty())
                }
            }

            // (b) exact sets per id (symbols) — plan §2 verbatim.
            val byId = symbols.associateBy { it.id }
            for ((keyId, expected) in symbolsMenus) {
                assertEquals("$id/symbols/$keyId", expected, byId.getValue(keyId).alternates)
                perId.getOrPut(keyId) { mutableSetOf() }.add(byId.getValue(keyId).alternates)
            }

            // (c) symbolsShift: $ (id "dollar") → [€ £ ¥] — only where the key
            //     exists (8 assets have no $: da-de/el-gr/fi-fi/… carry €
            //     as a direct key; everything else stays without a menu).
            val dollar = shift.firstOrNull { it.id == "dollar" }
            if (dollar != null) {
                assertEquals("$id/symbolsShift/dollar", listOf("€", "£", "¥"), dollar.alternates)
            }

            // (d) EVERYTHING ELSE without a menu — inventory exception el-gr/semicolon → [·]
            //     (present before T4, Greek special path, untouched).
            for (key in symbols) {
                if (key.id !in symbolsMenus && !(id == "el-gr" && key.id == "semicolon")) {
                    assertTrue("$id/symbols/${key.id} must not have a menu (${key.alternates})",
                        key.alternates.isEmpty())
                }
            }
            for (key in shift) {
                if (key.id != "dollar") {
                    assertTrue("$id/symbolsShift/${key.id} must not have a menu (${key.alternates})",
                        key.alternates.isEmpty())
                }
            }

            // (e) hygiene per alternate (plan §1): ≤ 8, 1 codepoint, NFC, ≠ own
            //     character, no duplicates; NO hint on symbol keys.
            for (layerKeys in listOf(symbols, shift)) {
                for (key in layerKeys) {
                    assertTrue("$id ${key.id} ≤ 8 alternates", key.alternates.size <= 8)
                    assertNull("$id ${key.id} no hint on symbol key (plan §1)", key.hint)
                    assertEquals("$id ${key.id} no duplicates",
                        key.alternates.size, key.alternates.toSet().size)
                    for (alt in key.alternates) {
                        assertEquals("$id ${key.id} alternate '$alt' is 1 codepoint",
                            1, alt.codePointCount(0, alt.length))
                        assertTrue("$id ${key.id} alternate '$alt' NFC",
                            java.text.Normalizer.isNormalized(alt, java.text.Normalizer.Form.NFC))
                        assertTrue("$id ${key.id} alternate '$alt' ≠ own character",
                            alt != key.value && alt != key.label)
                    }
                }
            }
        }

        // (f) parity: every plan id has THE SAME set across all 15 assets.
        for ((keyId, sets) in perId) {
            assertEquals("parity $keyId across 15 assets", 1, sets.size)
        }
    }
}
