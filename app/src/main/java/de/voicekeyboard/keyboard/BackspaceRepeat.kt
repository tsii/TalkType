package de.voicekeyboard.keyboard

/**
 * B2 (PLAN_V0133 §B2): reine Zeitlogik des Backspace-Repeats — KEIN Android,
 * JVM-testbar (BackspaceTest). Vertrag:
 *  - [down]: GENAU EINE sofortige Löschaktion (true) + Zeitplan armiert —
 *    Startverzögerung [START_DELAY_MS] = 450 ms, Wiederholperiode
 *    [REPEAT_PERIOD_MS] = 70 ms (Zielbereich 60–80 ms).
 *  - [tick]: Timer-Feuerung — true ⇒ Löschaktion JETZT ausführen; hinkt der
 *    Timer hinterher, wird NICHT aufgeholt (höchstens ein Delete je Feuerung).
 *  - [cancel]: UP/CANCEL/Detach/Editorwechsel — deterministisch beendet, keine
 *    zweite Aktion; danach ist der Plan neu armierbar (nächste Geste frisch).
 *
 * Der Adapter (KeyboardView) holt die postDelayed-Verzögerung aus [nextDelayMs];
 * Repeat UND Tap laufen über denselben Servicezweig
 * (VoiceKeyboardService.onAction(BACKSPACE) → backspaceDeleteOnce).
 */
class BackspaceRepeat {
    private var armed = false
    private var nextAtMs = 0L

    /** DOWN: genau eine sofortige Aktion (true) + Zeitplan ab [nowMs]. */
    fun down(nowMs: Long): Boolean {
        if (armed) return false // DOWN ohne vorheriges UP: keine Zweitaktion
        armed = true
        nextAtMs = nowMs + START_DELAY_MS
        return true
    }

    /** Timer-Feuerung: true ⇒ Löschaktion jetzt; plant intern die nächste. */
    fun tick(nowMs: Long): Boolean {
        if (!armed || nowMs < nextAtMs) return false
        nextAtMs += REPEAT_PERIOD_MS
        if (nextAtMs <= nowMs) nextAtMs = nowMs + REPEAT_PERIOD_MS // nicht aufholen
        return true
    }

    /** UP/CANCEL/Detach/Editorwechsel: beendet — kein weiterer Auftrag. */
    fun cancel() {
        armed = false
    }

    /** Verzögerung bis zur nächsten fälligen Feuerung; null = nicht armiert. */
    fun nextDelayMs(nowMs: Long): Long? =
        if (!armed) null else (nextAtMs - nowMs).coerceAtLeast(0L)

    companion object {
        const val START_DELAY_MS = 450L
        const val REPEAT_PERIOD_MS = 70L
    }
}
