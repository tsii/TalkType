package de.voicekeyboard.keyboard

import de.voicekeyboard.layouts.KeyActions
import de.voicekeyboard.layouts.KeyKind
import de.voicekeyboard.layouts.LayoutKey
import de.voicekeyboard.layouts.LayoutLayer
import kotlin.math.abs

/**
 * Measures a layout layer in pixels: relative widths → px, including gaps.
 * Pure JVM logic (no Android types) and therefore unit-testable.
 *
 * T20/S1: The row height follows the passed height fit ([fit], determined
 * by KeyboardView anew on EVERY onMeasure and passed through as a parameter —
 * T21: no global state): with a limited offer ALL rows are SCALED —
 * no row drop, no half-drawn key.
 * A fit with a differing row count (e. g. after a layer switch, before the
 * next measurement runs) is defensively ignored.
 */
class KeyboardModel(
    layer: LayoutLayer,
    private val totalWidthPx: Float,
    keyHeightPx: Float,
    rowGapPx: Float,
    val keyGapPx: Float,
    private val horizontalPaddingPx: Float = 0f,
    /**
     * S1/S2 height fit; null = fixed dimensions. T21: DELIBERATELY null as default — no
     * global `KeyboardHeight.lastFit` anymore: the fit is passed through as a parameter from
     * KeyboardView.rebuildModel, so that a fit from an earlier opening/session
     * never carries over.
     */
    fit: KeyboardHeight.HeightFit? = null,
) {

    data class MeasuredKey(
        val id: String,
        val label: String,
        val kind: String, // "text" | "action" | "dead"
        val value: String,
        val left: Float,
        val top: Float,
        val right: Float,
        val bottom: Float,
        val centerX: Float,
        val centerY: Float,
    )

    val keys: List<MeasuredKey>
    val heightPx: Float

    /** Effective (scaled if applicable) row height — S1; fixed when fit == null. */
    val effectiveRowHeightPx: Float

    /** Effective (scaled if applicable) row gap (hit tolerance, as before). */
    val effectiveRowGapPx: Float

    init {
        require(totalWidthPx > 0) { "totalWidthPx must be > 0" }
        require(keyHeightPx > 0) { "keyHeightPx must be > 0" }
        // S1: apply the fit only if it matches THIS layer (same row count)
        // and shrinks the row height (scale ≤ 1) — never enlarge.
        val applied = fit?.takeIf { it.rows == layer.rows.size && it.rowPx > 0 }
        effectiveRowHeightPx = ((applied?.rowPx?.toFloat()) ?: keyHeightPx).coerceAtMost(keyHeightPx)
        effectiveRowGapPx = (applied?.rowGapPx?.toFloat()) ?: rowGapPx
        val out = mutableListOf<MeasuredKey>()
        val usable = totalWidthPx - 2 * horizontalPaddingPx
        var top = 0f
        for (row in D10_FOOTER.compose(layer.rows)) {
            val sum = row.sumOf { it.width }
            require(sum > 0) { "Row without width" }
            val unit = (usable - keyGapPx * (row.size - 1)) / sum
            var left = horizontalPaddingPx
            for (key in row) {
                val w = (unit * key.width).toFloat()
                out.add(
                    MeasuredKey(
                        id = key.id,
                        label = key.label,
                        kind = key.kind.name.lowercase(),
                        value = key.value,
                        left = left,
                        top = top,
                        right = left + w,
                        bottom = top + effectiveRowHeightPx,
                        centerX = left + w / 2f,
                        centerY = top + effectiveRowHeightPx / 2f,
                    ),
                )
                left += w + keyGapPx
            }
            top += effectiveRowHeightPx + effectiveRowGapPx
        }
        keys = out
        heightPx = if (keys.isEmpty()) 0f else keys.maxOf { it.bottom }
    }

    /**
     * Hit detection: the point lies on a key OR in the gap between
     * keys — then the nearest key wins (center distance).
     * Returns null if the point lies completely outside.
     */
    fun keyAt(x: Float, y: Float): MeasuredKey? {
        if (y < -gapTolerance() || y > heightPx + gapTolerance()) return null
        var best: MeasuredKey? = null
        var bestDist = Float.MAX_VALUE
        for (k in keys) {
            val dx = if (x < k.left) k.left - x else if (x > k.right) x - k.right else 0f
            val dy = if (y < k.top) k.top - y else if (y > k.bottom) y - k.bottom else 0f
            if (dx <= keyGapPx && dy <= effectiveRowGapPx + effectiveRowHeightPx) {
                val dist = abs(x - k.centerX) + abs(y - k.centerY)
                if (dist < bestDist) {
                    bestDist = dist
                    best = k
                }
            }
        }
        if (best != null) return best
        // Edge areas: left/right outside → nothing (no phantom keys)
        return null
    }

    private fun gapTolerance(): Float = effectiveRowGapPx
}

/**
 * D10 §1 (docs/DESIGN_MIC_AND_GLOBE.md, chapter 1 "Reflow: Enter moves forward, Mic
 * gets the right edge"): shared PRESENTATION COMPOSITION of the footer for all
 * 15×4 layers — ID, action, label and alternates stay unchanged,
 * 0 JSON changes:
 *
 *  - Order: `?123/ABC · 🌐 · 😊 · Space · ‹ · › · Enter · Mic` — Mic moves
 *    to the far right end (behind Enter); no letter key is displaced.
 *  - Weights: Mic 1 → **2**, Space gives up exactly one unit (5 → 4),
 *    the total sum stays 13; the last right edge is exactly W.
 *  - Drawing, hit test AND accessibility read the same composed source
 *    (KeyboardModel) — no merely painted large Mic area.
 *  - Rows without Mic+Enter (letter rows) remain untouched.
 */
internal object D10_FOOTER {

    /** Reflow all rows of a layer (only theMic+Enter row is touched). */
    fun compose(rows: List<List<LayoutKey>>): List<List<LayoutKey>> = rows.map { row -> composeRow(row) }

    private fun composeRow(row: List<LayoutKey>): List<LayoutKey> {
        fun isAction(key: LayoutKey, action: String) =
            key.kind == KeyKind.ACTION && key.value == action

        val mic = row.firstOrNull { isAction(it, KeyActions.MIC) } ?: return row
        val enter = row.firstOrNull { isAction(it, KeyActions.ENTER) } ?: return row

        // Mic to the far right end, Enter moves to the second-to-last position.
        var out = row.filterNot { it === mic || it === enter } + enter + mic

        // Space gives up exactly one unit (5 → 4), Mic receives it (1 → 2).
        val space = out.firstOrNull { isAction(it, KeyActions.SPACE) && it.width > 1.0 } ?: return row
        out = out.map { key ->
            when {
                key === space -> key.copy(width = key.width - 1.0)
                key === mic -> key.copy(width = key.width + 1.0)
                else -> key
            }
        }
        return out
    }
}
