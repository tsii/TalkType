package de.voicekeyboard.asr.qwen

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import de.voicekeyboard.asr.AsrThreadConfig
import java.io.File
import java.nio.FloatBuffer
import java.nio.LongBuffer

/**
 * T22f2 (PLAN_V0146 §6; QWEN_ASR_CONTRACT §4): thin graph seam of the Qwen
 * engine — pure data in/out, NO decoding logic, so the decode core
 * ([QwenAsrEngine]) is JVM-testable with fakes (no onnxruntime needed).
 *
 * KV layout §4.2/§4.3 (T22-fix, emulator 5566): the graphs carry 16 REPEATED
 * heads — prefill returns past_k/past_v [28,1,16,L,128], step consumes
 * [28,1,16,P,128] and returns [28,1,16,P+1,128]. The GQA repeat 16->8
 * happens INSIDE the graph; the binding NEVER assumes a head count — the
 * dimensions are read from the PREFILL OUTPUT TENSOR SHAPE at runtime.
 *
 * T22-oom-fix 2 (3rd emulator run): Dalvik counts OUR direct ByteBuffers
 * against the same growth limit (137 MB allocateDirect died at 604 MB limit)
 * — so the pool owns NOTHING. Each KV generation lives in ORT-ALLOCATED
 * native memory ([NativeKv] holds the OrtSession.Result + its output
 * tensors); the step inputs BORROW the previous generation's direct views
 * (OnnxTensor.createTensor over a direct FloatBuffer — zero copy, valid
 * while the previous result stays open), the old generation is closed
 * deterministically AFTER the run (result.close(), no Cleaner). Exactly TWO
 * generations are alive at any time (~156 MB native at the 15 s segment
 * cap); the Java heap never holds KV data.
 */
internal interface QwenGraphs : AutoCloseable {
    /** One KV generation handle — ORT-owned native memory; [close] frees it. */
    interface KvCache : AutoCloseable

    /** input_features [1,128,frames] + block mask [1,1,S,S] -> audio_embeds [S,1024]. */
    fun encode(inputFeatures: FloatArray, frames: Int, attnMask: FloatArray, s: Int): FloatArray

    /** Prompt ids [1,L] + audio_embeds [1,S,1024] -> logits[last] + KV generation of length L. */
    fun prefill(promptIds: LongArray, audioEmbeds: FloatArray, s: Int): PrefillOut

    /** 1-token decode step on the KV generation at length [pastLen] -> next logits + generation. */
    fun step(id: Long, kv: KvCache, pastLen: Int): StepOut
}

internal class PrefillOut(val logits: FloatArray, val kv: QwenGraphs.KvCache)

internal class StepOut(val logits: FloatArray, val kv: QwenGraphs.KvCache)

/** One KV generation: ORT-owned result + output tensors ([close] frees both). */
internal class NativeKv(
    val result: OrtSession.Result,
    val k: OnnxTensor,
    val v: OnnxTensor,
    val layers: Int,
    val heads: Int,
    val headDim: Int,
) : QwenGraphs.KvCache {
    override fun close() {
        result.close() // frees the native memory of BOTH output tensors
    }
}

/**
 * onnxruntime binding of the three int8 graphs (session options like
 * Parakeet's OnnxSessions: AsrThreadConfig threads, CPU EP, ALL_OPT,
 * memory-conserving flags). The causal prefill mask and position ids are
 * built HERE — the engine stays free of tensor plumbing.
 */
