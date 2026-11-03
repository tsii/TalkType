package de.voicekeyboard.commit

import de.voicekeyboard.editor.DeltaTyper
import de.voicekeyboard.editor.EditorSink
import de.voicekeyboard.editor.ReviseKind
import de.voicekeyboard.editor.TextDelta
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * E8-Testfälle T1–T6 aus docs/COMMIT_RULE_DECISION_2.md §5 — reine JVM,
 * Pipeline CommitPolicy + DeltaTyper (ohne Emulator). T1 ist der gemessene
 * Gerätefall (s1_realEngine_realWav).
 */
class CommitE8Test {

    /** Fake-Sink wie DeltaTyperTest: bildet Deltas am Cursor ab. */
    private open class FakeSink : EditorSink {
        var text: String = ""
        val calls = mutableListOf<TextDelta>()

        override fun replaceBeforeCursor(replaceLength: Int, newText: String) {
            calls += TextDelta(replaceLength, newText)
            text = text.dropLast(replaceLength) + newText
        }

        override fun textBeforeCursor(maxChars: Int): CharSequence? =
            text.takeLast(minOf(maxChars, text.length))

        override fun ownLength(): Int = text.length
    }

    /** Kleiner Pipeline-Wrapper: eine Hypothese → Policy → Typer (wie Controller). */
    private class Pipeline(
        val sink: FakeSink = FakeSink(),
        val policy: CommitPolicy = CommitPolicy(),
        val typer: DeltaTyper = DeltaTyper(sink),
    ) {
        var audioSamples = 0L

        fun feed(text: String, budgetExceeded: Boolean = false, advanceMs: Long = 16_000) {
            audioSamples += advanceMs
            val committed = policy.onHypothesis(text, audioSamples, typer.committedLength, budgetExceeded)
            typer.commitUpTo(committed)
            typer.reviseOutcome(text)
        }
    }

    private val GOLDEN =
        "So, jetzt bin ich gespannt, ob die Dikti-Funktion auf dem Telefon funktioniert."

    // T1 — der gemessene Gerätefall: „… Vikti" zweimal, dann korrekt bis Golden.
    // Erwartung: Endtext = Golden; kein null-Stau; Rollback ≤ 60 Codepoints.
    @Test fun t1_viktiCaseEndsAtGolden() {
        val p = Pipeline()
        // Aufbau-Phase wie gemessen: der Fehler wird erst geschrieben …
        p.feed("So, jetzt bin ich gespannt, ob die Vikti")
        p.feed("So, jetzt bin ich gespannt, ob die Vikti-Funktion")
        p.feed("So, jetzt bin ich gespannt, ob die Vikti-Funktion auf dem")
        // … und später vom Modell korrigiert:
        p.feed("So, jetzt bin ich gespannt, ob die Dikti-Funktion auf dem")
        p.feed("So, jetzt bin ich gespannt, ob die Dikti-Funktion auf dem Telefon")
        p.feed(GOLDEN)
        p.feed(GOLDEN)
        assertEquals(GOLDEN, p.sink.text)
        // kein Divergenz-Endzustand
        assertFalse(p.typer.divergent)
    }

    // T1-Zusatz: Rollback existiert wirklich (W3c) — Divergenz wird in EINEM
    // Delta korrigiert, committedLength schrumpft auf common.
    @Test fun t1_rollbackIsAtomicAndAllowsShrinking() {
        val sink = FakeSink()
        val typer = DeltaTyper(sink)
        typer.reviseOutcome("ob die Vikti-Funktion")
        typer.commitUpTo("ob die Vikti-".length) // Fehlcommit (D2/Schumpfen nötig)
        assertTrue(typer.committedLength > 0)
        // korrekte Hypothese divergiert im committeten Bereich:
        val outcome = typer.reviseOutcome("ob die Dikti-Funktion")
        assertEquals("W3c: Rollback schreibt (kein Stau)", ReviseKind.DELTA, outcome.kind)
        assertEquals("ob die Dikti-Funktion", sink.text)
        assertEquals(7, typer.committedLength) // W3a: geschrumpft auf common=7
        assertFalse(typer.divergent)
        // EIN Delta: replaceLength = entfernter UTF-16-Bestand
        val last = sink.calls.last()
        assertEquals("Vikti-Funktion".length, last.replaceLength)
    }

