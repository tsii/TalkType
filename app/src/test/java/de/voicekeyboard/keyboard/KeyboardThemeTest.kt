package de.voicekeyboard.keyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S3/F36-V2 (docs/DESIGN_TASTATUR_V2.md §7, normative): token matrix as PURE
 * number assertions (ARGB ints, alpha 0xFF for surfaces).
 *
 * V2 roles: background, key (lighter than background — card look across the
 * 4-px gap), pressed (DIRECT spec color, no overlay composition anymore),
 * separator (below the status bar), text (WCAG AAA on the key), accent
 * (icon ⇧/⌫/⏎, shift-active). Not defined in V2: textFunctional,
 * textSecondary, error — keep their AOSP nominal values.
 * Geometry: rows 55/44 dp; F36/V2 §7: gap 2 px per side, radius 10 px
 * (@ density 2.625 → dp); §8: stroke width 3.5 dp.
 */
class KeyboardThemeTest {

    // ---- palette: exact ARGB values (no free guessing) ----

    @Test
    fun `light palette matches the V2 table`() {
        val p = KeyboardTheme.LIGHT
        assertEquals(0xFFF2F3F5.toInt(), p.background)      // V2 §7 Hintergrund
        assertEquals(0xFFFFFFFF.toInt(), p.keyNormal)       // V2 §7 Taste (heller als Hintergrund)
        assertEquals(0xFFDCE7E6.toInt(), p.keyPressed)      // V2 §7 gedrückt (direkt, kein Overlay)
        assertEquals(0xFF2E7D74.toInt(), p.keyToggledGlyph) // V2 §7 Akzent (⇧/⌫/⏎, Shift-aktiv)
        assertEquals(0xFF1A1C1E.toInt(), p.textPrimary)    // V2 §7 Text (17,09:1 auf Taste → AAA)
        assertEquals(0xCC37474F.toInt(), p.textFunctional) // nicht in V2: AOSP bleibt
        assertEquals(0xB337474F.toInt(), p.textSecondary)  // nicht in V2: AOSP bleibt
        assertEquals(0xFFE0E2E5.toInt(), p.separator)      // V2 §7 Trennlinie
        assertEquals(0xFF2E7D74.toInt(), p.accent)        // V2 §7 Akzent
        assertEquals(0xFFFFFFFF.toInt(), p.micRecordingGlyph) // Gegenfarbe auf #2E7D74 (≈ 4,6:1)
        assertEquals(0xFFB3261E.toInt(), p.error)          // nicht in V2: bleibt
    }

    @Test
    fun `dark palette matches the V2 table`() {
        val p = KeyboardTheme.DARK
        assertEquals(0xFF202124.toInt(), p.background)      // V2 §7 Hintergrund
        assertEquals(0xFF2B2D31.toInt(), p.keyNormal)       // V2 §7 Taste
        assertEquals(0xFF3A4543.toInt(), p.keyPressed)      // V2 §7 gedrückt (direkt)
        assertEquals(0xFF80CBC4.toInt(), p.keyToggledGlyph) // V2 §7 Akzent
        assertEquals(0xFFE8EAED.toInt(), p.textPrimary)    // V2 §7 Text (11,44:1 → AAA)
        assertEquals(0xCCFFFFFF.toInt(), p.textFunctional) // nicht in V2: AOSP bleibt
        assertEquals(0xB3FFFFFF.toInt(), p.textSecondary)  // nicht in V2: AOSP bleibt
        assertEquals(0xFF3C3F44.toInt(), p.separator)      // V2 §7 Trennlinie
        assertEquals(0xFF80CBC4.toInt(), p.accent)        // V2 §7 Akzent
        assertEquals(0xFF263238.toInt(), p.micRecordingGlyph) // Gegenfarbe auf #80CBC4 (≈ 7,4:1)
        assertEquals(0xFFFFB4AB.toInt(), p.error)          // nicht in V2: bleibt
    }

    // ---- contract assertions: states must not collapse ----

