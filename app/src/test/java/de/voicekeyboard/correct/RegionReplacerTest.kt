package de.voicekeyboard.correct

import de.voicekeyboard.correct.TypedRegionTracker.ResetReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T23e (V0.1.47, docs/PLAN_V0147.md §5): wand-press replacement via the editor
 * seam — batch delete+commit, pre/post suffix proof, undo level, UTF-16
 * lengths, delta semantics. Fake editor records an op history (content-free,
 * rule 9); assertions carry indices/lengths only.
 */
class RegionReplacerTest {

    /** InputConnection-shaped fake: models only the text BEFORE the cursor. */
    private class FakeEditor(initialLeftContext: String = "") : RegionReplacer.Editor {
        val buf = StringBuilder(initialLeftContext)
        val ops = mutableListOf<String>()
        var selection = false

        /** When set, the NEXT getTextBeforeCursor AFTER a commit returns garbage. */
        var corruptAfterCommit = false
        private var pendingCorruption = false

        override fun beginBatchEdit() {
            ops += "begin"
        }

        override fun deleteSurroundingText(beforeLength: Int, afterLength: Int) {
            ops += "del:$beforeLength:$afterLength"
            buf.setLength(buf.length - beforeLength)
        }

        override fun commitText(text: String, newCursorPosition: Int) {
            ops += "commit:${text.length}"
            buf.append(text)
            if (corruptAfterCommit) pendingCorruption = true
        }

        override fun endBatchEdit() {
            ops += "end"
        }

        override fun getTextBeforeCursor(length: Int): String =
            if (pendingCorruption) {
                pendingCorruption = false
                "###corrupted###"
            } else {
                buf.substring(maxOf(0, buf.length - length))
            }

        override fun hasSelection(): Boolean = selection
    }

    private var mismatchResets = 0

    private fun replacer() = RegionReplacer(onSuffixMismatch = { mismatchResets++ })

    // ------------------------------------------------------- happy path replace

    @Test fun exactDeleteCommitWithSuffixProof() {
        val editor = FakeEditor("xx morgem")
        val r = replacer()
        assertTrue(r.replace("morgem", "morgen", editor, dictationIdle = true))
        assertEquals(listOf("begin", "del:6:0", "commit:6", "end"), editor.ops)
        assertTrue(editor.buf.toString().endsWith("xx morgen"))
        assertEquals(0, r.corrVerifyFail)
        assertTrue(r.hasUndoLevel)
    }

    // --------------------------------------------------------------- prechecks

    @Test fun suffixMismatchAbortsBeforeAnyEditorCall() {
        val editor = FakeEditor("xx morgE") // tail does NOT match the buffer
        val r = replacer()
        assertFalse(r.replace("morgem", "morgen", editor, dictationIdle = true))
        assertTrue(editor.ops.isEmpty()) // no begin, no delete, NO commit
        assertEquals(1, r.corrVerifyFail)
        assertEquals(1, mismatchResets)
        assertFalse(r.hasUndoLevel)
    }

    @Test fun selectionAbortsBeforeAnyEditorCall() {
        val editor = FakeEditor("xx morgem").apply { selection = true }
        val r = replacer()
        assertFalse(r.replace("morgem", "morgen", editor, dictationIdle = true))
        assertTrue(editor.ops.isEmpty())
    }

    @Test fun dictationBusyAndEqualTextAbort() {
        val editor = FakeEditor("xx morgem")
        val r = replacer()
        assertFalse(r.replace("morgem", "morgen", editor, dictationIdle = false))
        assertTrue(editor.ops.isEmpty())
        assertFalse(r.replace("morgem", "morgem", editor, dictationIdle = true))
        assertTrue(editor.ops.isEmpty())
    }

    @Test fun postCommitMismatchCountsVerifyFailAndSignalsReset() {
        val editor = FakeEditor("xx morgem").apply { corruptAfterCommit = true }
        val r = replacer()
        assertFalse(r.replace("morgem", "morgen", editor, dictationIdle = true))
        assertEquals(1, r.corrVerifyFail)
        assertEquals(1, mismatchResets)
        assertFalse(r.hasUndoLevel)
    }

    // -------------------------------------------------------------------- undo

    @Test fun undoRoundtripRestoresOriginalOnce() {
        val editor = FakeEditor("xx morgem")
        val r = replacer()
        assertTrue(r.replace("morgem", "morgen", editor, dictationIdle = true))
        assertTrue(r.undo(editor))
        assertEquals(listOf("begin", "del:6:0", "commit:6", "end", "begin", "del:6:0", "commit:6", "end"), editor.ops)
        assertTrue(editor.buf.toString().endsWith("xx morgem"))
        // single level: second undo does nothing
        assertFalse(r.undo(editor))
    }

    @Test fun invalidateUndoOnNextKeystroke() {
        val editor = FakeEditor("xx morgem")
        val r = replacer()
        assertTrue(r.replace("morgem", "morgen", editor, dictationIdle = true))
        r.invalidateUndo() // service calls this on the next keystroke
        assertFalse(r.hasUndoLevel)
        assertFalse(r.undo(editor))
    }

    // ---------------------------------------------------------------- lengths

    @Test fun surrogateLengthsAreUtf16Units() {
        val emoji = "\uD83D\uDC4D" // 2 UTF-16 units
        val editor = FakeEditor("a${emoji}!")
        val r = replacer()
        assertTrue(r.replace("${emoji}!", "X", editor, dictationIdle = true))
        assertEquals("del:3:0", editor.ops[1]) // emoji pair (2) + '!' (1) = 3 units
    }

    // --------------------------------------------------------- delta semantics

    @Test fun secondApplySeesOnlyTheBufferTypedSinceWandPress() {
        val tracker = TypedRegionTracker()
        val r = replacer()
        tracker.reset(ResetReason.FIELD_START)
        tracker.onTextInput("morgem ")

        val editor = FakeEditor("xx morgem ")
        assertTrue(r.replace(tracker.buffer, "morgen ", editor, dictationIdle = true))
        tracker.reset(ResetReason.WAND_PRESS, cursorAbs = 8)

        tracker.onTextInput("xy ")
        assertEquals("xy ", tracker.buffer)
        val editor2 = FakeEditor("xx morgen xy ")
        assertTrue(r.replace(tracker.buffer, "xyz ", editor2, dictationIdle = true))
        assertEquals("del:3:0", editor2.ops[1]) // only the new 3-unit region
        assertEquals(8, tracker.anchorAbs)
    }
}
