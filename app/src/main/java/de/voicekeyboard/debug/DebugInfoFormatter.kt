package de.voicekeyboard.debug

/**
 * A14/T17: Rein JVM-Formatierer für den Debug-Info-Export (KEIN Android-Import,
 * deshalb ohne Gerät testbar — siehe DebugInfoFormatterTest).
 *
 * Regel 9 (docs/DEBUG_INFO_FIELDS.md §2): der Export enthält NUR Zahlen,
 * Status-Codes und Versions-Kurznamen — keine Audio-/Textinhalte, keine Pfade,
 * keine URLs, kein Ziel-App-Paketname. Der Exporttext wird niemals geloggt
 * (das betrifft die aufrufende Schicht, DebugInfoCollector).
 *
 * Format: Kopfzeile mit sichtbarer Zusicherung, danach GENAU 68 Zeilen
 * `schluessel: wert` in fester Reihenfolge (Determinismus), fehlende Werte
 * als `-`, Werte einzeilig sanitisiert (keine Umbrüche, keine Doppelpunkte).
 */

/** Nur primitive Felder/Strings — die Android-Schicht (Collector) füllt sie. */
data class DebugSnapshot(
    /** versionName aus PackageManager (BuildConfig existiert in diesem Modul nicht). */
    val versionName: String? = null,
    /** versionCode aus PackageManager. */
    val versionCode: Int? = null,
    /** debug/release via ApplicationInfo.FLAG_DEBUGGABLE. */
    val debugBuild: Boolean? = null,
    val apiLevel: Int? = null,
    /** ModelStatus-Name (READY/BROKEN/ABSENT/…). */
    val modelStatus: String? = null,
    /** ModelVariant-Name (ONNX_CPU/LITERT_CPU). */
    val modelVariant: String? = null,
    /** Versions-Kurzname (z. B. `v-1730…`) — NIE der volle Pfad (§2.1). */
    val modelVersionShort: String? = null,
    /** Summe der Modell-Dateien in Bytes (keine Namen, keine Pfade). */
    val modelBytes: Long? = null,
    /** Letzter Fehlercode (E5-Klasse, z. B. `audioInit=SecurityException`) — nie message. */
    val lastError: String? = null,
    /** RTF = processingMs/audioMs; null, wenn noch keine Audiodauer. */
    val rtf: Double? = null,
    /** Erst-Hypothese-Latenz in Millisekunden; 0/null = noch keine. */
    val firstHypothesisMs: Long? = null,
    val intraOpThreads: Int? = null,
    val interOpThreads: Int? = null,
    val permRecordAudio: Boolean? = null,
    val permPostNotifications: Boolean? = null,
    /** Spitzen-Pegel 0..100 (ein Maximalwert, keine Kurve — LevelPeak). */
    val peakPercent: Int? = null,

    /** Aufnahmeweg laut AudioRouter: „intern“ / Headset / Bluetooth — nur der Kurzname. */
    val audioRoute: String? = null,

    /** Verwendeter AudioRecord-Eingang (z. B. VOICE_RECOGNITION) — nur der Name, kein Pfad. */
    val audioSource: String? = null,

    /** Spitze in dBFS (<= 0). peak_pct ist bei sehr leiser Aufnahme nicht mehr auflösend. */
    val peakDbfs: Double? = null,

    // ---- F13b2: Beweisfelder für den Langtext-Abbruch (C1) — nur Zahlen/Zustände ----

    /** Zustand des Controllers als Kurzcode (IDLE/RECORDING/FINALIZING) — der
     *  Beweisfall: IDLE während der Nutzer noch drückt ⇒ C1 (still gescheitert). */
    val ctlState: String? = null,

    /** 0/1: failedThisSession (Fehler bewahrt bis zum nächsten Start). */
    val ctlFailed: Boolean? = null,

    /** Anzahl verworfener Audio-Blocks (nach stiller fail — F13b-Drop-Zähler). */
    val droppedBlocks: Int? = null,

    /** Anzahl erzeugter Hypothesen dieser Sitzung. */
    val hypCount: Int? = null,

    /** Abstand seit der letzten Hypothese in ms; -1 wenn nie eine kam (> 4000 ms ⇒ Rückstau). */
    val hypGapMs: Long? = null,

    /** Bisher aufgenommenes Audio in Sekunden, 1 Nachkommastelle. */
    val audioSeconds: Double? = null,

    /** Dauer des letzten Starts in ms (0 = nicht gemessen). */
    val lastStartMs: Long? = null,

    /** 0/1: Start läuft gerade (Watchdog-Fall: 1 mit ungewöhnlichem start_ms). */
    val starting: Boolean? = null,

    // ---- F17b/C2: Decode-Arbeitslast (C2) — nur Zahlen, keine Inhalte ----

    /** Größte Decode-Zeit je transcribe-Aufruf in ms (0 = noch nie dekodiert). */
    val decodeMsMax: Long? = null,

    /** p95 der Decode-Zeiten in ms (deterministische Stichprobe des Controllers). */
    val decodeMsP95: Long? = null,

    /** Pufferlänge in Sekunden beim letzten Dekodierlauf (wächst decode_ms mit?). */
    val bufferSeconds: Double? = null,

    // ---- F21: cost-aware Decode-Takt — nur Zahlen, keine Inhalte ----

    /** Aktuell geltendes Partial-Decode-Intervall in ms (0 = Pacing aus). */
    val decodePaceMs: Long? = null,

    /** Anzahl wegen Takt übersprungener Partial-Decodes (nie ein Fehlerzähler). */
    val decodeSkips: Int? = null,
    /** F28b1: empfangene Audio-Blöcke (n>0) der Session (Capture-Stall-Messung). */
    val capBlocks: Int? = null,
    /** F28b1: ms seit dem letzten Audio-Block bzw. Sessionstart (0 = keine Session). */
    val capStallMs: Long? = null,
    /** F26a: Grund des letzten Abbruchs/Outcomes (C2-Export: E3/E4/E5 trennen). */
    val abortReason: String? = null,
    /** F26b (C2-Export): Bufferstand (s) der letzten akzeptierten Hypothese. */
    val hypLastBufferSeconds: Double? = null,
    /** F28b2: letztes Session-Ende-Ereignis (kodiert + ms seit Sessionstart); "" = keines. */
    val micRel: String? = null,

    // AP3 (PLAN_C2_FABLE_R2 §4, Export v2 Teil 1) — Session-Forensik:
    /** Vereinheitlichte Stopp-Ursache (stopCauseOf: holdup|up|toggle|autostop|…). */
    val stopCause: String? = null,
    /** Start-Art (hold|toggle). */
    val startSrc: String? = null,
    /** F43-Gate im Stop-Moment: 1=offen, 0=zu (gehalten), -1=kein Stopp. */
    val gateAtStop: Int? = null,
    /** Besitzverlust-Moment in Session-Audio-Sekunden; -1 = nie. */
    val ownerLostAtS: Double? = null,
    /** Anzahl ausgeführter Segment-Mini-Finale dieser Session. */
    val segmentFinalizations: Int? = null,
    /** Längste Laut-Lücke der Session in ms (Block-RMS). */
    val maxPauseMs: Long? = null,
    /** onStartInput(restarting=true)-Zähler. */
    val restartInputs: Int? = null,
    /** Unerwartete Selection-Callbacks (Besitzverlust-Familie). */
    val selUnexpected: Int? = null,

    /** AP7-W1 (Fable §0.1.6): abgewiesene Fremd-Resets des Halte-Gates. */
    val gateForeignResets: Int? = null,

    /** AP7-E1 (Fable §0.2.5): Re-Baselines nach restartInput (🐞 rebases). */
    val rebases: Int? = null,
    /** F28b2: zuletzt angezeigter Statuszustand (ready|dictat|finalizing|error). */
    val statusShown: String? = null,
    /** F52: Code/Ressourcen-Name des zuletzt gezeigten Statuszeilen-Texts
     *  (starting|recording|finalizing|idle|hint|diag|error:<res>|raw) — nie
     *  der Text selbst (Regel 9). */
    val statusRes: String? = null,
    /** F31: Sekunden mit lautem Signal (Block-RMS ≥ Schwelle) der Session (Regel 9: Zahl). */
    val loudS: Double? = null,

    // ---- F42 (F41-Fehlende-Messpunkte): Ausgabe-/Stille-/FGS-Forensik — nur Zahlen/Codes ----

    /** F42 (🐞: had_output): 0/1 — mindestens ein Hypothesen-Commit dieser Sitzung
     *  (löst die „13-Zeichen“-Ähnlichkeit auf: kam ÜBERHAUPT Text?). */
    val hadOutput: Boolean? = null,

    /** F42 (🐞: output_chars): Brutto-Zeichenzahl der Commits (nur die Zahl,
     *  NIE der Text — Regel 9). */
    val outputChars: Int? = null,

    /** F42 (🐞: first_loud_ms): ms nach Sessionstart beim everLoud-Latch; -1 = nie. */
    val firstLoudMs: Long? = null,

    /** F42 (🐞: first3s_loud_blocks): laute Blöcke in den ersten 3 s (Startfenster). */
    val first3sLoudBlocks: Int? = null,

    /** F42 (🐞: first3s_peak_dbfs): Peak-Block-RMS der ersten 3 s in dBFS;
     *  null = kein Block im Fenster. */
    val first3sPeakDbfs: Double? = null,

    /** F42 (🐞: fgs_stopped_by): Ursachencode des letzten Mikro-FGS-Stops (F44:
     *  „abort:<grund>“/„stop“/„fgs-timeout“…; nur Codes, nie Inhalte — Regel 9). */
    val fgsStoppedBy: String? = null,

    // ---- A4a (PLAN_V0133 §A4a): Decode-/Schreib-Diagnose (A2b) — nur Zahlen/Codes ----

    /** A4a (🐞: last_decode_ms): Dauer des letzten Decode-Laufs in ms (A2b-
     *  Diagnoseobjekt; null = noch kein Fenster). */
    val lastDecodeMs: Long? = null,

    /** A4a (🐞: hyp_len): Länge der letzten Hypothese in Zeichen — nie Inhalt. */
    val hypLen: Int? = null,

    /** A4a (🐞: current_len): eigene Standlänge nach dem letzten Fenster. */
    val currentLen: Int? = null,

    /** A4a (🐞: committed_len): committete Präfixlänge nach dem letzten Fenster. */
    val committedLen: Int? = null,

    /** A4a (🐞: write_reason): letzter Schreib-Code (same|w1|w3d|delta; "-" = keiner). */
    val writeReason: String? = null,

    /** A4a (🐞: write_same): SAME-Ergebnisse dieser Session (monoton). */
    val writeSame: Int? = null,

    /** A4a (🐞: write_w1): verweigerte Schreibabsichten. */
    val writeW1: Int? = null,

    /** A4a (🐞: write_w3d): blockierte Rollbacks (einmalig sichtbar). */
    val writeW3d: Int? = null,

    /** A4a (🐞: write_delta): ausgeführte Schreibschritte. */
    val writeDelta: Int? = null,

    /** A4a (🐞: last_write_audio_s): Session-Audio-Sekunden des letzten DELTA;
     *  -1 = noch kein DELTA (NIE '-' — der Wert existiert per Controller immer). */
    val lastWriteAudioS: Double? = null,

    // ---- W-Paket (vc34, Debugger-Empfehlung): Engine-Warming — nur Zahlen/Codes ----

    /** W1 (🐞: engine_load_ms): reine Dauer des letzten engine.load()-Laufs in ms
     *  (0 = noch nie geladen; nur Zahl, Regel 9). */
    val engineLoadMs: Long? = null,

    /** W1/W2 (🐞: engine_resident): 0/1 — Engine ist geladen und bleibt resident
     *  bis shutdown() (bewusst KEIN Idle-Unload; Wärme für die Folgesitzung). */
    val engineResident: Boolean? = null,

    /** W1 (🐞: warm_hit): 0/1 — der letzte Diktat-Start fand die Engine bereits
     *  resident vor (Show-Preload griff; start_ms ohne Ladezeit). */
    val warmHit: Boolean? = null,

    /** W1 (🐞: load_trigger): Auslöser des letzten erfolgreichen load()
     *  (mic | ime_show; "-" = noch nie geladen). */
    val loadTrigger: String? = null,

    /** W1 (🐞: engine_loads): Anzahl ausgeführter engine.load()-Läufe (monoton
     *  bis shutdown; nur Zahl, Regel 9). */
    val engineLoads: Int? = null,
)

