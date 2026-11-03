package de.voicekeyboard.ime

import de.voicekeyboard.keyboard.BackspaceRepeat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * B2 (PLAN_V0133 §B2) — backspace repeat and complete selection deletion:
 *  1. Fake schedule: initial delete at 0 ms, repeats at 450, 520, 590, 660,
 *     730, 800, 870, 940 ms — UP at 1000 ms ⇒ EXACTLY 9 deletes, none after.
 *  2. No repeat before 450 ms; UP/CANCEL/detach stops deterministically; a
 *     short tap produces exactly ONE delete.
 *  3. Non-empty selection ⇒ EXACTLY 1× commitText("",1), 0 deleteSurroundingText,
 *     0 DEL key events — the complete selection is replaced.
 *  4. Empty/null selection ⇒ stock path grapheme clusters; no double delete.
 *  5. The repeat after a dropped selection keeps deleting over the normal
 *     grapheme-cluster/fallback path.
 *
 * Pure JVM logic (no Android): schedule from [BackspaceRepeat], editor branch
 * from [backspaceDeleteOnce] across the thin [BackspaceEditor] seam.
 */
class BackspaceTest {

    // B2 red test 1: fake schedule 0/450/520/…/940 ⇒ UP at 1000 ms exactly 9.
    @Test fun `B2 schedule - exactly 9 deletes then stop`() {
        val r = BackspaceRepeat()
        var deletes = 0
        assertTrue("DOWN: exactly ONE immediate action", r.down(0))
        deletes++
        val fired = mutableListOf<Long>()
        var t = 0L
        while (t <= 1000) {
            if (r.tick(t)) {
                deletes++
                fired += t
            }
            t += 10
        }
        assertEquals(
            "Repeats exactly at 450..940 in the 70 grid",
            listOf(450L, 520L, 590L, 660L, 730L, 800L, 870L, 940L),
            fired,
        )
        r.cancel() // UP at 1000 ms
        assertEquals("exactly 9 deletes (1 initial + 8 repeats)", 9, deletes)
        // afterwards NO more — not even with eternal continued ticking
        var after = 0
        for (u in 1010..2000 step 10) if (r.tick(u.toLong())) after++
        assertEquals("after UP: no further deletes", 0, after)
    }

    // B2 red test 2: no repeat < 450 ms; stop deterministic; tap ⇒ exactly 1.
    @Test fun `B2 no repeat before 450ms - deterministic stop - tap exactly one`() {
        val r = BackspaceRepeat()
        assertTrue(r.down(0))
        var deletes = 1
        for (t in 0..440 step 10) if (r.tick(t.toLong())) deletes++
        assertEquals("no repeat before 450 ms", 1, deletes)
        assertTrue("first repeat exactly at 450", r.tick(450))
        // UP/CANCEL/detach: deterministically finished — every later firing dead
        r.cancel()
        assertFalse(r.tick(451))
        assertFalse(r.tick(10_000))
        // short tap: DOWN + immediate UP ⇒ EXACTLY ONE delete
        val tap = BackspaceRepeat()
        var tapDeletes = 0
        if (tap.down(100)) tapDeletes++
        tap.cancel()
        for (t in 110..1000 step 10) if (tap.tick(t.toLong())) tapDeletes++
        assertEquals("short tap: exactly ONE delete", 1, tapDeletes)
        // after cancel the schedule is armable FRESH (the next gesture starts new)
        assertTrue("re-armable after the stop", tap.down(2_000))
        assertTrue("and fires again from 450 ms on", tap.tick(2_450))
    }

    // B2 red test 3: non-empty selection atomic — EXACTLY 1× commitText("",1),
    // 0 deleteSurroundingText, 0 DEL key events; selection completely replaced.
    @Test fun `B2 non-empty selection deleted atomically - no DEL no deleteSurrounding`() {
        val e = FakeBackspaceEditor(selected = "ausgewaehlt", before = "Text davor")
        backspaceDeleteOnce(e)
        assertEquals("EXACTLY ONE empty commitText (atomic replacement)", 1, e.commitEmptyCalls)
        assertEquals("NO deleteSurroundingText", 0, e.deleteGraphemeCalls)
        assertEquals("NO DEL KeyEvent", 0, e.deleteKeyCalls)
        assertEquals("exactly ONE operation in total (no double delete)", 1, e.totalOps())
        assertFalse("Selektion komplett weg", e.hasSelection)
    }

