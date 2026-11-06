package de.voicekeyboard.debug

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T23h (PLAN_V0147 §8): the 9 correction fields in the 🐞 export — counter
 * values, empty-session defaults (0/off), and ONLY codes/numbers (rule 9: the
 * export never carries typed or corrected text). Pure formatter matrix.
 */
class CorrectionFieldsTest {

    private fun line(out: String, key: String): String =
        out.lines().first { it.startsWith("$key: ") }

    @Test fun `counters render with their values`() {
        val s = DebugSnapshot(
            corrRowEnabled = true,
            corrLangMode = "all",
            corrRowsShown = 3,
            corrApplied = 2,
            corrUndone = 1,
            corrComputes = 5,
            corrVerifyFail = 0,
            corrLang = "de-de",
            corrRegionBucket = 2,
        )
        val out = DebugInfoFormatter.format(s)
        assertEquals("corr_row_enabled: on", line(out, "corr_row_enabled"))
        assertEquals("corr_lang_mode: all", line(out, "corr_lang_mode"))
        assertEquals("corr_rows_shown: 3", line(out, "corr_rows_shown"))
        assertEquals("corr_applied: 2", line(out, "corr_applied"))
        assertEquals("corr_undone: 1", line(out, "corr_undone"))
        assertEquals("corr_computes: 5", line(out, "corr_computes"))
        assertEquals("corr_verify_fail: 0", line(out, "corr_verify_fail"))
        assertEquals("corr_lang: de-de", line(out, "corr_lang"))
        assertEquals("corr_region_bucket: 2", line(out, "corr_region_bucket"))
    }

    @Test fun `empty session renders off current zeros and dash`() {
        val s = DebugSnapshot(
            corrRowEnabled = false,
            corrLangMode = "current",
            corrRowsShown = 0,
            corrApplied = 0,
            corrUndone = 0,
            corrComputes = 0,
            corrVerifyFail = 0,
            corrLang = null,
            corrRegionBucket = 0,
        )
        val out = DebugInfoFormatter.format(s)
        assertEquals("corr_row_enabled: off", line(out, "corr_row_enabled"))
        assertEquals("corr_lang_mode: current", line(out, "corr_lang_mode"))
        assertEquals("corr_rows_shown: 0", line(out, "corr_rows_shown"))
        assertEquals("corr_applied: 0", line(out, "corr_applied"))
        assertEquals("corr_undone: 0", line(out, "corr_undone"))
        assertEquals("corr_computes: 0", line(out, "corr_computes"))
        assertEquals("corr_verify_fail: 0", line(out, "corr_verify_fail"))
        assertEquals("corr_lang: -", line(out, "corr_lang"))
        assertEquals("corr_region_bucket: 0", line(out, "corr_region_bucket"))
    }

    @Test fun `corr lines carry only codes and numbers - never text`() {
        val s = DebugSnapshot(
            corrRowEnabled = true,
            corrLangMode = "current",
            corrRowsShown = 12,
            corrApplied = 4,
            corrUndone = 2,
            corrComputes = 30,
            corrVerifyFail = 1,
            corrLang = "en-us",
            corrRegionBucket = 3,
        )
        val corrLines = DebugInfoFormatter.format(s).lines().filter { it.startsWith("corr_") }
        assertEquals("exactly 9 corr fields", 9, corrLines.size)
        // rule 9: the value part is a code/number ONLY — no typed or
        // corrected text can enter the export through these fields.
        val pattern = Regex("^(on|off|current|all|-|[0-9]+|[a-z]{2}-[a-z]{2})$")
        for (l in corrLines) {
            assertTrue("value is not a code/number: '$l'", pattern.matches(l.substringAfter(": ")))
        }
    }
}
