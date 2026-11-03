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
 * F27d/F29 (Stock-Geometrie + Laptop-Footer): Das Footer-Zielbild ueber ALLE 15 Assets.
 *
 * F29 (Zielbild, 2026-10-31 Nutzerentscheidung): base/shift-Fussreihe (r3): symbols 1.5,
 * language 1, emoji 1, space **6.5**, period **1**, mic **2** (Summe 13);
 * period traegt >= 5 alternates + passende cellActions mit den 2 Cursor-Zellen
 * (cursor-left auf '‹', cursor-right auf '›'; ru-ru: 6 alternates mit @ an alternates[0]);
 * KEIN enter in r3; enter genau EINMAL in r2 (value "enter");
 * base/shift r3 identisch (id + width + value); mic ist die letzte Taste.
 *
 * Die Symbolebenen (symbols/symbolsShift) sind unangetastet und behalten den
 * Alt-Footer (base/language/emoji/space/mic/enter) — D10_FOOTER.compose reflowt
 * ihn weiter (no-op nur ohne enter in der Zeile; ueberprueft im KeyboardModelTest).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class FooterAcrossAssetsTest {

    private val assetIds = listOf(
        "cs-cz", "da-dk", "de-de", "el-gr", "en-us", "es-es", "fi-fi", "fr-fr",
        "it-it", "nl-nl", "pl-pl", "pt-pt", "ru-ru", "sv-se", "tr-tr",
    )

    private fun asset(id: String) =
        LayoutParser.parse(java.io.File("src/main/assets/layouts/$id.json").readText())

    @Test
    fun neuesFooterZielbildGiltFürAlle15Assets() {
        assertEquals("Layout-Assets zahlen", 15, assetIds.size)
        var gepruefteEbenen = 0
        for (id in assetIds) {
            val asset = asset(id)
            val base = asset.layers.getValue("base").rows
            val shift = asset.layers.getValue("shift").rows
            checkNewFooter(base, "$id/base")
            checkNewFooter(shift, "$id/shift")
            // base/shift r3 identisch (id + width + value)
            assertEquals(
                "$id base/shift r3 muss identisch sein",
                base[3].map { it.id to it.width to it.value },
                shift[3].map { it.id to it.width to it.value },
            )
            gepruefteEbenen += 2
        }
        assertEquals("Alle base/shift-Ebenen geprüft", 30, gepruefteEbenen)
    }

    private fun checkNewFooter(rows: List<List<LayoutKey>>, ctx: String) {
        val r3 = rows[3]
        assertEquals("$ctx r3 Summe 13", 13.0, r3.sumOf { it.width }, 0.0001)
        assertEquals("$ctx r3 exakt 6 Tasten", 6, r3.size)
        val byId = r3.associateBy { it.id }
        // F29-Zielbild: space 6.5 statt 7.0, mic 2 statt 1.5 (Punkt sitzt weiter links,
        // Mikrofon bekommt 2u).
        for ((id, w) in listOf("symbols" to 1.5, "language" to 1.0, "emoji" to 1.0, "space" to 6.5)) {
            assertEquals("$ctx $id = $w", w, byId.getValue(id).width, 0.0001)
        }
        assertEquals("$ctx period = 1", 1.0, byId.getValue("period").width, 0.0001)
        assertEquals("$ctx mic = 2", 2.0, byId.getValue("mic").width, 0.0001)
        assertEquals("$ctx mic ist die letzte Taste", "mic", r3.last().id)
        // F54 (Nutzer-Auftrag 02:4x): period = , ! ? : ; ( ) (ru-ru: @-Präfix
        // davor) — ‹/› und die cursor-Zellen sind RAUS; Grundzeichen bleibt
        // auf der Taste; keine cursor-left/right-Aktionen mehr.
        val period = byId.getValue("period")
        val expectedAlts = if (ctx.contains("ru-ru")) 8 else 7
        assertEquals("$ctx period hat $expectedAlts alternates", expectedAlts, period.alternates.size)
        assertEquals(
            "$ctx period-Zellreihenfolge , ! ? : ; ( )",
            listOf(",", "!", "?", ":", ";", "(", ")"),
            if (ctx.contains("ru-ru")) period.alternates.drop(1) else period.alternates,
        )
        assertEquals("keine cursor-Aktionen im period-Key", 0, period.cellActions.size)
        // KEIN enter in r3
        assertTrue("$ctx kein enter in r3", r3.none { it.value == KeyActions.ENTER })
        // enter genau EINMAL in r2, nirgendwo sonst in der Ebene
        assertEquals("$ctx enter genau 1x in r2", 1, rows[2].count { it.value == KeyActions.ENTER })
        val enterElsewhere = rows.filterIndexed { i, _ -> i != 2 }.flatten().count { it.value == KeyActions.ENTER }
        assertEquals("$ctx enter nirgendwo ausser r2", 0, enterElsewhere)
    }

    /** F29-ABSCHLUSS (ru-ru-Sonderfall): 11 Buchstaben in r1 lassen @-Taste nicht zu
     *  (0.5 + 11 + 1 = 12.5u > 12, r2 ist D27-gesperrt) — @ sitzt stattdessen als
     *  alternates[0] an der Punkt-Taste der Fusszeile (base UND shift), die Cursor-Zellen
     *  bleiben auf '‹'/'›'. */
    @Test
    fun ruRuKeinAtKeyInR1StattdessenAtAlsAlternates0AmPeriod() {
        val asset = asset("ru-ru")
        for (layerName in listOf("base", "shift")) {
            val rows = asset.layers.getValue(layerName).rows
            assertTrue("$layerName: kein 'at'-Objekt in r1", rows[1].none { it.id == "at" })
            val period = rows[3].first { it.id == "period" }
            assertEquals("$layerName: @ ist alternates[0] am period", "@", period.alternates.first())
            assertEquals("$layerName: F54-Standardzellen hinter @", listOf(",", "!", "?", ":", ";", "(", ")"), period.alternates.drop(1))
            assertEquals("$layerName: F54 — keine cursor-Aktionen mehr", 0, period.cellActions.size)
        }
    }

    /** T6 (v0.1.37, PLAN_SYMBOL_RASTER): Symbolebenen-Footer MIGRIERT — der
     *  Alt-Footer (⏎ 1,5 + Cursor-Zellen) ist abgelöst: r3 = ABC1,5/🌐1/😊1/
     *  space7,5/🎤2, mic zuletzt, KEIN ⏎ (⏎ wandert an r2-Ende in den Buchstaben-
     *  Slot wie in der Buchstaben-Ebene; kein Textzeichen neben der Spacebar).
     *  D10_FOOTER.compose ist damit kein Reflow mehr (kein enter in der Zeile). */
    @Test
    fun symbolEbenenT6FooterOhneEnter() {
        for (id in assetIds) {
            val asset = asset(id)
            for (layerName in listOf("symbols", "symbolsShift")) {
                val raw = asset.layers.getValue(layerName).rows
                val foot = raw[3]
                assertEquals("$id/$layerName Fussreihe = 13", 13.0, foot.sumOf { it.width }, 0.0001)
                assertEquals("$id/$layerName r3 exakt 5 Tasten (T6)", 5, foot.size)
                assertEquals(
                    "$id/$layerName Footer-Belegung",
                    listOf("base", "language", "emoji", "space", "mic"),
                    foot.map { it.id },
                )
                assertEquals("$id/$layerName mic 2,0 (zuletzt)", 2.0, foot.last().width, 0.0001)
                assertEquals("$id/$layerName space 7,5", 7.5, foot[3].width, 0.0001)
                assertTrue(
                    "$id/$layerName KEIN enter im Footer (T6: ⏎ sitzt am r2-Ende)",
                    foot.none { it.value == KeyActions.ENTER },
                )
                assertTrue(
                    "$id/$layerName keine Cursor-Zellen mehr (T6)",
                    foot.none { it.id == "cursor-left" || it.id == "cursor-right" },
                )
                // compose: ohne enter in der Zeile kein Reflow — Gewichte unveraendert.
                val composed = D10_FOOTER.compose(raw)
                assertEquals(
                    "$id/$layerName compose no-op (kein enter)",
                    foot.sumOf { it.width },
                    composed[3].sumOf { it.width },
                    0.0001,
                )
            }
        }
    }
}
