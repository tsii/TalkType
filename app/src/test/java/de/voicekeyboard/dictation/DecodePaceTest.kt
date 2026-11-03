package de.voicekeyboard.dictation

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * F21: specification of the cost-aware decode pace [DecodePace.intervalMs].
 * Written BEFORE the production code. The pace may ONLY determine WHEN
 * decoding happens (rule 2 / KONZEPT §5.1: the whole window stays).
 */
class DecodePaceTest {

    private val base = 2000L
    private val max = 6000L

    // 1) fast engine (never measured) -> baseMs = behavior as today
    @Test fun `lastDecodeMs null shows baseMs`() {
        assertEquals(base, DecodePace.intervalMs(0L, 3000L, base, max))
    }

    // 2) very small decode time -> stays at baseMs (safety factor of 2 changes nothing)
    @Test fun `lastDecodeMs very small stays at baseMs`() {
        assertEquals(base, DecodePace.intervalMs(3L, 3000L, base, max))
    }

    // 3) 2x lastDecode exceeds max -> clamped to maxMs
    @Test fun `lastDecodeMs 4000 with base 2000 max 6000 clamps to 6000`() {
        assertEquals(max, DecodePace.intervalMs(4000L, 6000L, base, max))
    }

    // 4) outliers/overflow: huge lastDecodeMs -> maxMs, NEVER overflow/negative
    @Test fun `lastDecodeMs outliers and Long-MAX end at maxMs without overflow`() {
        assertEquals(max, DecodePace.intervalMs(900_000L, 10_000L, base, max))
        assertEquals(max, DecodePace.intervalMs(Long.MAX_VALUE, 10_000L, base, max))
        // right at the saturation boundary: 2x would overflow exactly
        assertEquals(max, DecodePace.intervalMs(Long.MAX_VALUE / 2 + 1, 10_000L, base, max))
    }

    // 5) defensive bracket: base > max -> maxMs, no crash
    @Test fun `baseMs greater than maxMs yields maxMs without crash`() {
        assertEquals(max, DecodePace.intervalMs(0L, 10_000L, 8000L, max))
        assertEquals(max, DecodePace.intervalMs(5000L, 10_000L, 8000L, max))
    }

    // 6) plausibility buffer: bufferMs < baseMs -> baseMs (never a pace < buffer age)
    @Test fun `Buffer younger than baseMs clamps to baseMs`() {
        // 500 ms decode ⇒ 1000 ms target, but the buffer is only 1500 ms old ⇒ baseMs 2000
        assertEquals(base, DecodePace.intervalMs(500L, 1500L, base, max))
    }
}
