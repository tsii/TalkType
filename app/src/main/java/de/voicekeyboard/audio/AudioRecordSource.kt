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
 * AudioRecord implementation of [AudioSource]: 16 kHz, mono, PCM16.
 *
 * - Buffer: `getMinBufferSize * BUFFER_FACTOR` (factor 4 → more tolerant of
 *   scheduling jitter, DESIGN §3 „mindestens anhand getMinBufferSize").
 * - Own capture thread with blocking `read` (DESIGN §2.3-2); the main thread
 *   stays free.
 * - No resampling, no disk access, no audio logs (durations/error codes only).
 * - Safety net: [maxDurationMs] — the session ends in a controlled way, even if
 *   a stop signal gets lost (budget: 300 s dictation max, DESIGN §7).
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
        const val CHUNK_MS = 20 // 320 samples — chunk start value 20–40 ms (DESIGN §3)
        const val MAX_DURATION_MS = 300_000L // 300 s Auto-Stop (IMPLEMENTATION_PLAN M6)
        private const val QUEUE_CAPACITY = 32 // 32 × 20 ms ≈ 640 ms buffer before backpressure
        const val ERR_INIT = "init"
        /** Only the name of the chosen input for the 🐞 export (no path, no content). */
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

    @SuppressLint("MissingPermission") // RECORD_AUDIO is held by the caller (IME/FGS)
    private fun createRecord(): AudioRecord {
        minBufferBytes = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL, ENCODING)
        if (minBufferBytes <= 0) {
            throw IllegalStateException("getMinBufferSize=$minBufferBytes")
        }
        return AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION, // dictation: AGC yes, NR depending on the device
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
                    return // controlled end; the stop is detected by the consumer via stopAndDrain
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
                        // drop-oldest instead of blocking: backpressure loses old data, never order
                        while (!queue.offer(block, 50, TimeUnit.MILLISECONDS)) {
                            queue.poll()
                        }
                    }
                    n == 0 -> Unit // no data — loop
                    else -> { // ERROR_INVALID_OPERATION or similar — recording stopped by the system
                        errorListener?.onError(
                            if (n == AudioRecord.ERROR_INVALID_OPERATION) ERR_INVALID_OP else ERR_SYSTEM_STOP,
                            "readResult=$n",
                        )
                        return
                    }
                }
            }
        } catch (_: InterruptedException) {
            // stop(): let the capture thread run out (stop protocol DESIGN §3)
        } catch (e: Exception) {
            errorListener?.onError(ERR_SYSTEM_STOP, e.javaClass.simpleName)
        }
    }

    /** RMS of a PCM16 block as a linear level 0..1. Pure function (JVM-testable). */
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
            } catch (_: IllegalStateException) { /* already stopped */ }
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
        } catch (_: IllegalStateException) { /* not started */ }
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
