package de.voicekeyboard.debug

/**
 * Holds the highest audio level seen during a dictation session.
 *
 * Pure JVM logic (no Android type), so that the display is proven without a device.
 * Rule 9: only a maximum value is kept — no samples,
 * no curves, no time series, nothing that would allow conclusions about contents.
 *
 * The input value range is linear 0..1 (see `AudioLevelListener`).
 */
class LevelPeak {

    @Volatile private var max: Float = 0f

    /**
     * Report the level of a block. Values outside the range or
     * non-numeric values are ignored (no crash in the diagnostics).
     */
    fun note(rms: Float) {
        if (!rms.isFinite() || rms <= 0f) return
        if (rms > max) max = if (rms > 1f) 1f else rms
    }

    /** Peak in percent (0..100), integer — exactly what the status row shows. */
    fun percent(): Int = (max * 100f).toInt().coerceIn(0, 100)

    /**
     * Peak in dBFS (<= 0) or null if no level was ever measured.
     * Why: peak_pct drops to 0..2 for a quiet recording and is not
     * resolvable there. -34 dBFS (phone finding) only becomes recognizable as a number.
     */
    fun dbfs(): Double? {
        if (max <= 0f) return null
        return (20.0 * kotlin.math.log10(max.toDouble())).coerceAtMost(0.0)
    }

    /** For the session start: forget the peak. */
    fun reset() {
        max = 0f
    }

    /**
     * Short form for the status row: "2 (-34 dB)" or "0". The percent value
     * alone is not distinguishable for a quiet recording (0 vs. 2 vs. 3),
     * the dBFS step makes the difference visible (phone finding -34 dBFS).
     */
    fun short(): String {
        val p = percent()
        val d = dbfs() ?: return p.toString()
        return "$p (${"%.0f".format(d)} dB)"
    }
}
