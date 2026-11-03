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
 * TYPOGRAFIE (Sol §1, T2/vc36): Buchstaben 26 sp, Ziffern 24 sp, Funktion 14 sp,
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
    /** T2 (v0.1.35, Nutzerwunsch vc34 „~8–10 % größer"): Hauptzeichen-Buchstabe
     *  24 → 26 sp (+8,3 %). NUR Hauptzeichen — Footer-/Funktions- und Sekundär-
     *  Labels, Space, Hint-Legende (F23a2-Deckel) und Popup-Zellen bleiben
     *  unskaliert. Raster-Fit: 26 sp ≈ 68 px @ Referenzdichte 2,625 — frei von
     *  der 1u=108-px-Zelle und den 2-px-Fugen (T2-Test). */
    const val SP_LETTER_SP = 26f

    /** T2 (v0.1.35): Ziffern 22 → 24 sp (+9,1 %) — Hauptzeichen-Rolle wie Buchstaben. */
    const val SP_DIGIT_SP = 24f

    const val SP_FUNCTION_SP = 14f
    const val SP_SECONDARY_SP = 12f
    /** F23a2 Hint-Legende: 0,5 x SP_LETTER_SP (docs/DESIGN_KEY_POPUP.md §7.1) —
     *  T2: bleibt 12 sp (Deckel wirkt: 0,5 × 26 = 13 > 12). */
    const val SP_KEY_HINT_SP = 12f

    // ---- Geometrie-Nennwerte (Sol §1: 268 dp = 48 + 220) ----
    const val ROW_HEIGHT_4_ROWS_DP = 55
    const val ROW_HEIGHT_5_ROWS_DP = 44
    const val ROW_GAP_DP = 0
    const val KEY_GAP_DP = 0
    /** F36/V2 (docs/DESIGN_TASTATUR_V2.md §7): Eckenradius 10 px @ Referenzdichte
     *  2,625 → dp-Nennwert (skaliert auf anderen Dichten mit). */
    const val KEY_RADIUS_DP = 10f / 2.625f
    /** F36/V2 §7: Tastenfläche = Vollrechteck minus 2 px Fuge JE SEITE (Fuge zeigt
     *  den Hintergrund; 2 px je Seite ⇒ 4 px sichtbare Fuge zwischen Tasten —
     *  einzige Tastenabgrenzung, keine eigenen Trennlinien). */
    const val KEY_FUGE_DP = 2f / 2.625f

    // ---- F22 D12: Langdruckmenü-Maße (docs/DESIGN_KEY_POPUP.md §1) — benannte
    // Designwerte, KEINE neuen Farben (Fläche palette.keyPressed, Zeichen
    // palette.textPrimary, Auswahl palette.accent/micRecordingGlyph) ----
    /** Zelle 48×48 dp (nicht Grundtastenbreite); Höhe des Menüs 56 dp. */
    const val POPUP_CELL_DP = 48f
    const val POPUP_HEIGHT_DP = 56f
    /** Innen-/Zwischenpolster 4 dp; Abstand zur Grundtaste und zum Rand 8 dp. */
    const val POPUP_PAD_DP = 4f
    const val POPUP_GAP_DP = 8f
    /** Außenradius 4 dp, nur Menü-Außenkante; kein Rahmen/Schatten/Zeigerspitze. */
    const val POPUP_RADIUS_DP = 4f
    /** Zeichengröße 24 sp, normaler Typeface (§1). */
    const val POPUP_GLYPH_SP = 24f
    /** Bewegung > 8 dp vom DOWN bricht das Bewaffnen endgültig ab (§2). */
    const val POPUP_MOVE_CANCEL_DP = 8f
    /** Langdruck-Schwelle 450 ms (§2-Tabelle Z. 94); DOUBLE_TAP_MS=350 bleibt unberührt. */
    const val LONG_PRESS_MS = 450L

    /** F27b: Shift/Backspace/Enter-Icons größer — 24 dp (≈1.3× der 14-sp-Funktionsgröße),
     *  wie das 26-dp-Mic in dp (density, kein scaledDensity). */
    const val ACTION_GLYPH_DP = 24f

    /** F29: Strichstärke der drei Action-Glyphen (⇧/⌫/⏎) — IDENTISCH für alle drei,
     *  als Canvas-Pfade gezeichnet (Font-Glyphen zeichnen pro Zeichen unterschiedlich
     *  dicke Linien; Nutzerwunsch: gleiche Strichstärke).
     *  F39 (Nutzer-Feedback v0.1.27): deutlich dünner — Glyphen dürfen NICHT dicker
     *  wirken als die Buchstaben (24-sp-Buchstabenstrich als Maßstab): 3,5 → 2,0 dp. */
    const val ACTION_GLYPH_STROKE_DP = 2.0f

    /** F28a: Höhe der Status-/Info-Leiste (ImeRootView statusRow, 48 dp) — Massstab für
     *  das Langdruckmenü der obersten Zeile: die Palette beginnt bei Root-y=0 (also
     *  -STATUS_LINE_DP im KeyboardView-Koordinatensystem), d. h. direkt unterhalb der
     *  Statusleiste. Gleicher Wert wie die 48-dp-Statuszeile in ImeRootView. */
    const val STATUS_LINE_DP = 48f

    /**
     * T2 (v0.1.35, Nutzerbefund Pixel/vc34): reine Geometrie der ⇧-Glyphe als
     * Anteile der Bindekiste s — der Pfeil (Kopf + Balken) ist von der
     * horizontalen SOCKELLEISTE abgesetzt, in EXAKTER Korrespondenz zum ⌫, wo
     * der Kopf 0,18·s vom vertikalen Sockel abgesetzt gezeichnet ist
     * ([BACKSPACE_HEAD_SOCKEL_GAP_RATIO]). Vorher stand der Schaft nur 0,07·s
     * über der Linie — mit dem 2-dp-Strich (runde Enden, ±0,083·s) verschmolzen:
     * „Pfeil steht auf der Linie auf". Nur die Shift-Glyphe — ⌫/⏎ unangetastet.
     * Das 0,60·s-Optikhöhe-Gate (F29/P0) bleibt: Sockelleiste = Box-Unterkante,
     * Pfeilspitze = Box-Oberkante; nur der Pfeilfuß rückt hoch.
     */
    object ShiftGlyph {

        /** Sockelleiste (horizontale Linie) — y-Anteil der Box-Unterkante (0,60·s). */
        const val BASE_LINE_RATIO = 0.30f

        /** T2: Abstand Pfeilfuß ↔ Sockelleiste — identisch zum ⌫-Kopf↔Sockel-Abstand. */
        const val BASE_GAP_RATIO = 0.18f

        /** ⌫-Kopf↔Sockel-Abstand (0,465 − 0,285) — Quelle der Korrespondenz (F36/V2 §8). */
        const val BACKSPACE_HEAD_SOCKEL_GAP_RATIO = 0.465f - 0.285f

        /** T2: Pfeilfuß (Schaft-Unterkante) im Bindekisten-Maß —
         *  shift-kopf-y = linien-y − offset, offset > 0: 0,30 − 0,18 = 0,12
         *  (vorher 0,23 — verschmolzen, siehe KDoc). Reine Funktion, JVM-testbar. */
        fun arrowBottomRatio(): Float = BASE_LINE_RATIO - BASE_GAP_RATIO
    }

    /** Sol §1: 55 dp bei bis zu vier Reihen, 44 dp ab fünf (PT bleibt fünfzeilig). */
    fun rowHeightDp(rows: Int): Int = if (rows >= 5) ROW_HEIGHT_5_ROWS_DP else ROW_HEIGHT_4_ROWS_DP

    /** F36/V2 (§7 „Farben", normativ): helle Palette — Taste HELLER als der
     *  Hintergrund (Kartenoptik, sichtbar durch die 4-px-Fuge), gedrückt als
     *  DIREKTE Spec-Farbe (kein Overlay mehr), Text/Akzent nach der V2-Tabelle
     *  (Text/Taste 17,09:1 → WCAG AAA). Nicht in V2 definiert: textFunctional,
     *  textSecondary, error — behalten ihre AOSP-Nennwerte. */
    val LIGHT = KeyboardPalette(
        background = 0xFFF2F3F5.toInt(),
        keyNormal = 0xFFFFFFFF.toInt(),
        keyPressed = 0xFFDCE7E6.toInt(), // V2 §7 direkt — keine compose()-Überlagerung
        keyToggledGlyph = 0xFF2E7D74.toInt(), // V2 §7 Akzent (Icon ⇧/⌫/⏎, Shift-aktiv)
        textPrimary = 0xFF1A1C1E.toInt(),
        textFunctional = 0xCC37474F.toInt(),
        textSecondary = 0xB337474F.toInt(),
        separator = 0xFFE0E2E5.toInt(), // V2 §7 Trennlinie (1 px unter der Statusleiste)
        accent = 0xFF2E7D74.toInt(),
        micRecordingGlyph = 0xFFFFFFFF.toInt(), // Gegenfarbe auf Akzent #2E7D74 (≈ 4,6:1)
        error = 0xFFB3261E.toInt(),
    )

    /** F36/V2 (§7): dunkle Palette — gleiche Rollen wie hell (s. d.). */
    val DARK = KeyboardPalette(
        background = 0xFF202124.toInt(),
        keyNormal = 0xFF2B2D31.toInt(),
        keyPressed = 0xFF3A4543.toInt(), // V2 §7 direkt
        keyToggledGlyph = 0xFF80CBC4.toInt(),
        textPrimary = 0xFFE8EAED.toInt(),
        textFunctional = 0xCCFFFFFF.toInt(),
        textSecondary = 0xB3FFFFFF.toInt(),
        separator = 0xFF3C3F44.toInt(),
        accent = 0xFF80CBC4.toInt(),
        micRecordingGlyph = 0xFF263238.toInt(), // Gegenfarbe auf Akzent #80CBC4 (≈ 7,4:1)
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
