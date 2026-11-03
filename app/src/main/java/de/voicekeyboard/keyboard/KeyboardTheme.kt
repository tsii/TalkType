package de.voicekeyboard.keyboard

/**
 * S3 — stock-look tokens (docs/DESIGN_STOCK_LOOK.md §1 „Konkrete Geometrie und
 * Typografie“ + „Farben – ARGB, Hell/Dunkel“). Pure JVM values, no Android
 * import, so that the token matrix is tested as numbers (KeyboardThemeTest).
 *
 * COLOR TABLE — source: AOSP LatinIME (android16-release, java/res/values/
 * colors.xml:43–78, themes-lxx-light.xml:45–55, themes-lxx-dark.xml:45–55):
 *
 *  | Token                    | Light     | Dark      | Origin                         |
 *  |--------------------------|-----------|-----------|--------------------------------|
 *  | background / key normal  | #FFECEFF1 | #FF263238 | AOSP key_background_lxx_*      |
 *  | pressed (overlay)        | #2637474F | #19FFFFFF | AOSP key_background_pressed_*  |
 *  | letter/digit text        | #FF37474F | #CCFFFFFF | AOSP key_text_color_lxx_*      |
 *  | function text            | #CC37474F | #CCFFFFFF | AOSP functional                |
 *  | secondary/space          | #B337474F | #B3FFFFFF | AOSP secondary space colors     |
 *  | accent                   | #FF4DB6AC | #FF80CBC4 | AOSP highlight_color_lxx_*     |
 *  | separator                | #2637474F | #26FFFFFF | own 1-dp separator             |
 *  | error state              | #FFB3261E | #FFFFB4AB | own error color                 |
 *  | toggle glyph (Shift/Caps)| #FF00796B | #FF80CBC4 | own readable light variant      |
 *
 * Pressed is an ALPHA OVERLAY over the previously filled keyboard surface
 * ([compose], SrcOver) — not a lone transparent window tint; the
 * pressed surface is opaque (alpha 0xFF after the composition).
 * Normal keys are IDENTICAL to the background (flat surface, no
 * individual cards, radius 0).
 *
 * TYPOGRAPHY (spec §1, T2/vc36): letters 26 sp, digits 24 sp, function 14 sp,
 * space/status 12 sp — as sp with the same nominal value at fontScale=1.
 *
 * GEOMETRY (spec §1, "Delta" row keyHeight/rowGap): 55 dp per row with
 * four rows (220 dp total), 44 dp per row with five (PT, 220 dp);
 * row gap 0 dp, key gap 0 dp, corner radius 0 dp.
 */
data class KeyboardPalette(
    /** Root/keyboard background (opaque). */
    val background: Int,
    /** Surface of a normal key — identical to the background (no card look). */
    val keyNormal: Int,
    /** Composed press surface: overlay over [background] (opaque). */
    val keyPressed: Int,
    /** Glyph in the toggle state (Shift/Caps active) — NOT a colored whole key. */
    val keyToggledGlyph: Int,
    /** Letter/digit text. */
    val textPrimary: Int,
    /** Function text (?123/ABC, backspace, enter, …). */
    val textFunctional: Int,
    /** Secondary text (space word, status). */
    val textSecondary: Int,
    /** Subtle 1-dp separator (below the status row). */
    val separator: Int,
    /** Accent (recording indicator, highlight). */
    val accent: Int,
    /**
     * Glyph on the recording surface (Mic symbol while recording).
     * D10 §2: deliberately NOT [textPrimary] — in dark mode that would be
     * `#CCFFFFFF` on the light accent surface `#80CBC4` (contrast ~1.4:1).
     */
    val micRecordingGlyph: Int,
    /** Error state color. */
    val error: Int,
)

object KeyboardTheme {

    // ---- type hierarchy (sp, spec §1) ----
    /** T2 (v0.1.35, user request vc34 "~8–10 % larger"): main-character letter
     *  24 → 26 sp (+8.3 %). ONLY the main character — footer/function and secondary
     *  labels, space, hint legend (F23a2 cap) and popup cells stay
     *  unscaled. Grid fit: 26 sp ≈ 68 px @ reference density 2.625 — free of
     *  the 1u=108-px cell and the 2-px gaps (T2 test). */
    const val SP_LETTER_SP = 26f

