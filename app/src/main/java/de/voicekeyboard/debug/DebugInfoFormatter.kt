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
 * Format: Kopfzeile mit sichtbarer Zusicherung, danach GENAU 13 Zeilen
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
)

object DebugInfoFormatter {

    /** Sichtbare Regel-9-Zusicherung in der Kopfzeile. */
    private const val HEADER = "# VK-DEBUG v1 keine inhalte"

    /** Feste Feld-Reihenfolge = Kap. §1-Katalog (gekürzt auf 13 Zeilen). */
    private val KEYS = listOf(
        "app_version", "build", "api_level", "model_status", "model_variant",
        "model_version", "model_bytes", "last_error", "rtf", "first_hyp_ms",
        "threads", "permissions", "peak_pct", "route", "src", "peak_dbfs",
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
