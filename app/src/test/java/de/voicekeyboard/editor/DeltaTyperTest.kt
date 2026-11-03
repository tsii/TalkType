package de.voicekeyboard.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class DeltaTyperTest {

    /** Fake-Sink: bildet Text, Cursor am Ende, Calls und steuerbare Fehler ab. */
    private class FakeSink : EditorSink {
        var text: String = ""
        var failNext = false
        val calls = mutableListOf<Pair<Int, String>>()

        override fun replaceBeforeCursor(replaceLength: Int, newText: String) {
            if (failNext) {
                failNext = false
                throw IllegalStateException("sink error")
            }
            calls += replaceLength to newText
            text = text.dropLast(replaceLength) + newText
        }

        override fun textBeforeCursor(maxChars: Int): CharSequence? =
            text.takeLast(minOf(maxChars, text.length))

        override fun ownLength(): Int = text.length
    }

    @Test fun firstInsertIsPureAppend() {
        val sink = FakeSink()
        val typer = DeltaTyper(sink)
        val outcome = typer.reviseOutcome("Hallo")
        assertEquals(ReviseKind.DELTA, outcome.kind)
        assertEquals(TextDelta(0, "Hallo"), outcome.delta)
        assertEquals("Hallo", sink.text)
    }

    // (e) minimales Delta: gemeinsames Präfix bleibt stehen

    @Test fun minimalDeltaKeepsCommonPrefix() {
        val sink = FakeSink()
        val typer = DeltaTyper(sink)
        typer.reviseOutcome("Hallo Welt")
        val outcome = typer.reviseOutcome("Hallo Walt")
        assertEquals(ReviseKind.DELTA, outcome.kind)
        assertEquals(TextDelta(3, "alt"), outcome.delta) // nur „elt“→„alt“, „Hallo W“ bleibt stehen
        assertEquals("Hallo Walt", sink.text)
        assertEquals(listOf(0 to "Hallo Welt", 3 to "alt"), sink.calls)
    }

    @Test fun pureAppendHasNoBackspace() {
        val sink = FakeSink()
        val typer = DeltaTyper(sink)
        typer.reviseOutcome("Hallo")
        assertEquals(TextDelta(0, " Welt"), deltaOf(typer.reviseOutcome("Hallo Welt")))
        assertEquals("Hallo Welt", sink.text)
    }

    @Test fun surrogatePairsAreNotSplit() {
        val sink = FakeSink()
        val typer = DeltaTyper(sink)
        typer.reviseOutcome("Hallo \uD83E\uDD16")
        // gemeinsames Präfix endet auf High-Surrogate → ein Zeichen zurückgestuft,
        // damit das Surrogatpaar nicht getrennt gelöscht/eingefügt wird
        val outcome = typer.reviseOutcome("Hallo \uD83D\uDE00")
        assertEquals(ReviseKind.DELTA, outcome.kind)
        assertEquals(TextDelta(2, "\uD83D\uDE00"), outcome.delta)
        assertEquals("Hallo \uD83D\uDE00", sink.text)
    }

    // (f) Idempotenz: gleiche Hypothese erneut → kein zusätzliches Delta

    @Test fun sameHypothesisTwiceProducesNoSecondDelta() {
        val sink = FakeSink()
        val typer = DeltaTyper(sink)
        typer.reviseOutcome("Hallo Welt")
        val callsAfterFirst = sink.calls.size
        assertEquals(ReviseKind.SAME, typer.reviseOutcome("Hallo Welt").kind)
        assertEquals(callsAfterFirst, sink.calls.size)
        assertEquals("Hallo Welt", typer.currentText)
    }

    // (c) max_revision_chars = 60: 61 Zeichen Revision → Commit erzwungen

    @Test fun sixtyCharsRevisionStillAllowed() {
        val sink = FakeSink()
        val typer = DeltaTyper(sink)
        typer.reviseOutcome("a".repeat(60))
        val outcome = typer.reviseOutcome("b".repeat(60))
        assertEquals(ReviseKind.DELTA, outcome.kind)
        assertEquals(TextDelta(60, "b".repeat(60)), outcome.delta)
        assertEquals(0, typer.committedLength) // normale Revision, nichts eingefroren
        assertEquals("b".repeat(60), sink.text)
    }

    @Test fun sixtyOneCharsRevisionForcesCommit() {
        val sink = FakeSink()
        val typer = DeltaTyper(sink)
        typer.reviseOutcome("a".repeat(61))
        val outcome = typer.reviseOutcome("b".repeat(61))
        // kein Löschen: bestehender Text wird committet, nur angehängt (kein Flackern)
        assertEquals(ReviseKind.DELTA, outcome.kind)
        assertEquals(TextDelta(0, "b".repeat(61)), outcome.delta)
        assertEquals(61, typer.committedLength)
        assertEquals("a".repeat(61) + "b".repeat(61), sink.text)
        // danach ist der Bestand unantastbar: weitere Revision hängt nur an
        assertEquals(TextDelta(0, "!"), deltaOf(typer.reviseOutcome("a".repeat(61) + "b".repeat(61) + "!")))
    }

    // (d) committed-Präfix wird bei späterer Revision nicht angetastet (Negativtest)

    @Test fun divergentCommittedPrefixIsRolledBack_E8() {
        // E8.1 W3c: Divergenz im eigenen, besitzbestätigten Bereich wird in
        // EINER atomaren Revision korrigiert (D1 „verwerfen" ist aufgehoben).
        val sink = FakeSink()
        val typer = DeltaTyper(sink)
        typer.reviseOutcome("abc def")
        typer.commitUpTo(4) // „abc “ (inkl. Leerzeichen) ist committet
        val callsBefore = sink.calls.size

        // Hypothese weicht im committeten Bereich ab → Rollback (W3c), kein null
        val outcome = typer.reviseOutcome("xyz def")
        assertEquals(ReviseKind.DELTA, outcome.kind)
        assertEquals(TextDelta(7, "xyz def"), outcome.delta) // ganzer own-Bereich ab common=0
        assertEquals(callsBefore + 1, sink.calls.size)
        assertEquals("xyz def", sink.text)
        assertEquals(0, typer.committedLength) // W3a: auf common=0 geschrumpft
        assertFalse(typer.divergent)

        // zulässige Tail-Revision funktioniert weiterhin
        assertEquals(TextDelta(3, "DEF"), deltaOf(typer.reviseOutcome("xyz DEF")))
        assertEquals("xyz DEF", sink.text)
    }

    @Test fun commitUpToClampsButMayShrink_E8_W3a() {
        // E8.1 W3a (D2 aufgehoben): commitUpTo darf schrumpfen — der Rollback
        // setzt den Schutzbereich auf den gemeinsamen Präfix zurück.
        val sink = FakeSink()
        val typer = DeltaTyper(sink)
        typer.reviseOutcome("abcdefgh")
        typer.commitUpTo(5)
        typer.commitUpTo(3) // Schrumpfen erlaubt (W3a)
        assertEquals(3, typer.committedLength)
        typer.commitUpTo(100) // über die eigene Textlänge hinaus → geklemmt
        assertEquals(8, typer.committedLength)
        typer.commitUpTo(0) // auch 0 ist erlaubt (Finaldecode-Reset)
        assertEquals(0, typer.committedLength)
    }

    // (e) Sink-Fehler: Ausnahme nach oben, interner Zustand bleibt konsistent

    @Test fun sinkErrorLeavesStateConsistent() {
        val sink = FakeSink()
        val typer = DeltaTyper(sink)
        typer.reviseOutcome("Hallo")
        val callsBefore = sink.calls.size

        sink.failNext = true
        var threw = false
        try {
            typer.reviseOutcome("Hallo Welt")
        } catch (expected: IllegalStateException) {
            threw = true
        }
        assertTrue(threw)
        // Zustand unverändert: currentText entspricht weiterhin dem Editor-Inhalt
        assertEquals("Hallo", typer.currentText)
        assertEquals("Hallo", sink.text)
        assertEquals(callsBefore, sink.calls.size)

        // Wiederholung mit funktionierendem Sink: gleiche Revision sauber
        assertEquals(TextDelta(0, " Welt"), deltaOf(typer.reviseOutcome("Hallo Welt")))
        assertEquals("Hallo Welt", sink.text)
    }

    @Test fun ownershipMismatchIsRejected() {
        val sink = FakeSink()
        val typer = DeltaTyper(sink)
        typer.reviseOutcome("Hallo")
        val callsBefore = sink.calls.size
        // Nutzer hat getippt: eigene Länge stimmt nicht mehr → kein Schreiben
        sink.text = "HalloX"
        assertEquals(ReviseKind.W1, typer.reviseOutcome("Hallo Welt").kind)
        assertEquals(callsBefore, sink.calls.size)
        assertEquals("Hallo", typer.currentText) // interner Stand unverändert
    }

    @Test fun resetStartsFreshSession() {
        val sink = FakeSink()
        val typer = DeltaTyper(sink)
        typer.reviseOutcome("Alt")
        typer.commitUpTo(3)
        typer.reset()
        assertEquals("", typer.currentText)
        assertEquals(0, typer.committedLength)
        assertEquals(TextDelta(0, "Neu"), deltaOf(typer.reviseOutcome("Neu")))
    }

    // (d) S4-02-Gegenprobe: Sink wirft im >60-Zweig → committedLength unverändert,
    //     dieselbe Revision danach erneut versuchbar.

    @Test fun sinkErrorInForcedCommitKeepsStateRetryable() {
        val sink = FakeSink()
        val typer = DeltaTyper(sink)
        typer.reviseOutcome("a".repeat(61))
        val callsBefore = sink.calls.size

        sink.failNext = true
        var threw = false
        try {
            typer.reviseOutcome("b".repeat(61))
        } catch (expected: IllegalStateException) {
            threw = true
        }
        assertTrue(threw)
        // committedLength wurde NICHT vorab gefroren — Zustand vollständig unverändert
        assertEquals(0, typer.committedLength)
        assertEquals("a".repeat(61), typer.currentText)
        assertEquals(callsBefore, sink.calls.size)

        // dieselbe Revision erneut versuchbar: normaler Forced-Commit-Verlauf
        assertEquals(TextDelta(0, "b".repeat(61)), deltaOf(typer.reviseOutcome("b".repeat(61))))
        assertEquals(61, typer.committedLength)
        assertEquals("a".repeat(61) + "b".repeat(61), sink.text)
    }

    @Test fun commonPrefixLengthHelper() {
        assertEquals(0, DeltaTyper.commonPrefixLength("a", "b"))
        assertEquals(3, DeltaTyper.commonPrefixLength("abcX", "abcY"))
        assertEquals(2, DeltaTyper.commonPrefixLength("ab", "ab"))
    }

    // ---- A2a (PLAN_V0133 §1 A2a): Grundrückgabe {SAME, W1, W3d, DELTA} ----

    /** A2a-Hilfe: DELTA mit exakt diesem EINEN Sink-Schritt (kind mitgeprüft). */
    private fun deltaOf(outcome: ReviseOutcome): TextDelta {
        assertEquals(ReviseKind.DELTA, outcome.kind)
        return outcome.delta!!
    }

    /** A2a: Sink mit Fremdtext-PRÄFIX — ownLength zählt nur die eigene Spanne;
     *  textBeforeCursor liefert den Gesamttext (Fremdpräfix + eigener). */
    private class ForeignPrefixSink : EditorSink {
        var foreign: String = ""
        var own: String = ""
        val calls = mutableListOf<Pair<Int, String>>()

        override fun replaceBeforeCursor(replaceLength: Int, newText: String) {
            calls += replaceLength to newText
            own = own.dropLast(replaceLength) + newText
        }

        override fun textBeforeCursor(maxChars: Int): CharSequence? =
            (foreign + own).takeLast(minOf(maxChars, foreign.length + own.length))

        override fun ownLength(): Int = own.length
    }

    // A2a-Rote-Test 1: reviseOutcome() liefert GESCHLOSSENE Ergebnisse —
    // SAME, W1, W3d, DELTA; SAME bei identischem Ziel. Null-Mehrdeutigkeit
    // („null“ für drei verschiedene Fälle) ist im neuen Produktionspfad
    // verboten; [ReviseOutcome.delta] ist nur bei DELTA gesetzt (genau ein
    // Sink-Schritt).
    @Test fun `A2a reviseOutcome liefert geschlossene Ergebnisse SAME W1 W3d DELTA`() {
        val sink = FakeSink()
        val typer = DeltaTyper(sink)

        // SAME: identisches Ziel — kein Sink-Aufruf
        typer.reviseOutcome("Hallo")
        val callsAfterDelta = sink.calls.size
        val same = typer.reviseOutcome("Hallo")
        assertEquals(ReviseKind.SAME, same.kind)
        assertNull("SAME schreibt nichts", same.delta)
        assertEquals(callsAfterDelta, sink.calls.size)

        // DELTA: minimale Tail-Revision — genau ein Sink-Schritt
        val written = typer.reviseOutcome("Hallo Welt")
        assertEquals(ReviseKind.DELTA, written.kind)
        assertEquals(TextDelta(0, " Welt"), written.delta)
        assertEquals("Hallo Welt", sink.text)

        // W1: Besitz nicht bewiesen — kein Schreiben, divergent sichtbar
        sink.text = "Hallo WeltX" // Nutzer tippt dazwischen
        val w1 = typer.reviseOutcome("Hallo Welt!")
        assertEquals(ReviseKind.W1, w1.kind)
        assertNull("W1 schreibt nichts", w1.delta)
        assertTrue(typer.divergent)
        assertEquals("Hallo WeltX", sink.text)

        // W3d: Divergenz > 60 Codepoints im committeten Bereich — einmalig
        // sichtbar, kein Schreiben (Aufloesung prueft Rote-Test 2)
        val sink2 = FakeSink()
        val typer2 = DeltaTyper(sink2)
        typer2.reviseOutcome("a".repeat(75))
        typer2.commitUpTo(70)
        val w3d = typer2.reviseOutcome("b".repeat(75))
        assertEquals(ReviseKind.W3d, w3d.kind)
        assertNull("W3d schreibt nichts (einmalig sichtbar)", w3d.delta)
        assertTrue(typer2.divergent)
        assertEquals("a".repeat(75), sink2.text)
    }

    // A2a-Rote-Test 2: W3d-Fall mit 75 Codepoints, committedLength=70 — der
    // ERSTE blockierte Versuch meldet W3d; die NÄCHSTE Hypothese revidiert bei
    // erneut bewiesenem Besitz die eigene Spanne ATOMAR (ein Sink-Schritt,
    // 60-Grenze einmalig überschritten) und meldet DELTA. Kein dauerhafter
    // Stillstand.
    @Test fun `A2a W3d blockiert nur eine Hypothese und naechste revidiert atomar`() {
        val sink = FakeSink()
        val typer = DeltaTyper(sink)
        typer.reviseOutcome("a".repeat(75))
        typer.commitUpTo(70)
        val callsBefore = sink.calls.size

        assertEquals(ReviseKind.W3d, typer.reviseOutcome("b".repeat(75)).kind)
        assertEquals("kein Schreibversuch im blockierten Schritt", callsBefore, sink.calls.size)
        assertEquals("a".repeat(75), sink.text)

        // nächste Hypothese (Besitz erneut bewiesen — Editor unverändert):
        val outcome = typer.reviseOutcome("b".repeat(75))
        assertEquals(ReviseKind.DELTA, outcome.kind)
        assertEquals("GANZE eigene Spanne in EINEM Schritt", TextDelta(75, "b".repeat(75)), outcome.delta)
        assertEquals("genau ein neuer Sink-Aufruf", callsBefore + 1, sink.calls.size)
        assertEquals("b".repeat(75), sink.text)
        assertEquals("b".repeat(75), typer.currentText)
        assertEquals("W3a-Analog: committedLength auf common geschrumpft", 0, typer.committedLength)
        assertFalse("Divergenz ist aufgeloest", typer.divergent)
    }

    // A2a-Rote-Test 3: reviseFinal() nach einer W3d-Sperre — die GESAMTE eigene
    // Spanne wird in GENAU EINEM Sink-Schritt revidiert, ein Fremdpräfix bleibt
    // unangetastet; Besitz wird ZUERST geprüft (W1 ohne jeden Schreibversuch).
    @Test fun `A2a reviseFinal nach W3d revidiert gesamte eigene Spanne in einem Schritt`() {
        val sink = ForeignPrefixSink()
        val typer = DeltaTyper(sink)
        typer.reviseOutcome("a".repeat(75))
        typer.commitUpTo(70)
        sink.foreign = "Nutzer " // Fremdtext VOR der eigenen Spanne
        assertEquals(ReviseKind.W3d, typer.reviseOutcome("b".repeat(75)).kind)
        val callsBefore = sink.calls.size

        val final = typer.reviseFinal("b".repeat(70) + " Ende")
        assertEquals(ReviseKind.DELTA, final.kind)
        assertEquals("ein Sink-Schritt fuer die GESAMTE eigene Spanne", 1, sink.calls.size - callsBefore)
        assertEquals("nur eigene Zeichen geloescht (75 UTF-16)", TextDelta(75, "b".repeat(70) + " Ende"), final.delta)
        assertEquals("Fremdpraefix unangetastet", "Nutzer ", sink.foreign)
        assertEquals("Nutzer " + "b".repeat(70) + " Ende", sink.foreign + sink.own)

        // Besitz zuerst: ohne Beweis KEIN Finale-Schreibversuch
        sink.own = sink.own + "X" // Nutzer tippt ans Ende — Besitz weg
        val w1 = typer.reviseFinal("anderes Finale")
        assertEquals(ReviseKind.W1, w1.kind)
        assertEquals("kein zusaetzlicher Sink-Aufruf", callsBefore + 1, sink.calls.size)
        assertTrue(typer.divergent)
    }

    // A2a-Rote-Test 4: SAME-Flut — 100 identische Hypothesen nach einem
    // initialen DELTA liefern 100× SAME; Sink-Calls und geschriebene Zeichen
    // bleiben EXAKT auf dem Stand nach dem initialen Delta (0 zusätzliche
    // Sink-Zugriffe; SAME erzeugt keinen Sink-Aufruf).
    @Test fun `A2a 100 identische Hypothesen bleiben SAME ohne zusaetzliche Sink-Calls`() {
        val sink = FakeSink()
        val typer = DeltaTyper(sink)
        assertEquals(ReviseKind.DELTA, typer.reviseOutcome("Hallo Welt").kind)
        val callsAfterDelta = sink.calls.size
        val charsAfterDelta = sink.text.length
        repeat(100) {
            assertEquals("identische Hypothese $it", ReviseKind.SAME, typer.reviseOutcome("Hallo Welt").kind)
        }
        assertEquals("keine zusaetzlichen Sink-Calls", callsAfterDelta, sink.calls.size)
        assertEquals("keine zusaetzlich geschriebenen Zeichen", charsAfterDelta, sink.text.length)
        assertEquals("Hallo Welt", typer.currentText)
    }
}
