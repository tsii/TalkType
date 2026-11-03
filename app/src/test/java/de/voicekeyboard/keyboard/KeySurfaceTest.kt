package de.voicekeyboard.keyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * D11 (docs/DESIGN_STOCK_LOOK.md “D11 — Rückmeldung gedrückte Taste”, from line 240):
 * pure surface/glyph decision without Android — the seam that KeyboardView
 * calls while drawing. Test-first, JVM.
 *
 * RULES (explicit, resolved order):
 *  1. micRecording is the highest priority (mic recording state, D10 stays).
 *  2. CAPS_FILL: shift key with LATCHED capsLock — the accent surface stays
 *     even when pressed (D11: “Pressed+Caps keeps this accent fill … without
 *     an additional overlay assignment”); derived ONLY from capsLock, never
 *     from shiftTemporary and never from symbolsShift.
 *  3. pressed (overlay reinforcement D11 point 1) — for all other keys.
 *  4. normal.
 * Glyph color contrary to the fill: CAPS_FILL/MIC_RECORDING ⇒ complementary
 * color (micRecordingGlyph token), never accent on accent; temporary shift ⇒
 * only the glyph accented (keyToggledGlyph token), surface stays normal.
 */
class KeySurfaceTest {

    private fun decide(
        istShiftKey: Boolean = false,
        capsLock: Boolean = false,
        shiftTemporary: Boolean = false,
        symbolShift: Boolean = false,
        pressed: Boolean = false,
        micRecording: Boolean = false,
        activeLayerIsSymbols: Boolean = false,
    ) = KeySurface.decide(
        istShiftKey = istShiftKey,
        capsLock = capsLock,
        shiftTemporary = shiftTemporary,
        symbolShift = symbolShift,
        pressed = pressed,
        micRecording = micRecording,
        activeLayerIsSymbols = activeLayerIsSymbols,
    )

    // (a) pressed > normal; the capsLock fill beats normal, loses to micRecording.
    @Test
    fun `pressed and caps fill beat normal - micRecording beats everything`() {
        assertEquals(KeySurface.Surface.PRESSED, decide(pressed = true).surface)
        assertEquals(KeySurface.Surface.NORMAL, decide().surface)
        // caps fill (latched shift):
        val caps = decide(istShiftKey = true, capsLock = true)
        assertEquals(KeySurface.Surface.CAPS_FILL, caps.surface)
        // ... beats normal AND stays when pressed (no second overlay assignment):
        assertEquals(
            "pressed+caps: the accent fill stays (D11)",
            KeySurface.Surface.CAPS_FILL,
            decide(istShiftKey = true, capsLock = true, pressed = true).surface,
        )
        // ... loses to micRecording:
        assertEquals(
            KeySurface.Surface.MIC_RECORDING,
            decide(micRecording = true, istShiftKey = true, capsLock = true, pressed = true).surface,
        )
        // pressed+caps: the glyph stays the complementary color (no switch to accent):
        assertEquals(
            KeySurface.Glyph.CONTRAST,
            decide(istShiftKey = true, capsLock = true, pressed = true).glyph,
        )
    }

    // (b) shiftTemporary creates NO surface fill — only the accented glyph.
    @Test
    fun `shiftTemporary does not fill the surface - only the glyph is accented`() {
        val r = decide(istShiftKey = true, shiftTemporary = true)
        assertEquals("surface stays normal", KeySurface.Surface.NORMAL, r.surface)
        assertEquals("Glyphe akzentuiert (keyToggledGlyph-Token)", KeySurface.Glyph.ACCENT, r.glyph)
        // ...and not in combination with pressed either:
        assertEquals(
            KeySurface.Surface.PRESSED,
            decide(istShiftKey = true, shiftTemporary = true, pressed = true).surface,
        )
        // pressed+temporary shift: the accented glyph stays above the overlay:
        assertEquals(
            KeySurface.Glyph.ACCENT,
            decide(istShiftKey = true, shiftTemporary = true, pressed = true).glyph,
        )
    }

