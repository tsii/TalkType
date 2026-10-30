package de.voicekeyboard.model

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * JDK-Downloader für Modell-Dateien (keine neue Dependency).
 *
 * - `HttpURLConnection` mit Connect/Read-Timeouts.
 * - Resume: vorhandene `.part` wird mit `Range: bytes=<len>-` fortgesetzt;
 *   Server-ETag wird je Datei gespeichert — weicht er ab, startet der Download
 *   von vorn (kein Misch-Datenbestand). Antwort 200 statt 206 auf Range ⇒ Neustart.
 * - SHA-256 wird WÄHREND des Streams berechnet (Prefix der `.part` wird vorab in
 *   den Digest gelesen), am Ende gegen [ModelFile.sha256] geprüft (Ende-zu-Ende).
 * - Fortschritt über [DownloadProgressListener] (Bytes, Gesamt, Rate) — nur Zahlen.
 * - Abbruch über `volatile` Flag ([cancel]): `.part` bleibt für Resume erhalten.
 * - Retry: Netzwerkfehler und HTTP 5xx mit begrenztem Backoff (max. [maxRetries]).
 * - Platzprüfung vor jedem Schreiben: `usableSpace` der Part-Partition.
 * - Sauberes Schließen aller Streams/Verbindungen in finally.
 * - Keine Logs von URLs/Inhalten (Aufrufer sieht nur [ModelError] mit Status/Detail).
 *
 * @param opener erlaubt Tests, den Transport zu fälschen (JDK-HttpServer oder
 *   In-Memory-Verbindung) — Produktionscode nutzt den Default.
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

    /** Abbruch-Flag: wird vom UI-Thread gesetzt, im Read-Loop geprüft. */
    @Volatile private var cancelled = false

    fun cancel() {
        cancelled = true
    }

    /**
     * Lädt [file] (Resume + Hash-Prüfung) in die Store-Part-Datei.
     * @return true, wenn die Datei vollständig und hash-korrekt ist (Move/Activate
     *   macht dann der [ModelStore]); wirft [ModelError] bei endgültigem Scheitern.
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
                throw e // Abbruch ist nie retry-bar
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
        throw lastError ?: ModelError.Network("unerwarteter Retry-Ende")
    }

    @Throws(ModelError::class)
    private fun downloadAttempt(
        file: ModelFile,
        listener: DownloadProgressListener?,
        restartedAfter416: Boolean = false,
        currentAttempt: Int = 1,
    ): Boolean {
        val part = store.partFile(file.storeName)
        val parent = part.parentFile ?: throw ModelError.Storage("kein Elternverzeichnis für ${part.name}")
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
                setRequestProperty("Accept-Encoding", "identity") // keine Kompression: Range+Hash sonst kaputt
            }
            connection.connect()
            val status = connection.responseCode
            if (status !in 200..299 && status != 416) {
                throw ModelError.Http(status, "für ${file.storeName}")
            }

            // ETag-Guard: Wechsel ⇒ alter .part unbrauchbar, von vorn beginnen.
            // Bei 416 KEIN ETag-Neustart: die .part ist (vermutlich) komplett —
            // ein ETag-Wechsel fängt in der Hash-Prüfung (Mismatch) sicher auf.
            val etag = connection.getHeaderField("ETag")
            val storedEtag = store.etagFor(file.storeName)
            if (status != 416 && resumeFrom > 0 && storedEtag != null && etag != null && storedEtag != etag) {
                part.delete()
                store.storeEtag(file.storeName, null)
                return downloadAttempt(file, listener) // ohne Resume, frischer ETag wird gespeichert
            }
            if (etag != null) store.storeEtag(file.storeName, etag)

            if (status == 416) {
                // Range nicht erfüllbar (B-1): .part NICHT löschen.
                if (part.length() >= file.sizeBytes) {
                    // vollständig → nur noch Hash-Verifikation, kein Neustart (keine Endlosschleife)
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
                // kleiner als erwartete Größe → verworfen und GENAU EIN Neustart ohne Range
                part.delete()
                store.storeEtag(file.storeName, null)
                if (restartedAfter416) {
                    throw ModelError.Http(416, "Range nicht erfüllbar auch ohne Resume (${file.storeName})")
                }
                return downloadAttempt(file, listener, restartedAfter416 = true)
            }

            val rangeAccepted = status == 206
            val effectiveStart = if (rangeAccepted) resumeFrom else 0L
            if (!rangeAccepted) {
                // D1(a): Server antwortet 200 statt 206 auf Range (CDN ignoriert
                // Range nach Redirect) — der volle Body darf NIEMALS an die
                // vorhandene .part angehaengt werden. .part auf 0 kuerzen,
                // komplett neu beginnen.
                java.io.RandomAccessFile(part, "rw").use { it.setLength(0) }
            }

            val contentLength = connection.contentLengthLong // -1 unbekannt
            val total = if (contentLength >= 0) effectiveStart + contentLength else file.sizeBytes
            checkDiskSpace(parent, total, effectiveStart)

            // Digest über die GESAMTE Datei: .part-Prefix vorab einlesen
            val digest = MessageDigest.getInstance("SHA-256")
            if (effectiveStart > 0) readPrefixIntoDigest(part, effectiveStart, digest)

            val input: InputStream? = connection.inputStream

            var written = effectiveStart
            val startedAtMs = System.currentTimeMillis()
            var lastEmitMs = 0L
            if (input != null) {
                val out = java.io.RandomAccessFile(part, "rw").use { raf ->
                    raf.setLength(effectiveStart) //truncate Neustart-Reste über die Marque
                    raf.seek(effectiveStart)
                    val buf = ByteArray(bufferBytes)
                    while (true) {
                        if (cancelled) throw ModelError.Cancelled(file.storeName)
                        val n = input.read(buf)
                        if (n < 0) break
                        raf.write(buf, 0, n)
                        digest.update(buf, 0, n)
                        written += n
                        // D1(b): .part waechst ueber die erwartete Groesse → besch
                        // digte Fortsetzung (Range/Hash-Basis kaputt). .part loeschen,
                        // als Netzwerkfehler klassifizieren (Retry-Logik greift).
                        if (written > file.sizeBytes) {
                            raf.setLength(0)
                            throw ModelError.Network("beschädigte Fortsetzung (${file.storeName})")
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

            // Hash-Prüfung (Ende-zu-Ende, auch beim puren 416-Fortsetzungsfall)
            if (written != file.sizeBytes) {
                throw ModelError.Network("unvollständig: ${written}/${file.sizeBytes} Bytes (${file.storeName})")
            }
            val actual = Sha256.hex(digest.digest())
            if (!Sha256.constantTimeEquals(actual, file.sha256)) {
                // Mismatch ist endgültig: .part verwerfen, damit kein kaputtes Resume folgt
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
            throw ModelError.Storage("zu wenig Platz: $remaining B nötig, $usable B frei")
        }
    }

    private fun readPrefixIntoDigest(part: File, length: Long, digest: MessageDigest) {
        java.io.FileInputStream(part).use { input ->
            val buf = ByteArray(64 * 1024)
            var remaining = length
            while (remaining > 0) {
                val n = input.read(buf, 0, minOf(buf.size.toLong(), remaining).toInt())
                if (n < 0) throw ModelError.Storage("part-Datei kürzer als erwartet: ${part.name}")
                digest.update(buf, 0, n)
                remaining -= n
            }
        }
    }
}
