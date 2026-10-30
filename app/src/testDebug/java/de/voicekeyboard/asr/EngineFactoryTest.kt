package de.voicekeyboard.asr

import de.voicekeyboard.model.ModelVariant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * V0-Tests (TDD): EngineFactory liefert für beide Varianten die bestehende
 * ONNX-Engine (absichtlich identisch in Stufe V0); ModelVariant.fromId
 * fällt bei unbekanntem/null auf ONNX_CPU zurück.
 */
class EngineFactoryTest {

    @Test
    fun `V0 variante ONNX_CPU liefert die heutige engine`() {
        val engine = EngineFactory.create(ModelVariant.ONNX_CPU)
        assertEquals(ParakeetAsrEngine::class.java, engine.javaClass)
    }

    @Test
    fun `V0 variante LITERT_CPU liefert bewusst dieselbe ONNX-engine`() {
        // V0-Absicht: kein LiteRT-Code — beide Varianten auf der bestehenden Engine,
        // damit die Kette grün bleibt (Umschalten erst in Stufe 2).
        val engine = EngineFactory.create(ModelVariant.LITERT_CPU)
        assertEquals(ParakeetAsrEngine::class.java, engine.javaClass)
    }

    @Test
    fun `fromId unbekannt oder null faellt auf ONNX_CPU zurueck`() {
        assertSame(ModelVariant.ONNX_CPU, ModelVariant.fromId(null))
        assertSame(ModelVariant.ONNX_CPU, ModelVariant.fromId("gibts-nicht"))
        assertSame(ModelVariant.ONNX_CPU, ModelVariant.fromId(""))
        assertSame(ModelVariant.ONNX_CPU, ModelVariant.fromId("onnx-cpu"))
        assertSame(ModelVariant.LITERT_CPU, ModelVariant.fromId("litert-cpu"))
        assertSame(ModelVariant.DEFAULT, ModelVariant.fromId("xyz"))
    }

    @Test
    fun `default ist ONNX_CPU und persistenzschluessel ist stabil`() {
        assertSame(ModelVariant.ONNX_CPU, ModelVariant.DEFAULT)
        assertEquals("model_variant", ModelVariant.PREFS_KEY)
        assertEquals("vk_layout", ModelVariant.PREFS_FILE)
    }

    // ---- V3: Skelett LiteRtAsrEngine + dokumentierter Fallback auf A ----

    @Test
    fun `V3 B ohne modelldateien liefert Factory A-engine und meldet ONNX_CPU`() {
        // B hat kein vollständiges Modell (Store ohne B-Zeiger) => dokumentierter
        // Fallback auf die bestehende ONNX-Engine, NICHT ein Objekt, das bei
        // load() scheitert (Regel: sichtbarer Grund statt Geisterschreiben).
        val store = de.voicekeyboard.model.ModelStore(
            java.io.File(createTempDir(), "models-root"),
        )
        val result = EngineFactory.create(ModelVariant.LITERT_CPU, store)
        assertEquals(ParakeetAsrEngine::class.java, result.engine.javaClass)
        assertEquals(ModelVariant.ONNX_CPU, result.usedVariant)
    }

    @Test
    fun `V3 A-ready bleibt A-engine`() {
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
