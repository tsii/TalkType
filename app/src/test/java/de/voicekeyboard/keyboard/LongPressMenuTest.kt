package de.voicekeyboard.keyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * F22: specification of the pure popup seam [LongPressMenu] — written BEFORE
 * the production code. Dimensions from docs/DESIGN_KEY_POPUP.md §1 (density 420,
 * 2.625 px/dp: cell 48 dp = 126 px, height 56 dp = 147 px, gap 8 dp = 21 px).
 * The prescribed assignment numbers (e.g. menuWidthPx(126,3,10)=418) are binding.
 */
class LongPressMenuTest {

    // --- cellCount: cap 8 (DESIGN §3: max 8; >8 is an asset validation case) ---

    @Test fun `cellCount caps at 8`() {
        assertEquals(8, LongPressMenu.cellCount(List(9) { it.toString() }))
        assertEquals(8, LongPressMenu.cellCount(List(8) { it.toString() }))
        assertEquals(3, LongPressMenu.cellCount(listOf("a", "b", "c")))
    }

    @Test fun `cellCount null list stays null cells`() {
        assertEquals(0, LongPressMenu.cellCount(emptyList()))
    }

    // --- menuWidthPx: cells + padding strip (pad on both outer edges and
    //     between the cells: n*cell + (n+1)*pad — prescription 126*3 + 4*10 = 418) ---

    @Test fun `menuWidthPx three cells with pad 10 is 418`() {
        assertEquals(418, LongPressMenu.menuWidthPx(126, 3, 10))
    }

    @Test fun `menuWidthPx null cells is null`() {
        assertEquals(0, LongPressMenu.menuWidthPx(126, 0, 10))
    }

    // --- pick: cell intervals closed on the left, open on the right; outside -1 ---

    @Test fun `pick returns the cell index for inside coordinates`() {
        assertEquals(0, LongPressMenu.pick(0, 126, 3))
        assertEquals(0, LongPressMenu.pick(125, 126, 3))
        assertEquals(1, LongPressMenu.pick(126, 126, 3))
        assertEquals(2, LongPressMenu.pick(377, 126, 3)) // last interval inclusive
    }

    @Test fun `pick outside and edge cases return -1`() {
        assertEquals(-1, LongPressMenu.pick(400, 126, 3)) // beyond 3*126=378
        assertEquals(-1, LongPressMenu.pick(-5, 126, 3)) // negative
        assertEquals(-1, LongPressMenu.pick(10, 126, 0)) // no cells
        assertEquals(-1, LongPressMenu.pick(0, 0, 3)) // broken cell width
    }

    // --- menuLeftPx: center over the base key, clamped to [0, viewWidth-menuWidth] ---

    @Test fun `menuLeftPx clamps the left overhang to zero`() {
        assertEquals(0, LongPressMenu.menuLeftPx(10, 399, 1080)) // wanted 10-199=-189
    }

    @Test fun `menuLeftPx clamps the right overhang at the right edge`() {
        assertEquals(1080 - 399, LongPressMenu.menuLeftPx(1070, 399, 1080))
    }

    @Test fun `menuLeftPx centered without clamping and never negative`() {
        assertEquals(540 - 199, LongPressMenu.menuLeftPx(540, 399, 1080))
        assertEquals(0, LongPressMenu.menuLeftPx(0, 2000, 1080)) // wider than view -> 0
        assertTrue(LongPressMenu.menuLeftPx(-100, 399, 1080) >= 0)
    }

    // --- T7 (v0.1.37) menuLeftClamped: popup left from the SET of cells —
    //     default centering over the base key, clamping at both view edges,
    //     NO cell outside (the start x is clamped, never cut off).
    //     Production dimensions symbol layer: cell 126 px (48 dp), padding 11 px
    //     (4 dp · 2.625), view 1080 px (420 dpi). ---

    @Test fun `menuLeftClamped centers and clamps at both view edges`() {
        // 2 cells à 126 + 3 padding à 11 = 285 px.
        assertEquals(540 - 285 / 2, LongPressMenu.menuLeftClamped(2, 540, 126, 1080, 11))
        assertEquals(0, LongPressMenu.menuLeftClamped(2, 100, 126, 1080, 11))    // out on the left
        assertEquals(1080 - 285, LongPressMenu.menuLeftClamped(2, 1070, 126, 1080, 11)) // right
    }

    @Test fun `menuLeftClamped set of 10 fits in NO position of the view`() {
        // T7 red evidence: 10 cells = 10·126 + 11·11 = 1381 px > 1080 px view —
        // the clamp can no longer save a cell (stock: 0; the adapter does
        // not open at menuW > width). The invariant left+menuW ≤ view
        // fails for EVERY position.
        assertEquals(1381, LongPressMenu.menuWidthPx(126, 10, 11))
        assertTrue(LongPressMenu.menuWidthPx(126, 10, 11) > 1080)
        assertEquals(0, LongPressMenu.menuLeftClamped(10, 540, 126, 1080, 11))
    }

