package de.voicekeyboard.ime

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * B2 (PLAN_V0130 §6.2 option A + §6.3-B2): pure warning decision
 * [holdWarnDue] for the 900-s upper limit — pattern [StartGateTest] (extracted
 * service decisions, JVM without Robolectric).
 *
 * Contract: warning EXACTLY ONCE per session, from 13 min of session audio
 * (780,000 ms = 900 s upper limit minus 2 min lead time); the warned flag
 * blocks repetitions and is reset only by the session end path.
 */
class HoldWarnTest {

    // ---- (1) below 13 min: no warning ----

    @Test
    fun `below 13 min no warning - not even just before`() {
        assertFalse("at 0 ms", holdWarnDue(sessionMs = 0L, warned = false))
        assertFalse("at 10 min (600 s)", holdWarnDue(sessionMs = 600_000L, warned = false))
        assertFalse("just below (779 999 ms)", holdWarnDue(sessionMs = 779_999L, warned = false))
    }

    // ---- (2) from 13 min: due exactly once ----

    @Test
    fun `from 780 s the warning becomes due - exactly at the threshold`() {
        assertTrue("exactly 780 000 ms", holdWarnDue(sessionMs = 780_000L, warned = false))
        assertTrue("far above (899 s)", holdWarnDue(sessionMs = 899_999L, warned = false))
    }

    // ---- (3) the warned flag blocks repetitions ----

    @Test
    fun `warned flag blocks repetition - even with a session rising further`() {
        assertFalse(
            "after the warning (14 min): not again",
            holdWarnDue(sessionMs = 840_000L, warned = true),
        )
        assertFalse(
            "just before the upper limit (899 s): not again",
            holdWarnDue(sessionMs = 899_999L, warned = true),
        )
    }

    // ---- (4) the session end resets: the next session warns again ----

    @Test
    fun `session end resets - the next session warns again exactly once`() {
        // first session: warning at 780 s …
        assertTrue(holdWarnDue(sessionMs = 780_000L, warned = false))
        // … flag set, no repetition:
        assertFalse(holdWarnDue(sessionMs = 850_000L, warned = true))
        // session END: flag back (service: onDictationStopped/abort/onAborted)
        // — the NEXT session starts at 0 and warns again from 780 s:
        assertFalse("new session start: not yet", holdWarnDue(sessionMs = 0L, warned = false))
        assertTrue("new session from 780 s: again exactly once", holdWarnDue(sessionMs = 780_000L, warned = false))
    }

    // ---- (5) defensive: absurd inputs, the flag always dominates ----

    @Test
    fun `hysteresis-free - threshold sharp, flag dominates, never for absurd values`() {
        // threshold sharp: 779,999 no / 780,000 yes (no drifting logic).
        assertFalse(holdWarnDue(sessionMs = 779_999L, warned = false))
        assertTrue(holdWarnDue(sessionMs = 780_000L, warned = false))
        // the flag ALWAYS dominates — never again even for absurdly large values.
        assertFalse(holdWarnDue(sessionMs = Long.MAX_VALUE / 2, warned = true))
        // negative/remaining inputs below the threshold: never.
        assertFalse(holdWarnDue(sessionMs = -1L, warned = false))
    }
}
