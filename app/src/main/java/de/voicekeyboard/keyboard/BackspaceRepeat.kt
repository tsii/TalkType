package de.voicekeyboard.keyboard

/**
 * B2 (PLAN_V0133 §B2): pure time logic of the backspace repeat — NO Android,
 * JVM-testable (BackspaceTest). Contract:
 *  - [down]: EXACTLY ONE immediate delete action (true) + schedule armed —
 *    start delay [START_DELAY_MS] = 450 ms, repeat period
 *    [REPEAT_PERIOD_MS] = 70 ms (target range 60–80 ms).
 *  - [tick]: timer firing — true ⇒ execute the delete action NOW; if the
 *    timer lags behind, nothing is caught up (at most one delete per firing).
 *  - [cancel]: UP/CANCEL/detach/editor change — deterministically ended, no
 *    second action; afterwards the plan is re-armable (the next gesture fresh).
 *
 * The adapter (KeyboardView) fetches the postDelayed delay from [nextDelayMs];
 * repeat AND tap run over the same service branch
 * (VoiceKeyboardService.onAction(BACKSPACE) → backspaceDeleteOnce).
 */
class BackspaceRepeat {
    private var armed = false
    private var nextAtMs = 0L

    /** DOWN: exactly one immediate action (true) + schedule from [nowMs]. */
    fun down(nowMs: Long): Boolean {
        if (armed) return false // DOWN without a preceding UP: no second action
        armed = true
        nextAtMs = nowMs + START_DELAY_MS
        return true
    }

    /** Timer firing: true ⇒ delete action now; schedules the next one internally. */
    fun tick(nowMs: Long): Boolean {
        if (!armed || nowMs < nextAtMs) return false
        nextAtMs += REPEAT_PERIOD_MS
        if (nextAtMs <= nowMs) nextAtMs = nowMs + REPEAT_PERIOD_MS // do not catch up
        return true
    }

    /** UP/CANCEL/detach/editor change: ended — no further task. */
    fun cancel() {
        armed = false
    }

    /** Delay until the next due firing; null = not armed. */
    fun nextDelayMs(nowMs: Long): Long? =
        if (!armed) null else (nextAtMs - nowMs).coerceAtLeast(0L)

    companion object {
        const val START_DELAY_MS = 450L
        const val REPEAT_PERIOD_MS = 70L
    }
}