    @Test fun `menuWidthPx formula limit - 8-set suppressed, 7 fits`() {
        // Vc39 KDoc follow-up (REVIEW_V0137, LongPressMenu.MAX_CELLS):
        // menuW(n) = n·126 + (n+1)·11 — at view 1080 px an 8-set (1107 px)
        // is wider than the view and suppressed by the guard menuW > width;
        // the EFFECTIVE upper limit is 7 cells (970 px).
        assertEquals(1107, LongPressMenu.menuWidthPx(126, 8, 11))
        assertEquals(970, LongPressMenu.menuWidthPx(126, 7, 11))
        assertTrue("an 8-item set is larger than the view (guard suppressed)",
            LongPressMenu.menuWidthPx(126, 8, 11) > 1080)
        assertTrue("a 7-item set fits (effective maximum)",
            LongPressMenu.menuWidthPx(126, 7, 11) <= 1080)
    }

    // --- menuTopPx (F28a): middle row — one row HIGHER; clamp at the top (never < 0) ---

    @Test fun `menuTopPx middle row lies one row above the key`() {
        // F28a: top=1000, row 1 (middle, lastRow=3), rowH=150, menu 147, gap 21 -> 1000-150-21-147 = 682
        assertEquals(682, LongPressMenu.menuTopPx(1000, 147, 21, 150, rowIndex = 1, isFooterRow = false))
    }

    @Test fun `menuTopPx middle row above the edge clamps to zero`() {
        assertEquals(0, LongPressMenu.menuTopPx(100, 147, 21, 150, rowIndex = 1, isFooterRow = false)) // 100-150-21-147=-218 -> 0
        assertTrue(LongPressMenu.menuTopPx(0, 147, 21, 150, rowIndex = 1, isFooterRow = false) >= 0)
    }

    // --- F22-M: cellWins — base key rectangle gate (direction irrelevant; device
    //     finding <device>: the top row was permanently closed with the
    //     upward gate) ---

    @Test fun `cellWins on the base key never wins`() {
        assertFalse(LongPressMenu.cellWins(0, 0, 98, 144, 21))
    }

    @Test fun `cellWins just above the slop zone wins`() {
        assertTrue(LongPressMenu.cellWins(0, -(144 / 2 - 21 + 1), 98, 144, 21))
    }

    @Test fun `cellWins just outside sideways wins — path for the top row`() {
        assertTrue(LongPressMenu.cellWins(98 / 2 - 21 + 1, 0, 98, 144, 21))
        assertTrue(LongPressMenu.cellWins(-(98 / 2 - 21 + 1), 0, 98, 144, 21))
    }

    @Test fun `cellWins inside the slop zone downwards and on the boundary does not win`() {
        assertFalse(LongPressMenu.cellWins(0, 144 / 2 - 21, 98, 144, 21))
        assertFalse(LongPressMenu.cellWins(98 / 2 - 21, 0, 98, 144, 21)) // exact boundary: inside
    }

    // --- F22-L: stillArmed — zone rule instead of an 8-dp ball (device finding b/c:
    //     real sliding into the menu row must not break the arming) ---

    private val keyW = 98 // 1080 px / 11 keys ~ 98 px
    private val keyH = 126
    private val slop = 21 // 8 dp @ density 420
    private val menuH = 147 // 56 dp @ density 420
    private val gap = 21 // 8 dp @ density 420

    @Test fun `stillArmed centered is always allowed`() {
        assertTrue(LongPressMenu.stillArmed(0, 0, keyW, keyH, slop, menuH, gap))
    }

    @Test fun `stillArmed allowed up to the upper menu boundary`() {
        // upwards allowed up to menuHeight + gap + slop = 189 px (y negative = up)
        assertTrue(LongPressMenu.stillArmed(0, -188, keyW, keyH, slop, menuH, gap)) // marginal, inside
        assertTrue(LongPressMenu.stillArmed(0, -189, keyW, keyH, slop, menuH, gap)) // exactly the boundary
        assertFalse(LongPressMenu.stillArmed(0, -190, keyW, keyH, slop, menuH, gap)) // beyond it
    }

    @Test fun `stillArmed sideways generous up to half key width plus slop`() {
        assertTrue(LongPressMenu.stillArmed(49 + 21 - 1, 0, keyW, keyH, slop, menuH, gap))
        assertTrue(LongPressMenu.stillArmed(-(49 + 21), 0, keyW, keyH, slop, menuH, gap)) // boundary
        assertFalse(LongPressMenu.stillArmed(49 + 21 + 1, 0, keyW, keyH, slop, menuH, gap))
    }

