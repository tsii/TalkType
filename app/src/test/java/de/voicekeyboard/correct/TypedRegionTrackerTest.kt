package de.voicekeyboard.correct

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T23d (V0.1.47, docs/PLAN_V0147.md §4): typed-region tracking + delta.
 * Pure JVM, style like editor/OwnershipTrackerTest. Rule 9: no typed text in
 * assertion messages — indices and lengths only.
 */
class TypedRegionTrackerTest {

    private val tracker = TypedRegionTracker()

    // ------------------------------------------------- append + grapheme backspace

    @Test fun appendAndBackspaceShrinkByGraphemeCluster() {
        tracker.reset(TypedRegionTracker.ResetReason.FIELD_START)
        tracker.onTextInput("Hallo")
        assertEquals("Hallo", tracker.buffer)
        assertEquals(0, tracker.anchorAbs)
        assertEquals(5, tracker.expectedCursor)

        tracker.onBackspace()
        assertEquals("Hall", tracker.buffer)
        assertEquals(4, tracker.expectedCursor)
    }

    @Test fun backspaceRemovesSurrogatePairAsOneCluster() {
        tracker.reset(TypedRegionTracker.ResetReason.FIELD_START)

        val emoji = "\uD83D\uDC4D" // 👍 (one surrogate pair = ONE cluster, 2 UTF-16 units)
        tracker.onTextInput("a")
        tracker.onTextInput(emoji)
        assertEquals(3, tracker.buffer.length) // 1 + 2 units
        tracker.onBackspace()
        assertEquals("a", tracker.buffer)
        assertEquals(1, tracker.expectedCursor)
    }

    @Test fun backspaceRemovesZwjFamilyEmojiAsOneCluster() {
        tracker.reset(TypedRegionTracker.ResetReason.FIELD_START)
        val family = "\uD83D\uDC68\u200D\uD83D\uDC69\u200D\uD83D\uDC67" // ZWJ sequence
        tracker.onTextInput(family)
        tracker.onBackspace()
        assertEquals("", tracker.buffer)
        assertEquals(0, tracker.expectedCursor)
    }

    @Test fun backspaceOnEmptyBufferFollowsCursorLeft() {
        tracker.reset(TypedRegionTracker.ResetReason.FIELD_START, cursorAbs = 10)
        tracker.onBackspace() // buffer empty -> anchorShiftLeft()
        assertEquals(9, tracker.anchorAbs)
        assertEquals(9, tracker.expectedCursor)
        assertEquals("", tracker.buffer)
    }

    // ------------------------------------------------------------------- delta

    @Test fun deltaAfterWandPressAnchorsBehindReplacedText() {
        tracker.reset(TypedRegionTracker.ResetReason.FIELD_START)
        tracker.onTextInput("morgem ")
        assertEquals(7, tracker.expectedCursor)

        // wand press replaced "morgem " with "morgen " -> cursor 7, region restarts
        tracker.reset(TypedRegionTracker.ResetReason.WAND_PRESS, cursorAbs = 7)
        assertEquals("", tracker.buffer)
        assertEquals(7, tracker.anchorAbs)
        assertEquals(7, tracker.expectedCursor)

        tracker.onTextInput("xy")
        assertEquals("xy", tracker.buffer)
        assertEquals(9, tracker.expectedCursor)
        // suffix proof: region covers ONLY the text typed since the press
        assertTrue(tracker.verify("ABCDExy"))
    }

    // ------------------------------------------------------------------ resets

    @Test fun dictationCommitResetsRegionKeepsCursor() {
        tracker.reset(TypedRegionTracker.ResetReason.FIELD_START)
        tracker.onTextInput("abc")
        tracker.reset(TypedRegionTracker.ResetReason.DICTATION_COMMIT)
        assertEquals("", tracker.buffer)
        assertEquals(3, tracker.anchorAbs)
        assertEquals(3, tracker.expectedCursor)
        assertEquals(TypedRegionTracker.ResetReason.DICTATION_COMMIT, tracker.lastResetReason)
    }

