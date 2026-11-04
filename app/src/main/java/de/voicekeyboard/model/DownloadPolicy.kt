package de.voicekeyboard.model

/**
 * Framework-free start/display decisions for the model download
 * (docs/SETUP_UI_DECISIONS.md E2/E3, L rules). Pure logic, JVM-testable.
 *
 * Rules:
 * (a) Start only with the network switch turned on (E2: app switch,
 *     default on until setup is complete, then off).
 * (b) Free space >= [MIN_FREE_BYTES] (E3: 2.5 GB — 671 MB model
 *     + reserve; replaces the 3-GB plausibility from DESIGN with the
 *     binding value from SETUP_UI_DECISIONS.md E3).
 * (c) Already READY → no start (L6).
 * (e) Error classes → short user text keys (netz/hash/platz/abgebrochen).
 *
 * T20b (PLAN_V0145): the E2 in-app network switch is GONE — the app never
 * gates or revokes the network itself. Callers pass networkEnabled = true;
 * a system-level INTERNET revoke surfaces as the "netz" error class (D1).
 */
object DownloadPolicy {

    /** E3: at least 2.5 GB free before the first byte (model + reserve). */
    const val MIN_FREE_BYTES: Long = 2_500_000_000L

    /** Start decision for the setup UI. */
    sealed class Decision {
        data object Start : Decision()
        data object AlreadyReady : Decision()          // (c) no start
        data object NetworkDisabled : Decision()       // (a) switch off
        data class InsufficientSpace(val freeBytes: Long) : Decision() // (b)
    }

    /** Complete start decision against the store/switch/space state. */
    fun decide(
        networkEnabled: Boolean,
        freeBytes: Long,
        status: ModelStatus,
    ): Decision = when {
        status == ModelStatus.READY -> Decision.AlreadyReady
        !networkEnabled -> Decision.NetworkDisabled
        freeBytes < MIN_FREE_BYTES -> Decision.InsufficientSpace(freeBytes)
        else -> Decision.Start
    }

    /** (e) Error kind → short user text key (never HTTP details, never URLs).
     *  D1 real taxonomy:
     *  - "netz" only for connection problems (timeout/connect/DNS/abort),
     *  - "server" for final 4xx (403/404/410 … — server refuses, connection ok),
     *  - "warte" for 408/429 (server overloaded — retry later),
     *  - "hash"/"platz"/"abgebrochen" as before.
     *  5xx stays "netz" (transient, retry already runs in the downloader). */
    fun userMessageKey(error: ModelError): String = when (error) {
        is ModelError.Cancelled -> "abgebrochen"
        is ModelError.HashMismatch -> "hash"
        is ModelError.Storage -> "platz"
        is ModelError.Http -> when (error.statusCode) {
            416 -> "fortsetzung" // D1-02: resume problem, not a server rejection
            408, 429 -> "warte"
            in 400..499 -> "server"
            else -> "netz" // 5xx: transient — see ModelDownloader for retries
        }
        is ModelError.Network -> "netz"
    }
}
