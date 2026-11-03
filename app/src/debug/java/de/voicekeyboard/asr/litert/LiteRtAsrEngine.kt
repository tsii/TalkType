package de.voicekeyboard.asr.litert

import de.voicekeyboard.asr.AsrEngine
import de.voicekeyboard.asr.AsrThreadConfig
import de.voicekeyboard.asr.Hypothesis
import de.voicekeyboard.audio.PcmWindow
import de.voicekeyboard.model.ModelManifest
import de.voicekeyboard.model.ModelVariant
import org.tensorflow.lite.Interpreter
import java.io.Closeable
import java.io.File
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicLong

/**
 * V3-SKELETON / V6a: LiteRT engine for variant B
 * (Arm/parakeet-tdt-0-6b-v3-int8-litert).
 *
 * - `load()` opens the four `.tflite` files from [ModelManifest.forVariant]
 *   (LITERT_CPU) with the LiteRT CPU interpreter (no NPU, no AOT).
 * - V6a: [featurize] runs a REAL inference on the featurizer —
 *   input shape/type are queried FROM THE INTERPRETER AT RUNTIME
 *   (not guessed): `getInputTensor(0)`, `shape()`, `dataType()`.
 * - The streaming cache is instance state and is DISCARDED on session change.
 * - `transcribe()` stays blocked in a typed way at this stage
 *   ([AsrNotReadyException]) — no silent empty-text return (rule A1).
 * - `close()` releases all interpreters.
 */
class LiteRtAsrEngine : AsrEngine, Closeable {

    /** Typed, deliberate error: inference step not wired at this stage. */
    class AsrNotReadyException(message: String) : IllegalStateException(message)

    private var interpreters: List<Interpreter> = emptyList()

    /** Featurizer interpreter (index in the manifest: parakeet_…_featurizer_…tflite). */
    private var featurizer: Interpreter? = null

    /** Streaming cache: valid only within ONE sessionId (session change ⇒ new). */
    private var cachedSessionId: Long = -1L
    private val cacheGeneration = AtomicLong(0)

    override fun load(modelDir: File) {
        check(interpreters.isEmpty()) { "AsrEngine.load() wurde bereits aufgerufen" }
        val files = ModelManifest.forVariant(ModelVariant.LITERT_CPU)
            .filter { it.storeName.endsWith(".tflite") }
        check(files.size == TFLITE_COUNT) {
            "Variante B erwartet $TFLITE_COUNT .tflite-Dateien, gefunden: ${files.size}"
        }
        val opened = ArrayList<Interpreter>(files.size)
        try {
            for (entry in files) {
                // V10 (L1-01): interpreter from the FILE via LiteRtLoader —
                // NO readBytes()/heap copy of the 648 MB encoder (mmap via
                // Interpreter(File, Options)); size check against the manifest
                // in the loader, BEFORE the open. Throwable boundary (errors too) in the loader
                // with a typed error + scrubbed cause.
                val loaded = LiteRtLoader.open(
                    File(modelDir, entry.storeName),
                    threadsForDevice(),
                    entry.sizeBytes,
                )
                opened.add(loaded.interpreter)
                if (entry.storeName.contains("featurizer")) {
                    featurizer = opened.last()
                }
            }
            interpreters = opened
        } catch (t: Throwable) {
            // SB1-03 pattern: on a follow-up error (errors too) close all already opened
            // interpreters — no half-open hybrid states.
            opened.forEach { runCatching { it.close() } }
            featurizer = null
            throw t
        }
    }

    /** True if load() succeeded (engine ready for featurize). */
    val isLoaded: Boolean
        get() = featurizer != null

    /**
     * V6a test access (androidTest): opens ONLY the featurizer from [file] —
     * for the single proof, without the other three B files. The production path
     * stays [load] (all four, size/name check against the manifest).
     */
    /**
     * V8: featurizer run via [LiteRtFeaturizer] — all 7 inputs from the
     * official ARM manifest (raw_wav, sample_mask, frame_mask, feature_mean,
     * feature_inv_std, pre_encode_feature_cache, previous_sample). The
     * 1-input assumption from V6a is replaced; AsrNotReadyException only for
     * real deviations (wrong tensor count, shape deviation from the manifest).
     */
    fun featurize(pcm: ShortArray, sampleRate: Int): IntArray =
        featurize(FloatArray(pcm.size) { pcm[it] / Short.MAX_VALUE.toFloat() }, pcm.size)

    fun featurize(
        pcm: FloatArray, pcmLen: Int,
        prevCache: FloatArray? = null, prevSample: Float? = null,
    ): IntArray {
        val f = featurizerHandle
            ?: throw AsrNotReadyException("Featurizer nicht geladen — load()/attachFeaturizer zuerst")
        return f.featurize(pcm, pcmLen, prevCache, prevSample).inputFeaturesShape
    }