    @Test fun selectionMoveResetSeedsCursorWhenGiven() {
        tracker.reset(TypedRegionTracker.ResetReason.FIELD_START)
        tracker.onTextInput("abcd")
        tracker.reset(TypedRegionTracker.ResetReason.SELECTION_MOVE, cursorAbs = 2)
        assertEquals("", tracker.buffer)
        assertEquals(2, tracker.anchorAbs)
        assertEquals(2, tracker.expectedCursor)
    }

    @Test fun selectionMoveWithoutCursorLeavesAnchorUnknownUntilSeeded() {
        tracker.reset(TypedRegionTracker.ResetReason.FIELD_START)
        tracker.onTextInput("abcd")
        tracker.reset(TypedRegionTracker.ResetReason.SELECTION_MOVE)
        assertEquals("", tracker.buffer)
        assertEquals(-1, tracker.anchorAbs)
        // text is ignored while unanchored (service must seed the cursor)
        tracker.onTextInput("zz")
        assertEquals("", tracker.buffer)
    }

    @Test fun nonEmptySelectionAndEditorChangeReset() {
        tracker.reset(TypedRegionTracker.ResetReason.FIELD_START)
        tracker.onTextInput("abc")
        tracker.reset(TypedRegionTracker.ResetReason.NON_EMPTY_SELECTION)
        assertEquals("", tracker.buffer)
        assertEquals(TypedRegionTracker.ResetReason.NON_EMPTY_SELECTION, tracker.lastResetReason)

        tracker.reset(TypedRegionTracker.ResetReason.FIELD_START)
        tracker.onTextInput("abc")
        tracker.reset(TypedRegionTracker.ResetReason.EDITOR_CHANGE)
        assertEquals("", tracker.buffer)
        assertEquals(TypedRegionTracker.ResetReason.EDITOR_CHANGE, tracker.lastResetReason)
    }

    // --------------------------------------------------------------------- cap

    @Test fun bufferCap500DropsOldestAndAdvancesAnchor() {
        tracker.reset(TypedRegionTracker.ResetReason.FIELD_START)
        tracker.onTextInput("x".repeat(600))
        assertEquals(500, tracker.buffer.length)
        assertEquals(100, tracker.anchorAbs)
        assertEquals(600, tracker.expectedCursor)
    }

    // -------------------------------------------------------------- suffix proof

    @Test fun suffixMismatchResetsAndReturnsFalse() {
        tracker.reset(TypedRegionTracker.ResetReason.FIELD_START)
        tracker.onTextInput("abc")
        assertFalse(tracker.verify("zzzabz"))
        assertEquals("", tracker.buffer)
        assertEquals(TypedRegionTracker.ResetReason.SUFFIX_MISMATCH, tracker.lastResetReason)
        assertFalse(tracker.verify(null))
    }

    @Test fun suffixProofPassesOnExactTailMatch() {
        tracker.reset(TypedRegionTracker.ResetReason.FIELD_START)
        tracker.onTextInput("abc")
        assertTrue(tracker.verify("xxabc")) // tail = getTextBeforeCursor(len+8)
        assertEquals("abc", tracker.buffer)
    }

    // -------------------------------------------------------------------- keys

    @Test fun enterAppendsNewline() {
        tracker.reset(TypedRegionTracker.ResetReason.FIELD_START)
        tracker.onTextInput("Hallo")
        tracker.onEnter()
        assertEquals("Hallo\n", tracker.buffer)
        assertEquals(6, tracker.expectedCursor)
    }

    @Test fun spaceAppendsOneSpace() {
        tracker.reset(TypedRegionTracker.ResetReason.FIELD_START)
        tracker.onTextInput("a")
        tracker.onSpace()
        assertEquals("a ", tracker.buffer)
        assertEquals(2, tracker.expectedCursor)
    }
}