    // (c) symbolsShift forces NO capsLock fill (the symbol layer is not a caps state).
    @Test
    fun `symbolsShift forces no caps fill`() {
        assertEquals(
            KeySurface.Surface.NORMAL,
            decide(istShiftKey = true, symbolShift = true).surface,
        )
        // Also on the symbol layer with a true capsLock (the shift key shows
        // symbolShift there, not caps): no caps fill.
        assertEquals(
            KeySurface.Surface.NORMAL,
            decide(istShiftKey = true, capsLock = true, symbolShift = true, activeLayerIsSymbols = true).surface,
        )
        // Glyph stays accented as today (toggle state visible):
        assertEquals(KeySurface.Glyph.ACCENT, decide(istShiftKey = true, symbolShift = true).glyph)
    }

    // (d) the glyph color is the COMPLEMENTARY color of the fill — never accent on accent.
    @Test
    fun `glyph is the complementary color of the fill - no accent on accent`() {
        assertEquals(KeySurface.Glyph.CONTRAST, decide(istShiftKey = true, capsLock = true).glyph)
        assertEquals(KeySurface.Glyph.CONTRAST, decide(micRecording = true).glyph)
        // on the token level: micRecordingGlyph (complementary color) is in BOTH
        // themes a different color than accent — accent-on-accent is excluded.
        for (p in listOf(KeyboardTheme.LIGHT, KeyboardTheme.DARK)) {
            assertNotEquals("the contrasting color must differ from the accent (${p.accent})", p.accent, p.micRecordingGlyph)
        }
    }

    // (e) normal path unchanged: normal key = NORMAL surface, role glyph color.
    @Test
    fun `normal path unchanged`() {
        val r = decide()
        assertEquals(KeySurface.Surface.NORMAL, r.surface)
        assertEquals(KeySurface.Glyph.NORMAL, r.glyph)
        // pressed normal key: surface PRESSED, glyph UNCHANGED (D11:
        // pressed does not change the text color).
        val pressed = decide(pressed = true)
        assertEquals(KeySurface.Surface.PRESSED, pressed.surface)
        assertEquals(KeySurface.Glyph.NORMAL, pressed.glyph)
        assertTrue(
            "the MIC_RECORDING glyph is the contrasting color (D10 §2 unchanged)",
            decide(micRecording = true).glyph == KeySurface.Glyph.CONTRAST,
        )
    }

    // ---- F23a2: hint placement (docs/DESIGN_KEY_POPUP.md §7.2) — pure seam ----

    @Test
    fun `hintAnchorX is right-aligned within pad plus half the ink width`() {
        // specification of the assignment: padPx 21 -> 98 - 21 - 9 = 68
        assertEquals(68f, KeySurface.hintAnchorX(98f, 21f, 18f), 1f)
        // documentation example §7.2: padPx = 4 dp @ density 420 = 10.5 px -> 78.5
        assertEquals(78.5f, KeySurface.hintAnchorX(98f, 10.5f, 18f), 1f)
    }

    @Test
    fun `hintTopOffsetDp is nominally 2 dp at full row height`() {
        assertEquals(2.0f, KeySurface.hintTopOffsetDp(4f, 55.0, 55), 0.01f)
    }

    @Test
    fun `hintTopOffsetDp shrinks relative to the row`() {
        // 44/55 x 2 dp = 1.6 dp (smaller than 2)
        assertEquals(1.6f, KeySurface.hintTopOffsetDp(4f, 44.0, 55), 0.05f)
    }

    @Test
    fun `hintTopOffsetDp never exceeds 2 dp and never becomes negative`() {
        assertEquals(2.0f, KeySurface.hintTopOffsetDp(4f, 90.0, 55), 0.01f) // taller row
        assertEquals(0f, KeySurface.hintTopOffsetDp(4f, 0.0, 55), 0.01f) // broken height
        assertEquals(0f, KeySurface.hintTopOffsetDp(4f, -10.0, 55), 0.01f) // negative
    }
}