object DebugInfoFormatter {

    /** Sichtbare Regel-9-Zusicherung in der Kopfzeile. */
    private const val HEADER = "# VK-DEBUG v1 keine inhalte"

    /** Feste Feld-Reihenfolge (F13b2: +8, F17b: +3, F21: +2, F26a/b: +2, F28b1: +2, F28b2: +2, F31: +1, F42: +6, F52: +1, AP3: +8, A4a: +10, W-vc34: +5). */
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
        // AP3 (Export v2 Teil 1):
        "stop_cause", "start_src", "gate_at_stop", "owner_lost_at_s",
        "segment_finalizations", "max_pause_ms", "restart_inputs", "sel_unexpected",
        "gate_foreign_resets", "rebases",
        // A4a (PLAN_V0133 §A4a): +10 Decode-/Schreib-Diagnose — ANGEHÄNGT, die
        // bestehenden 53 bleiben davor in Reihenfolge unverändert.
        "last_decode_ms", "hyp_len", "current_len", "committed_len", "write_reason",
        "write_same", "write_w1", "write_w3d", "write_delta", "last_write_audio_s",
        // W-Paket (vc34, Debugger-Empfehlung): +5 Engine-Warming — ANGEHÄNGT,
        // die Bestandsfelder bleiben davor in Reihenfolge unverändert.
        "engine_load_ms", "engine_resident", "warm_hit", "load_trigger", "engine_loads",
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
        // F13b2: Beweisfelder für den Langtext-Abbruch (C1) — Zahlen/Zustände only.
        lines += "ctl_state: " + value(s.ctlState)
        lines += "ctl_failed: " + value(s.ctlFailed?.let { if (it) "1" else "0" })
        lines += "dropped: " + value(s.droppedBlocks?.toString())
        lines += "hyp_count: " + value(s.hypCount?.toString())
        lines += "hyp_gap_ms: " + value(s.hypGapMs?.toString())
        lines += "audio_s: " + value(s.audioSeconds?.let { "%.1f".format(it) })
        lines += "start_ms: " + value(s.lastStartMs?.toString())
        lines += "starting: " + value(s.starting?.let { if (it) "1" else "0" })
        // F17b/C2: Decode-Arbeitslast — Zahlen only (Regel 9).
        lines += "decode_ms_max: " + value(s.decodeMsMax?.toString())
        lines += "decode_ms_p95: " + value(s.decodeMsP95?.toString())
        lines += "buffer_s: " + value(s.bufferSeconds?.let { "%.1f".format(it) })
        // F21: cost-aware Takt — Zahlen only (Regel 9).
        lines += "decode_pace_ms: " + value(s.decodePaceMs?.toString())
        lines += "decode_skips: " + value(s.decodeSkips?.toString())
        // F28b1: Capture-Stall-Messung — Zahlen only (Regel 9).
        lines += "cap_blocks: " + value(s.capBlocks?.toString())
        lines += "cap_stall_ms: " + value(s.capStallMs?.toString())
        // F26a: Abort-Grund (C2-Export) — kontrollierte Namen, keine Inhalte (Regel 9).
        lines += "abort_reason: " + value(s.abortReason)
        // F26b (C2-Export): Bufferstand der letzten Hypothese — 1 Dezimale wie buffer_s.
        lines += "hyp_last_buffer_s: " + value(s.hypLastBufferSeconds?.let { "%.1f".format(it) })
        // F28b2: Session-Ende-Diagnose — reine Codes/ms, keine Inhalte (Regel 9).
        lines += "mic_rel: " + value(s.micRel)
        lines += "status_shown: " + value(s.statusShown)
        lines += "status_res: " + value(s.statusRes)
        // F31: Stille-Erkennung — 1 Dezimale wie audio_s (Regel 9: nur Zahlen).
        lines += "loud_s: " + value(s.loudS?.let { "%.1f".format(it) })
        // F42 (F41-Fehlende-Messpunkte): Ausgabe-/Stille-/FGS-Forensik — nur Zahlen/Codes.
        lines += "had_output: " + value(s.hadOutput?.let { if (it) "1" else "0" })
        lines += "output_chars: " + value(s.outputChars?.toString())
        lines += "first_loud_ms: " + value(s.firstLoudMs?.toString())
        lines += "first3s_loud_blocks: " + value(s.first3sLoudBlocks?.toString())
        lines += "first3s_peak_dbfs: " + value(s.first3sPeakDbfs?.let { "%.1f".format(it) })
        lines += "fgs_stopped_by: " + value(s.fgsStoppedBy)
        // AP3 (Export v2 Teil 1): Session-Forensik — nur Codes/Zahlen (Regel 9).
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
        // AP7-W1: gate_foreign_resets ≥ 1 = ein Fremd-Reset traf auf und wurde
        // abgewiesen (positiver W1b-Beweis, Fable §0.1.6).
        lines += "gate_foreign_resets: " + value(s.gateForeignResets?.toString())
        // AP7-E1: rebases ≥ 1 = App-Restart wurde überlebt (Re-Baseline statt Abort).
        lines += "rebases: " + value(s.rebases?.toString())
        // A4a (PLAN_V0133 §A4a): Decode-/Schreib-Diagnose (A2b) — nur Zahlen/
        // geschlossene Codes, nie Inhalte (Regel 9); Fensterwerte fehlen ohne
        // Decode als '-', last_write_audio_s ist per Controller NIE null
        // (-1 = noch kein DELTA).
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
        // W-Paket (vc34, Debugger-Empfehlung): Engine-Warming — nur Zahlen /
        // geschlossene Codes (mic|ime_show|"-"), nie Inhalte (Regel 9).
        lines += "engine_load_ms: " + value(s.engineLoadMs?.toString())
        lines += "engine_resident: " + value(s.engineResident?.let { if (it) "1" else "0" })
        lines += "warm_hit: " + value(s.warmHit?.let { if (it) "1" else "0" })
        lines += "load_trigger: " + value(s.loadTrigger)
        lines += "engine_loads: " + value(s.engineLoads?.toString())
        check(lines.size == KEYS.size + 1) { "Feldzahl driftet: ${lines.size}" }
        return lines.joinToString("\n")
    }

    private fun yn(b: Boolean?) = when (b) {
        true -> "yes"
        false -> "no"
        null -> "-"
    }

    /**
     * Sanitizing (Regel 5 der Analyse): einzeilig, keine Umbrüche, keine
     * Doppelpunkte im Wert, mehrfache Leerzeichen kollabiert, leer/unbekannt
     * → `-`. Doppelpunkte werden zu `=` (Fehlercodes bleiben lesbar,
     * z. B. `audioInit=SecurityException`).
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
