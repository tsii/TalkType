package de.voicekeyboard.keyboard

import kotlin.math.abs

/**
 * F22 (docs/DESIGN_KEY_POPUP.md §1/§6): pure geometry/selection seam of the
 * long-press menu — NO Android type, analogous to [KeySurface]; the KeyboardView
 * remains the adapter for touch, timers and drawing.
 *
 * Dimensions (density 420, 2.625 px/dp): cell 48 dp = 126 px, menu height 56 dp = 147 px,
 * gap to the base key 8 dp = 21 px. The base character stays on its real
 * key — the menu has ONLY the alternate cells (asset cap 8, no ninth
 * cell; effective view upper bound see [MAX_CELLS]).
 * Cell intervals: closed on the left, open on the right; outside -> no selection.
 */
object LongPressMenu {

    /**
     * DESIGN §3: at most eight alternate cells (>8 is a validation case of the
     * asset — the cap itself stays 8). Vc39 reality follow-up (REVIEW_V0137):
     * the renderer (KeyboardView.openLongPressMenu) ADDITIONALLY suppresses sets
     * whose menu does not fit into the view — formula: menuW(n) = n·cell + (n+1)·pad.
     * With a 1080 px view, cell = 126 px, pad = 11 px (density 2.625) it holds:
     * n=8 → 8·126 + 9·11 = 1107 px > 1080 ⇒ guard menuW > width suppresses,
     * effectively max 7 (n=7 → 7·126 + 8·11 = 970 px fits). Changing density, view
     * width or pad shifts this bound — keep the formula here so the
     * drift stays visible.
     */
    const val MAX_CELLS = 8

    /** Cell count with cap 8 — the list itself is not silently shortened here during validation. */
    fun cellCount(alternates: List<String>): Int = minOf(alternates.size, MAX_CELLS)

    /**
     * F23c (AOSP evidence rowkeys_qwertz1.xml: keyHintLabel + additionalMoreKeys):
     * The hint becomes an additional popup cell so the digit is reachable via
     * long-press. Rules: the hint goes AT THE END (language alternates
     * stay in front, cell 0 does not shift); no duplication; null/
     * blank is dropped; above [maxCells] the HINT drops first, never a
     * language alternate.
     */
    fun withHint(alternates: List<String>, hint: String?, maxCells: Int): List<String> {
        val h = hint?.trim().orEmpty()
        if (h.isEmpty() || h in alternates) return alternates
        return if (alternates.size >= maxCells) alternates else alternates + h
    }

    /**
     * F24e0 (DESIGN_KEY_POPUP.md §7, user decision 07:2x): a menu cell can
     * carry an ACTION instead of committing text (e. g. cursor_left/cursor_right from
     * the footer row). [actionCells] is the optional action list indexed in parallel
     * to the cells (short = the remaining cells are text); null means
     * "as before: commit text". An empty/whitespace-only entry is like no
     * entry (F24e5: "" = normal text field, asset notation from F24e1; if it were
     * an "action" with an empty name, it would fall into the onAction-when and nothing
     * would happen — device finding point-menu). Pure logic, no Android types.
     */
    fun cellAction(index: Int, actionCells: List<String>): String? =
        actionCells.getOrNull(index)?.takeIf { it.isNotBlank() }

    /**
     * Menu width in px: n cells plus padding strips of width [padPx] on both
     * outer edges AND between the cells: `n*cellPx + (n+1)*padPx`
     * (spec: 126·3 + 10·4 = 418). n ≤ 0 -> 0.
     */
    fun menuWidthPx(cellPx: Int, n: Int, padPx: Int): Int =
        if (n <= 0) 0 else cellPx * n + padPx * (n + 1)

    /**
     * T7 (v0.1.37, user finding symbol layer): popup-left from the SET of
     * cells — default centering over the base key ([keyCenterX]), then
     * clamping the start-x to both view edges so that NO cell runs outside
     * the view (cells are NEVER cut off). Purely geometric:
     * menu width = [menuWidthPx] from [menuCount] cells; clamping as in
     * [menuLeftPx] (wider than the view -> 0; pass-fit is decided by the
     * adapter, which then does not open). Red evidence invariant: 10 cells
     * (126 px + 11 px padding) are 1381 px > 1080 px view — NO position
     * keeps all cells inside the view.
     */
    fun menuLeftClamped(
        menuCount: Int,
        keyCenterX: Int,
        cellW: Int,
        viewWidth: Int,
        padPx: Int,
    ): Int = menuLeftPx(keyCenterX, menuWidthPx(cellW, menuCount, padPx), viewWidth)

    /**
     * Menu-left: centered over the base key ([keyCenterX]), clamped to
     * `[0, viewWidth - menuWidth]` — never negative, even if the menu were wider
     * than the view (then 0; pass-fit is decided by the adapter).
     */
    fun menuLeftPx(keyCenterX: Int, menuWidth: Int, viewWidth: Int): Int {
        if (menuWidth >= viewWidth) return 0
        return (keyCenterX - menuWidth / 2).coerceIn(0, viewWidth - menuWidth)
    }