    @Test fun `stillArmed downwards only up to half key height plus slop`() {
        assertTrue(LongPressMenu.stillArmed(0, 63 + 21, keyW, keyH, slop, menuH, gap)) // exact
        assertFalse(LongPressMenu.stillArmed(0, 63 + 21 + 1, keyW, keyH, slop, menuH, gap))
    }

    // F38: row 0 — the menu lies BELOW, the vertical arming zone is mirrored
    // (a swipe DOWN into the menu cells stays armed, upwards tight).
    @Test fun `F38 stillArmed menuBelowKey - downwards up to the menu boundary, upwards tight`() {
        assertTrue(LongPressMenu.stillArmed(0, menuH + gap + slop, keyW, keyH, slop, menuH, gap, menuBelowKey = true)) // exact boundary
        assertFalse(LongPressMenu.stillArmed(0, menuH + gap + slop + 1, keyW, keyH, slop, menuH, gap, menuBelowKey = true)) // beyond it
        assertTrue(LongPressMenu.stillArmed(0, -(63 + 21), keyW, keyH, slop, menuH, gap, menuBelowKey = true)) // upwards: half key + slop
        assertFalse(LongPressMenu.stillArmed(0, -(63 + 21 + 1), keyW, keyH, slop, menuH, gap, menuBelowKey = true))
        // default (menu above) stays unchanged:
        assertTrue(LongPressMenu.stillArmed(0, -188, keyW, keyH, slop, menuH, gap))
        assertFalse(LongPressMenu.stillArmed(0, menuH + gap + slop, keyW, keyH, slop, menuH, gap))
    }

    // ---- F23c: hint as an additional popup cell (AOSP additionalMoreKeys) ----
    // The hint goes AT THE END — language alternates stay in front (cell 0 stays
    // e.g. ae for a). If the list exceeds MAX_CELLS, the hint is dropped FIRST,
    // not the language alternates.

    @Test fun `withHint - digit goes to the end of the alternates`() {
        assertEquals(listOf("é", "è", "3"), LongPressMenu.withHint(listOf("é", "è"), "3", 8))
    }

    @Test fun `withHint - no duplication when the hint is already contained`() {
        assertEquals(listOf("1"), LongPressMenu.withHint(listOf("1"), "1", 8))
    }

    @Test fun `withHint - empty alternates list with hint yields one cell`() {
        assertEquals(listOf("1"), LongPressMenu.withHint(emptyList(), "1", 8))
    }

    @Test fun `withHint - null and blank hint yield unchanged respectively empty`() {
        assertEquals(emptyList<String>(), LongPressMenu.withHint(emptyList(), null, 8))
        assertEquals(emptyList<String>(), LongPressMenu.withHint(emptyList(), "  ", 8))
        assertEquals(listOf("é"), LongPressMenu.withHint(listOf("é"), "", 8)) // blank: not appended
    }

    @Test fun `withHint - max cells 7 plus hint fills the eighth cell`() {
        val seven = (1..7).map { "s$it" } // does not contain the hint "1"
        val r = LongPressMenu.withHint(seven, "1", 8)
        assertEquals(8, r.size)
        assertEquals("1", r.last())
    }

    @Test fun `withHint - with eight language alternates the hint is dropped first`() {
        val eight = (1..8).map { "s$it" }
        assertEquals(eight, LongPressMenu.withHint(eight, "1", 8))
    }

    // ---- F24e0: menu cells can carry actions (cursor_left / cursor_right) ----

    @Test fun `cellAction - without actionCells everything behaves as before`() {
        assertNull(LongPressMenu.cellAction(0, emptyList()))
        assertNull(LongPressMenu.cellAction(1, emptyList()))
    }

    @Test fun `cellAction - action at the cell position, the rest stays text`() {
        val actions = listOf("cursor_left", "cursor_right")
        assertEquals("cursor_left", LongPressMenu.cellAction(0, actions))
        assertEquals("cursor_right", LongPressMenu.cellAction(1, actions))
        assertNull("cell 2 carries no action", LongPressMenu.cellAction(2, actions))
    }

    @Test fun `cellAction - negative indices and beyond the cell count return null`() {
        val actions = listOf("cursor_left", "cursor_right")
        assertNull(LongPressMenu.cellAction(-1, actions))
        assertNull(LongPressMenu.cellAction(2, actions))
        assertNull(LongPressMenu.cellAction(99, actions))
    }

    // ---- F24e5 P1 fix: "" = normal text cell (asset notation from F24e1) ----
    // Before: "" != null -> action branch -> onAction("") -> no KeyActions branch ->
    // nothing happened (device case: the period menu committed no text cell).

