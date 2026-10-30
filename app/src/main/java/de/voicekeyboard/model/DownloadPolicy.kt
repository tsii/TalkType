package de.voicekeyboard.model

/**
 * Frameworkfreie Start-/Anzeige-Entscheidungen für den Modelldownload
 * (docs/SETUP_UI_DECISIONS.md E2/E3, L-Regeln). Reine Logik, JVM-testbar.
 *
 * Regeln:
 * (a) Start nur bei eingeschaltetem Netzwerk-Schalter (E2: App-Schalter,
 *     Default an bis Setup fertig, danach aus).
 * (b) Freier Speicher >= [MIN_FREE_BYTES] (E3: 2,5 GB — 671 MB Modell
 *     + Reserve; ersetzt die 3-GB-Plausibilität aus DESIGN durch den
 *     in SETUP_UI_DECISIONS.md E3 verbindlichen Wert).
 * (c) Bereits READY → kein Start (L6).
 * (d) Bei READY → Netz-Schalter automatisch aus (Rückgabe [turnNetworkOff]).
 * (e) Fehlerklassen → kurze Nutzer-Textschlüssel (netz/hash/platz/abgebrochen).
 */
object DownloadPolicy {

    /** E3: vor dem ersten Byte mindestens 2,5 GB frei (Modell + Reserve). */
    const val MIN_FREE_BYTES: Long = 2_500_000_000L

    /** Startentscheidung für die Setup-UI. */
    sealed class Decision {
        data object Start : Decision()
        data object AlreadyReady : Decision()          // (c) kein Start
        data object NetworkDisabled : Decision()       // (a) Schalter aus
        data class InsufficientSpace(val freeBytes: Long) : Decision() // (b)

        /** (d) wird über [shouldTurnNetworkOff] abgefragt, nicht über diese Klasse. */
    }

    /** Vollständige Startentscheidung gegen Store-/Schalter-/Platz-Zustand. */
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

    /** (d) Nach erfolgreichem Setup (READY) soll die UI den Netz-Schalter ausstellen. */
    fun shouldTurnNetworkOff(status: ModelStatus): Boolean = status == ModelStatus.READY

    /** (e) Fehlerart → kurzer Nutzer-Textschlüssel (nie HTTP-Details, nie URLs).
     *  D1-Echttaxonomie:
     *  - "netz" nur fuer Verbindungsprobleme (Timeout/Connect/DNS/Abbruch),
     *  - "server" fuer endgueltige 4xx (403/404/410 … — Server verweigert, Verbindung ok),
     *  - "warte" fuer 408/429 (Server ueberlastet — spaeter erneut),
     *  - "hash"/"platz"/"abgebrochen" wie bisher.
     *  5xx bleibt "netz" (transient, Retry laeuft bereits im Downloader). */
    fun userMessageKey(error: ModelError): String = when (error) {
        is ModelError.Cancelled -> "abgebrochen"
        is ModelError.HashMismatch -> "hash"
        is ModelError.Storage -> "platz"
        is ModelError.Http -> when (error.statusCode) {
            416 -> "fortsetzung" // D1-02: Fortsetzungsproblem, keine Server-Ablehnung
            408, 429 -> "warte"
            in 400..499 -> "server"
            else -> "netz" // 5xx: transient — Retry sie ModelDownloader
        }
        is ModelError.Network -> "netz"
    }
}