    /** T2 (v0.1.35): digits 22 → 24 sp (+9.1 %) — main-character role like letters. */
    const val SP_DIGIT_SP = 24f

    const val SP_FUNCTION_SP = 14f
    const val SP_SECONDARY_SP = 12f
    /** F23a2 hint legend: 0.5 × SP_LETTER_SP (docs/DESIGN_KEY_POPUP.md §7.1) —
     *  T2: stays 12 sp (cap applies: 0.5 × 26 = 13 > 12). */
    const val SP_KEY_HINT_SP = 12f

    // ---- geometry nominal values (spec §1: 268 dp = 48 + 220) ----
    const val ROW_HEIGHT_4_ROWS_DP = 55
    const val ROW_HEIGHT_5_ROWS_DP = 44
    const val ROW_GAP_DP = 0
    const val KEY_GAP_DP = 0
    /** F36/V2 (docs/DESIGN_TASTATUR_V2.md §7): corner radius 10 px @ reference density
     *  2.625 → dp nominal value (scales along on other densities). */
    const val KEY_RADIUS_DP = 10f / 2.625f
    /** F36/V2 §7: key surface = full rectangle minus 2 px gap PER SIDE (the gap shows
     *  the background; 2 px per side ⇒ 4 px visible gap between keys —
     *  the only key delimitation, no separate separator lines). */
    const val KEY_FUGE_DP = 2f / 2.625f

    // ---- F22 D12: long-press menu dimensions (docs/DESIGN_KEY_POPUP.md §1) — named
    // design values, NO new colors (surface palette.keyPressed, glyph
    // palette.textPrimary, selection palette.accent/micRecordingGlyph) ----
    /** Cell 48×48 dp (not the base key width); menu height 56 dp. */
    const val POPUP_CELL_DP = 48f
    const val POPUP_HEIGHT_DP = 56f
    /** Inner/between padding 4 dp; distance to the base key and to the edge 8 dp. */
    const val POPUP_PAD_DP = 4f
    const val POPUP_GAP_DP = 8f
    /** Outer radius 4 dp, menu outer edge only; no border/shadow/pointer tip. */
    const val POPUP_RADIUS_DP = 4f
    /** Glyph size 24 sp, normal typeface (§1). */
    const val POPUP_GLYPH_SP = 24f
    /** Movement > 8 dp from DOWN definitively aborts the arming (§2). */
    const val POPUP_MOVE_CANCEL_DP = 8f
    /** Long-press threshold 450 ms (§2 table line 94); DOUBLE_TAP_MS=350 stays untouched. */
    const val LONG_PRESS_MS = 450L

    /** F27b: Shift/Backspace/Enter icons larger — 24 dp (≈1.3× the 14 sp function size),
     *  like the 26-dp Mic in dp (density, not scaledDensity). */
    const val ACTION_GLYPH_DP = 24f

    /** F29: stroke width of the three action glyphs (⇧/⌫/⏎) — IDENTICAL for all three,
     *  drawn as canvas paths (font glyphs draw lines of differing thickness per
     *  character; user request: equal stroke width).
     *  F39 (user feedback v0.1.27): clearly thinner — glyphs must NOT look thicker
     *  than the letters (24 sp letter stroke as the yardstick): 3.5 → 2.0 dp. */
    const val ACTION_GLYPH_STROKE_DP = 2.0f

    /** F28a: height of the status/info bar (ImeRootView statusRow, 48 dp) — reference for
     *  the long-press menu of the top row: the palette starts at root y=0 (i. e.
     *  -STATUS_LINE_DP in the KeyboardView coordinate system), i. e. directly below the
     *  status bar. Same value as the 48-dp status row in ImeRootView. */
    const val STATUS_LINE_DP = 48f

