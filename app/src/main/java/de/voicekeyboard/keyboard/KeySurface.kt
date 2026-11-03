package de.voicekeyboard.keyboard

import kotlin.math.min

/**
 * D11 (docs/DESIGN_STOCK_LOOK.md "D11 — Pressed key feedback", from line
 * 240): pure, JVM-testable surface/glyph state decision — NO
 * Android type; KeyboardView calls [decide per key while drawing.
 *
 * RULES (binding order, in this priority):
 *  1. `micRecording` — highest priority (Mic recording state, D10 unchanged):
 *     surface MIC_RECORDING (accent token), glyph CONTRAST (micRecordingGlyph).
 *  2. `CAPS_FILL` — shift key with LATCHED `capsLock` (letter layers only):
 *     accent surface permanently, glyph in the contrary color. The fill
 *     is derived ONLY from a real capsLock — never from shiftTemporary and never
 *     from symbolsShift. It stays while pressed (D11: "pressed+caps keeps this
 *     accent fill … without an additional overlay pass") —
 *     deliberate resolution of the naive chain "pressed > capsLock" in favor of
 *     the D11 decision; a second overlay pass on the accent surface is
 *     ruled out.
 *  3. `pressed` — press overlay (D11 point 1, reinforced opacity), glyph
 *     unchanged (pressed does not change the text color).
 *  4. `normal` — stock surface, role glyph color.
 *
 * Glyph tokens (KeyboardView maps to existing theme tokens):
 *  - CONTRAST → micRecordingGlyph (contrary color to accent surfaces — no accent
 *    on accent); ACCENT → keyToggledGlyph (glyph-only accentuation of the
 *    temporary shift and the symbol shift toggle, as before); NORMAL →
 *    role color (textPrimary/textFunctional/textSecondary).
 */
object KeySurface {

    /** Surface state of the key (order of characters see the class comment). */
    enum class Surface { NORMAL, PRESSED, CAPS_FILL, MIC_RECORDING }

    /** Glyph color request (token; KeyboardView picks the concrete color). */
    enum class Glyph { NORMAL, ACCENT, CONTRAST }

    data class Decision(val surface: Surface, val glyph: Glyph)

    fun decide(
        istShiftKey: Boolean,
        capsLock: Boolean,
        shiftTemporary: Boolean,
        symbolShift: Boolean,
        pressed: Boolean,
        micRecording: Boolean,
        activeLayerIsSymbols: Boolean,
    ): Decision {
        // Surface (priority: micRecording > CAPS_FILL > pressed > normal).
        val capsFill = istShiftKey && capsLock && !symbolShift && !activeLayerIsSymbols
        val surface = when {
            micRecording -> Surface.MIC_RECORDING
            capsFill -> Surface.CAPS_FILL // stays while pressed too (no double pass)
            pressed -> Surface.PRESSED
            else -> Surface.NORMAL
        }
        // Glyph: contrary to the fill (CAPS/MIC ⇒ contrary color); toggle states
        // (temporary shift, symbol shift) keep the accented glyph
        // ALSO over the press overlay (existing behavior, D11 §1 color states);
        // a pure press surface never changes the text color.
        val glyph = when {
            micRecording -> Glyph.CONTRAST
            capsFill -> Glyph.CONTRAST
            istShiftKey && (shiftTemporary || symbolShift) -> Glyph.ACCENT
            else -> Glyph.NORMAL
        }
        return Decision(surface, glyph)
    }

    /**
     * F23a2 (DESIGN_KEY_POPUP.md §7.2): hint anchor top right — text center
     * = right key edge minus lateral inner padding [padPx] (POPUP_PAD_DP)
     * minus half the measured ink width. Pure dimensions, no Android.
     */
    fun hintAnchorX(keyRight: Float, padPx: Float, inkWidth: Float): Float =
        keyRight - padPx - inkWidth / 2f

    /**
     * F23a2 (§7.2): vertical distance key top edge → visible glyph edge
     * in dp: (padDp / 2) × min(1, h / target height) — nominally 2 dp at full
     * row height, relatively smaller with shrunken rows, NEVER above padDp/2
     * and NEVER negative (broken/empty height -> 0).
     */
    fun hintTopOffsetDp(padDp: Float, actualRowHeightDp: Double, targetRowHeightDp: Int): Float {
        if (targetRowHeightDp <= 0 || actualRowHeightDp <= 0.0) return 0f
        val scale = min(1.0, actualRowHeightDp / targetRowHeightDp).toFloat()
        return (padDp / 2f * scale).coerceAtLeast(0f)
    }
}