    @Test fun `cellAction - an empty entry is a text cell, only non-empty ones are actions`() {
        val actions = listOf("", "", "cursor-left")
        assertNull("cell 0 is a text cell", LongPressMenu.cellAction(0, actions))
        assertNull("cell 1 is a text cell", LongPressMenu.cellAction(1, actions))
        assertEquals("cursor-left", LongPressMenu.cellAction(2, actions))
    }

    @Test fun `cellAction - whitespace-only entry returns null`() {
        assertNull(LongPressMenu.cellAction(0, listOf("   ")))
    }

    // ---- F24e5: lateral arming as wide as the palette itself ----
    // Device finding: 5-cell menu (696 px) over the period key (83 px) — the
    // comma cell at -270 px lay outside the old keyWidth/2 limit and the
    // palette did not open at all. Releasing outside a cell never commits,
    // so the wider zone cannot produce a wrong character.

    // ---- F24e5: lateral arming = the ACTUAL palette (after the clamp) ----
    // Device finding 08:2x: the menuWidthPx nominal width centered around the
    // key center acted too small (palette 696 px starts at left=384 due to the
    // edge clamp, key center 747 — cell 0 at offset -279 stayed outside the zone).
    // Now: zone = palette.

    @Test fun `armZoneOffsets - measurement cell 0 (-279) in the zone, -400 outside`() {
        // Device dimensions: centerX 747, view 1080, palette 696 at left 384, slop 12.
        val (minOff, maxOff) = LongPressMenu.armZoneOffsets(747, 1080, 696, 384, 12)
        assertEquals(-375, minOff)
        assertEquals(345, maxOff)
        assertTrue("the comma cell (-279) must stay armed",
            LongPressMenu.stillArmed(-279, 0, 83, 144, 12, 168, 20, minOff, maxOff))
        assertFalse(LongPressMenu.stillArmed(-400, 0, 83, 144, 12, 168, 20, minOff, maxOff))
        // right side: +345 inclusive boundary, +346 outside
        assertTrue(LongPressMenu.stillArmed(345, 0, 83, 144, 12, 168, 20, minOff, maxOff))
        assertFalse(LongPressMenu.stillArmed(346, 0, 83, 144, 12, 168, 20, minOff, maxOff))
    }

    @Test fun `armZoneOffsets - without a clamp the zone lies symmetrically around the center`() {
        val (minOff, maxOff) = LongPressMenu.armZoneOffsets(540, 1080, 696, 192, 12)
        assertEquals(192 - 12 - 540, minOff)
        assertEquals(192 + 696 + 12 - 540, maxOff)
    }

    @Test fun `stillArmed - without zone boundaries the old keyWidth behavior applies (default)`() {
        // default (0,0): keyWidth/2 + slop — the old 7-cell calls stay valid.
        assertFalse(LongPressMenu.stillArmed(-279, 0, 83, 144, 12, 168, 20))
        assertTrue(LongPressMenu.stillArmed(-(83 / 2 + 12), 0, 83, 144, 12, 168, 20))
    }

    // F28a/F38/T1: row position — 3 branches:
    //  row 0: T1 two-zone rule — zone A ABOVE (keyTop − gap − menuH ≥ 0,
    //            fully inside the view; an overlay surface above the view's
    //            upper edge guarantees NO visibility, F54/F34), otherwise zone
    //            B: edge (top=0) — NEVER below the key (B1 below-fallback
    //            removed, user finding vc34: the menu covered row 2);
    //  last row: directly above the key (keyTop - gap - menuH, state 0.1.16);
    //  middle rows: one row higher (keyTop - rowH - gap - menuH).
    @Test fun `F38 a) row 0 without space above - edge instead of below the key`() {
        val top = LongPressMenu.menuTopPx(keyTop = 0, menuHeight = 60, gapPx = 8, rowHeightPx = 150, rowIndex = 0, isFooterRow = false)
        assertEquals("T1 zone B: keyboard edge (top=0), NOT below the key", 0, top)
        assertTrue("the menu never starts above the keyboard surface (>= 0)", top >= 0)
        // zone A remains: WITH space above the key (keyTop 100 > gap+menuH 68)
        // the r0 menu lies ABOVE (fully inside the view).
        val top2 = LongPressMenu.menuTopPx(keyTop = 100, menuHeight = 60, gapPx = 8, rowHeightPx = 150, rowIndex = 0, isFooterRow = false)
        assertEquals("zone A: with space above -> keyTop - gap - menuH", 100 - 8 - 60, top2)
    }

