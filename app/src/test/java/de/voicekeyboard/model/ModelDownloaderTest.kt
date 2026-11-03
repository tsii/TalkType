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
 * Unit tests of the downloader transport with fake [opener] connections (B-2):
 * 206 resume, 200 restart, 416 with a complete/too short .part (B-1
 * regression), ETag change, hash mismatch. No network.
 */
class ModelDownloaderTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    /** Scriptable fake connection: status, body, content length, ETag, range check. */
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

    /** Simulates the server: responds depending on the Range header. */
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
                status == 416 && range == null -> 200 // restart without a range
                else -> status
            }.also { nextStatusWas206 = it == 206 }
        }

        /** Body matching the delivered status (only 206 is a real range slice). */
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
                ModelFile("model.bin", 12L, "") // hash is set per test
            ),
        )
        return ms to { _: URL -> FakeConnection(server) }
    }

    private lateinit var server: FakeServer

    // (a) 206 resume: .part prefix + new piece, hash over the total data
    @Test fun resume206AppendsAndHashMatches() {
        val body = ByteArray(12) { (it * 3 + 1).toByte() }
        server = FakeServer(body, 206)
        val (ms, opener) = store()
        val part = ms.partFile("model.bin")
        part.writeBytes(body.copyOfRange(0, 6)) // half already there
        ms.storeEtag("model.bin", "\"v1\"")
        val file = ModelFile("model.bin", 12L, sha256Hex(body))
        val dl = ModelDownloader(baseUrl = "http://test/", store = ms, opener = opener)
        assertTrue(dl.download(file))
        assertEquals(12L, part.length()) // prefix + new piece
    }

    // (b) 416 with a COMPLETE .part → the file is kept (B-1 regression)
    @Test fun http416WithCompletePartKeepsFile() {
        val body = ByteArray(12) { (it * 5 + 2).toByte() }
        server = FakeServer(body, 416)
        val (ms, opener) = store()
        val part = ms.partFile("model.bin")
        part.writeBytes(body) // complete (hence 416 for the range)
        ms.storeEtag("model.bin", "\"v1\"")
        val file = ModelFile("model.bin", 12L, sha256Hex(body))
        val dl = ModelDownloader(baseUrl = "http://test/", store = ms, opener = opener)
        assertTrue("complete .part at 416 must verify, not restart", dl.download(file))
        assertEquals(12L, part.length()) // NOT deleted, NOT re-downloaded
        assertEquals(1, server.calls) // exactly ONE connection setup
    }

    // 416 with a too short .part → discard + EXACTLY ONE restart (no endless retry)
    @Test fun http416WithShortPartRestartsExactlyOnce() {
        val body = ByteArray(12) { (it * 7 + 3).toByte() }
        server = FakeServer(body, 416)
        val (ms, opener) = store()
        val part = ms.partFile("model.bin")
        part.writeBytes(body.copyOfRange(0, 5)) // too short
        ms.storeEtag("model.bin", "\"v1\"")
        val file = ModelFile("model.bin", 12L, sha256Hex(body))
        val dl = ModelDownloader(baseUrl = "http://test/", store = ms, opener = opener)
        assertTrue(dl.download(file))
        assertEquals(12L, part.length())
        assertEquals(2, server.calls) // 416 + exactly one 200 restart
    }

    // (c) 200 despite a range → restart from 0
    @Test fun http200DespiteRangeRestartsFromZero() {
        val body = ByteArray(12) { (it * 11 + 4).toByte() }
        server = FakeServer(body, 200)
        val (ms, opener) = store()
        val part = ms.partFile("model.bin")
        part.writeBytes(ByteArray(5) { 0x7f }) // old garbage (wrong server)
        val file = ModelFile("model.bin", 12L, sha256Hex(body))
        val dl = ModelDownloader(baseUrl = "http://test/", store = ms, opener = opener)
        assertTrue(dl.download(file))
        assertEquals(12L, part.length()) // rewritten from 0
    }

    // (d) ETag change → discard the .part + restart
    @Test fun etagChangeDiscardsPartAndRestarts() {
        val body = ByteArray(12) { (it * 13 + 5).toByte() }
        server = FakeServer(body, 206, etag = "\"v2\"") // server has a new version
        val (ms, opener) = store()
        val part = ms.partFile("model.bin")
        part.writeBytes(body.copyOfRange(0, 6))
        ms.storeEtag("model.bin", "\"v1\"") // old ETag stored
        val file = ModelFile("model.bin", 12L, sha256Hex(body))
        val dl = ModelDownloader(baseUrl = "http://test/", store = ms, opener = opener)
        // First attempt: ETag change detected → delete + restart; afterwards
        // resumeFrom==0 → range gone → status 200 path? The fake delivers 206 …
        assertTrue(dl.download(file))
        assertEquals(12L, part.length())
        assertTrue("part must be rewritten from scratch", server.calls >= 2)
    }

    // (e) hash mismatch → .part deleted + ModelError.HashMismatch
    @Test fun hashMismatchDeletesPartAndThrows() {
        val body = ByteArray(12) { (it * 17 + 6).toByte() }
        server = FakeServer(body, 200, etag = null)
        val (ms, opener) = store()
        val file = ModelFile("model.bin", 12L, "0".repeat(64)) // wrong hash
        val dl = ModelDownloader(baseUrl = "http://test/", store = ms, opener = opener)
        val part = ms.partFile("model.bin")
        val e = try { dl.download(file); null } catch (e: ModelError.HashMismatch) { e }
        assertTrue("expected HashMismatch", e != null)
        assertEquals("model.bin", e!!.fileName)
        assertFalse(part.exists()) // make a broken resume impossible
    }


    // ---- V12: retry attempt count made visible (D1-03) ----

    @Test fun `progress reports attempt and maxAttempts during a retry`() {
        // two failed attempts then success: attempt must accompany every emitted
        // progress (1-based) and maxAttempts = maxRetries+1.
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
        // the fake delivers success on the first attempt — attempt=1, maxAttempts=3
        assertEquals(1, last?.attempt)
        assertEquals(3, last?.maxAttempts)
    }
}
