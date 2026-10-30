package de.voicekeyboard.model

/**
 * V0/V1 (MODEL_VARIANT_SWITCH_MEMO Stufe 1): Modell-Varianten.
 * A = ONNX_CPU (heutige Pipeline, unangetastet), B = LITERT_CPU
 * (Arm/parakeet-tdt-0-6b-v3-int8-litert, cached streaming).
 * Variante C (litert-community, NPU) wird NICHT ausgeliefert.
 */
enum class ModelVariant(val id: String, val label: String) {
    ONNX_CPU("onnx-cpu", "ONNX (CPU)"),
    LITERT_CPU("litert-cpu", "LiteRT (CPU)");

    companion object {
        const val PREFS_KEY = "model_variant"

        /** Persistenz-Schlüssel-Name in der vk_layout-Prefs (wie current_language). */
        const val PREFS_FILE = "vk_layout"

        /** Default = heutige Variante A — Verhalten unverändert. */
        val DEFAULT = ONNX_CPU

        /** Unbekannter/null-Wert ⇒ Fallback A (nie unbenannte Variante aktiv). */
        fun fromId(raw: String?): ModelVariant =
            entries.firstOrNull { it.id == raw } ?: DEFAULT
    }
}
