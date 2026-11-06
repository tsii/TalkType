package de.voicekeyboard.model

/**
 * Binding model file list: values EXACTLY from docs/MODEL_SHA256.txt
 * and docs/ASR_CONTRACT.md §1 (file sizes measured, sum int8 ≈ 671 MB).
 *
 * The names are at the same time the whitelist for every file access of the
 * [ModelStore] (path traversal protection) and the expectation for the
 * SHA-256 end-to-end check of the [ModelDownloader].
 */
/**
 * V5 (V1-01 fix): separation of URL path and file system name.
 * [urlPath] = path in the CDN repo (may contain subdirectories, e. g.
 * "assets/token_vocab.bin"); [storeName] = flat, traversal-safe name
 * in the model directory. A: both identical (no re-download).
 */
data class ModelFile(
    val storeName: String,
    val sizeBytes: Long,
    val sha256: String,
    val urlPath: String = storeName,
)

object ModelManifest {

    const val BASE_URL_ONNX = "https://huggingface.co/istupakov/parakeet-tdt-0.6b-v3-onnx/resolve/main/"

    /** Variant B (LiteRT, cached streaming) — base from the Arm repo. */
    const val BASE_URL_LITERT = "https://huggingface.co/Arm/parakeet-tdt-0-6b-v3-int8-litert/resolve/main/"

    /**
     * T22b'/VK-2 (REVIEW_V0146): base URL of the QWEN set — EMPTY until the
     * download server host is pinned at upload time (post-GATE decision;
     * the placeholder host of the draft was NOT resolvable — see review).
     * Empty = the set is NOT offered: DownloadPolicy reports Unsupported
     * ("not enabled yet"), the Setup card stays in the not-downloaded state
     * and a hand-started download fails fast WITHOUT any network attempt.
     * Pinning the host here is the one-line unlock (plus contract §3 server).
     */
    const val BASE_URL_QWEN = ""

    /** Compatibility alias: today's single set = variant A (uncontrolled callers). */
    const val BASE_URL = BASE_URL_ONNX

    /** Variant A (ONNX_CPU): today's list, untouched. */
    val ONNX: List<ModelFile> = listOf(
        ModelFile(
            storeName = "nemo128.onnx",
            sizeBytes = 139_764L,
            sha256 = "a9fde1486ebfcc08f328d75ad4610c67835fea58c73ba57e3209a6f6cf019e9f",
        ),
        ModelFile(
            storeName = "encoder-model.int8.onnx",
            sizeBytes = 652_183_999L,
            sha256 = "6139d2fa7e1b086097b277c7149725edbab89cc7c7ae64b23c741be4055aff09",
        ),
        ModelFile(
            storeName = "decoder_joint-model.int8.onnx",
            sizeBytes = 18_202_004L,
            sha256 = "eea7483ee3d1a30375daedc8ed83e3960c91b098812127a0d99d1c8977667a70",
        ),
        ModelFile(
            storeName = "vocab.txt",
            sizeBytes = 93_939L,
            sha256 = "d58544679ea4bc6ac563d1f545eb7d474bd6cfa467f0a6e2c1dc1c7d37e3c35d",
        ),
    )

    /** Variant B (LITERT_CPU): Arm/parakeet-tdt-0-6b-v3-int8-litert, 5 files. */
    val LITERT: List<ModelFile> = listOf(
        ModelFile(
            storeName = "parakeet_tdt_nemo_encoder_cached_int8_dynamic_litert.tflite",
            sizeBytes = 648_784_320L,
            sha256 = "f5d42665b3bd47827f2a7842db10a6fe1ea2050b349224183d71d03fea8580b0",
        ),
        ModelFile(
            storeName = "parakeet_tdt_nemo_decoder_step_int8_dynamic_litert.tflite",
            sizeBytes = 27_705_640L,
            sha256 = "3a821dda57c615eaef1c1d6b8ba671c20a39b6e73f5e194878d7311740a6e3cc",
        ),
        ModelFile(
            storeName = "parakeet_tdt_nemo_joint_step_int8_dynamic_litert.tflite",
            sizeBytes = 6_466_576L,
            sha256 = "8d9f93202460b5c6b2627b3eb07b6aa82ac8a1b1344b335df3c284b17f573936",
        ),
        ModelFile(
            storeName = "parakeet_tdt_nemo_featurizer_int8_dynamic_litert.tflite",
            sizeBytes = 262_864L,
            sha256 = "756287855ed8cf36cacd65ad1012da329cc9d612ef3b81e2d48fc1f8f728ec9a",
        ),
        ModelFile(
            storeName = "token_vocab.bin",
            urlPath = "assets/token_vocab.bin",
            sizeBytes = 78_669L, // HF API measured (V2 fix)
            sha256 = "e5893bde019de49606a1543e45f12114e71fa0a7c0d27345af9576cf8f24f8fd",
        ),
    )

