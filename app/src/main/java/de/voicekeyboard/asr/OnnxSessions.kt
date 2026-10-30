package de.voicekeyboard.asr

import ai.onnxruntime.NodeInfo
import ai.onnxruntime.OnnxJavaType
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import java.io.File
import kotlin.math.min

/**
 * Hält die drei OrtSessions (Mel-Frontend, Conformer-Encoder, TDT-Decoder+Joint)
 * aus einem Verzeichnis und validiert IO-Namen/Dtypes/Ränge strikt gegen
 * [AsrContract] (docs/ASR_CONTRACT.md §2). Abweichung → IllegalStateException
 * mit konkreter Meldung — keine Toleranz, keine Kompensation.
 *
 * CPU-EP explizit (TECH_FACTS.md §3: NNAPI deckt nicht alle Parakeet-Ops ab);
 * ALL_OPT-Optimierung; Speicher schonen (ASR_CONTRACT §5: ~0,9 GB RSS).
 */
class OnnxSessions private constructor(
    val mel: OrtSession,
    val encoder: OrtSession,
    val decoder: OrtSession,
) : AutoCloseable {

    companion object {
        const val FILE_MEL = "nemo128.onnx"
        const val FILE_ENCODER = "encoder-model.int8.onnx"
        const val FILE_DECODER = "decoder_joint-model.int8.onnx"

        /**
         * Lädt die drei Sessions aus [dir] und prüft alle IO-Tensor-Verträge.
         * Fehlende Dateien → IllegalStateException.
         */
        fun load(dir: File): OnnxSessions {
            val files = listOf(
                File(dir, FILE_MEL),
                File(dir, FILE_ENCODER),
                File(dir, FILE_DECODER),
            )
            for (f in files) {
                check(f.isFile) { "Modell-Datei fehlt: ${f.name}" } // Regel 9: nur der Name, nie der Pfad
            }
            val env = OrtEnvironment.getEnvironment()
            val opts = OrtSession.SessionOptions().apply {
                // S12 Hebel 1: Thread-Setting ueber die reine, testbare Konfig
                // (AsrThreadConfig.forDevice; Defaults = heutige Ist-Werte).
                // S12-01-FIX: ramMb = GERAETE-RAM (deviceRamMb, vom IME-Service
                // aus ActivityManager gesetzt) — NIE Runtime.maxMemory (Java-Heap).
                AsrThreadConfig.forDevice(
                    cores = Runtime.getRuntime().availableProcessors(),
                    ramMb = AsrThreadConfig.deviceRamMb,
                ).applyTo(object : AsrThreadConfig.OptionReceiver {
                    override fun setIntraOpThreads(n: Int) = setIntraOpNumThreads(n)
                    override fun setInterOpThreads(n: Int) = setInterOpNumThreads(n)
                })
                // CPU-EP explizit festnageln (Default, hier gegen künftige Defaults)
                addCPU(false)
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                // Speicher schonen: keine Memory-Pattern-Preallokation, kein Prepacking
                setMemoryPatternOptimization(false)
                addConfigEntry("session.disable_prepacking", "true")
            }
            opts.use { o ->
                // SB1-03: bereits erzeugte Sessions bei Folgefehler schließen
                // (Encoder ≈ 848 MB nativ — kein partieller Leak bei korruptem Modell).
                val opened = ArrayList<OrtSession>(3)
                var success = false
                try {
                    opened.add(env.createSession(files[0].absolutePath, o))
                    opened.add(env.createSession(files[1].absolutePath, o))
                    opened.add(env.createSession(files[2].absolutePath, o))
                    val s = OnnxSessions(opened[0], opened[1], opened[2])
                    s.validateMel()
                    s.validateEncoder()
                    s.validateDecoder()
                    success = true
                    return s // Besitz geht an den Aufrufer — finally schließt dann nichts
                } finally {
                    if (!success) opened.forEach { it.close() }
                }
            }
        }

        /** Dtype-Name für Fehlermeldungen. */
        private fun OnnxJavaType.pretty(): String = this.toString()
    }

    // ---- Validierung (§2): Name, Dtype, Rang strikt, keine Extra-Tensoren ----

    private fun checkIo(model: String, nodes: Map<String, NodeInfo>, direction: String,
                        expected: List<Triple<String, OnnxJavaType, Int>>) {
        val expectedNames = expected.map { it.first }.toSet()
        for ((name, exp) in expectedNames.zip(expected)) {
            val node = nodes[name]
                ?: throw IllegalStateException(
                    "$model: $direction-Tensor '$name' fehlt — vorhanden: ${nodes.keys.sorted()}")
            val info = node.info
            if (info !is TensorInfo) {
                throw IllegalStateException(
                    "$model: $direction-Tensor '$name' ist kein Tensor: $info")
            }
            if (info.type != exp.second) {
                throw IllegalStateException(
                    "$model: $direction-Tensor '$name' hat Dtype ${info.type.pretty()}, erwartet ${exp.second.pretty()}")
            }
            if (info.shape.size != exp.third) {
                throw IllegalStateException(
                    "$model: $direction-Tensor '$name' hat Rang ${info.shape.size}, erwartet ${exp.third} (Shape=${info.shape.contentToString()})")
            }
        }
        for (extra in nodes.keys) {
            if (extra !in expectedNames) {
                throw IllegalStateException(
                    "$model: unerwarteter $direction-Tensor '$extra' — Vertrag kennt nur ${expectedNames.sorted()}")
            }
        }
    }

    private fun validateMel() {
        val i = mel.inputInfo
        val o = mel.outputInfo
        checkIo(FILE_MEL, i, "IN", listOf(
            Triple(AsrContract.MEL_IN_WAVEFORMS, OnnxJavaType.FLOAT, 2),
            Triple(AsrContract.MEL_IN_WAVEFORMS_LENS, OnnxJavaType.INT64, 1),
        ))
        checkIo(FILE_MEL, o, "OUT", listOf(
            Triple(AsrContract.MEL_OUT_FEATURES, OnnxJavaType.FLOAT, 3),
            Triple(AsrContract.MEL_OUT_FEATURES_LENS, OnnxJavaType.INT64, 1),
        ))
    }

    private fun validateEncoder() {
        checkIo(FILE_ENCODER, encoder.inputInfo, "IN", listOf(
            Triple(AsrContract.ENC_IN_AUDIO_SIGNAL, OnnxJavaType.FLOAT, 3),
            Triple(AsrContract.ENC_IN_LENGTH, OnnxJavaType.INT64, 1),
        ))
        checkIo(FILE_ENCODER, encoder.outputInfo, "OUT", listOf(
            Triple(AsrContract.ENC_OUT_OUTPUTS, OnnxJavaType.FLOAT, 3),
            Triple(AsrContract.ENC_OUT_ENCODED_LENGTHS, OnnxJavaType.INT64, 1),
        ))
    }

    private fun validateDecoder() {
        checkIo(FILE_DECODER, decoder.inputInfo, "IN", listOf(
            Triple(AsrContract.DEC_IN_ENCODER_OUTPUTS, OnnxJavaType.FLOAT, 3),
            Triple(AsrContract.DEC_IN_TARGETS, OnnxJavaType.INT32, 2),
            Triple(AsrContract.DEC_IN_TARGET_LENGTH, OnnxJavaType.INT32, 1),
            Triple(AsrContract.DEC_IN_INPUT_STATES_1, OnnxJavaType.FLOAT, 3),
            Triple(AsrContract.DEC_IN_INPUT_STATES_2, OnnxJavaType.FLOAT, 3),
        ))
        checkIo(FILE_DECODER, decoder.outputInfo, "OUT", listOf(
            Triple(AsrContract.DEC_OUT_OUTPUTS, OnnxJavaType.FLOAT, 4),
            Triple(AsrContract.DEC_OUT_PREDNET_LENGTHS, OnnxJavaType.INT32, 1),
            Triple(AsrContract.DEC_OUT_OUTPUT_STATES_1, OnnxJavaType.FLOAT, 3),
            Triple(AsrContract.DEC_OUT_OUTPUT_STATES_2, OnnxJavaType.FLOAT, 3),
        ))
    }

    override fun close() {
        try {
            mel.close()
        } finally {
            try {
                encoder.close()
            } finally {
                decoder.close()
            }
        }
    }
}
