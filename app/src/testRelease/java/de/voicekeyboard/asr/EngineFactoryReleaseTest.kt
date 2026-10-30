package de.voicekeyboard.asr

import de.voicekeyboard.model.ModelStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import java.io.File

/**
 * V13 Release-Pfad: LITERT_CPU kann in Release NIE eine LiteRT-Engine
 * erzeugen — der Bridge-Provider ist null (Variante B qualitätsgesteuert
 * entfernt), der dokumentierte Fallback liefert ParakeetAsrEngine mit
 * usedVariant = ONNX_CPU.
 */
class EngineFactoryReleaseTest {

    @Test
    fun `release LITERT_CPU faellt dokumentiert auf ONNX_CPU zurueck`() {
        val store = ModelStore(
            File(kotlin.io.path.createTempDirectory("v13rel").toFile(), "models"),
        )
        // Provider ist in Release null — selbst ein READY-B-Modell (hier
        // unmoeglich, aber der Zweig ist abgesichert) darf keine LiteRT-Engine
        // erzeugen.
        assertEquals(null, LiteRtBridge.acquire())
        val result = EngineFactory.create(de.voicekeyboard.model.ModelVariant.LITERT_CPU, store)
        assertEquals(ParakeetAsrEngine::class.java, result.engine.javaClass)
        assertEquals(de.voicekeyboard.model.ModelVariant.ONNX_CPU, result.usedVariant)
        assertNotEquals(
            "Kein LiteRT-Typ in Release",
            "de.voicekeyboard.asr.litert.LiteRtAsrEngine",
            result.engine.javaClass.name,
        )
    }

    @Test
    fun `release ONNX_CPU bleibt ONNX_CPU`() {
        val store = ModelStore(
            File(kotlin.io.path.createTempDirectory("v13rel2").toFile(), "models"),
        )
        val result = EngineFactory.create(de.voicekeyboard.model.ModelVariant.ONNX_CPU, store)
        assertEquals(ParakeetAsrEngine::class.java, result.engine.javaClass)
        assertEquals(de.voicekeyboard.model.ModelVariant.ONNX_CPU, result.usedVariant)
    }
}
