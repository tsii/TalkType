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
 * T11 (docs/PLAN_T11_T12.md §2, decision D1 = VARIANT B): base/shift r2
 * boundary grid — 8 equal cells (7 letters + the NEW period key before ⏎)
 * on the r1 boundary raster, ⇧ and ⏎ take the symmetric remainder (⇧ = ⏎).
 *
 * TARGET CONSTANTS (variant B, replaces the T8 Y/M centering):
 *   - 13 seven-key assets (r1 = 0.5 margin + 9 letters at 0.94444 + ⌫ 1.0,
 *     boundaries g_i = 0.5 + i·0.94444): r2 = ⇧ 1.22224 + 8 cells à 0.94444
 *     + ⏎ 1.22224 (px: 132 + 8×102 + 132 = 1080, Σ 10 exact).
 *   - cs-cz (B', 6-key home row at 0.85): r2 = ⇧ 2.025 + 7 cells à 0.85
 *     (6 letters + period) + ⏎ 2.025 (Σ 10 exact).
 *   - period key (base AND shift): id 'period', kind text, value '.',
 *     cell width; alternates = the EXACT F54 cells , ! ? : ; ( ) — the
 *     footer period's menu moves up with it.
 *   - INVARIANT (variant B): the FULL r2 center chain is symmetric about
 *     5.0 — every center pair c[i] + c[n-1-i] = 10 (⇧ = ⏎).
 *   - ru-ru: D27 exception, untouched (Σ 12, no period key in r2, 6-key
 *     footer with @ on period).
 *   - M key = the LAST LETTER of r2 (lookup excludes id 'period'); it keeps
 *     its @ alternate (T8) in base AND shift.
 * constant tolerance ±1e-3 (order T11).
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

    /** Running center per key (start at [start], unit grid u). */
    private fun centers(keys: List<LayoutKey>, start: Double): List<Double> {
        var x = start
        val out = mutableListOf<Double>()
        for (k in keys) {
            out.add(x + k.width / 2)
            x += k.width
        }
        return out
    }

    @Test
    fun sevenLetterRows_symmetricBoundaryGrid_periodBeforeEnter() {
        for (id in sevenLetterAssets) {
            for (ln in listOf("base", "shift")) {
                val rows = asset(id).layers.getValue(ln).rows
                val r2 = rows[2]
                assertEquals("$id/$ln Σ r2 = 10", 10.0, r2.sumOf { it.width }, 0.001)
                assertEquals("$id/$ln ⇧ 1.22224 (variant B: ⇧ = ⏎)", 1.22224, r2[0].width, 0.001)
                val texts = r2.filter { it.kind == KeyKind.TEXT }
                assertEquals("$id/$ln 8 text keys (7 letters + period)", 8, texts.size)
                for (k in texts) {
                    assertEquals("$id/$ln/${k.id} at 0.94444", 0.94444, k.width, 0.001)
                }
                val enter = r2.last { it.kind == KeyKind.ACTION && it.value == KeyActions.ENTER }
                assertEquals("$id/$ln ⏎ 1.22224 (variant B: ⇧ = ⏎)", 1.22224, enter.width, 0.001)
                assertTrue(
                    "$id/$ln @ out of r2",
                    r2.none { it.kind == KeyKind.TEXT && it.value == "@" },
                )
                // T11: period key directly before ⏎ with the F54 menu.
                val period = r2.last { it.kind == KeyKind.TEXT }
                assertEquals("$id/$ln period is the last text key before ⏎", "period", period.id)
                assertEquals("$id/$ln period value '.'", ".", period.value)
                assertEquals(
                    "$id/$ln period F54 menu , ! ? : ; ( )",
                    listOf(",", "!", "?", ":", ";", "(", ")"),
                    period.alternates,
                )
                assertNull("$id/$ln/period no hint", period.hint)
                // T11 invariant (variant B): center chain symmetric about 5.0.
                val c = centers(r2, 0.0)
                for (i in c.indices) {
                    assertEquals("$id/$ln center pair c[$i]+c[${c.size - 1 - i}] = 10",
                        10.0, c[i] + c[c.size - 1 - i], 0.001)
                }
            }
        }
    }

    @Test
    fun csAusnahme_siebenerZellen_mitPunkt_Symmetrisch() {
        val a = asset("cs-cz")
        for (ln in listOf("base", "shift")) {
            val r2 = a.layers.getValue(ln).rows[2]
            assertEquals("cs-cz/$ln Σ r2 = 10", 10.0, r2.sumOf { it.width }, 0.001)
            assertEquals("cs-cz/$ln ⇧ 2.025 (B': ⇧ = ⏎)", 2.025, r2[0].width, 0.001)
            val texts = r2.filter { it.kind == KeyKind.TEXT }
            assertEquals("cs-cz/$ln 7 text keys (6 letters + period)", 7, texts.size)
            for (k in texts) {
                assertEquals("cs-cz/$ln/${k.id} at 0.85", 0.85, k.width, 0.001)
            }
            val enter = r2.last { it.kind == KeyKind.ACTION && it.value == KeyActions.ENTER }
            assertEquals("cs-cz/$ln ⏎ 2.025 (B': ⇧ = ⏎)", 2.025, enter.width, 0.001)
            assertTrue(
                "cs-cz/$ln @ out of r2",
                r2.none { it.kind == KeyKind.TEXT && it.value == "@" },
            )
            val period = r2.last { it.kind == KeyKind.TEXT }
            assertEquals("cs-cz/$ln period before ⏎", "period", period.id)
            assertEquals(
                "cs-cz/$ln period F54 menu , ! ? : ; ( )",
                listOf(",", "!", "?", ":", ";", "(", ")"),
                period.alternates,
            )
            // T11 invariant (variant B): center chain symmetric about 5.0.
            val c = centers(r2, 0.0)
            for (i in c.indices) {
                assertEquals("cs-cz/$ln center pair c[$i]+c[${c.size - 1 - i}] = 10",
                    10.0, c[i] + c[c.size - 1 - i], 0.001)
            }
        }
    }

    @Test
    fun ruAusnahme_d27VollreiheUnangetastet() {
        val a = asset("ru-ru")
        for (ln in listOf("base", "shift")) {
            val rows = a.layers.getValue(ln).rows
            val r2 = rows[2]
            assertEquals("ru-ru/$ln Σ r2 = 12 (D27)", 12.0, r2.sumOf { it.width }, 0.001)
            assertEquals("ru-ru/$ln ⇧ 1,0 (D27)", 1.0, r2[0].width, 0.001)
            val letters = r2.filter { it.kind == KeyKind.TEXT }
            assertEquals("ru-ru/$ln 10 letters at 1.0 (D27)", 10, letters.size)
            for (k in letters) assertEquals("ru-ru/$ln/${k.id} at 1.0", 1.0, k.width, 0.001)
            val enter = r2.last { it.kind == KeyKind.ACTION && it.value == KeyActions.ENTER }
            assertEquals("ru-ru/$ln ⏎ 1.0 (D27)", 1.0, enter.width, 0.001)
            assertTrue(
                "ru-ru/$ln no @ in r2 (D27, never present)",
                r2.none { it.kind == KeyKind.TEXT && it.value == "@" },
            )
            assertTrue(
                "ru-ru/$ln no period key in r2 (T11 does not apply, D27)",
                r2.none { it.id == "period" },
            )
            // T11 does not slim the ru footer either: 6-key footer with period.
            val r3 = rows[3]
            assertEquals(
                "ru-ru/$ln footer keeps the 6-key D27 form",
                listOf("symbols", "language", "emoji", "space", "period", "mic"),
                r3.map { it.id },
            )
        }
        // Nothing invented: ru does not know @ on the letter layers — the
        // M key (id em) carries NO @ alternate (reachability via the
        // symbol layer).
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
                // M lookup EXCLUDES the period key: the M key is the last
                // LETTER of r2, not the last text key (period follows it).
                val m = r2.last { it.kind == KeyKind.TEXT && it.id != "period" }
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
                assertTrue("$id/$ln period is NOT the M key", m.id != "period")
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