    // T2 — Nutzer tippt dazwischen (Besitz verloren): kein Schreiben mehr (W1)
    @Test fun t2_ownershipLostWritesNothing() {
        val sink = object : FakeSink() {
            var lost = false
            override fun ownLength(): Int = if (lost) 0 else text.length
        }
        val typer = DeltaTyper(sink)
        typer.reviseOutcome("So, jetzt bin")
        val callsBefore = sink.calls.size
        sink.lost = true // Fremdtext: Besitz weg
        assertEquals(ReviseKind.W1, typer.reviseOutcome("So, jetzt bin ich").kind)
        assertEquals(callsBefore, sink.calls.size) // nichts geschrieben
        assertTrue(typer.divergent)
        assertEquals("So, jetzt bin", sink.text) // unverändert
    }

    // T3 — Divergenz mit 75 Codepoints: kein Rollback (W3d), divergent=true
    @Test fun t3_tooLargeDivergenceLeavesText() {
        val sink = FakeSink()
        val typer = DeltaTyper(sink)
        typer.reviseOutcome("a".repeat(75))
        typer.commitUpTo(70)
        assertEquals("W3d: 70 > 60 ⇒ kein Rollback", ReviseKind.W3d, typer.reviseOutcome("b".repeat(75)).kind)
        assertTrue(typer.divergent)
        assertEquals("a".repeat(75), sink.text) // Text bleibt
    }

    // T4 — 2 Übereinstimmungen auf falschem Wort, 3. korrekt:
    //      C1 (N=3) verhindert, dass „Vikti" je committet wird.
    @Test fun t4_twoAgreementsDoNotCommitWrongWord() {
        val p = Pipeline()
        p.feed("ob die Vikti")
        p.feed("ob die Vikti-Funktion") // 2. Übereinstimmung — ALT würde committen
        assertEquals(0, p.typer.committedLength) // C1 (N=3): „Vikti" ist NICHT committet
        p.feed("ob die Vikti-Funktion auf")
        // korrekte Hypothesen folgen; der Fehler wurde nie fest — kein Rollback nötig
        p.feed("ob die Dikti-Funktion auf")
        p.feed("ob die Dikti-Funktion auf")
        assertEquals("ob die Dikti-Funktion auf", p.sink.text)
        assertFalse(p.typer.divergent)
        // „Vikti" lag NIE im geschützten Bereich — committet ist höchstens „ob "
        assertTrue(p.typer.committedLength <= "ob ".length)
    }

    // T5 — stabile Kante mit 3 Wörtern: nur das 1. Wort committed (C2/C3)
    @Test fun t5_holdbackKeepsLastTwoWordsUncommitted() {
        val p = Pipeline()
        // 3 identische Hypothesen mit 3 Wörtern
        p.feed("eins zwei drei")
        p.feed("eins zwei drei")
        p.feed("eins zwei drei")
        // C2 (M=2) + C3 (≥3 Wörter): committet wird nur „eins "
        assertEquals(5, p.typer.committedLength) // „eins "
        // die letzten 2 Wörter bleiben revidierbar:
        p.feed("eins zwei vier")
        assertEquals("eins zwei vier", p.sink.text)
    }

