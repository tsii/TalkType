package de.voicekeyboard.keyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * T20/S1 (docs/DESIGN_STOCK_LOOK.md §3+§4): Höhenvertrag OHNE Row-Drop.
 *
 * Korrektur von T19: „Row-Drop ist kein akzeptierter Fix" — bei kleinem Angebot
 * (650 px = 248 dp) dürfen Reihen weder halb noch ganz verschwinden; ALLE Reihen
 * werden skaliert. Skala = min(1, (Angebot − Status − Navigation) / Bedarf).
 *
 * Zielwerte nach Sol (density 420 = 2,625 px/dp): 268 dp ohne Navigation = 704 px,
 * 276 dp mit Navigation = 724,5 px → ganzzahlig höchstens 725 px. Statuszeile
 * 48 dp = 126 px und Navigationsinset (nominal 8 dp = 21 px) werden JE GENAU
 * EINMAL vom Angebot abgezogen — Root und Child dürfen nicht beide abziehen.
 *
 * Rechenbeispiele (55-dp-Reihen = 144 px, Gap 0 Stock-Stil):
 *  - 829 px, Nav 21 px: Keys-Budget 578 px ≥ Bedarf 576 px → scale 1, 4 Reihen,
 *    Gesamthöhe 723 px ≤ 725 px — nichts abgeschnitten.
 *  - 650 px, Nav 21 px: Keys-Budget 503 px → scale 0,873 → 125 px je Reihe
 *    (≈ 47,9 dp, wie Sol-Tabelle), ALLE 4 Reihen, Gesamthöhe 647 px ≤ 650 px.
 */
class KeyboardHeightTest {

    // density 420 = 2,625 px/dp — Vorgaben aus DESIGN_STOCK_LOOK §3:
    private val status = 126        // 48 dp Statuszeile
    private val nav = 21            // 8 dp nominale Navigation
    private val row4 = 144          // 55 dp je Reihe (4 Reihen, Stock)
    private val row5 = 115          // 44 dp je Reihe (PT: 5 Reihen, Stock)
    private val maxWithNav = 725    // 276 dp mit Navigation
    private val maxNoNav = 704      // 268 dp ohne Navigation
    private val minKey = 84         // 32 dp Mindest-Tastenhöhe (eigener Parameter, nicht 0)

    private fun fit(offered: Int, rows: Int, maxPx: Int = maxWithNav, navPx: Int = nav) =
        KeyboardHeight.fitHeight(
            offeredPx = offered,
            rows = rows,
            desiredRowPx = if (rows >= 5) row5 else row4,
            desiredRowGapPx = 0, // Stock: 0 dp Reihenlücke
            statusLinePx = status,
            navInsetPx = navPx,
            maxPx = maxPx,
            minKeyHeightPx = minKey,
        )

    // 1. Pflichtfälle 650/829 px, 4 und 5 Reihen (parametrisiert):
    //    ALLE Reihen erhalten, unterer Rand im Budget, Status/Nav genau je einmal.
    @Test
    fun `650 und 829 px - alle Reihen erhalten, Rand im Budget, Status und Nav je genau einmal`() {
        for (offered in intArrayOf(650, 829)) {
            for (rows in intArrayOf(4, 5)) {
                val f = fit(offered, rows)
                assertEquals("Row-Drop verboten bei $offered px / $rows Reihen", rows, f.rows)
                assertEquals(
                    "Status/Nav müssen GENAU JE EINMAL einbezogen sein ($offered/$rows)",
                    f.keysHeightPx, f.viewHeightPx - status - nav,
                )
                assertEquals(
                    "Keys-Höhe muss dem Raster entsprechen ($offered/$rows)",
                    rows * f.rowPx + (rows - 1) * f.rowGapPx, f.keysHeightPx,
                )
                assertTrue("Rand über Budget bei $offered/$rows", f.viewHeightPx <= minOf(offered, maxWithNav))
                assertTrue("Reihenhöhe muss > 0 sein ($offered/$rows)", f.rowPx > 0)
            }
        }
    }

    // 2. Zielwerte: 268 dp (704 px) ohne Navigation, 276 dp (725 px) mit —
    //    bei 829 px Angebot darf NICHTS abgeschnitten werden (scale 1).
    @Test
    fun `Zielwerte 704 px ohne Nav und 725 px mit Nav - bei 829 px nichts abgeschnitten`() {
        val withNav = fit(829, 4)
        assertEquals("bei 829 px muss unskaliert bleiben", 1f, withNav.scale, 0.0001f)
        assertEquals(row4, withNav.rowPx)
        assertEquals(723, withNav.viewHeightPx) // 126 + 21 + 576
        assertTrue(withNav.viewHeightPx <= maxWithNav)

        val noNav = fit(829, 4, maxPx = maxNoNav, navPx = 0)
        assertEquals(1f, noNav.scale, 0.0001f)
        assertEquals(row4, noNav.rowPx)
        assertEquals(702, noNav.viewHeightPx) // 126 + 0 + 576
        assertTrue(noNav.viewHeightPx <= maxNoNav)
    }

