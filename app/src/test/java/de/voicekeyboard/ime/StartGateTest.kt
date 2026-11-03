package de.voicekeyboard.ime

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * F50/F50a (PLAN_V0130 §1 A2): pure start gate decisions of the IME —
 * JVM-testable without Robolectric (pattern `MicToggleTest` for the other
 * extracted functions of the service).
 *
 *  - [micStartGate]: start serialization (V4 race). Invariant: at any time
 *    at most ONE start coroutine runs; an expired one is aborted or its
 *    result discarded (no zombie dictation, no double start, no overwriting
 *    of `lastStartMs`).
 *  - [micUpDuringStart]: UP in the STARTING window = pending stop with a
 *    transient hint (F50a) — removes the silent flag (C2 symptom 1),
 *    without changing the KEEP_RUNNING design (P0 finding 2(a)).
 */
class StartGateTest {

    // ---- F50: second DOWN before the deadline (start running) → IGNORE ----

    @Test
    fun `second DOWN before the deadline - start still running - is ignored`() {
        // F11 P1: the start loads the ~652 MB encoder — a second mic press
        // during startingUp must NOT create a second appointment.
        assertEquals(
            MicStartGateAction.IGNORE,
            micStartGate(startingUp = true, sessionRecording = false, oldStartJobActive = true),
        )
        // Even without an active job handle: startingUp seals off the gesture.
        assertEquals(
            MicStartGateAction.IGNORE,
            micStartGate(startingUp = true, sessionRecording = false, oldStartJobActive = false),
        )
    }

    // ---- F50: deadline expired, old job still running → SUPERSEDE ----

    @Test
    fun `deadline expired - old start job still running - supersede path`() {
        // the 15-s deadline has released startingUp, but the old start
        // coroutine still hangs (native engine.load) → the second DOWN
        // supersedes it: cancel + abort("start_superseded") + stopFgs("superseded"),
        // then a fresh start with a NEW coordinator (no zombie dictation, V4).
        assertEquals(
            MicStartGateAction.SUPERSEDE,
            micStartGate(startingUp = false, sessionRecording = false, oldStartJobActive = true),
        )
    }

    // ---- F50: running dictation session → IGNORE (no restart via DOWN) ----

    @Test
    fun `running session - DOWN is no restart - IGNORE`() {
        assertEquals(
            MicStartGateAction.IGNORE,
            micStartGate(startingUp = false, sessionRecording = true, oldStartJobActive = false),
        )
        // even with a hanging leftover job: the running recording wins.
        assertEquals(
            MicStartGateAction.IGNORE,
            micStartGate(startingUp = false, sessionRecording = true, oldStartJobActive = true),
        )
    }

    // ---- F50: normal case → FRESH_START ----

    @Test
    fun `no start active - fresh start`() {
        assertEquals(
            MicStartGateAction.FRESH_START,
            micStartGate(startingUp = false, sessionRecording = false, oldStartJobActive = false),
        )
    }

    // ---- B2 (PLAN §6.2 option A): hold starts with the 900-s limit ----

    @Test
    fun `B2 hold and toggle start with the 900-s upper limit`() {
        // The pure decision [sessionMaxDurationMs] controls the source
        // (AudioRecordSource parameter, source code untouched): 15 min
        // instead of the 300-s source default — for hold AND toggle sessions
        // (toggle practically irrelevant: F43 stops at the first pause).
        assertEquals("Hold: 900 s", 900_000L, sessionMaxDurationMs(holdGesture = true))
        assertEquals("toggle: the same value", 900_000L, sessionMaxDurationMs(holdGesture = false))
    }

    // ---- F50a: UP during STARTING → pending stop + hint ----

    @Test
    fun `UP during STARTING - pending-stop flag with hint, otherwise a regular stop`() {
        // UP in the (cold) start window: the stop is applied later (cancelStartRequested)
        // PLUS a transient hint "Release pre-noted — recording ends shortly"
        // — the previously silent flag was the point of confusion (C2 symptom 1).
        assertEquals(
            MicUpDuringStartAction.PENDING_STOP_HINT,
            micUpDuringStart(startingUp = true),
        )
        // no start in progress → the release is the regular stop.
        assertEquals(
            MicUpDuringStartAction.REGULAR_STOP,
            micUpDuringStart(startingUp = false),
        )
    }
}