internal class OrtQwenGraphs private constructor(
    private val env: OrtEnvironment,
    private val encoder: OrtSession,
    private val prefillSession: OrtSession,
    private val stepSession: OrtSession,
) : QwenGraphs {

    companion object {
        const val FILE_ENCODER = "encoder.int8.onnx"
        const val FILE_PREFILL = "qwen_prefill.int8.onnx"
        const val FILE_STEP = "qwen_step.int8.onnx"

        private const val NEG_INF = -1e9f
        private const val LOGITS = 151_936
        private const val PROJECTOR_DIM = 1024
        private const val MEL_BINS = 128

        fun load(dir: File): OrtQwenGraphs {
            val files = listOf(FILE_ENCODER, FILE_PREFILL, FILE_STEP).map { File(dir, it) }
            for (f in files) check(f.isFile) { "model file missing: ${f.name}" } // rule 9: name only
            val env = OrtEnvironment.getEnvironment()
            val opts = OrtSession.SessionOptions().apply {
                AsrThreadConfig.forDevice(
                    cores = Runtime.getRuntime().availableProcessors(),
                    ramMb = AsrThreadConfig.deviceRamMb,
                ).applyTo(object : AsrThreadConfig.OptionReceiver {
                    override fun setIntraOpThreads(n: Int) = setIntraOpNumThreads(n)
                    override fun setInterOpThreads(n: Int) = setInterOpNumThreads(n)
                })
                addCPU(false)
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                setMemoryPatternOptimization(false)
                addConfigEntry("session.disable_prepacking", "true")
            }
            opts.use { o ->
                val opened = ArrayList<OrtSession>(3)
                var success = false
                try {
                    opened.add(env.createSession(files[0].absolutePath, o))
                    opened.add(env.createSession(files[1].absolutePath, o))
                    opened.add(env.createSession(files[2].absolutePath, o))
                    success = true
                    return OrtQwenGraphs(env, opened[0], opened[1], opened[2])
                } finally {
                    if (!success) opened.forEach { it.close() }
                }
            }
        }
    }

    override fun encode(inputFeatures: FloatArray, frames: Int, attnMask: FloatArray, s: Int): FloatArray {
        val tensors = ArrayList<OnnxTensor>(2)
        try {
            val features = tensor(tensors, inputFeatures, longArrayOf(1, MEL_BINS.toLong(), frames.toLong()))
            val mask = tensor(tensors, attnMask, longArrayOf(1, 1, s.toLong(), s.toLong()))
            return encoder.run(
                mapOf("input_features" to features, "attn_mask" to mask),
            ).use { result -> copyOut(result, "audio_embeds", s * PROJECTOR_DIM) }
        } finally {
            tensors.forEach { it.close() }
        }
    }

    override fun prefill(promptIds: LongArray, audioEmbeds: FloatArray, s: Int): PrefillOut {
        val l = promptIds.size
        // causal additive mask: 0 at/below the diagonal, -1e9 above (§4.2)
        val causal = FloatArray(l * l)
        for (row in 0 until l) {
            for (col in (row + 1) until l) causal[row * l + col] = NEG_INF
        }
        val tensors = ArrayList<OnnxTensor>(4)
        try {
            val ids = longTensor(tensors, promptIds, longArrayOf(1, l.toLong()))
            val embeds = tensor(tensors, audioEmbeds, longArrayOf(1, s.toLong(), PROJECTOR_DIM.toLong()))
            val mask = tensor(tensors, causal, longArrayOf(1, 1, l.toLong(), l.toLong()))
            val positions = longTensor(tensors, LongArray(l) { it.toLong() }, longArrayOf(1, l.toLong()))
            // NO .use{} here: the RESULT stays open — its native past_k/past_v
            // memory IS the first KV generation (ownership moves to the handle).
            val result = prefillSession.run(
                mapOf(
                    "input_ids" to ids,
                    "audio_embeds" to embeds,
                    "attn_mask" to mask,
                    "position_ids" to positions,
                ),
            )
            val logits = copyOut(result, "logits", LOGITS)
            val kTensor = tensorOf(result, "past_k")
            val vTensor = tensorOf(result, "past_v")
            // T22-fix: dimensions from the OUTPUT TENSOR SHAPE (runtime truth,
            // symbolic in the graph) — never assumed.
            val shape = (kTensor.info as TensorInfo).shape
            require(shape.size == 5 && shape[1] == 1L && shape[3] == l.toLong()) {
                "past_k shape unexpected: ${shape.contentToString()} (L=$l)"
            }
            val kv = NativeKv(
                result, kTensor, vTensor,
                layers = shape[0].toInt(),
                heads = shape[2].toInt(),
                headDim = shape[4].toInt(),
            )
            return PrefillOut(logits, kv)
        } finally {
            tensors.forEach { it.close() } // inputs only — the result survives
        }
    }

    override fun step(id: Long, kv: QwenGraphs.KvCache, pastLen: Int): StepOut {
        val prev = kv as NativeKv
        val kvShape = longArrayOf(
            prev.layers.toLong(), 1, prev.heads.toLong(),
            pastLen.toLong(), prev.headDim.toLong(),
        )
        val tensors = ArrayList<OnnxTensor>(5)
        try {
            // INPUT tensors BORROW the previous generation's direct views
            // (ORT-owned memory, zero Java-side allocation, no FloatArray) —
            // valid while the previous result stays open (closed below,
            // AFTER the run consumed the inputs).
            val ids = longTensor(tensors, longArrayOf(id), longArrayOf(1, 1))
            val pk = OnnxTensor.createTensor(env, prev.k.floatBuffer, kvShape).also { tensors += it }
            val pv = OnnxTensor.createTensor(env, prev.v.floatBuffer, kvShape).also { tensors += it }
            val mask = tensor(tensors, FloatArray(pastLen + 1), longArrayOf(1, 1, 1, (pastLen + 1).toLong()))
            val positions = longTensor(tensors, longArrayOf(pastLen.toLong()), longArrayOf(1, 1))
            val result = stepSession.run(
                mapOf(
                    "input_ids" to ids,
                    "past_k" to pk,
                    "past_v" to pv,
                    "attn_mask" to mask,
                    "position_ids" to positions,
                ),
            )
            val logits = copyOut(result, "logits", LOGITS)
            val next = NativeKv(
                result,
                tensorOf(result, "past_k_out"),
                tensorOf(result, "past_v_out"),
                prev.layers, prev.heads, prev.headDim,
            )
            val out = StepOut(logits, next)
            return out
        } finally {
            tensors.forEach { it.close() } // inputs first (borrowed wrappers)
            prev.close() // OLD generation freed deterministically (ORT-owned)
        }
    }

    override fun close() {
        try {
            encoder.close()
        } finally {
            try {
                prefillSession.close()
            } finally {
                stepSession.close()
            }
        }
        // KV generations are NOT held here — the engine closes its handle
        // after the decode (KvCache.close -> result.close), nothing leaks.
    }

    // ---- tensor plumbing (no logic) ----

    private fun tensor(sink: MutableList<OnnxTensor>, a: FloatArray, shape: LongArray): OnnxTensor =
        OnnxTensor.createTensor(env, FloatBuffer.wrap(a), shape).also { sink += it }

    private fun longTensor(sink: MutableList<OnnxTensor>, a: LongArray, shape: LongArray): OnnxTensor =
        OnnxTensor.createTensor(env, LongBuffer.wrap(a), shape).also { sink += it }

    private fun tensorOf(result: OrtSession.Result, name: String): OnnxTensor {
        val value = result.get(name).orElse(null)
            ?: throw IllegalStateException("output tensor '$name' missing in result")
        return value as OnnxTensor
    }

    private fun copyOut(result: OrtSession.Result, name: String, size: Int): FloatArray {
        val tensor = tensorOf(result, name)
        val out = FloatArray(size)
        tensor.floatBuffer.get(out)
        return out
    }
}
