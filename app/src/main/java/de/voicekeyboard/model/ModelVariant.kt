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
/**
 * T24b (PLAN_V0147 §17.2, Gleis 3): variant KIND — ASR sets drive the
 * DICTATION engine; TEXT sets (U2) are on-demand spelling-correction models
 * alongside the resident ASR engine (§17 one-engine relaxation) and are
 * NEVER the active dictation set.
 */
enum class ModelVariant(val id: String, val label: String, val kind: Kind = Kind.ASR) {
    ONNX_CPU("onnx-cpu", "ONNX (CPU)"),
    LITERT_CPU("litert-cpu", "LiteRT (CPU)"),

    /** Second dictation set (T22b'): id = set id per plan §2/§7 (0.6B, gate). */
    QWEN3_ASR("qwen3-asr-0.6b-int8", "Qwen3-ASR (CPU)"),

    /**
     * T24b U2 TEXT sets (Gleis 3): BART (en) / FLAN-T5 (de) spelling
     * correction, MERGED int8 artifacts (BART_CORRECT_CONTRACT §12 —
     * 3 graphs + shared/ external-data blob + tokenizer trio). Opt-in
     * download, never auto; never the dictation engine (kind = TEXT).
     */
    BART_SPELLING_EN("bart-spelling-en-int8", "Text correction en", Kind.TEXT),
    BART_SPELLING_DE("bart-spelling-de-int8", "Text correction de", Kind.TEXT);

    enum class Kind { ASR, TEXT }

    companion object {
        const val PREFS_KEY = "model_variant"

        /** Persistence key name in the vk_layout prefs (like current_language). */
        const val PREFS_FILE = "vk_layout"

        /** Default = today's variant A — behavior unchanged. */
        val DEFAULT = ONNX_CPU

        /** Unknown/null value ⇒ fallback A (never an unnamed variant active).
         *  T24b: ONLY ASR variants are valid values of the model_variant pref —
         *  a TEXT id (hand-bent pref, future U2 pref mixups) never becomes the
         *  active dictation set. */
        fun fromId(raw: String?): ModelVariant =
            entries.firstOrNull { it.kind == Kind.ASR && it.id == raw } ?: DEFAULT

        /**
         * T24b: U2 TEXT set by id — null for everything else (ASR ids,
         * unknown); the CALLER decides what "off" means (never a fallback
         * TEXT set behind the caller's back).
         */
        fun textFromId(raw: String?): ModelVariant? =
            entries.firstOrNull { it.kind == Kind.TEXT && it.id == raw }
    }
}
