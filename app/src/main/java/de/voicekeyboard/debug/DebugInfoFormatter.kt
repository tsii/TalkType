package de.voicekeyboard.debug

/**
 * A14/T17: pure JVM formatter for the debug info export (NO Android import,
 * hence testable without a device — see DebugInfoFormatterTest).
 *
 * Rule 9 (docs/DEBUG_INFO_FIELDS.md §2): the export contains ONLY numbers,
 * status codes and version short names — no audio/text contents, no paths,
 * no URLs, no target app package name. The export text is never logged
 * (that concerns the calling layer, DebugInfoCollector).
 *
 * Format: header line with a visible assurance, then EXACTLY 70 lines
 * `schluessel: wert` in fixed order (determinism), missing values
 * as `-`, values sanitized to a single line (no line breaks, no colons).
 */

/** Only primitive fields/strings — the Android layer (collector) fills them. */
data class DebugSnapshot(
    /** versionName from PackageManager (BuildConfig does not exist in this module). */
    val versionName: String? = null,
    /** versionCode from PackageManager. */
    val versionCode: Int? = null,
    /** debug/release via ApplicationInfo.FLAG_DEBUGGABLE. */
    val debugBuild: Boolean? = null,
    val apiLevel: Int? = null,
    /** ModelStatus name (READY/BROKEN/ABSENT/…). */
    val modelStatus: String? = null,
    /** ModelVariant name (ONNX_CPU/LITERT_CPU). */
    val modelVariant: String? = null,
    /** Version short name (e.g. `v-1730…`) — NEVER the full path (§2.1). */
    val modelVersionShort: String? = null,
    /** Sum of the model files in bytes (no names, no paths). */
    val modelBytes: Long? = null,
    /** Last error code (E5 class, e.g. `audioInit=SecurityException`) — never message. */
    val lastError: String? = null,
    /** RTF = processingMs/audioMs; null if there is no audio duration yet. */
    val rtf: Double? = null,
    /** First-hypothesis latency in milliseconds; 0/null = none yet. */
    val firstHypothesisMs: Long? = null,
    val intraOpThreads: Int? = null,
    val interOpThreads: Int? = null,
    val permRecordAudio: Boolean? = null,
    val permPostNotifications: Boolean? = null,
    /** Peak level 0..100 (one maximum value, no curve — LevelPeak). */
    val peakPercent: Int? = null,

    /** Recording route per AudioRouter: „intern“ / Headset / Bluetooth — only the short name. */
    val audioRoute: String? = null,

    /** Used AudioRecord input (e.g. VOICE_RECOGNITION) — only the name, no path. */
    val audioSource: String? = null,

    /** Peak in dBFS (<= 0). peak_pct is no longer resolvable for a very quiet recording. */
    val peakDbfs: Double? = null,

    // ---- F13b2: evidence fields for the long-text abort (C1) — numbers/states only ----

    /** Controller state as a short code (IDLE/RECORDING/FINALIZING) — the
     *  evidence case: IDLE while the user is still pressing ⇒ C1 (silently failed). */
    val ctlState: String? = null,

    /** 0/1: failedThisSession (error preserved until the next start). */
    val ctlFailed: Boolean? = null,

    /** Number of discarded audio blocks (after a silent fail — F13b drop counter). */
    val droppedBlocks: Int? = null,

    /** Number of hypotheses produced in this session. */
    val hypCount: Int? = null,

    /** Gap since the last hypothesis in ms; -1 if none ever came (> 4000 ms ⇒ backpressure). */
    val hypGapMs: Long? = null,

    /** Audio recorded so far in seconds, 1 decimal place. */
    val audioSeconds: Double? = null,

    /** Duration of the last start in ms (0 = not measured). */
    val lastStartMs: Long? = null,

    /** 0/1: a start is currently running (watchdog case: 1 with an unusual start_ms). */
    val starting: Boolean? = null,

    // ---- F17b/C2: decode workload (C2) — numbers only, no contents ----

    /** Largest decode time per transcribe call in ms (0 = never decoded yet). */
    val decodeMsMax: Long? = null,

    /** p95 of the decode times in ms (deterministic sample of the controller). */
    val decodeMsP95: Long? = null,

    /** Buffer length in seconds at the last decode run (does decode_ms grow?). */
    val bufferSeconds: Double? = null,

    // ---- F21: cost-aware decode pace — numbers only, no contents ----

    /** Currently effective partial-decode interval in ms (0 = pacing off). */
    val decodePaceMs: Long? = null,

    /** Number of partial decodes skipped due to the pace (never an error counter). */
    val decodeSkips: Int? = null,
    /** F28b1: received audio blocks (n>0) of the session (capture stall measurement). */
    val capBlocks: Int? = null,
    /** F28b1: ms since the last audio block or the session start (0 = no session). */
    val capStallMs: Long? = null,
    /** F26a: reason of the last abort/outcome (C2 export: separating E3/E4/E5). */
    val abortReason: String? = null,
    /** F26b (C2 export): buffer level (s) of the last accepted hypothesis. */
    val hypLastBufferSeconds: Double? = null,
    /** F28b2: last session-end event (coded + ms since session start); "" = none. */
    val micRel: String? = null,

    // AP3 (PLAN_C2_FABLE_R2 §4, export v2 part 1) — session forensics:
    /** Unified stop cause (stopCauseOf: holdup|up|toggle|autostop|…). */
    val stopCause: String? = null,
    /** Start kind (hold|toggle). */
    val startSrc: String? = null,
    /** F43 gate at the stop moment: 1=open, 0=closed (held), -1=no stop. */
    val gateAtStop: Int? = null,
    /** Ownership-loss moment in session audio seconds; -1 = never. */
    val ownerLostAtS: Double? = null,
    /** Number of executed segment mini-finales in this session. */
    val segmentFinalizations: Int? = null,
    /** Longest loud gap of the session in ms (block RMS). */
    val maxPauseMs: Long? = null,
    /** onStartInput(restarting=true) counter. */
    val restartInputs: Int? = null,
    /** Unexpected selection callbacks (ownership-loss family). */
    val selUnexpected: Int? = null,

    /** AP7-W1 (Fable §0.1.6): rejected foreign resets of the hold gate. */
    val gateForeignResets: Int? = null,

    /** AP7-E1 (Fable §0.2.5): re-baselines after restartInput (🐞 rebases). */
    val rebases: Int? = null,
    /** F28b2: last displayed status state (ready|dictat|finalizing|error). */
    val statusShown: String? = null,
    /** F52: code/resource name of the last shown status row text
     *  (starting|recording|finalizing|idle|hint|diag|error:<res>|raw) — never
     *  the text itself (rule 9). */
    val statusRes: String? = null,
    /** F31: seconds with a loud signal (block RMS ≥ threshold) of the session (rule 9: number). */
    val loudS: Double? = null,

    // ---- F42 (F41 missing measurement points): output/silence/FGS forensics — numbers/codes only ----

    /** F42 (🐞: had_output): 0/1 — at least one hypothesis commit in this session
     *  (resolves the "13 characters" similarity: did ANY text arrive?). */
    val hadOutput: Boolean? = null,

    /** F42 (🐞: output_chars): gross character count of the commits (the number only,
     *  NEVER the text — rule 9). */
    val outputChars: Int? = null,

    /** F42 (🐞: first_loud_ms): ms after session start at the everLoud latch; -1 = never. */
    val firstLoudMs: Long? = null,

    /** F42 (🐞: first3s_loud_blocks): loud blocks in the first 3 s (start window). */
    val first3sLoudBlocks: Int? = null,

    /** F42 (🐞: first3s_peak_dbfs): peak block RMS of the first 3 s in dBFS;
     *  null = no block in the window. */
    val first3sPeakDbfs: Double? = null,

    /** F42 (🐞: fgs_stopped_by): cause code of the last microphone FGS stop (F44:
     *  „abort:<reason>“/„stop“/„fgs-timeout“…; codes only, never contents — rule 9). */
    val fgsStoppedBy: String? = null,

    // ---- A4a (PLAN_V0133 §A4a): decode/write diagnostics (A2b) — numbers/codes only ----

    /** A4a (🐞: last_decode_ms): duration of the last decode run in ms (A2b
     *  diagnostics object; null = no window yet). */
    val lastDecodeMs: Long? = null,

    /** A4a (🐞: hyp_len): length of the last hypothesis in characters — never content. */
    val hypLen: Int? = null,

    /** A4a (🐞: current_len): own text length after the last window. */
    val currentLen: Int? = null,

    /** A4a (🐞: committed_len): committed prefix length after the last window. */
    val committedLen: Int? = null,

    /** A4a (🐞: write_reason): last write code (same|w1|w3d|delta; "-" = none). */
    val writeReason: String? = null,

    /** A4a (🐞: write_same): SAME results in this session (monotone). */
    val writeSame: Int? = null,

    /** A4a (🐞: write_w1): refused write intentions. */
    val writeW1: Int? = null,

    /** A4a (🐞: write_w3d): blocked rollbacks (visible once). */
    val writeW3d: Int? = null,

    /** A4a (🐞: write_delta): executed write steps. */
    val writeDelta: Int? = null,

    /** A4a (🐞: last_write_audio_s): session audio seconds of the last DELTA;
     *  -1 = no DELTA yet (NEVER '-' — the value always exists per the controller). */
    val lastWriteAudioS: Double? = null,

    // ---- W package (vc34, debugger recommendation): engine warming — numbers/codes only ----

    /** W1 (🐞: engine_load_ms): pure duration of the last engine.load() run in ms
     *  (0 = never loaded; number only, rule 9). */
    val engineLoadMs: Long? = null,

    /** W1/W2 (🐞: engine_resident): 0/1 — the engine is loaded and stays resident
     *  until shutdown() (deliberately NO idle unload; warmth for the next session). */
    val engineResident: Boolean? = null,

    /** W1 (🐞: warm_hit): 0/1 — the last dictation start found the engine already
     *  resident (show preload took effect; start_ms without load time). */
    val warmHit: Boolean? = null,

    /** W1 (🐞: load_trigger): trigger of the last successful load()
     *  (mic | ime_show; "-" = never loaded). */
    val loadTrigger: String? = null,

    /** W1 (🐞: engine_loads): number of executed engine.load() runs (monotone
     *  until shutdown; number only, rule 9). */
    val engineLoads: Int? = null,

    // ---- T22i (PLAN_V0146 §6): model set fields — ids/codes only (rule 9) ----

    /** T22i (🐞: active_model_set): id of the ACTIVE model set (prefs choice,
     *  injected by the caller — the collector never reads prefs), e.g.
     *  `onnx-cpu`/`qwen3-asr-0.6b-int8`. */
    val activeModelSet: String? = null,

    /** T22i (🐞: engine_set_used): id of the set whose engine is ACTUALLY
     *  loaded — different from [activeModelSet] ⇒ fallback visible (target
     *  set not READY, coordinator fell back to A). */
    val engineSetUsed: String? = null,

    // ================================================= T23h (PLAN_V0147 §8)
    /** T23h (🐞: corr_row_enabled): typing suggestions switch state (on/off). */
    val corrRowEnabled: Boolean? = null,
    /** T23h (🐞: corr_lang_mode): correction language mode (current/all). */
    val corrLangMode: String? = null,
    /** T23h (🐞: corr_rows_shown): publishes with a suggestion (counter). */
    val corrRowsShown: Int? = null,
    /** T23h (🐞: corr_applied): wand applies (counter). */
    val corrApplied: Int? = null,
    /** T23h (🐞: corr_undone): wand undos (counter). */
    val corrUndone: Int? = null,
    /** T23h (🐞: corr_computes): region computes (debounce + separator). */
    val corrComputes: Int? = null,
    /** T23h (🐞: corr_verify_fail): failed suffix proofs (counter). */
    val corrVerifyFail: Int? = null,
    /** T23h (🐞: corr_lang): layout id of the last compute (code, no text). */
    val corrLang: String? = null,
    /** T23h (🐞: corr_region_bucket): typed-region length bucket
     *  (0 / 1-50 / 51-150 / 151-500). */
    val corrRegionBucket: Int? = null,
)

