package de.voicekeyboard.audio

/**
 * Audio capture interface per IMPLEMENTATION_PLAN.md §B.1 (binding).
 *
 * Pull model: a capture thread fills an internal queue (monotone sequence),
 * the consumer calls the blocking [read] with a timeout. No Android type in
 * the interface — JVM-testable. RMS level and errors run via optional
 * listeners (error channel), so that the UI can show the level without
 * draining the queue.
 */
data class PcmBlock(
    val seq: Long,            // monotone block number of the session
    val startSample: Long,    // sample index relative to the session start (16 kHz)
    val samples: ShortArray,  // 16 kHz mono s16
) {
    override fun equals(other: Any?): Boolean =
        other is PcmBlock && seq == other.seq && startSample == other.startSample &&
            samples.contentEquals(other.samples)

    override fun hashCode(): Int = 31 * (31 * seq.hashCode() + startSample.hashCode()) + samples.contentHashCode()
}

interface AudioSource {
    /**
     * Starts the capture for a session. Idempotent: a second call
     * during a running session starts nothing anew and returns false.
     */
    fun start(sessionId: Long): Boolean

    /**
     * Blocking read with timeout.
     * @return > 0: number of samples read into [dst]; 0: timeout/no data; < 0: error (session ended)
     */
    fun read(dst: ShortArray, timeoutMs: Int): Int

    /** Ends the recording and returns the OLDER queue remains (not the last read). */
    fun stopAndDrain(): List<PcmBlock>

    /**
     * Releases already received remainder blocks (empty after [stopAndDrain]) and
     * frees native resources. Never invokes listener callbacks anymore.
     */
    fun release()
}

/** Error channel: callback-based, because [read] has only a return code. */
fun interface AudioErrorListener {
    /**
     * @param code error code (init error, invalid state, recording stop
     *   by the system, route loss) — codes/durations only, never audio data.
     */
    fun onError(code: String, detail: String)
}

/** Level channel: RMS per block (linear, 0..1), for the UI display. */
fun interface AudioLevelListener {
    fun onLevel(rms: Float, blockSeq: Long)
}
