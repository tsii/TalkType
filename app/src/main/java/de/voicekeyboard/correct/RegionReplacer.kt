package de.voicekeyboard.correct

/**
 * T23e (V0.1.47, docs/PLAN_V0147.md §5): wand-press replacement via an editor
 * seam — pure logic, the service passes a thin InputConnection adapter
 * (beginBatchEdit/deleteSurroundingText/commitText/endBatchEdit/
 * getTextBeforeCursor + selection query). TYPE_NULL fields never reach here
 * (row off, §5).
 *
 * Preconditions (checked in order): corrected != buffer, non-empty buffer,
 * dictation IDLE, selection empty, suffix proof BEFORE the batch
 * (getTextBeforeCursor(buffer.length) == buffer — otherwise abort with NO
 * editor call). Batch: deleteSurroundingText(utf16 length of buffer, 0) +
 * commitText(corrected, 1). RE-VERIFY after the batch: the new tail must
 * equal corrected, else corrVerifyFail++ and the onSuffixMismatch callback
 * fires (the service resets the TypedRegionTracker).
 *
 * Undo: ONE level (original, corrected), valid until the next keystroke
 * (service calls invalidateUndo()); undo re-checks the suffix before and
 * after and consumes the level. [WandPress] is the service-side dispatch enum
 * (T23f/g: wand press = APPLY or UNDO).
 *
 * UTF-16: delete/commit lengths are UTF-16 units — a surrogate pair counts
 * as 2 (emoji-safe). Rule 9: no typed content in logs; the only retained
 * content is the single undo pair.
 */
class RegionReplacer(private val onSuffixMismatch: () -> Unit = {}) {

    /** Wand-press purpose (service dispatch, §6 row / T23f). */
    enum class WandPress { APPLY, UNDO }

    /** InputConnection-shaped seam (JVM-testable fake, production adapter in T23g). */
    interface Editor {
        fun beginBatchEdit()
        fun deleteSurroundingText(beforeLength: Int, afterLength: Int)
        fun commitText(text: String, newCursorPosition: Int)
        fun endBatchEdit()
        fun getTextBeforeCursor(length: Int): String?
        fun hasSelection(): Boolean
    }

    /** Debug counter (corr_verify_fail, §8) — count only, no content. */
    var corrVerifyFail: Int = 0
        private set

    private var undoOriginal: String? = null
    private var undoCorrected: String? = null

    /** true while an undo level exists (wand may show the undo glyph, §6). */
    val hasUndoLevel: Boolean
        get() = undoOriginal != null

    /**
     * Applies [corrected] in place of [buffer] (the typed region). Returns
     * true only when the whole batch ran AND the post-edit suffix proof
     * passed; on any failure nothing or only the failed batch happened and
     * the caller sees false (see class doc for the precondition order).
     */
    fun replace(buffer: String, corrected: String, editor: Editor, dictationIdle: Boolean): Boolean {
        if (buffer == corrected) return false
        if (buffer.isEmpty()) return false
        if (!dictationIdle) return false
        if (editor.hasSelection()) return false
        // suffix proof BEFORE the batch (§5) — abort without any editor call
        if (editor.getTextBeforeCursor(buffer.length) != buffer) {
            corrVerifyFail++
            onSuffixMismatch()
            return false
        }
        editor.beginBatchEdit()
        editor.deleteSurroundingText(buffer.length, 0) // UTF-16 units (emoji-safe)
        editor.commitText(corrected, 1)
        editor.endBatchEdit()
        // RE-VERIFY (§5): mismatch -> counter + reset callback
        if (editor.getTextBeforeCursor(corrected.length) != corrected) {
            corrVerifyFail++
            onSuffixMismatch()
            return false
        }
        undoOriginal = buffer
        undoCorrected = corrected
        return true
    }

    /**
     * Restores the original of the last successful [replace] (single level,
     * valid until the next keystroke). Returns false without editor calls
     * when no level exists or the current field tail does not match the
     * corrected text.
     */
    fun undo(editor: Editor): Boolean {
        val original = undoOriginal ?: return false
        val corrected = requireNotNull(undoCorrected)
        if (editor.hasSelection()) return false
        if (editor.getTextBeforeCursor(corrected.length) != corrected) {
            corrVerifyFail++
            invalidateUndo()
            onSuffixMismatch()
            return false
        }
        editor.beginBatchEdit()
        editor.deleteSurroundingText(corrected.length, 0)
        editor.commitText(original, 1)
        editor.endBatchEdit()
        invalidateUndo() // single level (§5: undo until next keystroke)
        if (editor.getTextBeforeCursor(original.length) != original) {
            corrVerifyFail++
            onSuffixMismatch()
            return false
        }
        return true
    }

    /** The service calls this on the next keystroke (undo window ends, §5). */
    fun invalidateUndo() {
        undoOriginal = null
        undoCorrected = null
    }
}
