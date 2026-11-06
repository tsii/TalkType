package de.voicekeyboard.model

/**
 * V0/V1 (MODEL_VARIANT_SWITCH_MEMO stage 1): model variants.
 * A = ONNX_CPU (today's pipeline, untouched), B = LITERT_CPU
 * (Arm/parakeet-tdt-0-6b-v3-int8-litert, cached streaming).
 * Variant C (litert-community, NPU) is NOT shipped.
 *
 * T22b/T22b' (PLAN_V0146 §3/§7): QWEN3_ASR = second, switchable dictation set.
 * T22a GATE decision (v0.1.46): Qwen3-ASR-0.6B int8 — EXPERIMENTAL second
 * model, set id "qwen3-asr-0.6b-int8" (the 1.7B draft id is NOT a set).
 * The manifest is pinned in ModelManifest.QWEN (QWEN_ASR_CONTRACT §3).
 * Release-visibility: {ONNX_CPU, QWEN3_ASR}; LITERT_CPU stays debug-only and
 * hidden in the UI (visibility is wired in T22h).
 */
enum class ModelVariant(val id: String, val label: String) {
    ONNX_CPU("onnx-cpu", "ONNX (CPU)"),
    LITERT_CPU("litert-cpu", "LiteRT (CPU)"),

    /** Second dictation set (T22b'): id = set id per plan §2/§7 (0.6B, gate). */
    QWEN3_ASR("qwen3-asr-0.6b-int8", "Qwen3-ASR (CPU)");

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