    // 3. REGRESSIONSCHUTZ: der alte T19-Fall „letzte Reihe entfällt bei 650 px"
    //    muss jetzt rot werden — die Reihe wird skaliert, NIEMALS entfernt.
    @Test
    fun `T19-Row-Drop verboten - letzte Reihe wird skaliert statt entfernt`() {
        val f = fit(650, 4)
        assertEquals("alle 4 Reihen (T19 lieferte nur 3)", 4, f.rows)
        assertTrue("Reihe muss verkleinert werden, nicht verworfen", f.rowPx in 1 until row4)
        assertEquals(125, f.rowPx)   // ≈ 47,9 dp — Sol-Tabelle „47,90 dp je Reihe"
        assertEquals(647, f.viewHeightPx)
        // T19-Ergebnis war 3 Reihen zu 569 px — explizit KEIN gültiger Zustand mehr:
        assertTrue("T19-Altzustand (3-Reihen-Raster) darf nicht zurückkommen",
            f.viewHeightPx - status - nav != 3 * row4)

        // S4: der tote Kompatibilitäts-Eingang (heightFor/lastFit, globale Bridge)
        // ist ENTFERNT — derselbe Pflichtzustand gilt direkt über fitHeight mit
        // den alten Produktionsmaßen (52-dp-Reihen, 6-dp-Lücke, Status 48 dp):
        val legacy = KeyboardHeight.fitHeight(
            offeredPx = 650, rows = 4, desiredRowPx = 137, desiredRowGapPx = 16,
            statusLinePx = 126, navInsetPx = 0, maxPx = 724, minKeyHeightPx = 82,
        )
        assertEquals("alle 4 Reihen auch mit den Altmaßen", 4, legacy.rows)
        assertEquals(120, legacy.rowPx)
        assertEquals(14, legacy.rowGapPx)
        assertEquals("126 + 4*120 + 3*14 — alle 4 Reihen, NICHT 569 (3 Reihen)",
            648, legacy.viewHeightPx)
    }

    // 4. Extrem kleines Angebot (400 px): Mindest-Tastenhöhe (eigener Parameter)
    //    wird GEHALTEN und das unterschrittene Angebot GEMELDET, nicht verschluckt.
    @Test
    fun `extrem kleines Angebot 400 px - Mindesttastenhoehe gehalten, Defizit gemeldet`() {
        val f = fit(400, 4)
        assertEquals("Mindest-Tastenhöhe muss gewinnen", minKey, f.rowPx)
        assertTrue("minKeyHeld muss gemeldet sein", f.minKeyHeld)
        assertEquals(483, f.viewHeightPx)   // 126 + 21 + 4*84
        assertEquals("Defizit muss gemeldet werden, nicht verschluckt", 83, f.deficitPx)
    }

    // 5. Mindest-Tastenhöhe ist ein Parameter und darf NICHT 0 sein (Vertrag).
    @Test
    fun `Mindesttastenhoehe ist Parameter und darf nicht 0 sein`() {
        try {
            KeyboardHeight.fitHeight(650, 4, row4, 0, status, nav, maxWithNav, minKeyHeightPx = 0)
            fail("minKeyHeightPx = 0 muss abgelehnt werden")
        } catch (e: IllegalArgumentException) {
            // erwartet
        }
    }

    // 6. „Genau einmal"-Regression: ein Budget, das EXAKT für Bedarf+Status+Nav
    //    reicht, muss unskaliert bleiben — doppelte Abzüge würden scale < 1 liefern.
    @Test
    fun `Status und Navigation werden je genau einmal abgezogen`() {
        val exact = KeyboardHeight.fitHeight(
            offeredPx = status + nav + 4 * row4, // 723 px — exakt passend
            rows = 4, desiredRowPx = row4, desiredRowGapPx = 0,
            statusLinePx = status, navInsetPx = nav,
            maxPx = 100_000, minKeyHeightPx = minKey,
        )
        assertEquals("doppelter Abzug hätte skaliert", 1f, exact.scale, 0.0001f)
        assertEquals(723, exact.viewHeightPx)
        assertEquals(0, exact.deficitPx)
    }

    // 7. Beschriftungsgröße skaliert mit (untere Grenze ein Parameter).
    @Test
    fun `Beschriftungsgroesse skaliert mit, untere Grenze gilt`() {
        val scaled = KeyboardHeight.fitHeight(
            650, 4, row4, 0, status, nav, maxWithNav, minKey,
            desiredLabelPx = 63, minLabelPx = 40,
        )
        assertEquals(55, scaled.labelPx) // floor(63 * 0,873) = 55 — skaliert, ≥ Untergrenze
        val held = KeyboardHeight.fitHeight(
            400, 4, row4, 0, status, nav, maxWithNav, minKey,
            desiredLabelPx = 63, minLabelPx = 55,
        )
        assertEquals("Untergrenze muss gelten", 55, held.labelPx)
        val full = KeyboardHeight.fitHeight(
            829, 4, row4, 0, status, nav, maxWithNav, minKey,
            desiredLabelPx = 63, minLabelPx = 40,
        )
        assertEquals("bei scale 1 bleibt die Beschriftung unverändert", 63, full.labelPx)
    }

    // 8. Determinismus: gleiche Eingaben → gleiches Ergebnis (kein Drift).
    @Test
    fun `fit ist deterministisch`() {
        for (offered in intArrayOf(400, 500, 650, 829)) {
            assertEquals(
                KeyboardHeight.fitHeight(offered, 4, row4, 0, status, nav, maxWithNav, minKey),
                KeyboardHeight.fitHeight(offered, 4, row4, 0, status, nav, maxWithNav, minKey),
            )
        }
    }
}