    // ---- B1 → T1 (v0.1.35): row-0 digit menu (Q..P) — two-zone rule ----
    // F54 a) stayed removed (an overlay above the view's upper edge guarantees
    // no visibility: F34-statusRow-Z + root clipping). B1's below fallback
    // (position BELOW the key, exactly over row 2) was the vc34 user finding
    // — T1 replaces it with zone B: keyboard edge (top=0), NEVER below the key.

    // T1 acceptance: real geometry keyTop=0,rowH=150,menuH=147,gap=21 ⇒ edge (0),
    // NOT -168 (overlay invisible) and NOT 171 (below the key, row-2 cover).
    @Test fun `T1 r0 real geometry - edge instead of below-fallback and instead of minus 168`() {
        val zoneB = LongPressMenu.row0MenuBelowKey(0, 147, 21, 10, 126, 126)
        assertTrue("menu surface NOT fully inside the view ⇒ zone B (edge)", zoneB)
        val top = LongPressMenu.menuTopPx(
            keyTop = 0, menuHeight = 147, gapPx = 21, rowHeightPx = 150,
            rowIndex = 0, isFooterRow = false, padPx = 10, cellPx = 126, spaceAbovePx = 126,
        )
        assertEquals("T1 zone B: keyboard edge (top=0)", 0, top)
        org.junit.Assert.assertNotEquals("NOT -168 (overlay/root clipping: invisible)", -168, top)
        org.junit.Assert.assertNotEquals("NOT 171 (below the key, covering row 2)", 171, top)
        assertTrue("top >= 0", top >= 0)
        assertTrue("top + menuH <= 318 (fully inside the surface)", top + 147 <= 318)
    }

    // T1 (debugger re-sharpening): position and arming switch TOGETHER — the
    // r0 menu opens at the edge (top=0), NEVER below the key ⇒ the adapter
    // passes menuBelowKey=false: F22-L default zone, cells reachable by an
    // upward/sideways swipe (F22-M: direction irrelevant). The mirrored
    // downward zone of stillArmed remains as a function contract (checked below).
    @Test fun `T1 position and arming switch together - default zone, never below`() {
        // zone B (edge) for the real r0 geometry — the same pure function:
        assertTrue("zone B: above does not fit into the view", LongPressMenu.row0MenuBelowKey(0, 147, 21, 10, 126, 126))
        assertEquals("edge (0) instead of the below-fallback", 0, LongPressMenu.menuTopPx(0, 147, 21, 150, 0, false))
        // default zone (menuBelowKey=false): an upward swipe into the menu row …
        assertTrue(
            "an upward swipe up to the menu's upper edge stays armed",
            LongPressMenu.stillArmed(0, -(menuH + gap + slop), keyW, keyH, slop, menuH, gap, menuBelowKey = false),
        )
        assertFalse(
            "beyond it the gesture is left",
            LongPressMenu.stillArmed(0, -(menuH + gap + slop + 1), keyW, keyH, slop, menuH, gap, menuBelowKey = false),
        )
        assertTrue(
            "a sideways swipe stays armed (F22-M)",
            LongPressMenu.stillArmed(keyW / 2 + slop, -(menuH / 2), keyW, keyH, slop, menuH, gap, menuBelowKey = false),
        )
        // function contract of the mirrored zone (B1) remains — only the
        // adapter no longer wires it (T1: never below the key):
        assertTrue(
            "stillArmed below mode preserved as a contract",
            LongPressMenu.stillArmed(0, menuH + gap + slop, keyW, keyH, slop, menuH, gap, menuBelowKey = true),
        )
        assertFalse(
            "beyond it the gesture is left (contract)",
            LongPressMenu.stillArmed(0, menuH + gap + slop + 1, keyW, keyH, slop, menuH, gap, menuBelowKey = true),
        )
        // spaceAbovePx must NOT rescue the visibility decision — even a huge
        // value keeps zone B (no root-clipping rescue anchor):
        assertTrue("no root-clipping rescue anchor", LongPressMenu.row0MenuBelowKey(0, 147, 21, 10, 126, 10_000))
    }

    // B1 red test 3: middle row/footer and period menu — coordinates EXACTLY
    // unchanged (stock regression protection of the fix).
    @Test fun `B1 middle row footer and period menu exactly unchanged`() {
        // middle row: one row higher, clamped to 0 (F28a)
        assertEquals(682, LongPressMenu.menuTopPx(1000, 147, 21, 150, 1, false))
        assertEquals(0, LongPressMenu.menuTopPx(100, 147, 21, 150, 1, false))
        // footer (period menu measurement): directly above the key, 432-21-146=265
        assertEquals(265, LongPressMenu.menuTopPx(432, 146, 21, 150, 3, true))
        // the same key without the footer marker: row height subtracted (115)
        assertEquals(115, LongPressMenu.menuTopPx(432, 146, 21, 150, 3, false))
        // clamping: last row above the view top -> 0
        assertEquals(0, LongPressMenu.menuTopPx(50, 146, 10, 144, 3, true))
        // F38 stock case r0 with space: stays above
        assertEquals(232, LongPressMenu.menuTopPx(400, 147, 21, 150, 0, false))
    }

