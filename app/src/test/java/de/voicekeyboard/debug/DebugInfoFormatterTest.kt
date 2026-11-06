package de.voicekeyboard.debug

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A14/T17 §5: the 8 required JVM tests for [DebugInfoFormatter].
 * Pure logic, no Android, no Robolectric — the formatter is deliberately
 * built Android-free so that exactly these tests run.
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
        // F13b2: long-run abort evidence fields (C1) — numbers/state keys only
        ctlState = "RECORDING",
        ctlFailed = false,
        droppedBlocks = 0,
        hypCount = 12,
        hypGapMs = 800,
        audioSeconds = 9.0,
        lastStartMs = 2144,
        starting = false,
        // F17b/C2: decode workload — numbers only (rule 9)
        decodeMsMax = 900,
        decodeMsP95 = 850,
        bufferSeconds = 8.5,
        // F21: cost-aware pace — numbers only (rule 9)
        decodePaceMs = 4000,
        decodeSkips = 7,
    )

    private val empty = DebugSnapshot()

    // 1. FIELD COUNT (F31→AP7-E1→A4a→W-vc34→T22i→T23h): header + 79 lines = 80 — the drift check
    // in the formatter (`check(lines.size == KEYS.size + 1)`) must catch every field change.
    @Test
    fun `format has exactly 80 lines - header plus 79 fields`() {
        for (s in listOf(full, empty)) {
            val lines = DebugInfoFormatter.format(s).lines()
            assertEquals("header + KEYS.size(79) fields", 80, lines.size)
            assertTrue("too few lines: ${lines.size}", lines.size >= 5)
        }
    }

    // 2. every line (after the header) matches `^[a-z0-9_]+: .*$`
    @Test
    fun `every line matches key-value pattern`() {
        val lines = DebugInfoFormatter.format(full).lines()
        assertEquals("header line fixed", "# VK-DEBUG v1 keine inhalte", lines.first())
        val pattern = Regex("^[a-z0-9_]+: .*$")
        for (line in lines.drop(1)) {
            assertTrue("line does not match key: value: '$line'", pattern.matches(line))
        }
        // fixed order (determinism via the keys)
        val keys = lines.drop(1).map { it.substringBefore(":") }
        assertEquals(
            listOf("app_version", "build", "api_level", "model_status", "model_variant",
                "model_version", "model_bytes", "last_error", "rtf", "first_hyp_ms",
                "threads", "permissions", "peak_pct", "route", "src", "peak_dbfs",
                "ctl_state", "ctl_failed", "dropped", "hyp_count", "hyp_gap_ms",
                "audio_s", "start_ms", "starting",
                "decode_ms_max", "decode_ms_p95", "buffer_s",
                "decode_pace_ms", "decode_skips", "cap_blocks", "cap_stall_ms",
                "abort_reason", "hyp_last_buffer_s", "mic_rel", "status_shown", "status_res", "loud_s",
                "had_output", "output_chars", "first_loud_ms",
                "first3s_loud_blocks", "first3s_peak_dbfs", "fgs_stopped_by",
                "stop_cause", "start_src", "gate_at_stop", "owner_lost_at_s",
                "segment_finalizations", "max_pause_ms", "restart_inputs", "sel_unexpected",
                "gate_foreign_resets", "rebases",
                // A4a (PLAN_V0133 §A4a): +10 write/decode diagnostic fields at the end.
                "last_decode_ms", "hyp_len", "current_len", "committed_len", "write_reason",
                "write_same", "write_w1", "write_w3d", "write_delta", "last_write_audio_s",
                // W package (vc34, debugger recommendation): +5 engine warming fields at the end.
                "engine_load_ms", "engine_resident", "warm_hit", "load_trigger", "engine_loads",
                // T22i (PLAN_V0146 §6): +2 model-set fields at the end.
                "active_model_set", "engine_set_used",
                // T23h (PLAN_V0147 §8): +9 correction fields at the end.
                "corr_row_enabled", "corr_lang_mode", "corr_rows_shown", "corr_applied",
                "corr_undone", "corr_computes", "corr_verify_fail", "corr_lang",
                "corr_region_bucket"),
            keys,
        )
    }

    // 3. no paths/URLs/emails/package names (no '/', 'http', '@', '\')
    @Test
    fun `contains no paths urls emails or package names`() {
        for (s in listOf(full, empty)) {
            val out = DebugInfoFormatter.format(s)
            assertFalse("path slash in the export", out.contains("/"))
            assertFalse("http in the export", out.contains("http"))
            assertFalse("email/@ in the export", out.contains("@"))
            assertFalse("backslash in the export", out.contains("\\"))
            assertFalse("package name in the export", out.contains("de.voicekeyboard"))
        }
    }

    // 4. version + versionCode appear verbatim
    @Test
    fun `version and code appear verbatim`() {
        val out = DebugInfoFormatter.format(full)
        assertTrue("versionName missing", out.contains("app_version: 0.1.4 (5)"))
    }

    // 5. missing values → `-`
    @Test
    fun `unknown values render as dash`() {
        val out = DebugInfoFormatter.format(empty)
        val lines = out.lines().drop(1)
        assertTrue("all values of an empty snapshot are '-'",
            lines.isNotEmpty() && lines.all { it.endsWith(": -") })
    }

    // 6. values with newline/colon are sanitized
    @Test
    fun `values with newline or colon are sanitized`() {
        val dirty = full.copy(
            modelVersionShort = "v-1\n2:3\r\nx",
            lastError = "audioInit:Security\nException:secret",
        )
        val out = DebugInfoFormatter.format(dirty)
        assertEquals("no multi-line output", 80, out.lines().size)
        for (line in out.lines().drop(1)) {
            val value = line.substringAfter(": ")
            assertFalse("colon in the value: '$value'", value.contains(":"))
            assertFalse("line break in the value", value.contains("\n"))
        }
        assertTrue("empty/dirty value collapses to '-'",
            out.lines().any { it.startsWith("model_version: ") })
    }

    // 7. model status and byte sum appear 1:1 in the export
    @Test
    fun `model status and byte sum match input`() {
        val out = DebugInfoFormatter.format(full)
        assertTrue(out.contains("model_status: READY"))
        assertTrue(out.contains("model_bytes: 670619706"))
    }

    // F42 (F41 missing measurement points): output/silence/FGS forensics —
    // numbers/codes only, never text content (rule 9); missing values = '-'.
    @Test
    fun `F42-Felder had_output output_chars first_loud_ms first3s fgs_stopped_by`() {
        val out = DebugInfoFormatter.format(
            DebugSnapshot(
                hadOutput = true,
                outputChars = 13,
                firstLoudMs = 2_140L,
                first3sLoudBlocks = 42,
                first3sPeakDbfs = -23.4,
                fgsStoppedBy = "abort:audio_error:silence",
            ),
        )
        assertTrue("had_output 0/1", out.lines().contains("had_output: 1"))
        assertTrue("output_chars number only", out.lines().contains("output_chars: 13"))
        assertTrue("first_loud_ms", out.lines().contains("first_loud_ms: 2140"))
        assertTrue("first3s_loud_blocks", out.lines().contains("first3s_loud_blocks: 42"))
        assertTrue("first3s_peak_dbfs 1 decimal", out.lines().contains("first3s_peak_dbfs: -23.4"))
        // colons in the code are sanitized (abort=audio_error=silence)
        assertTrue("fgs_stopped_by code sanitized", out.lines().contains("fgs_stopped_by: abort=audio_error=silence"))
        // empty values: '-' instead of guessing (never loud / no block / no FGS stop).
        val none = DebugInfoFormatter.format(DebugSnapshot()).lines()
        assertTrue("never loud -> -", none.contains("first_loud_ms: -"))
        assertTrue("no block -> -", none.contains("first3s_peak_dbfs: -"))
        assertTrue("no FGS stop -> -", none.contains("fgs_stopped_by: -"))
    }

    // 8. formatting twice ⇒ identical (determinism)
    @Test
    fun `output is deterministic`() {
        val a = DebugInfoFormatter.format(full)
        val b = DebugInfoFormatter.format(full.copy())
        assertEquals(a, b)
        assertEquals(DebugInfoFormatter.format(empty), DebugInfoFormatter.format(DebugSnapshot()))
    }

    // F10: capture route/source/level in dBFS — without these lines a phone
    // finding like "peak_pct: 2" cannot be interpreted (docs/DEBUG_INFO_FIELDS.md).
    @Test
    fun `new fields route src peak_dbfs appear and are cleanly absent as a dash`() {
        val fullText = DebugInfoFormatter.format(full)
        assertTrue(fullText.contains("route: intern"))
        assertTrue(fullText.contains("src: VOICE_RECOGNITION"))
        assertTrue(fullText.contains("peak_dbfs: -34.2"))
        val emptyText = DebugInfoFormatter.format(empty)
        assertTrue(emptyText.contains("route: -"))
        assertTrue(emptyText.contains("src: -"))
        assertTrue(emptyText.contains("peak_dbfs: -"))
        // the export text still contains no paths (rule 9).
        assertFalse(fullText.contains("/"))
    }

    // F52 (PLAN_V0130 A3, 🐞: status_res): code/resource NAME of the last shown
    // status text — NEVER the text itself (rule 9); colons in the code are
    // sanitized (like fgs_stopped_by); missing → '-'.
    @Test
    fun `F52 field status_res code only never content`() {
        val out = DebugInfoFormatter.format(
            DebugSnapshot(statusRes = "error:dictation_error_editor_busy"),
        )
        assertTrue(
            "status_res with resource name",
            out.lines().contains("status_res: error=dictation_error_editor_busy"),
        )
        // kinds without a colon stay unchanged.
        val plain = DebugInfoFormatter.format(DebugSnapshot(statusRes = "starting")).lines()
        assertTrue(plain.contains("status_res: starting"))
        // never content: the status text itself must not appear.
        assertFalse(out.contains("Starte"))
        // missing → '-', never a guess.
        val none = DebugInfoFormatter.format(DebugSnapshot()).lines()
        assertTrue(none.contains("status_res: -"))
    }

    // F13b2 (a-d): the 8 new diagnostic fields — exact keys, exact values,
    // fixed order at the end, missing values as a dash (never as content).
    @Test
    fun `new diagnostic fields ctl_state through starting appear exactly and are absent as a dash`() {
        val fullText = DebugInfoFormatter.format(full)
        // (d) exact key names and values in the full snapshot:
        assertTrue(fullText.contains("ctl_state: RECORDING"))
        assertTrue(fullText.contains("ctl_failed: 0"))
        assertTrue(fullText.contains("dropped: 0"))
        assertTrue(fullText.contains("hyp_count: 12"))
        assertTrue(fullText.contains("hyp_gap_ms: 800"))
        assertTrue(fullText.contains("audio_s: 9.0"))
        assertTrue(fullText.contains("start_ms: 2144"))
        assertTrue(fullText.contains("starting: 0"))
        // (b) missing values → "-", NEVER content (rule 9):
        val emptyText = DebugInfoFormatter.format(empty)
        for (key in listOf("ctl_state", "ctl_failed", "dropped", "hyp_count",
                "hyp_gap_ms", "audio_s", "start_ms", "starting")) {
            assertTrue("$key must be missing as a dash", emptyText.contains("$key: -"))
        }
        // (c) order exactly like KEYS — the evidence fields sit AT THE END
        // (F28b1: capture stall fields before the abort fields; F42: +6 forensic
        // fields after loud_s; W-vc34: +5 engine warming fields behind those).
        val keys = fullText.lines().drop(1).map { it.substringBefore(":") }
        assertEquals(
            listOf("ctl_state", "ctl_failed", "dropped", "hyp_count", "hyp_gap_ms",
                "audio_s", "start_ms", "starting",
                "decode_ms_max", "decode_ms_p95", "buffer_s",
                "decode_pace_ms", "decode_skips", "cap_blocks", "cap_stall_ms",
                "abort_reason", "hyp_last_buffer_s", "mic_rel", "status_shown", "status_res", "loud_s",
                "had_output", "output_chars", "first_loud_ms",
                "first3s_loud_blocks", "first3s_peak_dbfs", "fgs_stopped_by",
                // AP3 (export v2 part 1): +8 forensic fields at the end.
                "stop_cause", "start_src", "gate_at_stop", "owner_lost_at_s",
                "segment_finalizations", "max_pause_ms", "restart_inputs", "sel_unexpected",
                "gate_foreign_resets", "rebases",
                // A4a: +10 — the existing keys stay BEFORE the new ones.
                "last_decode_ms", "hyp_len", "current_len", "committed_len", "write_reason",
                "write_same", "write_w1", "write_w3d", "write_delta", "last_write_audio_s"),
            keys.dropLast(16).takeLast(47),
        )
        // F17b+F21: exact values in the full snapshot, dash when missing (numbers only, rule 9):
        assertTrue(fullText.contains("decode_ms_max: 900"))
        assertTrue(fullText.contains("decode_ms_p95: 850"))
        assertTrue(fullText.contains("buffer_s: 8.5"))
        assertTrue(fullText.contains("decode_pace_ms: 4000"))
        assertTrue(fullText.contains("decode_skips: 7"))
        for (key in listOf("decode_ms_max", "decode_ms_p95", "buffer_s",
                "decode_pace_ms", "decode_skips")) {
            assertTrue("$key must be missing as a dash", emptyText.contains("$key: -"))
        }
        // ctl_failed/starting are 0/1, never yes/no or true/false:
        val flagged = full.copy(ctlFailed = true, starting = true)
        val flaggedText = DebugInfoFormatter.format(flagged)
        assertTrue(flaggedText.contains("ctl_failed: 1"))
        assertTrue(flaggedText.contains("starting: 1"))
        assertFalse(flaggedText.contains("ctl_failed: yes"))
        assertFalse(flaggedText.contains("starting: true"))
    }

    // AP3 (PLAN_C2_FABLE_R2 §4, export v2 part 1): session forensics fields —
    // codes/numbers only (rule 9); a colon in the value is sanitized (=).
    @Test
    fun `AP3 fields stop_cause start_src gate_at_stop owner_lost_at_s and more`() {
        val out = DebugInfoFormatter.format(
            DebugSnapshot(
                stopCause = "audio_error=silence".replace("=", ":"), // value with ':' -> '='
                startSrc = "hold",
                gateAtStop = 1,
                ownerLostAtS = 42.5,
                segmentFinalizations = 3,
                maxPauseMs = 8100L,
                restartInputs = 2,
                selUnexpected = 5,
            ),
        )
        val lines = out.lines()
        assertTrue("stop_cause sanitized", lines.contains("stop_cause: audio_error=silence"))
        assertTrue(lines.contains("start_src: hold"))
        assertTrue(lines.contains("gate_at_stop: 1"))
        assertTrue("owner_lost_at_s 1 decimal", lines.contains("owner_lost_at_s: 42.5"))
        assertTrue(lines.contains("segment_finalizations: 3"))
        assertTrue(lines.contains("max_pause_ms: 8100"))
        assertTrue(lines.contains("restart_inputs: 2"))
        assertTrue(lines.contains("sel_unexpected: 5"))
        // never lost: missing values collapse to '-'
        val none = DebugInfoFormatter.format(DebugSnapshot()).lines()
        assertTrue("owner_lost_at_s missing -> -", none.contains("owner_lost_at_s: -"))
        assertTrue("stop_cause missing -> -", none.contains("stop_cause: -"))
    }

    // ---- A4a (PLAN_V0133 §A4a): +10 write/decode diagnostic fields ----

    // A4a red test 1: exactly the ten new keys, appended after rebases
    // (existing order untouched), no duplicates. W-vc34: the +5 engine
    // warming fields sit BEHIND the A4a fields (dropLast(5)).
    @Test
    fun `A4a contains exactly the ten new keys after rebases`() {
        val keys = DebugInfoFormatter.format(DebugSnapshot()).lines().drop(1).map { it.substringBefore(":") }
        assertEquals(
            listOf("last_decode_ms", "hyp_len", "current_len", "committed_len", "write_reason",
                "write_same", "write_w1", "write_w3d", "write_delta", "last_write_audio_s"),
            keys.dropLast(16).takeLast(10),
        )
        assertEquals("79 fields", 79, keys.size)
        assertEquals("no duplicate keys", keys.size, keys.toSet().size)
    }

    // A4a red test 2: missing values → '-'; NO write yields
    // last_write_audio_s=-1 (never '-'); ':' in the value is sanitized to '='.
    @Test
    fun `A4a missing values dash - last_write_audio_s -1 - colon sanitized`() {
        val empty = DebugInfoFormatter.format(DebugSnapshot()).lines()
        for (k in listOf("last_decode_ms", "hyp_len", "current_len", "committed_len", "write_reason",
                "write_same", "write_w1", "write_w3d", "write_delta", "last_write_audio_s")) {
            assertTrue("$k must be missing as a dash", empty.contains("$k: -"))
        }
        // no write: last_write_audio_s = -1 (number, NEVER '-')
        val noWrite = DebugInfoFormatter.format(
            DebugSnapshot(lastWriteAudioS = -1.0, writeReason = "w1", writeSame = 1, writeW1 = 1),
        ).lines()
        assertTrue("-1 before the first DELTA", noWrite.contains("last_write_audio_s: -1"))
        assertTrue(noWrite.contains("write_reason: w1"))
        assertTrue(noWrite.contains("write_same: 1"))
        assertTrue(noWrite.contains("write_w1: 1"))
        // ':' in the value is sanitized to '=' (rule 9/5)
        val dirty = DebugInfoFormatter.format(DebugSnapshot(writeReason = "same:x")).lines()
        assertTrue("colon sanitized", dirty.contains("write_reason: same=x"))
        // last write: 1 decimal like audio_s; all ten fields with values
        val wrote = DebugInfoFormatter.format(
            DebugSnapshot(
                lastDecodeMs = 427L, hypLen = 55, currentLen = 55, committedLen = 48,
                writeReason = "delta", writeDelta = 3, writeW3d = 1, lastWriteAudioS = 12.34,
            ),
        ).lines()
        assertTrue("1 decimal", wrote.contains("last_write_audio_s: 12.3"))
        assertTrue(wrote.contains("write_delta: 3"))
        assertTrue(wrote.contains("write_w3d: 1"))
        assertTrue("duration of the last decode run", wrote.contains("last_decode_ms: 427"))
        assertTrue("character lengths, never content (rule 9)", wrote.contains("hyp_len: 55"))
        assertTrue(wrote.contains("current_len: 55"))
        assertTrue(wrote.contains("committed_len: 48"))
        assertTrue("closed write code", wrote.contains("write_reason: delta"))
    }

    // A4a red test 3: the existing 53 KEYS stay unchanged IN ORDER;
    // header + 79 = 80 lines (W-vc34: +5, T22i: +2, T23h: +9), deterministic
    // for an empty and a full snapshot.
    @Test
    fun `A4a existing keys unchanged - 80 lines deterministic empty and full`() {
        val old53 = listOf("app_version", "build", "api_level", "model_status", "model_variant",
            "model_version", "model_bytes", "last_error", "rtf", "first_hyp_ms",
            "threads", "permissions", "peak_pct", "route", "src", "peak_dbfs",
            "ctl_state", "ctl_failed", "dropped", "hyp_count", "hyp_gap_ms",
            "audio_s", "start_ms", "starting",
            "decode_ms_max", "decode_ms_p95", "buffer_s",
            "decode_pace_ms", "decode_skips", "cap_blocks", "cap_stall_ms",
            "abort_reason", "hyp_last_buffer_s", "mic_rel", "status_shown", "status_res", "loud_s",
            "had_output", "output_chars", "first_loud_ms",
            "first3s_loud_blocks", "first3s_peak_dbfs", "fgs_stopped_by",
            "stop_cause", "start_src", "gate_at_stop", "owner_lost_at_s",
            "segment_finalizations", "max_pause_ms", "restart_inputs", "sel_unexpected",
            "gate_foreign_resets", "rebases")
        for (s in listOf(full, empty)) {
            val lines = DebugInfoFormatter.format(s).lines()
            assertEquals("header + 79 fields", 80, lines.size)
            assertEquals("existing 53 KEYS unchanged", old53, lines.drop(1).map { it.substringBefore(":") }.dropLast(26))
        }
        assertEquals(
            DebugInfoFormatter.format(DebugSnapshot()),
            DebugInfoFormatter.format(DebugSnapshot()),
        )
        assertEquals(DebugInfoFormatter.format(full), DebugInfoFormatter.format(full.copy()))
    }

    // ---- W package (vc34, debugger recommendation): +5 engine warming fields ----

    // W4c: exact keys/values at the end, header+79=80 lines, '-/default' for
    // the empty state (rule 9: numbers/codes mic|ime_show|'-' only, never content).
    @Test
    fun `W fields engine_load_ms through engine_loads exact - 80 lines - dash when empty`() {
        val out = DebugInfoFormatter.format(
            DebugSnapshot(
                engineLoadMs = 4531L,
                engineResident = true,
                warmHit = true,
                loadTrigger = "ime_show",
                engineLoads = 1,
            ),
        ).lines()
        assertTrue("pure load duration", out.contains("engine_load_ms: 4531"))
        assertTrue("resident 0/1", out.contains("engine_resident: 1"))
        assertTrue("warm start 0/1", out.contains("warm_hit: 1"))
        assertTrue("trigger code", out.contains("load_trigger: ime_show"))
        assertTrue("load counter", out.contains("engine_loads: 1"))
        // header + 79 fields = 80 lines (the drift check in the formatter secures the count)
        assertEquals(80, out.size)
        // the last seven keys exactly, in fixed order (after last_write_audio_s:
        // the W-vc34 engine fields, then the T22i model-set fields):
        assertEquals(
            listOf("engine_load_ms", "engine_resident", "warm_hit", "load_trigger", "engine_loads",
                "active_model_set", "engine_set_used"),
            out.drop(1).map { it.substringBefore(":") }.takeLast(16).take(7),
        )
        // empty state: '-' instead of guessing (no coordinator => everything a dash)
        val none = DebugInfoFormatter.format(DebugSnapshot()).lines()
        for (k in listOf("engine_load_ms", "engine_resident", "warm_hit", "load_trigger", "engine_loads")) {
            assertTrue("$k must be missing as a dash", none.contains("$k: -"))
        }
        assertEquals(80, none.size)
        // 0/1 encoding, never true/false (like ctl_failed/starting)
        val zero = DebugInfoFormatter.format(
            DebugSnapshot(engineResident = false, warmHit = false),
        ).lines()
        assertTrue(zero.contains("engine_resident: 0"))
        assertTrue(zero.contains("warm_hit: 0"))
    }

    // ---- T22i (PLAN_V0146 §6): +2 model-set fields (🐞 active_model_set /
    // engine_set_used) — set IDS (closed codes, rule 9), injected by the
    // caller (the collector never touches prefs). A FALLBACK is visible:
    // active=qwen3-asr-0.6b-int8 with engine_set_used=onnx-cpu means the target
    // set was not READY and the coordinator fell back to A. ----

    @Test
    fun `T22i fields active_model_set and engine_set_used - ids exact - fallback visible - dash when empty`() {
        val out = DebugInfoFormatter.format(
            DebugSnapshot(activeModelSet = "qwen3-asr-0.6b-int8", engineSetUsed = "onnx-cpu"),
        ).lines()
        assertTrue("active set id verbatim", out.contains("active_model_set: qwen3-asr-0.6b-int8"))
        assertTrue("engine set id verbatim", out.contains("engine_set_used: onnx-cpu"))
        // fallback case: BOTH values differ and are visible as such
        assertTrue(
            "fallback must be visible (different values)",
            out.contains("active_model_set: qwen3-asr-0.6b-int8") &&
                out.contains("engine_set_used: onnx-cpu"),
        )
        // no fallback: both identical (today's single-set world)
        val same = DebugInfoFormatter.format(
            DebugSnapshot(activeModelSet = "onnx-cpu", engineSetUsed = "onnx-cpu"),
        ).lines()
        assertTrue(same.contains("active_model_set: onnx-cpu"))
        assertTrue(same.contains("engine_set_used: onnx-cpu"))
        // missing values → '-', never a guess; appended AFTER engine_loads
        val none = DebugInfoFormatter.format(DebugSnapshot()).lines()
        assertTrue(none.contains("active_model_set: -"))
        assertTrue(none.contains("engine_set_used: -"))
        assertEquals(80, none.size)
        assertEquals(
            listOf("active_model_set", "engine_set_used"),
            none.drop(1).map { it.substringBefore(":") }.takeLast(11).take(2),
        )
        // a colon in the id would be sanitized (ids have none today — pinned anyway)
        val dirty = DebugInfoFormatter.format(
            DebugSnapshot(activeModelSet = "a:b", engineSetUsed = "c:d"),
        ).lines()
        assertTrue(dirty.contains("active_model_set: a=b"))
        assertTrue(dirty.contains("engine_set_used: c=d"))
    }
}
