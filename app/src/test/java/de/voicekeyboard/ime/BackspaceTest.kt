package de.voicekeyboard.ime

import de.voicekeyboard.keyboard.BackspaceRepeat
import de.voicekeyboard.keyboard.BackspaceRepeatMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
 *  6. B2w: from 2000 ms held on WHOLE WORDS instead of characters — char ticks
 *     450..1990, then NO char tick anymore; the first word tick EXACTLY at
 *     2000 ms, then every 167 ms (6 words/s); UP stops in word mode too and
 *     a fresh gesture starts in char mode again.
 *  7. B2w word boundaries: spaces, line start, only-spaces prefix, cursor
 *     mid-word — pure [backspaceWordLengthOf] + the seam branch
 *     [backspaceDeleteWordOnce].
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
            if (r.tick(t) != null) {
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
        for (u in 1010..2000 step 10) if (r.tick(u.toLong()) != null) after++
        assertEquals("after UP: no further deletes", 0, after)
    }

    // B2 red test 2: no repeat < 450 ms; stop deterministic; tap ⇒ exactly 1.
    @Test fun `B2 no repeat before 450ms - deterministic stop - tap exactly one`() {
        val r = BackspaceRepeat()
        assertTrue(r.down(0))
        var deletes = 1
        for (t in 0..440 step 10) if (r.tick(t.toLong()) != null) deletes++
        assertEquals("no repeat before 450 ms", 1, deletes)
        assertEquals("first repeat exactly at 450", BackspaceRepeatMode.CHARACTER, r.tick(450))
        // UP/CANCEL/detach: deterministically finished — every later firing dead
        r.cancel()
        assertNull(r.tick(451))
        assertNull(r.tick(10_000))
        // short tap: DOWN + immediate UP ⇒ EXACTLY ONE delete
        val tap = BackspaceRepeat()
        var tapDeletes = 0
        if (tap.down(100)) tapDeletes++
        tap.cancel()
        for (t in 110..1000 step 10) if (tap.tick(t.toLong()) != null) tapDeletes++
        assertEquals("short tap: exactly ONE delete", 1, tapDeletes)
        // after cancel the schedule is armable FRESH (the next gesture starts new)
        assertTrue("re-armable after the stop", tap.down(2_000))
        assertEquals(
            "and fires again from 450 ms on",
            BackspaceRepeatMode.CHARACTER,
            tap.tick(2_450),
        )
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

    // B2w/T14 red test 6: switch point 2000 ms — char ticks 450..1990 (70 grid),
    // then NO char tick anymore; the FIRST word tick EXACTLY at 2000 ms,
    // then every 167 ms (6 words/s).
    @Test fun `B2w after 2000ms whole words - first word tick exactly at the threshold`() {
        val r = BackspaceRepeat()
        assertTrue(r.down(0))
        val chars = mutableListOf<Long>()
        val words = mutableListOf<Long>()
        var t = 0L
        while (t <= 3000) {
            when (r.tick(t)) {
                BackspaceRepeatMode.CHARACTER -> chars += t
                BackspaceRepeatMode.WORD -> words += t
                null -> Unit
            }
            t += 1
        }
        assertEquals(
            "char ticks exactly at 450..1990 in the 70 grid",
            (0..22).map { 450L + 70L * it },
            chars,
        )
        assertEquals(
            "from the threshold on NO character tick fires anymore",
            0,
            chars.count { it >= BackspaceRepeat.WORD_THRESHOLD_MS },
        )
        assertEquals(
            "word ticks: first EXACTLY at 2000, then every 167 ms (6 words/s)",
            listOf(2000L, 2167L, 2334L, 2501L, 2668L, 2835L),
            words,
        )
        assertEquals(
            "rate window [2000,3000): EXACTLY 6 word ticks (next one at 3002)",
            6,
            words.count { it >= 2000L && it < 3000L },
        )
        r.cancel() // UP in word mode: deterministic stop
        assertNull("after UP no further word tick (next would be 3002)", r.tick(3002))
    }

    // B2w red test 7: stop in word mode; re-arm starts FRESH in char mode.
    @Test fun `B2w stop in word mode - re-arm fresh in char mode`() {
        val r = BackspaceRepeat()
        assertTrue(r.down(0))
        assertEquals(BackspaceRepeatMode.CHARACTER, r.tick(450))
        assertEquals("switch to word at the threshold", BackspaceRepeatMode.WORD, r.tick(2000))
        r.cancel() // UP in word mode
        assertNull(r.tick(2333))
        // re-arm: the NEXT gesture starts FRESH in character mode (450/70 grid)
        assertTrue(r.down(10_000))
        assertEquals(BackspaceRepeatMode.CHARACTER, r.tick(10_450))
        assertEquals("before the threshold still char mode", BackspaceRepeatMode.CHARACTER, r.tick(10_520))
        assertEquals("word again only after 2000 ms", BackspaceRepeatMode.WORD, r.tick(12_000))
        // T14 lag case: if the timer lags (first firing at 2500), NOTHING is
        // caught up — the next word tick follows after 167 ms.
        val lag = BackspaceRepeat()
        assertTrue(lag.down(0))
        assertEquals(
            "lagged firing (2500 > threshold 2000) becomes a word tick",
            BackspaceRepeatMode.WORD,
            lag.tick(2500),
        )
        assertEquals("lag case: nextDelayMs(2500) == 167", 167L, lag.nextDelayMs(2500))
        lag.cancel()
    }

    // B2w red test 8: word boundaries — pure length logic (spaces, line start,
    // only-spaces prefix, cursor mid-word).
    @Test fun `B2w word boundaries - spaces line start only-spaces prefix mid-word`() {
        // word + spaces before it (normal case)
        assertEquals("'Hallo Welt|' ⇒ ' Welt' (word + leading space)", 5, backspaceWordLengthOf("Hallo Welt"))
        assertEquals("'Hallo|' ⇒ 'Hallo' (up to the text start)", 5, backspaceWordLengthOf("Hallo"))
        assertEquals("'a  b|' ⇒ '  b' (ALL spaces between)", 3, backspaceWordLengthOf("a  b"))
        // cursor MINTTEN im Wort: only the part left of the cursor
        assertEquals("'Hal|lo' ⇒ 'Hal'", 3, backspaceWordLengthOf("Hal"))
        // only-spaces prefix before the cursor: spaces + word in ONE tick
        assertEquals("'abc   |' ⇒ 'abc   '", 6, backspaceWordLengthOf("abc   "))
        assertEquals("line start: ONLY the spaces, never the '\n'", 3, backspaceWordLengthOf("abc\n   "))
        // line start: NO deletion (the word deletion never crosses a line)
        assertEquals("cursor directly after '\n' ⇒ 0", 0, backspaceWordLengthOf("Zeile1\n"))
        assertEquals("'abc\ndef|' ⇒ 'def'", 3, backspaceWordLengthOf("abc\ndef"))
        assertEquals("empty text ⇒ 0", 0, backspaceWordLengthOf(""))
    }

    // B2w red test 9: word tick over the editor seam — selection atomic, ONE
    // deleteSurroundingText with ALL units, line start/empty = NO-OP (never
    // a DEL key event in word mode).
    @Test fun `B2w word tick over the editor seam - no DEL key`() {
        val e = FakeBackspaceEditor(selected = null, before = "Hallo Welt")
        backspaceDeleteWordOnce(e)
        assertEquals("ONE deleteSurroundingText for the whole word", 1, e.deleteGraphemeCalls)
        assertEquals("' Welt' = 5 UTF-16 units in ONE call", 5, e.lastDeleteUnits)
        assertEquals(0, e.commitEmptyCalls)
        assertEquals(0, e.deleteKeyCalls)
        // non-empty selection stays atomic (like the character path)
        val sel = FakeBackspaceEditor(selected = "ausgewaehlt", before = "Text davor")
        backspaceDeleteWordOnce(sel)
        assertEquals(1, sel.commitEmptyCalls)
        assertEquals(0, sel.deleteGraphemeCalls)
        assertEquals(0, sel.deleteKeyCalls)
        // line start: NO-OP — never a DEL key event (no line crossing)
        val lineStart = FakeBackspaceEditor(selected = null, before = "Zeile1\n")
        backspaceDeleteWordOnce(lineStart)
        assertEquals("line start: no operation at all", 0, lineStart.totalOps())
        // empty buffer: also NO-OP in word mode (no DEL key fallback)
        val empty = FakeBackspaceEditor(selected = null, before = null)
        backspaceDeleteWordOnce(empty)
        assertEquals("empty buffer: no DEL key in word mode", 0, empty.totalOps())
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
