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
 * T8 (v0.1.38, Y-row alignment base/shift): @ key out of r2 (user
 * confirmed), ⇧ narrower, ⏎ wider — Y centered below A/S, M centered below K/L.
 *
 * DERIVATION of the target constants (7-key assets, r1 = 0.5 margin + 9 letters at
 * 0.94444 + ⌫ 1.0 ⇒ center of the i-th r1 letter c1(i) = 0.5 + (i+0.5)·0.94444):
 *   required  c(r2[0]) = (c1(0)+c1(1))/2 = 1.44444   (Y below A/S)
 *              c(r2[6]) = (c1(7)+c1(8))/2 = 8.05556   (M below K/L)
 *   with r2 = ⇧ + 7 letters at w + ⏎:
 *   c(r2[0]) = ⇧ + w/2,  c(r2[6]) = ⇧ + 6.5·w
 *   ⇒ w = (8.05556 − 1.44444)/6 = 1.10185
 *     ⇧ = 1.44444 − w/2 = 0.89352
 *     ⏎ = 10 − ⇧ − 7w = 1.39352
 * constant tolerance ±1e-3 (order T8).
 *
 *  - the 13 7-key assets (da-dk, de-de, el-gr, en-us, es-es, fi-fi, fr-fr, it-it,
 *    nl-nl, pl-pl, pt-pt, sv-se, tr-tr — center invariant; the order's text
 *    list named 12, da-dk/el-gr have the same 7-key structure and are
 *    measured too, see report): base AND shift r2 identical.
 *  - cs-cz (6-key x..m, no y): @ out, ⇧ 1.88889 + 6 at 1.03704 stay,
 *    ⏎ = 10 − 1.88889 − 6·1.03704 = 1.88887 (the order named 1.88875 —
 *    difference 0.00012 u ≈ 0.01 px, within ±1e-3); M centering below K/L
 *    is arithmetically unreachable in the 6-key grid ⇒ documented exception.
 *  - ru-ru: r2 without @ (D27 full row) — untouched, exception documented;
 *    no @ alternate invented (ru knows @ only via the symbol layer).
 *  - @ stays reachable: per asset the M key (last r2 letter,
 *    id per asset: m respectively el-gr μ) carries alternates ["@"] (≤ 8, no hint).
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
    fun sevenLetterRows_YCenteredUnderAS_MCenteredUnderKL() {
        for (id in sevenLetterAssets) {
            for (ln in listOf("base", "shift")) {
                val rows = asset(id).layers.getValue(ln).rows
                val r2 = rows[2]
                assertEquals("$id/$ln Σ r2 = 10", 10.0, r2.sumOf { it.width }, 0.001)
                assertEquals("$id/$ln ⇧ 0.89352 (narrower)", 0.89352, r2[0].width, 0.001)
                val letters = r2.filter { it.kind == KeyKind.TEXT }
                assertEquals("$id/$ln 7 letters", 7, letters.size)
                for (k in letters) {
                    assertEquals("$id/$ln/${k.id} at 1.10185", 1.10185, k.width, 0.001)
                }
                val enter = r2.last { it.kind == KeyKind.ACTION && it.value == KeyActions.ENTER }
                assertEquals("$id/$ln ⏎ 1.39352 (wider)", 1.39352, enter.width, 0.001)
                assertTrue(
                    "$id/$ln @ out of r2 (T8)",
                    r2.none { it.kind == KeyKind.TEXT && it.value == "@" },
                )
                // center invariant: r1 = 0.5 margin + 9 letters + ⌫.
                val r1letters = rows[1].filter { it.kind == KeyKind.TEXT }
                assertEquals("$id/$ln 9 letters in r1", 9, r1letters.size)
                val c1 = centers(r1letters, 0.5)
                val c2 = centers(letters, r2[0].width)
                assertEquals("$id/$ln Y center = (A+S)/2", (c1[0] + c1[1]) / 2, c2[0], 0.001)
                assertEquals("$id/$ln M center = (K+L)/2", (c1[7] + c1[8]) / 2, c2[6], 0.001)
            }
        }
    }

    @Test
    fun csAusnahme_sechserReihe_AtRaus_EnterBreiter() {
        val a = asset("cs-cz")
        for (ln in listOf("base", "shift")) {
            val r2 = a.layers.getValue(ln).rows[2]
            assertEquals("cs-cz/$ln Σ r2 = 10", 10.0, r2.sumOf { it.width }, 0.001)
            assertEquals("cs-cz/$ln ⇧ 1.88889 stays", 1.88889, r2[0].width, 0.001)
            val letters = r2.filter { it.kind == KeyKind.TEXT }
            assertEquals("cs-cz/$ln 6 letters (x..m)", 6, letters.size)
            for (k in letters) {
                assertEquals("cs-cz/$ln/${k.id} at 1.03704 stays", 1.03704, k.width, 0.001)
            }
            val enter = r2.last { it.kind == KeyKind.ACTION && it.value == KeyActions.ENTER }
            assertEquals("cs-cz/$ln ⏎ 1.88889 (planner approval: ⇧=⏎ symmetric, Σ 10,00002 within tolerance)",
                1.88889, enter.width, 0.001)
            assertTrue(
                "cs-cz/$ln @ out of r2 (T8)",
                r2.none { it.kind == KeyKind.TEXT && it.value == "@" },
            )
        }
        // M centering below K/L: in the 6-key grid (y sits on the HOME row, r2
        // starts at x) arithmetically unreachable — documented exception
        // (order T8): NOT centered, the structure stays.
        val r2 = a.layers.getValue("base").rows[2]
        val c1 = centers(a.layers.getValue("base").rows[1].filter { it.kind == KeyKind.TEXT }, 0.5)
        val c2 = centers(r2.filter { it.kind == KeyKind.TEXT }, r2[0].width)
        assertTrue(
            "cs-cz x NOT centered below A/S (documented 6-key exception)",
            kotlin.math.abs(c2[0] - (c1[0] + c1[1]) / 2) > 0.001,
        )
    }

    @Test
    fun ruAusnahme_d27VollreiheUnangetastet() {
        val a = asset("ru-ru")
        for (ln in listOf("base", "shift")) {
            val r2 = a.layers.getValue(ln).rows[2]
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
        }
        // Nothing invented: ru does not know @ on the letter layers — the
        // M key (id em) carries NO @ alternate (reachability via the
        // symbol layer, see report).
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
                val m = r2.last { it.kind == KeyKind.TEXT }
                assertTrue(
                    "$id/$ln M key '${m.id}' carries @ in its long-press menu (T8: @ out ⇒ alternate)",
                    "@" in m.alternates,
                )
                assertTrue("$id/$ln/${m.id} ≤ 8 alternates", m.alternates.size <= 8)
                assertNull("$id/$ln/${m.id} no hint (T8)", m.hint)
                assertEquals(
                    "$id/$ln/${m.id} no duplicates",
                    m.alternates.size, m.alternates.toSet().size,
                )
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
