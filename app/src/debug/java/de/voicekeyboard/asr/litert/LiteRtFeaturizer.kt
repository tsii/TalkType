package de.voicekeyboard.asr.litert

import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.Tensor
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * V8: featurizer wrapper for `parakeet_tdt_nemo_featurizer_int8_dynamic_litert.tflite`.
 *
 * Manifest (measured by the orchestrator from the official parakeet manifest):
 *  [0] raw_wav                    [1,121600] float32 (7.6 s @ 16 kHz, FIXED shape)
 *  [1] sample_mask                [1,121600] float32 (1.0 real, 0.0 padding)
 *  [2] frame_mask                 [1,760,1]  float32 (1.0 for frames with real audio)
 *  [3] feature_mean               [1,1,128]  float32
 *  [4] feature_inv_std            [1,1,128]  float32
 *  [5] pre_encode_feature_cache  [1,9,128]  float32 (features of the previous window boundary)
 *  [6] previous_sample            [1]        float32 (last sample of the previous window)
 *  OUT[0] input_features [1,769,128] float32 · OUT[1] log_mel [1,760,128] float32
 *
 * ENCODER states (for V9, only documented here): cache_last_channel
 * [1,24,190,1024] (~18.6 MB), cache_last_time [1,24,1024,4], cache_last_channel_len [1].
 *
 * Window rule: 121 600 samples fixed; shorter inputs are padded with ZERO PADDING,
 * sample_mask/frame_mask mark the real areas.
 *
 * V9 device finding (2026-10-29, tflite-runtime + ARM sample beckett.wav, sine,
 * silence): output 1 log_mel is DEAD (constant -16.636 for speech/sine/silence,
 * variance 0.0) — statistics derived from it are useless, it is used/asserted
 * NOWHERE. Output 0 input_features [1,769,128] is the LIVING feature stream
 * (variance 3.20 speech / 31.31 sine / 0.0 silence) and the input
 * for the encoder (V10). Statistics mode: NEUTRAL (mean 0/inv_std 1)
 * with the following JUSTIFICATION: the dataset statistics are missing (ARM
 * provides no public mean/inv_std values); fromMel would have to be derived
 * from the dead log_mel and is therefore worthless. V10 delivers the real values
 * afterwards.
 */
class LiteRtFeaturizer(file: File, numThreads: Int = 2) {

    companion object {
        const val WINDOW_SAMPLES = 121_600   // 7.6 s @ 16 kHz (fixed tensor shape)
        const val FRAME_HOP = 160            // 10 ms @ 16 kHz
        const val FRAME_COUNT = 760          // 121600 / 160
        const val FEATURE_DIM = 128
        const val CACHE_FRAMES = 9            // pre_encode_feature_cache
        const val INPUT_COUNT = 7
        const val OUTPUT_COUNT = 2
    }

    /** Result of a featurizer run: both outputs + shapes + frame mask. */
    data class Result(
        /** LIVING feature stream [769*128] (V9: the input for the encoder). */
        val inputFeatures: FloatArray,
        /** DEAD: constant -16.636 in the ARM artifact (device measurement 2026-10-29) —
         *  discarded, kept only for shape diagnostics. */
        val logMel: FloatArray,
        val inputFeaturesShape: IntArray, // [1,769,128]
        val logMelShape: IntArray,        // [1,760,128]
        val frameMask: BooleanArray,      // true = frame contains real audio
        val statsMode: String,            // V9: always "neutral" (see KDoc)
    )

    private val interpreter: Interpreter
    private val closed = java.util.concurrent.atomic.AtomicBoolean(false)