    // B2 red test 4: empty/null selection ⇒ stock path grapheme clusters or
    // DEL-key fallback; one call takes effect EXACTLY once (no double delete DOWN+UP).
    @Test fun `B2 empty selection - grapheme cluster stock path without double delete`() {
        // null selection, text before the cursor: ONE grapheme cluster at the END
        // (a + U+0308 = NFD ä ⇒ 2 UTF-16 units, one cluster)
        val e = FakeBackspaceEditor(selected = null, before = "lloHa\u0308")
        backspaceDeleteOnce(e)
        assertEquals("grapheme-cluster path (retained)", 1, e.deleteGraphemeCalls)
        assertEquals("ONE grapheme cluster = 2 UTF-16 units (a+U+0308)", 2, e.lastDeleteUnits)
        assertEquals(0, e.commitEmptyCalls)
        assertEquals(0, e.deleteKeyCalls)
        assertEquals("exactly ONE operation per call", 1, e.totalOps())
        // EMPTY selection ("" from getSelectedText) is like none: stock path
        val emptySel = FakeBackspaceEditor(selected = "", before = "abc")
        backspaceDeleteOnce(emptySel)
        assertEquals("empty selection ⇒ grapheme path", 1, emptySel.deleteGraphemeCalls)
        assertEquals(0, emptySel.commitEmptyCalls)
        // empty buffer: DEL-key fallback (stock)
        val empty = FakeBackspaceEditor(selected = null, before = null)
        backspaceDeleteOnce(empty)
        assertEquals("DEL-key fallback on an empty buffer", 1, empty.deleteKeyCalls)
        assertEquals(0, empty.deleteGraphemeCalls)
    }

    // B2 red test 5: the repeat after a dropped selection keeps deleting over
    // the normal grapheme-cluster/fallback path (the same service branch).
    @Test fun `B2 repeat after a dropped selection keeps deleting normally`() {
        val e = FakeBackspaceEditor(selected = "abc", before = "Rest Text")
        backspaceDeleteOnce(e) // 1st press: selection atomically replaced (gone)
        assertEquals(1, e.commitEmptyCalls)
        assertFalse(e.hasSelection)
        e.selected = null // the selection is gone due to the commit
        repeat(3) { backspaceDeleteOnce(e) } // repeat ticks: the SAME branch
        assertEquals("no further commitEmpty", 1, e.commitEmptyCalls)
        assertEquals("grapheme clusters keep being deleted", 3, e.deleteGraphemeCalls)
        assertEquals(0, e.deleteKeyCalls)
    }

    /** B2: fake of the editor seam — records all operations (no Android). */
    private class FakeBackspaceEditor(
        var selected: CharSequence?,
        var before: CharSequence? = null,
    ) : BackspaceEditor {
        var commitEmptyCalls = 0
        var deleteGraphemeCalls = 0
        var deleteKeyCalls = 0
        var lastDeleteUnits = -1
        var hasSelection = selected?.isNotEmpty() == true

        override fun selection(): CharSequence? =
            selected?.takeIf { hasSelection && it.isNotEmpty() }

        override fun commitEmptyReplacement() {
            commitEmptyCalls++
            hasSelection = false
        }

        override fun textBeforeCursor(maxChars: Int): CharSequence? = before

        override fun deleteGraphemeBeforeCursor(units: Int) {
            deleteGraphemeCalls++
            lastDeleteUnits = units
        }

        override fun sendDeleteKeyEvent() {
            deleteKeyCalls++
        }

        fun totalOps() = commitEmptyCalls + deleteGraphemeCalls + deleteKeyCalls
    }
}
