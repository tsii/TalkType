package de.voicekeyboard.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.SystemClock
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.BlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.sqrt

/**
 * AudioRecord-Implementierung von [AudioSource]: 16 kHz, mono, PCM16.
 *
 * - Puffer: `getMinBufferSize * BUFFER_FACTOR` (Faktor 4 → toleranter gegen
 *   Scheduling-Jitter, DESIGN §3 „mindestens anhand getMinBufferSize").
 * - Eigener Capture-Thread mit blockierendem `read` (DESIGN §2.3-2); Main-Thread
 *   bleibt frei.
 * - Kein Resampling, kein Plattenzugriff, keine Audio-Logs (nur Dauern/Fehlercodes).
 * - Sicherheitsnetz: [maxDurationMs] — die Session endet kontrolliert, auch wenn
 *   ein Stop-Signal verloren geht (Budget: 300 s Diktatmax, DESIGN §7).
 */
class AudioRecordSource(
    private val maxDurationMs: Long = MAX_DURATION_MS,
    var errorListener: AudioErrorListener? = null,
    var levelListener: AudioLevelListener? = null,
) : AudioSource {

    companion object {
        const val SAMPLE_RATE = 16_000
        const val CHANNEL = AudioFormat.CHANNEL_IN_MONO
        const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
        const val BUFFER_FACTOR = 4
        const val CHUNK_MS = 20 // 320 Samples — Chunkstartwert 20–40 ms (DESIGN §3)
        const val MAX_DURATION_MS = 300_000L // 300 s Auto-Stop (IMPLEMENTATION_PLAN M6)
        private const val QUEUE_CAPACITY = 32 // 32 × 20 ms ≈ 640 ms Puffer vor Rückstau
        const val ERR_INIT = "init"
        /** Nur der Name des gewählten Eingangs für den 🐞-Export (kein Pfad, kein Inhalt). */
        const val SOURCE_NAME = "VOICE_RECOGNITION"
        const val ERR_INVALID_OP = "invalidOperation"
        const val ERR_SYSTEM_STOP = "systemStop"
        const val ERR_MAX_DURATION = "maxDuration"
        private const val READ_TIMEOUT_MARKER = -1L
    }

    private val queue: BlockingQueue<PcmBlock> = ArrayBlockingQueue(QUEUE_CAPACITY)
    private val started = AtomicBoolean(false)
    private val stopping = AtomicBoolean(false)
    private val seqCounter = AtomicLong(0)
    private val startSampleCounter = AtomicLong(0)

    @Volatile private var thread: Thread? = null
    @Volatile private var record: AudioRecord? = null
    @Volatile private var minBufferBytes = 0

    override fun start(sessionId: Long): Boolean {
        if (!started.compareAndSet(false, true)) return false // idempotent
        val rec = try {
            createRecord().also { record = it }
        } catch (e: Exception) {
            started.set(false)
            errorListener?.onError(ERR_INIT, e.message ?: e.javaClass.simpleName)
            return false
        }
        try {
            if (rec.state != AudioRecord.STATE_INITIALIZED) {
                throw IllegalStateException("AudioRecord nicht initialisiert (state=${rec.state})")
            }
            rec.startRecording()
            if (rec.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                throw IllegalStateException("recordingState=${rec.recordingState}")
            }
        } catch (e: Exception) {
            releaseRecord(rec)
            started.set(false)
            errorListener?.onError(ERR_INIT, e.message ?: e.javaClass.simpleName)
            return false
        }
        val t = Thread({ captureLoop(rec) }, "vk-capture-${sessionId}")
        thread = t
        t.start()
        return true
    }

    @SuppressLint("MissingPermission") // RECORD_AUDIO wird vom Aufrufer (IME/FGS) gehalten
    private fun createRecord(): AudioRecord {
        minBufferBytes = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL, ENCODING)
        if (minBufferBytes <= 0) {
            throw IllegalStateException("getMinBufferSize=$minBufferBytes")
        }
        return AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION, // Diktat: AGC ja, NR je nach Gerät
            SAMPLE_RATE,
            CHANNEL,
            ENCODING,
            minBufferBytes * BUFFER_FACTOR,
        )
    }

    private fun captureLoop(rec: AudioRecord) {
        val chunk = ShortArray(SAMPLE_RATE * CHUNK_MS / 1000) // 320
        val startUptime = SystemClock.elapsedRealtime()
        try {
            while (!stopping.get()) {
                if (SystemClock.elapsedRealtime() - startUptime >= maxDurationMs) {
                    errorListener?.onError(ERR_MAX_DURATION, "durationMs=$maxDurationMs")
                    return // kontrolliertes Ende; Stop wird vom Consumer über stopAndDrain erkannt
                }
                val n = rec.read(chunk, 0, chunk.size)
                when {
                    n > 0 -> {
                        val copy = chunk.copyOf(n)
                        val block = PcmBlock(
                            seq = seqCounter.getAndIncrement(),
                            startSample = startSampleCounter.getAndAdd(n.toLong()),
                            samples = copy,
                        )
                        levelListener?.onLevel(rms(copy), block.seq)
                        // drop-oldest statt blockieren: Rückstau verliert Altes, nie Reihenfolge
                        while (!queue.offer(block, 50, TimeUnit.MILLISECONDS)) {
                            queue.poll()
                        }
                    }
                    n == 0 -> Unit // kein Daten — loop
                    else -> { // ERROR_INVALID_OPERATION o. ä. — Aufnahme vom System gestoppt
                        errorListener?.onError(
                            if (n == AudioRecord.ERROR_INVALID_OPERATION) ERR_INVALID_OP else ERR_SYSTEM_STOP,
                            "readResult=$n",
                        )
                        return
                    }
                }
            }
        } catch (_: InterruptedException) {
            // stop(): Capture-Thread auslaufen lassen (Stopprotokoll DESIGN §3)
        } catch (e: Exception) {
            errorListener?.onError(ERR_SYSTEM_STOP, e.javaClass.simpleName)
        }
    }

    /** RMS eines PCM16-Blocks als linearer Pegel 0..1. Reine Funktion (JVM-testbar). */
    private fun rms(samples: ShortArray): Float {
        if (samples.isEmpty()) return 0f
        var sum = 0.0
        for (s in samples) {
            val v = s / 32768.0
            sum += v * v
        }
        return sqrt(sum / samples.size).toFloat()
    }

    override fun read(dst: ShortArray, timeoutMs: Int): Int {
        if (stopping.get()) return READ_TIMEOUT_MARKER.toInt()
        val head = try {
            queue.poll(timeoutMs.toLong(), TimeUnit.MILLISECONDS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            return READ_TIMEOUT_MARKER.toInt()
        } ?: return 0
        val n = minOf(dst.size, head.samples.size)
        System.arraycopy(head.samples, 0, dst, 0, n)
        return n
    }

    override fun stopAndDrain(): List<PcmBlock> {
        if (!stopping.compareAndSet(false, true)) return emptyList()
        thread?.let { t ->
            t.interrupt()
            try {
                t.join(500)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
        record?.let { rec ->
            try {
                if (rec.recordingState == AudioRecord.RECORDSTATE_RECORDING) rec.stop()
            } catch (_: IllegalStateException) { /* bereits gestoppt */ }
        }
        val drained = mutableListOf<PcmBlock>()
        while (true) {
            drained += queue.poll() ?: break
        }
        queue.clear()
        return drained
    }

    private fun releaseRecord(rec: AudioRecord) {
        try {
            rec.stop()
        } catch (_: IllegalStateException) { /* nicht gestartet */ }
        try {
            rec.release()
        } catch (_: Exception) { /* best effort */ }
    }

    override fun release() {
        stopAndDrain()
        record?.let { releaseRecord(it) }
        record = null
        thread = null
        started.set(false)
        stopping.set(false)
    }
}
