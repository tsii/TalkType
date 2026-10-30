package de.voicekeyboard.asr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class PcmWindowTest {

    @Test fun audioSeconds() {
        val w = de.voicekeyboard.audio.PcmWindow(ShortArray(16000), 16000)
        assertEquals(1.0, w.audioSeconds, 1e-9)
        assertEquals(16000, w.samples.size)
    }

    @Test fun audioSecondsEmpty() {
        val w = de.voicekeyboard.audio.PcmWindow(ShortArray(0), 16000)
        assertEquals(0.0, w.audioSeconds, 1e-9)
    }

    @Test fun toFloatMono() {
        val w = de.voicekeyboard.audio.PcmWindow(shortArrayOf(0, 32767, -32768), 16000)
        val f = w.toFloatMono()
        assertEquals(0.0f, f[0], 0f)
        assertEquals(32767f / 32768f, f[1], 0f)
        assertEquals(-1.0f, f[2], 0f)
    }

    @Test fun rejectsNon16kSampleRate() {
        val ex = assertThrows(IllegalArgumentException::class.java) {
            de.voicekeyboard.audio.PcmWindow(ShortArray(441), 44100)
        }
        assertEquals(
            "PcmWindow expects 16000 Hz mono PCM16, got 44100 Hz — resampling is not supported here",
            ex.message
        )
    }
}