    @Test
    fun `pressed and toggled differ from normal`() {
        for (p in listOf(KeyboardTheme.LIGHT, KeyboardTheme.DARK)) {
            assertNotEquals("V2 §7: the key stands out from the background (seam look)", p.background, p.keyNormal)
            assertNotEquals("gedrueckt != normal", p.keyNormal, p.keyPressed)
            assertNotEquals("toggled(glyph) != normal(text color)", p.textPrimary, p.keyToggledGlyph)
        }
    }

    @Test
    fun `surfaces are opaque - alpha 0xFF`() {
        for (p in listOf(KeyboardTheme.LIGHT, KeyboardTheme.DARK)) {
            // only SURFACES are opaque (Sol §1: "no target app content shows through");
            // text colors deliberately keep their AOSP alpha (0xCC/0xB3).
            for (value in listOf(
                p.background, p.keyNormal, p.keyPressed, p.keyToggledGlyph,
                p.accent, p.error, p.separator, p.micRecordingGlyph,
            )) {
                assertEquals("surface must have alpha 0xFF: 0x%08X".format(value),
                    0xFF, (value ushr 24) and 0xFF)
            }
        }
        // text colors keep the AOSP transparency nominal values (Sol table):
        assertEquals(0xCC, (KeyboardTheme.LIGHT.textFunctional ushr 24) and 0xFF)
        assertEquals(0xB3, (KeyboardTheme.LIGHT.textSecondary ushr 24) and 0xFF)
        assertEquals(0xCC, (KeyboardTheme.DARK.textFunctional ushr 24) and 0xFF)
        assertEquals(0xB3, (KeyboardTheme.DARK.textSecondary ushr 24) and 0xFF)
    }

    @Test
    fun `no single color for both themes`() {
        val light = KeyboardTheme.LIGHT
        val dark = KeyboardTheme.DARK
        val pairs = listOf(
            "background" to (light.background to dark.background),
            "keyNormal" to (light.keyNormal to dark.keyNormal),
            "keyPressed" to (light.keyPressed to dark.keyPressed),
            "keyToggledGlyph" to (light.keyToggledGlyph to dark.keyToggledGlyph),
            "textPrimary" to (light.textPrimary to dark.textPrimary),
            "textFunctional" to (light.textFunctional to dark.textFunctional),
            "textSecondary" to (light.textSecondary to dark.textSecondary),
            "separator" to (light.separator to dark.separator),
            "accent" to (light.accent to dark.accent),
            "error" to (light.error to dark.error),
        )
        for ((name, values) in pairs) {
            assertNotEquals("token '$name' must not be identical for light AND dark", values.first, values.second)
        }
    }

    // ---- overlay contract: composed, alpha blended (SrcOver, pure logic) ----

    @Test
    fun `overlay composition stays pure logic - press is the V2 direct color`() {
        // compose() remains pure color math (SrcOver) — used where overlays
        // are really needed; V2 press is NO composition anymore, but the
        // direct spec color #DCE7E6/#3A4543 (§7).
        assertEquals(0xFF123456.toInt(), KeyboardTheme.compose(0xFF123456.toInt(), 0x00FF0000))
        assertEquals(0xFF123456.toInt(), KeyboardTheme.compose(0xFFECEFF1.toInt(), 0xFF123456.toInt()))
        // F36/V2: press is deliberately SUBTLE ("calm overall image", §7) — the
        // old D11 criterion (channel delta > 60) no longer applies; the actual
        // deltas are the exact spec distances key↔pressed (light 35, dark 24).
        fun maxChannelDelta(a: Int, b: Int): Int {
            var max = 0
            for (shift in listOf(16, 8, 0)) {
                val d = kotlin.math.abs(((a shr shift) and 0xFF) - ((b shr shift) and 0xFF))
                if (d > max) max = d
            }
            return max
        }
        assertEquals(35, maxChannelDelta(KeyboardTheme.LIGHT.keyNormal, KeyboardTheme.LIGHT.keyPressed))
        assertEquals(24, maxChannelDelta(KeyboardTheme.DARK.keyNormal, KeyboardTheme.DARK.keyPressed))
    }

    // ---- font hierarchy: T2 (v0.1.35) 26/24 sp main glyphs — 14/12/12 unchanged ----

