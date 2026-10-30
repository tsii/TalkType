package de.voicekeyboard.keyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * D11 (docs/DESIGN_STOCK_LOOK.md „D11 — Rückmeldung gedrückte Taste", ab Zeile 240):
 * Reine Flächen-/Glyphen-Entscheidung ohne Android — die Naht, die KeyboardView
 * beim Zeichnen aufruft. Test-first, JVM.
 *
 * REGELN (explizit, aufgelöste Reihenfolge):
 *  1. micRecording ist höchste Priorität (Mic-Aufnahmezustand, D10 bleibt).
 *  2. CAPS_FILL: Shift-Taste mit EINGERASTETEM capsLock — Akzentfläche bleibt
 *     auch beim Drücken (D11: „Gedrückt+Caps behält diese Akzentfüllung … ohne
 *     zusätzlichen Overlayauftrag"); abgeleitet NUR aus capsLock, nie aus
 *     shiftTemporary und nie aus symbolsShift.
 *  3. pressed (Overlay-Verstärkung D11-Punkt 1) — für alle anderen Tasten.
 *  4. normal.
 * Glyphenfarbe konträr zur Füllung: CAPS_FILL/MIC_RECORDING ⇒ Gegenfarbe
 * (micRecordingGlyph-Token), nie Akzent auf Akzent; temporäres Shift ⇒ nur
 * Glyphe akzentuiert (keyToggledGlyph-Token), Fläche bleibt normal.
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

    // (a) pressed > normal; capsLock-Füllung gewinnt gegen normal, verliert gegen micRecording.
    @Test
    fun `pressed und capsFuellung schlagen normal - micRecording schlaegt alles`() {
        assertEquals(KeySurface.Surface.PRESSED, decide(pressed = true).surface)
        assertEquals(KeySurface.Surface.NORMAL, decide().surface)
        // Caps-Füllung (eingerasteter Shift):
        val caps = decide(istShiftKey = true, capsLock = true)
        assertEquals(KeySurface.Surface.CAPS_FILL, caps.surface)
        // ... gewinnt gegen normal UND bleibt beim Drücken (kein zweiter Overlayauftrag):
        assertEquals(
            "Gedrückt+Caps: Akzentfüllung bleibt (D11)",
            KeySurface.Surface.CAPS_FILL,
            decide(istShiftKey = true, capsLock = true, pressed = true).surface,
        )
        // ... verliert gegen micRecording:
        assertEquals(
            KeySurface.Surface.MIC_RECORDING,
            decide(micRecording = true, istShiftKey = true, capsLock = true, pressed = true).surface,
        )
        // Gedrückt+Caps: Glyphe bleibt die Gegenfarbe (kein Wechsel auf Akzent):
        assertEquals(
            KeySurface.Glyph.CONTRAST,
            decide(istShiftKey = true, capsLock = true, pressed = true).glyph,
        )
    }

    // (b) shiftTemporary erzeugt KEINE Flächenfüllung — nur die akzentuierte Glyphe.
    @Test
    fun `shiftTemporary fuellt die Flaeche nicht - nur Glyphe akzentuiert`() {
        val r = decide(istShiftKey = true, shiftTemporary = true)
        assertEquals("Fläche bleibt normal", KeySurface.Surface.NORMAL, r.surface)
        assertEquals("Glyphe akzentuiert (keyToggledGlyph-Token)", KeySurface.Glyph.ACCENT, r.glyph)
        // ...auch nicht in Kombination mit gedrückt:
        assertEquals(
            KeySurface.Surface.PRESSED,
            decide(istShiftKey = true, shiftTemporary = true, pressed = true).surface,
        )
        // Gedrückt+temporäres Shift: akzentuierte Glyphe bleibt über dem Overlay:
        assertEquals(
            KeySurface.Glyph.ACCENT,
            decide(istShiftKey = true, shiftTemporary = true, pressed = true).glyph,
        )
    }

    // (c) symbolsShift erzwingt KEINE CapsLock-Füllung (Symbol-Ebene ist kein Caps-Zustand).
    @Test
    fun `symbolsShift erzwingt keine CapsFuellung`() {
        assertEquals(
            KeySurface.Surface.NORMAL,
            decide(istShiftKey = true, symbolShift = true).surface,
        )
        // Auch auf der Symbolebene mit wahrem capsLock (Shift-Taste zeigt dort
        // symbolShift, nicht Caps): keine Caps-Füllung.
        assertEquals(
            KeySurface.Surface.NORMAL,
            decide(istShiftKey = true, capsLock = true, symbolShift = true, activeLayerIsSymbols = true).surface,
        )
        // Glyph bleibt wie heute akzentuiert (Toggle-Zustand sichtbar):
        assertEquals(KeySurface.Glyph.ACCENT, decide(istShiftKey = true, symbolShift = true).glyph)
    }

    // (d) Glyphenfarbe ist die GEGENFARBE der Füllung — nie Akzent auf Akzent.
    @Test
    fun `Glyphe ist Gegenfarbe der Fuellung - kein Akzent auf Akzent`() {
        assertEquals(KeySurface.Glyph.CONTRAST, decide(istShiftKey = true, capsLock = true).glyph)
        assertEquals(KeySurface.Glyph.CONTRAST, decide(micRecording = true).glyph)
        // Auf Token-Ebene: micRecordingGlyph (Gegenfarbe) ist in BEIDEN Themes
        // eine andere Farbe als accent — Akzent-auf-Akzent ist ausgeschlossen.
        for (p in listOf(KeyboardTheme.LIGHT, KeyboardTheme.DARK)) {
            assertNotEquals("Gegenfarbe muss sich vom Akzent unterscheiden (${p.accent})", p.accent, p.micRecordingGlyph)
        }
    }

    // (e) Normalpfad unverändert: normale Taste = NORMAL-Fläche, Rollen-Glyphfarbe.
    @Test
    fun `Normalpfad unveraendert`() {
        val r = decide()
        assertEquals(KeySurface.Surface.NORMAL, r.surface)
        assertEquals(KeySurface.Glyph.NORMAL, r.glyph)
        // Gedrückte normale Taste: Fläche PRESSED, Glyphe UNVERÄNDERT (D11:
        // gedrückt ändert die Textfarbe nicht).
        val pressed = decide(pressed = true)
        assertEquals(KeySurface.Surface.PRESSED, pressed.surface)
        assertEquals(KeySurface.Glyph.NORMAL, pressed.glyph)
        assertTrue(
            "MIC_RECORDING-Glyph ist die Gegenfarbe (D10 §2 unverändert)",
            decide(micRecording = true).glyph == KeySurface.Glyph.CONTRAST,
        )
    }
}
