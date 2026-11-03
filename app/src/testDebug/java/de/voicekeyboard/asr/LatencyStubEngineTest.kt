package de.voicekeyboard.asr

import de.voicekeyboard.audio.PcmWindow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AP1 (docs/PLAN_C2_FABLE_R2.md §1.2): Smoke-Test der LatencyStubEngine —
 * Geometrie des Pseudo-Texts (Länge ∝ Puffer, Satzzeichen alle 8 Wörter,
 * Präfixstabilität des wachsenden Puffers). Die Latenz selbst (Echtzeituhr,
 * Thread.sleep) wird bewusst NICHT gemessen — kein sleep-basiertes Timing im
 * Unit-Test (Harness-Disziplin); α=0 im Test.
 */
class LatencyStubEngineTest {

    private fun window(seconds: Int) = PcmWindow(ShortArray(seconds * 16_000), 16_000)

    @Test
    fun `Textlaenge waechst proportional zum Puffer`() = runBlocking {
        val engine = LatencyStubEngine(alphaMsPerSecond = 0.0, baseMs = 0L)
        val t2 = engine.transcribe(window(2), sessionId = 1, runId = 1).text
        val t4 = engine.transcribe(window(4), sessionId = 1, runId = 2).text
        // 2 Wörter pro Sekunde: 2 s → 4 Wörter, 4 s → 8 Wörter (8. Wort trägt
        // den Satzpunkt — Satzzeichen alle 8 Wörter, s. Test unten).
        assertEquals(listOf("wort1", "wort2", "wort3", "wort4"), t2.split(" "))
        assertEquals(listOf("wort1", "wort2", "wort3", "wort4", "wort5", "wort6", "wort7", "wort8."), t4.split(" "))
        assertTrue("Länge ∝ buffer_s", t4.length > t2.length)
    }

    @Test
    fun `Praefixstabilitaet - wachsender Puffer erweitert den Text`() = runBlocking {
        val engine = LatencyStubEngine(alphaMsPerSecond = 0.0, baseMs = 0L)
        val t2 = engine.transcribe(window(2), sessionId = 1, runId = 1).text
        val t4 = engine.transcribe(window(4), sessionId = 1, runId = 2).text
        // Commit/Agreement-Voraussetzung: die zweite Hypothese ERWEITERT die erste.
        assertTrue("t4 ist Erweiterung von t2", t4.startsWith(t2) || t4.startsWith("$t2 "))
    }

    @Test
    fun `Satzzeichen alle 8 Woerter - Punkt nur am 8ten Wort`() = runBlocking {
        val engine = LatencyStubEngine(alphaMsPerSecond = 0.0, baseMs = 0L)
        val t4 = engine.transcribe(window(4), sessionId = 1, runId = 1).text // 8 Wörter
        assertEquals("Punkt am 8. Wort (füttert Segmentregel a)", "wort8.", t4.split(" ").last())
        assertTrue("Hypothese endet mit Punkt", t4.endsWith("."))
        val t2 = engine.transcribe(window(2), sessionId = 1, runId = 2).text // 4 Wörter
        assertFalse("kein Punkt vor dem 8. Wort", t2.contains("."))
    }

    @Test
    fun `runId und sessionId werden monoton durchgereicht`() = runBlocking {
        val engine = LatencyStubEngine(alphaMsPerSecond = 0.0, baseMs = 0L)
        val h = engine.transcribe(window(1), sessionId = 7, runId = 42)
        assertEquals(7L, h.sessionId)
        assertEquals(42L, h.runId)
        assertEquals(16_000, h.audioSamples)
        assertFalse(h.incomplete)
    }
}
