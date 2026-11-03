package de.voicekeyboard.ime

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * AP3 (docs/PLAN_C2_FABLE_R2.md §4, export v2 part 1): priorities of the
 * unified stop cause [stopCauseOf] — pure function, JVM-testable, codes only
 * (rule 9). The priority is anchored in the function's KDoc and nailed down
 * here as a contract.
 */
class StopCauseTest {

    // 1. user stop: release after holding — physical event (micRel)
    //    beats "final" (regular overall final without another event).
    @Test
    fun `hold end is holdup not final`() {
        assertEquals(
            "holdup",
            stopCauseOf(explicitCause = "-", micRel = "holdup:5400", lastAbortReason = "final", startSrc = "hold"),
        )
    }

    // 2. toggle switch: micRel "up" on a toggle start ⇒ "toggle".
    @Test
    fun `micro switch stop is toggle`() {
        assertEquals(
            "toggle",
            stopCauseOf(explicitCause = "-", micRel = "up:1200", lastAbortReason = "final", startSrc = "toggle"),
        )
        // … on a hold start "up" remains (status bar UP).
        assertEquals(
            "up",
            stopCauseOf(explicitCause = "-", micRel = "up:1200", lastAbortReason = "final", startSrc = "hold"),
        )
    }

    // 3. FINE abort details beat generic and physical sources:
    //    editor_change / michold_cancel take precedence over micRel and final.
    @Test
    fun `editor change and hold cancel are fine details with precedence`() {
        assertEquals(
            "editor_change",
            stopCauseOf(explicitCause = "-", micRel = "editor:3200", lastAbortReason = "editor_change", startSrc = "hold"),
        )
        assertEquals(
            "michold_cancel",
            stopCauseOf(explicitCause = "-", micRel = "holdcancel:1500", lastAbortReason = "michold_cancel", startSrc = "hold"),
        )
    }

    // 4. audio source ends with their OWN code: maxDuration/systemStop
    //    instead of the generic audio_error:<code>.
    @Test
    fun `maxDuration and systemStop have their own codes`() {
        assertEquals(
            "maxDuration",
            stopCauseOf(explicitCause = "-", micRel = "", lastAbortReason = "audio_error:maxDuration", startSrc = "hold"),
        )
        assertEquals(
            "systemStop",
            stopCauseOf(explicitCause = "-", micRel = "", lastAbortReason = "audio_error:systemStop", startSrc = "toggle"),
        )
        // other audio errors remain audio_error:<code>
        assertEquals(
            "audio_error:silence",
            stopCauseOf(explicitCause = "-", micRel = "", lastAbortReason = "audio_error:silence", startSrc = "toggle"),
        )
    }

    // 5. W1b SYMPTOM CASE (Fable §0.1): auto-stop during a hold —
    //    explicit "autostop" beats EVERYTHING (even micRel holdup with ms ≫ audio_s);
    //    the signature in the export: stop_cause=autostop + start_src=hold +
    //    gate_at_stop=1 (gate lost/open in the middle of holding) + mic_rel
    //    holdup:<ms≫audio_s>. gate_at_stop is a SEPARATE field — here the
    //    pure part of the signature.
    @Test
    fun `W1b - autostop beats micRel holdup despite holding`() {
        assertEquals(
            "autostop",
            stopCauseOf(explicitCause = "autostop", micRel = "holdup:540000", lastAbortReason = "final", startSrc = "hold"),
        )
    }

    // 6. hidden (IME window gone) and further abort reasons (abort:<r>).
    @Test
    fun `hidden and abort others`() {
        assertEquals(
            "hidden",
            stopCauseOf(explicitCause = "-", micRel = "hidden:9100", lastAbortReason = "final", startSrc = "toggle"),
        )
        assertEquals(
            "abort:start_superseded",
            stopCauseOf(explicitCause = "-", micRel = "", lastAbortReason = "start_superseded", startSrc = "hold"),
        )
    }

    // 7. defaults: regular final without an event ⇒ final; no stop ⇒ idle_noop.
    @Test
    fun `final and idle_noop defaults`() {
        assertEquals(
            "final",
            stopCauseOf(explicitCause = "-", micRel = "", lastAbortReason = "final", startSrc = "toggle"),
        )
        assertEquals(
            "idle_noop",
            stopCauseOf(explicitCause = "-", micRel = "", lastAbortReason = "-", startSrc = "-"),
        )
        assertEquals(
            "idle_noop",
            stopCauseOf(explicitCause = "-", micRel = "", lastAbortReason = "idle_noop", startSrc = "hold"),
        )
    }
}