    // B1 red test 4: the upper path only if the COMPLETE menu surface lies
    // inside the view (above >= 0) — boundary cases exact; spaceAbovePx uninvolved.
    @Test fun `B1 r0 upper path only with the complete surface inside the view`() {
        // plenty of space: above, even WITHOUT known root space
        assertEquals(232, LongPressMenu.menuTopPx(400, 147, 21, 150, 0, false, 10, 126, 0))
        assertEquals(232, LongPressMenu.menuTopPx(400, 147, 21, 150, 0, false, 10, 126, 126))
        // exactly fitting surface (above == 0): upper path, top == 0
        assertEquals(0, LongPressMenu.menuTopPx(168, 147, 21, 150, 0, false, 10, 126, 126))
        assertFalse(LongPressMenu.row0MenuBelowKey(168, 147, 21, 10, 126, 126))
        // 1 px off (above == -1): zone B (edge) — spaceAbovePx does not rescue
        assertTrue(LongPressMenu.row0MenuBelowKey(167, 147, 21, 10, 126, 126))
        assertEquals("T1 zone B: edge (0), NOT below the key", 0, LongPressMenu.menuTopPx(167, 147, 21, 150, 0, false, 10, 126, 126))
    }

    // ---- T1 (v0.1.35, user finding Pixel/vc34): two-zone rule for the
    // letter row — menu NEVER below the finger / never over the row below ----

    // T1 core claim (debugger re-sharpening): the r0 and r1 menus do not
    // overlap the row BELOW the pressed key — top=0 case OR
    // menuTop + menuHeight <= keyTop(row below). Real pixel geometry
    // (density 2.625): rowH=144 (55 dp), menuH=147 (56 dp), gap=21 (8 dp).
    @Test fun `T1 r0-r1 menu does not overlap the row below - never below the finger`() {
        val rowH = 144
        val menuH = 147
        val gap = 21
        // r0 = Q..P (keyTop 0), r1 = a..l (keyTop 144): both open at the edge.
        for ((row, keyTop) in listOf(0 to 0, 1 to rowH)) {
            val top = LongPressMenu.menuTopPx(keyTop, menuH, gap, rowH, row, isFooterRow = false)
            val rowBelowTop = keyTop + rowH
            assertTrue("r$row: menu never below the pressed key (top=$top)", top <= keyTop)
            assertTrue(
                "r$row: top=0 case or menu bottom edge free of the row below",
                top == 0 || top + menuH <= rowBelowTop,
            )
        }
        // r0 core case: edge (0) — the B1 below fallback (171, exactly over
        // row 2) is gone; row 2 (y..m, top 288) stays completely free:
        val top0 = LongPressMenu.menuTopPx(0, menuH, gap, rowH, 0, isFooterRow = false)
        assertEquals(0, top0)
        assertTrue("row 2 stays free", top0 + menuH <= 2 * rowH)
        // r2/footer unchanged (stock rule): one row higher respectively
        // directly above the key, clamped at 0.
        assertEquals(0, LongPressMenu.menuTopPx(2 * rowH, menuH, gap, rowH, 2, false))
        assertEquals(4 * rowH - gap - menuH, LongPressMenu.menuTopPx(4 * rowH, menuH, gap, rowH, 3, true))
    }

    // T1 two-zone rule: zone A (above, fully inside the view) remains for space
    // above the key; zone B (edge) applies at the boundary — NEVER below the
    // key, NEVER negative (no overlay/root-clipping dependency: zone B lies
    // COMPLETELY inside the KeyboardView ⇒ 100 % visible without clipChildren).
    @Test fun `T1 two-zone rule - zone A above inside the view, zone B edge, never below the key`() {
        // zone A: enough space above the key (above >= 0) ⇒ directly above it.
        assertEquals(400 - 21 - 147, LongPressMenu.menuTopPx(400, 147, 21, 150, 0, false))
        // zone-A boundary: above == 0 ⇒ top == 0 (edge, fully visible).
        assertEquals(0, LongPressMenu.menuTopPx(168, 147, 21, 150, 0, false))
        // zone B: 1 px off ⇒ edge (0) — NOT below the key (338).
        assertEquals(0, LongPressMenu.menuTopPx(167, 147, 21, 150, 0, false))
        // invariant over all r0 cases: menu upper edge never below the key's
        // upper edge (never below the finger), never negative (no overlay needed).
        for (keyTop in listOf(0, 50, 167, 168, 400)) {
            val top = LongPressMenu.menuTopPx(keyTop, 147, 21, 150, 0, false)
            assertTrue("keyTop=$keyTop: top=$top never negative", top >= 0)
            assertTrue("keyTop=$keyTop: top=$top never below the key", top <= keyTop)
        }
    }

