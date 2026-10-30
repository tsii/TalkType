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
 * M1/M2/M4 aus `docs/PLAN_AUDIO_UND_LAYOUT.md` §1.3: Beweis, dass der ECHTE
 * Mikrofonpfad auf dem Gerät Samples liefert und der Pegelkanal meldet.
 *
 * Ausführung (nur Orchestrator, adb exklusiv):
 *   tools/emulator_audio_up.sh          // Emulator mit Mikrofon-Einlass, Senke 65 %
 *   adb -s <device> shell pm grant de.voicekeyboard android.permission.RECORD_AUDIO
 *   ( for i in 1 2 3; do paplay testdata/de_smoke.wav; sleep 0.3; done ) &
 *   ./gradlew --offline connectedDebugAndroidTest -Pandroid.testDevice=<device> \
 *       -Pandroid.testInstrumentationRunnerArguments.class=de.voicekeyboard.MicCaptureTest
 *
 * Methodennamen ohne Leerzeichen (DEX-Bau bricht sonst bei Lambdas in Rückticks).
 */
class MicCaptureTest {

    private val sampleRate = 16_000
    private val channel = AudioFormat.CHANNEL_IN_MONO
    private val encoding = AudioFormat.ENCODING_PCM_16BIT

    private fun newRecord(): AudioRecord {
        val min = AudioRecord.getMinBufferSize(sampleRate, channel, encoding)
        assertTrue("getMinBufferSize lieferte $min", min > 0)
        return AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            sampleRate,
            channel,
            encoding,
            min * 4,
        )
    }

    /** M1: Nach dem Start ist der Rekorder wirklich initialisiert UND am Aufnehmen. */
    @Test
    fun m1_captureStateInitializedAfterStart() {
        val rec = newRecord()
        try {
            assertTrue(
                "state=${rec.state} (erwartet STATE_INITIALIZED=${AudioRecord.STATE_INITIALIZED})",
                rec.state == AudioRecord.STATE_INITIALIZED,
            )
            rec.startRecording()
            assertTrue(
                "recordingState=${rec.recordingState} (erwartet RECORDING=${AudioRecord.RECORDSTATE_RECORDING})",
                rec.recordingState == AudioRecord.RECORDSTATE_RECORDING,
            )
        } finally {
            runCatching { rec.stop() }
            rec.release()
        }
    }

    /**
     * M2: Über 2 s echte Aufnahme kommen Samples an, und sie sind kein Rauschen.
     * Schwellen aus dem Plan: RMS >= 200, Peak >= 1000 (int16, bei 65 % Senkenlage
     * gemessen Peak ~19 800 / RMS ~1 900).
     */
    @Test
    fun m2_readYieldsSamplesWithRms() {
        val rec = newRecord()
        var geleseneSamples = 0
        var quadrate = 0.0
        var peak = 0
        val puffer = ShortArray(1600) // 100 ms
        try {
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
        require(geleseneSamples > 0) { "keine Samples gelesen" }
        val rms = sqrt(quadrate / geleseneSamples).toInt()
        assertTrue("keine Samples gelesen ($geleseneSamples)", geleseneSamples > 0)
        // Schwellen aus dem gemessenen Betriebspegel (Senkenlage 65 %: RMS ~1 930,
        // Peak ~19 800). Die alten Werte 200/1000 hätten auch schwaches Störgeräusch
        // durchgelassen; 800/4 000 verlangen den echten Ton, bleiben aber weit unter
        // dem Messwert und damit robust gegen leichte Pegelschwankungen.
        assertTrue("RMS $rms unter 800 — es kommt kein Ton am Mikrofon an", rms >= 800)
        assertTrue("Peak $peak unter 4000 — viel zu leise zum Prüfen", peak >= 4_000)
    }

    /** M4: Der Pegelkanal der echten Quelle meldet mehrere Blöcke mit Pegel > 0. */
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
        assertTrue("AudioRecordSource.start() schlug fehl", quelle.start(sessionId = 1L))
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
            "levelListener lieferte nur ${aufrufe.get()} Werte (erwartet >= 3)",
            aufrufe.get() >= 3,
        )
        assertTrue("Pegel durchgehend 0 — die Quelle meldet Stille", maxPegel.get() > 0f)
    }
}
