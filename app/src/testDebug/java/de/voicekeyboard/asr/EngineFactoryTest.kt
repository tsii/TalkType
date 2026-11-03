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
}
