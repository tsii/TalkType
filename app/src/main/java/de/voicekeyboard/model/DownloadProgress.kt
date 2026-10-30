package de.voicekeyboard.model

/**
 * Fortschrittsmeldung eines laufenden Downloads (ein Wert pro Datei-Sekunde bzw.
 * je Read-Batch, gedrosselt vom Downloader). Enthält NUR Bytes/Raten — nie URLs,
 * nie Inhalte.
 */
data class DownloadProgress(
    val fileName: String,
    val bytesDone: Long,
    val bytesTotal: Long,
    val bytesPerSecond: Long,
    /** V12 (D1-03): 1-basierter Versuchszähler (0 = nicht emittiert/kompatibel). */
    val attempt: Int = 0,
    /** V12: Gesamtzahl Versuche = maxRetries+1 (0 = nicht emittiert/kompatibel). */
    val maxAttempts: Int = 0,
) {
    /** 0..1, -1 wenn der Server keine Gesamtgröße geliefert hat. */
    val fraction: Float
        get() = if (bytesTotal <= 0) -1f else (bytesDone.toDouble() / bytesTotal).toFloat().coerceIn(0f, 1f)
}

fun interface DownloadProgressListener {
    fun onProgress(progress: DownloadProgress)
}
