package de.voicekeyboard.asr

import de.voicekeyboard.audio.PcmWindow
import java.io.File

/**
 * Result of a transcription run (IMPLEMENTATION_PLAN.md B.1).
 *
 * @param tokenIds emitted vocabulary IDs (already filtered: no special tokens, no blank)
 * @param text detokenized text (Vocabulary.detokenize)
 * @param frameTimesMs timestamps per token in ms (frame index × 80 ms, ASR_CONTRACT §2)
 * @param sessionId identity of the recording session
 * @param runId identity of this decode run
 * @param audioSamples number of PCM samples of the decoded window (16 kHz)
 * @param incomplete true if a safety/budget limit was reached
 */
data class Hypothesis(
    val tokenIds: IntArray,
    val text: String,
    val frameTimesMs: IntArray,
    val sessionId: Long,
    val runId: Long,
    val audioSamples: Int,
    val incomplete: Boolean,
) {
    override fun equals(other: Any?): Boolean =
        other is Hypothesis &&
            tokenIds.contentEquals(other.tokenIds) &&
            text == other.text &&
            frameTimesMs.contentEquals(other.frameTimesMs) &&
            sessionId == other.sessionId &&
            runId == other.runId &&
            audioSamples == other.audioSamples &&
            incomplete == other.incomplete

    override fun hashCode(): Int {
        var h = tokenIds.contentHashCode()
        h = 31 * h + text.hashCode()
        h = 31 * h + frameTimesMs.contentHashCode()
        h = 31 * h + sessionId.hashCode()
        h = 31 * h + runId.hashCode()
        h = 31 * h + audioSamples
        h = 31 * h + incomplete.hashCode()
        return h
    }
}

/**
 * Engine interface (IMPLEMENTATION_PLAN.md B.1, binding for tests).
 */
interface AsrEngine {
    /** Loads and validates the models from [modelDir] (nemo128.onnx, encoder-model.int8.onnx, decoder_joint-model.int8.onnx, vocab.txt). */
    fun load(modelDir: File)

    /** Transcribes a window. Must only be loaded for its own model. */
    suspend fun transcribe(window: PcmWindow, sessionId: Long, runId: Long): Hypothesis

    fun close()
}
