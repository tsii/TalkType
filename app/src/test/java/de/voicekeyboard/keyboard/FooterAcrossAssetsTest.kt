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
 * F27d/F29 (stock geometry + laptop footer): the footer target image across ALL 15 assets.
 *
 * F29 (target image, 2026-10-31 user decision): base/shift footer row (r3):
 * symbols 1.5, language 1, emoji 1, space **6.5**, period **1**, mic **2** (sum 13);
 * period carries >= 5 alternates + fitting cellActions with the 2 cursor cells
 * (cursor-left on '‹', cursor-right on '›'; ru-ru: 6 alternates with @ at alternates[0]);
 * NO enter in r3; enter exactly ONCE in r2 (value "enter");
 * base/shift r3 identical (id + width + value); mic is the last key.
 *
 * The symbol layers (symbols/symbolsShift) are untouched and keep the old
 * footer (base/language/emoji/space/mic/enter) — D10_FOOTER.compose keeps
 * reflowing it (a no-op only without enter in the row; checked in KeyboardModelTest).
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
            // base/shift r3 identical (id + width + value)
            assertEquals(
                "$id base/shift r3 must be identical",
                base[3].map { it.id to it.width to it.value },
                shift[3].map { it.id to it.width to it.value },
            )
            gepruefteEbenen += 2
        }
        assertEquals("all base/shift layers checked", 30, gepruefteEbenen)
    }

    private fun checkNewFooter(rows: List<List<LayoutKey>>, ctx: String) {
        val r3 = rows[3]
        assertEquals("$ctx r3 Summe 13", 13.0, r3.sumOf { it.width }, 0.0001)
        assertEquals("$ctx r3 exakt 6 Tasten", 6, r3.size)
        val byId = r3.associateBy { it.id }
        // F29 target image: space 6.5 instead of 7.0, mic 2 instead of 1.5 (the
        // period sits further left, the microphone gets 2u).
        for ((id, w) in listOf("symbols" to 1.5, "language" to 1.0, "emoji" to 1.0, "space" to 6.5)) {
            assertEquals("$ctx $id = $w", w, byId.getValue(id).width, 0.0001)
        }
        assertEquals("$ctx period = 1", 1.0, byId.getValue("period").width, 0.0001)
        assertEquals("$ctx mic = 2", 2.0, byId.getValue("mic").width, 0.0001)
        assertEquals("$ctx mic is the last key", "mic", r3.last().id)
        // F54 (user assignment 02:4x): period = , ! ? : ; ( ) (ru-ru: @ prefix
        // before it) — ‹/› and the cursor cells are OUT; the base character
        // stays on the key; no more cursor-left/right actions.
        val period = byId.getValue("period")
        val expectedAlts = if (ctx.contains("ru-ru")) 8 else 7
        assertEquals("$ctx period has $expectedAlts alternates", expectedAlts, period.alternates.size)
        assertEquals(
            "$ctx period-Zellreihenfolge , ! ? : ; ( )",
            listOf(",", "!", "?", ":", ";", "(", ")"),
            if (ctx.contains("ru-ru")) period.alternates.drop(1) else period.alternates,
        )
        assertEquals("no cursor actions in the period key", 0, period.cellActions.size)
        // NO enter in r3
        assertTrue("$ctx no enter in r3", r3.none { it.value == KeyActions.ENTER })
        // enter exactly ONCE in r2, nowhere else in the layer
        assertEquals("$ctx enter exactly 1x in r2", 1, rows[2].count { it.value == KeyActions.ENTER })
        val enterElsewhere = rows.filterIndexed { i, _ -> i != 2 }.flatten().count { it.value == KeyActions.ENTER }
        assertEquals("$ctx enter nirgendwo ausser r2", 0, enterElsewhere)
    }

    /** F29 conclusion (ru-ru special case): 11 letters in r1 leave no room for an @
     *  key (0.5 + 11 + 1 = 12.5u > 12, r2 is D27-locked) — @ sits instead as
     *  alternates[0] on the period key of the footer row (base AND shift), the
     *  cursor cells stay on '‹'/'›'. */
    @Test
    fun ruRuKeinAtKeyInR1StattdessenAtAlsAlternates0AmPeriod() {
        val asset = asset("ru-ru")
        for (layerName in listOf("base", "shift")) {
            val rows = asset.layers.getValue(layerName).rows
            assertTrue("$layerName: no 'at' object in r1", rows[1].none { it.id == "at" })
            val period = rows[3].first { it.id == "period" }
            assertEquals("$layerName: @ is alternates[0] on the period key", "@", period.alternates.first())
            assertEquals("$layerName: F54-Standardzellen hinter @", listOf(",", "!", "?", ":", ";", "(", ")"), period.alternates.drop(1))
            assertEquals("$layerName: F54 — no more cursor actions", 0, period.cellActions.size)
        }
    }

    /** T6 (v0.1.37, PLAN_SYMBOL_RASTER): symbol layer footer MIGRATED — the
     *  old footer (⏎ 1.5 + cursor cells) is superseded: r3 = ABC1.5/🌐1/😊1/
     *  space7.5/🎤2, mic last, NO ⏎ (⏎ moves to the r2 end into the letter slot
     *  as in the letter layer; no text character next to the space bar).
     *  D10_FOOTER.compose is thereby no longer a reflow (no enter in the row). */
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
                    "$id/$layerName no more cursor cells (T6)",
                    foot.none { it.id == "cursor-left" || it.id == "cursor-right" },
                )
                // compose: without enter in the row no reflow — weights unchanged.
                val composed = D10_FOOTER.compose(raw)
                assertEquals(
                    "$id/$layerName compose no-op (no enter)",
                    foot.sumOf { it.width },
                    composed[3].sumOf { it.width },
                    0.0001,
                )
            }
        }
    }
}
