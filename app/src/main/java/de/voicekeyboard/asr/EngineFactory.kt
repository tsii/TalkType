package de.voicekeyboard.asr

import de.voicekeyboard.model.ModelStore
import de.voicekeyboard.model.ModelVariant

/**
 * V0/V3 (MODEL_VARIANT_SWITCH_MEMO): Fabrik für die AsrEngine je Variante.
 *
 * V13: LITERT_CPU liefert eine LiteRT-Engine NUR im Debug-Build (LiteRtBridge
 * — Variante B ist qualitätsgesteuert aus Release entfernt, Quellcode bleibt
 * im Debug-Source-Set) UND nur wenn der Store ein vollständiges B-Modell hat
 * ([ModelStore.isReady]). Sonst dokumentierter Fallback auf die bestehende
 * ONNX-Engine (Release: IMMER Fallback — Provider ist dort null).
 *
 * [create] ist die alte Signatur (V0: immer ONNX-Engine); [create] mit
 * Store-Abfrage ist die V3-Produktionssignatur mit [Result.usedVariant].
 */
object EngineFactory {

    /** Ergebnis: gebaute Engine + die tatsächlich verwendete Variante (Fallback sichtbar). */
    data class Result(val engine: AsrEngine, val usedVariant: ModelVariant)

    /** Liefert die Engine für [variant]; V0: immer die bestehende ONNX-Engine. */
    fun create(variant: ModelVariant): AsrEngine = ParakeetAsrEngine()

    /**
     * V3: Variante nur nutzen, wenn das Modell READY ist; sonst Fallback A.
     * [store] entscheidet per [ModelStore.isReady] (variantengetrennt, V1).
     */
    fun create(variant: ModelVariant, store: ModelStore): Result {
        if (variant == ModelVariant.LITERT_CPU && store.isReady(ModelVariant.LITERT_CPU)) {
            val engine = LiteRtBridge.acquire()?.invoke()
            if (engine != null) return Result(engine, ModelVariant.LITERT_CPU)
            // Release: Provider null => dokumentierter Fallback (V13)
        }
        // Dokumentierter Fallback: A-Engine + usedVariant=ONNX_CPU
        return Result(ParakeetAsrEngine(), ModelVariant.ONNX_CPU)
    }
}
