package de.voicekeyboard.keyboard

/** B2w: mode of a repeat firing — ONE character or ONE whole word. */
enum class BackspaceRepeatMode { CHARACTER, WORD }

/**
 * B2 (PLAN_V0133 §B2) + B2w: pure time logic of the backspace repeat — NO
 * Android, JVM-testable (BackspaceTest). Contract:
 *  - [down]: EXACTLY ONE immediate delete action (true) + schedule armed —
 *    start delay [START_DELAY_MS] = 450 ms, character period
 *    [REPEAT_PERIOD_MS] = 70 ms (target range 60–80 ms).
 *  - [tick]: timer firing — mode of the firing (null = none): CHARACTER while
 *    held < [WORD_THRESHOLD_MS] = 2000 ms; from the threshold on WHOLE WORDS
 *    every [WORD_PERIOD_MS] = 167 ms (6 words/s) — after 2000 ms NO character
 *    tick fires anymore, the first WORD tick lands EXACTLY at the threshold.
 *    If the timer lags behind, nothing is caught up (at most one delete per firing).
 *  - [cancel]: UP/CANCEL/detach/editor change — deterministically ended, no
 *    second action; afterwards the plan is re-armable (the next gesture fresh,
 *    i. e. again in CHARACTER mode).
 *
 * The adapter (KeyboardView) fetches the postDelayed delay from [nextDelayMs];
 * the character repeat AND tap run over the same service branch
 * (VoiceKeyboardService.onAction(BACKSPACE) → backspaceDeleteOnce), word
 * firings over the SAME seam (onBackspaceWord → backspaceDeleteWordOnce).
 */
class BackspaceRepeat {
    private var armed = false
    private var downMs = 0L
    private var nextAtMs = 0L
    private var mode = BackspaceRepeatMode.CHARACTER

    /** DOWN: exactly one immediate action (true) + schedule from [nowMs]. */
    fun down(nowMs: Long): Boolean {
        if (armed) return false // DOWN without a preceding UP: no second action
        armed = true
        downMs = nowMs
        mode = BackspaceRepeatMode.CHARACTER
        nextAtMs = nowMs + START_DELAY_MS
        return true
    }

    /** Timer firing: mode of the delete action (null = none); schedules the next one internally. */
    fun tick(nowMs: Long): BackspaceRepeatMode? {
        if (!armed || nowMs < nextAtMs) return null
        val thresholdMs = downMs + WORD_THRESHOLD_MS
        if (mode == BackspaceRepeatMode.WORD || nowMs >= thresholdMs) {
            // B2w: from the threshold on ONLY word ticks — never back to characters
            mode = BackspaceRepeatMode.WORD
            nextAtMs = maxOf(nextAtMs + WORD_PERIOD_MS, nowMs + WORD_PERIOD_MS)
            return BackspaceRepeatMode.WORD
        }
        // B2w: the NEXT character tick lands at the latest ON the threshold —
        // between the last character tick and the first word tick none is lost
        nextAtMs = (nextAtMs + REPEAT_PERIOD_MS).coerceAtMost(thresholdMs)
        if (nextAtMs <= nowMs) { // do not catch up
            nextAtMs = (nowMs + REPEAT_PERIOD_MS).coerceAtMost(thresholdMs)
        }
        return BackspaceRepeatMode.CHARACTER
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

        /** B2w: from this held duration on the repeat deletes WHOLE WORDS. */
        const val WORD_THRESHOLD_MS = 2000L

        /** B2w: word period — 6 words/s. */
        const val WORD_PERIOD_MS = 167L
    }
}
