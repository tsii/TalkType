package de.voicekeyboard.ime

import de.voicekeyboard.model.ModelVariant

/**
 * T22g (PLAN_V0146 §3): PURE switch decision for the model set — no Android
 * types, no store access (the callers pass the facts; JVM-testable).
 *
 * Highest ruling (plan §3): a RUNNING dictation is NEVER aborted. A switch
 * during a dictation becomes [Decision.Deferred] and is applied at the idle
 * report (VoiceKeyboardService.onDictationStopped).
 */
object ModelSwitchPolicy {

    /** Rejection reasons (closed codes, rule 9). */
    enum class Reason { NOT_READY }

    sealed class Decision {
        /** Target not READY — never applied (the UI writes the pref only on READY;
         *  a hand-bent pref falls back in buildCoordinator). */
        data class Rejected(val reason: Reason) : Decision()

        /** Target READY and no dictation running — apply immediately. */
        data object Apply : Decision()

        /** Target READY but a dictation is running — apply at the idle report. */
        data object Deferred : Decision()

        /** Target IS the current set — nothing to do (pref write is a no-op). */
        data object NoOp : Decision()
    }

    fun policy(
        target: ModelVariant,
        current: ModelVariant,
        targetReady: Boolean,
        dictationActive: Boolean,
    ): Decision = when {
        target == current -> Decision.NoOp
        !targetReady -> Decision.Rejected(Reason.NOT_READY)
        dictationActive -> Decision.Deferred
        else -> Decision.Apply
    }
}
