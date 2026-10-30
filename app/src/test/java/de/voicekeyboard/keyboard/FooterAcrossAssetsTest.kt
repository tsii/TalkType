package de.voicekeyboard.keyboard

import de.voicekeyboard.layouts.KeyActions
import de.voicekeyboard.layouts.KeyKind
import de.voicekeyboard.layouts.LayoutKey
import de.voicekeyboard.layouts.LayoutParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * F8 Q3 (P2): Die Footer-Zusammenfassung (D10 §1) war bisher nur an EINEM
 * synthetischen de-analogen Footer bewiesen. Dieser Test legt die echte
 * Komposition ueber ALLE 15 Assets x 4 Ebenen und verlangt je Ebene:
 *
 *  - Gewichtssumme unveraendert (13),
 *  - Mic ist die letzte Taste und traegt 2 Einheiten,
 *  - Enter sitzt direkt davor (Mic verdraengt keine Buchstabentaste),
 *  - ID-Menge identisch mit der Asset-Ebene (keine Taste verschluckt),
 *  - jedeMic-Taste hat 48-dp-konforme Breite (>= 2 Einheiten).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class FooterAcrossAssetsTest {

    private val assetIds = listOf(
        "cs-cz", "da-dk", "de-de", "el-gr", "en-us", "es-es", "fi-fi", "fr-fr",
        "it-it", "nl-nl", "pl-pl", "pt-pt", "ru-ru", "sv-se", "tr-tr",
    )

    private fun action(row: List<LayoutKey>, value: String) =
        row.single { it.kind == KeyKind.ACTION && it.value == value }

    @Test
    fun footerZusammenfassungGiltFürAlle15AssetsUndAlleEbenen() {
        assertEquals("Layout-Assets zahlen", 15, assetIds.size)
        var gepruefteEbenen = 0
        for (id in assetIds) {
            val asset = LayoutParser.parse(java.io.File("src/main/assets/layouts/$id.json").readText())
            for ((name, layer) in asset.layers) {
                val raw = layer.rows
                val idx = raw.indexOfFirst { row ->
                    row.any { it.kind == KeyKind.ACTION && it.value == KeyActions.MIC }
                }
                assertTrue("$id/$name: Ebene ohne Mic-Reihe", idx >= 0)
                val rawFoot = raw[idx]
                val composed = D10_FOOTER.compose(raw)
                assertEquals("$id/$name Reihenanzahl", raw.size, composed.size)
                val footRow = composed[idx]
                assertEquals(
                    "$id/$name Gewichtssumme Fussreihe unveraendert",
                    rawFoot.sumOf { it.width },
                    footRow.sumOf { it.width },
                    0.0001,
                )
                assertEquals("$id/$name Fussreihe = 13 Einheiten", 13.0, footRow.sumOf { it.width }, 0.0001)
                val mic = action(footRow, KeyActions.MIC)
                val enter = action(footRow, KeyActions.ENTER)
                assertEquals("$id/$name Mic ist letzte Taste", footRow.last().id, mic.id)
                assertEquals("$id/$name Mic-Gewicht 2", 2.0, mic.width, 0.0001)
                assertEquals(
                    "$id/$name Enter direkt vor Mic",
                    footRow.size - 2,
                    footRow.indexOf(enter),
                )
                assertEquals(
                    "$id/$name Space hat genau eine Einheit abgegeben",
                    action(rawFoot, KeyActions.SPACE).width - 1.0,
                    action(footRow, KeyActions.SPACE).width,
                    0.0001,
                )
                assertEquals(
                    "$id/$name ID-Menge unveraendert",
                    rawFoot.map { it.id }.toSet(),
                    footRow.map { it.id }.toSet(),
                )
                gepruefteEbenen++
            }
        }
        assertEquals("Alle Ebenen geprueft", 60, gepruefteEbenen)
    }
}
