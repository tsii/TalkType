package de.voicekeyboard.dictation

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * F21: Spezifikation des cost-aware Decode-Takts [DecodePace.intervalMs].
 * Geschrieben VOR dem Produktionscode. Der Takt darf NUR bestimmen, WANN
 * dekodiert wird (Regel 2 / KONZEPT §5.1: ganzes Fenster bleibt).
 */
class DecodePaceTest {

    private val base = 2000L
    private val max = 6000L

    // 1) schnelle Engine (nie gemessen) -> baseMs = Verhalten wie heute
    @Test fun `lastDecodeMs null zeigt baseMs`() {
        assertEquals(base, DecodePace.intervalMs(0L, 3000L, base, max))
    }

    // 2) sehr kleine Decode-Zeit -> tetap baseMs (Sicherheitsfaktor 2 ändert nichts)
    @Test fun `lastDecodeMs sehr klein zeigt baseMs`() {
        assertEquals(base, DecodePace.intervalMs(3L, 3000L, base, max))
    }

    // 3) 2x lastDecode überschreitet max -> Kappung auf maxMs
    @Test fun `lastDecodeMs 4000 bei base 2000 max 6000 kappt auf 6000`() {
        assertEquals(max, DecodePace.intervalMs(4000L, 6000L, base, max))
    }

    // 4) Ausreißer/Überlauf: riesige lastDecodeMs -> maxMs, NIE Overflow/Negatives
    @Test fun `lastDecodeMs Ausreisser und Long-MAX enden bei maxMs ohne Overflow`() {
        assertEquals(max, DecodePace.intervalMs(900_000L, 10_000L, base, max))
        assertEquals(max, DecodePace.intervalMs(Long.MAX_VALUE, 10_000L, base, max))
        // direkt an der Sättigungsgrenze: 2x würde exakt überlaufen
        assertEquals(max, DecodePace.intervalMs(Long.MAX_VALUE / 2 + 1, 10_000L, base, max))
    }

    // 5) defensive Klammer: base > max -> maxMs, kein Absturz
    @Test fun `baseMs grosser als maxMs liefert maxMs ohne Absturz`() {
        assertEquals(max, DecodePace.intervalMs(0L, 10_000L, 8000L, max))
        assertEquals(max, DecodePace.intervalMs(5000L, 10_000L, 8000L, max))
    }

    // 6) Plausibilitätspuffer: bufferMs < baseMs -> baseMs (nie Takt < Pufferalter)
    @Test fun `Buffer juenger als baseMs klemmt auf baseMs`() {
        // 500 ms Decode ⇒ 1000 ms Ziel, aber Puffer erst 1500 ms alt ⇒ baseMs 2000
        assertEquals(base, DecodePace.intervalMs(500L, 1500L, base, max))
    }
}