    // ---- F54 (user assignment 02:4x) → T1: row 0 without space above — edge ----

    @Test fun `F54 b) T1 without space above - edge (0), never negative, spaceAbove does not rescue`() {
        // spaceAbovePx=0 (no root space known): above ONLY fully inside the
        // view — T1 zone B: edge (0), NEVER below the key (not the B1 below fallback).
        assertTrue("no space above known -> zone B", LongPressMenu.row0MenuBelowKey(0, 147, 21, 10, 126, 0))
        val top = LongPressMenu.menuTopPx(
            keyTop = 0, menuHeight = 147, gapPx = 21, rowHeightPx = 150,
            rowIndex = 0, isFooterRow = false, padPx = 10, cellPx = 126, spaceAbovePx = 0,
        )
        assertEquals("T1 zone B: edge (>= 0), never below the key", 0, top)
        assertTrue("edge never negative", top >= 0)
        // exotic: huge menu — above it never fits into the view -> zone B (edge).
        assertTrue("giant menu -> zone B",
            LongPressMenu.row0MenuBelowKey(0, 400, 21, 10, 126, 30))
        val top2 = LongPressMenu.menuTopPx(
            keyTop = 0, menuHeight = 400, gapPx = 21, rowHeightPx = 150,
            rowIndex = 0, isFooterRow = false, padPx = 10, cellPx = 126, spaceAbovePx = 30,
        )
        assertEquals(0, top2)
        // clamping of the other branches stays: footer/middle NEVER negative.
        assertTrue(LongPressMenu.menuTopPx(50, 146, 10, 144, 3, true) >= 0)
        assertTrue(LongPressMenu.menuTopPx(100, 147, 21, 150, 1, false) >= 0)
    }

    @Test fun `F28a b) middle row - one row higher than above the key`() {
        val top = LongPressMenu.menuTopPx(keyTop = 400, menuHeight = 60, gapPx = 8, rowHeightPx = 150, rowIndex = 1, isFooterRow = false)
        assertEquals("keyTop - rowH - gap - menuH", 400 - 150 - 8 - 60, top)
        assertTrue("higher than 0.1.16 (keyTop - gap - menuH)", top < 400 - 8 - 60)
        // clamped at 0: middle row above the view start.
        assertEquals(0, LongPressMenu.menuTopPx(keyTop = 100, menuHeight = 60, gapPx = 8, rowHeightPx = 150, rowIndex = 1, isFooterRow = false))
    }

    @Test fun `F28a c) last row - directly above the key without the row height gives 276`() {
        // user measurement de-de: keyTop=432, menuHeight=146, gap=10, rowH=144, last row
        // -> keyTop - gap - menuHeight = 432 - 10 - 146 = 276 (ONE row above the key)
        val top = LongPressMenu.menuTopPx(keyTop = 432, menuHeight = 146, gapPx = 10, rowHeightPx = 144, rowIndex = 3, isFooterRow = true)
        assertEquals(276, top)
        // without the row height: lower than the middle-row formula would put it
        assertTrue("no row height subtracted", top > 432 - 144 - 10 - 146)
    }

    @Test fun `F28a d) last row - clamps to zero if above the view top`() {
        assertEquals(0, LongPressMenu.menuTopPx(keyTop = 50, menuHeight = 146, gapPx = 10, rowHeightPx = 144, rowIndex = 3, isFooterRow = true)) // 50-10-146=-106 -> 0
    }

    @Test fun `F38 e) row 0 - lies ALWAYS inside the keyboard surface (no more negative)`() {
        // T1: rowIndex==0 without space above opens at the edge (top=0) — always
        // >= 0 (before: -statusBarPx behind the status bar respectively B1 below the key).
        val top = LongPressMenu.menuTopPx(keyTop = 0, menuHeight = 146, gapPx = 10, rowHeightPx = 144, rowIndex = 0, isFooterRow = false)
        assertEquals(0, top) // T1 zone B: edge instead of 154 (0+144+10, below the key)
        assertTrue("inside the surface, nothing negative", top >= 0)
    }

