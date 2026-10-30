package de.voicekeyboard.model

/**
 * Verbindliche Modell-Dateiliste: Werte EXAKT aus docs/MODEL_SHA256.txt
 * und docs/ASR_CONTRACT.md §1 (Dateigrößen gemessen, Summe int8 ≈ 671 MB).
 *
 * Die Namen sind gleichzeitig die Whitelist für jeden Dateizugriff des
 * [ModelStore] (Pfad-Traversal-Schutz) und die Erwartung für die
 * SHA-256-Ende-zu-Ende-Prüfung des [ModelDownloader].
 */
/**
 * V5 (V1-01-Fix): Trennung von URL-Pfad und Dateisystem-Name.
 * [urlPath] = Pfad im CDN-Repo (darf Unterverzeichnisse enthalten, z. B.
 * "assets/token_vocab.bin"); [storeName] = flacher, traveralsicherer Name
 * im Modellverzeichnis. A: beide identisch (kein Re-Download).
 */
data class ModelFile(
    val storeName: String,
    val sizeBytes: Long,
    val sha256: String,
    val urlPath: String = storeName,
)

object ModelManifest {

    const val BASE_URL_ONNX = "https://huggingface.co/istupakov/parakeet-tdt-0.6b-v3-onnx/resolve/main/"

    /** Variante B (LiteRT, cached streaming) — Basis aus dem Arm-Repo. */
    const val BASE_URL_LITERT = "https://huggingface.co/Arm/parakeet-tdt-0-6b-v3-int8-litert/resolve/main/"

    /** Kompatibilitätsalias: heutige Einzelmenge = Variante A (unkontrollierte Aufrufer). */
    const val BASE_URL = BASE_URL_ONNX

    /** Variante A (ONNX_CPU): heutige Liste, unangetastet. */
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

    /** Variante B (LITERT_CPU): Arm/parakeet-tdt-0-6b-v3-int8-litert, 5 Dateien. */
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
            sizeBytes = 78_669L, // HF-API gemessen (V2-Fix)
            sha256 = "e5893bde019de49606a1543e45f12114e71fa0a7c0d27345af9576cf8f24f8fd",
        ),
    )

    /** Manifest je Variante (V1): A = heutige Liste, B = LiteRT-Set. */
    fun forVariant(variant: de.voicekeyboard.model.ModelVariant): List<ModelFile> =
        when (variant) {
            de.voicekeyboard.model.ModelVariant.ONNX_CPU -> ONNX
            de.voicekeyboard.model.ModelVariant.LITERT_CPU -> LITERT
        }

    /** Kompatibilität: DEFAULT bleibt Alias auf A. */
    val DEFAULT: List<ModelFile> = ONNX

    val TOTAL_BYTES: Long = DEFAULT.sumOf { it.sizeBytes }

    /** Gesamtgröße je Variante (V1: Platzbedarf für Setup-Anzeige). */
    fun totalBytes(variant: de.voicekeyboard.model.ModelVariant): Long =
        forVariant(variant).filter { it.sizeBytes > 0 }.sumOf { it.sizeBytes }

    /**
     * V5: Whitelist je Variante, bewusst TRAVERSAL-SCHARF: der Name muss flach
     * sein (kein '/', kein '\\', nicht "."/"..", nicht leer) und exakt dem
     * storeName der Variante entsprechen. urlPath-Präfixe sind hier NIEMALS
     * erlaubt (der storeName ist flach, nur der URL-Pfad darf tiefer liegen).
     */
    fun isAllowedName(storeName: String, variant: de.voicekeyboard.model.ModelVariant): Boolean =
        storeName.isNotEmpty() &&
            '/' !in storeName && '\\' !in storeName &&
            storeName != "." && storeName != ".." &&
            storeName in forVariant(variant).map { it.storeName }

    /** Kompatibilität (Default = Variante A). */
    fun isAllowedName(storeName: String): Boolean = isAllowedName(storeName, de.voicekeyboard.model.ModelVariant.ONNX_CPU)

    fun fileByName(name: String): ModelFile? = DEFAULT.firstOrNull { it.storeName == name }
}
