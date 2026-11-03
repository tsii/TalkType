package de.voicekeyboard.dictation

/**
 * Push-to-talk states (DESIGN.md §2.4, KONZEPT.md §2.1).
 *
 * IDLE → STARTING → RECORDING → FINALIZING → IDLE; errors/abort go through
 * orderly cleanup back to IDLE. CANCELLING/STOPPING are dropped as separate
 * states at this stage: [DictationController.abort] and [DictationController.stop]
 * run synchronously and deterministically back to IDLE.
 */
enum class DictationState {
    /** No recording. start() is allowed. */
    IDLE,

    /** Recording is being initialized (creating resources/session). */
    STARTING,

    /** Microphone is running, blocks arrive via feedAudio. */
    RECORDING,

    /** Key released: the final decode of the entire buffer is running. */
    FINALIZING,
}
