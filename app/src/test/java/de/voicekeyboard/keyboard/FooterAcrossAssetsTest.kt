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
 * V0.1.49 (docs/PLAN_GEOMETRY_V0149.md §4.5): the footer target image across
 * ALL 15 assets — the T11 slimming is REVERSED.
 *
 * base/shift footer r3 (ALL 15, incl. ru-ru — it already had this form since
 * F54): 6 keys — symbols 1.5, language 1, emoji 1, space **6.5** (7.5→6.5,
 * Σ stays 13), **period 1.0** (BACK from r2, between space and mic; F54 menu
 * `( ) : ; ! ? ,` — ru-ru: @ as alternates[0] before the F54 cells), mic **2**
 * (last); NO enter in r3; enter exactly ONCE in r2; NO period in r2; base/shift
 * r3 identical (id + width + value).
 *
 * The symbol layers (symbols/symbolsShift) are untouched and keep the T6
 * footer (base/language/emoji/space/mic) — D10_FOOTER.compose keeps
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
    fun newFooterTargetImageAppliesToAll15Assets() {
        assertEquals("layout asset count", 15, assetIds.size)
        var checkedLayers = 0
        for (id in assetIds) {
            val asset = asset(id)
            val base = asset.layers.getValue("base").rows
            val shift = asset.layers.getValue("shift").rows
            // V0.1.49: ALL 15 assets share the 6-key footer (ru-ru was already
            // there — only its period menu differs: @ as alternates[0]).
            checkNewFooter(base, "$id/base", id == "ru-ru")
            checkNewFooter(shift, "$id/shift", id == "ru-ru")
            // base/shift r3 identical (id + width + value)
            assertEquals(
                "$id base/shift r3 must be identical",
                base[3].map { it.id to it.width to it.value },
                shift[3].map { it.id to it.width to it.value },
            )
            checkedLayers += 2
        }
        assertEquals("all base/shift layers checked", 30, checkedLayers)
    }

    /** V0.1.49 (all 15): 6-key footer — period BACK between space and mic,
     *  space 7.5 → 6.5 (Σ 13). ru-ru only differs in the period menu (@ first). */
    private fun checkNewFooter(rows: List<List<LayoutKey>>, ctx: String, isRu: Boolean) {
        val r3 = rows[3]
        assertEquals("$ctx r3 sum 13", 13.0, r3.sumOf { it.width }, 0.0001)
        assertEquals("$ctx r3 exactly 6 keys (V0.1.49: period back in the footer)", 6, r3.size)
        assertEquals(
            "$ctx footer ids (V0.1.49 §4.5)",
            listOf("symbols", "language", "emoji", "space", "period", "mic"),
            r3.map { it.id },
        )
        for ((id, w) in listOf("symbols" to 1.5, "language" to 1.0, "emoji" to 1.0, "space" to 6.5)) {
            assertEquals("$ctx $id = $w", w, r3.first { it.id == id }.width, 0.0001)
        }
        assertEquals("$ctx period = 1.0 (one footer unit)", 1.0, r3.single { it.id == "period" }.width, 0.0001)
        assertEquals("$ctx mic = 2 (last)", 2.0, r3.last().width, 0.0001)
        assertEquals("$ctx mic is the last key", "mic", r3.last().id)
        val period = r3.single { it.id == "period" }
        assertEquals("$ctx period is a text key '.'", ".", period.value)
        assertEquals(
            "$ctx period menu",
            if (isRu) listOf("@", "(", ")", ":", ";", "!", "?", ",")
            else listOf("(", ")", ":", ";", "!", "?", ","),
            period.alternates,
        )
        assertTrue(
            "$ctx NO period in r2 (V0.1.49: the period key sits in the footer)",
            rows[2].none { it.id == "period" },
        )
        // NO enter in r3
        assertTrue("$ctx no enter in r3", r3.none { it.value == KeyActions.ENTER })
        // enter exactly ONCE in r2, nowhere else in the layer
        assertEquals("$ctx enter exactly 1x in r2", 1, rows[2].count { it.value == KeyActions.ENTER })
        val enterElsewhere = rows.filterIndexed { i, _ -> i != 2 }.flatten().count { it.value == KeyActions.ENTER }
        assertEquals("$ctx enter nowhere except r2", 0, enterElsewhere)
    }

    /** F29 conclusion (ru-ru special case): 11 letters in r1 leave no room for an @
     *  key (0.5 + 11 + 1 = 12.5u > 12, r2 is D27-locked) — @ sits instead as
     *  alternates[0] on the period key of the footer row (base AND shift), the
     *  cursor cells stay on '‹'/'›'. */
    @Test
    fun ruRuNoAtKeyInR1InsteadAtAsAlternates0OnPeriod() {
        val asset = asset("ru-ru")
        for (layerName in listOf("base", "shift")) {
            val rows = asset.layers.getValue(layerName).rows
            assertTrue("$layerName: no 'at' object in r1", rows[1].none { it.id == "at" })
            val period = rows[3].first { it.id == "period" }
            assertEquals("$layerName: @ is alternates[0] on the period key", "@", period.alternates.first())
            assertEquals("$layerName: F54 standard cells behind @", listOf("(", ")", ":", ";", "!", "?", ","), period.alternates.drop(1))
            assertEquals("$layerName: F54 — no more cursor actions", 0, period.cellActions.size)
        }
    }

    /** T6 (v0.1.37, PLAN_SYMBOL_RASTER): symbol layer footer MIGRATED — the
     *  old footer (⏎ 1.5 + cursor cells) is superseded: r3 = ABC1.5/🌐1/😊1/
     *  space7.5/🎤2, mic last, NO ⏎ (⏎ moves to the r2 end into the letter slot
     *  as in the letter layer; no text character next to the space bar).
     *  D10_FOOTER.compose is thereby no longer a reflow (no enter in the row). */
    @Test
    fun symbolLayersT6FooterWithoutEnter() {
        for (id in assetIds) {
            val asset = asset(id)
            for (layerName in listOf("symbols", "symbolsShift")) {
                val raw = asset.layers.getValue(layerName).rows
                val foot = raw[3]
                assertEquals("$id/$layerName footer row = 13", 13.0, foot.sumOf { it.width }, 0.0001)
                assertEquals("$id/$layerName r3 exactly 5 keys (T6)", 5, foot.size)
                assertEquals(
                    "$id/$layerName footer layout",
                    listOf("base", "language", "emoji", "space", "mic"),
                    foot.map { it.id },
                )
                assertEquals("$id/$layerName mic 2.0 (last)", 2.0, foot.last().width, 0.0001)
                assertEquals("$id/$layerName space 7.5", 7.5, foot[3].width, 0.0001)
                assertTrue(
                    "$id/$layerName NO enter in the footer (T6: ⏎ sits at the r2 end)",
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
