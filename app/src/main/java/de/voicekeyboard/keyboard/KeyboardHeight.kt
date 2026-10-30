package de.voicekeyboard.keyboard

import kotlin.math.max
import kotlin.math.min

/**
 * T20/S1 — Höhenvertrag OHNE Row-Drop (korrigiert T19; docs/DESIGN_STOCK_LOOK.md
 * §3 „Ein einziger Höhenhaushalt"): Reine JVM-Logik, kein Android-Import.
 *
 * VERTRAG (S1):
 *  - ALLE vorhandenen Reihen bleiben erhalten — eine Reihe, die nicht passt,
 *    wird SKALIERT (kleinere Reihenhöhe), niemals halb gezeichnet und niemals
 *    entfernt („Row-Drop ist kein akzeptierter Fix").
 *  - Skala = min(1, (Angebot − Status − Navigation) / gewünschter Bedarf).
 *    Tastenbreiten bleiben proportional (Breite ist nicht Teil dieses Vertrags),
 *    die Beschriftungsgröße skaliert mit (untere Grenze = Parameter).
 *  - Statuszeile und Navigationsinset werden JE GENAU EINMAL abgezogen —
 *    Root-Padding und Child dürfen nicht beide abziehen (Doku-ANNAHME §3).
 *  - Nie höher als das Angebot (außer: Mindest-Tastenhöhe gewinnt) und nie
 *    höher als maxPx (Design-Kappe, z. B. 276 dp ≈ 725 px mit Navigation,
 *    268 dp ≈ 704 px ohne — density 420 = 2,625 px/dp; 276 dp = 724,5 px).
 *  - Angebot ≤ 0 (MeasureSpec.UNSPECIFIED) gilt als unbegrenzt: maxPx entscheidet.
 *  - Bei extrem kleinem Angebot wird die Mindest-Tastenhöhe GEHALTEN und das
 *    unterschrittene Angebot als [HeightFit.deficitPx] GEMELDET, nicht verschluckt.
 *  - Ergebnisse sind ganzzahlig (1-px-genau), Rundungsreste werden einmal auf
 *    die Reihen verteilt; die letzte Unterkante ist exakt das Inhaltsende.
 */
object KeyboardHeight {

    /** S1-Ergebnistyp: skalierte Höhe für ALLE Reihen (kein Row-Drop). */
    data class HeightFit(
        /** Anzahl belegter Reihen — immer ALLE (kein Row-Drop). */
        val rows: Int,
        /** Skalierte Reihenhöhe (px). */
        val rowPx: Int,
        /** Skalierte Reihenlücke (px). */
        val rowGapPx: Int,
        /** rows*rowPx + (rows−1)*rowGapPx — Unterkante der letzten Reihe. */
        val keysHeightPx: Int,
        /** status + nav + keysHeightPx — Gesamthöhe der View inkl. der einen Abzüge. */
        val viewHeightPx: Int,
        /** Skala ≤ 1 (1 = unskaliert). */
        val scale: Float,
        /** True, wenn die Mindest-Tastenhöhe das Budget überschrieben hat. */
        val minKeyHeld: Boolean,
        /** > 0: Budget wird unterschritten (Mindest-Tastenhöhe gehalten) — gemeldet. */
        val deficitPx: Int,
        /** Skalierte Beschriftungsgröße (px); 0 = keine skalierte Beschriftung gefordert. */
        val labelPx: Int,
    )

    /**
     * S1-Kernfunktion: berechnet den Höhen-Fit für ALLE [rows] Reihen.
     *
     * @param offeredPx       Root-Angebot (px; ≤ 0 = unbegrenzt → maxPx entscheidet).
     * @param rows            vorhandene Reihenzahl — alle bleiben erhalten.
     * @param desiredRowPx    gewünschte Reihenhöhe (px, > 0), z. B. 55 dp ≈ 144 px.
     * @param desiredRowGapPx gewünschte Reihenlücke (px, ≥ 0; Stock-Stil: 0).
     * @param statusLinePx    Statuszeile (px, ≥ 0) — GENAU EINMAL abgezogen.
     * @param navInsetPx      Navigationsinset (px, ≥ 0) — GENAU EINMAL abgezogen.
     * @param maxPx           Design-Kappe (px, > 0).
     * @param minKeyHeightPx  Mindest-Tastenhöhe (px, > 0 — Parameter, nie 0).
     * @param desiredLabelPx  gewünschte Beschriftungsgröße (px; 0 = nicht gefordert).
     * @param minLabelPx      untere Grenze der Beschriftung (px, ≥ 0).
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
        require(rows >= 1) { "rows muss >= 1 sein" }
        require(desiredRowPx > 0) { "desiredRowPx muss > 0 sein" }
        require(desiredRowGapPx >= 0 && statusLinePx >= 0 && navInsetPx >= 0) { "gap/status/nav >= 0" }
        require(minKeyHeightPx > 0) { "minKeyHeightPx muss > 0 sein (Vertrag S1)" }
        require(minLabelPx >= 0) { "minLabelPx >= 0" }
        require(maxPx > 0) { "maxPx muss > 0 sein" }

        // Haushalt: Angebot (unbegrenzt → maxPx), gekappt durch maxPx.
        val budget = if (offeredPx <= 0) maxPx else min(offeredPx, maxPx)
        // Status UND Navigation JE GENAU EINMAL abziehen.
        val keysBudget = budget - statusLinePx - navInsetPx
        // Gewünschter Bedarf aller vorhandenen Reihen.
        val need = rows * desiredRowPx + (rows - 1) * desiredRowGapPx
        // Skala = min(1, Haushalt / Bedarf) — NIE hochskalieren.
        val scale = if (need <= 0) 1f else min(1f, keysBudget.toFloat() / need.toFloat())

        var rowPx = (desiredRowPx * scale).toInt() // abrunden — nie hoch über Budget
        val rowGapPx = (desiredRowGapPx * scale).toInt()
        var minKeyHeld = false
        if (rowPx < minKeyHeightPx) {
            rowPx = minKeyHeightPx // Minimum GEHALTEN; Defizit wird unten gemeldet
            minKeyHeld = true
        } else if (scale < 1f) {
            // Rundungsreste einmal auf die Reihen verteilen (letzte Unterkante
            // möglichst am Inhaltsende), ohne das Budget zu überschreiten.
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
