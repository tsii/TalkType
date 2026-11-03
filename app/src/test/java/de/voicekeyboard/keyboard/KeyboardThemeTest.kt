package de.voicekeyboard.keyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S3/F36-V2 (docs/DESIGN_TASTATUR_V2.md §7, normativ): Token-Matrix als REINE
 * Zahlen-Assertions (ARGB-Ints, Alpha 0xFF für Flächen).
 *
 * V2-Rollen: Hintergrund, Taste (heller als Hintergrund — Kartenoptik über die
 * 4-px-Fuge), gedrückt (DIREKTE Spec-Farbe, keine Overlay-Komposition mehr),
 * Trennlinie (unter der Statusleiste), Text (WCAG AAA auf der Taste), Akzent
 * (Icon ⇧/⌫/⏎, Shift-aktiv). Nicht in V2 definiert: textFunctional,
 * textSecondary, error — behalten ihre AOSP-Nennwerte.
 * Geometrie: Reihen 55/44 dp; F36/V2 §7: Fuge 2 px je Seite, Radius 10 px
 * (@ Dichte 2,625 → dp); §8: Strichstärke 3,5 dp.
 */
class KeyboardThemeTest {

    // ---- Palette: exakte ARGB-Werte (kein freies Raten) ----

    @Test
    fun `helle Palette entspricht der V2-Tabelle`() {
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
    fun `dunkle Palette entspricht der V2-Tabelle`() {
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

    // ---- Vertrags-Assertions: Zustände dürfen nicht zusammenfallen ----

    @Test
    fun `gedrueckt und getoggelt unterscheiden sich von normal`() {
        for (p in listOf(KeyboardTheme.LIGHT, KeyboardTheme.DARK)) {
            assertNotEquals("V2 §7: Taste hebt sich vom Hintergrund ab (Fugen-Optik)", p.background, p.keyNormal)
            assertNotEquals("gedrueckt != normal", p.keyNormal, p.keyPressed)
            assertNotEquals("getoggelt(Glyphe) != normal(Textfarbe)", p.textPrimary, p.keyToggledGlyph)
        }
    }

    @Test
    fun `flaechen sind deckend - Alpha 0xFF`() {
        for (p in listOf(KeyboardTheme.LIGHT, KeyboardTheme.DARK)) {
            // Nur FLAECHEN sind deckend (Sol §1: „durch scheint kein Ziel-App-Inhalt");
            // Textfarben tragen ihre AOSP-Alpha (0xCC/0xB3) absichtlich weiter.
            for (value in listOf(
                p.background, p.keyNormal, p.keyPressed, p.keyToggledGlyph,
                p.accent, p.error, p.separator, p.micRecordingGlyph,
            )) {
                assertEquals("Flaeche muss Alpha 0xFF haben: 0x%08X".format(value),
                    0xFF, (value ushr 24) and 0xFF)
            }
        }
        // Textfarben behalten die AOSP-Transparenz-Nennwerte (Sol-Tabelle):
        assertEquals(0xCC, (KeyboardTheme.LIGHT.textFunctional ushr 24) and 0xFF)
        assertEquals(0xB3, (KeyboardTheme.LIGHT.textSecondary ushr 24) and 0xFF)
        assertEquals(0xCC, (KeyboardTheme.DARK.textFunctional ushr 24) and 0xFF)
        assertEquals(0xB3, (KeyboardTheme.DARK.textSecondary ushr 24) and 0xFF)
    }

    @Test
    fun `keine einzelne Farbe fuer beide Themen`() {
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
            assertNotEquals("Token '$name' darf nicht für Hell UND Dunkel identisch sein", values.first, values.second)
        }
    }

    // ---- Overlay-Vertrag: komponiert, Alpha geblendet (SrcOver, reine Logik) ----

    @Test
    fun `overlay-komposition bleibt reine logik - press ist V2-direktfarbe`() {
        // compose() bleibt eine reine Farb-Mathematik (SrcOver) — genutzt wo
        // Überlagerungen wirklich gebraucht werden; V2-Press ist KEINE Komposition
        // mehr, sondern die direkte Spec-Farbe #DCE7E6/#3A4543 (§7).
        assertEquals(0xFF123456.toInt(), KeyboardTheme.compose(0xFF123456.toInt(), 0x00FF0000))
        assertEquals(0xFF123456.toInt(), KeyboardTheme.compose(0xFFECEFF1.toInt(), 0xFF123456.toInt()))
        // F36/V2: Press ist bewusst SUBTIL („ruhiges Gesamtbild“, §7) — das alte
        // D11-Kriterium (Kanal-Delta > 60) gilt nicht mehr; die Ist-Deltas sind
        // die exakten Spec-Abstände Taste↔gedrückt (hell 35, dunkel 24).
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

    // ---- Schrift-Hierarchie: T2 (v0.1.35) 26/24 sp Hauptzeichen — 14/12/12 unverändert ----

    // T2 (Nutzerwunsch vc34, „~8–10 % größer"): NUR die beiden Hauptzeichen-
    // Rollen wachsen (Buchstabe 24→26 sp = +8,3 %, Ziffer 22→24 sp = +9,1 %).
    // Footer-/Funktions-Labels, Space/Sekundär, Hint-Legende (F23a2-Deckel
    // 0,5×Hauptlabel, gedeckelt auf 12 sp — bleibt 12) und die Popup-Zellen
    // (POPUP_GLYPH_SP) bleiben unskaliert. Raster-Fit: 26 sp ≈ 68 px @
    // Referenzdichte 2,625 — frei von der 1u=108-px-Zelle und den Fugen (s. T2-Test).
    @Test
    fun `T2 Schrift-Hierarchie 26 24 14 12 sp - Hauptzeichen 8-10 Prozent groesser`() {
        assertEquals(26f, KeyboardTheme.SP_LETTER_SP)
        assertEquals(24f, KeyboardTheme.SP_DIGIT_SP)
        assertEquals(14f, KeyboardTheme.SP_FUNCTION_SP)
        assertEquals(12f, KeyboardTheme.SP_SECONDARY_SP)
        // Rollen dürfen nicht zusammenfallen (Hierarchie, keine Dubletten):
        assertTrue(KeyboardTheme.SP_LETTER_SP > KeyboardTheme.SP_DIGIT_SP)
        assertTrue(KeyboardTheme.SP_DIGIT_SP > KeyboardTheme.SP_FUNCTION_SP)
        assertTrue(KeyboardTheme.SP_FUNCTION_SP > KeyboardTheme.SP_SECONDARY_SP)
        // T2: Wachstum im Nutzerfenster [1.08, 1.10] — Bestandsmaßstab 24/22 sp (vc34):
        val letterGrowth = KeyboardTheme.SP_LETTER_SP / 24f
        val digitGrowth = KeyboardTheme.SP_DIGIT_SP / 22f
        assertTrue("Buchstaben +8-10 % (war $letterGrowth)", letterGrowth in 1.08f..1.10f)
        assertTrue("Ziffern +8-10 % (war $digitGrowth)", digitGrowth in 1.08f..1.10f)
        // Unskalierte Rollen (NUR Hauptzeichen): Footer/Funktions- und Sekundär-
        // Labels sowie die Hint-Legende bleiben exakt auf den Bestandszahlen:
        assertEquals(14f, KeyboardTheme.SP_FUNCTION_SP)
        assertEquals(12f, KeyboardTheme.SP_SECONDARY_SP)
        assertEquals(12f, KeyboardTheme.SP_KEY_HINT_SP)
        assertTrue("Hint-Deckel wirkt weiter (0,5×26=13 > 12)",
            KeyboardTheme.SP_LETTER_SP * 0.5f > KeyboardTheme.SP_KEY_HINT_SP)
    }

    // ---- T2 (v0.1.35): Raster-Fit der vergrößerten Hauptzeichen ----

    // 1u = 108 px Zelle (1080/10 @ Referenzdichte 2,625), Fuge 2 px JE Seite
    // (F36/V2 §7), Reihe 55 dp = 144 px: die ~8-10 % größere Glyphe touchiert
    // KEINE Fuge und bleibt frei in Zelle und Reihe (nichts darf an Fugen stoßen).
    @Test
    fun `T2 Raster-Fit - vergroesserte Hauptzeichen touchieren keine Fuge`() {
        val density = 2.625f // Referenzdichte (KeyboardTheme-Kommentar F36/V2 §7)
        val letterPx = KeyboardTheme.SP_LETTER_SP * density // 26 sp ≈ 68 px
        val digitPx = KeyboardTheme.SP_DIGIT_SP * density   // 24 sp ≈ 63 px
        assertTrue("Buchstaben-Glyphe frei in der 1u-Zelle (108 px)",
            letterPx + 2f * 2f < 108f)
        assertTrue("Ziffern-Glyphe frei in der 1u-Zelle",
            digitPx + 2f * 2f < 108f)
        assertTrue("frei in der Reihenhoehe (55 dp = 144 px)",
            letterPx < KeyboardTheme.ROW_HEIGHT_4_ROWS_DP * density)
        // Unskalierte Rollen (NUR Hauptzeichen wachsen): Footer-/Funktions- und
        // Sekundär-Labels bleiben exakt auf den Bestandszahlen.
        assertEquals(14f, KeyboardTheme.SP_FUNCTION_SP)
        assertEquals(12f, KeyboardTheme.SP_SECONDARY_SP)
    }

    // ---- T2 (v0.1.35): ⇧-Pfeil von der Sockelleiste abgesetzt (Korrespondenz ⌫) ----

    // Nutzerbefund Pixel/vc34: Pfeil (Kopf+Balken) „stand auf der horizontalen
    // Linie auf" — Wunsch: ein kleines Stück abgesetzt, EXAKT analog zum ⌫, wo
    // der Kopf vom vertikalen Sockel abgesetzt gezeichnet ist. Reine Naht:
    // KeyboardTheme.ShiftGlyph (KeyboardView zeichnet nur noch damit).
    @Test
    fun `T2 Shift-Pfeil von der Sockelleiste abgesetzt - Korrespondenz zum Backspace`() {
        // Korrespondenz: ⇧-Pfeil↔Sockelleiste-Abstand == ⌫-Kopf↔Sockel-Abstand:
        assertEquals(
            "gleicher Abstand wie ⌫ (Kopf vom vertikalen Sockel abgesetzt)",
            KeyboardTheme.ShiftGlyph.BACKSPACE_HEAD_SOCKEL_GAP_RATIO,
            KeyboardTheme.ShiftGlyph.BASE_GAP_RATIO,
        )
        assertTrue("kleines Stück abgesetzt: Abstand > 0", KeyboardTheme.ShiftGlyph.BASE_GAP_RATIO > 0f)
        // sichtbare Trennung: Abstand größer als die Strichstärke (2/24 ≈ 0,083·s
        // mit runden Enden) — vorher 0,07·s: verschmolzen (der Befund):
        assertTrue("Abstand größer als die Strichstärke",
            KeyboardTheme.ShiftGlyph.BASE_GAP_RATIO >
                KeyboardTheme.ACTION_GLYPH_STROKE_DP / KeyboardTheme.ACTION_GLYPH_DP)
        // shift-kopf-y = linien-y − offset, offset > 0 (Pfeilfuß ÜBER der Linie):
        assertEquals(
            "Pfeilfuß = Sockelleiste − Abstand",
            KeyboardTheme.ShiftGlyph.BASE_LINE_RATIO - KeyboardTheme.ShiftGlyph.BASE_GAP_RATIO,
            KeyboardTheme.ShiftGlyph.arrowBottomRatio(),
        )
        assertEquals("0,30 − 0,18 = 0,12 (vorher 0,23: auf der Linie)",
            0.12f, KeyboardTheme.ShiftGlyph.arrowBottomRatio(), 1e-6f)
        // Optikhöhe-Gate (F29/P0, 0,60·s) bleibt: Sockelleiste = Box-Unterkante,
        // Pfeilfuß bleibt innerhalb der Bindekiste (kein Wachstum des Icons).
        assertEquals(0.30f, KeyboardTheme.ShiftGlyph.BASE_LINE_RATIO)
        assertTrue("Pfeilfuß innerhalb der Box", KeyboardTheme.ShiftGlyph.arrowBottomRatio() < KeyboardTheme.ShiftGlyph.BASE_LINE_RATIO)
    }

    // ---- Geometrie: Reihen 55/44 dp; F36/V2 §7 Fuge/Radius, §8 Strichstärke ----

    @Test
    fun `reihenhoehe 55 bzw 44 dp - V2-fuge radius und strichstaerke aus px`() {
        assertEquals(55, KeyboardTheme.rowHeightDp(rows = 4))
        assertEquals(55, KeyboardTheme.rowHeightDp(rows = 3)) // unter 5 Reihen: 55 dp
        assertEquals(44, KeyboardTheme.rowHeightDp(rows = 5))  // PT: 5 Reihen
        assertEquals(44, KeyboardTheme.rowHeightDp(rows = 6))
        assertEquals(0, KeyboardTheme.ROW_GAP_DP)
        assertEquals(0, KeyboardTheme.KEY_GAP_DP)
        // F36/V2 §7: 2 px Fuge je Seite / 10 px Eckenradius @ Referenzdichte 2,625 → dp.
        assertEquals(2f / 2.625f, KeyboardTheme.KEY_FUGE_DP)
        assertEquals(10f / 2.625f, KeyboardTheme.KEY_RADIUS_DP)
        // F39 (Nutzer-Feedback v0.1.27): Strichstärke 2,0 dp — dünner als die
        // Buchstaben-Optik wirken dürfen die Glyphen nicht dicker sein (vorher 3,5).
        assertEquals(2.0f, KeyboardTheme.ACTION_GLYPH_STROKE_DP)
    }

    @Test
    fun `palette-Auswahl folgt Hell-Dunkel`() {
        assertEquals(KeyboardTheme.LIGHT, KeyboardTheme.palette(isDark = false))
        assertEquals(KeyboardTheme.DARK, KeyboardTheme.palette(isDark = true))
    }
}
