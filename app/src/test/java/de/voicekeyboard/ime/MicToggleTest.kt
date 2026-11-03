package de.voicekeyboard.ime

import de.voicekeyboard.dictation.DictationState
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * F9 d (P2) + F8 Q6 (P1): state machine of the footer-row microphone.
 * The key must (a) start AND stop and (b) deliberately do nothing in
 * intermediate states — otherwise it reports "start failed".
 */
class MicToggleTest {

    @Test
    fun `without a coordinator the first tap starts`() {
        assertEquals(MicToggleAction.START, micToggleAction(null))
    }

    @Test
    fun `IDLE starts the recording`() {
        assertEquals(MicToggleAction.START, micToggleAction(DictationState.IDLE))
    }

    @Test
    fun `RECORDING stops the recording (the second tap no longer swallows)`() {
        assertEquals(MicToggleAction.STOP, micToggleAction(DictationState.RECORDING))
    }

    @Test
    fun `STARTING and FINALIZING are a deliberate do-nothing`() {
        assertEquals(MicToggleAction.IGNORE, micToggleAction(DictationState.STARTING))
        assertEquals(MicToggleAction.IGNORE, micToggleAction(DictationState.FINALIZING))
    }

    @Test
    fun `every enum state is assigned, no case falls through`() {
        // START/STOP only in IDLE or RECORDING; all other states IGNORE.
        for (s in DictationState.entries) {
            val a = micToggleAction(s)
            when (s) {
                DictationState.IDLE -> assertEquals(MicToggleAction.START, a)
                DictationState.RECORDING -> assertEquals(MicToggleAction.STOP, a)
                else -> assertEquals("state $s must not trigger a start/stop", MicToggleAction.IGNORE, a)
            }
        }
    }

    // ---- D10 rest §1: HOLD GESTURE on the footer-row microphone ----

    @Test
    fun `hold gesture DOWN starts only from IDLE - no toggle stop across the press level`() {
        assertEquals(MicHoldAction.START, micHoldPressAction(null))
        assertEquals(MicHoldAction.START, micHoldPressAction(DictationState.IDLE))
        // In running/intermediate states DOWN is deliberately NOTHING:
        assertEquals(MicHoldAction.IGNORE, micHoldPressAction(DictationState.RECORDING))
        assertEquals(MicHoldAction.IGNORE, micHoldPressAction(DictationState.STARTING))
        assertEquals(MicHoldAction.IGNORE, micHoldPressAction(DictationState.FINALIZING))
    }

    @Test
    fun `hold gesture UP ends with a final - never started means deliberately nothing`() {
        assertEquals(MicHoldAction.STOP, micHoldReleaseAction(DictationState.RECORDING, cancelled = false))
        assertEquals(MicHoldAction.STOP, micHoldReleaseAction(DictationState.STARTING, cancelled = false))
        // IDLE/FINALIZING: no stop (not started or the final is already running)
        assertEquals(MicHoldAction.IGNORE, micHoldReleaseAction(null, cancelled = false))
        assertEquals(MicHoldAction.IGNORE, micHoldReleaseAction(DictationState.IDLE, cancelled = false))
        assertEquals(MicHoldAction.IGNORE, micHoldReleaseAction(DictationState.FINALIZING, cancelled = false))
    }

    @Test
    fun `CANCEL aborts without a final - only from a running recording`() {
        assertEquals(MicHoldAction.ABORT, micHoldReleaseAction(DictationState.RECORDING, cancelled = true))
        assertEquals(MicHoldAction.ABORT, micHoldReleaseAction(DictationState.STARTING, cancelled = true))
        assertEquals(MicHoldAction.IGNORE, micHoldReleaseAction(DictationState.IDLE, cancelled = true))
        assertEquals(MicHoldAction.IGNORE, micHoldReleaseAction(DictationState.FINALIZING, cancelled = true))
        assertEquals(MicHoldAction.IGNORE, micHoldReleaseAction(null, cancelled = true))
    }

    @Test
    fun `hold gesture covers every DictationState in every event combination`() {
        for (s in DictationState.entries) {
            val press = micHoldPressAction(s)
            val release = micHoldReleaseAction(s, cancelled = false)
            val cancel = micHoldReleaseAction(s, cancelled = true)
            when (s) {
                DictationState.IDLE -> {
                    assertEquals(MicHoldAction.START, press)
                    assertEquals(MicHoldAction.IGNORE, release)
                    assertEquals(MicHoldAction.IGNORE, cancel)
                }
                DictationState.RECORDING, DictationState.STARTING -> {
                    assertEquals(MicHoldAction.IGNORE, press)
                    assertEquals(MicHoldAction.STOP, release)
                    assertEquals(MicHoldAction.ABORT, cancel)
                }
                else -> assertEquals(
                    "state $s must not react to any hold-gesture event",
                    MicHoldAction.IGNORE, press,
                ).also {
                    assertEquals(MicHoldAction.IGNORE, release)
                    assertEquals(MicHoldAction.IGNORE, cancel)
                }
            }
        }
    }

    // ---- T3 (v0.1.35, debugger design, evidence /tmp/vk_phone_e2.log l.74-97):
    // tap/hold classification of the footer micro gesture (micHoldReleaseDecision) ----