object DebugInfoFormatter {

    /** Visible rule-9 assurance in the header line. */
    private const val HEADER = "# VK-DEBUG v1 keine inhalte"

    /** Fixed field order (F13b2: +8, F17b: +3, F21: +2, F26a/b: +2, F28b1: +2, F28b2: +2, F31: +1, F42: +6, F52: +1, AP3: +8, A4a: +10, W-vc34: +5, T22i: +2). */
    private val KEYS = listOf(
        "app_version", "build", "api_level", "model_status", "model_variant",
        "model_version", "model_bytes", "last_error", "rtf", "first_hyp_ms",
        "threads", "permissions", "peak_pct", "route", "src", "peak_dbfs",
        "ctl_state", "ctl_failed", "dropped", "hyp_count", "hyp_gap_ms",
        "audio_s", "start_ms", "starting",
        "decode_ms_max", "decode_ms_p95", "buffer_s",
        "decode_pace_ms", "decode_skips",
        "cap_blocks", "cap_stall_ms",
        "abort_reason", "hyp_last_buffer_s",
        "mic_rel", "status_shown", "status_res",
        "loud_s",
        "had_output", "output_chars", "first_loud_ms",
        "first3s_loud_blocks", "first3s_peak_dbfs", "fgs_stopped_by",
        // AP3 (export v2 part 1):
        "stop_cause", "start_src", "gate_at_stop", "owner_lost_at_s",
        "segment_finalizations", "max_pause_ms", "restart_inputs", "sel_unexpected",
        "gate_foreign_resets", "rebases",
        // A4a (PLAN_V0133 §A4a): +10 decode/write diagnostics — APPENDED, the
        // existing 53 stay in front of it in order unchanged.
        "last_decode_ms", "hyp_len", "current_len", "committed_len", "write_reason",
        "write_same", "write_w1", "write_w3d", "write_delta", "last_write_audio_s",
        // W package (vc34, debugger recommendation): +5 engine warming — APPENDED,
        // the stock fields stay in front of it in order unchanged.
        "engine_load_ms", "engine_resident", "warm_hit", "load_trigger", "engine_loads",
        // T22i (PLAN_V0146 §6): +2 model-set fields — APPENDED, the existing
        // 68 stay in front of it in order unchanged.
        "active_model_set", "engine_set_used",
        // T23h (PLAN_V0147 §8): +9 correction fields — APPENDED, the existing
        // 70 stay in front of it in order unchanged.
        "corr_row_enabled", "corr_lang_mode", "corr_rows_shown", "corr_applied",
        "corr_undone", "corr_computes", "corr_verify_fail", "corr_lang",
        "corr_region_bucket",
    )

