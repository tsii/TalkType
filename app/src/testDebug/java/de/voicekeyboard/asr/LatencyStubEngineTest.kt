package de.voicekeyboard.asr

import de.voicekeyboard.audio.PcmWindow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AP1 (docs/PLAN_C2_FABLE_R2.md §1.2): smoke test of the LatencyStubEngine —
 * geometry of the pseudo text (length ∝ buffer, punctuation every 8 words,
 * prefix stability of the growing buffer). The latency itself (real-time clock,
 * Thread.sleep) is deliberately NOT measured — no sleep-based timing in a
 * unit test (harness discipline); α=0 in the test.
 */
class LatencyStubEngineTest {

    private fun window(seconds: Int) = PcmWindow(ShortArray(seconds * 16_000), 16_000)

    @Test
    fun `text length grows proportionally to the buffer`() = runBlocking {
        val engine = LatencyStubEngine(alphaMsPerSecond = 0.0, baseMs = 0L)
        val t2 = engine.transcribe(window(2), sessionId = 1, runId = 1).text
        val t4 = engine.transcribe(window(4), sessionId = 1, runId = 2).text
        // 2 words per second: 2 s → 4 words, 4 s → 8 words (the 8th word carries
        // the period — punctuation every 8 words, see test below).
        assertEquals(listOf("wort1", "wort2", "wort3", "wort4"), t2.split(" "))
        assertEquals(listOf("wort1", "wort2", "wort3", "wort4", "wort5", "wort6", "wort7", "wort8."), t4.split(" "))
        assertTrue("length ∝ buffer_s", t4.length > t2.length)
    }

    @Test
    fun `prefix stability - a growing buffer extends the text`() = runBlocking {
        val engine = LatencyStubEngine(alphaMsPerSecond = 0.0, baseMs = 0L)
        val t2 = engine.transcribe(window(2), sessionId = 1, runId = 1).text
        val t4 = engine.transcribe(window(4), sessionId = 1, runId = 2).text
        // commit/agreement prerequisite: the second hypothesis EXTENDS the first.
        assertTrue("t4 is an extension of t2", t4.startsWith(t2) || t4.startsWith("$t2 "))
    }

    @Test
    fun `punctuation every 8 words - period only on the 8th word`() = runBlocking {
        val engine = LatencyStubEngine(alphaMsPerSecond = 0.0, baseMs = 0L)
        val t4 = engine.transcribe(window(4), sessionId = 1, runId = 1).text // 8 words
        assertEquals("period on the 8th word (feeds segment rule a)", "wort8.", t4.split(" ").last())
        assertTrue("the hypothesis ends with a period", t4.endsWith("."))
        val t2 = engine.transcribe(window(2), sessionId = 1, runId = 2).text // 4 words
        assertFalse("no period before the 8th word", t2.contains("."))
    }

    @Test
    fun `runId and sessionId are passed through monotonically`() = runBlocking {
        val engine = LatencyStubEngine(alphaMsPerSecond = 0.0, baseMs = 0L)
        val h = engine.transcribe(window(1), sessionId = 7, runId = 42)
        assertEquals(7L, h.sessionId)
        assertEquals(42L, h.runId)
        assertEquals(16_000, h.audioSamples)
        assertFalse(h.incomplete)
    }
}
