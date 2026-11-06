package de.voicekeyboard.asr

import de.voicekeyboard.model.ModelStore
import de.voicekeyboard.model.ModelVariant

/**
 * V0/V3 (MODEL_VARIANT_SWITCH_MEMO): factory for the AsrEngine per variant.
 *
 * V13: LITERT_CPU delivers a LiteRT engine ONLY in the debug build (LiteRtBridge
 * — variant B was removed from release via quality gate, the source code stays
 * in the debug source set) AND only if the store has a complete B model
 * ([ModelStore.isReady]). Otherwise documented fallback to the existing
 * ONNX engine (release: ALWAYS fallback — the provider is null there).
 *
 * [create] is the old signature (V0: always the ONNX engine); [create] with
 * a store query is the V3 production signature with [Result.usedVariant].
 */
object EngineFactory {

    /** Result: built engine + the actually used variant (fallback visible). */
    data class Result(val engine: AsrEngine, val usedVariant: ModelVariant)

    /** Delivers the engine for [variant]; V0: always the existing ONNX engine. */
    fun create(variant: ModelVariant): AsrEngine = ParakeetAsrEngine()

    /**
     * V3: use the variant only if the model is READY; otherwise fallback A.
     * [store] decides via [ModelStore.isReady] (variant-separated, V1).
     * T22g: QWEN3_ASR arm — the engine construction is ORT-FREE (sessions
     * come up only in load(), T22f2); a not-ready set falls back to A.
     */
    fun create(variant: ModelVariant, store: ModelStore): Result {
        if (variant == ModelVariant.QWEN3_ASR && store.isReady(ModelVariant.QWEN3_ASR)) {
            return Result(de.voicekeyboard.asr.qwen.QwenAsrEngine(), ModelVariant.QWEN3_ASR)
        }
        if (variant == ModelVariant.LITERT_CPU && store.isReady(ModelVariant.LITERT_CPU)) {
            val engine = LiteRtBridge.acquire()?.invoke()
            if (engine != null) return Result(engine, ModelVariant.LITERT_CPU)
            // Release: provider null => documented fallback (V13)
        }
        // Documented fallback: A engine + usedVariant=ONNX_CPU
        return Result(ParakeetAsrEngine(), ModelVariant.ONNX_CPU)
    }
}
