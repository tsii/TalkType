package de.voicekeyboard.debug

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * `LevelPeak` is the only source of the peak level in the diagnostics line.
 * Proven: maximum formation, limits, behavior on nonsense values, reset.
 */
class LevelPeakTest {

    @Test
    fun `holds on to the highest value`() {
        val p = LevelPeak()
        p.note(0.10f)
        p.note(0.42f)
        p.note(0.30f)
        assertEquals(42, p.percent())
    }

    @Test
    fun `empty session shows zero instead of a random value`() {
        assertEquals(0, LevelPeak().percent())
    }

    @Test
    fun `full scale becomes 100 and no more`() {
        val p = LevelPeak()
        p.note(1.0f)
        p.note(3.7f) // out of range of measured values -> clamped at the top
        assertEquals(100, p.percent())
    }

    @Test
    fun `NaN and negative values do not confuse the display`() {
        val p = LevelPeak()
        p.note(Float.NaN)
        p.note(-0.5f)
        p.note(Float.POSITIVE_INFINITY)
        assertEquals(0, p.percent())
        p.note(0.25f)
        assertEquals(25, p.percent())
    }

    @Test
    fun `reset at session start`() {
        val p = LevelPeak()
        p.note(0.8f)
        p.reset()
        assertEquals(0, p.percent())
    }

    @Test
    fun `small level stays visible and does not become zero`() {
        val p = LevelPeak()
        p.note(0.004f) // ~ -48 dBFS
        assertEquals(0, p.percent()) // rounding down is ok, as long as...
        p.note(0.011f)
        assertEquals(1, p.percent()) // ...a real speech level stays visible
    }

    @Test
    fun `dbfs resolves where peak_pct floors to 2 (phone finding)`() {
        val p = LevelPeak()
        assertEquals(null, p.dbfs()) // nothing measured -> no value, no 0
        p.note(0.02f) // the phone finding: peak_pct 2
        assertEquals(2, p.percent())
        assertEquals(-34.0, p.dbfs()!!, 0.1) // visible as dBFS: -34 dBFS
    }

    @Test
    fun `dbfs grows with the level and does not exceed 0`() {
        val p = LevelPeak()
        p.note(0.6f) // emulator with audio bridge
        assertEquals(-4.4, p.dbfs()!!, 0.2)
        p.note(2f) // clipped -> clamped
        assertEquals(0.0, p.dbfs()!!, 0.001)
    }

    @Test
    fun `short shows percent and dBFS step in the diagnostics line`() {
        val p = LevelPeak()
        assertEquals("0", p.short()) // nothing measured -> no dB suffix
        p.note(0.02f) // phone finding
        assertEquals("2 (-34 dB)", p.short())
        p.note(0.6f) // emulator with audio bridge
        assertEquals("60 (-4 dB)", p.short())
    }
}
