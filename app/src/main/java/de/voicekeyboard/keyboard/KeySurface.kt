package de.voicekeyboard.keyboard

import kotlin.math.min

/**
 * D11 (docs/DESIGN_STOCK_LOOK.md „D11 — Rückmeldung gedrückte Taste", ab Zeile
 * 240): Reine, JVM-testbare Entscheidung Flächen-/Glyphen-Zustand — KEIN
 * Android-Typ; KeyboardView ruft [decide] beim Zeichnen je Taste auf.
 *
 * REGELN (verbindliche Reihenfolge, in dieser Priorität):
 *  1. `micRecording` — höchste Priorität (Mic-Aufnahmezustand, D10 unverändert):
 *     Fläche MIC_RECORDING (accent-Token), Glyphe CONTRAST (micRecordingGlyph).
 *  2. `CAPS_FILL` — Shift-Taste mit EINGERASTETEM `capsLock` (nur Buchstaben-
 *     ebenen): Akzentfläche dauerhaft, Glyphe in der Gegenfarbe. Die Füllung
 *     wird NUR aus echtem capsLock abgeleitet — nie aus shiftTemporary und nie
 *     aus symbolsShift. Sie bleibt auch beim Drücken (D11: „Gedrückt+Caps
 *     behält diese Akzentfüllung … ohne zusätzlichen Overlayauftrag") —
 *     bewusste Auflösung der naive Kette „pressed > capsLock" zugunsten des
 *     D11-Entscheids; ein zweiter Overlayauftrag auf der Akzentfläche ist
 *     ausgeschlossen.
 *  3. `pressed` — Press-Overlay (D11-Punkt 1, verstärkte Deckkraft), Glyphe
 *     unverändert (gedrückt ändert die Textfarbe nicht).
 *  4. `normal` — Stock-Fläche, Rollen-Glyphfarbe.
 *
 * Glyphen-Tokens (KeyboardView mappt auf vorhandene Theme-Tokens):
 *  - CONTRAST → micRecordingGlyph (Gegenfarbe zu Akzent-Flächen — kein Akzent
 *    auf Akzent); ACCENT → keyToggledGlyph (nur-Glyphen-Akzentuierung des
 *    temporären Shifts und des Symbol-Shift-Toggles, wie bisher); NORMAL →
 *    Rollenfarbe (textPrimary/textFunctional/textSecondary).
 */
object KeySurface {

    /** Flächenzustand der Taste (Zeichenreihenfolge siehe Klassen-Kommentar). */
    enum class Surface { NORMAL, PRESSED, CAPS_FILL, MIC_RECORDING }

    /** Glyphen-Farbwunsch (Token; KeyboardView wählt die konkrete Farbe). */
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
        // Fläche (Priorität: micRecording > CAPS_FILL > pressed > normal).
        val capsFill = istShiftKey && capsLock && !symbolShift && !activeLayerIsSymbols
        val surface = when {
            micRecording -> Surface.MIC_RECORDING
            capsFill -> Surface.CAPS_FILL // bleibt auch gedrückt (kein Doppelauftrag)
            pressed -> Surface.PRESSED
            else -> Surface.NORMAL
        }
        // Glyphe: konträr zur Füllung (CAPS/MIC ⇒ Gegenfarbe); Toggle-Zustände
        // (temporäres Shift, Symbol-Shift) behalten die akzentuierte Glyphe
        // AUCH über dem Press-Overlay (bisheriges Verhalten, D11 §1 Farbzustände);
        // eine reine Press-Fläche ändert die Textfarbe nie.
        val glyph = when {
            micRecording -> Glyph.CONTRAST
            capsFill -> Glyph.CONTRAST
            istShiftKey && (shiftTemporary || symbolShift) -> Glyph.ACCENT
            else -> Glyph.NORMAL
        }
        return Decision(surface, glyph)
    }

    /**
     * F23a2 (DESIGN_KEY_POPUP.md §7.2): Hint-Anker rechts oben — Textmittelpunkt
     * = rechte Tastenkante minus seitlichem Innenabstand [padPx] (POPUP_PAD_DP)
     * minus halber gemessener Inkbreite. Reine Maße, kein Android.
     */
    fun hintAnchorX(keyRight: Float, padPx: Float, inkWidth: Float): Float =
        keyRight - padPx - inkWidth / 2f

    /**
     * F23a2 (§7.2): vertikaler Abstand Tastenoberkante → sichtbare Glyphenkante
     * in dp: (padDp / 2) × min(1, h / Sollhöhe) — nominal 2 dp bei voller
     * Zeilenhöhe, relativ kleiner bei geschrumpften Reihen, NIE über padDp/2
     * und NIE negativ (kaputte/leere Höhe -> 0).
     */
    fun hintTopOffsetDp(padDp: Float, actualRowHeightDp: Double, targetRowHeightDp: Int): Float {
        if (targetRowHeightDp <= 0 || actualRowHeightDp <= 0.0) return 0f
        val scale = min(1.0, actualRowHeightDp / targetRowHeightDp).toFloat()
        return (padDp / 2f * scale).coerceAtLeast(0f)
    }
}
