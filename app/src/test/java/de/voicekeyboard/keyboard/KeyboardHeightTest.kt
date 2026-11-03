package de.voicekeyboard.keyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * T20/S1 (docs/DESIGN_STOCK_LOOK.md §3+§4): height contract WITHOUT a row drop.
 *
 * Correction of T19: "a row drop is not an accepted fix" — with a small
 * offer (650 px = 248 dp) rows must disappear neither half nor fully; ALL
 * rows are scaled. Scale = min(1, (offer − status − navigation) / demand).
 *
 * Target values per Sol (density 420 = 2.625 px/dp): 268 dp without navigation
 * = 704 px, 276 dp with navigation = 724.5 px → at most 725 px integer. Status
 * row 48 dp = 126 px and navigation inset (nominal 8 dp = 21 px) are subtracted
 * EXACTLY ONCE each from the offer — root and child must not both subtract.
 *
 * Calculation examples (55-dp rows = 144 px, gap 0 stock style):
 *  - 829 px, nav 21 px: keys budget 578 px ≥ demand 576 px → scale 1, 4 rows,
 *    total height 723 px ≤ 725 px — nothing cut off.
 *  - 650 px, nav 21 px: keys budget 503 px → scale 0.873 → 125 px per row
 *    (≈ 47.9 dp, per the Sol table), ALL 4 rows, total height 647 px ≤ 650 px.
 */
class KeyboardHeightTest {

    // density 420 = 2.625 px/dp — specifications from DESIGN_STOCK_LOOK §3:
    private val status = 126        // 48 dp status row
    private val nav = 21            // 8 dp nominal navigation
    private val row4 = 144          // 55 dp per row (4 rows, stock)
    private val row5 = 115          // 44 dp per row (PT: 5 rows, stock)
    private val maxWithNav = 725    // 276 dp with navigation
    private val maxNoNav = 704      // 268 dp without navigation
    private val minKey = 84         // 32 dp minimum key height (own parameter, not 0)

    private fun fit(offered: Int, rows: Int, maxPx: Int = maxWithNav, navPx: Int = nav) =
        KeyboardHeight.fitHeight(
            offeredPx = offered,
            rows = rows,
            desiredRowPx = if (rows >= 5) row5 else row4,
            desiredRowGapPx = 0, // stock: 0 dp row gap
            statusLinePx = status,
            navInsetPx = navPx,
            maxPx = maxPx,
            minKeyHeightPx = minKey,
        )

    // 1. mandatory cases 650/829 px, 4 and 5 rows (parameterized):
    //    ALL rows kept, lower edge within the budget, status/nav exactly once each.
    @Test
    fun `650 and 829 px - all rows kept, edge within budget, status and nav exactly once each`() {
        for (offered in intArrayOf(650, 829)) {
            for (rows in intArrayOf(4, 5)) {
                val f = fit(offered, rows)
                assertEquals("row drop forbidden at $offered px / $rows rows", rows, f.rows)
                assertEquals(
                    "status/nav must be included EXACTLY ONCE EACH ($offered/$rows)",
                    f.keysHeightPx, f.viewHeightPx - status - nav,
                )
                assertEquals(
                    "keys height must match the grid ($offered/$rows)",
                    rows * f.rowPx + (rows - 1) * f.rowGapPx, f.keysHeightPx,
                )
                assertTrue("edge over budget at $offered/$rows", f.viewHeightPx <= minOf(offered, maxWithNav))
                assertTrue("row height must be > 0 ($offered/$rows)", f.rowPx > 0)
            }
        }
    }

    // 2. target values: 268 dp (704 px) without navigation, 276 dp (725 px) with —
    //    at an 829 px offer NOTHING may be cut off (scale 1).
    @Test
    fun `target values 704 px without nav and 725 px with nav - nothing cut off at 829 px`() {
        val withNav = fit(829, 4)
        assertEquals("at 829 px it must stay unscaled", 1f, withNav.scale, 0.0001f)
        assertEquals(row4, withNav.rowPx)
        assertEquals(723, withNav.viewHeightPx) // 126 + 21 + 576
        assertTrue(withNav.viewHeightPx <= maxWithNav)

        val noNav = fit(829, 4, maxPx = maxNoNav, navPx = 0)
        assertEquals(1f, noNav.scale, 0.0001f)
        assertEquals(row4, noNav.rowPx)
        assertEquals(702, noNav.viewHeightPx) // 126 + 0 + 576
        assertTrue(noNav.viewHeightPx <= maxNoNav)
    }

