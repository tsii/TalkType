package de.voicekeyboard.asr

import de.voicekeyboard.model.ModelVariant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * V0 tests (TDD): EngineFactory returns the existing ONNX engine for both
 * variants (deliberately identical in stage V0); ModelVariant.fromId
 * falls back to ONNX_CPU for unknown/null.
 */
class EngineFactoryTest {

    @Test
    fun `V0 variant ONNX_CPU returns today's engine`() {
        val engine = EngineFactory.create(ModelVariant.ONNX_CPU)
        assertEquals(ParakeetAsrEngine::class.java, engine.javaClass)
    }

    @Test
    fun `V0 variant LITERT_CPU deliberately returns the same ONNX engine`() {
        // V0 intent: no LiteRT code — both variants on the existing engine,
        // so the chain stays green (switching only in stage 2).
        val engine = EngineFactory.create(ModelVariant.LITERT_CPU)
        assertEquals(ParakeetAsrEngine::class.java, engine.javaClass)
    }

    @Test
    fun `fromId unknown or null falls back to ONNX_CPU`() {
        assertSame(ModelVariant.ONNX_CPU, ModelVariant.fromId(null))
        assertSame(ModelVariant.ONNX_CPU, ModelVariant.fromId("gibts-nicht"))
        assertSame(ModelVariant.ONNX_CPU, ModelVariant.fromId(""))
        assertSame(ModelVariant.ONNX_CPU, ModelVariant.fromId("onnx-cpu"))
        assertSame(ModelVariant.LITERT_CPU, ModelVariant.fromId("litert-cpu"))
        assertSame(ModelVariant.DEFAULT, ModelVariant.fromId("xyz"))
    }

    @Test
    fun `default is ONNX_CPU and the persistence key is stable`() {
        assertSame(ModelVariant.ONNX_CPU, ModelVariant.DEFAULT)
        assertEquals("model_variant", ModelVariant.PREFS_KEY)
        assertEquals("vk_layout", ModelVariant.PREFS_FILE)
    }

    // ---- V3: LiteRtAsrEngine skeleton + documented fallback to A ----

    @Test
    fun `V3 B without model files returns the factory A engine and reports ONNX_CPU`() {
        // B has no complete model (store without a B pointer) => documented
        // fallback to the existing ONNX engine, NOT an object that fails at
        // load() (rule: a visible reason instead of ghost writing).
        val store = de.voicekeyboard.model.ModelStore(
            java.io.File(createTempDir(), "models-root"),
        )
        val result = EngineFactory.create(ModelVariant.LITERT_CPU, store)
        assertEquals(ParakeetAsrEngine::class.java, result.engine.javaClass)
        assertEquals(ModelVariant.ONNX_CPU, result.usedVariant)
    }

    @Test
    fun `V3 A-ready stays on the A engine`() {
        val store = de.voicekeyboard.model.ModelStore(
            java.io.File(createTempDir(), "models-root"),
        )
        val result = EngineFactory.create(ModelVariant.ONNX_CPU, store)
        assertEquals(ParakeetAsrEngine::class.java, result.engine.javaClass)
        assertEquals(ModelVariant.ONNX_CPU, result.usedVariant)
    }

    private fun createTempDir(): String =
        kotlin.io.path.createTempDirectory("v3factory").toFile().apply { deleteOnExit() }.absolutePath

    // ---- T22g (PLAN_V0146 §3): Qwen arm — construction ORT-free, fallback, model dir ----

    @get:org.junit.Rule
    val tmp = org.junit.rules.TemporaryFolder()

    private val fixtureA = byteArrayOf(1, 2, 3)
    private val fixtureB = byteArrayOf(9, 8)

    private fun miniManifest(): List<de.voicekeyboard.model.ModelFile> = listOf(
        de.voicekeyboard.model.ModelFile(
            "model.bin", fixtureA.size.toLong(),
            de.voicekeyboard.model.Sha256.ofBytes(fixtureA),
        ),
        de.voicekeyboard.model.ModelFile(
            "vocab.txt", fixtureB.size.toLong(),
            de.voicekeyboard.model.Sha256.ofBytes(fixtureB),
        ),
    )

    /** Store with a READY version of [variant] (T22c staging pattern). */
    private fun readyStore(variant: ModelVariant): de.voicekeyboard.model.ModelStore {
        val store = de.voicekeyboard.model.ModelStore(tmp.newFolder(), miniManifest())
        val staging = store.newStagingDir()
        java.io.File(staging, "model.bin").writeBytes(fixtureA)
        java.io.File(staging, "vocab.txt").writeBytes(fixtureB)
        org.junit.Assert.assertTrue("fixture activation expected", store.activate(staging, variant))
        return store
    }

    @Test
    fun `T22g qwen ready store delivers a QwenAsrEngine without starting ORT`() {
        val store = readyStore(ModelVariant.QWEN3_ASR)
        val result = EngineFactory.create(ModelVariant.QWEN3_ASR, store)
        // type + used variant — load() is NEVER called here: no ORT session
        assertEquals(de.voicekeyboard.asr.qwen.QwenAsrEngine::class.java, result.engine.javaClass)
        assertEquals(ModelVariant.QWEN3_ASR, result.usedVariant)
    }

    @Test
    fun `T22g qwen not ready falls back to the documented A engine`() {
        val store = de.voicekeyboard.model.ModelStore(tmp.newFolder(), miniManifest()) // nothing activated
        val result = EngineFactory.create(ModelVariant.QWEN3_ASR, store)
        assertEquals(ParakeetAsrEngine::class.java, result.engine.javaClass)
        assertEquals("fallback visible in usedVariant", ModelVariant.ONNX_CPU, result.usedVariant)
    }

    @Test
    fun `T22g modelDir of the qwen set points at its active version`() {
        val store = readyStore(ModelVariant.QWEN3_ASR)
        val dir = store.modelDir(ModelVariant.QWEN3_ASR)
        assertEquals("the active version dir", store.activeVersion(ModelVariant.QWEN3_ASR), dir.name)
        assertEquals("under versions/", "versions", dir.parentFile?.name)
        org.junit.Assert.assertTrue(
            "per-variant pointer file",
            java.io.File(dir.parentFile.parentFile, "active-qwen3-asr-0.6b-int8.txt").isFile,
        )
    }
}