    fun format(s: DebugSnapshot): String {
        val lines = mutableListOf(HEADER)
        lines += "app_version: " + value(
            if (s.versionName != null || s.versionCode != null) {
                "${s.versionName ?: "-"} (${s.versionCode ?: "-"})"
            } else null,
        )
        lines += "build: " + value(s.debugBuild?.let { if (it) "debug" else "release" })
        lines += "api_level: " + value(s.apiLevel?.toString())
        lines += "model_status: " + value(s.modelStatus)
        lines += "model_variant: " + value(s.modelVariant)
        lines += "model_version: " + value(s.modelVersionShort)
        lines += "model_bytes: " + value(s.modelBytes?.toString())
        lines += "last_error: " + value(s.lastError)
        lines += "rtf: " + value(s.rtf?.let { "%.2f".format(it) })
        lines += "first_hyp_ms: " + value(s.firstHypothesisMs?.takeIf { it > 0 }?.toString())
        lines += "threads: " + value(
            if (s.intraOpThreads != null || s.interOpThreads != null) {
                "intra=${s.intraOpThreads ?: "-"} inter=${s.interOpThreads ?: "-"}"
            } else null,
        )
        lines += "permissions: " + value(
            if (s.permRecordAudio != null || s.permPostNotifications != null) {
                "record=${yn(s.permRecordAudio)} notif=${yn(s.permPostNotifications)}"
            } else null,
        )
        lines += "peak_pct: " + value(s.peakPercent?.toString())
        lines += "route: " + value(s.audioRoute)
        lines += "src: " + value(s.audioSource)
        lines += "peak_dbfs: " + value(s.peakDbfs?.let { "%.1f".format(it) })
        // F13b2: evidence fields for the long-text abort (C1) — numbers/states only.
        lines += "ctl_state: " + value(s.ctlState)
        lines += "ctl_failed: " + value(s.ctlFailed?.let { if (it) "1" else "0" })
        lines += "dropped: " + value(s.droppedBlocks?.toString())
        lines += "hyp_count: " + value(s.hypCount?.toString())
        lines += "hyp_gap_ms: " + value(s.hypGapMs?.toString())
        lines += "audio_s: " + value(s.audioSeconds?.let { "%.1f".format(it) })
        lines += "start_ms: " + value(s.lastStartMs?.toString())
        lines += "starting: " + value(s.starting?.let { if (it) "1" else "0" })
        // F17b/C2: decode workload — numbers only (rule 9).
        lines += "decode_ms_max: " + value(s.decodeMsMax?.toString())
        lines += "decode_ms_p95: " + value(s.decodeMsP95?.toString())
        lines += "buffer_s: " + value(s.bufferSeconds?.let { "%.1f".format(it) })
        // F21: cost-aware pace — numbers only (rule 9).
        lines += "decode_pace_ms: " + value(s.decodePaceMs?.toString())
        lines += "decode_skips: " + value(s.decodeSkips?.toString())
        // F28b1: capture stall measurement — numbers only (rule 9).
        lines += "cap_blocks: " + value(s.capBlocks?.toString())
        lines += "cap_stall_ms: " + value(s.capStallMs?.toString())
        // F26a: abort reason (C2 export) — controlled names, no contents (rule 9).
        lines += "abort_reason: " + value(s.abortReason)
        // F26b (C2 export): buffer level of the last hypothesis — 1 decimal like buffer_s.
        lines += "hyp_last_buffer_s: " + value(s.hypLastBufferSeconds?.let { "%.1f".format(it) })
        // F28b2: session-end diagnostics — pure codes/ms, no contents (rule 9).
        lines += "mic_rel: " + value(s.micRel)
        lines += "status_shown: " + value(s.statusShown)
        lines += "status_res: " + value(s.statusRes)
        // F31: silence detection — 1 decimal like audio_s (rule 9: numbers only).
        lines += "loud_s: " + value(s.loudS?.let { "%.1f".format(it) })
        // F42 (F41 missing measurement points): output/silence/FGS forensics — numbers/codes only.
        lines += "had_output: " + value(s.hadOutput?.let { if (it) "1" else "0" })
        lines += "output_chars: " + value(s.outputChars?.toString())
        lines += "first_loud_ms: " + value(s.firstLoudMs?.toString())
        lines += "first3s_loud_blocks: " + value(s.first3sLoudBlocks?.toString())
        lines += "first3s_peak_dbfs: " + value(s.first3sPeakDbfs?.let { "%.1f".format(it) })
        lines += "fgs_stopped_by: " + value(s.fgsStoppedBy)
        // AP3 (export v2 part 1): session forensics — codes/numbers only (rule 9).
        lines += "stop_cause: " + value(s.stopCause)
        lines += "start_src: " + value(s.startSrc)
        lines += "gate_at_stop: " + value(s.gateAtStop?.toString())
        lines += "owner_lost_at_s: " + value(
            s.ownerLostAtS?.let { if (it < 0) "-1" else "%.1f".format(it) },
        )
        lines += "segment_finalizations: " + value(s.segmentFinalizations?.toString())
        lines += "max_pause_ms: " + value(s.maxPauseMs?.toString())
        lines += "restart_inputs: " + value(s.restartInputs?.toString())
        lines += "sel_unexpected: " + value(s.selUnexpected?.toString())
        // AP7-W1: gate_foreign_resets ≥ 1 = a foreign reset arrived and was
        // rejected (positive W1b evidence, Fable §0.1.6).
        lines += "gate_foreign_resets: " + value(s.gateForeignResets?.toString())
        // AP7-E1: rebases ≥ 1 = an app restart was survived (re-baseline instead of abort).
        lines += "rebases: " + value(s.rebases?.toString())
        // A4a (PLAN_V0133 §A4a): decode/write diagnostics (A2b) — numbers/
        // closed codes only, never contents (rule 9); window values are missing without a
        // decode as '-', last_write_audio_s is NEVER null per the controller
        // (-1 = no DELTA yet).
        lines += "last_decode_ms: " + value(s.lastDecodeMs?.toString())
        lines += "hyp_len: " + value(s.hypLen?.toString())
        lines += "current_len: " + value(s.currentLen?.toString())
        lines += "committed_len: " + value(s.committedLen?.toString())
        lines += "write_reason: " + value(s.writeReason)
        lines += "write_same: " + value(s.writeSame?.toString())
        lines += "write_w1: " + value(s.writeW1?.toString())
        lines += "write_w3d: " + value(s.writeW3d?.toString())
        lines += "write_delta: " + value(s.writeDelta?.toString())
        lines += "last_write_audio_s: " + value(
            s.lastWriteAudioS?.let { if (it < 0) "-1" else "%.1f".format(it) },
        )
        // W package (vc34, debugger recommendation): engine warming — numbers /
        // closed codes only (mic|ime_show|"-"), never contents (rule 9).
        lines += "engine_load_ms: " + value(s.engineLoadMs?.toString())
        lines += "engine_resident: " + value(s.engineResident?.let { if (it) "1" else "0" })
        lines += "warm_hit: " + value(s.warmHit?.let { if (it) "1" else "0" })
        lines += "load_trigger: " + value(s.loadTrigger)
        lines += "engine_loads: " + value(s.engineLoads?.toString())
        // T22i (PLAN_V0146 §6): model set ids — closed codes only (rule 9);
        // active_model_set != engine_set_used makes an engine fallback visible.
        lines += "active_model_set: " + value(s.activeModelSet)
        lines += "engine_set_used: " + value(s.engineSetUsed)
        // T23h (PLAN_V0147 §8): correction fields — counters/codes ONLY
        // (rule 9: never typed or corrected text).
        lines += "corr_row_enabled: " + value(s.corrRowEnabled?.let { if (it) "on" else "off" })
        lines += "corr_lang_mode: " + value(s.corrLangMode)
        lines += "corr_rows_shown: " + value(s.corrRowsShown?.toString())
        lines += "corr_applied: " + value(s.corrApplied?.toString())
        lines += "corr_undone: " + value(s.corrUndone?.toString())
        lines += "corr_computes: " + value(s.corrComputes?.toString())
        lines += "corr_verify_fail: " + value(s.corrVerifyFail?.toString())
        lines += "corr_lang: " + value(s.corrLang)
        lines += "corr_region_bucket: " + value(s.corrRegionBucket?.toString())
        check(lines.size == KEYS.size + 1) { "Feldzahl driftet: ${lines.size}" }
        return lines.joinToString("\n")
    }

    private fun yn(b: Boolean?) = when (b) {
        true -> "yes"
        false -> "no"
        null -> "-"
    }

    /**
     * Sanitizing (rule 5 of the analysis): single-line, no line breaks, no
     * colons in the value, multiple spaces collapsed, empty/unknown
     * → `-`. Colons become `=` (error codes stay readable,
     * e.g. `audioInit=SecurityException`).
     */
    internal fun value(raw: String?): String {
        if (raw == null) return "-"
        val cleaned = raw
            .replace("\r", " ")
            .replace("\n", " ")
            .replace("\t", " ")
            .replace(":", "=")
            .replace("  ", " ")
            .trim()
        return if (cleaned.isEmpty()) "-" else cleaned
    }
}
