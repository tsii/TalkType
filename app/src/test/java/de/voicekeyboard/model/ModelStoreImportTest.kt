package de.voicekeyboard.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.io.InputStream

/**
 * V0.1.51 (PLAN_V0151 §D, P2): [ModelStore.importSet] — local model-set import
 * (USB/cloud folder) over the pinned manifest. Matrix: (a) complete set →
 * READY, (b) one wrong hash → HashMismatch + old set untouched + no staging
 * remnants, (c) missing file → Missing, (d) wrong size → SizeMismatch,
 * (e) IOException mid-copy → no half state, (f) import into an EXISTING set
 * replaces it only after full verification (never a set-less intermediate
 * state). Fake [ImportSource] like the app's document-tree adapter; the store
 * core stays Android-free (no ContentResolver faking needed).
 */
class ModelStoreImportTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val content = ByteArray(4096) { (it % 251).toByte() }

    /** Mini manifest with the REAL U2 name shape incl. multi-segment names. */
    private fun miniU2(): List<ModelFile> = listOf(
        ModelFile("encoder.int8.onnx", 1024L, shaOf(1024)),
        ModelFile("decoder_init.int8.onnx", 512L, shaOf(512)),
        ModelFile("shared/shared_weights.bin", 2048L, shaOf(2048)),
        ModelFile("tokenizer/vocab_meta.json", 64L, shaOf(64)),
    )

    private fun shaOf(n: Int): String {
        val f = tmp.newFile()
        f.writeBytes(content.copyOf(n))
        return Sha256.ofFile(f)
    }

    private fun store(manifest: List<ModelFile> = miniU2()): Pair<ModelStore, File> {
        val root = tmp.newFolder()
        return ModelStore(root, manifest) to root
    }

    /** Fake import source: map of relPath → bytes; optional mid-copy abort. */
    private class FakeSource(
        private val files: Map<String, ByteArray>,
        private val boomAfterBytes: MutableMap<String, Int> = mutableMapOf(),
    ) : ImportSource {
        override fun open(relativePath: String): InputStream? {
            val bytes = files[relativePath] ?: return null
            val boom = boomAfterBytes[relativePath]
            return if (boom != null) {
                object : InputStream() {
                    private var pos = 0
                    override fun read(): Int {
                        if (pos >= boom) throw IOException("source went away mid-copy")
                        val b = bytes[pos].toInt() and 0xff
                        pos++
                        return b
                    }
                }
            } else {
                bytes.inputStream()
            }
        }
    }

    /** Complete, correct source content for [manifest]. */
    private fun goodFiles(manifest: List<ModelFile>): Map<String, ByteArray> =
        manifest.associate { it.storeName to content.copyOf(it.sizeBytes.toInt()) }

    /** No leftover .part/.etag/staging after a failed import. */
    private fun assertNoRemnants(root: File, store: ModelStore) {
        assertNull(
            "downloads swept — no .part/.etag left",
            File(root, "downloads").listFiles()?.firstOrNull { it.name.endsWith(".part") || it.name.endsWith(".etag") },
        )
        val versions = File(root, "versions").listFiles()?.filter { it.isDirectory } ?: emptyList()
        assertTrue(
            "no staging version dir left over: ${versions.map { it.name }}",
            versions.all { it.name == store.activeVersion(ModelVariant.BART_SPELLING_EN) },
        )
    }

    // ------------------------------------------------------------- (a) happy path

    @Test fun `complete folder imports to READY with manifest hashes intact`() {
        val manifest = miniU2()
        val (store, root) = store(manifest)
        val result = store.importSet(ModelVariant.BART_SPELLING_EN, FakeSource(goodFiles(manifest)))

        assertEquals(ImportResult.Ready, result)
        assertEquals(ModelStatus.READY, store.statusQuick(ModelVariant.BART_SPELLING_EN))
        assertTrue("files present incl. subdirectories", store.isPresent(ModelVariant.BART_SPELLING_EN))
        assertTrue("full hash check passes against the manifest", store.verifyInstalled(ModelVariant.BART_SPELLING_EN))
        assertNoRemnants(root, store)
        // multi-segment names landed as a real subdirectory tree
        assertTrue(File(store.modelDir(ModelVariant.BART_SPELLING_EN), "shared/shared_weights.bin").isFile)
    }

    // --------------------------------------------------------- (b) wrong hash

    @Test fun `one wrong hash fails with HashMismatch and leaves the old set READY`() {
        val manifest = miniU2()
        val (store, root) = store(manifest)
        // old set present and READY (import over an existing set)
        assertTrue(store.activate(stageFrom(store, manifest), ModelVariant.BART_SPELLING_EN))
        val oldVersion = store.activeVersion(ModelVariant.BART_SPELLING_EN)
        val oldDir = store.modelDir(ModelVariant.BART_SPELLING_EN)

        val files = goodFiles(manifest).toMutableMap()
        files["shared/shared_weights.bin"] = ByteArray(2048) { 7 } // right SIZE, wrong bytes
        val result = store.importSet(ModelVariant.BART_SPELLING_EN, FakeSource(files))

        assertTrue("wrong content identified as HashMismatch: $result", result is ImportResult.HashMismatch)
        assertEquals("the offending file is named", "shared/shared_weights.bin", (result as ImportResult.HashMismatch).relPath)
        assertEquals("old set stays READY", ModelStatus.READY, store.statusQuick(ModelVariant.BART_SPELLING_EN))
        assertEquals("old version untouched", oldVersion, store.activeVersion(ModelVariant.BART_SPELLING_EN))
        assertTrue("old files still hash-correct", store.verifyInstalled(ModelVariant.BART_SPELLING_EN))
        assertEquals("old bytes unmodified", shaOf(2048), Sha256.ofFile(File(oldDir, "shared/shared_weights.bin")))
        assertNoRemnants(root, store)
    }

    // ---------------------------------------------------------- (c) missing file

    @Test fun `a missing file fails with Missing naming the relative path`() {
        val manifest = miniU2()
        val (store, root) = store(manifest)
        val files = goodFiles(manifest).toMutableMap()
        files.remove("tokenizer/vocab_meta.json")

        val result = store.importSet(ModelVariant.BART_SPELLING_EN, FakeSource(files))

        assertTrue("missing file identified: $result", result is ImportResult.Missing)
        assertEquals("the missing file is named", "tokenizer/vocab_meta.json", (result as ImportResult.Missing).relPath)
        assertFalse("nothing activated", store.isPresent(ModelVariant.BART_SPELLING_EN))
        assertEquals(ModelStatus.ABSENT, store.statusQuick(ModelVariant.BART_SPELLING_EN))
        assertNoRemnants(root, store)
    }

    // ---------------------------------------------------------- (d) wrong size

    @Test fun `a truncated file fails with SizeMismatch`() {
        val manifest = miniU2()
        val (store, root) = store(manifest)
        val files = goodFiles(manifest).toMutableMap()
        files["encoder.int8.onnx"] = content.copyOf(1023) // 1 byte short

        val result = store.importSet(ModelVariant.BART_SPELLING_EN, FakeSource(files))

        assertTrue("short file identified as SizeMismatch: $result", result is ImportResult.SizeMismatch)
        assertEquals("encoder.int8.onnx", (result as ImportResult.SizeMismatch).relPath)
        assertEquals(ModelStatus.ABSENT, store.statusQuick(ModelVariant.BART_SPELLING_EN))
        assertNoRemnants(root, store)
    }

    @Test fun `an oversized file fails with SizeMismatch too`() {
        val manifest = miniU2()
        val (store, _) = store(manifest)
        val files = goodFiles(manifest).toMutableMap()
        files["decoder_init.int8.onnx"] = content.copyOf(513)

        val result = store.importSet(ModelVariant.BART_SPELLING_EN, FakeSource(files))

        assertTrue(result is ImportResult.SizeMismatch)
        assertEquals("decoder_init.int8.onnx", (result as ImportResult.SizeMismatch).relPath)
    }

    // -------------------------------------------- (e) IOException mid-copy

    @Test fun `an aborted copy leaves no half state and the old set intact`() {
        val manifest = miniU2()
        val (store, root) = store(manifest)
        assertTrue(store.activate(stageFrom(store, manifest), ModelVariant.BART_SPELLING_EN))
        val oldVersion = store.activeVersion(ModelVariant.BART_SPELLING_EN)

        val files = goodFiles(manifest)
        val boom = mutableMapOf("decoder_init.int8.onnx" to 100) // dies after 100 of 512 bytes
        val result = store.importSet(ModelVariant.BART_SPELLING_EN, FakeSource(files, boom))

        assertTrue("source death classified as IoError: $result", result is ImportResult.IoError)
        assertEquals("old set stays READY", ModelStatus.READY, store.statusQuick(ModelVariant.BART_SPELLING_EN))
        assertEquals("old version untouched", oldVersion, store.activeVersion(ModelVariant.BART_SPELLING_EN))
        assertTrue(store.verifyInstalled(ModelVariant.BART_SPELLING_EN))
        assertNoRemnants(root, store)
    }

    // ------------------------------------------- (f) replace an existing set

    @Test fun `import into an existing set replaces it only after full verification`() {
        val manifest = miniU2()
        val (store, root) = store(manifest)
        assertTrue(store.activate(stageFrom(store, manifest), ModelVariant.BART_SPELLING_EN))
        val oldVersion = store.activeVersion(ModelVariant.BART_SPELLING_EN)

        // first a BAD import: old set must survive untouched (no set-less state)
        val bad = goodFiles(manifest).toMutableMap()
        bad["tokenizer/vocab_meta.json"] = ByteArray(64) { 9 }
        val badResult = store.importSet(ModelVariant.BART_SPELLING_EN, FakeSource(bad))
        assertTrue(badResult is ImportResult.HashMismatch)
        assertEquals(ModelStatus.READY, store.statusQuick(ModelVariant.BART_SPELLING_EN))
        assertEquals(oldVersion, store.activeVersion(ModelVariant.BART_SPELLING_EN))

        // then the GOOD import: new version active, old one swept, still READY
        val result = store.importSet(ModelVariant.BART_SPELLING_EN, FakeSource(goodFiles(manifest)))
        assertEquals(ImportResult.Ready, result)
        val newVersion = store.activeVersion(ModelVariant.BART_SPELLING_EN)
        assertNotEquals("a NEW version was activated", oldVersion, newVersion)
        assertEquals(ModelStatus.READY, store.statusQuick(ModelVariant.BART_SPELLING_EN))
        assertTrue(store.verifyInstalled(ModelVariant.BART_SPELLING_EN))
        assertFalse("old version dir swept", File(File(root, "versions"), oldVersion!!).exists())
        assertNoRemnants(root, store)
    }

    // --------------------------------------------------------------- helpers

    /** Stages all manifest files with correct bytes (like the downloader does). */
    private fun stageFrom(store: ModelStore, manifest: List<ModelFile>): File {
        val staging = store.newStagingDir()
        for (entry in manifest) {
            val f = File(staging, entry.storeName)
            f.parentFile?.mkdirs()
            f.writeBytes(content.copyOf(entry.sizeBytes.toInt()))
        }
        return staging
    }
}