    // F28a2: the footer (space row) is no longer detected via rowIndex==last
    // but passed as isFooterRow — then the menu lies DIRECTLY above the key
    // WITHOUT the row height. Device finding (18:18): r3 period palette
    // keyTop=432, gap=21, menu=146 ⇒ 265 (before 120 due to the row height).
    @Test fun `F28a2 footer isFooterRow true - menu directly above the key (265)`() {
        val top = LongPressMenu.menuTopPx(keyTop = 432, menuHeight = 146, gapPx = 21, rowHeightPx = 150, rowIndex = 3, isFooterRow = true)
        assertEquals("432 - 21 - 146 = 265 (no row height)", 265, top)
        // delimitation: the same key WITHOUT the footer marker would subtract
        // the row height (120) — exactly the bug F28a2 fixes.
        val middle = LongPressMenu.menuTopPx(keyTop = 432, menuHeight = 146, gapPx = 21, rowHeightPx = 150, rowIndex = 3, isFooterRow = false)
        assertEquals(115, middle) // 432 - 150 - 21 - 146 = 115
    }

    // ---- T5 (vc37, user verdict emulator image): r0 menu INSIDE THE STATUS BAND ----

    // (1) band zone: r0 (Q..P) opens in the status band — top = band upper edge
    //     (−statusBandPx in view coordinates); invariant top+menuH ≤ rowBelowTop:
    //     the menu NEVER covers the row below the pressed key, the overhang
    //     menuH−band protrudes only into the top row (finger stays free).
    //     Real geometry: band 126 px (48 dp @2.625), menuH 147, row 144 px.
    @Test fun `T5 band zone - r0 menu in the status band, the row below stays free`() {
        val top = LongPressMenu.menuTopForStatusBand(
            keyTop = 0, menuHeight = 147, gapPx = 21,
            statusBandPx = 126, rowBelowTop = 144,
        )
        assertEquals("top = statusBandTop (band upper edge, view-coords)", -126, top)
        assertTrue("invariant: menu bottom edge never below the pressed key/row below",
            top + 147 <= 144)
        assertTrue("finger position (row center ~72) not covered", top + 147 < 72)
        // guard: a giant menu violates the invariant ⇒ two-zone rule (zone B):
        assertEquals("guard: invariant violated ⇒ T1 two-zone rule",
            0, LongPressMenu.menuTopForStatusBand(0, 400, 21, statusBandPx = 126, rowBelowTop = 144))
        // menuTopPx hook: the band parameter passes the band zone through (rowIndex 0):
        assertEquals(-126, LongPressMenu.menuTopPx(
            0, 147, 21, 150, 0, false, statusBandPx = 126, rowBelowTop = 144,
        ))
    }

    // (2) cell choice with cells at y<0: cellWins is direction-agnostic (F22-M) —
    //     negative offsets (finger ABOVE the key center, in the band) choose
    //     correctly; the base key NEVER wins as long as the finger is inside it.
    @Test fun `T5 cell choice - cellWins accepts negative y (menu cells in the band)`() {
        val keyW = 98; val keyH = 126; val slop = 21
        // finger far ABOVE the center (−100 px, in the band): base key left ⇒ a cell wins.
        assertTrue("a negative offset selects a cell", LongPressMenu.cellWins(0, -100, keyW, keyH, slop))
        // finger above the center, BUT still inside the shrunken base key: NO cell.
        assertFalse("inside the base key a cell never wins",
            LongPressMenu.cellWins(0, -(keyH / 2 - slop - 1), keyW, keyH, slop))
        assertTrue("boundary: exactly left ⇒ cell",
            LongPressMenu.cellWins(0, -(keyH / 2 - slop + 1), keyW, keyH, slop))
        // horizontal cell choice remains pure x arithmetic (band x unchanged):
        assertEquals(2, LongPressMenu.pick(300, 126, 3))
        assertEquals(-1, LongPressMenu.pick(-5, 126, 3))
    }

    // (3) regression protection: band height 0 (no status band, e.g. height
    //     limits) ⇒ the T1 two-zone rule applies unchanged (zone A above inside
    //     the view / zone B edge).
    @Test fun `T5 fallback - band height 0 leaves the T1 two-zone rule untouched`() {
        assertEquals("zone B (edge) as in T1", 0,
            LongPressMenu.menuTopForStatusBand(0, 147, 21, statusBandPx = 0, rowBelowTop = 144))
        assertEquals("zone A as in T1 (space above inside the view)", 232,
            LongPressMenu.menuTopForStatusBand(400, 147, 21, statusBandPx = 0, rowBelowTop = 550))
        // menuTopPx WITHOUT the band parameter (every stock call site) stays byte-identical:
        assertEquals(0, LongPressMenu.menuTopPx(0, 147, 21, 150, 0, false))
        assertEquals(232, LongPressMenu.menuTopPx(400, 147, 21, 150, 0, false))
        // footer/middle rows as stock (no band dependency):
        assertEquals(265, LongPressMenu.menuTopPx(432, 146, 21, 150, 3, true))
        assertEquals(682, LongPressMenu.menuTopPx(1000, 147, 21, 150, 1, false))
    }
}
