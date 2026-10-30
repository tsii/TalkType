package de.voicekeyboard.keyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S3 (docs/DESIGN_STOCK_LOOK.md §1 + §4 Zeile S3): Token-Matrix für den
 * Stock-Look als REINE Zahlen-Assertions (ARGB-Ints, Alpha 0xFF).
 *
 * Quellen der Werte (Sol-Tabelle §1 „Farben – ARGB, Hell/Dunkel"):
 *  - AOSP key_background_lxx_*  (android16-release, values/colors.xml:43–78)
 *  - AOSP key_text_color_lxx_* / functional, eigene Toggle-/Fehler-Tokens.
 * Schrift-Hierarchie 24/22/14/12 sp (Buchstaben/Ziffern/Funktion/Space-Status).
 * Gedrückt ist ein ÜBERLAY über der Tastaturfläche (komponiert, SrcOver) —
 * deshalb unterscheidet sich `keyPressed` von `keyNormal`, und `keyNormal`
 * ist identisch zum Hintergrund (flache Fläche, keine Kartenoptik).
 */
class KeyboardThemeTest {

    // ---- Palette: exakte ARGB-Werte (kein freies Raten) ----

    @Test
    fun `helle Palette entspricht der Sol-Tabelle`() {
        val p = KeyboardTheme.LIGHT
        assertEquals(0xFFECEFF1.toInt(), p.background)      // AOSP key_background_lxx_light
        assertEquals(0xFFECEFF1.toInt(), p.keyNormal)       // identisch zum Hintergrund (flach)
        assertEquals(0xFFA3ABB0.toInt(), p.keyPressed)      // D11: compose(ECEFF1, Overlay 0x6637474F)
        assertEquals(0xFF00796B.toInt(), p.keyToggledGlyph) // eigene dunklere Hell-Variante (Shift/Caps)
        assertEquals(0xFF37474F.toInt(), p.textPrimary)    // AOSP key_text_color_lxx_light
        assertEquals(0xCC37474F.toInt(), p.textFunctional) // AOSP functional
        assertEquals(0xB337474F.toInt(), p.textSecondary)  // AOSP sekundäre/Space-Farben
        assertEquals(0x2637474F.toInt(), p.separator)      // eigener 1-dp-Separator
        assertEquals(0xFF4DB6AC.toInt(), p.accent)        // AOSP highlight_color_lxx_light
        assertEquals(0xFFB3261E.toInt(), p.error)          // eigene Fehlerfarbe
    }

    @Test
    fun `dunkle Palette entspricht der Sol-Tabelle`() {
        val p = KeyboardTheme.DARK
        assertEquals(0xFF263238.toInt(), p.background)      // AOSP key_background_lxx_dark
        assertEquals(0xFF263238.toInt(), p.keyNormal)
        assertEquals(0xFF676F74.toInt(), p.keyPressed)     // D11: compose(263238, Overlay 0x4DFFFFFF)
        assertEquals(0xFF80CBC4.toInt(), p.keyToggledGlyph)
        assertEquals(0xCCFFFFFF.toInt(), p.textPrimary)
        assertEquals(0xCCFFFFFF.toInt(), p.textFunctional)
        assertEquals(0xB3FFFFFF.toInt(), p.textSecondary)
        assertEquals(0x26FFFFFF.toInt(), p.separator)
        assertEquals(0xFF80CBC4.toInt(), p.accent)
        assertEquals(0xFFFFB4AB.toInt(), p.error)
    }

    // ---- Vertrags-Assertions: Zustände dürfen nicht zusammenfallen ----

    @Test
    fun `gedrueckt und getoggelt unterscheiden sich von normal`() {
        for (p in listOf(KeyboardTheme.LIGHT, KeyboardTheme.DARK)) {
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
                p.accent, p.error,
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
    fun `Overlay komponiert ueber die vorher gefuellte Flaeche`() {
        // Overlay-Alpha 0 (unsichtbar) ändert die Basis nicht:
        assertEquals(0xFFECEFF1.toInt(), KeyboardTheme.compose(0xFFECEFF1.toInt(), 0x00FF0000))
        // Voll deckendes Overlay ersetzt die Basis komplett:
        assertEquals(0xFF123456.toInt(), KeyboardTheme.compose(0xFFECEFF1.toInt(), 0xFF123456.toInt()))
        // D11-Press: compose(Hintergrund, verstärktes Overlay 0x66/0x4D) == keyPressed:
        assertEquals(
            KeyboardTheme.LIGHT.keyPressed,
            KeyboardTheme.compose(KeyboardTheme.LIGHT.background, 0x6637474F),
        )
        assertEquals(
            KeyboardTheme.DARK.keyPressed,
            KeyboardTheme.compose(KeyboardTheme.DARK.background, 0x4DFFFFFF),
        )
        // D11-Schwellenmessung (echtes Delta ausgerechnet, je Kanal max. |Δ|):
        // Befund war nur 27/255 (hell) bzw. 21/255 (dunkel) — der neue Press-
        // Zustand muss deutlich darüber liegen (Ziel: > 60), sonst wäre die
        // Verstärkung wirkungslos.
        fun maxChannelDelta(a: Int, b: Int): Int {
            var max = 0
            for (shift in listOf(16, 8, 0)) {
                val d = kotlin.math.abs(((a shr shift) and 0xFF) - ((b shr shift) and 0xFF))
                if (d > max) max = d
            }
            return max
        }
        val deltaLight = maxChannelDelta(KeyboardTheme.LIGHT.keyNormal, KeyboardTheme.LIGHT.keyPressed)
        val deltaDark = maxChannelDelta(KeyboardTheme.DARK.keyNormal, KeyboardTheme.DARK.keyPressed)
        assertTrue("Hell: Press-Delta $deltaLight muss > 60 sein (vorher 27)", deltaLight > 60)
        assertTrue("Dunkel: Press-Delta $deltaDark muss > 60 sein (vorher 21)", deltaDark > 60)
        assertEquals("Hell: erwartetes neues Press-Delta", 73, deltaLight)
        assertEquals("Dunkel: erwartetes neues Press-Delta", 65, deltaDark)
    }

    // ---- Schrift-Hierarchie: 24/22/14/12 sp als eigene Konstanten ----

    @Test
    fun `schrift-Hierarchie ist 24 22 14 12 sp`() {
        assertEquals(24f, KeyboardTheme.SP_LETTER_SP)
        assertEquals(22f, KeyboardTheme.SP_DIGIT_SP)
        assertEquals(14f, KeyboardTheme.SP_FUNCTION_SP)
        assertEquals(12f, KeyboardTheme.SP_SECONDARY_SP)
        // Rollen dürfen nicht zusammenfallen (Hierarchie, keine Dubletten):
        assertTrue(KeyboardTheme.SP_LETTER_SP > KeyboardTheme.SP_DIGIT_SP)
        assertTrue(KeyboardTheme.SP_DIGIT_SP > KeyboardTheme.SP_FUNCTION_SP)
        assertTrue(KeyboardTheme.SP_FUNCTION_SP > KeyboardTheme.SP_SECONDARY_SP)
    }

    // ---- Geometrie-Nennwerte (Sol §1): 55 dp (4 Reihen) / 44 dp (5 Reihen), Lücken 0, Radius 0 ----

    @Test
    fun `reihenhoehe folgt der Sol-Geometrie - 55 dp bzw 44 dp, Luecke 0`() {
        assertEquals(55, KeyboardTheme.rowHeightDp(rows = 4))
        assertEquals(55, KeyboardTheme.rowHeightDp(rows = 3)) // unter 5 Reihen: 55 dp
        assertEquals(44, KeyboardTheme.rowHeightDp(rows = 5))  // PT: 5 Reihen
        assertEquals(44, KeyboardTheme.rowHeightDp(rows = 6))
        assertEquals(0, KeyboardTheme.ROW_GAP_DP)
        assertEquals(0, KeyboardTheme.KEY_GAP_DP)
        assertEquals(0f, KeyboardTheme.KEY_RADIUS_DP)
    }

    @Test
    fun `palette-Auswahl folgt Hell-Dunkel`() {
        assertEquals(KeyboardTheme.LIGHT, KeyboardTheme.palette(isDark = false))
        assertEquals(KeyboardTheme.DARK, KeyboardTheme.palette(isDark = true))
    }
}
