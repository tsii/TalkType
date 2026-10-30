package de.voicekeyboard.debug

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * `LevelPeak` ist die einzige Quelle für den Spitzenpegel in der Diagnosezeile.
 * Bewiesen wird: Maximalbildung, Grenzen, Verhalten bei Unsinnswerten, Reset.
 */
class LevelPeakTest {

    @Test
    fun `haltet den hoechsten Wert fest`() {
        val p = LevelPeak()
        p.note(0.10f)
        p.note(0.42f)
        p.note(0.30f)
        assertEquals(42, p.percent())
    }

    @Test
    fun `leere Sitzung zeigt null statt Zufallswert`() {
        assertEquals(0, LevelPeak().percent())
    }

    @Test
    fun `Vollaussteuerung wird 100 und nicht mehr`() {
        val p = LevelPeak()
        p.note(1.0f)
        p.note(3.7f) // außer Reichweite gemessener Werte -> oben begrenzt
        assertEquals(100, p.percent())
    }

    @Test
    fun `NaN und negative Werte bringen die Anzeige nicht durcheinander`() {
        val p = LevelPeak()
        p.note(Float.NaN)
        p.note(-0.5f)
        p.note(Float.POSITIVE_INFINITY)
        assertEquals(0, p.percent())
        p.note(0.25f)
        assertEquals(25, p.percent())
    }

    @Test
    fun `Reset zu Sitzungsbeginn`() {
        val p = LevelPeak()
        p.note(0.8f)
        p.reset()
        assertEquals(0, p.percent())
    }

    @Test
    fun `kleiner Pegel bleibt sichtbar und wird nicht null`() {
        val p = LevelPeak()
        p.note(0.004f) // ~ -48 dBFS
        assertEquals(0, p.percent()) // Rundung nach unten ist ok, solange...
        p.note(0.011f)
        assertEquals(1, p.percent()) // ...ein realer Sprechpegel sichtbar bleibt
    }

    @Test
    fun `dbfs aufloesend wo peak_pct auf 2 runt (Telefonbefund)`() {
        val p = LevelPeak()
        assertEquals(null, p.dbfs()) // nichts gemessen -> kein Wert, keine 0
        p.note(0.02f) // der Telefonbefund: peak_pct 2
        assertEquals(2, p.percent())
        assertEquals(-34.0, p.dbfs()!!, 0.1) // als dBFS sichtbar: -34 dBFS
    }

    @Test
    fun `dbfs waechst mit dem Pegel und ueberschreitet 0 nicht`() {
        val p = LevelPeak()
        p.note(0.6f) // Emulator mit Audio-Bruecke
        assertEquals(-4.4, p.dbfs()!!, 0.2)
        p.note(2f) // uebersteuert -> begrenzt
        assertEquals(0.0, p.dbfs()!!, 0.001)
    }

    @Test
    fun `short zeigt Prozent und dBFS-Stufe in der Diagnosezeile`() {
        val p = LevelPeak()
        assertEquals("0", p.short()) // nichts gemessen -> kein dB-Anhängsel
        p.note(0.02f) // Telefonbefund
        assertEquals("2 (-34 dB)", p.short())
        p.note(0.6f) // Emulator mit Audio-Bruecke
        assertEquals("60 (-4 dB)", p.short())
    }
}
