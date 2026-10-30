package de.voicekeyboard.debug

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A14/T17 §5: die 8 geforderten JVM-Tests für [DebugInfoFormatter].
 * Reine Logik, kein Android, kein Robolectric — der Formatter ist
 * absichtlich Android-frei gebaut, damit genau diese Tests laufen.
 */
class DebugInfoFormatterTest {

    private val full = DebugSnapshot(
        versionName = "0.1.4",
        versionCode = 5,
        debugBuild = false,
        apiLevel = 36,
        modelStatus = "READY",
        modelVariant = "ONNX_CPU",
        modelVersionShort = "v-1730abcdef",
        modelBytes = 670_619_706L,
        lastError = "audioInit:SecurityException",
        rtf = 0.31,
        firstHypothesisMs = 2400,
        intraOpThreads = 8,
        interOpThreads = 1,
        permRecordAudio = true,
        permPostNotifications = true,
        peakPercent = 47,
        audioRoute = "intern",
        audioSource = "VOICE_RECOGNITION",
        peakDbfs = -34.2,
    )

    private val empty = DebugSnapshot()

    // 1. max. 20 Zeilen (Kopf + 16 Zeilen = 17; F10 hat route/src/peak_dbfs ergänzt)
    @Test
    fun `format has at most 20 lines`() {
        for (s in listOf(full, empty)) {
            val lines = DebugInfoFormatter.format(s).lines()
            assertTrue("zu viele Zeilen: ${lines.size}", lines.size <= 20)
            assertTrue("zu wenige Zeilen: ${lines.size}", lines.size >= 5)
        }
    }

    // 2. jede Zeile (nach dem Kopf) matcht `^[a-z0-9_]+: .*$`
    @Test
    fun `every line matches key-value pattern`() {
        val lines = DebugInfoFormatter.format(full).lines()
        assertEquals("Kopfzeile fest", "# VK-DEBUG v1 keine inhalte", lines.first())
        val pattern = Regex("^[a-z0-9_]+: .*$")
        for (line in lines.drop(1)) {
            assertTrue("Zeile matcht nicht key: value: '$line'", pattern.matches(line))
        }
        // feste Reihenfolge (Determinismus über die Keys)
        val keys = lines.drop(1).map { it.substringBefore(":") }
        assertEquals(
            listOf("app_version", "build", "api_level", "model_status", "model_variant",
                "model_version", "model_bytes", "last_error", "rtf", "first_hyp_ms",
                "threads", "permissions", "peak_pct", "route", "src", "peak_dbfs"),
            keys,
        )
    }

    // 3. keine Pfade/URLs/E-Mails/Paketnamen (kein '/', 'http', '@', '\')
    @Test
    fun `contains no paths urls emails or package names`() {
        for (s in listOf(full, empty)) {
            val out = DebugInfoFormatter.format(s)
            assertFalse("Pfad-Slash im Export", out.contains("/"))
            assertFalse("http im Export", out.contains("http"))
            assertFalse("E-Mail/@ im Export", out.contains("@"))
            assertFalse("Backslash im Export", out.contains("\\"))
            assertFalse("Paketname im Export", out.contains("de.voicekeyboard"))
        }
    }

    // 4. Version + versionCode erscheinen unverändert
    @Test
    fun `version and code appear verbatim`() {
        val out = DebugInfoFormatter.format(full)
        assertTrue("versionName fehlt", out.contains("app_version: 0.1.4 (5)"))
    }

    // 5. fehlende Werte → `-`
    @Test
    fun `unknown values render as dash`() {
        val out = DebugInfoFormatter.format(empty)
        val lines = out.lines().drop(1)
        assertTrue("alle Werte eines leeren Snapshots sind '-'",
            lines.isNotEmpty() && lines.all { it.endsWith(": -") })
    }

    // 6. Werte mit Umbruch/Doppelpunkt werden sanitisiert
    @Test
    fun `values with newline or colon are sanitized`() {
        val dirty = full.copy(
            modelVersionShort = "v-1\n2:3\r\nx",
            lastError = "audioInit:Security\nException:secret",
        )
        val out = DebugInfoFormatter.format(dirty)
        assertEquals("keine mehrzeilige Ausgabe", 17, out.lines().size)
        for (line in out.lines().drop(1)) {
            val value = line.substringAfter(": ")
            assertFalse("Doppelpunkt im Wert: '$value'", value.contains(":"))
            assertFalse("Umbruch im Wert", value.contains("\n"))
        }
        assertTrue("leerer/schmutziger Wert kollabiert zu '-'",
            out.lines().any { it.startsWith("model_version: ") })
    }

    // 7. Modellstatus und Byte-Summe stehen 1:1 im Export
    @Test
    fun `model status and byte sum match input`() {
        val out = DebugInfoFormatter.format(full)
        assertTrue(out.contains("model_status: READY"))
        assertTrue(out.contains("model_bytes: 670619706"))
    }

    // 8. zweimal formatieren ⇒ identisch (Determinismus)
    @Test
    fun `output is deterministic`() {
        val a = DebugInfoFormatter.format(full)
        val b = DebugInfoFormatter.format(full.copy())
        assertEquals(a, b)
        assertEquals(DebugInfoFormatter.format(empty), DebugInfoFormatter.format(DebugSnapshot()))
    }

    // F10: Aufnahmeweg/-eingang/Pegel in dBFS — ohne diese Zeilen ist ein
    // Telefonbefund wie „peak_pct: 2“ nicht deutbar (docs/DEBUG_INFO_FIELDS.md).
    @Test
    fun `neue Felder route src peak_dbfs erscheinen und fehlen sauber als Strich`() {
        val fullText = DebugInfoFormatter.format(full)
        assertTrue(fullText.contains("route: intern"))
        assertTrue(fullText.contains("src: VOICE_RECOGNITION"))
        assertTrue(fullText.contains("peak_dbfs: -34.2"))
        val emptyText = DebugInfoFormatter.format(empty)
        assertTrue(emptyText.contains("route: -"))
        assertTrue(emptyText.contains("src: -"))
        assertTrue(emptyText.contains("peak_dbfs: -"))
        // Der Exporttext enthaelt weiterhin keine Pfade (Regel 9).
        assertFalse(fullText.contains("/"))
    }
}
