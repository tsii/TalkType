package de.voicekeyboard.ime

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A3 (PLAN_V0133 §A3) — remove the `seg_final` log artifact:
 *  1. New session after an ended one with seg=1: mic DOWN sets the session
 *     baseline (-1) — at session start (counter 0) NO `seg_final` is logged
 *     (before: stale artifact from the old sessLastSeg=1 against the new
 *     counter 0, log line even before `start_ok`); afterwards a real mini
 *     final (0→1) logs exactly once.
 *  2. `1→1` produces no entry; the second session repeats the same course
 *     without a stale artifact.
 *
 * Pure decision (rule 9): only a REAL counter increase logs —
 * `previous >= 0 && current > previous`; the session switch opens the
 * baseline without an entry. No change to the segmenter or its counter.
 */
class SegmentSessionLogTest {

    // A3 red test 1: old session seg=1 → mic DOWN → new session seg=0:
    // NO seg_final at the start; a real mini final 0→1 logs exactly ONCE.
    // (Realistic model: updateStatus observes every session first with
    // counter 0 — e.g. at start_ok —, the mini final later as 0→1.)
    @Test fun `A3 new session after an ended one with seg1 logs no seg_final and the mini final exactly once`() {
        val log = SegmentSessionLog()
        // old session: regular course — 0 (no entry), then mini final 0→1
        log.onMicDown()
        assertFalse("no entry at the old session's start", log.onSegmentCount(0))
        assertTrue("echtes Mini-Finale 0→1 loggt", log.onSegmentCount(1))

        // mic DOWN: NEW session — session baseline without an entry
        log.onMicDown()

        // new session counter 0 (near start_ok): NO stale artifact entry
        assertFalse("no seg_final at the session start (counter 0 after the baseline)", log.onSegmentCount(0))
        // same picture on repeated observation (start_ok environment)
        assertFalse("no seg_final with a stable counter 0", log.onSegmentCount(0))

        // real mini final of the NEW session: exactly ONE entry
        assertTrue("mini-final 0→1 of the new session logs", log.onSegmentCount(1))
        assertFalse("no second entry for the same counter value", log.onSegmentCount(1))
    }

    // A3 red test 2: 1→1 produces NO entry; the second session repeats
    // the same course without a stale artifact.
    @Test fun `A3 1to1 logs nothing and the second session without a stale artifact`() {
        val log = SegmentSessionLog()
        log.onMicDown()
        assertFalse("no entry at the session start (0)", log.onSegmentCount(0))
        assertTrue("the first real mini-final logs", log.onSegmentCount(1))
        assertFalse("1→1 creates no entry", log.onSegmentCount(1))

        // second session (mic DOWN): the same course, no stale artifact
        log.onMicDown()
        assertFalse("no entry at the new session start (0)", log.onSegmentCount(0))
        assertFalse("no entry with a stable 0", log.onSegmentCount(0))
        assertTrue("a real mini-final of the second session logs exactly once", log.onSegmentCount(1))
        assertFalse("1→1 stays without an entry", log.onSegmentCount(1))

        // third session: baseline clean again
        log.onMicDown()
        assertFalse("no entry at the third session start", log.onSegmentCount(0))
        assertTrue("a real mini-final of the third session logs", log.onSegmentCount(1))
    }
}
