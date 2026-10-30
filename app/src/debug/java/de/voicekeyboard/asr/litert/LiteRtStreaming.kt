package de.voicekeyboard.asr.litert

import kotlin.math.sqrt

/**
 * V11a: Welford-Statistik über log_mel-Frames (ptdt.cpp:342
 * compute_global_feature_normalization) — reine JVM-Logik.
 *
 * var = m2 / max(1, n-1), feature_inv_std = 1/(sqrt(var) + 1e-5);
 * bei Stille (var 0) ist inv_std = 1/1e-5 begrenzt — kein NaN/Unendlich.
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

    /** Liefert (mean[dims], invStd[dims]); bei n==0 Neutral (0/1). */
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
 * Reine Fenster-Logik der B-Pipeline (ptdt.cpp:400-490), bewiesen gegen
 * tools/arm_litert_reference_decode.py. Keine Modelle, keine Android-Typen.
 */
object LiteRtStreaming {

    const val WINDOW_SAMPLES = 121_600
    const val FRAME_HOP = 160
    const val NUM_VALID_FRAMES = 760
    const val CACHE_FRAMES = 9
    const val FEAT_DIM = 128

    /** ptdt.cpp:458: input_length = 9 + gültige Feature-Frames. */
    fun inputLengthFor(validFrames: Int): Int = CACHE_FRAMES + validFrames

    /** ptdt.cpp:347: valid_feature_frames = min(760, max(0, samples/160)). */
    fun validFramesFor(samples: Int): Int =
        minOf(NUM_VALID_FRAMES, maxOf(0, samples / FRAME_HOP))

    /**
     * V14 (L14-01): Cache FÜR DAS FOLGENDE FENSTER aus den letzten
     * [CACHE_FRAMES] gültigen Frames des AKTUELLEN Fensters.
     *
     * Fenster-Ablauf (Vertrag, ptdt.cpp:400-490 — Reihenfolge ist verbindlich):
     *  (a) Featurizer-EINGANG von Fenster n: pre_encode_feature_cache = der
     *      Cache, den preEncodeCache(inputFeatures_{n-1}, valid_{n-1}) geliefert hat.
     *  (b) Featurizer erzeugt input_features_n = [9 Cache-Frames][valid_n] (flat,
     *      Länge (9+valid_n)*featureSize).
     *  (c) preEncodeCache(inputFeatures_n, valid_n) ⇒ Cache für Fenster n+1.
     *  (d) Encoder-Caches (Ausgang cache_last_channel/time/len) ⇒ Eingang des
     *      nächsten Encoder-Laufs.
     *
     * Vertragsbruch ⇒ IllegalArgumentException mit beiden Zahlen — kein stilles
     * Abschneiden, keine Index-Ausnahme.
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
     * Array-Variante (Zeilen = Frames) mit demselben harten Vertrag —
     * [feats] muss (9+validFrames) Zeilen haben (input_features-Layout).
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

    /** ptdt.cpp:400: previous_sample = audio[start-1] (start>0), sonst 0. */
    fun previousSampleAt(audio: FloatArray, start: Int): Float =
        if (start > 0 && start <= audio.size) audio[start - 1] else 0f
}
