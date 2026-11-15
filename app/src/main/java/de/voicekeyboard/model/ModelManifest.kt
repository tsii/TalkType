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

    /**
     * V0.1.53 (PLAN_QWEN17B §B, S2): base URL of the 1.7B set — EMPTY until
     * checked model files + sha256 pins exist (gate §B.2: export, quantization
     * and the on-device measurement are still pending). Empty = the set is
     * NOT offered (same pattern as [BASE_URL_QWEN]); the Setup card stays
     * locked. Pinning files + host here is the unlock.
     */
    const val BASE_URL_QWEN_17B = ""

    /** Compatibility alias: today's single set = variant A (uncontrolled callers). */
    const val BASE_URL = BASE_URL_ONNX

    /**
     * V0.1.53 (PLAN_HOSTING §2a): per-set base URLs of the U2 TEXT sets —
     * EMPTY until the download host is pinned at upload time. en and de
     * share the SAME file names (`encoder.int8.onnx`, …) with DIFFERENT
     * sha256 hashes, so ONE common base URL is impossible — every set maps
     * to its own base (one-line unlock each; commit-pinned URL, never a
     * branch). Empty = the set is NOT offered (Qwen VK-2 pattern: inert,
     * fail-fast, never a dead download promise).
     */
    const val BASE_URL_U2_EN = ""
    const val BASE_URL_U2_DE = ""

    /**
     * T24b U2 TEXT sets — MERGED int8 artifacts (BART_CORRECT_CONTRACT §12:
     * 3 graphs + shared/ external-data blob + tokenizer trio; the graphs
     * reference "shared/shared_weights.bin" RELATIVELY, so the store names
     * keep the subdirectory tree). sha256/bytes pinned against
     * tools/bart_ref/EXPORT_LOG.txt (stage=merge/tokenizer). NOTE (T24b,
     * next Teil): ModelStore's flat-name whitelist must learn MULTI-SEGMENT
     * store names (traversal-safe per segment) before these sets can
     * download — this list is the pin, not yet the unlock.
     */
    val U2_EN: List<ModelFile> = listOf(
        ModelFile("encoder.int8.onnx", 43_601_202L, "bba119c4dc16830e6a0a0d670167134862ce124e1ac6b39c5abc936e2436cf54"),
        ModelFile("decoder_init.int8.onnx", 7_466_649L, "d986026349e944bbf1f2da44cf5a7e7da4b4bf211b1e3a6c2b86fd7704f96629"),
        ModelFile("decoder_step.int8.onnx", 324_984L, "2d1a70e4e4217f06fe42517003ec099035447b4a83cf1e508f2cb455901dc8b2"),
        ModelFile("shared/shared_weights.bin", 127_859_712L, "0a8a2920ae3412512ee60bfc48f31984a092c77cb3efd83a7fa80d7a39c3479c"),
        ModelFile("tokenizer/tokenizer.json", 3_558_746L, "dcf1e4d967564d18f9b7721e268b8a517027319a5cac544b9c1d9a0ce0163f44"),
        ModelFile("tokenizer/tokenizer_config.json", 497L, "123732aac64b1214daae8ad115a9efcd59d935f09db70872c01efb713781ae51"),
        ModelFile("tokenizer/vocab_meta.json", 225L, "5787c24699747b0db708eccc72475af62d38a1d6661b406a562986d41257f662"),
    )

    val U2_DE: List<ModelFile> = listOf(
        ModelFile("encoder.int8.onnx", 85_228_809L, "05507b81ecdad185131bdfdaccf942cf066584eb11ea20d85e5343eb25dbb89e"),
        ModelFile("decoder_init.int8.onnx", 14_453_095L, "97872f564c62c091db9b8f47b8b743ebcafa685d1bd2b01f37a088778d6ccda2"),
        ModelFile("decoder_step.int8.onnx", 333_394L, "275a785385b759351f84a74fc4ba7a18cd64b8823838c9e73f716291fd1a2b63"),
        ModelFile("shared/shared_weights.bin", 148_509_696L, "68296cd919fa2691744c80d62c981b55047b8f1ab3ed3e6a2f31b1111da430cd"),
        ModelFile("tokenizer/tokenizer.json", 2_422_267L, "483ff18e85be28f7c6a27b6a4ed17583abf139bb016bd4bd91277e35924a536d"),
        ModelFile("tokenizer/tokenizer_config.json", 2_488L, "682dc5904ee05fef4acddad8478ccf35aea659fecb927686bf77247d978648a1"),
        ModelFile("tokenizer/vocab_meta.json", 230L, "12d90200acae09451e327cb4402af03ba310f5002507a706dd4c9f351cca2da4"),
    )

    /**
     * V0.1.53 (PLAN_QWEN17B §B, S2): 1.7B set — DELIBERATELY EMPTY (inert):
     * no verified export exists yet (prefill int8 corrupt, step not exported,
     * no sha256 pins), so NOTHING may be offered or downloaded. An empty list
     * keeps the set structurally inert (isAllowedName rejects everything,
     * DownloadPolicy reports Unsupported, canOfferSet is false). Pinning the
     * checked files here + [BASE_URL_QWEN_17B] is the unlock (gate §B.2).
     */
    val QWEN_17B: List<ModelFile> = emptyList()

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

    /** Manifest per variant (V1): A = today's list, B = LiteRT set, Q = pinned 0.6B contract (T22b'),
     *  U2 = TEXT sets (T24b Gleis 3). */
    fun forVariant(variant: de.voicekeyboard.model.ModelVariant): List<ModelFile> =
        when (variant) {
            de.voicekeyboard.model.ModelVariant.ONNX_CPU -> ONNX
            de.voicekeyboard.model.ModelVariant.LITERT_CPU -> LITERT
            de.voicekeyboard.model.ModelVariant.QWEN3_ASR -> QWEN
            de.voicekeyboard.model.ModelVariant.QWEN3_ASR_17B -> QWEN_17B
            de.voicekeyboard.model.ModelVariant.BART_SPELLING_EN -> U2_EN
            de.voicekeyboard.model.ModelVariant.BART_SPELLING_DE -> U2_DE
        }

    /** T22b (PLAN_V0146 §2): base URL per set — the downloader gets its source
     *  per variant (A/B unchanged, QWEN = download server path). */
    fun baseUrlFor(variant: de.voicekeyboard.model.ModelVariant): String =
        when (variant) {
            de.voicekeyboard.model.ModelVariant.ONNX_CPU -> BASE_URL_ONNX
            de.voicekeyboard.model.ModelVariant.LITERT_CPU -> BASE_URL_LITERT
            de.voicekeyboard.model.ModelVariant.QWEN3_ASR -> BASE_URL_QWEN
            de.voicekeyboard.model.ModelVariant.QWEN3_ASR_17B -> BASE_URL_QWEN_17B
            de.voicekeyboard.model.ModelVariant.BART_SPELLING_EN -> BASE_URL_U2_EN
            de.voicekeyboard.model.ModelVariant.BART_SPELLING_DE -> BASE_URL_U2_DE
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
