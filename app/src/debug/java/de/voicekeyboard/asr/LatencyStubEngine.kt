package de.voicekeyboard.asr

import de.voicekeyboard.audio.PcmWindow
import java.io.File
import kotlin.math.roundToInt

/**
 * AP1 (docs/PLAN_C2_FABLE_R2.md §1.2): deterministic latency stub engine for
 * C2 simulations on the emulator/device — debug source set ONLY.
 *
 *  - transcribe() sleeps `base_ms + α · buffer_s` on the REAL-TIME CLOCK
 *    (the pipeline only sees decode duration and text length; α default 38.5 =
 *    device curve I3, stress variants 80/150 via debug.vk.engine).
 *  - Pseudo text: length ∝ buffer_s (`WORDS_PER_SECOND`), pattern „wort<i>",
 *    sentence marker '.' on every 8th word (feeds the segment rule (a)).
 *    Word IDs are derived deterministically from 1 per window from the buffer length —
 *    consecutive decodes of the GROWING segment buffer
 *    are prefix-stable (agreement/commit run realistically); after a
 *    segment restart the numbering starts over (like a real transcription
 *    of the excerpt); the controller's [runId] stays monotone
 *    and is passed through unchanged.
 *
 * Rule 9: contents are generic pattern-word numbers, never user data.
 * Limit (documented in the plan): for RSS/OOM and quality tests the
 * REAL engine must be used (debug.vk.engine=real).
 */
class LatencyStubEngine(
    val alphaMsPerSecond: Double = 38.5,
    val baseMs: Long = 0L,
    private val wordsPerSecond: Double = 2.0,
) : AsrEngine {

    override fun load(modelDir: File) {
        // No model — the latency is a pure compute-time simulation.
    }

    override suspend fun transcribe(
        window: PcmWindow,
        sessionId: Long,
        runId: Long,
    ): Hypothesis {
        val bufferS = window.samples.size.toDouble() / window.sampleRate
        val sleepMs = baseMs + (alphaMsPerSecond * bufferS).toLong()
        if (sleepMs > 0) Thread.sleep(sleepMs) // real-time clock, deliberate (AP1)
        val wordCount = (bufferS * wordsPerSecond).roundToInt().coerceAtLeast(1)
        val sb = StringBuilder()
        for (i in 1..wordCount) {
            if (i > 1) sb.append(' ')
            sb.append("wort").append(i)
            if (i % 8 == 0) sb.append('.') // sentence marker every 8 words (rule a)
        }
        return Hypothesis(
            IntArray(0), sb.toString(), IntArray(0), sessionId, runId,
            window.samples.size, incomplete = false,
        )
    }

    override fun close() {}
}
