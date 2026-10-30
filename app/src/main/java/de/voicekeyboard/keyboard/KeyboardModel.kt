package de.voicekeyboard.keyboard

import de.voicekeyboard.layouts.KeyActions
import de.voicekeyboard.layouts.KeyKind
import de.voicekeyboard.layouts.LayoutKey
import de.voicekeyboard.layouts.LayoutLayer
import kotlin.math.abs

/**
 * Misst eine Layout-Ebene in Pixel: relative Breiten → px, inklusive Lücken.
 * Reine JVM-Logik (kein Android-Typen) und damit unit-testbar.
 *
 * T20/S1: Die Reihenhöhe folgt dem übergebenen Höhen-Fit ([fit], von
 * KeyboardView bei JEDEM onMeasure neu bestimmt und als Parameter
 * durchgereicht — T21: kein globaler Zustand): bei begrenztem Angebot werden
 * ALLE Reihen SKALIERT — kein Row-Drop, keine halb gezeichnete Taste.
 * Ein Fit mit abweichender Reihenzahl (z. B. nach Ebenenwechsel, bevor die
 * nächste Messung läuft) wird defensiv ignoriert.
 */
class KeyboardModel(
    layer: LayoutLayer,
    private val totalWidthPx: Float,
    keyHeightPx: Float,
    rowGapPx: Float,
    val keyGapPx: Float,
    private val horizontalPaddingPx: Float = 0f,
    /**
     * S1/S2-Höhen-Fit; null = feste Maße. T21: BEWUSST null als Default — kein
     * globales `KeyboardHeight.lastFit` mehr: der Fit wird als Parameter von
     * KeyboardView.rebuildModel durchgereicht, damit niemals ein Fit aus einer
     * früheren Öffnung/Sitzung weiterwirkt.
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

    /** Effektive (ggf. skalierte) Reihenhöhe — S1; fest bei fit == null. */
    val effectiveRowHeightPx: Float

    /** Effektive (ggf. skalierte) Reihenlücke (Treffer-Toleranz, wie bisher). */
    val effectiveRowGapPx: Float

    init {
        require(totalWidthPx > 0) { "totalWidthPx muss > 0 sein" }
        require(keyHeightPx > 0) { "keyHeightPx muss > 0 sein" }
        // S1: Fit nur anwenden, wenn er zu DIESER Ebene passt (gleiche Reihenzahl)
        // und die Reihenhöhe verkleinert (Skala ≤ 1) — niemals vergrößern.
        val applied = fit?.takeIf { it.rows == layer.rows.size && it.rowPx > 0 }
        effectiveRowHeightPx = ((applied?.rowPx?.toFloat()) ?: keyHeightPx).coerceAtMost(keyHeightPx)
        effectiveRowGapPx = (applied?.rowGapPx?.toFloat()) ?: rowGapPx
        val out = mutableListOf<MeasuredKey>()
        val usable = totalWidthPx - 2 * horizontalPaddingPx
        var top = 0f
        for (row in D10_FOOTER.compose(layer.rows)) {
            val sum = row.sumOf { it.width }
            require(sum > 0) { "Zeile ohne Breite" }
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
     * Treffererkennung: Punkt liegt in einer Taste ODER in der Lücke zwischen
     * Tasten — dann gewinnt die nächstgelegene Taste (Mittenabstand).
     * Gibt null zurück, wenn der Punkt komplett außerhalb liegt.
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
        // Randbereiche: links/rechts außerhalb → nichts (kein Phantom-Tasten)
        return null
    }

    private fun gapTolerance(): Float = effectiveRowGapPx
}

/**
 * D10 §1 (docs/DESIGN_MIC_AND_GLOBE.md, Kapitel 1 „Reflow: Enter rückt vor, Mic
 * bekommt den rechten Rand“): gemeinsame DARSTELLUNGSKOMPOSITION des Footers
 * für alle 15×4 Ebenen — ID, Aktion, Label und Alternates bleiben unverändert,
 * 0 JSON-Änderungen:
 *
 *  - Reihenfolge: `?123/ABC · 🌐 · 😊 · Space · ‹ · › · Enter · Mic` — Mic wandert
 *    ans äußerst rechte Ende (hinter Enter); keine Buchstabentaste verdrängt.
 *  - Gewichte: Mic 1 → **2**, Space gibt genau eine Einheit ab (5 → 4),
 *    Gesamtsumme bleibt 13; die letzte rechte Kante ist exakt W.
 *  - Zeichnung, Hit-Test UND Accessibility lesen dieselbe komponierte Quelle
 *    (KeyboardModel) — keine nur gemalte große Mic-Fläche.
 *  - Reihen ohne Mic+Enter (Buchstabenreihen) bleiben unberührt.
 */
internal object D10_FOOTER {

    /** Reflow alle Reihen einer Ebene (nur dieMic+Enter-Zeile wird angefasst). */
    fun compose(rows: List<List<LayoutKey>>): List<List<LayoutKey>> = rows.map { row -> composeRow(row) }

    private fun composeRow(row: List<LayoutKey>): List<LayoutKey> {
        fun isAction(key: LayoutKey, action: String) =
            key.kind == KeyKind.ACTION && key.value == action

        val mic = row.firstOrNull { isAction(it, KeyActions.MIC) } ?: return row
        val enter = row.firstOrNull { isAction(it, KeyActions.ENTER) } ?: return row

        // Mic ans äußerst rechte Ende, Enter rückt auf die vorletzte Position.
        var out = row.filterNot { it === mic || it === enter } + enter + mic

        // Space gibt genau eine Einheit ab (5 → 4), Mic erhält sie (1 → 2).
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
