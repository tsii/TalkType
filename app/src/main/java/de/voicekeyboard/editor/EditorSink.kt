package de.voicekeyboard.editor

/**
 * Framework-free access to the dictation editor (DESIGN.md §6, IMPLEMENTATION_PLAN.md B.2).
 *
 * Deliberately without Android types (no `InputConnection`), so that commit/delta logic
 * is purely testable on the JVM. The Android implementation `InputConnectionSink`
 * (stage 4, part 2b) translates these operations into `deleteSurroundingText` /
 * `commitText`.
 *
 * Contract:
 * - [replaceBeforeCursor] acts only on the **own** area before the cursor.
 *   The call counts as atomic: on an exception it has not been executed.
 * - Ownership is not proven if context/length do not match the expectations
 *   (`null` / `-1`).
 */
interface EditorSink {

    /**
     * Replaces the last [replaceLength] characters before the cursor with [newText].
     * Afterwards the cursor stands behind the new text. Throws on error (errors go up,
     * KONZEPT.md §2.7); the caller's internal state stays consistent.
     */
    fun replaceBeforeCursor(replaceLength: Int, newText: String)

    /**
     * Up to [maxChars] characters of text before the cursor (context check before every
     * revision, DESIGN.md §6) or `null` if the editor does not deliver it
     * (ownership not proven).
     */
    fun textBeforeCursor(maxChars: Int): CharSequence?

    /**
     * Length of the own inserted area in characters — or `-1` if
     * unknown. Serves the ownership check (expected length vs. actual length).
     */
    fun ownLength(): Int

    /**
     * AP7-E1c (vc33 acceptance, P0 blocker): the own inserted text —
     * synchronous with the write operation (the sink executes edits on the
     * editor thread; an app's `restartInput` fires WITHIN our
     * commitText round, before the worker can update its tracker expectation
     * — this source is then ahead of the worker's view).
     * Default empty: implementations without their own text management degrade to
     * the tracker expectation.
     */
    fun ownText(): String = ""

    /**
     * A5 (v0.1.34): session change — the own span of the PREVIOUS
     * dictation session is no longer proven in the new session contact
     * (ownership does NOT carry over the session boundary; the sink instance
     * itself is reused). Implementations with their own text management
     * discard it here. Default no-op: framework-free fakes/tests stay
     * unchanged.
     * Caller: `DictationController.start()` (the same place as the
     * typer/tracker reset); NOT on apply error paths — a failed
     * edit counts as not executed, the span then stays unchanged.
     */
    fun resetSession() {}
}
