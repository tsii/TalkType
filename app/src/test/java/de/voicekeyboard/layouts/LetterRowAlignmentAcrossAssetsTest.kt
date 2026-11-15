package de.voicekeyboard.layouts

import de.voicekeyboard.layouts.KeyActions
import de.voicekeyboard.layouts.KeyKind
import de.voicekeyboard.layouts.LayoutParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * V0.1.50/V0.1.51 (docs/PLAN_V0151.md §A): base/shift row alignment across
 * the assets.
 *
 * TARGET STATE (13 seven-key assets):
 *   - r1 = margin 0.5 + 9 letters à 1.0 (102.86 px at Σ 10.5) + ⌫ 1.0
 *     (102.86 px — ONE FULL letter unit, user finding V0.1.51);
 *   - r2 = ⇧ 1.5 (162 px) + 7 letters à 1.0 (108 px) + ⏎ 1.5 (162 px)
 *     — ⏎ == ⇧ (symmetric wings); Σ r2 = 10 puts the M CENTER on the
 *     i|o gap of r0 (8.0u = 864 px), see GeometryV0149Test.mCenteredUnderTheIAndOGap;
 *   - period: GONE from r2 — it moved back to the footer between space and
 *     mic (F54 menu , ! ? : ; ( ) unchanged); space 7.5 → 6.5 (Σ13 stays);
 *   - alignment invariant: the letters of BOTH rows sit on the SAME cell
 *     raster — Z1 letter width == Z2 letter width (both 1.0).
 *   - cs-cz (10-key home row): r1 = 0.5 + 10 à 1.0 + ⌫ 1.0 (Σ 11.5);
 *     r2 = ⇧ 2.0 + 6 à 1.0 + ⏎ 2.0 (unchanged; ⏎ == ⇧).
 *   - ru-ru (exception row): r1 = 0.5 + 11 à 1.0 + ⌫ 1.0 (Σ 12.5);
 *     r2 = ⇧ 1.5 + 10 à 0.9 + ⏎ 1.5 (Σ 12; ⏎ == ⇧); 6-key footer (period
 *     with @ as alternates[0]).
 * constant tolerance ±1e-3 (order V0.1.51).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LetterRowAlignmentAcrossAssetsTest {

    private val sevenLetterAssets = listOf(
        "da-dk", "de-de", "el-gr", "en-us", "es-es", "fi-fi", "fr-fr", "it-it",
        "nl-nl", "pl-pl", "pt-pt", "sv-se", "tr-tr",
    )

    private fun asset(id: String) =
        LayoutParser.parse(java.io.File("src/main/assets/layouts/$id.json").readText())

    @Test
    fun sevenLetterRows_v0151Grid_alignmentInvariant() {
        for (id in sevenLetterAssets) {
            for (ln in listOf("base", "shift")) {
                val rows = asset(id).layers.getValue(ln).rows
                val r1 = rows[1]
                val r2 = rows[2]

                // r1: margin 0.5 + 9 letters à 1.0 + ⌫ 1.0 at the END (V0.1.51).
                assertEquals("$id/$ln Σ r1 = 10.5", 10.5, r1.sumOf { it.width }, 0.001)
                assertEquals("$id/$ln r1 margin 0.5", 0.5, r1[0].width, 0.001)
                assertEquals("$id/$ln r1[0] is the margin", KeyKind.MARGIN, r1[0].kind)
                val r1Letters = r1.filter { it.kind == KeyKind.TEXT }
                assertEquals("$id/$ln 9 letters in r1", 9, r1Letters.size)
                for (k in r1Letters) {
                    assertEquals("$id/$ln/${k.id} r1 at 1.0 (102.86 px)", 1.0, k.width, 0.001)
                }
                val bs = r1.last()
                assertEquals("$id/$ln ⌫ at the r1 end, width 1.0 (102.86 px)", 1.0, bs.width, 0.001)
                assertEquals(
                    "$id/$ln ⌫ == r1 letter width (V0.1.51: full unit)",
                    r1Letters.first().width, bs.width, 1e-6,
                )
                assertEquals("$id/$ln ⌫ is an action key", KeyKind.ACTION, bs.kind)
                assertEquals("$id/$ln ⌫ value", KeyActions.BACKSPACE, bs.value)
                assertEquals("$id/$ln exactly ONE ⌫ in r1 (§0 variant A)",
                    1, r1.count { it.value == KeyActions.BACKSPACE })

                // r2: ⇧ 1.5 + 7 letters à 1.0 + ⏎ 1.5 (== ⇧, symmetric wings).
                assertEquals("$id/$ln Σ r2 = 10", 10.0, r2.sumOf { it.width }, 0.001)
                assertEquals("$id/$ln ⇧ 1.5", 1.5, r2[0].width, 0.001)
                assertEquals("$id/$ln r2[0] is ⇧", KeyActions.SHIFT, r2[0].value)
                val r2Letters = r2.filter { it.kind == KeyKind.TEXT }
                assertEquals("$id/$ln 7 letters in r2 (period moved to the footer)", 7, r2Letters.size)
                for (k in r2Letters) {
                    assertEquals("$id/$ln/${k.id} r2 at 1.0 (108 px)", 1.0, k.width, 0.001)
                }
                val enter = r2.last { it.kind == KeyKind.ACTION && it.value == KeyActions.ENTER }
                assertEquals("$id/$ln ⏎ 1.5 (162 px, V0.1.50)", 1.5, enter.width, 0.001)
                assertEquals(
                    "$id/$ln ⏎ == ⇧ (symmetric wings, V0.1.50)",
                    r2[0].width, enter.width, 1e-3,
                )
                assertTrue(
                    "$id/$ln @ out of r2",
                    r2.none { it.kind == KeyKind.TEXT && it.value == "@" },
                )
                assertTrue(
                    "$id/$ln no period in r2 (V0.1.49: footer)",
                    r2.none { it.id == "period" },
                )

                // V0.1.50 alignment invariant: the letters of BOTH rows sit
                // on the SAME cell raster (both 1.0).
                assertEquals(
                    "$id/$ln Z1 letter width == Z2 letter width (common raster)",
                    r1Letters.first().width, r2Letters.first().width, 0.05,
                )

                // Period moved DOWN to the footer (between space and mic) with
                // the F54 menu unchanged; space 7.5 → 6.5.
                val r3 = rows[3]
                assertEquals(
                    "$id/$ln footer ids",
                    listOf("symbols", "language", "emoji", "space", "period", "mic"),
                    r3.map { it.id },
                )
                val period = r3.single { it.id == "period" }
                assertEquals("$id/$ln period value '.'", ".", period.value)
                assertEquals(
                    "$id/$ln period F54 menu , ! ? : ; ( )",
                    listOf(",", "!", "?", ":", ";", "(", ")"),
                    period.alternates,
                )
                assertNull("$id/$ln/period no hint", period.hint)
                assertEquals("$id/$ln space 6.5", 6.5, r3.single { it.id == "space" }.width, 0.001)
                assertEquals("$id/$ln footer Σ 13", 13.0, r3.sumOf { it.width }, 0.001)
            }
        }
    }

    @Test
    fun csAusnahme_zehnerHeimzeile_groessereR2Zellen() {
        val a = asset("cs-cz")
        for (ln in listOf("base", "shift")) {
            val rows = a.layers.getValue(ln).rows
            val r1 = rows[1]
            val r2 = rows[2]
            // r1: margin 0.5 + 10 letters à 1.0 + ⌫ 1.0 (V0.1.51, Σ 11.5).
            assertEquals("cs-cz/$ln Σ r1 = 11.5", 11.5, r1.sumOf { it.width }, 0.001)
            assertEquals("cs-cz/$ln r1 margin 0.5", 0.5, r1[0].width, 0.001)
            val r1Letters = r1.filter { it.kind == KeyKind.TEXT }
            assertEquals("cs-cz/$ln 10 letters in r1", 10, r1Letters.size)
            for (k in r1Letters) {
                assertEquals("cs-cz/$ln/${k.id} r1 at 1.000", 1.0, k.width, 0.001)
            }
            assertEquals(
                "cs-cz/$ln ⌫ == letter width (V0.1.51: full unit)",
                r1Letters.first().width, r1.last().width, 1e-6,
            )
            assertEquals("cs-cz/$ln ⌫ 1.0 at the r1 end", 1.0, r1.last().width, 0.001)
            assertEquals("cs-cz/$ln ⌫ value", KeyActions.BACKSPACE, r1.last().value)
            // r2: ⇧ 2.0 + 6 letters à 1.0 + ⏎ 2.0 (unchanged; ⏎ == ⇧; the
            // raster invariant holds here too: 1.0 == 1.0).
            assertEquals("cs-cz/$ln Σ r2 = 10", 10.0, r2.sumOf { it.width }, 0.001)
            assertEquals("cs-cz/$ln ⇧ 2.0", 2.0, r2[0].width, 0.001)
            val r2Letters = r2.filter { it.kind == KeyKind.TEXT }
            assertEquals("cs-cz/$ln 6 letters in r2 (period moved to the footer)", 6, r2Letters.size)
            for (k in r2Letters) {
                assertEquals("cs-cz/$ln/${k.id} r2 at 1.000", 1.0, k.width, 0.001)
            }
            val enter = r2.last { it.kind == KeyKind.ACTION && it.value == KeyActions.ENTER }
            assertEquals("cs-cz/$ln ⏎ 2.0 (== ⇧, symmetric wings)", 2.0, enter.width, 0.001)
            assertEquals("cs-cz/$ln ⏎ == ⇧ (V0.1.50)", r2[0].width, enter.width, 1e-3)
            assertTrue(
                "cs-cz/$ln @ out of r2",
                r2.none { it.kind == KeyKind.TEXT && it.value == "@" },
            )
            assertTrue("cs-cz/$ln no period in r2", r2.none { it.id == "period" })
            val period = rows[3].single { it.id == "period" }
            assertEquals(
                "cs-cz/$ln period F54 menu , ! ? : ; ( )",
                listOf(",", "!", "?", ":", ";", "(", ")"),
                period.alternates,
            )
            assertEquals("cs-cz/$ln space 6.5", 6.5, rows[3].single { it.id == "space" }.width, 0.001)
        }
    }

    @Test
    fun ruAusnahme_backspaceHeimzeile_sum12EnterEqualsShift() {
        val a = asset("ru-ru")
        for (ln in listOf("base", "shift")) {
            val rows = a.layers.getValue(ln).rows
            val r1 = rows[1]
            val r2 = rows[2]
            // r1 (V0.1.51): margin 0.5 + 11 à 1.0 + ⌫ 1.0 (Σ 12.5) — the
            // backspace REPLACED the old m-right margin back in V0.1.49.
            assertEquals("ru-ru/$ln Σ r1 = 12.5", 12.5, r1.sumOf { it.width }, 0.001)
            assertEquals("ru-ru/$ln r1 margin 0.5", 0.5, r1[0].width, 0.001)
            assertEquals("ru-ru/$ln r1[0] is the margin", KeyKind.MARGIN, r1[0].kind)
            assertEquals(
                "ru-ru/$ln exactly ONE margin in r1 (m-right is gone)",
                1, r1.count { it.kind == KeyKind.MARGIN },
            )
            val r1Letters = r1.filter { it.kind == KeyKind.TEXT }
            assertEquals("ru-ru/$ln 11 letters in r1", 11, r1Letters.size)
            for (k in r1Letters) {
                assertEquals("ru-ru/$ln/${k.id} r1 at 1.000", 1.0, k.width, 0.001)
            }
            val bs = r1.last()
            assertEquals("ru-ru/$ln ⌫ at the r1 end", KeyActions.BACKSPACE, bs.value)
            assertEquals(
                "ru-ru/$ln ⌫ == letter width (V0.1.51: full unit)",
                r1Letters.first().width, bs.width, 1e-6,
            )
            assertEquals("ru-ru/$ln ⌫ width 1.0", 1.0, bs.width, 0.001)
            // r2 (V0.1.50): ⇧ 1.5 + 10 à 0.9 + ⏎ 1.5 → Σ12; ⏎ == ⇧.
            assertEquals("ru-ru/$ln Σ r2 = 12", 12.0, r2.sumOf { it.width }, 0.001)
            assertEquals("ru-ru/$ln ⇧ 1.5", 1.5, r2[0].width, 0.001)
            val r2Letters = r2.filter { it.kind == KeyKind.TEXT }
            assertEquals("ru-ru/$ln 10 letters in r2", 10, r2Letters.size)
            for (k in r2Letters) {
                assertEquals("ru-ru/$ln/${k.id} r2 at 0.900", 0.9, k.width, 0.001)
            }
            val enter = r2.last { it.kind == KeyKind.ACTION && it.value == KeyActions.ENTER }
            assertEquals("ru-ru/$ln ⏎ == ⇧ (symmetric wings, V0.1.50)", r2[0].width, enter.width, 1e-3)
            assertEquals("ru-ru/$ln ⏎ 1.5 (135 px at Σ12)", 1.5, enter.width, 0.001)
            assertTrue(
                "ru-ru/$ln no @ in r2 (reachable via the period menu)",
                r2.none { it.kind == KeyKind.TEXT && it.value == "@" },
            )
            assertTrue("ru-ru/$ln no period key in r2", r2.none { it.id == "period" })
            // ru keeps the 6-key footer with @ as alternates[0] on period.
            val r3 = rows[3]
            assertEquals(
                "ru-ru/$ln footer keeps the 6-key form",
                listOf("symbols", "language", "emoji", "space", "period", "mic"),
                r3.map { it.id },
            )
            assertEquals("ru-ru/$ln space 6.5", 6.5, r3.single { it.id == "space" }.width, 0.001)
            assertEquals("ru-ru/$ln @ is alternates[0] on period", "@", r3.single { it.id == "period" }.alternates.first())
        }
        // Nothing invented: ru does not know @ on the letter layers — the
        // M key (id em) carries NO @ alternate (reachability via the
        // symbol layer / the period menu).
        for (ln in listOf("base", "shift")) {
            val m = a.layers.getValue(ln).rows[2].last { it.kind == KeyKind.TEXT }
            assertTrue("ru-ru/$ln m without @ alternate (nothing invented)", "@" !in m.alternates)
        }
    }

    @Test
    fun atBleibtUeberMAlternativenErreichbar() {
        for (id in sevenLetterAssets + "cs-cz") {
            for (ln in listOf("base", "shift")) {
                val r2 = asset(id).layers.getValue(ln).rows[2]
                // The M key is the LAST LETTER of r2 — no period key in r2
                // anymore (V0.1.49), so no exclusion is needed.
                val m = r2.last { it.kind == KeyKind.TEXT }
                assertTrue(
                    "$id/$ln M key '${m.id}' carries @ in its long-press menu (@ out ⇒ alternate)",
                    "@" in m.alternates,
                )
                assertTrue("$id/$ln/${m.id} ≤ 8 alternates", m.alternates.size <= 8)
                assertNull("$id/$ln/${m.id} no hint", m.hint)
                assertEquals(
                    "$id/$ln/${m.id} no duplicates",
                    m.alternates.size, m.alternates.toSet().size,
                )
                assertTrue("$id/$ln M is not the period key", m.id != "period")
            }
            // @ reachability per asset: direct key OR alternate (here: alternate).
            val a = asset(id)
            val reachable = mutableSetOf<String>()
            for (ln in listOf("base", "shift")) {
                for (row in a.layers.getValue(ln).rows) {
                    for (k in row) {
                        if (k.kind == KeyKind.TEXT) reachable.add(k.value)
                        k.alternates.forEach { reachable.add(it) }
                    }
                }
            }
            assertTrue("$id: @ reachable on the letter layer (direct key OR alternate)",
                "@" in reachable)
        }
    }
}
