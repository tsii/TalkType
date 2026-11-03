package de.voicekeyboard.audio

/**
 * A closed PCM16 mono window (16 kHz), as AsrEngine.transcribe() receives it.
 * No Android type, so that the ASR pipeline stays JVM-testable.
 */
class PcmWindow(val samples: ShortArray, val sampleRate: Int) {

    init {
        require(sampleRate == SAMPLE_RATE_16K) {
            "PcmWindow expects ${SAMPLE_RATE_16K} Hz mono PCM16, got $sampleRate Hz — resampling is not supported here"
        }
    }

    /** Duration of the window in seconds. */
    val audioSeconds: Double
        get() = if (sampleRate == 0) 0.0 else samples.size.toDouble() / sampleRate

    /** PCM16 → float32 [-1, 1] (32768 divisor), as expected by `waveforms`. */
    fun toFloatMono(): FloatArray {
        val out = FloatArray(samples.size)
        for (i in samples.indices) out[i] = samples[i] / 32768f
        return out
    }

    companion object {
        const val SAMPLE_RATE_16K = 16000
    }
}