    // T6 — Rollback über einen Satzpunkt: kein Rollback (W3d-Grenze)
    @Test fun t6_noRollbackAcrossSentenceTerminator() {
        val sink = FakeSink()
        val typer = DeltaTyper(sink)
        typer.reviseOutcome("Das ist der erste Teil. Und noch")
        typer.commitUpTo("Das ist der erste Teil. Un".length) // Fehlcommit MIT Punkt
        // korrekte Hypothese divergiert VOR dem Satzpunkt (common < committed):
        assertEquals(
            "Satzpunkt im Löschbereich ⇒ W3d",
            ReviseKind.W3d,
            typer.reviseOutcome("Das ist der erste Teil! Und noch mehr").kind,
        )
        assertTrue(typer.divergent)
        assertEquals("Das ist der erste Teil. Und noch", sink.text) // bleibt
    }
    // T7 — Einheiten (B1): Grenze in CODEPOINTS, delete-Länge in UTF-16.
    // own = 15×😀 (30 UTF-16) + 45 BMP = 60 Codepoints / 75 UTF-16-Einheiten.
    // Divergenz am Anfang ⇒ Rollback erlaubt (60 ≤ 60), replaceLength == 75.
    @Test fun t7_emojiUnitsCodepointLimitUtf16Delete() {
        val sink = FakeSink()
        val typer = DeltaTyper(sink)
        val emoji = "\uD83D\uDE00" // 😀 = 2 UTF-16
        val own = emoji.repeat(15) + "a".repeat(45) // 60 Codepoints, 75 UTF-16
        typer.reviseOutcome(own)
        typer.commitUpTo(own.length) // alles committet
        val target = "b" + own.substring(1) // Divergenz am Anfang
        val outcome = typer.reviseOutcome(target)
        assertEquals("60 Codepoints <= 60 ⇒ W3c erlaubt", ReviseKind.DELTA, outcome.kind)
        assertEquals(75, outcome.delta!!.replaceLength) // UTF-16-Einheiten, nicht Codepoints
        assertEquals(target, sink.text)
    }

    // T8 — Kurze Session (B3): 1 Wort, nie committed; Final schreibt exakt einmal.
    @Test fun t8_shortSessionFinalWritesExactlyOnce() {
        val p = Pipeline()
        p.feed("Danke.")
        assertEquals(0, p.typer.committedLength) // C3: <3 Wörter ⇒ nie committet
        assertEquals("Danke.", p.sink.text)
        // Finaldecode (gleicher Text, gleicher Stand): kein Duplikat, kein Rest
        p.feed("Danke.")
        assertEquals("Danke.", p.sink.text)
    }

    // T9 (korrekt gebaut): Sink mit Fremdtext-Präfix; ownLength zählt nur eigene Spanne
    private class ForeignPrefixSink : EditorSink {
        var foreign = ""
        var own = ""
        val calls = mutableListOf<TextDelta>()
        override fun replaceBeforeCursor(replaceLength: Int, newText: String) {
            calls += TextDelta(replaceLength, newText)
            own = own.dropLast(replaceLength) + newText
        }
        override fun textBeforeCursor(maxChars: Int): CharSequence? =
            (foreign + own).takeLast(minOf(maxChars, foreign.length + own.length))
        override fun ownLength(): Int = own.length
    }

    @Test fun t9_rollbackDeletesOnlyOwnChars() {
        val sink = ForeignPrefixSink()
        val typer = DeltaTyper(sink)
        typer.reviseOutcome("Vikti-Funktion") // eigener Bereich
        typer.commitUpTo("Vikti-".length)
        sink.foreign = "Hallo " // Nutzer tippt LINKS davor — ownStart verschiebt sich
        // Besitz bleibt gültig: ownLength == own.length, Kontext-Suffix passt
        val outcome = typer.reviseOutcome("Dikti-Funktion")
        assertEquals("W3c trotz Fremdtext davor", ReviseKind.DELTA, outcome.kind)
        assertEquals("Hallo Dikti-Funktion", sink.foreign + sink.own)
        // Rollback hat ausschließlich eigene Zeichen gelöscht:
        assertEquals("Hallo ", sink.foreign)
        assertEquals("Dikti-Funktion", sink.own)
        assertEquals("Vikti-Funktion".length, outcome.delta!!.replaceLength)
    }
}