    /** Full featurizer access (instrumented test / production V9). */
    fun featurizeFull(
        pcm: FloatArray, pcmLen: Int,
        prevCache: FloatArray? = null, prevSample: Float? = null,
    ): LiteRtFeaturizer.Result {
        val f = featurizerHandle
            ?: throw AsrNotReadyException("Featurizer nicht geladen — load()/attachFeaturizer zuerst")
        return f.featurize(pcm, pcmLen, prevCache, prevSample)
    }


    // ---- V11a: signature-based streaming pipeline (pre-pass + window) ----

    private var signatureFeaturizer: LiteRtSignature? = null
    private var signatureEncoder: LiteRtSignature? = null

    /** Encoder cache state (ptdt.cpp: pass the window on from window to window). */
    private var encCacheChannel = FloatArray(24 * 190 * 1024)
    private var encCacheTime = FloatArray(24 * 1024 * 4)
    private var encCacheLen = longArrayOf(0L)

    fun attachSignatureFeaturizer(file: File) {
        val m = LiteRtLoader.open(file, threadsForDevice())
        signatureFeaturizer = LiteRtSignature(m.interpreter)
    }

    fun attachSignatureEncoder(file: File) {
        val m = LiteRtLoader.open(file, threadsForDevice())
        signatureEncoder = LiteRtSignature(m.interpreter)
    }

    /**
     * ptdt.cpp:342: pre-pass over the WHOLE recording — Welford over log_mel
     * (NEUTRAL runs), returns (mean[128], invStd[128]).
     * MUST run before the first decode pre-pass (the encoder needs normalized
     * features; log_mel lives ONLY in the signature path — see LiteRtSignature KDoc).
     */
    fun precomputeGlobalStats(audio: FloatArray, length: Int): Pair<FloatArray, FloatArray> {
        val f = signatureFeaturizer
            ?: throw AsrNotReadyException("Signature-Featurizer fehlt — attachSignatureFeaturizer zuerst")
        val stats = GlobalFeatureStats(LiteRtStreaming.FEAT_DIM)
        var off = 0
        while (off < length) {
            val remaining = length - off
            if (remaining < LiteRtStreaming.FRAME_HOP) break
            val n = minOf(LiteRtStreaming.WINDOW_SAMPLES, remaining)
            val valid = LiteRtStreaming.validFramesFor(n)
            val out = runFeaturizerSignature(f, audio, off, n,
                FloatArray(LiteRtStreaming.FEAT_DIM), FloatArray(LiteRtStreaming.FEAT_DIM) { 1f },
                Array(LiteRtStreaming.CACHE_FRAMES) { FloatArray(LiteRtStreaming.FEAT_DIM) },
                0f)
            @Suppress("UNCHECKED_CAST")
            val logMel = out["log_mel"] as? Array<FloatArray>
                ?: throw AsrNotReadyException("log_mel fehlt im Ausgang: ${f.outputNames}")
            for (frame in 0 until valid) stats.addFrame(logMel[frame])
            off += n
        }
        return stats.finish()
    }

    /** One featurizer signature run with zero padding (ptdt.cpp:400-434). */
    private fun runFeaturizerSignature(
        f: LiteRtSignature, audio: FloatArray, start: Int, n: Int,
        mean: FloatArray, invStd: FloatArray,
        cache: Array<FloatArray>, prevSample: Float,
    ): Map<String, Any> {
        val raw = FloatArray(LiteRtStreaming.WINDOW_SAMPLES)
        val sampleMask = FloatArray(LiteRtStreaming.WINDOW_SAMPLES)
        System.arraycopy(audio, start, raw, 0, n)
        for (i in 0 until n) sampleMask[i] = 1f
        val valid = LiteRtStreaming.validFramesFor(n)
        val frameMask = FloatArray(LiteRtStreaming.NUM_VALID_FRAMES)
        for (i in 0 until valid) frameMask[i] = 1f
        return f.run(
            mapOf(
                "raw_wav" to arrayOf(raw),
                "sample_mask" to arrayOf(sampleMask),
                "frame_mask" to arrayOf(arrayOf(frameMask)),
                "feature_mean" to arrayOf(arrayOf(mean)),
                "feature_inv_std" to arrayOf(arrayOf(invStd)),
                "pre_encode_feature_cache" to arrayOf(cache),
                "previous_sample" to floatArrayOf(prevSample),
            ),
        )
    }

