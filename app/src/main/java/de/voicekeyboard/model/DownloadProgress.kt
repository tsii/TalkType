package de.voicekeyboard.model

/**
 * Progress report of a running download (one value per file-second respectively
 * per read batch, throttled by the downloader). Contains ONLY bytes/rates — never URLs,
 * never contents.
 */
data class DownloadProgress(
    val fileName: String,
    val bytesDone: Long,
    val bytesTotal: Long,
    val bytesPerSecond: Long,
    /** V12 (D1-03): 1-based attempt counter (0 = not emitted/compatible). */
    val attempt: Int = 0,
    /** V12: total number of attempts = maxRetries+1 (0 = not emitted/compatible). */
    val maxAttempts: Int = 0,
) {
    /** 0..1, -1 if the server did not send a total size. */
    val fraction: Float
        get() = if (bytesTotal <= 0) -1f else (bytesDone.toDouble() / bytesTotal).toFloat().coerceIn(0f, 1f)
}

fun interface DownloadProgressListener {
    fun onProgress(progress: DownloadProgress)
}
