package de.voicekeyboard.audio

import java.io.File
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.math.sqrt

/**
 * F30 (docs/EMULATOR_DIKTAT_HARNESS.md, option C): debug-only AudioSource that plays
 * a WAV file (16 kHz mono s16) as a deterministic capture replacement.
 *
 * Purpose: make abort paths (stall watchdog, silence, stop() finale) REPRODUCIBLE —
 * without emulator audio, without ONNX (the coordinator gets a fake engine in tests).
 *
 * Semantics:
 * - Block delivery: 10 ms = 160 samples per successful [read] (capture blocks, like
 *   table §4 of the harness doc).
 * - TIME FIDELITY via the injectable clock [nowMs] (ms, monotone test time; production
 *   of this debug type: SystemClock). [read] blocks on a condition until the
 *   clock reaches the block due date — NO Thread.sleep in the rhythm.
 * - Scenario parameters:
 *   [silenceFromMs]  from this clock time the source delivers NO more blocks (read=0,
 *                    "dead" capture — watchdog material). NO zero-level stream:
 *                    silence here means "source delivers nothing" (the user case "mic
 *                    muted"); a streaming zero level would be per doc §4/S2 "silence ≠
 *                    stall" and would NOT trigger the watchdog.
 *   [gapFromMs]/[gapToMs]  bounded: in this clock window the source delivers 0
 *                    (e.g. 3500 ms = watchdog trigger), afterwards it continues.
 *   [stopAfterMs]   after this clock time the source is "finished" (read=0 forever).
 *   All defaults = LONG_MAX resp. -1 (no scenario active).
 * - WAV shorter than the scene: the content is LOOPED (de_smoke.wav is 6.4 s).
 * - Rule 9: never log audio/text contents — this type logs nothing at all
 *   (the coordinator logs numbers only).
 */