    // T2 (user request vc34, "~8–10 % larger"): ONLY the two main glyph roles
    // grow (letter 24→26 sp = +8.3 %, digit 22→24 sp = +9.1 %).
    // Footer/function labels, space/secondary, hint legend (F23a2 cap
    // 0.5× main label, capped at 12 sp — stays 12) and the popup cells
    // (POPUP_GLYPH_SP) remain unscaled. Grid fit: 26 sp ≈ 68 px @ reference
    // density 2.625 — free of the 1u=108-px cell and the gaps (see T2 test).
    @Test
    fun `T2 font hierarchy 26 24 14 12 sp - main glyphs 8-10 percent larger`() {
        assertEquals(26f, KeyboardTheme.SP_LETTER_SP)
        assertEquals(24f, KeyboardTheme.SP_DIGIT_SP)
        assertEquals(14f, KeyboardTheme.SP_FUNCTION_SP)
        assertEquals(12f, KeyboardTheme.SP_SECONDARY_SP)
        // roles must not collapse (hierarchy, no duplicates):
        assertTrue(KeyboardTheme.SP_LETTER_SP > KeyboardTheme.SP_DIGIT_SP)
        assertTrue(KeyboardTheme.SP_DIGIT_SP > KeyboardTheme.SP_FUNCTION_SP)
        assertTrue(KeyboardTheme.SP_FUNCTION_SP > KeyboardTheme.SP_SECONDARY_SP)
        // T2: growth in the user window [1.08, 1.10] — stock baseline 24/22 sp (vc34):
        val letterGrowth = KeyboardTheme.SP_LETTER_SP / 24f
        val digitGrowth = KeyboardTheme.SP_DIGIT_SP / 22f
        assertTrue("letters +8-10 % (was $letterGrowth)", letterGrowth in 1.08f..1.10f)
        assertTrue("digits +8-10 % (was $digitGrowth)", digitGrowth in 1.08f..1.10f)
        // unscaled roles (ONLY main glyphs): footer/function and secondary
        // labels as well as the hint legend stay exactly at the stock values:
        assertEquals(14f, KeyboardTheme.SP_FUNCTION_SP)
        assertEquals(12f, KeyboardTheme.SP_SECONDARY_SP)
        assertEquals(12f, KeyboardTheme.SP_KEY_HINT_SP)
        assertTrue("Hint cap still effective (0.5×26=13 > 12)",
            KeyboardTheme.SP_LETTER_SP * 0.5f > KeyboardTheme.SP_KEY_HINT_SP)
    }

    // ---- T2 (v0.1.35): grid fit of the enlarged main glyphs ----

    // 1u = 108 px cell (1080/10 @ reference density 2.625), gap 2 px PER side
    // (F36/V2 §7), row 55 dp = 144 px: the ~8-10 % larger glyph touches NO
    // gap and stays free in cell and row (nothing may touch the gaps).
    @Test
    fun `T2 grid fit - enlarged main glyphs touch no gap`() {
        val density = 2.625f // reference density (KeyboardTheme comment F36/V2 §7)
        val letterPx = KeyboardTheme.SP_LETTER_SP * density // 26 sp ≈ 68 px
        val digitPx = KeyboardTheme.SP_DIGIT_SP * density   // 24 sp ≈ 63 px
        assertTrue("Buchstaben-Glyphe frei in der 1u-Zelle (108 px)",
            letterPx + 2f * 2f < 108f)
        assertTrue("Ziffern-Glyphe frei in der 1u-Zelle",
            digitPx + 2f * 2f < 108f)
        assertTrue("free within the row height (55 dp = 144 px)",
            letterPx < KeyboardTheme.ROW_HEIGHT_4_ROWS_DP * density)
        // unscaled roles (ONLY main glyphs grow): footer/function and
        // secondary labels stay exactly at the stock values.
        assertEquals(14f, KeyboardTheme.SP_FUNCTION_SP)
        assertEquals(12f, KeyboardTheme.SP_SECONDARY_SP)
    }

    // ---- T2 (v0.1.35): ⇧ arrow set off from the base bar (correspondence ⌫) ----