    // ① fresh tap, UP 150 ms BEFORE start_ok ⇒ TOGGLE_ON — the session keeps
    // running (STARTING = start not yet finished), the flag stays UNSET
    // (micStartFinishedAction RUN ⇒ RECORDING after start_ok, no stop).
    @Test
    fun `T3 fresh tap 150ms BEFORE start_ok - TOGGLE_ON, session keeps running`() {
        assertEquals(
            MicReleaseDecision.TOGGLE_ON,
            micHoldReleaseDecision(
                stateAtDown = DictationState.IDLE,
                stateAtRelease = DictationState.STARTING, // start_ok noch nicht gekommen
                holdDurationMs = 150L,
                cancelled = false,
                startingUp = true,
            ),
        )
        // TOGGLE_ON sets NO wantedStop flag — after start_ok the session runs (RUN):
        assertEquals(MicStartFinishAction.RUN, micStartFinishedAction(wantedStop = false, wantedAbort = false))
    }

    // ② fresh tap, UP 40 ms AFTER start_ok ⇒ TOGGLE_ON, NO stop — exactly the
    // evidence case (warm): before, micHoldReleaseAction(RECORDING, UP) killed
    // the session immediately (stopped ok=0/audio_s=0, 10-90 ms after start_ok).
    @Test
    fun `T3 fresh tap 40ms AFTER start_ok - TOGGLE_ON, no stop, session lives`() {
        assertEquals(
            MicReleaseDecision.TOGGLE_ON,
            micHoldReleaseDecision(
                stateAtDown = DictationState.IDLE,
                stateAtRelease = DictationState.RECORDING, // start_ok kam schon
                holdDurationMs = 40L,
                cancelled = false,
                startingUp = false,
            ),
        )
        // the Down-lost case (null = no coordinator) is a fresh tap too:
        assertEquals(
            MicReleaseDecision.TOGGLE_ON,
            micHoldReleaseDecision(null, DictationState.RECORDING, 40L, cancelled = false, startingUp = false),
        )
    }

    // ③ hold > 300 ms ⇒ a real hold release: STOP with a final; release variant
    // DURING the start ⇒ HOLD_STOP_PENDING — the pending stop applies AFTER
    // the start end (micStartFinishedAction STOP_AFTER_START, not blind KEEP_RUNNING).
    @Test
    fun `T3 hold over 300ms - stop with a final - release during start holds the pending stop`() {
        assertEquals(
            "hold-release on a running session: regular stop with a final",
            MicReleaseDecision.HOLD_STOP,
            micHoldReleaseDecision(DictationState.IDLE, DictationState.RECORDING, 350L, cancelled = false, startingUp = false),
        )
        assertEquals(
            "Release während STARTING: pending Stop",
            MicReleaseDecision.HOLD_STOP_PENDING,
            micHoldReleaseDecision(DictationState.IDLE, DictationState.STARTING, 350L, cancelled = false, startingUp = true),
        )
        assertEquals(
            "release on RECORDING while start_ok is still pending: pending stop",
            MicReleaseDecision.HOLD_STOP_PENDING,
            micHoldReleaseDecision(DictationState.IDLE, DictationState.RECORDING, 350L, cancelled = false, startingUp = true),
        )
        // the pending stop REALLY applies AFTER the start end (T3 replaces KEEP_RUNNING):
        assertEquals(MicStartFinishAction.STOP_AFTER_START, micStartFinishedAction(wantedStop = true, wantedAbort = false))
        // boundary: exactly 300 ms is still a tap (>) — 301 ms already a hold:
        assertEquals(MicReleaseDecision.TOGGLE_ON,
            micHoldReleaseDecision(DictationState.IDLE, DictationState.RECORDING, MIC_TAP_MAX_MS, cancelled = false, startingUp = false))
        assertEquals(MicReleaseDecision.HOLD_STOP,
            micHoldReleaseDecision(DictationState.IDLE, DictationState.RECORDING, MIC_TAP_MAX_MS + 1, cancelled = false, startingUp = false))
    }

    // ④ counter-check: second tap on the running session ⇒ TOGGLE_OFF stays
    // (regular switch off) — the T3 classification opens no endless toaster.
    @Test
    fun `T3 counter-check - second tap on a running session is TOGGLE_OFF`() {
        assertEquals(
            MicReleaseDecision.TOGGLE_OFF,
            micHoldReleaseDecision(DictationState.RECORDING, DictationState.RECORDING, 120L, cancelled = false, startingUp = false),
        )
        assertEquals(
            "a tap on a starting session switches off as well",
            MicReleaseDecision.TOGGLE_OFF,
            micHoldReleaseDecision(DictationState.RECORDING, DictationState.STARTING, 120L, cancelled = false, startingUp = true),
        )
        // CANCEL unchanged: abort without a final (only from a running/starting recording):
        assertEquals(MicReleaseDecision.ABORT,
            micHoldReleaseDecision(DictationState.RECORDING, DictationState.RECORDING, 120L, cancelled = true, startingUp = false))
        assertEquals(MicReleaseDecision.IGNORE,
            micHoldReleaseDecision(DictationState.IDLE, DictationState.IDLE, 120L, cancelled = true, startingUp = false))
        // the session ended between DOWN and UP: deliberately nothing:
        assertEquals(MicReleaseDecision.IGNORE,
            micHoldReleaseDecision(DictationState.RECORDING, DictationState.FINALIZING, 120L, cancelled = false, startingUp = false))
    }
}
