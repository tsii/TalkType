package de.voicekeyboard

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import de.voicekeyboard.audio.AudioRecordSource
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * M1/M2/M4 from `docs/PLAN_AUDIO_UND_LAYOUT.md` §1.3: proof that the REAL
 * microphone path delivers samples on the device and the level channel reports.
 *
 * Execution (orchestrator only, adb exclusive):
 *   tools/emulator_audio_up.sh          // emulator with microphone inlet, sink 65 %
 *   adb -s <device> shell pm grant de.voicekeyboard android.permission.RECORD_AUDIO
 *   ( for i in 1 2 3; do paplay testdata/de_smoke.wav; sleep 0.3; done ) &
 *   ./gradlew --offline connectedDebugAndroidTest -Pandroid.testDevice=<device> \
 *       -Pandroid.testInstrumentationRunnerArguments.class=de.voicekeyboard.MicCaptureTest
 *
 * Method names without spaces (otherwise the DEX build breaks on lambdas in backticks).
 */
class MicCaptureTest {

    private val sampleRate = 16_000
    private val channel = AudioFormat.CHANNEL_IN_MONO
    private val encoding = AudioFormat.ENCODING_PCM_16BIT

    private fun newRecord(): AudioRecord {
        val min = AudioRecord.getMinBufferSize(sampleRate, channel, encoding)
        assertTrue("getMinBufferSize returned $min", min > 0)
        return AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            sampleRate,
            channel,
            encoding,
            min * 4,
        )
    }

    /** M1: after the start the recorder is really initialized AND recording. */
    @Test
    fun m1_captureStateInitializedAfterStart() {
        val rec = newRecord()
        try {
            assertTrue(
                "state=${rec.state} (expected STATE_INITIALIZED=${AudioRecord.STATE_INITIALIZED})",
                rec.state == AudioRecord.STATE_INITIALIZED,
            )
            rec.startRecording()
            assertTrue(
                "recordingState=${rec.recordingState} (expected RECORDING=${AudioRecord.RECORDSTATE_RECORDING})",
                rec.recordingState == AudioRecord.RECORDSTATE_RECORDING,
            )
        } finally {
            runCatching { rec.stop() }
            rec.release()
        }
    }

    /**
     * M2: over 2 s of real recording samples arrive, and they are not noise.
     * Thresholds from the plan: RMS >= 200, peak >= 1000 (int16; at 65 % sink
     * level a measured peak of ~19 800 / RMS ~1 900).
     */
    @Test
    fun m2_readYieldsSamplesWithRms() {
        val rec = newRecord()
        var geleseneSamples = 0
        var quadrate = 0.0
        var peak = 0
        val puffer = ShortArray(1600) // 100 ms        try {
            rec.startRecording()
            val start = System.currentTimeMillis()
            while (geleseneSamples < sampleRate * 2 && System.currentTimeMillis() - start < 6000) {
                val n = rec.read(puffer, 0, puffer.size)
                if (n > 0) {
                    val nutzbare = minOf(n, puffer.size)
                    geleseneSamples += nutzbare
                    for (i in 0 until nutzbare) {
                        val wert = puffer[i].toDouble()
                        quadrate += wert * wert
                        val a = abs(puffer[i].toInt())
                        if (a > peak) peak = a
                    }
                }
            }
        } finally {
            runCatching { rec.stop() }
            rec.release()
        }
        require(geleseneSamples > 0) { "no samples read" }
        val rms = sqrt(quadrate / geleseneSamples).toInt()
        assertTrue("no samples read ($geleseneSamples)", geleseneSamples > 0)
        // thresholds from the measured operating level (sink level 65 %: RMS ~1 930,
        // peak ~19 800). The old values 200/1000 would also have let weak noise
        // through; 800/4 000 demand the real tone yet stay far below
        // the measured value and are therefore robust against slight level drift.
        assertTrue("RMS $rms below 800 — no tone reaches the microphone", rms >= 800)
        assertTrue("peak $peak below 4000 — far too quiet to check", peak >= 4_000)
    }

    /** M4: the level channel of the real source reports several blocks with level > 0. */
    @Test
    fun m4_levelListenerEmits() {
        val aufrufe = java.util.concurrent.atomic.AtomicInteger(0)
        val maxPegel = java.util.concurrent.atomic.AtomicReference(0f)
        val quelle = AudioRecordSource(
            errorListener = null,
            levelListener = de.voicekeyboard.audio.AudioLevelListener { rms, _ ->
                aufrufe.incrementAndGet()
                if (rms > maxPegel.get()) maxPegel.set(rms)
            },
        )
        assertTrue("AudioRecordSource.start() failed", quelle.start(sessionId = 1L))
        try {
            val puffer = ShortArray(320)
            val bis = System.currentTimeMillis() + 2500
            while (System.currentTimeMillis() < bis) {
                quelle.read(puffer, 50)
            }
        } finally {
            quelle.release()
        }
        assertTrue(
            "levelListener delivered only ${aufrufe.get()} values (expected >= 3)",
            aufrufe.get() >= 3,
        )
        assertTrue("level consistently 0 — the source reports silence", maxPegel.get() > 0f)
    }
}
