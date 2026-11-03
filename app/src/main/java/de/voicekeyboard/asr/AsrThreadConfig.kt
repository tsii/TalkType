package de.voicekeyboard.asr

/**
 * S12: pure, JVM-testable ASR runtime configuration (no Android/ORT types).
 *
 * Lever 1 (CPU EP threads): [forDevice] delivers the thread setting; applied
 * via [applyTo] to the ORT SessionOptions (OnnxSessions passes the real
 * receiver; tests use a fake — no ORT internals reflection needed).
 *
 * Lever 2 (streaming pace): chunkMs (audio aggregation per transcription run)
 * and hypothesisMinIntervalMs (minimum distance of visible hypotheses).
 *
 * [DEFAULT] = the deliberate standard values: intra = min(4, cores) → 4 on typical
 * devices, inter = 1, chunkMs = 2000 (F21: halved coordinator pace because of
 * quadratic re-decoding of the whole buffer — user approval 2026-10-30),
 * hypothesisMinIntervalMs = 2000 (MIN_AUDIO_SECONDS_PARTIALS),
 * decodePaceBaseMs/MaxMs = clamps of the cost-aware partial pace (F21/DecodePace).
 */
data class AsrThreadConfig(
    val intraOpThreads: Int,
    val interOpThreads: Int,
    val chunkMs: Int = DEFAULT_CHUNK_MS,
    val hypothesisMinIntervalMs: Int = DEFAULT_HYPOTHESIS_MIN_INTERVAL_MS,
    /** F21: lower clamp of the cost-aware partial pace (DecodePace.intervalMs). */
    val decodePaceBaseMs: Long = DEFAULT_DECODE_PACE_BASE_MS,
    /** F21: upper clamp of the cost-aware partial pace — the pace never grows beyond it. */
    val decodePaceMaxMs: Long = DEFAULT_DECODE_PACE_MAX_MS,
) {

    /** Interface onto which the thread options are set (production: ORT). */
    interface OptionReceiver {
        fun setIntraOpThreads(n: Int)
        fun setInterOpThreads(n: Int)
    }

    /** Sets exactly the configured values (proof test: fake receiver). */
    fun applyTo(receiver: OptionReceiver) {
        receiver.setIntraOpThreads(intraOpThreads)
        receiver.setInterOpThreads(interOpThreads)
    }

    /** Chunk boundaries in samples (16 kHz) for totalSamples audio. */
    fun chunkBoundaries(totalSamples: Int, sampleRateHz: Int = 16_000): List<Int> {
        val chunkSamples = chunkMs * sampleRateHz / 1000
        val out = mutableListOf<Int>()
        var b = chunkSamples
        while (b <= totalSamples) { out.add(b); b += chunkSamples }
        return out
    }

    /**
     * Lever 2: emission times of visible hypotheses from tick times —
     * never more often than [hypothesisMinIntervalMs], deterministic (first after the
     * first full interval). Pure logic, no audio.
     */
    fun hypothesisEmissionTimes(tickTimesMs: List<Int>): List<Int> {
        val out = mutableListOf<Int>()
        var last = Long.MIN_VALUE / 2 // Long basis: no Int overflow at the first tick
        for (t in tickTimesMs) {
            if (t - last >= hypothesisMinIntervalMs) { out.add(t); last = t.toLong() }
        }
        return out
    }

    companion object {
        // F21 (user approval 2026-10-30): 1000 -> 2000. Reason: every partial decodes
        // the whole buffer (KONZEPT §5.1, no sliding window) — the work per run
        // grows linearly with the duration, quadratically in total. Half pace = half the runs.
        const val DEFAULT_CHUNK_MS = 2000
        const val DEFAULT_HYPOTHESIS_MIN_INTERVAL_MS = 2000
        /** F21: pace clamps of the cost-aware decode pace (only WHEN, never WHAT). */
        const val DEFAULT_DECODE_PACE_BASE_MS = 2000L
        const val DEFAULT_DECODE_PACE_MAX_MS = 6000L
        private const val MIN_THREADS = 2
        private const val MAX_THREADS = 6

        /**
         * S12-01-FIX: device RAM in MB, set by the only place with
         * a context (IME service, ActivityManager.getMemoryInfo().totalMem) —
         * NEVER the Java heap (maxMemory), which on every phone amounts to 256-512 MB
         * and thereby falsely triggered the 2-GB capping.
         * 0 = unknown ⇒ no reduction. asr/ stays context-free.
         */
        @Volatile var deviceRamMb: Long = 0

        /** Default values = today's behavior (intra=4 like min(4, 8-core device), inter=1). */
        val DEFAULT = AsrThreadConfig(intraOpThreads = 4, interOpThreads = 1)

        /**
         * Device-dependent configuration: intra clamp 2..6 (today's formula
         * min(4, cores), with little RAM (≤ 2 GB) down to 2); inter stays 1.
         * ramMb = 0 (unknown) → no further reduction.
         */
        fun forDevice(cores: Int, ramMb: Long): AsrThreadConfig {
            val safeCores = cores.coerceAtLeast(1)
            // TDD specification (AsrThreadConfigTest): typical devices (up to ~8
            // cores) keep today's formula min(4, cores); very many
            // cores (> 8) use up to MAX 6; little RAM caps at MIN 2.
            var intra = if (safeCores <= 8) minOf(4, safeCores) else minOf(MAX_THREADS, safeCores)
            intra = intra.coerceIn(MIN_THREADS, MAX_THREADS)
            if (ramMb in 1..2047) intra = intra.coerceAtMost(MIN_THREADS)
            return AsrThreadConfig(intraOpThreads = intra, interOpThreads = 1)
        }
    }
}