    /**
     * T2 (v0.1.35, user finding Pixel/vc34): pure geometry of the ⇧ glyph as
     * fractions of the bounding box s — the arrow (head + bar) is offset from the
     * horizontal BASE BAR, in EXACT correspondence to ⌫, where
     * the head is drawn offset 0.18·s from the vertical base
     * ([BACKSPACE_HEAD_SOCKEL_GAP_RATIO]). Previously the shaft stood only 0.07·s
     * above the line — merged with the 2-dp stroke (round caps, ±0.083·s):
     * "arrow stands on the line". Only the shift glyph — ⌫/⏎ untouched.
     * The 0.60·s optical-height gate (F29/P0) stays: base bar = box bottom edge,
     * arrow tip = box top edge; only the arrow foot moves up.
     */
    object ShiftGlyph {

        /** Base bar (horizontal line) — y fraction of the box bottom edge (0.60·s). */
        const val BASE_LINE_RATIO = 0.30f

        /** T2: distance arrow foot ↔ base bar — identical to the ⌫ head↔base distance. */
        const val BASE_GAP_RATIO = 0.18f

        /** ⌫ head↔base distance (0.465 − 0.285) — source of the correspondence (F36/V2 §8). */
        const val BACKSPACE_HEAD_SOCKEL_GAP_RATIO = 0.465f - 0.285f

        /** T2: arrow foot (shaft bottom edge) in bounding-box units —
         *  shift-head-y = line-y − offset, offset > 0: 0.30 − 0.18 = 0.12
         *  (previously 0.23 — merged, see KDoc). Pure function, JVM-testable. */
        fun arrowBottomRatio(): Float = BASE_LINE_RATIO - BASE_GAP_RATIO
    }

    /** Spec §1: 55 dp with up to four rows, 44 dp from five (PT stays five-row). */
    fun rowHeightDp(rows: Int): Int = if (rows >= 5) ROW_HEIGHT_5_ROWS_DP else ROW_HEIGHT_4_ROWS_DP

    /** F36/V2 (§7 "Farben", normative): light palette — key LIGHTER than the
     *  background (card look, visible through the 4-px gap), pressed as a
     *  DIRECT spec color (no overlay anymore), text/accent per the V2 table
     *  (text/key 17.09:1 → WCAG AAA). Not defined in V2: textFunctional,
     *  textSecondary, error — they keep their AOSP nominal values. */
    val LIGHT = KeyboardPalette(
        background = 0xFFF2F3F5.toInt(),
        keyNormal = 0xFFFFFFFF.toInt(),
        keyPressed = 0xFFDCE7E6.toInt(), // V2 §7 direct — no compose() overlay
        keyToggledGlyph = 0xFF2E7D74.toInt(), // V2 §7 accent (icon ⇧/⌫/⏎, shift active)
        textPrimary = 0xFF1A1C1E.toInt(),
        textFunctional = 0xCC37474F.toInt(),
        textSecondary = 0xB337474F.toInt(),
        separator = 0xFFE0E2E5.toInt(), // V2 §7 separator (1 px below the status bar)
        accent = 0xFF2E7D74.toInt(),
        micRecordingGlyph = 0xFFFFFFFF.toInt(), // contrary color on accent #2E7D74 (≈ 4.6:1)
        error = 0xFFB3261E.toInt(),
    )

    /** F36/V2 (§7): dark palette — same roles as light (see there). */
    val DARK = KeyboardPalette(
        background = 0xFF202124.toInt(),
        keyNormal = 0xFF2B2D31.toInt(),
        keyPressed = 0xFF3A4543.toInt(), // V2 §7 direct
        keyToggledGlyph = 0xFF80CBC4.toInt(),
        textPrimary = 0xFFE8EAED.toInt(),
        textFunctional = 0xCCFFFFFF.toInt(),
        textSecondary = 0xB3FFFFFF.toInt(),
        separator = 0xFF3C3F44.toInt(),
        accent = 0xFF80CBC4.toInt(),
        micRecordingGlyph = 0xFF263238.toInt(), // contrary color on accent #80CBC4 (≈ 7.4:1)
        error = 0xFFFFB4AB.toInt(),
    )

    /** System light/dark decides the palette (no target-app-theme guessing). */
    fun palette(isDark: Boolean): KeyboardPalette = if (isDark) DARK else LIGHT

    /**
     * Overlay composition (SrcOver, pure JVM logic): [overlay] lies over the
     * previously filled surface [base] — result opaque, channels blended.
     * Basis of the press states: "no lone transparent window color;
     * this way no target-app content shines through" (spec §1).
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
