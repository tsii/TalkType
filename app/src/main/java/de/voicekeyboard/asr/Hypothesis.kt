package de.voicekeyboard.asr

import de.voicekeyboard.audio.PcmWindow
import java.io.File

/**
 * Ergebnis eines Transkriptionslaufs (IMPLEMENTATION_PLAN.md B.1).
 *
 * @param tokenIds emittierte Vokabel-IDs (bereits gefiltert: keine Sondertokens, kein Blank)
 * @param text detokenisierter Text (Vocabulary.detokenize)
 * @param frameTimesMs Zeitstempel je Token in ms (Frame-Index × 80 ms, ASR_CONTRACT §2)
 * @param sessionId Identität der Aufnahme-Sitzung
 * @param runId Identität dieses Dekodierungslaufs
 * @param audioSamples Anzahl PCM-Samples des dekodierten Fensters (16 kHz)
 * @param incomplete true, wenn Sicherheits-/Budgetlimit erreicht wurde
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
 * Engine-Schnittstelle (IMPLEMENTATION_PLAN.md B.1, verbindlich für Tests).
 */
interface AsrEngine {
    /** Lädt und validiert die Modelle aus [modelDir] (nemo128.onnx, encoder-model.int8.onnx, decoder_joint-model.int8.onnx, vocab.txt). */
    fun load(modelDir: File)

    /** Transkribiert ein Fenster. Muss nur für das eigene Modell geladen sein. */
    suspend fun transcribe(window: PcmWindow, sessionId: Long, runId: Long): Hypothesis

    fun close()
}
