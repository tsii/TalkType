package de.voicekeyboard.keyboard

/**
 * S3 — Stock-Look-Tokens (docs/DESIGN_STOCK_LOOK.md §1 „Konkrete Geometrie und
 * Typografie" + „Farben – ARGB, Hell/Dunkel"). Reine JVM-Werte, kein Android-
 * Import, damit die Token-Matrix als Zahlen getestet ist (KeyboardThemeTest).
 *
 * FARBTABELLE — Quelle: AOSP LatinIME (android16-release, java/res/values/
 * colors.xml:43–78, themes-lxx-light.xml:45–55, themes-lxx-dark.xml:45–55):
 *
 *  | Token                    | Hell      | Dunkel    | Herkunft                       |
 *  |--------------------------|-----------|-----------|--------------------------------|
 *  | Hintergrund / Taste norm.| #FFECEFF1 | #FF263238 | AOSP key_background_lxx_*      |
 *  | Gedrückt (Overlay)       | #2637474F | #19FFFFFF | AOSP key_background_pressed_*  |
 *  | Buchstaben-/Zifferntext   | #FF37474F | #CCFFFFFF | AOSP key_text_color_lxx_*      |
 *  | Funktionstext             | #CC37474F | #CCFFFFFF | AOSP functional                |
 *  | Sekundär/Space            | #B337474F | #B3FFFFFF | AOSP sekundäre Space-Farben    |
 *  | Akzent                    | #FF4DB6AC | #FF80CBC4 | AOSP highlight_color_lxx_*     |
 *  | Trennlinie                | #2637474F | #26FFFFFF | eigener 1-dp-Separator         |
 *  | Fehlerstatus              | #FFB3261E | #FFFFB4AB | eigene Fehlerfarbe             |
 *  | Toggle-Glyphe (Shift/Caps)| #FF00796B | #FF80CBC4 | eigene lesbare Hell-Variante   |
 *
 * Gedrückt ist ein ALPHA-OVERLAY über der vorher gefüllten Tastaturfläche
 * ([compose], SrcOver) — kein alleiniger transparenter Fensterfarbton; die
 * gedrückte Fläche ist deckend (Alpha 0xFF nach der Komposition).
 * Normale Tasten sind IDENTISCH zum Hintergrund (flache Fläche, keine
 * Einzelkarten, Radius 0).
 *
 * TYPOGRAFIE (Sol §1): Buchstaben 24 sp, Ziffern 22 sp, Funktion 14 sp,
 * Space/Status 12 sp — als sp mit gleichem Nennwert bei fontScale=1.
 *
 * GEOMETRIE (Sol §1, „Delta"-Zeile keyHeight/rowGap): 55 dp je Reihe bei
 * vier Reihen (220 dp zusammen), 44 dp je Reihe bei fünf (PT, 220 dp);
 * Reihenlücke 0 dp, Tastenlücke 0 dp, Eckenradius 0 dp.
 */
data class KeyboardPalette(
    /** Root-/Tastatur-Hintergrund (deckend). */
    val background: Int,
    /** Fläche einer normalen Taste — identisch zum Hintergrund (keine Kartenoptik). */
    val keyNormal: Int,
    /** Komponierte Pressfläche: Overlay über [background] (deckend). */
    val keyPressed: Int,
    /** Glyphe im Toggle-Zustand (Shift/Caps aktiv) — KEINE gefärbte ganze Taste. */
    val keyToggledGlyph: Int,
    /** Buchstaben-/Zifferntext. */
    val textPrimary: Int,
    /** Funktionstext (?123/ABC, Backspace, Enter, …). */
    val textFunctional: Int,
    /** Sekundärer Text (Space-Wort, Status). */
    val textSecondary: Int,
    /** Dezenter 1-dp-Separator (unter der Statuszeile). */
    val separator: Int,
    /** Akzent (Aufnahme-Indikator, Hervorhebung). */
    val accent: Int,
    /**
     * Glyph auf der Aufnahme-Flaeche (Mic-Symbol waehrend der Aufnahme).
     * D10 §2: bewusst NICHT [textPrimary] — im Dunkelmodus waere das
     * `#CCFFFFFF` auf der hellen Akzentflaeche `#80CBC4` (Kontrast ~1,4:1).
     */
    val micRecordingGlyph: Int,
    /** Fehlerstatus-Farbe. */
    val error: Int,
)