    /**
     * T22b' (PLAN_V0146 §7 + docs/QWEN_ASR_CONTRACT.md §3): QWEN3-ASR-0.6B set
     * (gate decision v0.1.46) — the PINNED production file list. 6 FLAT names
     * (the server delivers flat names, the engine finds them in the model
     * dir); sizes/sha256 verified against the export artifacts. An EMPTY list
     * would keep the set structurally inert (isAllowedName rejects everything,
     * DownloadPolicy reports Unsupported) — this list is the unlock.
     */
    val QWEN: List<ModelFile> = listOf(
        ModelFile(
            storeName = "encoder.int8.onnx",
            sizeBytes = 187_361_021L,
            sha256 = "327974e11e2914037272224fc439af093bb251d0d232c0513bc9054d1ae6adba",
        ),
        ModelFile(
            storeName = "qwen_prefill.int8.onnx",
            sizeBytes = 752_810_624L,
            sha256 = "1a505e73e42074a2fdded9cdef4d9484586d52dbd654760982df738e1c03e48d",
        ),
        ModelFile(
            storeName = "qwen_step.int8.onnx",
            sizeBytes = 752_598_179L,
            sha256 = "9a7f8946996be4f36c0687265cc18f0d929c459001d15d1dfc5b6f783cb198d5",
        ),
        ModelFile(
            storeName = "tokenizer.json",
            sizeBytes = 11_429_653L,
            sha256 = "fe1fad59be22a41ee293363fcf95fdedbc7c93f3b49270b1d2e18bd1399a7a05",
        ),
        ModelFile(
            storeName = "mel_filters.npy",
            sizeBytes = 103_040L,
            sha256 = "8abf12444e0d9c0fd6c5e49f8b977d41e47b51a5eb48d399c232578d3d5e914f",
        ),
        ModelFile(
            storeName = "mel_config.json",
            sizeBytes = 260L,
            sha256 = "1888b2defc1b7db6ca081bd706f31e8fc1107cd44f1eba644834251f6dccd24c",
        ),
    )

    /** Manifest per variant (V1): A = today's list, B = LiteRT set, Q = pinned 0.6B contract (T22b'). */
    fun forVariant(variant: de.voicekeyboard.model.ModelVariant): List<ModelFile> =
        when (variant) {
            de.voicekeyboard.model.ModelVariant.ONNX_CPU -> ONNX
            de.voicekeyboard.model.ModelVariant.LITERT_CPU -> LITERT
            de.voicekeyboard.model.ModelVariant.QWEN3_ASR -> QWEN
        }

    /** T22b (PLAN_V0146 §2): base URL per set — the downloader gets its source
     *  per variant (A/B unchanged, QWEN = download server path). */
    fun baseUrlFor(variant: de.voicekeyboard.model.ModelVariant): String =
        when (variant) {
            de.voicekeyboard.model.ModelVariant.ONNX_CPU -> BASE_URL_ONNX
            de.voicekeyboard.model.ModelVariant.LITERT_CPU -> BASE_URL_LITERT
            de.voicekeyboard.model.ModelVariant.QWEN3_ASR -> BASE_URL_QWEN
        }

    /** Compatibility: DEFAULT stays an alias to A. */
    val DEFAULT: List<ModelFile> = ONNX

    val TOTAL_BYTES: Long = DEFAULT.sumOf { it.sizeBytes }

    /** Total size per variant (V1: space requirement for the setup display). */
    fun totalBytes(variant: de.voicekeyboard.model.ModelVariant): Long =
        forVariant(variant).filter { it.sizeBytes > 0 }.sumOf { it.sizeBytes }

    /**
     * V5: whitelist per variant, deliberately TRAVERSAL-STRICT: the name must be flat
     * (no '/', no '\\', not "."/"..", not empty) and match exactly the
     * storeName of the variant. urlPath prefixes are NEVER allowed here
     * (the storeName is flat, only the URL path may lie deeper).
     */
    fun isAllowedName(storeName: String, variant: de.voicekeyboard.model.ModelVariant): Boolean =
        storeName.isNotEmpty() &&
            '/' !in storeName && '\\' !in storeName &&
            storeName != "." && storeName != ".." &&
            storeName in forVariant(variant).map { it.storeName }

    /** Compatibility (default = variant A). */
    fun isAllowedName(storeName: String): Boolean = isAllowedName(storeName, de.voicekeyboard.model.ModelVariant.ONNX_CPU)

    fun fileByName(name: String): ModelFile? = DEFAULT.firstOrNull { it.storeName == name }
}
