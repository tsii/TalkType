package de.voicekeyboard.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OwnershipTrackerTest {

    @Test fun baselineBeforeFirstOwnEditIsNotForeign() {
        val t = OwnershipTracker()
        // the system callback at dictation start (cursor anywhere) is the baseline
        t.onUpdateSelection(0, 0, 10, 10)
        assertTrue(t.isOwned("", null)) // nothing written yet → ownership possible
    }

    @Test fun ownSelectionCallbackDoesNotLoseOwnership() {
        val t = OwnershipTracker()
        t.onOwnEdit("Hallo")
        t.onUpdateSelection(5, 5, 10, 10) // callback of our own edit
        assertFalse(t.ownershipLost)
        assertTrue(t.isOwned("Hallo", "Nutzer Hallo"))
    }

    @Test fun foreignInputIsDetectedAndBlocksForever() {
        val t = OwnershipTracker()
        t.onUpdateSelection(0, 0, 0, 0)      // baseline
        t.onOwnEdit("Hallo")
        t.onUpdateSelection(0, 0, 5, 5)      // own callback
        t.onUpdateSelection(5, 5, 9, 9)      // user moved the cursor/typed
        assertTrue(t.ownershipLost)
        // afterwards NO more overwriting — not even with matching context
        assertFalse(t.isOwned("Hallo", "Hallo"))
        assertFalse(t.isOwned("Hallo", "Nutzer Hallo"))
        t.onUpdateSelection(9, 9, 2, 2)       // further events change nothing
        assertFalse(t.isOwned("Hallo", "Hallo"))
        // only a new session restores ownership
        t.reset()
        assertFalse(t.ownershipLost)
    }

    @Test fun selectionRangeIsAlwaysForeign() {
        val t = OwnershipTracker()
        t.onOwnEdit("Hi")
        t.onUpdateSelection(0, 0, 3, 7) // selection (never from commitText)
        assertTrue(t.ownershipLost)
    }

    @Test fun contextMismatchIsNotOwned() {
        val t = OwnershipTracker()
        t.onOwnEdit("Welt")
        // suffix rule: user text BEFORE our area is ok …
        assertTrue(t.isOwned("Welt", "Hallo Welt"))
        // … divergent context written behind our text is not
        assertFalse(t.isOwned("Welt", "WeltX"))
        assertTrue(t.isOwned("Welt", "Welt"))
        assertFalse(t.isOwned("Welt", null))
    }

    @Test fun editorChangeRaisesGenerationAndDropsOwnership() {
        val t = OwnershipTracker()
        val before = t.generation
        t.onEditorChanged()
        assertEquals(before + 1, t.generation)
        assertTrue(t.ownershipLost)
    }

    @Test fun emptyExpectedTextOwnedWhileNotLost() {
        val t = OwnershipTracker()
        assertTrue(t.isOwned("", null))
        assertFalse(t.ownershipLost)
    }
}
