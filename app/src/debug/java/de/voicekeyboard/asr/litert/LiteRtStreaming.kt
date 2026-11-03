package de.voicekeyboard.asr.litert

import kotlin.math.sqrt

/**
 * V11a: Welford statistics over log_mel frames (ptdt.cpp:342
 * compute_global_feature_normalization) — pure JVM logic.
 *
 * var = m2 / max(1, n-1), feature_inv_std = 1/(sqrt(var) + 1e-5);
 * for silence (var 0) inv_std is bounded at 1/1e-5 — no NaN/infinity.
 */
class GlobalFeatureStats(private val dims: Int) {

    private val mean = DoubleArray(dims)
    private val m2 = DoubleArray(dims)
    private var n = 0L

    fun addFrame(frame: FloatArray) {
        require(frame.size == dims) { "Frame hat ${frame.size} Dim, erwartet $dims" }
        n++
        val count = n.toDouble()
        for (d in 0 until dims) {
            val value = frame[d].toDouble()
            val delta = value - mean[d]
            mean[d] += delta / count
            m2[d] += delta * (value - mean[d])
        }
    }

    /** Returns (mean[dims], invStd[dims]); for n==0 neutral (0/1). */
    fun finish(): Pair<FloatArray, FloatArray> {
        if (n == 0L) return FloatArray(dims) to FloatArray(dims) { 1f }
        val denom = maxOf(1L, n - 1).toDouble()
        val outMean = FloatArray(dims)
        val outInvStd = FloatArray(dims)
        for (d in 0 until dims) {
            outMean[d] = mean[d].toFloat()
            val variance = (m2[d] / denom).toFloat()
            val std = sqrt(variance)
            outInvStd[d] = 1f / (std + EPS)
        }
        return outMean to outInvStd
    }

    companion object {
        const val EPS = 1e-5f
    }
}

/**
 * Pure window logic of the B pipeline (ptdt.cpp:400-490), proven against
 * tools/arm_litert_reference_decode.py. No models, no Android types.
 */
object LiteRtStreaming {

    const val WINDOW_SAMPLES = 121_600
    const val FRAME_HOP = 160
    const val NUM_VALID_FRAMES = 760
    const val CACHE_FRAMES = 9
    const val FEAT_DIM = 128

    /** ptdt.cpp:458: input_length = 9 + valid feature frames. */
    fun inputLengthFor(validFrames: Int): Int = CACHE_FRAMES + validFrames

    /** ptdt.cpp:347: valid_feature_frames = min(760, max(0, samples/160)). */
    fun validFramesFor(samples: Int): Int =
        minOf(NUM_VALID_FRAMES, maxOf(0, samples / FRAME_HOP))

    /**
     * V14 (L14-01): cache FOR THE FOLLOWING WINDOW from the last
     * [CACHE_FRAMES] valid frames of the CURRENT window.
     *
     * Window flow (contract, ptdt.cpp:400-490 — the order is binding):
     *  (a) featurizer INPUT of window n: pre_encode_feature_cache = the
     *      cache that preEncodeCache(inputFeatures_{n-1}, valid_{n-1}) returned.
     *  (b) the featurizer produces input_features_n = [9 cache frames][valid_n] (flat,
     *      length (9+valid_n)*featureSize).
     *  (c) preEncodeCache(inputFeatures_n, valid_n) ⇒ cache for window n+1.
     *  (d) encoder caches (output cache_last_channel/time/len) ⇒ input of the
     *      next encoder run.
     *
     * Contract violation ⇒ IllegalArgumentException with both numbers — no silent
     * truncation, no index exception.
     */
    fun preEncodeCacheFlat(
        feats: FloatArray,
        validFrames: Int,
        featureSize: Int,
    ): Array<FloatArray> {
        val expected = (CACHE_FRAMES + validFrames) * featureSize
        if (feats.size != expected) {
            throw IllegalArgumentException(
                "preEncodeCache: feats.size=${feats.size} verletzt den Vertrag — " +
                    "erwartet (9+validFrames)*featureSize=$expected, erhalten ${feats.size}",
            )
        }
        val out = Array(CACHE_FRAMES) { FloatArray(featureSize) }
        val toCopy = minOf(CACHE_FRAMES, validFrames)
        if (toCopy <= 0) return out
        val src = CACHE_FRAMES + validFrames - toCopy
        for (i in 0 until toCopy) {
            System.arraycopy(
                feats, (src + i) * featureSize,
                out[CACHE_FRAMES - toCopy + i], 0, featureSize,
            )
        }
        return out
    }

    /**
     * Array variant (rows = frames) with the same hard contract —
     * [feats] must have (9+validFrames) rows (input_features layout).
     */
    fun preEncodeCache(feats: Array<FloatArray>, validFrames: Int, dims: Int): Array<FloatArray> {
        val expected = CACHE_FRAMES + validFrames
        if (feats.size != expected) {
            throw IllegalArgumentException(
                "preEncodeCache: ${feats.size} Zeilen verletzt den Vertrag — " +
                    "erwartet 9+validFrames=$expected, erhalten ${feats.size}",
            )
        }
        val out = Array(CACHE_FRAMES) { FloatArray(dims) }
        val toCopy = minOf(CACHE_FRAMES, validFrames)
        if (toCopy <= 0) return out
        val src = CACHE_FRAMES + validFrames - toCopy
        for (i in 0 until toCopy) {
            System.arraycopy(feats[src + i], 0, out[CACHE_FRAMES - toCopy + i], 0, dims)
        }
        return out
    }

    /** ptdt.cpp:400: previous_sample = audio[start-1] (start>0), otherwise 0. */
    fun previousSampleAt(audio: FloatArray, start: Int): Float =
        if (start > 0 && start <= audio.size) audio[start - 1] else 0f
}