    /**
     * Row position (F28a + T1 two-zone rule + T5 band zone + T15 headroom):
     *  - Row 0 (letter row Q..P): **T15 (Opus option H)**: with headroom
     *    ([headroomPx] > 0, adapter: root paddingTop of the IME window) the menu
     *    takes the TRUE above position ([keyTop] − gap − menuH) whenever it lies
     *    ≥ −(statusBandPx + headroomPx) — i.e. fully inside band + headroom; the
     *    T5 band zone / T1 two-zone rule only remains as fallback (menu even
     *    bigger). headroomPx = 0 (default) ⇒ byte-identical to the old behavior.
     *  - Footer row: directly above the key, clamped to −spaceAbove (T15).
     *  - middle rows: one row higher, clamped to −spaceAbove (F28a; T15 clamp).
     */
    fun menuTopPx(
        keyTop: Int,
        menuHeight: Int,
        gapPx: Int,
        rowHeightPx: Int,
        rowIndex: Int,
        isFooterRow: Boolean,
        padPx: Int = 10,
        cellPx: Int = 126,
        spaceAbovePx: Int = 0,
        /** T5 (vc37): band height above the view's top edge (status row + 🐞); 0 = no
         *  band ⇒ T1 two-zone rule. Default 0 keeps ALL existing calls byte-identical. */
        statusBandPx: Int = 0,
        /** T5 (vc37): top edge of the row below the pressed key — invariant
         *  of the band zone (top + menuH ≤ rowBelowTop). Default: non-binding. */
        rowBelowTop: Int = Int.MAX_VALUE,
        /** T15 (Opus option H): transparent headroom of the IME window above the
         *  KeyboardView (ImeRootView paddingTop) — space into which the menu may
         *  extend. ADDITIVE to [statusBandPx]: spaceAbove = statusBandPx + headroomPx.
         *  Default 0 ⇒ all old call sites (and tests) stay byte-identical. */
        headroomPx: Int = 0,
    ): Int {
        val spaceAbove = statusBandPx + headroomPx
        return when {
            rowIndex == 0 -> {
                // T15: TRUE above — the menu never sits ON the pressed row, as long
                // as it fits completely into band + headroom (invariant
                // top + menuH ≤ keyTop − gap holds by construction: above + menuH
                // = keyTop − gap).
                val above = keyTop - gapPx - menuHeight
                if (above >= -spaceAbove) above
                else menuTopForStatusBand(keyTop, menuHeight, gapPx, statusBandPx, rowBelowTop)
            }
            isFooterRow -> (keyTop - gapPx - menuHeight).coerceAtLeast(-spaceAbove)
            else -> (keyTop - rowHeightPx - gapPx - menuHeight).coerceAtLeast(-spaceAbove)
        }
    }

    /**
     * T5 (vc37, user verdict emulator screenshot): position of the r0 long-press menu
     * (top row, Q..P) — **band zone**: the menu opens IN THE STATUS BAND, one
     * row above the letter key (right where status row + 🐞 sit; the band
     * belongs to the root/stack, NOT to the KeyboardView — top = −statusBandPx in
     * view coordinates). [statusBandPx] = band height above the view's top edge
     * (adapter: own top edge in the stack); 0 = no band ⇒ T1 two-zone rule
     * unchanged (regression protection). [rowBelowTop] = top edge of the row
     * BELOW the pressed one (invariant top + menuH ≤ rowBelowTop — the menu
     * never covers the row below the key; the overhang menuH − statusBandPx
     * protrudes only into the top row). Guard: if the band zone violates the
     * invariant (huge menu), it falls back to the two-zone rule.
     */
    fun menuTopForStatusBand(
        keyTop: Int,
        menuHeight: Int,
        gapPx: Int,
        statusBandPx: Int,
        rowBelowTop: Int,
    ): Int {
        // T5 band zone: top = statusBandTop (= −statusBandPx in the view system) — only
        // if the invariant holds (the menu's bottom edge never goes below the key's row).
        val bandTop = -statusBandPx
        if (statusBandPx > 0 && bandTop + menuHeight <= rowBelowTop) return bandTop
        // T5 fallback (3) / existing behavior: T1 two-zone rule — zone A at the top of the view,
        // otherwise zone B edge (never below the key).
        return if (row0MenuBelowKey(keyTop, menuHeight, gapPx, 10, 126, 0)) {
            0
        } else {
            keyTop - gapPx - menuHeight
        }
    }

    /**
     * T1: pure r0 zone decision (existing signature B1 unchanged) —
     * true ⇒ above does NOT fit into the view (above = keyTop − gap − menuHeight
     * < 0) ⇒ **zone B: edge (top=0)**. B1 in this case returned the position
     * BELOW the key (vc34 user finding: exactly over row 2); T1 replaces the
     * below-fallback with the edge. The adapter derives the arming zone from
     * THE SAME result (shared switch: the menu is never below the
     * key ⇒ [stillArmed] runs in the F22-L default, menuBelowKey=false).
     * [padPx]/[cellPx]/[spaceAbovePx] remain for signature stability,
     * but do NOT influence the decision (an overlay above the view's top edge
     * guarantees no visibility, F54/F34).
     */
    fun row0MenuBelowKey(
        keyTop: Int,
        menuHeight: Int,
        gapPx: Int,
        padPx: Int,
        cellPx: Int,
        spaceAbovePx: Int,
    ): Boolean = keyTop - gapPx - menuHeight < 0

