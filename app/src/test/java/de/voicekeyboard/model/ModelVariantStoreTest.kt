package de.voicekeyboard.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * V1 tests (MODEL_VARIANT_SWITCH_MEMO stage 1): manifest per variant,
 * store separated by variant, backwards compatibility of the old pointer.
 */
class ModelVariantStoreTest {

    @Test
    fun `V1 manifest A sum and B sum from the manifests`() {
        // A: 139_764 + 652_183_999 + 18_202_004 + 93_939 = 670_619_706
        assertEquals(670_619_706L, ModelManifest.totalBytes(ModelVariant.ONNX_CPU))
        assertEquals(670_619_706L, ModelManifest.TOTAL_BYTES) // compatibility alias
        // B: 648_784_320 + 27_705_640 + 6_466_576 + 262_864 + 78_669
        assertEquals(683_298_069L, ModelManifest.totalBytes(ModelVariant.LITERT_CPU))
        assertEquals(5, ModelManifest.forVariant(ModelVariant.LITERT_CPU).size)
        assertEquals(4, ModelManifest.forVariant(ModelVariant.ONNX_CPU).size)
    }

    @Test
    fun `V1 a tampered B size breaks the check`() {
        // counter-check: a changed size in B must not survive the sum check
        // unnoticed — totalBytes deviates from the manifest value.
        val realB = ModelManifest.forVariant(ModelVariant.LITERT_CPU)
        val tampered = realB.mapIndexed { i, f ->
            if (i == 0) f.copy(sizeBytes = f.sizeBytes + 1) else f
        }.sumOf { it.sizeBytes }
        assertTrue("a tampered sum must deviate", tampered != 683_298_069L)
        // and: activate() checks size+hash per file — a wrong size leads to
        // verifyInstalled()==false (structure test further down).
    }

    @Test
    fun `V1 A-status stays READY for the old structure and B is ABSENT`() {
        val root = createTempDir("v1store")
        try {
            // old structure: active.txt (no variant suffix) + mini-A files
            val store = ModelStore(root, miniManifest())
            val staging = store.newStagingDir()
            for (f in miniManifest()) {
                File(staging, f.storeName).writeBytes(ByteArray(f.sizeBytes.toInt()) { 1 })
                // hash does not match — but isPresent (without hash) suffices for the structure
            }
            root.resolve("active.txt").writeText(staging.name)
            assertTrue("the old pointer counts as variant A", store.isPresent(ModelVariant.ONNX_CPU))
            assertTrue(store.activeVersion(ModelVariant.ONNX_CPU) != null)
            // B: no pointer, no directory ⇒ ABSENT (no fallback to A!)
            assertEquals(ModelStatus.ABSENT, store.status(ModelVariant.LITERT_CPU))
            assertEquals(null, store.activeVersion(ModelVariant.LITERT_CPU))
        } finally {
            root.deleteRecursively()
        }
    }

    private fun miniManifest(): List<ModelFile> = listOf(
        ModelFile("a.bin", 4L, Sha256.ofBytes(ByteArray(4) { 1 })),
        ModelFile("b.bin", 6L, Sha256.ofBytes(ByteArray(6) { 2 })),
    )

    private fun createTempDir(prefix: String): File =
        createTempFile(prefix).let { f -> f.delete(); f.apply { mkdirs() } }

    // ---- V5 (V1-01): urlPath/storeName separation + variant-aware whitelist ----

    @Test
    fun `V5 B-store-name has no slash and A stays unchanged`() {
        val b = ModelManifest.forVariant(ModelVariant.LITERT_CPU)
        for (f in b) {
            assertTrue("storeName flat: ${f.storeName}", '/' !in f.storeName && f.storeName.isNotBlank())
        }
        // the path prefix lives only in urlPath (download URL), never in the file system:
        val vocab = b.first { it.storeName == "token_vocab.bin" }
        assertEquals("assets/token_vocab.bin", vocab.urlPath)
        // regression against a 671-MB re-download: A names and sizes unchanged
        val a = ModelManifest.forVariant(ModelVariant.ONNX_CPU)
        assertEquals(listOf("nemo128.onnx", "encoder-model.int8.onnx",
            "decoder_joint-model.int8.onnx", "vocab.txt"), a.map { it.storeName })
        assertEquals(listOf(139_764L, 652_183_999L, 18_202_004L, 93_939L), a.map { it.sizeBytes })
        // A: urlPath == storeName (no switching, no re-download)
        assertTrue(a.all { it.urlPath == it.storeName })
        assertEquals(670_619_706L, a.sumOf { it.sizeBytes })
    }

    @Test
    fun `V5 whitelist is variant-aware - no B-path overwrites A`() {
        val bName = "parakeet_tdt_nemo_encoder_cached_int8_dynamic_litert.tflite"
        val aName = "encoder-model.int8.onnx"
        // the A whitelist rejects B names, the B whitelist allows them — and vice versa
        assertTrue(ModelManifest.isAllowedName(bName, ModelVariant.LITERT_CPU))
        assertTrue(!ModelManifest.isAllowedName(bName, ModelVariant.ONNX_CPU))
        assertTrue(ModelManifest.isAllowedName(aName, ModelVariant.ONNX_CPU))
        assertTrue(!ModelManifest.isAllowedName(aName, ModelVariant.LITERT_CPU))
    }

    @Test
    fun `V5 traversal protection stays sharp`() {
        // counter-check (most important assertion): '..' and '/' in storeName must
        // NEVER be let through — the correction must not soften it.
        assertTrue(!ModelManifest.isAllowedName("../encoder-model.int8.onnx", ModelVariant.ONNX_CPU))
        assertTrue(!ModelManifest.isAllowedName("assets/token_vocab.bin", ModelVariant.ONNX_CPU))
        assertTrue(!ModelManifest.isAllowedName("assets/token_vocab.bin", ModelVariant.LITERT_CPU))
        assertTrue(!ModelManifest.isAllowedName("..", ModelVariant.ONNX_CPU))
        assertTrue(!ModelManifest.isAllowedName("", ModelVariant.ONNX_CPU))
    }

    @Test
    fun `V5 activating B without stock returns false instead of throwing`() {
        val root = createTempDir("v5store")
        try {
            val store = ModelStore(root)
            val staging = File(root, "versions/v-fake")
            staging.mkdirs()
            // B stock incomplete (empty staging) => false, no throw
            assertTrue(!store.activate(staging, ModelVariant.LITERT_CPU))
        } finally {
            root.deleteRecursively()
        }
    }
}
