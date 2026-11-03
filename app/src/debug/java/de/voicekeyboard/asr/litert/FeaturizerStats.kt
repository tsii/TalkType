package de.voicekeyboard.asr.litert

/**
 * V8: statistics for the featurizer normalization (feature_mean/feature_inv_std).
 *
 * The ARM pipeline requires both inputs, but there is NO manufacturer value
 * public. Two modes:
 *  - [NEUTRAL]: mean 0 / inv_std 1 — identity normalization (ASSUMPTION, as a
 *    documented fallback, NO invented constants).
 *  - [fromMel]: computes mean/spread per feature dimension from the just
 *    computed log_mel (exactly NeMo's `normalize_per_feature`): after the first
 *    run() the statistics are drawn from log_mel and the features are rescaled
 *    with them — no second inference run needed.
 *
 * Production wiring (V8): [fromMel] — see LiteRtFeaturizer.
 */
object FeaturizerStats {

    /** Dimension of the feature vector (log-mel bins of the Parakeet pipeline). */
    const val FEATURE_DIM = 128

    /** Identity mode (mean=0, inv_std=1) — documented ASSUMPTION, fallback only. */
    fun neutralMean(): FloatArray = FloatArray(FEATURE_DIM)
    fun neutralInvStd(): FloatArray = FloatArray(FEATURE_DIM) { 1f }

    /**
     * Statistics from log_mel [frames][128]: mean and inverse std deviation
     * PER feature dimension (normalize_per_feature). Frames with mask 0 (padding)
     * must NOT flow into the statistics — the caller filters beforehand.
     */
    fun fromMel(logMel: FloatArray, frames: Int, mask: BooleanArray): Pair<FloatArray, FloatArray> {
        val mean = FloatArray(FEATURE_DIM)
        val invStd = FloatArray(FEATURE_DIM) { 1f }
        var real = 0
        for (f in mask.indices) if (mask[f]) real++
        if (real == 0 || frames <= 0) return neutralMean() to neutralInvStd()
        for (d in 0 until FEATURE_DIM) {
            var sum = 0.0
            for (f in 0 until frames) if (mask.getOrNull(f) == true) sum += logMel[f * FEATURE_DIM + d]
            val m = (sum / real).toFloat()
            var sq = 0.0
            for (f in 0 until frames) if (mask.getOrNull(f) == true) {
                val v = (logMel[f * FEATURE_DIM + d] - m).toDouble()
                sq += v * v
            }
            val std = Math.sqrt(sq / real).toFloat()
            mean[d] = m
            invStd[d] = if (std > 1e-6f) 1f / std else 1f
        }
        return mean to invStd
    }
}
