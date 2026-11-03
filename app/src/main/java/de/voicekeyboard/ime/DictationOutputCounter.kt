package de.voicekeyboard.ime

/**
 * Counts for D10-rest §3 whether a dictation produced characters in this session —
 * the decision basis for the commit check mark vs. "No text recognized".
 *
 * **Why gross and not net (review F12, P2):** the first version counted
 * `new characters - replaced characters`. Correcting five characters with five
 * new ones nets 0, even though the user sees text in the field — the check mark
 * was missing and instead "No text recognized" appeared. Therefore the sum of all
 * characters written in this session is counted.
 *
 * Deliberate deviation from D10 §2: a rollback to nothing also counts as
 * output. The visible failure picture is a recording that stayed completely
 * empty, not a corrected word.
 *
 * Rule 9: exclusively lengths, never text contents. Pure function without
 * Android dependencies, so the semantics are pinned in the JVM test (pattern
 * [micToggleAction]).
 */
class DictationOutputCounter {

    private var grossChars = 0

    /** Gross over the session — diagnostic value, not a decision. */
    val outputChars: Int
        get() = grossChars

    /** Did this dictation write anything at all? */
    val hasOutput: Boolean
        get() = grossChars > 0

    /**
     * One write step of the DeltaTyper. [replaceLength] is deliberately not
     * netted out (see class comment).
     */
    fun onCommit(newTextLength: Int, replaceLength: Int = 0) {
        if (newTextLength > 0) grossChars += newTextLength
    }

    /** New recording — without this reset the verdict would carry over from the last session. */
    fun reset() {
        grossChars = 0
    }
}
