package de.voicekeyboard.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OwnershipTrackerTest {

    @Test fun baselineBeforeFirstOwnEditIsNotForeign() {
        val t = OwnershipTracker()
        // System-Callback beim Diktat-Start (Cursor irgendwo) ist die Baseline
        t.onUpdateSelection(0, 0, 10, 10)
        assertTrue(t.isOwned("", null)) // noch nichts geschrieben → Besitz möglich
    }

    @Test fun ownSelectionCallbackDoesNotLoseOwnership() {
        val t = OwnershipTracker()
        t.onOwnEdit("Hallo")
        t.onUpdateSelection(5, 5, 10, 10) // Callback unseres eigenen Edits
        assertFalse(t.ownershipLost)
        assertTrue(t.isOwned("Hallo", "Nutzer Hallo"))
    }

    @Test fun foreignInputIsDetectedAndBlocksForever() {
        val t = OwnershipTracker()
        t.onUpdateSelection(0, 0, 0, 0)      // Baseline
        t.onOwnEdit("Hallo")
        t.onUpdateSelection(0, 0, 5, 5)      // eigener Callback
        t.onUpdateSelection(5, 5, 9, 9)      // Nutzer hat Cursor bewegt/getippt
        assertTrue(t.ownershipLost)
        // danach KEINE Überschreibungen mehr — auch nicht mit passendem Kontext
        assertFalse(t.isOwned("Hallo", "Hallo"))
        assertFalse(t.isOwned("Hallo", "Nutzer Hallo"))
        t.onUpdateSelection(9, 9, 2, 2)       // weitere Events ändern nichts
        assertFalse(t.isOwned("Hallo", "Hallo"))
        // erst eine neue Sitzung stellt den Besitz wieder her
        t.reset()
        assertFalse(t.ownershipLost)
    }

    @Test fun selectionRangeIsAlwaysForeign() {
        val t = OwnershipTracker()
        t.onOwnEdit("Hi")
        t.onUpdateSelection(0, 0, 3, 7) // Auswahl (nie von commitText)
        assertTrue(t.ownershipLost)
    }

    @Test fun contextMismatchIsNotOwned() {
        val t = OwnershipTracker()
        t.onOwnEdit("Welt")
        // Suffix-Regel: Nutzertext VOR unserem Bereich ist ok …
        assertTrue(t.isOwned("Welt", "Hallo Welt"))
        // … abweichender/hinter unserm Text geschriebener Kontext nicht
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
