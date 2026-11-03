package de.voicekeyboard.ime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A4b (PLAN_V0133 §A4b) — compact `event=window` VK-ASR line:
 *  1. The pure formatting function produces EXACTLY ONE line with `event=window`
 *     and exactly all 17 metrics (audio_s..buffer_s, plan order).
 *  2. Rule 9: no text/audio fields, no paths/URLs/package names, only closed
 *     write codes; no debug guard (pure function without Android — the JVM
 *     testability itself is the proof, effective in release via the main source).
 *
 * `out` comes synchronously from the existing DictationOutputCounter (function
 * parameter; wiring in the service).
 */
class DecodeWindowLogTest {

    private val full = DecodeWindowDiagnosis(
        audioS = 12.34,
        decodeMs = 427L,
        paceMs = 4000L,
        skips = 3,
        hypLen = 55,
        currentLen = 55,
        committedLen = 48,
        write = "delta",
        ownerLostAtS = -1.0,
        selUnexpected = 0,
        restartInputs = 1,
        rebases = 2,
        hypGapMs = 1100L,
        decodeMsMax = 900L,
        decodeMsP95 = 850L,
        bufferS = 11.96,
    )

    // A4b red test 1: exactly ONE line, event=window, exactly all 17 metrics
    // in plan order with exact formats (1 decimal for seconds).
    @Test fun `A4b window line contains event=window and exactly 17 metrics`() {
        val line = decodeWindowLine(full, out = 42)
        assertFalse("exactly one line, no line break", line.contains('\n'))
        assertEquals(
            "event=window audio_s=12.3 out=42 decode_ms=427 pace_ms=4000 skips=3 " +
                "hyp_len=55 current_len=55 committed_len=48 write=delta " +
                "owner_lost_at_s=-1 sel_unexpected=0 restart_inputs=1 rebases=2 " +
                "hyp_gap_ms=1100 decode_ms_max=900 decode_ms_p95=850 buffer_s=12.0",
            line,
        )
        // ownership case: 1 decimal instead of -1
        val owned = decodeWindowLine(full.copy(ownerLostAtS = 8.56), out = 0)
        assertTrue("owner_lost_at_s 1 Dezimale", owned.contains("owner_lost_at_s=8.6"))
    }

    // A4b red test 2: rule 9 — numbers/closed codes only; no text/audio/path/
    // package fields; a foreign write code collapses defensively to '-'.
    @Test fun `A4b rule 9 - only numbers and closed codes, no paths or content`() {
        val line = decodeWindowLine(full, out = 42)
        val parts = line.split(' ')
        assertEquals("event=window + 17 metrics, nothing above/below", 18, parts.size)
        assertEquals("event=window", parts.first())
        assertEquals(
            listOf("audio_s", "out", "decode_ms", "pace_ms", "skips", "hyp_len",
                "current_len", "committed_len", "write", "owner_lost_at_s",
                "sel_unexpected", "restart_inputs", "rebases", "hyp_gap_ms",
                "decode_ms_max", "decode_ms_p95", "buffer_s"),
            parts.drop(1).map { it.substringBefore('=') },
        )
        for (p in parts.drop(1)) {
            val v = p.substringAfter('=')
            if (p.startsWith("write=")) {
                assertTrue("geschlossener Write-Code: $v", v in setOf("same", "w1", "w3d", "delta", "-"))
            } else {
                assertTrue("numbers only, never content (rule 9): $p", Regex("^-?[0-9]+(\\.[0-9])?$").matches(v))
            }
        }
        assertFalse("no colon", line.contains(":"))
        assertFalse("no path slash", line.contains("/"))
        assertFalse("no URL", line.contains("http"))
        assertFalse("no @", line.contains("@"))
        assertFalse("no package name", line.contains("de.voicekeyboard"))
        // defensive barrier: a corrupt write code (colon/space)
        // collapses to '-', the line stays closed and single-line:
        val dirty = decodeWindowLine(full.copy(write = "bo:ese x"), out = 0)
        assertEquals("write=-", dirty.split(' ').first { it.startsWith("write=") })
        assertFalse("single line even after corruption", dirty.contains('\n'))
    }
}
