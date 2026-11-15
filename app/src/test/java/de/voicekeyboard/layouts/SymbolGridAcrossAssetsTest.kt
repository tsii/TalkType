package de.voicekeyboard.layouts

import de.voicekeyboard.layouts.KeyActions
import de.voicekeyboard.layouts.KeyKind
import de.voicekeyboard.layouts.LayoutParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * T6 (v0.1.37, docs/PLAN_SYMBOL_RASTER.md §Tests 2) + V0.1.49
 * (docs/PLAN_GEOMETRY_V0149.md §2) + V0.1.51 (docs/PLAN_V0151.md §A):
 * symbol grid with stable ⌫/⏎ slots — across ALL 15 assets:
 *
 *  - Σ per row [10, 11.5, 10, 13] ± 0.001 (unit grid; 1u = 93.91 px at Σ 11.5);
 *  - r1 = margin 0.5 (half-indent like the letter home row) +
 *    10 characters à 1.0; ⌫ EXACTLY 1× at the r1 END, width 1.0
 *    (V0.1.51: one FULL character — == character width);
 *  - r2 = NEW toggle 1.0 (V0.1.49 §4.7: symbols '#+=' → layer-symbols-shift,
 *    symbolsShift '?123' → layer-symbols; NO ⇧ anymore) + 10 × 0.75 +
 *    ⏎ EXACTLY 1× at the r2 END, width 1.5;
 *  - symbols r0 = 10 digits at 1.0 (digit IDs asset-local), r1/r2 in the
 *    PLAN order (language-neutral identical in all 15);
 *  - footer r3 = ABC1.5/🌐1/😊1/space7.5/🎤2 — NO ⏎, no text character next to
 *    the space bar, mic last;
 *  - migration invariant: per asset the CHARACTER SET (direct keys ∪
 *    menu entries of both symbol layers) before == after — anchored against
 *    the migration list (no character is lost; da/fi-‰ stays reachable via the
 *    percent menu, da/fi/el-€ becomes a symbolsShift direct key).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SymbolGridAcrossAssetsTest {

    private val assetIds = listOf(
        "cs-cz", "da-dk", "de-de", "el-gr", "en-us", "es-es", "fi-fi", "fr-fr",
        "it-it", "nl-nl", "pl-pl", "pt-pt", "ru-ru", "sv-se", "tr-tr",
    )

    private fun asset(id: String) =
        LayoutParser.parse(java.io.File("src/main/assets/layouts/$id.json").readText())

    /** PLAN order symbols r1 (language-neutral, identical in all 15 assets). */
    private val symbolsR1Order = listOf("-", "+", "/", "(", ")", "&", "@", "=", "#", "%")

    /** PLAN order symbols r2 (language-neutral, identical in all 15 assets). */
    private val symbolsR2Order = listOf(":", ";", ",", "'", "\"", "_", "*", "!", "?", ".")

    /** Anchored migration list: as-is character set (direct keys ∪ menus) per asset
     *  BEFORE the T6 migration — sorted character string, nothing lost.
     *  Provenance (REVIEW_V0136 P2#1): the list = the T6 as-is inventory, hardcoded — an
     *  independent verification against the real pre-state is only possible with
     *  the git history (the real protection was the script assert before==after). */
    private val anchoredSets = mapOf(
        "cs-cz" to "!\"#\$%&'()*+,-./0123456789:;<=>?@[\\]_{|}~¡£§¬°±²³¹¼½¿×–—‘’‚“”„…‰€",
        "da-dk" to "!\"#\$%&'()*+,-./0123456789:;<=>?@[\\]_{|}~¡£¥§«¬°±²³¹»¼½¿–—‘’‚“”„†‡•…‰€",
        "de-de" to "!\"#\$%&'()*+,-./0123456789:;<=>?@[\\]_{|}~¡¢£¥§¬°±²³¶·¹¼½¿–—‘’‚“”„…‰€",
        "el-gr" to "!\"#\$%&'()*+,-./0123456789:;<=>?@[\\]_{|}~¡£§«¬°±²³·¹»¼½¿–—‘’‚“”„†‡•…‰€",
        "en-us" to "!\"#\$%&'()*+,-./0123456789:;<=>?@[\\]^_`{|}~¡£¥§©®°±¶¿×÷π–—‘’‚“”„•…‰€∆√",
        "es-es" to "!\"#\$%&'()*+,-./0123456789:;<=>?@[\\]_`{|}~¡¢£¥§ª«¬°±²³µ¹º»¼½¿–—‘’‚“”„…‰€",
        "fi-fi" to "!\"#\$%&'()*+,-./0123456789:;<=>?@[\\]_{|}~¡£¥§«¬°±²³¹»¼½¿–—‘’‚“”„†‡•…‰€",
        "fr-fr" to "!\"#\$%&'()*+,-./0123456789:;<=>?@[\\]_`{|}~¡¢£¥§«¬°±²³µ·¹»¼½¿–—‘’‚“”„†…‰€",
        "it-it" to "!\"#\$%&'()*+,-./0123456789:;<=>?@[\\]_`{|}~¡¢£¥§«¬°±²³µ·¹»¼½¿–—‘’‚“”„…‰€",
        "nl-nl" to "!\"#\$%&'()*+,-./0123456789:;<=>?@[\\]_`{|}~¡¢£¥§¬°±²³µ·¹¼½¿–—‘’‚“”„†…‰€",
        "pl-pl" to "!\"#\$%&'()*+,-./0123456789:;<=>?@[\\]_{|}~¡£§¬°±²³¹¼½¿×–—‘’‚“”„…‰€",
        "pt-pt" to "!\"#\$%&'()*+,-./0123456789:;<=>?@[\\]_`{|}~¡¢£¥§ª«¬°±²³µ·¹º»¼½¿–—‘’‚“”„…‰€",
        "ru-ru" to "!\"#\$%&'()*+,-./0123456789:;<=>?@[\\]_{|}~¡£§«¬°±²³¹»¼½¿×–—‘’‚“”„…‰€№",
        "sv-se" to "!\"#\$%&'()*+,-./0123456789:;<=>?@[\\]_{|}~¡£§¬°±²³¹¼½¿×–—‘’‚“”„…‰€",
        "tr-tr" to "!\"#\$%&'()*+,-./0123456789:;<=>?@[\\]_{|}~¡£§¬°±²³¹¼½¿×–—‘’‚“”„•…‰€",
    )

    @Test
    fun symbolGridAppliesWithStableSlotsInAll15Assets() {
        assertEquals("layout assets count", 15, assetIds.size)
        for (id in assetIds) {
            val asset = asset(id)
            for (layerName in listOf("symbols", "symbolsShift")) {
                val rows = asset.layers.getValue(layerName).rows
                assertEquals("$id/$layerName: 4 rows", 4, rows.size)
                // (a) unit grid Σ=[10,11.5,10,13] (V0.1.51).
                for ((i, expect) in listOf(10.0, 11.5, 10.0, 13.0).withIndex()) {
                    assertEquals("$id/$layerName Σ r$i", expect, rows[i].sumOf { it.width }, 0.001)
                }
                // (b) V0.1.51: r1 = margin 0.5 + 10 characters à 1.0; ⌫ EXACTLY
                //     1× at the r1 END, width 1.0 (== character width).
                val r1 = rows[1]
                assertEquals("$id/$layerName r1[0] is the margin", KeyKind.MARGIN, r1[0].kind)
                assertEquals("$id/$layerName r1 margin 0.5", 0.5, r1[0].width, 0.0001)
                val r1text = r1.filter { it.kind == KeyKind.TEXT }
                assertEquals("$id/$layerName r1 = 10 direct cells", 10, r1text.size)
                for (k in r1text) {
                    assertEquals("$id/$layerName r1 '${k.label}' at 1.0 (V0.1.51)", 1.0, k.width, 0.0001)
                }
                val bs = r1.filter { it.kind == KeyKind.ACTION && it.value == KeyActions.BACKSPACE }
                assertEquals("$id/$layerName ⌫ exactly 1x", 1, bs.size)
                assertTrue("$id/$layerName ⌫ at the r1 END", r1.last() === bs.single())
                assertEquals("$id/$layerName ⌫ width 1.0 (V0.1.51)", 1.0, bs.single().width, 0.0001)
                assertEquals(
                    "$id/$layerName ⌫ == character width (V0.1.51)",
                    r1text.first().width, bs.single().width, 1e-6,
                )
                // (c) V0.1.49: Z2[0] is the TOGGLE (1.0) — symbols '#+=' →
                //     layer-symbols-shift, symbolsShift '?123' → layer-symbols;
                //     ⇧ is GONE from both symbol layers. ⏎ EXACTLY 1× at the r2
                //     END, width 1.5.
                val r2 = rows[2]
                val toggle = r2.firstOrNull()
                val wantToggle = if (layerName == "symbols")
                    listOf("symbols-toggle", "#+=", KeyActions.LAYER_SYMBOLS_ALT)
                else
                    listOf("symbols-back", "?123", KeyActions.LAYER_SYMBOLS)
                assertTrue(
                    "$id/$layerName toggle r2[0] (${wantToggle[0]})",
                    toggle != null && toggle.kind == KeyKind.ACTION && toggle.value == wantToggle[2],
                )
                assertEquals("$id/$layerName ${wantToggle[0]} id", wantToggle[0], toggle!!.id)
                assertEquals("$id/$layerName ${wantToggle[0]} label", wantToggle[1], toggle.label)
                assertEquals("$id/$layerName ${wantToggle[0]} width 1.0 (V0.1.49)", 1.0, toggle.width, 0.0001)
                assertEquals(
                    "$id/$layerName NO ⇧ on the symbol layers (V0.1.49 §4.7)",
                    0, rows.flatten().count { it.value == KeyActions.SHIFT },
                )
                val enter = r2.filter { it.kind == KeyKind.ACTION && it.value == KeyActions.ENTER }
                assertEquals("$id/$layerName ⏎ exactly 1x in r2", 1, enter.size)
                assertTrue("$id/$layerName ⏎ at the r2 END", r2.last() === enter.single())
                assertEquals("$id/$layerName ⏎ width 1.5 (V0.1.49)", 1.5, enter.single().width, 0.0001)
                // (c2) T7-M rule (v0.1.37, PLAN_SYMBOL_RASTER.md; budget adapted
                //     to V0.1.49): r2 at most 10 direct cells at 0.75 — 11/12 at
                //     0.75 break the budget 7.5 (Σ10.000 − toggle 1.0 − ⏎ 1.5;
                //     11·0.75 = 8.25 > 7.5, arithmetically impossible). Overflow
                //     stays reachable via inventory menus (menu handover: da/fi
                //     „…“ → dot menu, el „–“ → hyphen menu).
                val r2text = r2.filter { it.kind == KeyKind.TEXT }
                assertTrue("$id/$layerName r2 ≤ 10 direct cells (M rule)", r2text.size <= 10)
                for (k in r2text) {
                    assertEquals("$id/$layerName r2 '${k.label}' at 0.75 (M rule)", 0.75, k.width, 0.0001)
                }
                // (d) footer: ABC1.5/🌐1/😊1/space7.5/🎤2 — no ⏎, no text character
                //     next to space, mic last (cursor cells dropped).
                val r3 = rows[3]
                assertEquals("$id/$layerName r3 exactly 5 keys", 5, r3.size)
                assertEquals(
                    "$id/$layerName footer layout",
                    listOf("base", "language", "emoji", "space", "mic"),
                    r3.map { it.id },
                )
                assertEquals("$id/$layerName base 1.5", 1.5, r3[0].width, 0.0001)
                assertEquals("$id/$layerName space 7.5", 7.5, r3[3].width, 0.0001)
                assertEquals("$id/$layerName mic 2.0 (last)", 2.0, r3[4].width, 0.0001)
                assertTrue("$id/$layerName no text character in the footer",
                    r3.none { it.kind == KeyKind.TEXT })
            }
            // (e) symbols language-neutral: r0 = 10 digits at 1.0; r1/r2 in PLAN order.
            val sym = asset.layers.getValue("symbols").rows
            assertEquals("$id/symbols r0 = 10 digits", 10, sym[0].size)
            for (k in sym[0]) {
                assertTrue("$id/symbols r0 '${k.label}' is a digit",
                    k.label.length == 1 && k.label[0] in '0'..'9')
                assertEquals("$id/symbols r0 digit 1.0", 1.0, k.width, 0.0001)
            }
            assertEquals(
                "$id/symbols r1 characters in PLAN order",
                symbolsR1Order,
                sym[1].filter { it.kind == KeyKind.TEXT }.map { it.label },
            )
            assertEquals(
                "$id/symbols r2 characters in PLAN order",
                symbolsR2Order,
                sym[2].filter { it.kind == KeyKind.TEXT }.map { it.label },
            )
            // (f) symbolsShift r0: 10 keys at 1.0 (superscript block, „where available").
            val sh0 = asset.layers.getValue("symbolsShift").rows[0]
            assertEquals("$id/symbolsShift r0 = 10 keys", 10, sh0.size)
            for (k in sh0) {
                assertEquals("$id/symbolsShift r0 '${k.label}' width 1.0", 1.0, k.width, 0.0001)
            }
            // (g) migration invariant: character set (direct keys ∪ menus) before==after.
            val now = mutableSetOf<String>()
            for (layerName in listOf("symbols", "symbolsShift")) {
                for (row in asset.layers.getValue(layerName).rows) {
                    for (key in row) {
                        if (key.kind == KeyKind.TEXT) now.add(key.label)
                        key.alternates.forEach { now.add(it) }
                    }
                }
            }
            assertEquals(
                "$id: character set before==after (anchored migration list)",
                anchoredSets.getValue(id),
                now.sorted().joinToString(""),
            )
        }
    }
}