    /**
     * V11a window step (ptdt.cpp:458-505): featurizer (with statistics +
     * window cache + previous_sample), encoder with input_length = 9 + valid,
     * encoder caches passed on as inputs/outputs. Returns
     * (poolerOutput[95][1024], encoderLength).
     */
    fun encodeWindow(
        audio: FloatArray, length: Int, start: Int,
        mean: FloatArray, invStd: FloatArray,
        prevCache: Array<FloatArray>,
    ): Pair<Array<FloatArray>, Long> {
        val f = signatureFeaturizer
            ?: throw AsrNotReadyException("Signature-Featurizer fehlt")
        val e = signatureEncoder
            ?: throw AsrNotReadyException("Signature-Encoder fehlt — attachSignatureEncoder zuerst")
        val n = minOf(LiteRtStreaming.WINDOW_SAMPLES, length - start)
        val prevSample = LiteRtStreaming.previousSampleAt(audio, start)
        val out = runFeaturizerSignature(f, audio, start, n, mean, invStd, prevCache, prevSample)
        @Suppress("UNCHECKED_CAST")
        val feats = out["input_features"] as? Array<FloatArray>
            ?: throw AsrNotReadyException("input_features fehlt: ${f.outputNames}")
        val valid = LiteRtStreaming.validFramesFor(n)
        val inputLength = LiteRtStreaming.inputLengthFor(valid)
        val encOut = e.run(
            mapOf(
                "input_features" to arrayOf(feats),
                "input_length" to longArrayOf(inputLength.toLong()),
                "cache_last_channel" to arrayOf(arrayOf(arrayOf(encCacheChannel))),
                "cache_last_time" to arrayOf(arrayOf(arrayOf(encCacheTime))),
                "cache_last_channel_len" to encCacheLen,
            ),
        )
        @Suppress("UNCHECKED_CAST")
        val pool = encOut["encoder_pooler_output"] as? Array<FloatArray>
            ?: throw AsrNotReadyException("encoder_pooler_output fehlt: ${e.outputNames}")
        val encLen = when (val el = encOut["encoder_output_length"]) {
            is LongArray -> el[0]
            is Long -> el
            else -> throw AsrNotReadyException("encoder_output_length fehlt: ${e.outputNames}")
        }
        // update the caches (ptdt.cpp: NOT internally!)
        (encOut["cache_last_channel"] as? Array<Array<FloatArray>>)?.let {
            encCacheChannel = it[0][0]
        }
        (encOut["cache_last_time"] as? Array<Array<FloatArray>>)?.let {
            encCacheTime = it[0][0]
        }
        (encOut["cache_last_channel_len"] as? LongArray)?.let { encCacheLen = it }
        return pool to encLen
    }

    /** Cache for the next window from the last 9 valid frames (ptdt.cpp:470). */
    fun nextWindowCache(feats: Array<FloatArray>, validFrames: Int): Array<FloatArray> =
        LiteRtStreaming.preEncodeCache(feats, validFrames, LiteRtStreaming.FEAT_DIM)

    /** V8: featurizer wrapper; opens the file with the thread count from the config. */
    private var featurizerHandle: LiteRtFeaturizer? = null

    fun attachFeaturizer(file: File) {
        featurizerHandle?.close()
        featurizerHandle = LiteRtFeaturizer(file, threadsForDevice())
    }

    private fun threadsForDevice(): Int = AsrThreadConfig.forDevice(
        cores = Runtime.getRuntime().availableProcessors(),
        ramMb = AsrThreadConfig.deviceRamMb,
    ).intraOpThreads

    fun openFeaturizerOnly(file: File) {
        // V8: same path as production — the 7-input featurizer
        check(file.isFile) { "Featurizer fehlt: ${file.name}" }
        attachFeaturizer(file)
    }

    override suspend fun transcribe(window: PcmWindow, sessionId: Long, runId: Long): Hypothesis {
        check(interpreters.isNotEmpty()) {
            "AsrEngine nicht geladen — load() vor transcribe() aufrufen"
        }
        // Streaming cache: a session change discards the cache (V3/V6a invariant).
        if (cachedSessionId != sessionId) {
            cachedSessionId = sessionId
            cacheGeneration.incrementAndGet()
        }
        // Deliberately blocked until V4: typed error instead of empty text
        throw AsrNotReadyException(
            "LiteRT-Transkription in dieser Stufe nicht verdrahtet (V4) — Cache-Generation ${cacheGeneration.get()}",
        )
    }

    override fun close() {
        interpreters.forEach { runCatching { it.close() } }
        interpreters = emptyList()
        featurizer = null
        featurizerHandle?.close()
        featurizerHandle = null
        cachedSessionId = -1L
    }

    companion object {
        private const val TFLITE_COUNT = 4

        /** Test hook: cache generation (session change increments). */
        fun cacheGenerationOf(engine: LiteRtAsrEngine): Long = engine.cacheGeneration.get()
    }
}