    init {
        check(file.isFile) { "Featurizer fehlt: ${file.name}" }
        val bytes = file.readBytes()
        val opts = Interpreter.Options().apply { setNumThreads(numThreads) }
        interpreter = Interpreter(
            ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder())
                .apply { put(bytes) }.apply { flip() },
            opts,
        )
        // check the shape contract (deviation = AsrNotReadyException with a reason text)
        val inputs = interpreter.getInputTensorCount()
        if (inputs != INPUT_COUNT) {
            interpreter.close()
            throw LiteRtAsrEngine.AsrNotReadyException(
                "Featurizer erwartet $INPUT_COUNT Eingänge, gefunden: $inputs",
            )
        }
        for (i in 0 until INPUT_COUNT) {
            val t = interpreter.getInputTensor(i)
            if (t.dataType() != DataType.FLOAT32) {
                interpreter.close()
                throw LiteRtAsrEngine.AsrNotReadyException(
                    "Eingang $i erwartet float32, gefunden: ${t.dataType()}",
                )
            }
        }
    }

    /** Names of the inputs at runtime (diagnostics, no contents — rule 9). */
    fun inputNames(): List<String> =
        (0 until INPUT_COUNT).map { i -> inputName(interpreter.getInputTensor(i)) }

    private fun inputName(t: Tensor): String =
        try {
            val m = Tensor::class.java.getMethod("name")
            m.invoke(t) as String
        } catch (e: Exception) {
            "input_${t.hashCode() % 1000}"
        }

    /**
     * Runs the featurizer inference. All 7 inputs are built from the single
     * input ([pcm] real up to [pcmLen], remainder zero padding):
     *  - raw_wav: pcm[0..pcmLen), rest 0
     *  - sample_mask: 1.0 for i<pcmLen, otherwise 0.0
     *  - frame_mask: 1.0 for floor(i/160) < ceil(pcmLen/160), otherwise 0.0
     *  - feature_mean/inv_std: NEUTRAL (V9; log_mel is dead, dataset values
     *    missing — V10 delivers real statistics afterwards).
     *  - pre_encode_feature_cache: prevCache or zeros
     *  - previous_sample: prevSample ?: 0
     */
    fun featurize(
        pcm: FloatArray,
        pcmLen: Int,
        prevCache: FloatArray? = null,
        prevSample: Float? = null,
    ): Result {
        check(!closed.get()) { "Featurizer geschlossen" }
        require(pcmLen in 0..WINDOW_SAMPLES) { "pcmLen $pcmLen außerhalb 0..$WINDOW_SAMPLES" }
        val rawWav = FloatArray(WINDOW_SAMPLES)
        val sampleMask = FloatArray(WINDOW_SAMPLES)
        System.arraycopy(pcm, 0, rawWav, 0, pcmLen)
        for (i in 0 until pcmLen) sampleMask[i] = 1f
        val realFrames = (pcmLen + FRAME_HOP - 1) / FRAME_HOP
        val frameMask = FloatArray(FRAME_COUNT)
        for (f in 0 until realFrames.coerceAtMost(FRAME_COUNT)) frameMask[f] = 1f

        val cache = FloatArray(CACHE_FRAMES * FEATURE_DIM)
        if (prevCache != null) {
            System.arraycopy(prevCache, 0, cache, 0, minOf(prevCache.size, cache.size))
        }
        val prevS = prevSample ?: 0f

        // V9: statistics mode NEUTRAL (mean 0/inv_std 1) — JUSTIFICATION: output 1
        // log_mel is CONSTANT -16.636 in the ARM artifact (device-measured, var=0),
        // fromMel would be worthless; dataset statistics missing (V10 delivers them later).
        // ONE run (no more double inference).
        val out = runOnce(rawWav, sampleMask, frameMask,
            FeaturizerStats.neutralMean(), FeaturizerStats.neutralInvStd(), cache, prevS)
        val maskBool = BooleanArray(FRAME_COUNT) { frameMask[it] > 0.5f }
        val mode = "neutral"
        return Result(
            inputFeatures = out.inputFeatures,
            logMel = out.logMel,
            inputFeaturesShape = out.inputFeaturesShape,
            logMelShape = out.logMelShape,
            frameMask = maskBool,
            statsMode = mode,
        )
    }

    /** One runForMultipleInputsOutputs run with the 7 input buffers. */
    private fun runOnce(
        rawWav: FloatArray, sampleMask: FloatArray, frameMask: FloatArray,
        mean: FloatArray, invStd: FloatArray, cache: FloatArray, prevSample: Float,
    ): Result {
        fun buf(a: FloatArray, shape: IntArray): ByteBuffer {
            val bb = ByteBuffer.allocateDirect(a.size * 4).order(ByteOrder.nativeOrder())
            for (v in a) bb.putFloat(v)
            bb.flip()
            return bb
        }
        fun flat(shape: IntArray): Int = shape.fold(1) { acc, v -> acc * (if (v > 0) v else 1) }

        // query the input shapes per tensor and form the buffers accordingly
        val inShapes = (0 until INPUT_COUNT).map { interpreter.getInputTensor(it).shape() }
        val inputs = arrayOf<Any>(
            buf(rawWav, inShapes[0]),
            buf(sampleMask, inShapes[1]),
            buf(frameMask, inShapes[2]),
            buf(mean, inShapes[3]),
            buf(invStd, inShapes[4]),
            buf(cache, inShapes[5]),
            buf(floatArrayOf(prevSample), inShapes[6]),
        )
        // output buffers in declared shapes (fixed shapes per the manifest)
        val ifShape = intArrayOf(1, FRAME_COUNT + 1, FEATURE_DIM) // [1,769,128]
        val lmShape = intArrayOf(1, FRAME_COUNT, FEATURE_DIM)      // [1,760,128]
        val outIf = ByteBuffer.allocateDirect(flat(ifShape) * 4).order(ByteOrder.nativeOrder())
        val outLm = ByteBuffer.allocateDirect(flat(lmShape) * 4).order(ByteOrder.nativeOrder())
        val outputs = mapOf(0 to outIf, 1 to outLm)
        try {
            interpreter.runForMultipleInputsOutputs(inputs, outputs)
        } catch (e: Exception) {
            // V9: make the original LiteRT message VISIBLE (previously swallowed),
            // but rule-9-scrubbed: per token shorten everything from '/' to the file name
            // (no path, no URL), max. 160 characters. append the cause.
            val scrubbed = e.message?.let { scrubForUser(it) } ?: "(keine Meldung)"
            val ex = LiteRtAsrEngine.AsrNotReadyException(
                "Featurizer-Inferenz fehlgeschlagen (${e.javaClass.simpleName}: $scrubbed)",
            )
            ex.initCause(e)
            throw ex
        }
        fun floats(bb: ByteBuffer, n: Int): FloatArray {
            val arr = FloatArray(n)
            bb.rewind()
            for (i in 0 until n) arr[i] = bb.float
            return arr
        }
        return Result(
            inputFeatures = floats(outIf, flat(ifShape)),
            logMel = floats(outLm, flat(lmShape)),
            inputFeaturesShape = ifShape,
            logMelShape = lmShape,
            frameMask = BooleanArray(0),
            statsMode = "run",
        )
    }

    fun close() {
        if (closed.compareAndSet(false, true)) interpreter.close()
    }

    /** V9/rule 9: scrub the message — tokens with '/'/'\\' shortened to the file name
     *  (no path, no URL), max. 160 characters. */
    private fun scrubForUser(msg: String): String {
        val sb = StringBuilder()
        for (tok in msg.split(Regex("[\\s]+"))) {
            if (sb.isNotEmpty()) sb.append(' ')
            val cut = if (tok.contains('/') || tok.contains('\\')) {
                tok.substringAfterLast('/').substringAfterLast('\\')
            } else tok
            sb.append(if (cut.isNotEmpty()) cut else tok)
        }
        return sb.toString().take(160)
    }
}
