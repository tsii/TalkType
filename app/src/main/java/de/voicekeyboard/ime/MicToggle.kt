package de.voicekeyboard.ime

import de.voicekeyboard.dictation.DictationState

/**
 * D10 §2 + F9 d: what a tap on the foot-row microphone key triggers in which
 * state — as a pure function, so the state machine can be proven without an
 * IME lifecycle.
 *
 * - IDLE (or no coordinator yet): START — begin recording.
 * - RECORDING: STOP — end the recording and decode finally (otherwise the
 *   key is a start-only switch and a running recording can no longer be
 *   ended, F8 Q6 = P1).
 * - STARTING / FINALIZING (and any future intermediate state): IGNORE —
 *   a tap must not trigger a restart here, because onMicDown would then fail
 *   at coordinator.start() and show „Start fehlgeschlagen“ (F9 d = P2).
 */
enum class MicToggleAction { START, STOP, IGNORE }

/**
 * D10-rest §1 („Daten und PTT“) — events of the foot-row HOLD GESTURE:
 * DOWN starts, UP ends (with finale), CANCEL aborts (abort, NO final
 * decode). Pure function, so the gesture is provable without an IME
 * lifecycle (see MicToggleTest).
 */
enum class MicHoldAction { START, STOP, ABORT, IGNORE }

/**
 * DOWN on the foot-row microphone: start ONLY from IDLE (or without a
 * coordinator) — in RECORDING/STARTING/FINALIZING a DOWN is a deliberate
 * no-op (no toggle stop via the press level; stopping comes exclusively via
 * the release, D10 §1).
 */
fun micHoldPressAction(state: DictationState?): MicHoldAction = when (state) {
    null, DictationState.IDLE -> MicHoldAction.START
    else -> MicHoldAction.IGNORE
}

/**
 * Releasing the foot-row microphone:
 * - normal (finger UP): RECORDING/STARTING → STOP (final decode); otherwise IGNORE
 *   (e. g. never started, because permission/model were missing).
 * - CANCEL (system/gesture steals the pointer): RECORDING/STARTING → ABORT —
 *   safe abort WITHOUT final decode (no chasing of a decode into an editor
 *   the user no longer looks at).
 */
fun micHoldReleaseAction(state: DictationState?, cancelled: Boolean): MicHoldAction =
    if (cancelled) {
        when (state) {
            DictationState.RECORDING, DictationState.STARTING -> MicHoldAction.ABORT
            else -> MicHoldAction.IGNORE
        }
    } else {
        when (state) {
            DictationState.RECORDING, DictationState.STARTING -> MicHoldAction.STOP
            else -> MicHoldAction.IGNORE
        }
    }

// ---- T3 (v0.1.35, debugger design, evidence /tmp/vk_phone_e2.log lines 74-97): tap/hold classification ----

/**
 * T3: maximum DOWN→UP duration in ms that still counts as a **TAP** — below it
 * the foot-row mic gesture is a SWITCH (toggle), above it a real hold-release
 * (PTT). Evidence: four sessions died 10–90 ms after `start_ok` via their OWN
 * release (mic_down → start_ok+105 ms → holdup → stopped ok=0/audio_s=0),
 * because `micHoldReleaseAction` returned STOP indiscriminately on
 * RECORDING/STARTING + UP.
 */
const val MIC_TAP_MAX_MS = 300L

/**
 * T3: decision on releasing the foot-row mic gesture — pure function over the
 * coordinator state AT DOWN ([stateAtDown]: fresh vs. session/start already
 * active), the DOWN→UP duration ([holdDurationMs], measured in KeyboardView from
 * `event.eventTime`) and the state at release:
 *
 *  - **fresh tap** (DOWN without session, ≤ [MIC_TAP_MAX_MS]) ⇒ [TOGGLE_ON] — the
 *    session keeps running, REGARDLESS of whether the UP came before or after
 *    `start_ok` (core of the finding: cold lost the UP to startingUp/KEEP_RUNNING,
 *    warm to start_ok);
 *  - **tap onto a running/starting session** (DOWN with an active session) ⇒
 *    [TOGGLE_OFF] — stop regularly (the second tap switches off, counter-check);
 *  - **> [MIC_TAP_MAX_MS]** ⇒ real hold-release ⇒ stop ([HOLD_STOP]); if the
 *    start is still running ([startingUp]/STARTING) ⇒ [HOLD_STOP_PENDING] — the
 *    stop takes effect AFTER the start ends (micStartFinishedAction:
 *    STOP_AFTER_START, not blindly KEEP_RUNNING — the fresh tap does not set
 *    the flag at all);
 *  - **CANCEL** unchanged ⇒ abort without finale (only from a running/starting
 *    recording).
 */
enum class MicReleaseDecision { TOGGLE_ON, TOGGLE_OFF, HOLD_STOP, HOLD_STOP_PENDING, ABORT, IGNORE }

/** T3: the release decision (pure seam, JVM-testable — pattern MicToggleTest). */
fun micHoldReleaseDecision(
    stateAtDown: DictationState?,
    stateAtRelease: DictationState?,
    holdDurationMs: Long,
    cancelled: Boolean,
    startingUp: Boolean,
): MicReleaseDecision = when {
    // CANCEL unchanged: safe abort without finale — only from a running/starting recording.
    cancelled -> when (stateAtRelease) {
        DictationState.RECORDING, DictationState.STARTING -> MicReleaseDecision.ABORT
        else -> MicReleaseDecision.IGNORE
    }
    // Real hold-release: stop with finale; start still running ⇒ pending after the start ends.
    holdDurationMs > MIC_TAP_MAX_MS -> when (stateAtRelease) {
        DictationState.RECORDING ->
            if (startingUp) MicReleaseDecision.HOLD_STOP_PENDING else MicReleaseDecision.HOLD_STOP
        DictationState.STARTING -> MicReleaseDecision.HOLD_STOP_PENDING
        else -> MicReleaseDecision.IGNORE // never started (permission/model/start failed)
    }
    // Tap ≤ 300 ms: DOWN without session ⇒ TOGGLE_ON — nothing to stop, regardless
    // of whether the UP came before or after start_ok (that was the session-killing case).
    stateAtDown == null || stateAtDown == DictationState.IDLE -> MicReleaseDecision.TOGGLE_ON
    // Tap onto a running/starting session ⇒ switch off regularly (counter-check).
    else -> when (stateAtRelease) {
        DictationState.RECORDING, DictationState.STARTING -> MicReleaseDecision.TOGGLE_OFF
        else -> MicReleaseDecision.IGNORE // session ended between DOWN and UP
    }
}

fun micToggleAction(state: DictationState?): MicToggleAction = when (state) {
    null, DictationState.IDLE -> MicToggleAction.START
    DictationState.RECORDING -> MicToggleAction.STOP
    else -> MicToggleAction.IGNORE
}