    // 3. REGRESSION PROTECTION: the old T19 case "last row dropped at 650 px"
    //    must now turn red — the row is scaled, NEVER removed.
    @Test
    fun `T19 row drop forbidden - the last row is scaled instead of removed`() {
        val f = fit(650, 4)
        assertEquals("all 4 rows (T19 delivered only 3)", 4, f.rows)
        assertTrue("the row must be shrunk, not dropped", f.rowPx in 1 until row4)
        assertEquals(125, f.rowPx)   // ≈ 47.9 dp — Sol table "47.90 dp per row"
        assertEquals(647, f.viewHeightPx)
        // the T19 result was 3 rows at 569 px — explicitly NO valid state anymore:
        assertTrue("the old T19 state (3-row grid) must not come back",
            f.viewHeightPx - status - nav != 3 * row4)

        // S4: the dead compatibility input (heightFor/lastFit, global bridge)
        // is REMOVED — the same mandatory state holds directly via fitHeight with
        // the old production dimensions (52-dp rows, 6-dp gap, status 48 dp):
        val legacy = KeyboardHeight.fitHeight(
            offeredPx = 650, rows = 4, desiredRowPx = 137, desiredRowGapPx = 16,
            statusLinePx = 126, navInsetPx = 0, maxPx = 724, minKeyHeightPx = 82,
        )
        assertEquals("all 4 rows with the legacy sizes too", 4, legacy.rows)
        assertEquals(120, legacy.rowPx)
        assertEquals(14, legacy.rowGapPx)
        assertEquals("126 + 4*120 + 3*14 — all 4 rows, NOT 569 (3 rows)",
            648, legacy.viewHeightPx)
    }

    // 4. extremely small offer (400 px): the minimum key height (own parameter)
    //    is HELD and the underflowing offer REPORTED, not swallowed.
    @Test
    fun `extremely small offer 400 px - minimum key height held, deficit reported`() {
        val f = fit(400, 4)
        assertEquals("the minimum key height must win", minKey, f.rowPx)
        assertTrue("minKeyHeld must be reported", f.minKeyHeld)
        assertEquals(483, f.viewHeightPx)   // 126 + 21 + 4*84
        assertEquals("the deficit must be reported, not swallowed", 83, f.deficitPx)
    }

    // 5. the minimum key height is a parameter and must NOT be 0 (contract).
    @Test
    fun `minimum key height is a parameter and must not be 0`() {
        try {
            KeyboardHeight.fitHeight(650, 4, row4, 0, status, nav, maxWithNav, minKeyHeightPx = 0)
            fail("minKeyHeightPx = 0 must be rejected")
        } catch (e: IllegalArgumentException) {
            // expected
        }
    }

    // 6. "exactly once" regression: a budget that fits EXACTLY demand+status+nav
    //    must stay unscaled — double subtraction would yield scale < 1.
    @Test
    fun `status and navigation are subtracted exactly once each`() {
        val exact = KeyboardHeight.fitHeight(
            offeredPx = status + nav + 4 * row4, // 723 px — an exact fit
            rows = 4, desiredRowPx = row4, desiredRowGapPx = 0,
            statusLinePx = status, navInsetPx = nav,
            maxPx = 100_000, minKeyHeightPx = minKey,
        )
        assertEquals("a double deduction would have scaled", 1f, exact.scale, 0.0001f)
        assertEquals(723, exact.viewHeightPx)
        assertEquals(0, exact.deficitPx)
    }

    // 7. the label size scales along (lower bound a parameter).
    @Test
    fun `label size scales along, the lower bound holds`() {
        val scaled = KeyboardHeight.fitHeight(
            650, 4, row4, 0, status, nav, maxWithNav, minKey,
            desiredLabelPx = 63, minLabelPx = 40,
        )
        assertEquals(55, scaled.labelPx) // floor(63 * 0.873) = 55 — scaled, ≥ lower bound
        val held = KeyboardHeight.fitHeight(
            400, 4, row4, 0, status, nav, maxWithNav, minKey,
            desiredLabelPx = 63, minLabelPx = 55,
        )
        assertEquals("the lower bound applies", 55, held.labelPx)
        val full = KeyboardHeight.fitHeight(
            829, 4, row4, 0, status, nav, maxWithNav, minKey,
            desiredLabelPx = 63, minLabelPx = 40,
        )
        assertEquals("at scale 1 the label stays unchanged", 63, full.labelPx)
    }

    // 8. determinism: same inputs → same result (no drift).
    @Test
    fun `fit is deterministic`() {
        for (offered in intArrayOf(400, 500, 650, 829)) {
            assertEquals(
                KeyboardHeight.fitHeight(offered, 4, row4, 0, status, nav, maxWithNav, minKey),
                KeyboardHeight.fitHeight(offered, 4, row4, 0, status, nav, maxWithNav, minKey),
            )
        }
    }
}