    // user finding Pixel/vc34: the arrow (head+bar) "stood on the horizontal
    // line" — request: set off by a small piece, EXACTLY analogous to ⌫, where
    // the head is drawn set off from the vertical base. Pure seam:
    // KeyboardTheme.ShiftGlyph (KeyboardView now draws only with it).
    @Test
    fun `T2 shift arrow set off from the base bar - correspondence to backspace`() {
        // correspondence: ⇧ arrow↔base bar distance == ⌫ head↔base distance:
        assertEquals(
            "the same gap as ⌫ (the head set off from the vertical base)",
            KeyboardTheme.ShiftGlyph.BACKSPACE_HEAD_SOCKEL_GAP_RATIO,
            KeyboardTheme.ShiftGlyph.BASE_GAP_RATIO,
        )
        assertTrue("a small piece set off: gap > 0", KeyboardTheme.ShiftGlyph.BASE_GAP_RATIO > 0f)
        // visible separation: distance larger than the stroke width (2/24 ≈ 0.083·s
        // with round ends) — before 0.07·s: fused (the finding):
        assertTrue("gap larger than the stroke width",
            KeyboardTheme.ShiftGlyph.BASE_GAP_RATIO >
                KeyboardTheme.ACTION_GLYPH_STROKE_DP / KeyboardTheme.ACTION_GLYPH_DP)
        // shift-head-y = line-y − offset, offset > 0 (arrow foot ABOVE the line):
        assertEquals(
            "Pfeilfuß = Sockelleiste − Abstand",
            KeyboardTheme.ShiftGlyph.BASE_LINE_RATIO - KeyboardTheme.ShiftGlyph.BASE_GAP_RATIO,
            KeyboardTheme.ShiftGlyph.arrowBottomRatio(),
        )
        assertEquals("0.30 − 0.18 = 0.12 (previously 0.23: on the line)",
            0.12f, KeyboardTheme.ShiftGlyph.arrowBottomRatio(), 1e-6f)
        // optical height gate (F29/P0, 0.60·s) stays: base bar = box bottom edge,
        // arrow foot stays inside the bounding box (no growth of the icon).
        assertEquals(0.30f, KeyboardTheme.ShiftGlyph.BASE_LINE_RATIO)
        assertTrue("arrow foot inside the box", KeyboardTheme.ShiftGlyph.arrowBottomRatio() < KeyboardTheme.ShiftGlyph.BASE_LINE_RATIO)
    }

    // ---- geometry: rows 55/44 dp; F36/V2 §7 gap/radius, §8 stroke width ----

    @Test
    fun `row height 55 and 44 dp - V2 gap radius and stroke width derived from px`() {
        assertEquals(55, KeyboardTheme.rowHeightDp(rows = 4))
        assertEquals(55, KeyboardTheme.rowHeightDp(rows = 3)) // below 5 rows: 55 dp
        assertEquals(44, KeyboardTheme.rowHeightDp(rows = 5))  // PT: 5 rows
        assertEquals(44, KeyboardTheme.rowHeightDp(rows = 6))
        assertEquals(0, KeyboardTheme.ROW_GAP_DP)
        assertEquals(0, KeyboardTheme.KEY_GAP_DP)
        // F36/V2 §7: 2 px Fuge je Seite / 10 px Eckenradius @ Referenzdichte 2,625 → dp.
        assertEquals(2f / 2.625f, KeyboardTheme.KEY_FUGE_DP)
        assertEquals(10f / 2.625f, KeyboardTheme.KEY_RADIUS_DP)
        // F39 (user feedback v0.1.27): stroke width 2.0 dp — the glyphs must not be
        // thicker than the letter optics allow (before 3.5).
        assertEquals(2.0f, KeyboardTheme.ACTION_GLYPH_STROKE_DP)
    }

    @Test
    fun `palette selection follows light-dark`() {
        assertEquals(KeyboardTheme.LIGHT, KeyboardTheme.palette(isDark = false))
        assertEquals(KeyboardTheme.DARK, KeyboardTheme.palette(isDark = true))
    }
}
