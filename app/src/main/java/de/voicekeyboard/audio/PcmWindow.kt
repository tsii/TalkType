package de.voicekeyboard.audio

/**
 * Ein geschlossenes PCM16-Mono-Fenster (16 kHz), wie es AsrEngine.transcribe()
 * erhält. Kein Android-Typ, damit die ASR-Pipeline JVM-testbar bleibt.
 */
class PcmWindow(val samples: ShortArray, val sampleRate: Int) {

    init {
        require(sampleRate == SAMPLE_RATE_16K) {
            "PcmWindow expects ${SAMPLE_RATE_16K} Hz mono PCM16, got $sampleRate Hz — resampling is not supported here"
        }
    }

    /** Dauer des Fensters in Sekunden. */
    val audioSeconds: Double
        get() = if (sampleRate == 0) 0.0 else samples.size.toDouble() / sampleRate

    /** PCM16 → float32 [-1, 1] (32768-Nenner), wie es `waveforms` erwartet. */
    fun toFloatMono(): FloatArray {
        val out = FloatArray(samples.size)
        for (i in samples.indices) out[i] = samples[i] / 32768f
        return out
    }

    companion object {
        const val SAMPLE_RATE_16K = 16000
    }
}
