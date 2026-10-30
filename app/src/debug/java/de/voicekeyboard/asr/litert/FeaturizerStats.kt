package de.voicekeyboard.asr.litert

/**
 * V8: Statistik für die Featurizer-Normalisierung (feature_mean/feature_inv_std).
 *
 * Die Arm-Pipeline verlangt beide Eingänge, aber es ist KEIN Herstellerwert
 * öffentlich. Zwei Modi:
 *  - [NEUTRAL]: mean 0 / inv_std 1 — identitätsnormalisierung (ANNAHME, als
 *    dokumentierter Fallback, KEINE erfundenen Konstanten).
 *  - [fromMel]: bildet Mittelwert/Streuung pro Feature-Dimension aus dem gerade
 *    berechneten log_mel (exakt NeMos `normalize_per_feature`): nach dem ersten
 *    run() werden die Statistiken aus log_mel gezogen und die Features damit
 *    neu skaliert — kein zweiter Inferenzlauf nötig.
 *
 * Produktionsverdrahtung (V8): [fromMel] — siehe LiteRtFeaturizer.
 */
object FeaturizerStats {

    /** Dimension des Feature-Vektors (log-mel bins der Parakeet-Pipeline). */
    const val FEATURE_DIM = 128

    /** Identitätsmodus (mean=0, inv_std=1) — dokumentierte ANNAHME, nur Fallback. */
    fun neutralMean(): FloatArray = FloatArray(FEATURE_DIM)
    fun neutralInvStd(): FloatArray = FloatArray(FEATURE_DIM) { 1f }

    /**
     * Statistik aus log_mel [frames][128]: Mittelwert und inverse Std-Abweichung
     * PRO Feature-Dimension (normalize_per_feature). Frames mit Mask 0 (Polster)
     * dürfen NICHT in die Statistik einfließen — Aufrufer filtert vorher.
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
