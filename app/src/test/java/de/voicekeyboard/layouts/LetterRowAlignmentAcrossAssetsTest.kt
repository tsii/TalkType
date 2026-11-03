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
 * T8 (v0.1.38, Y-Reihen-Ausrichtung base/shift): @-Taste raus aus r2 (Nutzer-
 * bestätigt), ⇧ schmaler, ⏎ breiter — Y mittig unter A/S, M mittig unter K/L.
 *
 * HERLEITUNG der Zielkonstanten (7er-Assets, r1 = 0,5-Rand + 9 Buchstaben à
 * 0,94444 + ⌫ 1,0 ⇒ Mitte des i-ten r1-Buchstabens c1(i) = 0,5 + (i+0,5)·0,94444):
 *   gefordert  c(r2[0]) = (c1(0)+c1(1))/2 = 1,44444   (Y unter A/S)
 *              c(r2[6]) = (c1(7)+c1(8))/2 = 8,05556   (M unter K/L)
 *   mit r2 = ⇧ + 7 Buchstaben à w + ⏎:
 *   c(r2[0]) = ⇧ + w/2,  c(r2[6]) = ⇧ + 6,5·w
 *   ⇒ w = (8,05556 − 1,44444)/6 = 1,10185
 *     ⇧ = 1,44444 − w/2 = 0,89352
 *     ⏎ = 10 − ⇧ − 7w = 1,39352
 * Konstanten-Toleranz ±1e-3 (Auftrag T8).
 *
 *  - 13 7er-Assets (da-dk, de-de, el-gr, en-us, es-es, fi-fi, fr-fr, it-it,
 *    nl-nl, pl-pl, pt-pt, sv-se, tr-tr — Zentren-Invariante; die Auftragstext-
 *    Liste nannte 12, da-dk/el-gr haben dieselbe 7er-Struktur und sind
 *    mitgemessen, s. Report): base UND shift r2 identisch.
 *  - cs-cz (6er x..m, kein y): @ raus, ⇧ 1,88889 + 6 à 1,03704 bleiben,
 *    ⏎ = 10 − 1,88889 − 6·1,03704 = 1,88887 (Auftrag nannte 1,88875 —
 *    Differenz 0,00012 u ≈ 0,01 px, innerhalb ±1e-3); M-Mittigkeit unter K/L
 *    ist im 6er-Raster arithmetisch unerreichbar ⇒ dokumentierte Ausnahme.
 *  - ru-ru: r2 ohne @ (D27-Vollreihe) — unangetastet, Ausnahme dokumentiert;
 *    kein @-Alternate erfunden (ru kennt @ nur über die Symbolebene).
 *  - @ bleibt erreichbar: je Asset trägt die M-Taste (letzter r2-Buchstabe,
 *    id je Asset: m bzw. el-gr μ) alternates ["@"] (≤ 8, kein hint).
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

    /** Laufende Mitte je Taste (Start bei [start], Einheitsraster u). */
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
    fun siebenerReihen_YMittigUnterAS_MMittigUnterKL() {
        for (id in sevenLetterAssets) {
            for (ln in listOf("base", "shift")) {
                val rows = asset(id).layers.getValue(ln).rows
                val r2 = rows[2]
                assertEquals("$id/$ln Σ r2 = 10", 10.0, r2.sumOf { it.width }, 0.001)
                assertEquals("$id/$ln ⇧ 0,89352 (schmaler)", 0.89352, r2[0].width, 0.001)
                val letters = r2.filter { it.kind == KeyKind.TEXT }
                assertEquals("$id/$ln 7 Buchstaben", 7, letters.size)
                for (k in letters) {
                    assertEquals("$id/$ln/${k.id} à 1,10185", 1.10185, k.width, 0.001)
                }
                val enter = r2.last { it.kind == KeyKind.ACTION && it.value == KeyActions.ENTER }
                assertEquals("$id/$ln ⏎ 1,39352 (breiter)", 1.39352, enter.width, 0.001)
                assertTrue(
                    "$id/$ln @ raus aus r2 (T8)",
                    r2.none { it.kind == KeyKind.TEXT && it.value == "@" },
                )
                // Zentren-Invariante: r1 = 0,5-Rand + 9 Buchstaben + ⌫.
                val r1letters = rows[1].filter { it.kind == KeyKind.TEXT }
                assertEquals("$id/$ln 9 Buchstaben in r1", 9, r1letters.size)
                val c1 = centers(r1letters, 0.5)
                val c2 = centers(letters, r2[0].width)
                assertEquals("$id/$ln Y-Mitte = (A+S)/2", (c1[0] + c1[1]) / 2, c2[0], 0.001)
                assertEquals("$id/$ln M-Mitte = (K+L)/2", (c1[7] + c1[8]) / 2, c2[6], 0.001)
            }
        }
    }

    @Test
    fun csAusnahme_sechserReihe_AtRaus_EnterBreiter() {
        val a = asset("cs-cz")
        for (ln in listOf("base", "shift")) {
            val r2 = a.layers.getValue(ln).rows[2]
            assertEquals("cs-cz/$ln Σ r2 = 10", 10.0, r2.sumOf { it.width }, 0.001)
            assertEquals("cs-cz/$ln ⇧ 1,88889 bleibt", 1.88889, r2[0].width, 0.001)
            val letters = r2.filter { it.kind == KeyKind.TEXT }
            assertEquals("cs-cz/$ln 6 Buchstaben (x..m)", 6, letters.size)
            for (k in letters) {
                assertEquals("cs-cz/$ln/${k.id} à 1,03704 bleibt", 1.03704, k.width, 0.001)
            }
            val enter = r2.last { it.kind == KeyKind.ACTION && it.value == KeyActions.ENTER }
            assertEquals("cs-cz/$ln ⏎ 1,88889 (Planer-Freigabe: ⇧=⏎ symmetrisch, Σ 10,00002 in Toleranz)",
                1.88889, enter.width, 0.001)
            assertTrue(
                "cs-cz/$ln @ raus aus r2 (T8)",
                r2.none { it.kind == KeyKind.TEXT && it.value == "@" },
            )
        }
        // M-Mittigkeit unter K/L: im 6er-Raster (y sitzt auf der HOME-Reihe, r2
        // beginnt bei x) arithmetisch unerreichbar — dokumentierte Ausnahme
        // (Auftrag T8): NICHT zentriert, Struktur bleibt.
        val r2 = a.layers.getValue("base").rows[2]
        val c1 = centers(a.layers.getValue("base").rows[1].filter { it.kind == KeyKind.TEXT }, 0.5)
        val c2 = centers(r2.filter { it.kind == KeyKind.TEXT }, r2[0].width)
        assertTrue(
            "cs-cz x NICHT unter A/S zentriert (dokumentierte 6er-Ausnahme)",
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
            assertEquals("ru-ru/$ln 10 Buchstaben à 1,0 (D27)", 10, letters.size)
            for (k in letters) assertEquals("ru-ru/$ln/${k.id} à 1,0", 1.0, k.width, 0.001)
            val enter = r2.last { it.kind == KeyKind.ACTION && it.value == KeyActions.ENTER }
            assertEquals("ru-ru/$ln ⏎ 1,0 (D27)", 1.0, enter.width, 0.001)
            assertTrue(
                "ru-ru/$ln kein @ in r2 (D27, nie vorhanden)",
                r2.none { it.kind == KeyKind.TEXT && it.value == "@" },
            )
        }
        // Nichts erfinden: ru kennt @ auf den Buchstaben-Ebenen nicht — die
        // M-Taste (id em) trägt KEIN @-Alternate (Erreichbarkeit über die
        // Symbolebene, s. Report).
        for (ln in listOf("base", "shift")) {
            val m = a.layers.getValue(ln).rows[2].last { it.kind == KeyKind.TEXT }
            assertTrue("ru-ru/$ln m ohne @-Alternate (nichts erfinden)", "@" !in m.alternates)
        }
    }

    @Test
    fun atBleibtUeberMAlternativenErreichbar() {
        for (id in sevenLetterAssets + "cs-cz") {
            for (ln in listOf("base", "shift")) {
                val r2 = asset(id).layers.getValue(ln).rows[2]
                val m = r2.last { it.kind == KeyKind.TEXT }
                assertTrue(
                    "$id/$ln M-Taste '${m.id}' trägt @ im Langdruck-Menü (T8: @ raus ⇒ Alternate)",
                    "@" in m.alternates,
                )
                assertTrue("$id/$ln/${m.id} ≤ 8 alternates", m.alternates.size <= 8)
                assertNull("$id/$ln/${m.id} kein hint (T8)", m.hint)
                assertEquals(
                    "$id/$ln/${m.id} keine Duplikate",
                    m.alternates.size, m.alternates.toSet().size,
                )
            }
            // @-Erreichbarkeit je Asset: Direkttaste ODER Alternate (hier: Alternate).
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
            assertTrue("$id: @ auf Buchstaben-Ebene erreichbar (Direkttaste ODER Alternate)",
                "@" in reachable)
        }
    }
}
