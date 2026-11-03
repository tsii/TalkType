package de.voicekeyboard.ime

import de.voicekeyboard.keyboard.BackspaceRepeat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * B2 (PLAN_V0133 §B2) — Backspace-Repeat und komplette Selektionslöschung:
 *  1. Fake-Zeitplan: initialer Delete bei 0 ms, Repeats bei 450, 520, 590, 660,
 *     730, 800, 870, 940 ms — UP bei 1000 ms ⇒ GENAU 9 Deletes, danach keine.
 *  2. Kein Repeat vor 450 ms; UP/CANCEL/Detach stoppt deterministisch; kurzer
 *     Tap erzeugt genau EINEN Delete.
 *  3. Nichtleere Selektion ⇒ GENAU 1× commitText("",1), 0 deleteSurroundingText,
 *     0 DEL-KeyEvents — die komplette Selektion ist ersetzt.
 *  4. Leere/null-Selektion ⇒ Bestandspfad Graphemcluster; kein Doppeldelete.
 *  5. Repeat nach weggefallener Selektion löscht weiter über den normalen
 *     Graphemcluster-/Fallbackpfad.
 *
 * Reine JVM-Logik (kein Android): Zeitplan aus [BackspaceRepeat], Editor-zweig
 * aus [backspaceDeleteOnce] über die dünne [BackspaceEditor]-Naht.
 */
class BackspaceTest {

    // B2-Rote-Test 1: Fake-Zeitplan 0/450/520/…/940 ⇒ UP bei 1000 ms exakt 9.
    @Test fun `B2 Zeitplan - exakt 9 Deletes dann stopp`() {
        val r = BackspaceRepeat()
        var deletes = 0
        assertTrue("DOWN: genau EINE sofortige Aktion", r.down(0))
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
            "Repeats exakt bei 450..940 im 70er-Raster",
            listOf(450L, 520L, 590L, 660L, 730L, 800L, 870L, 940L),
            fired,
        )
        r.cancel() // UP bei 1000 ms
        assertEquals("exakt 9 Deletes (1 initial + 8 Repeats)", 9, deletes)
        // danach KEINE weiteren — auch nicht bei ewigem Weiterticken
        var after = 0
        for (u in 1010..2000 step 10) if (r.tick(u.toLong())) after++
        assertEquals("nach UP: null weitere Deletes", 0, after)
    }

    // B2-Rote-Test 2: kein Repeat < 450 ms; Stopp deterministisch; Tap ⇒ genau 1.
    @Test fun `B2 kein Repeat vor 450ms - Stopp deterministisch - Tap genau einer`() {
        val r = BackspaceRepeat()
        assertTrue(r.down(0))
        var deletes = 1
        for (t in 0..440 step 10) if (r.tick(t.toLong())) deletes++
        assertEquals("kein Repeat vor 450 ms", 1, deletes)
        assertTrue("erster Repeat exakt bei 450", r.tick(450))
        // UP/CANCEL/Detach: deterministisch beendet — jede spätere Feuerung tot
        r.cancel()
        assertFalse(r.tick(451))
        assertFalse(r.tick(10_000))
        // kurzer Tap: DOWN + sofortiges UP ⇒ GENAU EIN Delete
        val tap = BackspaceRepeat()
        var tapDeletes = 0
        if (tap.down(100)) tapDeletes++
        tap.cancel()
        for (t in 110..1000 step 10) if (tap.tick(t.toLong())) tapDeletes++
        assertEquals("kurzer Tap: genau EIN Delete", 1, tapDeletes)
        // nach cancel ist der Plan NEU armierbar (nächste Geste startet frisch)
        assertTrue("neu armierbar nach Stopp", tap.down(2_000))
        assertTrue("und feuert wieder ab 450 ms", tap.tick(2_450))
    }

    // B2-Rote-Test 3: nichtleere Selektion atomar — GENAU 1× commitText("",1),
    // 0 deleteSurroundingText, 0 DEL-KeyEvents; Selektion komplett ersetzt.
    @Test fun `B2 nichtleere Selektion atomar geloescht - kein DEL kein deleteSurrounding`() {
        val e = FakeBackspaceEditor(selected = "ausgewaehlt", before = "Text davor")
        backspaceDeleteOnce(e)
        assertEquals("GENAU EIN leeres commitText (atomare Ersetzung)", 1, e.commitEmptyCalls)
        assertEquals("KEIN deleteSurroundingText", 0, e.deleteGraphemeCalls)
        assertEquals("KEIN DEL-KeyEvent", 0, e.deleteKeyCalls)
        assertEquals("genau EINE Operation insgesamt (kein Doppeldelete)", 1, e.totalOps())
        assertFalse("Selektion komplett weg", e.hasSelection)
    }

    // B2-Rote-Test 4: leere/null-Selektion ⇒ Bestandspfad Graphemcluster bzw.
    // DEL-Key-Fallback; ein Aufruf wirkt GENAU einmal (kein Doppeldelete DOWN+UP).
    @Test fun `B2 leere Selektion - Graphemcluster-Bestandspfad ohne Doppeldelete`() {
        // null-Selektion, Text vor Cursor: EIN Graphemcluster am ENDE
        // (a + U+0308 = NFD-ä ⇒ 2 UTF-16-Einheiten, ein Cluster)
        val e = FakeBackspaceEditor(selected = null, before = "lloHa\u0308")
        backspaceDeleteOnce(e)
        assertEquals("Graphemcluster-Pfad (Bestand)", 1, e.deleteGraphemeCalls)
        assertEquals("EIN Graphemcluster = 2 UTF-16-Einheiten (a+U+0308)", 2, e.lastDeleteUnits)
        assertEquals(0, e.commitEmptyCalls)
        assertEquals(0, e.deleteKeyCalls)
        assertEquals("genau EINE Operation pro Aufruf", 1, e.totalOps())
        // LEERE Selektion ("" von getSelectedText) ist wie keine: Bestandspfad
        val emptySel = FakeBackspaceEditor(selected = "", before = "abc")
        backspaceDeleteOnce(emptySel)
        assertEquals("leere Selektion ⇒ Graphempfad", 1, emptySel.deleteGraphemeCalls)
        assertEquals(0, emptySel.commitEmptyCalls)
        // leerer Puffer: DEL-Key-Fallback (Bestand)
        val empty = FakeBackspaceEditor(selected = null, before = null)
        backspaceDeleteOnce(empty)
        assertEquals("DEL-Key-Fallback bei leerem Puffer", 1, empty.deleteKeyCalls)
        assertEquals(0, empty.deleteGraphemeCalls)
    }

    // B2-Rote-Test 5: Repeat nach weggefallener Selektion löscht WEITER über den
    // normalen Graphemcluster-/Fallbackpfad (derselbe Servicezweig).
    @Test fun `B2 Repeat nach weggefallener Selektion loescht normal weiter`() {
        val e = FakeBackspaceEditor(selected = "abc", before = "Rest Text")
        backspaceDeleteOnce(e) // 1. Press: Selektion atomar ersetzt (weg)
        assertEquals(1, e.commitEmptyCalls)
        assertFalse(e.hasSelection)
        e.selected = null // Selektion ist durch den Commit weg
        repeat(3) { backspaceDeleteOnce(e) } // Repeat-Ticks: SELBER Zweig
        assertEquals("kein weiteres commitEmpty", 1, e.commitEmptyCalls)
        assertEquals("Graphemcluster loeschen weiter", 3, e.deleteGraphemeCalls)
        assertEquals(0, e.deleteKeyCalls)
    }

    /** B2: Fake der Editor-Naht — zeichnet alle Operationen auf (kein Android). */
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
