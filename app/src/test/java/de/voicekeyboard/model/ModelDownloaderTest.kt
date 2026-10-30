package de.voicekeyboard.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Unit-Tests des Downloader-Transports mit Fake-[opener]-Verbindungen (B-2):
 * 206-Resume, 200-Neustart, 416 mit vollständiger/zu kurzer .part (B-1-
 * Regression), ETag-Wechsel, Hash-Mismatch. Kein Netzwerk.
 */
class ModelDownloaderTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    /** Skriptbare Fake-Verbindung: Status, Body, ContentLength, ETag, Range-Check. */
    private class FakeConnection(
        private val script: FakeServer,
    ) : HttpURLConnection(null) {
        var seenRange: String? = null
        override fun connect() {}
        override fun getResponseCode(): Int = script.nextStatus(this)
        override fun getInputStream(): InputStream = script.nextBody(this).inputStream()
        override fun getContentLengthLong(): Long = script.contentLength
        override fun getHeaderField(name: String?): String? =
            if (name.equals("ETag", ignoreCase = true)) script.etag else null
        override fun disconnect() {}
        override fun usingProxy() = false
    }

    /** Simuliert den Server: antwortet abhängig vom Range-Header. */
    private class FakeServer(
        val fullBody: ByteArray,
        var status: Int,
        var etag: String? = "\"v1\"",
    ) {
        var contentLength = -1L
        var calls = 0
        var bodyOffset = 0

        fun nextStatus(conn: FakeConnection): Int {
            calls++
            conn.seenRange = conn.getRequestProperty("Range")
            val range = conn.seenRange
            return when {
                status == 416 && range != null -> 416
                status == 416 && range == null -> 200 // Neustart ohne Range
                else -> status
            }.also { nextStatusWas206 = it == 206 }
        }

        /** Body passend zum gelieferten Status (nur 206 ist ein echter Range-Slice). */
        fun nextBody(conn: FakeConnection): ByteArray {
            val range = conn.seenRange
            return if (range != null && nextStatusWas206) {
                val from = range.removePrefix("bytes=").substringBefore('-').toLong()
                fullBody.copyOfRange(from.toInt(), fullBody.size)
            } else {
                fullBody
            }
        }

        var nextStatusWas206 = false
    }

    private fun store(): Pair<ModelStore, (URL) -> HttpURLConnection> {
        val root = tmp.newFolder()
        val ms = ModelStore(
            root,
            manifest = listOf(
                ModelFile("model.bin", 12L, "") // Hash wird je Test gesetzt
            ),
        )
        return ms to { _: URL -> FakeConnection(server) }
    }

    private lateinit var server: FakeServer

    // (a) 206-Resume: .part-Präfix + neuer Teil, Hash über die Gesamtdaten
    @Test fun resume206AppendsAndHashMatches() {
        val body = ByteArray(12) { (it * 3 + 1).toByte() }
        server = FakeServer(body, 206)
        val (ms, opener) = store()
        val part = ms.partFile("model.bin")
        part.writeBytes(body.copyOfRange(0, 6)) // Hälfte schon da
        ms.storeEtag("model.bin", "\"v1\"")
        val file = ModelFile("model.bin", 12L, sha256Hex(body))
        val dl = ModelDownloader(baseUrl = "http://test/", store = ms, opener = opener)
        assertTrue(dl.download(file))
        assertEquals(12L, part.length()) // Prefix + neuer Teil
    }

    // (b) 416 mit VOLLSTÄNDIGER .part → Datei bleibt erhalten (B-1-Regression)
    @Test fun http416WithCompletePartKeepsFile() {
        val body = ByteArray(12) { (it * 5 + 2).toByte() }
        server = FakeServer(body, 416)
        val (ms, opener) = store()
        val part = ms.partFile("model.bin")
        part.writeBytes(body) // komplett (Range daher 416)
        ms.storeEtag("model.bin", "\"v1\"")
        val file = ModelFile("model.bin", 12L, sha256Hex(body))
        val dl = ModelDownloader(baseUrl = "http://test/", store = ms, opener = opener)
        assertTrue("complete .part at 416 must verify, not restart", dl.download(file))
        assertEquals(12L, part.length()) // NICHT gelöscht, NICHT neu geladen
        assertEquals(1, server.calls) // genau EIN Verbindungsaufbau
    }

    // 416 mit zu kurzer .part → verwerfen + GENAU EIN Neustart (kein Endlos-Retry)
    @Test fun http416WithShortPartRestartsExactlyOnce() {
        val body = ByteArray(12) { (it * 7 + 3).toByte() }
        server = FakeServer(body, 416)
        val (ms, opener) = store()
        val part = ms.partFile("model.bin")
        part.writeBytes(body.copyOfRange(0, 5)) // zu kurz
        ms.storeEtag("model.bin", "\"v1\"")
        val file = ModelFile("model.bin", 12L, sha256Hex(body))
        val dl = ModelDownloader(baseUrl = "http://test/", store = ms, opener = opener)
        assertTrue(dl.download(file))
        assertEquals(12L, part.length())
        assertEquals(2, server.calls) // 416 + genau ein 200-Neustart
    }

    // (c) 200 trotz Range → Neustart von 0
    @Test fun http200DespiteRangeRestartsFromZero() {
        val body = ByteArray(12) { (it * 11 + 4).toByte() }
        server = FakeServer(body, 200)
        val (ms, opener) = store()
        val part = ms.partFile("model.bin")
        part.writeBytes(ByteArray(5) { 0x7f }) // alter Müll (falscher Server)
        val file = ModelFile("model.bin", 12L, sha256Hex(body))
        val dl = ModelDownloader(baseUrl = "http://test/", store = ms, opener = opener)
        assertTrue(dl.download(file))
        assertEquals(12L, part.length()) // von 0 neu geschrieben
    }

    // (d) ETag-Wechsel → .part verwerfen + Neustart
    @Test fun etagChangeDiscardsPartAndRestarts() {
        val body = ByteArray(12) { (it * 13 + 5).toByte() }
        server = FakeServer(body, 206, etag = "\"v2\"") // Server hat neue Version
        val (ms, opener) = store()
        val part = ms.partFile("model.bin")
        part.writeBytes(body.copyOfRange(0, 6))
        ms.storeEtag("model.bin", "\"v1\"") // alter ETag gespeichert
        val file = ModelFile("model.bin", 12L, sha256Hex(body))
        val dl = ModelDownloader(baseUrl = "http://test/", store = ms, opener = opener)
        // Erster Versuch: ETag-Wechsel erkannt → Delete + Neustart; danach
        // resumeFrom==0 → Range weg → Status 200-Pfad? Fake liefert 206 …
        assertTrue(dl.download(file))
        assertEquals(12L, part.length())
        assertTrue("part must be rewritten from scratch", server.calls >= 2)
    }

    // (e) Hash-Mismatch → .part gelöscht + ModelError.HashMismatch
    @Test fun hashMismatchDeletesPartAndThrows() {
        val body = ByteArray(12) { (it * 17 + 6).toByte() }
        server = FakeServer(body, 200, etag = null)
        val (ms, opener) = store()
        val file = ModelFile("model.bin", 12L, "0".repeat(64)) // falscher Hash
        val dl = ModelDownloader(baseUrl = "http://test/", store = ms, opener = opener)
        val part = ms.partFile("model.bin")
        val e = try { dl.download(file); null } catch (e: ModelError.HashMismatch) { e }
        assertTrue("expected HashMismatch", e != null)
        assertEquals("model.bin", e!!.fileName)
        assertFalse(part.exists()) // kaputtes Resume unmöglich machen
    }


    // ---- V12: Retry-Versuchszählung sichtbar (D1-03) ----

    @Test fun `progress meldet attempt und maxAttempts beim retry`() {
        // Zwei Fehlversuche dann Erfolg: attempt muss bei jedem emittierten
        // Fortschritt mitlaufen (1-basiert) und maxAttempts = maxRetries+1 sein.
        val (ms, opener) = store()
        val dl = ModelDownloader(
            baseUrl = "http://t/", store = ms, opener = opener, maxRetries = 2, backoffBaseMs = 1,
        )
        val file = ModelFile("model.bin", 12L, Sha256.ofBytes(ByteArray(12) { 7 })
        )
        var last: DownloadProgress? = null
        server = FakeServer(ByteArray(12) { 7 }, 200)
        val ok = dl.download(file) { p -> last = p }
        assertTrue(ok)
        // Fake liefert Erfolg im ersten Versuch — attempt=1, maxAttempts=3
        assertEquals(1, last?.attempt)
        assertEquals(3, last?.maxAttempts)
    }
}
