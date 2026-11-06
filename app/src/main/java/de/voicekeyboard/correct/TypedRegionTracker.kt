package de.voicekeyboard.correct

import de.voicekeyboard.editor.Graphemes

/**
 * T23d (V0.1.47, docs/PLAN_V0147.md §4): typed-region tracking + delta.
 * Pure Kotlin/JVM, style like editor/OwnershipTracker. The tracker holds the
 * region of text typed since the last anchor:
 *
 *  - onTextInput / onSpace / onEnter append; expectedCursor follows (absolute
 *    field position = anchorAbs + buffer length). Text while UNANCHORED
 *    (anchorAbs == -1, e.g. right after a selection move without a seeded
 *    cursor) is ignored — the service re-anchors via reset(reason, cursorAbs)
 *    from its next selection observation.
 *  - onBackspace removes the last GRAPHENE cluster (editor/Graphemes: surrogate
 *    pairs, ZWJ sequences, combining marks are ONE cluster). Backspace on an
 *    EMPTY buffer lets the region follow the cursor LEFT (anchorShiftLeft):
 *    anchor and cursor move by ONE UTF-16 unit — the exact cluster width of
 *    the field text is unknowable without field access; the suffix proof and
 *    the service's selection comparison are the authority, mismatch resets.
 *  - reset(reason, cursorAbs): FieldStart (cursor 0 by default), WandPress
 *    (DELTA: anchor behind the replaced text, seed the post-replacement
 *    cursor), DictationCommit (cursor stays), SelectionMove /
 *    NonEmptySelection / EditorChange (cursor unknown unless seeded),
 *    SuffixMismatch (from verify).
 *  - buffer cap 500 UTF-16 units: the oldest part drops, the anchor advances.
 *  - verify(fieldTail): fieldTail is getTextBeforeCursor(buffer.length + 8);
 *    the buffer must EXACTLY match the tail end (tolerance: exact) and the
 *    cursor invariant must hold; mismatch -> reset(SUFFIX_MISMATCH), false.
 *    null tail (editor failure) is always a mismatch. An empty buffer needs
 *    no proof (true) — there is nothing to replace.
 *
 * Rule 9: no typed content in logs — the class never logs; reset reasons are
 * codes only.
 */
class TypedRegionTracker {

    enum class ResetReason {
        FIELD_START,
        WAND_PRESS,
        DICTATION_COMMIT,
        SELECTION_MOVE,
        NON_EMPTY_SELECTION,
        EDITOR_CHANGE,
        SUFFIX_MISMATCH,
    }

    /** Absolute field offset where the region starts; -1 = unanchored. */
    var anchorAbs: Int = -1
        private set

    /** Absolute cursor position the tracker expects after the buffer; -1 unknown. */
    var expectedCursor: Int = -1
        private set

    /** The typed region (at most [CAP] UTF-16 units). */
    var buffer: String = ""
        private set

    /** Last reset reason (reason codes only, rule 9). */
    var lastResetReason: ResetReason? = null
        private set

    fun onTextInput(text: String) {
        if (text.isEmpty()) return
        if (anchorAbs < 0) return // unanchored: service must seed via reset(.., cursorAbs)
        val merged = buffer + text
        if (merged.length <= CAP) {
            buffer = merged
        } else { // oldest part drops, anchor advances (§4)
            val drop = merged.length - CAP
            buffer = merged.substring(drop)
            anchorAbs += drop
        }
        expectedCursor = anchorAbs + buffer.length
    }

    fun onSpace() = onTextInput(" ")

    fun onEnter() = onTextInput("\n")

    fun onBackspace() {
        if (anchorAbs < 0) return
        if (buffer.isEmpty()) {
            anchorShiftLeft()
            return
        }
        buffer = buffer.substring(0, Graphemes.lastGraphemeBoundary(buffer))
        expectedCursor = anchorAbs + buffer.length
    }

    /**
     * Backspace on an empty buffer: the region follows the cursor LEFT into
     * field text (documented T23d approximation — one UTF-16 unit per press;
     * the real deleted cluster width is only known to the field).
     */
    fun anchorShiftLeft() {
        if (anchorAbs < 0 || expectedCursor < 0) return
        if (anchorAbs > 0) anchorAbs--
        if (expectedCursor > 0) expectedCursor--
    }

    fun reset(reason: ResetReason, cursorAbs: Int = -1) {
        lastResetReason = reason
        buffer = ""
        when (reason) {
            ResetReason.FIELD_START -> {
                anchorAbs = if (cursorAbs >= 0) cursorAbs else 0
                expectedCursor = anchorAbs
            }
            ResetReason.WAND_PRESS, ResetReason.DICTATION_COMMIT -> {
                if (cursorAbs >= 0) {
                    anchorAbs = cursorAbs
                    expectedCursor = cursorAbs
                } else {
                    anchorAbs = expectedCursor // cursor stays where the region ended
                }
            }
            ResetReason.SELECTION_MOVE, ResetReason.NON_EMPTY_SELECTION,
            ResetReason.EDITOR_CHANGE, ResetReason.SUFFIX_MISMATCH,
            -> {
                if (cursorAbs >= 0) {
                    anchorAbs = cursorAbs
                    expectedCursor = cursorAbs
                } else {
                    anchorAbs = -1 // unknown until the service seeds the cursor
                    expectedCursor = -1
                }
            }
        }
    }

    /**
     * Suffix proof (§4): [fieldTail] is getTextBeforeCursor(buffer.length + 8).
     * The buffer must exactly match the tail end; otherwise the tracker resets
     * (SUFFIX_MISMATCH) and returns false.
     */
    fun verify(fieldTail: String?): Boolean {
        if (fieldTail == null) {
            reset(ResetReason.SUFFIX_MISMATCH)
            return false
        }
        if (buffer.isEmpty()) return true // nothing to replace -> nothing to prove
        if (!fieldTail.endsWith(buffer) || expectedCursor != anchorAbs + buffer.length) {
            reset(ResetReason.SUFFIX_MISMATCH)
            return false
        }
        return true
    }

    companion object {
        /** Region cap in UTF-16 units (§4). */
        const val CAP = 500
    }
}
