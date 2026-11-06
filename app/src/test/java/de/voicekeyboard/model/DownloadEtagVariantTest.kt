package de.voicekeyboard.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * VK-1 (REVIEW_V0146, P1): the ETag side channel must be variant-aware —
 * etagFor/storeEtag defaulted to the ONNX_CPU whitelist, so EVERY Qwen
 * download died on its own file names (IllegalArgumentException ->
 * ModelError.Storage -> FAILED). Pins:
 *  - store level: Qwen names accepted under QWEN3_ASR, still rejected for A
 *    (real manifests — the whitelists are disjoint), A names keep working;
 *  - downloader level: a FULL ModelDownloader run for a Qwen name with
 *    variant=QWEN3_ASR (fake transport, NO network) goes through partFile +
 *    etagFor + storeEtag of the OWN set and stores the server ETag.
 */
class DownloadEtagVariantTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun sha256(b: ByteArray): String = java.security.MessageDigest.getInstance("SHA-256")
        .digest(b).joinToString("") { "%02x".format(it) }

    // ---- store level: whitelist per set on the ETag side channel ----

    @Test
    fun `store etag helpers accept qwen names under the qwen variant and reject them for A`() {
        val store = ModelStore(tmp.newFolder()) // REAL manifests: A and Qwen disjoint
        // Qwen name under the Qwen set: accepted (RED before VK-1: threw)
        store.storeEtag("encoder.int8.onnx", "\"q1\"", ModelVariant.QWEN3_ASR)
        assertEquals("\"q1\"", store.etagFor("encoder.int8.onnx", ModelVariant.QWEN3_ASR))
        // the SAME name stays rejected for the A whitelist
        assertThrows("A whitelist must reject the qwen name (read)", IllegalArgumentException::class.java) {
            store.etagFor("encoder.int8.onnx")
        }
        assertThrows("A whitelist must reject the qwen name (write)", IllegalArgumentException::class.java) {
            store.storeEtag("encoder.int8.onnx", null)
        }
        // A name under A: unchanged (regression)
        store.storeEtag("vocab.txt", "\"a1\"")
        assertEquals("\"a1\"", store.etagFor("vocab.txt"))
    }

    // ---- downloader level: full run for a Qwen name through the OWN set ----

    /** Scriptable fake connection (pattern of ModelDownloaderTest, no network). */
    private class FakeConnection(private val body: ByteArray, private val etag: String?) :
        HttpURLConnection(null) {
        override fun connect() {}
        override fun getResponseCode(): Int = 200
        override fun getInputStream(): InputStream = body.inputStream()
        override fun getContentLengthLong(): Long = body.size.toLong()
        override fun getHeaderField(name: String?): String? =
            if (name.equals("ETag", ignoreCase = true)) etag else null
        override fun disconnect() {}
        override fun usingProxy() = false
    }

    @Test
    fun `downloader with qwen variant completes a download including the etag path`() {
        val body = ByteArray(16) { (it + 3).toByte() }
        val store = ModelStore(tmp.newFolder()) // REAL manifests (whitelists disjoint)
        val downloader = ModelDownloader(
            baseUrl = "http://test/", // the fake transport ignores the URL
            store = store,
            variant = ModelVariant.QWEN3_ASR,
            opener = { _: URL -> FakeConnection(body, "\"q1\"") },
        )
        val file = ModelFile("encoder.int8.onnx", body.size.toLong(), sha256(body))

        // RED before VK-1: etagFor/storeEtag ran through the A whitelist ->
        // IllegalArgumentException -> ModelError.Storage. After the fix the
        // whole path (partFile + etag read + etag store) uses the OWN set.
        assertTrue("qwen download must succeed end-to-end", downloader.download(file))

        // the server ETag was stored under the QWEN whitelist
        assertEquals("\"q1\"", store.etagFor("encoder.int8.onnx", ModelVariant.QWEN3_ASR))
        // and the .part carries the downloaded bytes of the OWN set
        assertEquals(body.size.toLong(), store.partFile("encoder.int8.onnx", ModelVariant.QWEN3_ASR).length())
    }
}
