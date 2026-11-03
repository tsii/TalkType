package de.voicekeyboard.ime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A4b (PLAN_V0133 §A4b) — kompakte `event=window`-VK-ASR-Zeile:
 *  1. Reine Formatierungsfunktion erzeugt GENAU EINE Zeile mit `event=window`
 *     und exakt allen 17 Metriken (audio_s..buffer_s, Plan-Reihenfolge).
 *  2. Regel 9: keine Text-/Audiofelder, keine Pfade/URLs/Paketnamen, nur
 *     geschlossene Write-Codes; kein Debug-Guard (reine Funktion ohne Android —
 *     die JVM-Testbarkeit selbst ist der Beweis, Release-wirksam via main-source).
 *
 * `out` kommt synchron vom bestehenden DictationOutputCounter (Parameter der
 * Funktion; Verdrahtung im Service).
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

    // A4b-Rote-Test 1: genau EINE Zeile, event=window, exakt alle 17 Metriken
    // in Plan-Reihenfolge mit exakten Formaten (1 Dezimale für Sekunden).
    @Test fun `A4b Fensterzeile enthaelt event=window und exakt 17 Metriken`() {
        val line = decodeWindowLine(full, out = 42)
        assertFalse("genau eine Zeile, kein Umbruch", line.contains('\n'))
        assertEquals(
            "event=window audio_s=12.3 out=42 decode_ms=427 pace_ms=4000 skips=3 " +
                "hyp_len=55 current_len=55 committed_len=48 write=delta " +
                "owner_lost_at_s=-1 sel_unexpected=0 restart_inputs=1 rebases=2 " +
                "hyp_gap_ms=1100 decode_ms_max=900 decode_ms_p95=850 buffer_s=12.0",
            line,
        )
        // Eigentumsfall: 1 Dezimale statt -1
        val owned = decodeWindowLine(full.copy(ownerLostAtS = 8.56), out = 0)
        assertTrue("owner_lost_at_s 1 Dezimale", owned.contains("owner_lost_at_s=8.6"))
    }

    // A4b-Rote-Test 2: Regel 9 — nur Zahlen/geschlossene Codes; keine Text-/
    // Audio-/Pfad-/Paketfelder; fremder Write-Code kollabiert defensiv auf '-'.
    @Test fun `A4b Regel9 - nur Zahlen und geschlossene Codes, keine Pfade Inhalte`() {
        val line = decodeWindowLine(full, out = 42)
        val parts = line.split(' ')
        assertEquals("event=window + 17 Metriken, nichts darueber/darunter", 18, parts.size)
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
                assertTrue("nur Zahlen, nie Inhalte (Regel 9): $p", Regex("^-?[0-9]+(\\.[0-9])?$").matches(v))
            }
        }
        assertFalse("kein Doppelpunkt", line.contains(":"))
        assertFalse("kein Pfad-Slash", line.contains("/"))
        assertFalse("keine URL", line.contains("http"))
        assertFalse("kein @", line.contains("@"))
        assertFalse("kein Paketname", line.contains("de.voicekeyboard"))
        // Defensiv-Sperre: ein korrupter Write-Code (Doppelpunkt/Leerzeichen)
        // kollabiert auf '-', die Zeile bleibt geschlossen und einzeilig:
        val dirty = decodeWindowLine(full.copy(write = "bo:ese x"), out = 0)
        assertEquals("write=-", dirty.split(' ').first { it.startsWith("write=") })
        assertFalse("auch nach Korruption einzeilig", dirty.contains('\n'))
    }
}