object KeyboardTheme {

    // ---- Schrift-Hierarchie (sp, Sol §1) ----
    const val SP_LETTER_SP = 24f
    const val SP_DIGIT_SP = 22f
    const val SP_FUNCTION_SP = 14f
    const val SP_SECONDARY_SP = 12f

    // ---- Geometrie-Nennwerte (Sol §1: 268 dp = 48 + 220) ----
    const val ROW_HEIGHT_4_ROWS_DP = 55
    const val ROW_HEIGHT_5_ROWS_DP = 44
    const val ROW_GAP_DP = 0
    const val KEY_GAP_DP = 0
    const val KEY_RADIUS_DP = 0f

    /** Sol §1: 55 dp bei bis zu vier Reihen, 44 dp ab fünf (PT bleibt fünfzeilig). */
    fun rowHeightDp(rows: Int): Int = if (rows >= 5) ROW_HEIGHT_5_ROWS_DP else ROW_HEIGHT_4_ROWS_DP

    val LIGHT = KeyboardPalette(
        background = 0xFFECEFF1.toInt(),
        keyNormal = 0xFFECEFF1.toInt(),
        keyPressed = compose(0xFFECEFF1.toInt(), 0x2637474F),
        keyToggledGlyph = 0xFF00796B.toInt(),
        textPrimary = 0xFF37474F.toInt(),
        textFunctional = 0xCC37474F.toInt(),
        textSecondary = 0xB337474F.toInt(),
        separator = 0x2637474F,
        accent = 0xFF4DB6AC.toInt(),
        micRecordingGlyph = 0xFF37474F.toInt(), // D10 §2 Hell
        error = 0xFFB3261E.toInt(),
    )

    val DARK = KeyboardPalette(
        background = 0xFF263238.toInt(),
        keyNormal = 0xFF263238.toInt(),
        keyPressed = compose(0xFF263238.toInt(), 0x19FFFFFF),
        keyToggledGlyph = 0xFF80CBC4.toInt(),
        textPrimary = 0xCCFFFFFF.toInt(),
        textFunctional = 0xCCFFFFFF.toInt(),
        textSecondary = 0xB3FFFFFF.toInt(),
        separator = 0x26FFFFFF,
        accent = 0xFF80CBC4.toInt(),
        micRecordingGlyph = 0xFF263238.toInt(), // D10 §2 Dunkel: dunkel auf heller Teal
        error = 0xFFFFB4AB.toInt(),
    )

    /** System Hell/Dunkel entscheidet die Palette (kein Ziel-App-Theme-Raten). */
    fun palette(isDark: Boolean): KeyboardPalette = if (isDark) DARK else LIGHT

    /**
     * Overlay-Komposition (SrcOver, reine JVM-Logik): [overlay] liegt über der
     * vorher gefüllten Fläche [base] — Ergebnis deckend, Kanäle geblendet.
     * Grundlage der Presszustände: „keine alleinige transparente Fensterfarbe;
     * dadurch scheint kein Ziel-App-Inhalt durch" (Sol §1).
     */
    fun compose(base: Int, overlay: Int): Int {
        val bA = (base ushr 24) and 0xFF
        val bR = (base ushr 16) and 0xFF
        val bG = (base ushr 8) and 0xFF
        val bB = base and 0xFF
        val oA = (overlay ushr 24) and 0xFF
        val oR = (overlay ushr 16) and 0xFF
        val oG = (overlay ushr 8) and 0xFF
        val oB = overlay and 0xFF
        val inv = 255 - oA
        val aA = oA + (bA * inv) / 255
        val aR = (oR * oA + bR * inv) / 255
        val aG = (oG * oA + bG * inv) / 255
        val aB = (oB * oA + bB * inv) / 255
        return (aA shl 24) or (aR shl 16) or (aG shl 8) or aB
    }
}
