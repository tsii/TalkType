package de.voicekeyboard.keyboard

import kotlin.math.max
import kotlin.math.min

/**
 * T20/S1 — height contract WITHOUT row drop (corrects T19; docs/DESIGN_STOCK_LOOK.md
 * §3 "A single height budget"): pure JVM logic, no Android import.
 *
 * CONTRACT (S1):
 *  - ALL existing rows are kept — a row that does not fit is
 *    SCALED (smaller row height), never half-drawn and never
 *    removed ("row drop is not an accepted fix").
 *  - Scale = min(1, (offered − status − navigation) / desired need).
 *    Key widths stay proportional (width is not part of this contract),
 *    the label size scales along (lower bound = parameter).
 *  - Status row and navigation inset are subtracted EXACTLY ONCE —
 *    root padding and child must not both subtract (documentation ASSUMPTION §3).
 *  - Never higher than the offer (except: minimum key height wins) and never
 *    higher than maxPx (design cap, e. g. 276 dp ≈ 725 px with navigation,
 *    268 dp ≈ 704 px without — density 420 = 2.625 px/dp; 276 dp = 724.5 px).
 *  - Offer ≤ 0 (MeasureSpec.UNSPECIFIED) counts as unlimited: maxPx decides.
 *  - With an extremely small offer the minimum key height is HELD and the
 *    undershot offer is REPORTED as [HeightFit.deficitPx], not swallowed.
 *  - Results are integers (1-px exact), rounding remainders are distributed once
 *    over the rows; the last bottom edge is exactly the content end.
 */
object KeyboardHeight {

    /** S1 result type: scaled height for ALL rows (no row drop). */
    data class HeightFit(
        /** Number of occupied rows — always ALL (no row drop). */
        val rows: Int,
        /** Scaled row height (px). */
        val rowPx: Int,
        /** Scaled row gap (px). */
        val rowGapPx: Int,
        /** rows*rowPx + (rows−1)*rowGapPx — bottom edge of the last row. */
        val keysHeightPx: Int,
        /** status + nav + keysHeightPx — total height of the view incl. the single subtractions. */
        val viewHeightPx: Int,
        /** Scale ≤ 1 (1 = unscaled). */
        val scale: Float,
        /** True if the minimum key height overrode the budget. */
        val minKeyHeld: Boolean,
        /** > 0: budget is undershot (minimum key height held) — reported. */
        val deficitPx: Int,
        /** Scaled label size (px); 0 = no scaled label requested. */
        val labelPx: Int,
    )

    /**
     * S1 core function: computes the height fit for ALL [rows] rows.
     *
     * @param offeredPx       root offer (px; ≤ 0 = unlimited → maxPx decides).
     * @param rows            existing row count — all are kept.
     * @param desiredRowPx    desired row height (px, > 0), e. g. 55 dp ≈ 144 px.
     * @param desiredRowGapPx desired row gap (px, ≥ 0; stock style: 0).
     * @param statusLinePx    status row (px, ≥ 0) — subtracted EXACTLY ONCE.
     * @param navInsetPx      navigation inset (px, ≥ 0) — subtracted EXACTLY ONCE.
     * @param maxPx           design cap (px, > 0).
     * @param minKeyHeightPx  minimum key height (px, > 0 — parameter, never 0).
     * @param desiredLabelPx  desired label size (px; 0 = not requested).
     * @param minLabelPx      lower bound of the label (px, ≥ 0).
     */
    fun fitHeight(
        offeredPx: Int,
        rows: Int,
        desiredRowPx: Int,
        desiredRowGapPx: Int,
        statusLinePx: Int,
        navInsetPx: Int,
        maxPx: Int,
        minKeyHeightPx: Int,
        desiredLabelPx: Int = 0,
        minLabelPx: Int = 0,
    ): HeightFit {
        require(rows >= 1) { "rows must be >= 1" }
        require(desiredRowPx > 0) { "desiredRowPx must be > 0" }
        require(desiredRowGapPx >= 0 && statusLinePx >= 0 && navInsetPx >= 0) { "gap/status/nav >= 0" }
        require(minKeyHeightPx > 0) { "minKeyHeightPx must be > 0 (contract S1)" }
        require(minLabelPx >= 0) { "minLabelPx >= 0" }
        require(maxPx > 0) { "maxPx must be > 0" }

        // Budget: offer (unlimited → maxPx), capped by maxPx.
        val budget = if (offeredPx <= 0) maxPx else min(offeredPx, maxPx)
        // Subtract status AND navigation EXACTLY ONCE each.
        val keysBudget = budget - statusLinePx - navInsetPx
        // Desired need of all existing rows.
        val need = rows * desiredRowPx + (rows - 1) * desiredRowGapPx
        // Scale = min(1, budget / need) — NEVER scale up.
        val scale = if (need <= 0) 1f else min(1f, keysBudget.toFloat() / need.toFloat())

        var rowPx = (desiredRowPx * scale).toInt() // round down — never above budget
        val rowGapPx = (desiredRowGapPx * scale).toInt()
        var minKeyHeld = false
        if (rowPx < minKeyHeightPx) {
            rowPx = minKeyHeightPx // minimum HELD; deficit is reported below
            minKeyHeld = true
        } else if (scale < 1f) {
            // Distribute rounding remainders once over the rows (last bottom edge
            // as close to the content end as possible) without exceeding the budget.
            val rem = keysBudget - (rows * rowPx + (rows - 1) * rowGapPx)
            if (rem > 0) rowPx += rem / rows
        }
        val keysHeight = rows * rowPx + (rows - 1) * rowGapPx
        val viewHeight = statusLinePx + navInsetPx + keysHeight
        val deficit = max(0, viewHeight - budget)
        val labelPx = when {
            desiredLabelPx <= 0 -> 0
            else -> max(minLabelPx, (desiredLabelPx * scale).toInt())
        }
        return HeightFit(
            rows = rows,
            rowPx = rowPx,
            rowGapPx = rowGapPx,
            keysHeightPx = keysHeight,
            viewHeightPx = viewHeight,
            scale = scale,
            minKeyHeld = minKeyHeld,
            deficitPx = deficit,
            labelPx = labelPx,
        )
    }
}
