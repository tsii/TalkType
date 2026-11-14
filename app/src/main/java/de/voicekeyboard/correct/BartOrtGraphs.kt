package de.voicekeyboard.correct

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.File
import java.nio.FloatBuffer
import java.nio.LongBuffer

/**
 * T24c (Gleis 3 B3): the PRODUCTION graphs of [BartCorrectEngine] — the three
 * merged int8 sessions (BART_CORRECT_CONTRACT §3) from the ModelStore set tree.
 * ORT resolves the shared external-data blob ("shared/shared_weights.bin")
 * RELATIVE to the graph files — the B2 multi-segment store names keep the
 * tree intact. Idiom follows QwenGraphs (buffer-backed tensors, named
 * outputs); JVM-untestable (native ORT), the loop semantics are pinned in
 * BartCorrectEngineTest over fake graphs, end-to-end proof is T24j duty.
 * Never logs contents (rule 9).
 */
class BartOrtGraphs private constructor(
    private val env: OrtEnvironment,
    private val encoder: OrtSession,
    private val decoderInit: OrtSession,
    private val decoderStep: OrtSession,
) : BartCorrectEngine.BartGraphs {

    /** Shapes cached from the decoder_init outputs (cross_k [L,1,12,S,64]). */
    private var layers = 0L
    private var encLen = 0L

    override fun encode(inputIds: LongArray): FloatArray {
        val s = inputIds.size.toLong()
        val ids = OnnxTensor.createTensor(env, LongBuffer.wrap(inputIds), longArrayOf(1, s))
        val out = encoder.run(mapOf("input_ids" to ids))
        ids.close()
        try {
            val t = tensorOf(out, "last_hidden")
            val hidden = FloatArray(t.info.shape.drop(1).fold(1L) { a, b -> a * b }.toInt())
            t.floatBuffer.get(hidden)
            return hidden
        } finally {
            out.close()
        }
    }

    override fun decodeInit(startId: Long, encoderHidden: FloatArray): BartCorrectEngine.InitOut {
        val s = encLenOf(encoderHidden)
        val tensors = ArrayList<OnnxTensor>()
        val start = longTensor(tensors, longArrayOf(startId), longArrayOf(1, 1))
        val hidden = floatTensor(tensors, encoderHidden, longArrayOf(1, s, 768))
        val out = decoderInit.run(
            mapOf("decoder_start_ids" to start, "encoder_hidden" to hidden),
        )
        tensors.forEach { it.close() }
        try {
            val crossShape = tensorOf(out, "cross_k").info.shape
            layers = crossShape[0]
            encLen = crossShape[3]
            return BartCorrectEngine.InitOut(
                logits = readFloat(out, "logits"),
                selfK = readFloat(out, "self_k"),
                selfV = readFloat(out, "self_v"),
                crossK = readFloat(out, "cross_k"),
                crossV = readFloat(out, "cross_v"),
            )
        } finally {
            out.close()
        }
    }

    override fun decodeStep(
        tokenId: Long,
        position: Long,
        selfK: FloatArray,
        selfV: FloatArray,
        crossK: FloatArray,
        crossV: FloatArray,
    ): BartCorrectEngine.StepOut {
        val l = layers
        val s = encLen
        val p = if (l > 0) selfK.size / (l * 12L * 64L) else 0L
        check(l > 0 && s > 0 && p > 0) { "decodeStep before decodeInit" }
        val tensors = ArrayList<OnnxTensor>()
        val tok = longTensor(tensors, longArrayOf(tokenId), longArrayOf(1, 1))
        val pos = longTensor(tensors, longArrayOf(position), longArrayOf(1, 1))
        val sk = floatTensor(tensors, selfK, longArrayOf(l, 1, 12, p, 64))
        val sv = floatTensor(tensors, selfV, longArrayOf(l, 1, 12, p, 64))
        val ck = floatTensor(tensors, crossK, longArrayOf(l, 1, 12, s, 64))
        val cv = floatTensor(tensors, crossV, longArrayOf(l, 1, 12, s, 64))
        val out = decoderStep.run(
            mapOf(
                "input_ids" to tok,
                "position_ids" to pos,
                "self_k" to sk,
                "self_v" to sv,
                "cross_k" to ck,
                "cross_v" to cv,
            ),
        )
        tensors.forEach { it.close() }
        try {
            return BartCorrectEngine.StepOut(
                logits = readFloat(out, "logits"),
                selfK = readFloat(out, "self_k_out"),
                selfV = readFloat(out, "self_v_out"),
                crossK = crossK,
                crossV = crossV,
            )
        } finally {
            out.close()
        }
    }

    override fun close() {
        encoder.close()
        decoderInit.close()
        decoderStep.close()
    }

    // ------------------------------------------------------------ helpers

    private fun encLenOf(encoderHidden: FloatArray): Long = (encoderHidden.size / 768).toLong()

    private fun longTensor(sink: MutableList<OnnxTensor>, a: LongArray, shape: LongArray): OnnxTensor =
        OnnxTensor.createTensor(env, LongBuffer.wrap(a), shape).also { sink += it }

    private fun floatTensor(sink: MutableList<OnnxTensor>, a: FloatArray, shape: LongArray): OnnxTensor =
        OnnxTensor.createTensor(env, FloatBuffer.wrap(a), shape).also { sink += it }

    private fun tensorOf(result: OrtSession.Result, name: String): OnnxTensor {
        val value = result.get(name).orElse(null)
            ?: throw IllegalStateException("output tensor missing: $name")
        return value as OnnxTensor
    }

    private fun readFloat(result: OrtSession.Result, name: String): FloatArray {
        val t = tensorOf(result, name)
        val size = t.info.shape.fold(1L) { a, b -> a * b }.toInt()
        val out = FloatArray(size)
        t.floatBuffer.get(out)
        return out
    }

    companion object {
        const val FILE_ENCODER = "encoder.int8.onnx"
        const val FILE_DECODER_INIT = "decoder_init.int8.onnx"
        const val FILE_DECODER_STEP = "decoder_step.int8.onnx"

        fun load(modelDir: File): BartOrtGraphs {
            for (name in listOf(FILE_ENCODER, FILE_DECODER_INIT, FILE_DECODER_STEP)) {
                check(File(modelDir, name).isFile) { "model file missing: $name" } // rule 9: name only
            }
            val env = OrtEnvironment.getEnvironment()
            fun session(file: String): OrtSession {
                val opts = OrtSession.SessionOptions().apply {
                    de.voicekeyboard.asr.AsrThreadConfig.forDevice(
                        cores = Runtime.getRuntime().availableProcessors(),
                        ramMb = de.voicekeyboard.asr.AsrThreadConfig.deviceRamMb,
                    ).applyTo(
                        object : de.voicekeyboard.asr.AsrThreadConfig.OptionReceiver {
                            override fun setIntraOpThreads(n: Int) = setIntraOpNumThreads(n)
                            override fun setInterOpThreads(n: Int) = setInterOpNumThreads(n)
                        },
                    )
                    // Pin the CPU EP explicitly (like the ASR engines)
                    addCPU(false)
                    // CPU EP only (like the ASR engines); memory-conserving
                    // options per contract §9 — session ~0.4-0.5 GB.
                }
                return env.createSession(File(modelDir, file).absolutePath, opts)
            }
            return BartOrtGraphs(env, session(FILE_ENCODER), session(FILE_DECODER_INIT), session(FILE_DECODER_STEP))
        }
    }
}
