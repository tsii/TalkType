package de.voicekeyboard.asr

import ai.onnxruntime.NodeInfo
import ai.onnxruntime.OnnxJavaType
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import java.io.File
import kotlin.math.min

/**
 * Holds the three OrtSessions (mel frontend, conformer encoder, TDT decoder+joint)
 * from a directory and validates IO names/dtypes/ranks strictly against
 * [AsrContract] (docs/ASR_CONTRACT.md §2). Deviation → IllegalStateException
 * with a concrete message — no tolerance, no compensation.
 *
 * CPU EP explicitly (TECH_FACTS.md §3: NNAPI does not cover all Parakeet ops);
 * ALL_OPT optimization; conserve memory (ASR_CONTRACT §5: ~0.9 GB RSS).
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
         * Loads the three sessions from [dir] and checks all IO tensor contracts.
         * Missing files → IllegalStateException.
         */
        fun load(dir: File): OnnxSessions {
            val files = listOf(
                File(dir, FILE_MEL),
                File(dir, FILE_ENCODER),
                File(dir, FILE_DECODER),
            )
            for (f in files) {
                check(f.isFile) { "model file missing: ${f.name}" } // rule 9: only the name, never the path
            }
            val env = OrtEnvironment.getEnvironment()
            val opts = OrtSession.SessionOptions().apply {
                // S12 lever 1: thread setting via the pure, testable config
                // (AsrThreadConfig.forDevice; defaults = today's actual values).
                // S12-01-FIX: ramMb = DEVICE RAM (deviceRamMb, set by the IME service
                // from ActivityManager) — NEVER Runtime.maxMemory (Java heap).
                AsrThreadConfig.forDevice(
                    cores = Runtime.getRuntime().availableProcessors(),
                    ramMb = AsrThreadConfig.deviceRamMb,
                ).applyTo(object : AsrThreadConfig.OptionReceiver {
                    override fun setIntraOpThreads(n: Int) = setIntraOpNumThreads(n)
                    override fun setInterOpThreads(n: Int) = setInterOpNumThreads(n)
                })
                // Pin the CPU EP explicitly (default, here against future defaults)
                addCPU(false)
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                // Conserve memory: no memory pattern preallocation, no prepacking
                setMemoryPatternOptimization(false)
                addConfigEntry("session.disable_prepacking", "true")
            }
            opts.use { o ->
                // SB1-03: close already created sessions on a follow-up failure
                // (encoder ≈ 848 MB native — no partial leak on a corrupt model).
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
                    return s // ownership goes to the caller — finally then closes nothing
                } finally {
                    if (!success) opened.forEach { it.close() }
                }
            }
        }

        /** Dtype name for error messages. */
        private fun OnnxJavaType.pretty(): String = this.toString()
    }

    // ---- Validation (§2): name, dtype, rank strict, no extra tensors ----

    private fun checkIo(model: String, nodes: Map<String, NodeInfo>, direction: String,
                        expected: List<Triple<String, OnnxJavaType, Int>>) {
        val expectedNames = expected.map { it.first }.toSet()
        for ((name, exp) in expectedNames.zip(expected)) {
            val node = nodes[name]
                ?: throw IllegalStateException(
                    "$model: $direction tensor '$name' missing — present: ${nodes.keys.sorted()}")
            val info = node.info
            if (info !is TensorInfo) {
                throw IllegalStateException(
                    "$model: $direction tensor '$name' is not a tensor: $info")
            }
            if (info.type != exp.second) {
                throw IllegalStateException(
                    "$model: $direction tensor '$name' has dtype ${info.type.pretty()}, expected ${exp.second.pretty()}")
            }
            if (info.shape.size != exp.third) {
                throw IllegalStateException(
                    "$model: $direction tensor '$name' has rank ${info.shape.size}, expected ${exp.third} (Shape=${info.shape.contentToString()})")
            }
        }
        for (extra in nodes.keys) {
            if (extra !in expectedNames) {
                throw IllegalStateException(
                    "$model: unexpected $direction tensor '$extra' — contract knows only ${expectedNames.sorted()}")
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
