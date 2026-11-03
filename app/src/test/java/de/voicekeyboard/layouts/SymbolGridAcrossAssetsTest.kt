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
 * T6 (v0.1.37, docs/PLAN_SYMBOL_RASTER.md §Tests 2): Symbol-Raster mit stabilen
 * ⌫/⏎-Buchstaben-Slots — über ALLE 15 Assets:
 *
 *  - Σ je Reihe [10, 10, 10, 13] ± 0,001 (Einheitsraster; 1u = 108 px);
 *  - ⌫ EXAKT 1× am r1-ENDE, width 1,0 (Buchstaben-Slot wie base r1 — KEY_GAP_DP=0
 *    ⇒ pixelgleich); ⏎ EXAKT 1× am r2-ENDE, width 1,0; ⇧ bleibt r2[0] mit 1,5;
 *  - symbols r0 = 10 Ziffern à 1,0 (Digit-IDs asset-lokal), r1/r2 in der
 *    PLAN-Ordnung (sprachneutral identisch in allen 15);
 *  - Footer r3 = ABC1,5/🌐1/😊1/space7,5/🎤2 — KEIN ⏎, kein Textzeichen neben
 *    der Spacebar, mic zuletzt;
 *  - Migrations-Invariante: je Asset ist die ZEICHENMENGE (Direkttasten ∪
 *    Menü-Einträge beider Symbolebenen) vor == nach — verankert gegen die
 *    Migrationsliste (kein Zeichen geht verloren; da/fi-‰ bleibt über das
 *    percent-Menü erreichbar, da/fi/el-€ wird symbolsShift-Direkttaste).
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

    /** Plan-Ordnung symbols r1 (sprachneutral, alle 15 Assets identisch). */
    private val symbolsR1Order = listOf("-", "/", ":", ";", "(", ")", "&", "@", "=", "#")

    /** Plan-Ordnung symbols r2 (sprachneutral, alle 15 Assets identisch). */
    private val symbolsR2Order = listOf(".", ",", "?", "!", "'", "\"", "%", "+", "_", "*")

    /** Verankerte Migrationsliste: Ist-Zeichenmenge (Direkttasten ∪ Menüs) je Asset
     *  VOR der T6-Migration — sortierte Zeichenkette, nothing lost.
     *  Provenienz (REVIEW_V0136 P2#1): Liste = T6-Ist-Bestand, hartkodiert — eine
     *  unabhängige Verprobung gegen den echten Vor-Zustand ist erst mit Git-
     *  Historie möglich (echter Schutz lag im Skript-Assert vor==nach). */
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
    fun symbolRasterGiltMitStabilenSlotsInAllen15Assets() {
        assertEquals("Layout-Assets zahlen", 15, assetIds.size)
        for (id in assetIds) {
            val asset = asset(id)
            for (layerName in listOf("symbols", "symbolsShift")) {
                val rows = asset.layers.getValue(layerName).rows
                assertEquals("$id/$layerName: 4 Reihen", 4, rows.size)
                // (a) Einheitsraster Σ=[10,10,10,13].
                for ((i, expect) in listOf(10.0, 10.0, 10.0, 13.0).withIndex()) {
                    assertEquals("$id/$layerName Σ r$i", expect, rows[i].sumOf { it.width }, 0.001)
                }
                // (b) ⌫ EXAKT 1× am r1-ENDE, width 1,0 (Buchstaben-Slot).
                val r1 = rows[1]
                val bs = r1.filter { it.kind == KeyKind.ACTION && it.value == KeyActions.BACKSPACE }
                assertEquals("$id/$layerName ⌫ genau 1x", 1, bs.size)
                assertTrue("$id/$layerName ⌫ am r1-ENDE", r1.last() === bs.single())
                assertEquals("$id/$layerName ⌫ width 1,0", 1.0, bs.single().width, 0.0001)
                // (c) ⇧ bleibt r2[0] mit 1,5; ⏎ EXAKT 1× am r2-ENDE, width 1,0.
                val r2 = rows[2]
                val shift = r2.firstOrNull()
                assertTrue(
                    "$id/$layerName ⇧ r2[0]",
                    shift != null && shift.kind == KeyKind.ACTION && shift.value == KeyActions.SHIFT,
                )
                assertEquals("$id/$layerName ⇧ 1,5 bleibt", 1.5, shift!!.width, 0.0001)
                val enter = r2.filter { it.kind == KeyKind.ACTION && it.value == KeyActions.ENTER }
                assertEquals("$id/$layerName ⏎ genau 1x in r2", 1, enter.size)
                assertTrue("$id/$layerName ⏎ am r2-ENDE", r2.last() === enter.single())
                assertEquals("$id/$layerName ⏎ width 1,0", 1.0, enter.single().width, 0.0001)
                // (c2) T7-M-Regel (v0.1.37, PLAN_SYMBOL_RASTER.md): r2 maximal 10
                //     Direktzellen à 0,75 — 11/12 à 0,75 sprengen das Budget 7,5
                //     (Σ10,000 − ⇧1,5 − ⏎1,0; 11·0,75 = 8,25 > 7,5, arithmetisch
                //     unmöglich). Überlauf bleibt über Bestands-Menüs erreichbar
                //     (Menü-Weitergabe: da/fi „…“ → dot-Menü, el „–“ → hyphen-Menü).
                val r2text = r2.filter { it.kind == KeyKind.TEXT }
                assertTrue("$id/$layerName r2 ≤ 10 Direktzellen (M-Regel)", r2text.size <= 10)
                for (k in r2text) {
                    assertEquals("$id/$layerName r2 '${k.label}' à 0,75 (M-Regel)", 0.75, k.width, 0.0001)
                }
                // (d) Footer: ABC1,5/🌐1/😊1/space7,5/🎤2 — kein ⏎, kein Textzeichen
                //     neben Space, mic zuletzt (Cursor-Zellen entfallen).
                val r3 = rows[3]
                assertEquals("$id/$layerName r3 exakt 5 Tasten", 5, r3.size)
                assertEquals(
                    "$id/$layerName Footer-Belegung",
                    listOf("base", "language", "emoji", "space", "mic"),
                    r3.map { it.id },
                )
                assertEquals("$id/$layerName base 1,5", 1.5, r3[0].width, 0.0001)
                assertEquals("$id/$layerName space 7,5", 7.5, r3[3].width, 0.0001)
                assertEquals("$id/$layerName mic 2,0 (zuletzt)", 2.0, r3[4].width, 0.0001)
                assertTrue("$id/$layerName kein Textzeichen im Footer",
                    r3.none { it.kind == KeyKind.TEXT })
            }
            // (e) symbols sprachneutral: r0 = 10 Ziffern à 1,0; r1/r2 in Plan-Ordnung.
            val sym = asset.layers.getValue("symbols").rows
            assertEquals("$id/symbols r0 = 10 Ziffern", 10, sym[0].size)
            for (k in sym[0]) {
                assertTrue("$id/symbols r0 '${k.label}' ist eine Ziffer",
                    k.label.length == 1 && k.label[0] in '0'..'9')
                assertEquals("$id/symbols r0 Ziffer 1,0", 1.0, k.width, 0.0001)
            }
            assertEquals(
                "$id/symbols r1-Zeichen in Plan-Ordnung",
                symbolsR1Order,
                sym[1].filter { it.kind == KeyKind.TEXT }.map { it.label },
            )
            assertEquals(
                "$id/symbols r2-Zeichen in Plan-Ordnung",
                symbolsR2Order,
                sym[2].filter { it.kind == KeyKind.TEXT }.map { it.label },
            )
            // (f) symbolsShift r0: 10 Tasten à 1,0 (Supertext-Block, „wo vorhanden").
            val sh0 = asset.layers.getValue("symbolsShift").rows[0]
            assertEquals("$id/symbolsShift r0 = 10 Tasten", 10, sh0.size)
            for (k in sh0) {
                assertEquals("$id/symbolsShift r0 '${k.label}' width 1,0", 1.0, k.width, 0.0001)
            }
            // (g) Migrations-Invariante: Zeichenmenge (Direkttasten ∪ Menüs) vor==nach.
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
                "$id: Zeichenmenge vor==nach (verankerte Migrationsliste)",
                anchoredSets.getValue(id),
                now.sorted().joinToString(""),
            )
        }
    }
}
