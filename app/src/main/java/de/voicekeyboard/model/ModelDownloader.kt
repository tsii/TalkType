package de.voicekeyboard.model

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * JDK downloader for model files (no new dependency).
 *
 * - `HttpURLConnection` with connect/read timeouts.
 * - Resume: an existing `.part` is continued with `Range: bytes=<len>-`;
 *   the server ETag is stored per file — if it changes, the download starts
 *   from scratch (no mixed data stock). Response 200 instead of 206 on Range ⇒ restart.
 * - SHA-256 is computed WHILE streaming (the `.part` prefix is read into the
 *   digest beforehand), checked against [ModelFile.sha256] at the end (end to end).
 * - Progress via [DownloadProgressListener] (bytes, total, rate) — numbers only.
 * - Abort via a `volatile` flag ([cancel]): `.part` stays for resuming.
 * - Retry: network errors and HTTP 5xx with bounded backoff (max. [maxRetries]).
 * - Space check before every write: `usableSpace` of the part partition.
 * - Clean closing of all streams/connections in finally.
 * - No logs of URLs/contents (the caller only sees [ModelError] with status/detail).
 *
 * @param opener lets tests fake the transport (JDK HttpServer or
 *   in-memory connection) — production code uses the default.
 */
class ModelDownloader(
    private val baseUrl: String = ModelManifest.BASE_URL,
    private val store: ModelStore,
    private val connectTimeoutMs: Int = 15_000,
    private val readTimeoutMs: Int = 30_000,
    private val maxRetries: Int = 4,
    private val backoffBaseMs: Long = 1_000,
    private val bufferBytes: Int = 32 * 1024,
    private val opener: (URL) -> HttpURLConnection = { url ->
        url.openConnection() as HttpURLConnection
    },
) {

    /** Abort flag: set by the UI thread, checked in the read loop. */
    @Volatile private var cancelled = false

    fun cancel() {
        cancelled = true
    }

    /**
     * Downloads [file] (resume + hash check) into the store part file.
     * @return true if the file is complete and hash-correct (the [ModelStore]
     *   then does the move/activate); throws [ModelError] on final failure.
     */
    @Throws(ModelError::class)
    fun download(
        file: ModelFile,
        listener: DownloadProgressListener? = null,
    ): Boolean {
        cancelled = false
        var attempt = 0
        var lastError: ModelError? = null
        while (attempt <= maxRetries) {
            try {
                return downloadAttempt(file, listener, currentAttempt = attempt + 1)
            } catch (e: ModelError.Cancelled) {
                throw e // an abort is never retryable
            } catch (e: ModelError) {
                val retryable = e is ModelError.Network || (e is ModelError.Http && e.statusCode >= 500)
                if (!retryable || attempt == maxRetries) throw e
                lastError = e
                attempt++
                try {
                    Thread.sleep(backoffBaseMs shl (attempt - 1).coerceAtMost(4))
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw ModelError.Cancelled(file.storeName)
                }
            }
        }
        throw lastError ?: ModelError.Network("unexpected end of retries")
    }

    @Throws(ModelError::class)
    private fun downloadAttempt(
        file: ModelFile,
        listener: DownloadProgressListener?,
        restartedAfter416: Boolean = false,
        currentAttempt: Int = 1,
    ): Boolean {
        val part = store.partFile(file.storeName)
        val parent = part.parentFile ?: throw ModelError.Storage("no parent directory for ${part.name}")
        parent.mkdirs()

        var resumeFrom = 0L
        if (part.isFile && part.length() > 0) {
            resumeFrom = part.length()
        }

        var connection: HttpURLConnection? = null
        try {
            val url = URL(baseUrl + file.urlPath)
            connection = opener(url).apply {
                connectTimeout = connectTimeoutMs
                readTimeout = readTimeoutMs
                if (resumeFrom > 0) setRequestProperty("Range", "bytes=$resumeFrom-")
                setRequestProperty("Accept-Encoding", "identity") // no compression: range+hash would break
            }
            connection.connect()
            val status = connection.responseCode
            if (status !in 200..299 && status != 416) {
                throw ModelError.Http(status, "for ${file.storeName}")
            }

            // ETag guard: change ⇒ old .part unusable, start from scratch.
            // On 416 NO ETag restart: the .part is (presumably) complete —
            // an ETag change is caught safely by the hash check (mismatch).
            val etag = connection.getHeaderField("ETag")
            val storedEtag = store.etagFor(file.storeName)
            if (status != 416 && resumeFrom > 0 && storedEtag != null && etag != null && storedEtag != etag) {
                part.delete()
                store.storeEtag(file.storeName, null)
                return downloadAttempt(file, listener) // without resume, fresh ETag is stored
            }
            if (etag != null) store.storeEtag(file.storeName, etag)

            if (status == 416) {
                // Range not satisfiable (B-1): do NOT delete the .part.
                if (part.length() >= file.sizeBytes) {
                    // complete → hash verification only, no restart (no endless loop)
                    val digest = MessageDigest.getInstance("SHA-256")
                    readPrefixIntoDigest(part, part.length(), digest)
                    val actual = Sha256.hex(digest.digest())
                    if (!Sha256.constantTimeEquals(actual, file.sha256)) {
                        part.delete()
                        store.storeEtag(file.storeName, null)
                        throw ModelError.HashMismatch(file.storeName, file.sha256, actual)
                    }
                    return true
                }
                // smaller than the expected size → discarded and EXACTLY ONE restart without range
                part.delete()
                store.storeEtag(file.storeName, null)
                if (restartedAfter416) {
                    throw ModelError.Http(416, "range not satisfiable even without resume (${file.storeName})")
                }
                return downloadAttempt(file, listener, restartedAfter416 = true)
            }

            val rangeAccepted = status == 206
            val effectiveStart = if (rangeAccepted) resumeFrom else 0L
            if (!rangeAccepted) {
                // D1(a): server answers 200 instead of 206 on Range (CDN ignores
                // Range after a redirect) — the full body must NEVER be
                // appended to the existing .part. Truncate the .part to 0,
                // start completely anew.
                java.io.RandomAccessFile(part, "rw").use { it.setLength(0) }
            }

            val contentLength = connection.contentLengthLong // -1 unknown
            val total = if (contentLength >= 0) effectiveStart + contentLength else file.sizeBytes
            checkDiskSpace(parent, total, effectiveStart)

            // Digest over the WHOLE file: read the .part prefix beforehand
            val digest = MessageDigest.getInstance("SHA-256")
            if (effectiveStart > 0) readPrefixIntoDigest(part, effectiveStart, digest)

            val input: InputStream? = connection.inputStream

            var written = effectiveStart
            val startedAtMs = System.currentTimeMillis()
            var lastEmitMs = 0L
            if (input != null) {
                val out = java.io.RandomAccessFile(part, "rw").use { raf ->
                    raf.setLength(effectiveStart) // truncate restart remnants beyond the mark
                    raf.seek(effectiveStart)
                    val buf = ByteArray(bufferBytes)
                    while (true) {
                        if (cancelled) throw ModelError.Cancelled(file.storeName)
                        val n = input.read(buf)
                        if (n < 0) break
                        raf.write(buf, 0, n)
                        digest.update(buf, 0, n)
                        written += n
                        // D1(b): .part grows beyond the expected size → corrupted
                        // continuation (range/hash basis broken). Delete the .part,
                        // classify as a network error (retry logic applies).
                        if (written > file.sizeBytes) {
                            raf.setLength(0)
                            throw ModelError.Network("corrupted resume (${file.storeName})")
                        }
                        val now = System.currentTimeMillis()
                        if (listener != null && (now - lastEmitMs >= 250 || written == total)) {
                            lastEmitMs = now
                            val elapsed = ((now - startedAtMs).coerceAtLeast(1)) / 1000.0
                            val rate = ((written - effectiveStart) / elapsed).toLong()
                            listener.onProgress(
                                DownloadProgress(
                                    file.storeName, written, total, rate,
                                    attempt = currentAttempt,
                                    maxAttempts = maxRetries + 1,
                                ),
                            )
                        }
                    }
                    written
                }
            } else {
                written = part.length()
            }

            // Hash check (end to end, also in the pure 416 continuation case)
            if (written != file.sizeBytes) {
                throw ModelError.Network("incomplete: ${written}/${file.sizeBytes} bytes (${file.storeName})")
            }
            val actual = Sha256.hex(digest.digest())
            if (!Sha256.constantTimeEquals(actual, file.sha256)) {
                // A mismatch is final: discard the .part so no broken resume follows
                part.delete()
                store.storeEtag(file.storeName, null)
                throw ModelError.HashMismatch(file.storeName, file.sha256, actual)
            }
            return true
        } catch (e: ModelError) {
            throw e
        } catch (e: IOException) {
            throw ModelError.Network(e.message ?: e.javaClass.simpleName, e)
        } catch (e: Exception) {
            throw ModelError.Storage(e.message ?: e.javaClass.simpleName, e)
        } finally {
            connection?.disconnect()
        }
    }

    private fun checkDiskSpace(parent: File, totalBytes: Long, alreadyThere: Long) {
        if (totalBytes <= alreadyThere) return
        val usable = parent.usableSpace
        val remaining = totalBytes - alreadyThere
        if (usable < remaining) {
            throw ModelError.Storage("not enough space: $remaining B needed, $usable B free")
        }
    }

    private fun readPrefixIntoDigest(part: File, length: Long, digest: MessageDigest) {
        java.io.FileInputStream(part).use { input ->
            val buf = ByteArray(64 * 1024)
            var remaining = length
            while (remaining > 0) {
                val n = input.read(buf, 0, minOf(buf.size.toLong(), remaining).toInt())
                if (n < 0) throw ModelError.Storage("part file shorter than expected: ${part.name}")
                digest.update(buf, 0, n)
                remaining -= n
            }
        }
    }
}
