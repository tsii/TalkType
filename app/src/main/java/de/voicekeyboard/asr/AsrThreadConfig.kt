package de.voicekeyboard.asr

/**
 * S12: Reine, JVM-testbare ASR-Laufzeit-Konfiguration (keine Android-/ORT-Typen).
 *
 * Hebel 1 (CPU-EP-Threads): [forDevice] liefert das Thread-Setting; angewendet
 * über [applyTo] auf die ORT-SessionOptions (OnnxSessions übergibt den echten
 * Receiver; Tests nutzen ein Fake — kein ORT-Interna-Reflexion nötig).
 *
 * Hebel 2 (Streaming-Takt): chunkMs (Audio-Aggregation je Transkriptionslauf)
 * und hypothesisMinIntervalMs (Mindestabstand sichtbarer Hypothesen).
 *
 * [DEFAULT] = exakt die heutigen Ist-Werte, damit das Standardverhalten
 * identisch bleibt: intra = min(4, cores) → 4 auf üblichen Geräten, inter = 1,
 * chunkMs = 1000 (Coordinator-Tick TICK_SAMPLES_1S), hypothesisMinIntervalMs =
 * 2000 (MIN_AUDIO_SECONDS_PARTIALS).
 */
data class AsrThreadConfig(
    val intraOpThreads: Int,
    val interOpThreads: Int,
    val chunkMs: Int = DEFAULT_CHUNK_MS,
    val hypothesisMinIntervalMs: Int = DEFAULT_HYPOTHESIS_MIN_INTERVAL_MS,
) {

    /** Schnittstelle, auf die die Thread-Optionen gesetzt werden (Produktion: ORT). */
    interface OptionReceiver {
        fun setIntraOpThreads(n: Int)
        fun setInterOpThreads(n: Int)
    }

    /** Setzt genau die konfigurierten Werte (Belegtest: Fake-Receiver). */
    fun applyTo(receiver: OptionReceiver) {
        receiver.setIntraOpThreads(intraOpThreads)
        receiver.setInterOpThreads(interOpThreads)
    }

    /** Chunk-Grenzen in Samples (16 kHz) für totalSamples Audio. */
    fun chunkBoundaries(totalSamples: Int, sampleRateHz: Int = 16_000): List<Int> {
        val chunkSamples = chunkMs * sampleRateHz / 1000
        val out = mutableListOf<Int>()
        var b = chunkSamples
        while (b <= totalSamples) { out.add(b); b += chunkSamples }
        return out
    }

    /**
     * Hebel 2: Emissions-Zeitpunkte sichtbarer Hypothesen aus Tick-Zeitpunkten —
     * nie öfter als [hypothesisMinIntervalMs], deterministisch (erste nach dem
     * ersten vollen Intervall). Reine Logik, kein Audio.
     */
    fun hypothesisEmissionTimes(tickTimesMs: List<Int>): List<Int> {
        val out = mutableListOf<Int>()
        var last = Long.MIN_VALUE / 2 // Long-Basis: kein Int-Overflow beim ersten Tick
        for (t in tickTimesMs) {
            if (t - last >= hypothesisMinIntervalMs) { out.add(t); last = t.toLong() }
        }
        return out
    }

    companion object {
        const val DEFAULT_CHUNK_MS = 1000
        const val DEFAULT_HYPOTHESIS_MIN_INTERVAL_MS = 2000
        private const val MIN_THREADS = 2
        private const val MAX_THREADS = 6

        /**
         * S12-01-FIX: Geräte-RAM in MB, gesetzt von der einzigen Stelle mit
         * Context (IME-Service, ActivityManager.getMemoryInfo().totalMem) —
         * NIE der Java-Heap (maxMemory), der auf jedem Telefon 256-512 MB
         * beträgt und dadurch fälschlich die 2-GB-Dekkelung auslöste.
         * 0 = unbekannt ⇒ keine Reduktion. asr/ bleibt Context-frei.
         */
        @Volatile var deviceRamMb: Long = 0

        /** Standardwerte = heutiges Verhalten (intra=4 wie min(4,8-Kern-Gerät), inter=1). */
        val DEFAULT = AsrThreadConfig(intraOpThreads = 4, interOpThreads = 1)

        /**
         * Geräteabhängige Konfiguration: intra clamp 2..6 (heutige Formel
         * min(4, cores), bei wenig RAM (≤ 2 GB) gegen 2); inter bleibt 1.
         * ramMb = 0 (unbekannt) → keine weitere Reduktion.
         */
        fun forDevice(cores: Int, ramMb: Long): AsrThreadConfig {
            val safeCores = cores.coerceAtLeast(1)
            // TDD-Spezifikation (AsrThreadConfigTest): uebliche Geraete (bis ~8
            // Kerne) bleiben bei der heutigen Formel min(4, cores); sehr viele
            // Kerne (> 8) nutzen bis MAX 6; wenig RAM dekelt gegen MIN 2.
            var intra = if (safeCores <= 8) minOf(4, safeCores) else minOf(MAX_THREADS, safeCores)
            intra = intra.coerceIn(MIN_THREADS, MAX_THREADS)
            if (ramMb in 1..2047) intra = intra.coerceAtMost(MIN_THREADS)
            return AsrThreadConfig(intraOpThreads = intra, interOpThreads = 1)
        }
    }
}
