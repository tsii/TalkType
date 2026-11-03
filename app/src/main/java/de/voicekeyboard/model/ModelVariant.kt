package de.voicekeyboard.model

/**
 * V0/V1 (MODEL_VARIANT_SWITCH_MEMO stage 1): model variants.
 * A = ONNX_CPU (today's pipeline, untouched), B = LITERT_CPU
 * (Arm/parakeet-tdt-0-6b-v3-int8-litert, cached streaming).
 * Variant C (litert-community, NPU) is NOT shipped.
 */
enum class ModelVariant(val id: String, val label: String) {
    ONNX_CPU("onnx-cpu", "ONNX (CPU)"),
    LITERT_CPU("litert-cpu", "LiteRT (CPU)");

    companion object {
        const val PREFS_KEY = "model_variant"

        /** Persistence key name in the vk_layout prefs (like current_language). */
        const val PREFS_FILE = "vk_layout"

        /** Default = today's variant A — behavior unchanged. */
        val DEFAULT = ONNX_CPU

        /** Unknown/null value ⇒ fallback A (never an unnamed variant active). */
        fun fromId(raw: String?): ModelVariant =
            entries.firstOrNull { it.id == raw } ?: DEFAULT
    }
}