    /**
     * Selection: [offsetX] is the horizontal interior coordinate relative to the start
     * of the first cell. Cell intervals closed on the left, open on the right;
     * outside (negative, beyond n·cellPx, n ≤ 0, cellPx ≤ 0) -> -1.
     */
    fun pick(offsetX: Int, cellPx: Int, n: Int): Int {
        if (n <= 0 || cellPx <= 0 || offsetX < 0) return -1
        val idx = offsetX / cellPx
        return if (idx < n) idx else -1
    }

    /**
     * F22-M (device finding <device>, top row): replaces the upward gate
     * F22-K. A cell only wins once the finger has REALLY left the base key —
     * in WHICH direction does not matter: within the base key shrunk by [slopPx]
     * (offset from the key center) a cell NEVER wins. Reason for the direction
     * openness: in row 0 [menuTopPx] clamps the menu to the view's top edge;
     * an upward gate y < keyTop - slop < 0 would be permanently unreachable
     * for the top row (0 characters in all cells).
     * Holding without movement = inside the base key = base character — the
     * F22-K device finding ("è" without sliding) therefore stays fixed.
     */
    fun cellWins(
        offsetXFromKeyCenter: Int,
        offsetYFromKeyCenter: Int,
        keyWidth: Int,
        keyHeight: Int,
        slopPx: Int,
    ): Boolean = !(abs(offsetXFromKeyCenter) <= keyWidth / 2 - slopPx &&
        abs(offsetYFromKeyCenter) <= keyHeight / 2 - slopPx)

    /**
     * F24e5 LOOKUP: lateral arming bounds as offset to the down position.
     * The zone is the ACTUAL palette (after the edge clamp in [menuLeftPx]),
     * not half the nominal width around the key center — with shifted palettes
     * (left=384 instead of 399) the outermost cell would otherwise lie outside the zone and
     * the palette would never open via sliding. Returns (minOffsetX, maxOffsetX).
     */
    fun armZoneOffsets(centerX: Int, width: Int, menuWidthPx: Int, menuLeftPx: Int, slopPx: Int): Pair<Int, Int> =
        (menuLeftPx - slopPx - centerX) to (menuLeftPx + menuWidthPx + slopPx - centerX)

    /**
     * F22-L (device finding <device> b/c): arming ZONE instead of 8-dp sphere.
     * The 8-dp sphere from D12 §2 killed almost every long-press before the 450-ms
     * threshold ran — stock/AOSP however allows: hold, THEN slide into the menu
     * row. The menu row lies ABOVE the base key, so sliding upward is the
     * intended path, not an abort. Zone relative to the DOWN point
     * (y negative = up): laterally |offsetX| ≤ keyWidth/2 + slopPx (generous —
     * 21 px any finger reaches in < 100 ms), upward up to menuHeightPx +
     * gapPx + slopPx (never tighter than the later popup), downward only up to
     * keyHeight/2 + slopPx; otherwise false = gesture really left.
     */
    fun stillArmed(
        offsetX: Int,
        offsetY: Int,
        keyWidth: Int,
        keyHeight: Int,
        slopPx: Int,
        menuHeightPx: Int,
        gapPx: Int,
        /**
         * F24e5 LOOKUP: lateral offset bounds from [armZoneOffsets] (the
         * actual, edge-clamped if applicable, palette + slop). (0, 0) = not
         * set -> old behavior (half key width + slop). Releasing
         * OUTSIDE a cell never commits anyway (KeyboardView
         * popupSelectionAt / cellWins), no wrong character can arise.
         */
        minOffsetX: Int = 0,
        maxOffsetX: Int = 0,
        /** F38: menu lies BELOW the base key (row 0) — vertical zone
         *  mirrored: downward up to menuHeightPx + gapPx + slopPx (a swing into the
         *  menu stays armed), upward only keyHeight/2 + slopPx. */
        menuBelowKey: Boolean = false,
    ): Boolean {
        val halfW = keyWidth / 2 + slopPx
        val inX = if (minOffsetX == 0 && maxOffsetX == 0) {
            offsetX >= -halfW && offsetX <= halfW
        } else {
            offsetX >= minOffsetX && offsetX <= maxOffsetX
        }
        if (!inX) return false
        if (menuBelowKey) { // F38: menu below the key (row 0) — zone mirrored
            if (offsetY > menuHeightPx + gapPx + slopPx) return false // below the menu
            if (offsetY < -(keyHeight / 2 + slopPx)) return false       // left upward
        } else {
            if (offsetY < -(menuHeightPx + gapPx + slopPx)) return false // above the menu
            if (offsetY > keyHeight / 2 + slopPx) return false           // left downward
        }
        return true
    }
}
