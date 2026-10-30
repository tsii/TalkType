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
        val delta = typer.revise("Hallo")
        assertEquals(TextDelta(0, "Hallo"), delta)
        assertEquals("Hallo", sink.text)
    }

    // (e) minimales Delta: gemeinsames Präfix bleibt stehen

    @Test fun minimalDeltaKeepsCommonPrefix() {
        val sink = FakeSink()
        val typer = DeltaTyper(sink)
        typer.revise("Hallo Welt")
        val delta = typer.revise("Hallo Walt")
        assertEquals(TextDelta(3, "alt"), delta) // nur „elt“→„alt“, „Hallo W“ bleibt stehen
        assertEquals("Hallo Walt", sink.text)
        assertEquals(listOf(0 to "Hallo Welt", 3 to "alt"), sink.calls)
    }

    @Test fun pureAppendHasNoBackspace() {
        val sink = FakeSink()
        val typer = DeltaTyper(sink)
        typer.revise("Hallo")
        assertEquals(TextDelta(0, " Welt"), typer.revise("Hallo Welt"))
        assertEquals("Hallo Welt", sink.text)
    }

    @Test fun surrogatePairsAreNotSplit() {
        val sink = FakeSink()
        val typer = DeltaTyper(sink)
        typer.revise("Hallo \uD83E\uDD16")
        // gemeinsames Präfix endet auf High-Surrogate → ein Zeichen zurückgestuft,
        // damit das Surrogatpaar nicht getrennt gelöscht/eingefügt wird
        val delta = typer.revise("Hallo \uD83D\uDE00")
        assertEquals(TextDelta(2, "\uD83D\uDE00"), delta)
        assertEquals("Hallo \uD83D\uDE00", sink.text)
    }

    // (f) Idempotenz: gleiche Hypothese erneut → kein zusätzliches Delta

    @Test fun sameHypothesisTwiceProducesNoSecondDelta() {
        val sink = FakeSink()
        val typer = DeltaTyper(sink)
        typer.revise("Hallo Welt")
        val callsAfterFirst = sink.calls.size
        assertNull(typer.revise("Hallo Welt"))
        assertEquals(callsAfterFirst, sink.calls.size)
        assertEquals("Hallo Welt", typer.currentText)
    }

    // (c) max_revision_chars = 60: 61 Zeichen Revision → Commit erzwungen

    @Test fun sixtyCharsRevisionStillAllowed() {
        val sink = FakeSink()
        val typer = DeltaTyper(sink)
        typer.revise("a".repeat(60))
        val delta = typer.revise("b".repeat(60))
        assertEquals(TextDelta(60, "b".repeat(60)), delta)
        assertEquals(0, typer.committedLength) // normale Revision, nichts eingefroren
        assertEquals("b".repeat(60), sink.text)
    }

    @Test fun sixtyOneCharsRevisionForcesCommit() {
        val sink = FakeSink()
        val typer = DeltaTyper(sink)
        typer.revise("a".repeat(61))
        val delta = typer.revise("b".repeat(61))
        // kein Löschen: bestehender Text wird committet, nur angehängt (kein Flackern)
        assertEquals(TextDelta(0, "b".repeat(61)), delta)
        assertEquals(61, typer.committedLength)
        assertEquals("a".repeat(61) + "b".repeat(61), sink.text)
        // danach ist der Bestand unantastbar: weitere Revision hängt nur an
        assertEquals(TextDelta(0, "!"), typer.revise("a".repeat(61) + "b".repeat(61) + "!"))
    }

    // (d) committed-Präfix wird bei späterer Revision nicht angetastet (Negativtest)

    @Test fun divergentCommittedPrefixIsRolledBack_E8() {
        // E8.1 W3c: Divergenz im eigenen, besitzbestätigten Bereich wird in
        // EINER atomaren Revision korrigiert (D1 „verwerfen" ist aufgehoben).
        val sink = FakeSink()
        val typer = DeltaTyper(sink)
        typer.revise("abc def")
        typer.commitUpTo(4) // „abc “ (inkl. Leerzeichen) ist committet
        val callsBefore = sink.calls.size

        // Hypothese weicht im committeten Bereich ab → Rollback (W3c), kein null
        val delta = typer.revise("xyz def")
        assertEquals(TextDelta(7, "xyz def"), delta) // ganzer own-Bereich ab common=0
        assertEquals(callsBefore + 1, sink.calls.size)
        assertEquals("xyz def", sink.text)
        assertEquals(0, typer.committedLength) // W3a: auf common=0 geschrumpft
        assertFalse(typer.divergent)

        // zulässige Tail-Revision funktioniert weiterhin
        assertEquals(TextDelta(3, "DEF"), typer.revise("xyz DEF"))
        assertEquals("xyz DEF", sink.text)
    }

    @Test fun commitUpToClampsButMayShrink_E8_W3a() {
        // E8.1 W3a (D2 aufgehoben): commitUpTo darf schrumpfen — der Rollback
        // setzt den Schutzbereich auf den gemeinsamen Präfix zurück.
        val sink = FakeSink()
        val typer = DeltaTyper(sink)
        typer.revise("abcdefgh")
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
        typer.revise("Hallo")
        val callsBefore = sink.calls.size

        sink.failNext = true
        var threw = false
        try {
            typer.revise("Hallo Welt")
        } catch (expected: IllegalStateException) {
            threw = true
        }
        assertTrue(threw)
        // Zustand unverändert: currentText entspricht weiterhin dem Editor-Inhalt
        assertEquals("Hallo", typer.currentText)
        assertEquals("Hallo", sink.text)
        assertEquals(callsBefore, sink.calls.size)

        // Wiederholung mit funktionierendem Sink: gleiche Revision sauber
        assertEquals(TextDelta(0, " Welt"), typer.revise("Hallo Welt"))
        assertEquals("Hallo Welt", sink.text)
    }

    @Test fun ownershipMismatchIsRejected() {
        val sink = FakeSink()
        val typer = DeltaTyper(sink)
        typer.revise("Hallo")
        val callsBefore = sink.calls.size
        // Nutzer hat getippt: eigene Länge stimmt nicht mehr → kein Schreiben
        sink.text = "HalloX"
        assertNull(typer.revise("Hallo Welt"))
        assertEquals(callsBefore, sink.calls.size)
        assertEquals("Hallo", typer.currentText) // interner Stand unverändert
    }

    @Test fun resetStartsFreshSession() {
        val sink = FakeSink()
        val typer = DeltaTyper(sink)
        typer.revise("Alt")
        typer.commitUpTo(3)
        typer.reset()
        assertEquals("", typer.currentText)
        assertEquals(0, typer.committedLength)
        assertEquals(TextDelta(0, "Neu"), typer.revise("Neu"))
    }

    // (d) S4-02-Gegenprobe: Sink wirft im >60-Zweig → committedLength unverändert,
    //     dieselbe Revision danach erneut versuchbar.

    @Test fun sinkErrorInForcedCommitKeepsStateRetryable() {
        val sink = FakeSink()
        val typer = DeltaTyper(sink)
        typer.revise("a".repeat(61))
        val callsBefore = sink.calls.size

        sink.failNext = true
        var threw = false
        try {
            typer.revise("b".repeat(61))
        } catch (expected: IllegalStateException) {
            threw = true
        }
        assertTrue(threw)
        // committedLength wurde NICHT vorab gefroren — Zustand vollständig unverändert
        assertEquals(0, typer.committedLength)
        assertEquals("a".repeat(61), typer.currentText)
        assertEquals(callsBefore, sink.calls.size)

        // dieselbe Revision erneut versuchbar: normaler Forced-Commit-Verlauf
        assertEquals(TextDelta(0, "b".repeat(61)), typer.revise("b".repeat(61)))
        assertEquals(61, typer.committedLength)
        assertEquals("a".repeat(61) + "b".repeat(61), sink.text)
    }

    @Test fun commonPrefixLengthHelper() {
        assertEquals(0, DeltaTyper.commonPrefixLength("a", "b"))
        assertEquals(3, DeltaTyper.commonPrefixLength("abcX", "abcY"))
        assertEquals(2, DeltaTyper.commonPrefixLength("ab", "ab"))
    }
}
