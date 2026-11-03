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
 * F27d/F29 + T11 (docs/PLAN_T11_T12.md §2): the footer target image across
 * ALL 15 assets.
 *
 * T11 (base/shift footer row r3, 14 assets): the period key moves UP to r2
 * (slot before ⏎) — the footer slims to 5 keys: symbols 1.5, language 1,
 * emoji 1, space **7.5** (6.5→7.5, Σ stays 13), mic **2** (last); NO period,
 * NO enter in r3; enter exactly ONCE in r2; base/shift r3 identical
 * (id + width + value).
 *
 * ru-ru branch (D27 locked): keeps the 6-key footer — symbols 1.5, language 1,
 * emoji 1, space **6.5**, period 1 (with @ as alternates[0] before the F54
 * cells , ! ? : ; ( )), mic 2.
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
            // T11: 14 assets slim to the 5-key footer; ru-ru keeps the D27 6-key form.
            if (id == "ru-ru") {
                checkRuFooter(base, "$id/base")
                checkRuFooter(shift, "$id/shift")
            } else {
                checkNewFooter(base, "$id/base")
                checkNewFooter(shift, "$id/shift")
            }
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

    /** T11 (14 assets): 5-key footer — period up to r2, space 6.5 -> 7.5 (Σ 13). */
    private fun checkNewFooter(rows: List<List<LayoutKey>>, ctx: String) {
        val r3 = rows[3]
        assertEquals("$ctx r3 sum 13", 13.0, r3.sumOf { it.width }, 0.0001)
        assertEquals("$ctx r3 exactly 5 keys (T11: period up to r2)", 5, r3.size)
        assertEquals(
            "$ctx footer ids (T11)",
            listOf("symbols", "language", "emoji", "space", "mic"),
            r3.map { it.id },
        )
        for ((id, w) in listOf("symbols" to 1.5, "language" to 1.0, "emoji" to 1.0, "space" to 7.5)) {
            assertEquals("$ctx $id = $w", w, r3.first { it.id == id }.width, 0.0001)
        }
        assertEquals("$ctx mic = 2 (last)", 2.0, r3.last().width, 0.0001)
        assertEquals("$ctx mic is the last key", "mic", r3.last().id)
        assertTrue(
            "$ctx NO period in r3 (T11: the period key sits in r2 before ⏎)",
            r3.none { it.id == "period" },
        )
        // NO enter in r3
        assertTrue("$ctx no enter in r3", r3.none { it.value == KeyActions.ENTER })
        // enter exactly ONCE in r2, nowhere else in the layer
        assertEquals("$ctx enter exactly 1x in r2", 1, rows[2].count { it.value == KeyActions.ENTER })
        val enterElsewhere = rows.filterIndexed { i, _ -> i != 2 }.flatten().count { it.value == KeyActions.ENTER }
        assertEquals("$ctx enter nowhere except r2", 0, enterElsewhere)
    }

    /** ru-ru (D27): the 6-key footer stays — space 6.5, period with @ prefix. */
    private fun checkRuFooter(rows: List<List<LayoutKey>>, ctx: String) {
        val r3 = rows[3]
        assertEquals("$ctx r3 sum 13", 13.0, r3.sumOf { it.width }, 0.0001)
        assertEquals("$ctx r3 exactly 6 keys (D27)", 6, r3.size)
        val byId = r3.associateBy { it.id }
        for ((id, w) in listOf("symbols" to 1.5, "language" to 1.0, "emoji" to 1.0, "space" to 6.5)) {
            assertEquals("$ctx $id = $w", w, byId.getValue(id).width, 0.0001)
        }
        assertEquals("$ctx period = 1", 1.0, byId.getValue("period").width, 0.0001)
        assertEquals("$ctx mic = 2", 2.0, byId.getValue("mic").width, 0.0001)
        assertEquals("$ctx mic is the last key", "mic", r3.last().id)
        val period = byId.getValue("period")
        assertEquals("$ctx period has 8 alternates (@ + F54)", 8, period.alternates.size)
        assertEquals(
            "$ctx period cell order @ , ! ? : ; ( )",
            listOf("@", ",", "!", "?", ":", ";", "(", ")"),
            period.alternates,
        )
        assertEquals("no cursor actions in the period key", 0, period.cellActions.size)
        assertTrue("$ctx no enter in r3", r3.none { it.value == KeyActions.ENTER })
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
            assertEquals("$layerName: F54 standard cells behind @", listOf(",", "!", "?", ":", ";", "(", ")"), period.alternates.drop(1))
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
