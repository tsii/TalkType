package de.voicekeyboard.dictation

/**
 * T10 (v0.1.39): if a dictation ends on punctuation, the SESSION final
 * automatically appends ONE space in the editor — so the next dictation does
 * not stick directly to the punctuation („Hallo.Guten Morgen“ →
 * „Hallo. Guten Morgen“). Pure, JVM-testable decision (no Android types,
 * pattern of [DictationOutputCounter]); the call site is
 * [DictationController.writeHypothesis] with `final=true` — ONLY the real
 * session final (stop/UP or the F43 auto-stop final), NOT the
 * intermediate-segment finalization (F53 seg_final) and not the abort path.
 *
 * Rule: append `' '` exactly when
 *  - the relevant character at the text end (ignoring trailing
 *    CLOSERS [CLOSERS] `” “ " » ) ]` — then the character before them is
 *    relevant, multi-level as in „…“) is a SENTENCE PUNCTUATION mark from
 *    [PUNCT] `. ! ? : ; …`,
 *  - AND the committed text does not already end in whitespace and there is no
 *    space at the cursor ([alreadyAtCursor], editor side, null = unknown)
 *    (double guard against double spaces),
 *  - AND the session produced output (null/empty text ⇒ nothing).
 */
object TrailingSpace {

    /** T10: punctuation marks after which the session final appends a space. */
    val PUNCT: Set<Char> = setOf('.', '!', '?', ':', ';', '…')

    /** T10: trailing closing characters — the character before them is relevant. */
    val CLOSERS: Set<Char> = setOf('”', '“', '"', '»', ')', ']')

    /**
     * Decide whether to append. [committedTail] = end of the finally committed
     * text (null = session without output), [alreadyAtCursor] = character at
     * the cursor BEFORE appending (double guard, cursor side; null = unknown —
     * the editor sink only reads before the cursor, then the text-side double
     * guard alone decides).
     */
    fun rule(committedTail: String?, alreadyAtCursor: Char?): Boolean {
        if (committedTail.isNullOrEmpty()) return false // empty session ⇒ nothing
        // Double guard, text side: already ends in whitespace ⇒ no second one.
        if (committedTail.last().isWhitespace()) return false
        // Double guard, cursor side: a space already sits there ⇒ nothing.
        if (alreadyAtCursor?.isWhitespace() == true) return false
        // Relevant character: walk back from the end over trailing
        // closing characters (multi-level: „…“) to the first normal character.
        var i = committedTail.length - 1
        while (i >= 0 && committedTail[i] in CLOSERS) i--
        if (i < 0) return false // only closers, no sentence punctuation before
        return committedTail[i] in PUNCT
    }
}
