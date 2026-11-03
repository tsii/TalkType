package de.voicekeyboard.dictation

/**
 * F21: cost-aware decode pacing — PURE function, no clock, no Android.
 *
 * Core problem (C2, evidenced): the partial path decodes the COMPLETE buffer
 * so far on every run, for rule-2 reasons
 * (KONZEPT §5.1: no sliding window). The work per run therefore grows linearly
 * with the recording duration, the total work quadratically. The only allowed
 * lever is WHEN decoding happens — not what: the whole window stays, no new
 * state machine, no policy intervention.
 *
 * Rule: the next partial interval is double the last decode time
 * (safety factor 2: on average the decode may consume only half of real time),
 * clamped to [baseMs]/[maxMs]. A fast engine (runtime ≈
 * [baseMs]/2 or faster) gets [baseMs] — behavior as before.
 */
object DecodePace {

    /**
     * @param lastDecodeMs duration of the last decode run in ms (0 = none yet).
     * @param bufferMs    audio length so far in ms — plausibility only: if the
     *                    buffer is younger than [baseMs], a tighter pacing gains nothing.
     * @param baseMs      lower clamp (standard pacing, AsrThreadConfig).
     * @param maxMs       upper clamp — the pacing never grows without bound.
     * @return next partial interval in ms; defensively always in [min(base,max), max],
     *         if [baseMs] > [maxMs], [maxMs] applies; overflows are saturated.
     */
    fun intervalMs(lastDecodeMs: Long, bufferMs: Long, baseMs: Long, maxMs: Long): Long {
        val base = minOf(baseMs, maxMs) // defensive clamp: base > max ⇒ max applies
        if (bufferMs < base) return base
        // Safety factor 2, saturating: > MAX/2 would let *2 overflow.
        val doubled = if (lastDecodeMs > Long.MAX_VALUE / 2) Long.MAX_VALUE
        else (lastDecodeMs * 2).coerceAtLeast(0L) // negative measurements ⇒ base
        return doubled.coerceIn(base, maxMs)
    }
}