class FakeWavAudioSource(
    wavFile: File,
    private val silenceFromMs: Long = Long.MAX_VALUE,
    private val gapFromMs: Long = -1L,
    private val gapToMs: Long = -1L,
    private val stopAfterMs: Long = Long.MAX_VALUE,
    private val nowMs: () -> Long = { System.nanoTime() / 1_000_000L },
    var errorListener: AudioErrorListener? = null,
    var levelListener: AudioLevelListener? = null,
) : AudioSource {

    private val samples: ShortArray
    private val lock = ReentrantLock()
    private val dataAvailable = lock.newCondition()
    @Volatile private var released = false
    private var sessionStartMs = 0L // only under [lock]; block no. 0 is due from here
    private var nextBlockIndex = 0L // only under [lock]; block no. of the next delivery

    companion object {
        const val SAMPLE_RATE = 16_000
        /** 10 ms @ 16 kHz = 160 samples (harness doc §4: 10 ms capture blocks). */
        const val BLOCK_SAMPLES = 160
        /** Real poll upper bound per read (safety net; the rhythm comes from [nowMs]). */
        private const val POLL_NANOS = 5_000_000L
        const val ERR_INIT = "init"

        /**
         * F30: debug wiring (compilable only in the debug source dir):
         * `scenario = "throughput" | "silence" | "gap" | "short"` switchable via the
         * VKS field (VoiceKeyboardService.debugAudioSourceFactory) —
         * production (release) does not compile this directory and stays
         * AudioRecordSource.
         */
        @JvmStatic
        fun build(
            wavFile: File,
            scenario: String,
            nowMs: () -> Long = { System.nanoTime() / 1_000_000L },
        ): FakeWavAudioSource = when (scenario) {
            "throughput" -> FakeWavAudioSource(wavFile, nowMs = nowMs)
            "silence" -> FakeWavAudioSource(wavFile, silenceFromMs = 0L, nowMs = nowMs)
            "gap" -> FakeWavAudioSource(
                wavFile, gapFromMs = 1_000L, gapToMs = 4_500L, nowMs = nowMs,
            )
            "short" -> FakeWavAudioSource(wavFile, stopAfterMs = 1_200L, nowMs = nowMs)
            else -> throw IllegalArgumentException("unknown scenario (name only, no content)")
        }
    }

    init {
        samples = loadWavSamples(wavFile)
    }

    /** Minimal s16le mono reader: searches for the "data" chunk (independent of the header layout). */
    private fun loadWavSamples(file: File): ShortArray {
        if (!file.isFile) throw IllegalStateException("WAV missing: " + file.name)
        val b = file.readBytes()
        if (b.size < 44 || String(b, 0, 4) != "RIFF" || String(b, 8, 4) != "WAVE") {
            throw IllegalStateException("not RIFF/WAVE: " + file.name)
        }
        var i = 12
        while (i + 8 <= b.size) {
            val id = String(b, i, 4)
            val size = ((b[i + 4].toInt() and 0xff) or
                ((b[i + 5].toInt() and 0xff) shl 8) or
                ((b[i + 6].toInt() and 0xff) shl 16) or
                ((b[i + 7].toInt() and 0xff) shl 24))
            if (id == "data") {
                val n = minOf(size, b.size - i - 8)
                require(n > 0) { "data chunk empty: " + file.name }
                val out = ShortArray(n / 2)
                for (j in out.indices) {
                    val off = i + 8 + j * 2
                    out[j] = (b[off].toInt() or (b[off + 1].toInt() shl 8)).toShort()
                }
                return out
            }
            i += 8 + size + if (size and 1 == 1) 1 else 0
        }
        throw IllegalStateException("data chunk not found: " + file.name)
    }

    /** Idempotent (AudioSource contract). Notes the session start — the block
     *  due date aligns with it (block i due at sessionStart + i×10 ms). */
    override fun start(sessionId: Long): Boolean {
        lock.withLock {
            if (released) {
                errorListener?.onError(ERR_INIT, "released")
                return false
            }
            if (sessionStartMs == 0L) sessionStartMs = nowMs()
            return true
        }
    }

    /**
     * Blocking read, TIME-FAITHFUL on the [nowMs] clock:
     * - delivers the next 10 ms block of the WAV (looped), as soon as the clock reaches
     *   the due date (block no. × 10 ms);
     * - 0 = silence/end/gap (scenario) OR due date still open (then it waits on the
     *   condition until the next clock tick — no hot spin);
     * - -1 = released. Every 0 result is rate-limited to [POLL_NANOS]
     *   (so that the coordinator stall watchdog is checked in rhythm).
     */
    override fun read(dst: ShortArray, timeoutMs: Int): Int {
        lock.withLock {
            while (true) {
                if (released) return -1
                val t = nowMs()
                if (t >= silenceFromMs || stopAfterMs <= t || inGap(t)) {
                    dataAvailable.awaitNanos(POLL_NANOS)
                    return 0
                }
                if (t < sessionStartMs + nextBlockIndex * 10L) {
                    dataAvailable.awaitNanos(POLL_NANOS)
                    continue // due date not yet reached — the clock waits
                }
                val n = minOf(dst.size, BLOCK_SAMPLES)
                val base = (nextBlockIndex * BLOCK_SAMPLES % samples.size).toInt()
                for (j in 0 until n) {
                    dst[j] = samples[((base + j) % samples.size).toInt()]
                }
                nextBlockIndex += 1
                // Level channel: RMS per block (0..1) — numbers only, never contents.
                levelListener?.let {
                    var acc = 0L
                    for (j in 0 until n) acc += dst[j].toLong() * dst[j].toLong()
                    it.onLevel((sqrt(acc.toDouble() / n) / 32_768.0).toFloat(), nextBlockIndex - 1)
                }
                return n
            }
        }
    }

    /** Scenario gap: clock window without block delivery (only active if gapFromMs >= 0). */
    private fun inGap(t: Long): Boolean =
        gapFromMs >= 0L && gapToMs > gapFromMs && t in gapFromMs until gapToMs

    /** No internal queue (blocks were delivered directly via read) → empty. */
    override fun stopAndDrain(): List<PcmBlock> = emptyList()

    /** Wakes the blocking read (the capture loop runs out cleanly via -1). */
    override fun release() {
        lock.withLock {
            released = true
            dataAvailable.signalAll()
        }
    }
}
