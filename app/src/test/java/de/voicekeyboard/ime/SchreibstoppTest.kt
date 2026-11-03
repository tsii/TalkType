package de.voicekeyboard.ime

import de.voicekeyboard.asr.AsrEngine
import de.voicekeyboard.asr.Hypothesis
import de.voicekeyboard.audio.PcmBlock
import de.voicekeyboard.audio.PcmWindow
import de.voicekeyboard.dictation.DictationController
import de.voicekeyboard.dictation.DictationState
import de.voicekeyboard.dictation.StopOutcome
import de.voicekeyboard.editor.EditorSink
import de.voicekeyboard.editor.OwnershipTracker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicLong

/**
 * A2b (PLAN_V0133 §1 A2b) — Schreibstopp-Familie:
 *  1. W3d-Burst blockiert nur EINE Hypothese (Session bleibt RECORDING),
 *     write_w3d=1, danach wächst write_delta;
 *  2. Finale nach W3d committed den VOLLSTÄNDIGEN finalen Zielstand;
 *  3. W1 mit E1c-Suffixbeweis (actualBeforeCursor, fresherExpected=
 *     sink.ownText()) re-baselined und schreibt; Fremdtext ⇒ W1 ohne
 *     Sink-Zugriff, lost bleibt;
 *  4. E1c-Präfixregel: frischer Sink-Text vor älterer Tracker-Erwartung;
 *     lost=false NUR nach positivem Suffixbeweis (ownEditFresh allein reicht
 *     NICHT).
 *
 * Nur Zahlen/Codes/Längen in Annahmen und Assertions (Regel 9) — keine
 * Transkriptinhalte in Logs.
 */
class SchreibstoppTest {

    /** A2b: Sink mit Fremdtext-PRÄFIX und getrennter eigener Spanne. */
    private class OwnSpanSink : EditorSink {
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

        override fun ownText(): String = own
    }

    /** A2b: Engine mit Skript-Warteschlange (ein Text je Decode-Lauf). */
    private class ScriptedEngine(texts: List<String>) : AsrEngine {
        private val queue = ConcurrentLinkedQueue(texts)

        override fun load(modelDir: File) {}

        override suspend fun transcribe(window: PcmWindow, sessionId: Long, runId: Long): Hypothesis =
            Hypothesis(
                IntArray(1), queue.poll() ?: "", IntArray(1),
                sessionId, runId, window.samples.size, incomplete = false,
            )

        override fun close() {}
    }

    private fun hyp(text: String, sessionId: Long) =
        Hypothesis(IntArray(1), text, IntArray(1), sessionId, 1L, 16_000, incomplete = false)

    private fun newController(sink: EditorSink, tracker: OwnershipTracker) =
        DictationController(ScriptedEngine(emptyList()), sink, tracker = tracker)

    /** 0,2 s Audio: rückt die Agreement-Audioposition vor, ohne den 2-s-Partial-
     *  Guard auszulösen (LocalAgreement zählt gleiche Audiopositionen NICHT
     *  als zweite Bestätigung — DESIGN §5.4). */
    private suspend fun DictationController.tick() =
        feedAudio(PcmBlock(0, 0, ShortArray(3_200)))

    // A2b-Rote-Test 1: Controller-Session mit W3d-Burst bleibt RECORDING; die
    // NÄCHSTE Hypothese schreibt; write_w3d=1, danach wächst write_delta.
    @Test fun `A2b W3d-Burst blockiert nur eine Hypothese und Zaehler laufen mit`() {
        val sink = OwnSpanSink()
        val c = newController(sink, OwnershipTracker())
        assertTrue(c.start())
        assertEquals("last_write_audio_s=-1 vor erstem DELTA", -1.0, c.lastWriteAudioS, 1e-9)

        val t = "aa bb cc dd ee ff gg hh ii jj kk ll mm nn"
        kotlinx.coroutines.runBlocking {
            repeat(3) {
                c.tick()
                assertTrue(c.applyPartial(hyp(t, c.sessionId)))
            }
        }
        assertTrue("Agreement muss den Praefix committen", c.committedLen > 0)
        val callsAtCommit = sink.calls.size

        // W3d-Burst: Hypothese divergiert frueh im committeten Bereich —
        // Rollback verboten (>= 2 Leerzeichen im Loeschbereich)
        val d = "xy aa bb cc dd ee ff gg hh ii jj kk ll mm nn"
        kotlinx.coroutines.runBlocking {
            c.tick()
            assertTrue(c.applyPartial(hyp(d, c.sessionId)))
        }
        assertEquals("Session endet NICHT durch W3d selbst", DictationState.RECORDING, c.state)
        assertEquals("genau EIN W3d", 1, c.writeW3dCount)
        assertEquals("kein Schreibversuch im blockierten Schritt", callsAtCommit, sink.calls.size)
        assertEquals(t, sink.own)
        assertEquals("w3d", c.writeReason)

        // NÄCHSTE Hypothese schreibt (A2a-Latch): write_delta wächst
        kotlinx.coroutines.runBlocking {
            c.tick()
            assertTrue(c.applyPartial(hyp(d, c.sessionId)))
        }
        assertEquals("write_delta wächst (T#1 war bereits DELTA 1)", 2, c.writeDeltaCount)
        assertEquals(d, sink.own)
        assertEquals("W3d bleibt bei genau 1", 1, c.writeW3dCount)
        assertEquals(DictationState.RECORDING, c.state)
        assertTrue("last_write_audio_s gesetzt nach erstem DELTA", c.lastWriteAudioS >= 0.0)

        // weiteres Schreiben laesst write_delta weiter wachsen
        kotlinx.coroutines.runBlocking {
            c.tick()
            assertTrue(c.applyPartial(hyp(d + " oo", c.sessionId)))
        }
        assertEquals(3, c.writeDeltaCount)
        assertEquals(d + " oo", sink.own)
    }

    // A2b-Rote-Test 2: Finale nach W3d committed den VOLLSTÄNDIGEN finalen
    // Zielstand — keine alte Teilhypothese bleibt stehen.
    @Test fun `A2b Finale nach W3d committed den vollstaendigen Zielstand`() {
        val sink = OwnSpanSink()
        val finalText = "zz yu aa bb cc dd ee ff gg hh ii jj kk ll mm nn oo pp"
        val c = DictationController(ScriptedEngine(listOf(finalText)), sink)
        assertTrue(c.start())
        val t = "aa bb cc dd ee ff gg hh ii jj kk ll mm nn"
        kotlinx.coroutines.runBlocking {
            repeat(3) {
                c.tick()
                assertTrue(c.applyPartial(hyp(t, c.sessionId)))
            }
        }
        val d = "xy aa bb cc dd ee ff gg hh ii jj kk ll mm nn"
        kotlinx.coroutines.runBlocking {
            c.tick() // W3d-Blocker (einmalig)
            assertTrue(c.applyPartial(hyp(d, c.sessionId)))
            c.tick() // Latch: DELTA, Sperre konsumiert
            assertTrue(c.applyPartial(hyp(d, c.sessionId)))
            repeat(3) { // Agreement neu aufbauen
                c.tick()
                assertTrue(c.applyPartial(hyp(d, c.sessionId)))
            }
        }
        assertTrue("committeter Praefix vor dem Finale wieder da", c.committedLen > 0)
        val sinkBeforeFinal = sink.own

        val outcome = kotlinx.coroutines.runBlocking { c.stop() }
        assertTrue("Finale muss laufen", outcome is StopOutcome.FinalRan)
        assertEquals("VOLLSTAENDIGER finaler Zielstand", finalText, sink.own)
        assertTrue("alte Teilhypothese darf nicht stehen bleiben", sinkBeforeFinal != sink.own)
        assertEquals("kein zweites W3d im Finale", 1, c.writeW3dCount)
        assertEquals("delta", c.writeReason)
        assertEquals(DictationState.IDLE, c.state)

        // Counter werden beim Start genullt (nächste Session frisch)
        assertTrue(c.start())
        assertEquals(0, c.writeSameCount)
        assertEquals(0, c.writeW1Count)
        assertEquals(0, c.writeW3dCount)
        assertEquals(0, c.writeDeltaCount)
        assertEquals(-1.0, c.lastWriteAudioS, 1e-9)
        assertEquals("-", c.writeReason)
    }

    // A2b-Rote-Test 3: W1 mit actualBeforeCursor-Suffixbeweis und
    // fresherExpected=sink.ownText() darf re-baselinen und danach schreiben.
    // Fremder/abweichender Editorinhalt ⇒ W1, kein Sink-Zugriff, lost bleibt.
    @Test fun `A2b W1-Rebaseline mit Suffixbeweis schreibt - Fremdtext bleibt W1`() {
        val sink = OwnSpanSink()
        val clock = AtomicLong(1_000_000L)
        val tracker = OwnershipTracker(nowMs = { clock.get() })
        val c = newController(sink, tracker)
        assertTrue(c.start())
        assertTrue(c.applyPartial(hyp("hallo welt", c.sessionId)))
        assertEquals("hallo welt", sink.own)

        // Nutzer tippt VOR unserem Text (Suffix bleibt beweisbar); der Tracker
        // meldet Fremdeingabe (Range-Selektion) — ohne Fix ein W1-Stopp.
        sink.foreign = "User "
        c.onUpdateSelection(0, 0, 2, 5) // Range (2 != 5) ⇒ lost=true
        assertTrue("lost gesetzt", tracker.ownershipLost)
        clock.addAndGet(500) // Frische-Fenster deterministisch geschlossen

        assertTrue(
            "Re-Baseline mit Suffixbeweis ⇒ dieselbe Hypothese wird geschrieben",
            c.applyPartial(hyp("hallo welt mehr", c.sessionId)),
        )
        assertEquals("geschrieben", "hallo welt mehr", sink.own)
        assertEquals("Fremdpraefix byteweise unangetastet", "User ", sink.foreign)
        assertFalse("lost nach Suffixbeweis aufgehoben", tracker.ownershipLost)
        assertEquals("kein W3d gezaehlt", 0, c.writeW3dCount)
        assertEquals("write_delta gewachsen (Erstschrieb + Rebase-Schrieb)", 2, c.writeDeltaCount)

        // Gegenprobe: App ERSETZT unseren Text (kein Suffix) — lost bleibt,
        // KEIN Sink-Zugriff, Zaehler meldet W1.
        sink.own = "GANZ ANDERER TEXT"
        c.onUpdateSelection(0, 0, 2, 5) // Range ⇒ lost wieder true
        clock.addAndGet(500)
        val callsBeforeForeign = sink.calls.size
        val deltaBefore = c.writeDeltaCount
        assertFalse(
            "Fremdtext ⇒ Hypothese wird nicht ausgefuehrt",
            c.applyPartial(hyp("hallo welt nochmehr", c.sessionId)),
        )
        assertEquals("kein Sink-Zugriff", callsBeforeForeign, sink.calls.size)
        assertEquals("W1 gezaehlt", 1, c.writeW1Count)
        assertEquals("write_delta unveraendert", deltaBefore, c.writeDeltaCount)
        assertTrue("lost bleibt gesetzt", tracker.ownershipLost)
        assertEquals("Fremdtext byteweise unangetastet", "GANZ ANDERER TEXT", sink.own)
        assertEquals("w1", c.writeReason)
    }

    // P2 (REVIEW_A2_A4): Eine GELUNGENE W1-Re-Baseline ist KEIN Besitzverlust —
    // ownershipDeniedCount/-AtS zählen nur ECHTE Verweigerungen (Zähler und
    // Stempel wanderten hinter den Re-Baseline-Versuch in den Ablehnungs-Zweig).
    @Test fun `P2 gelungene W1-Rebaseline zaehlt keinen Besitzverlust`() {
        val sink = OwnSpanSink()
        val clock = AtomicLong(1_000_000L)
        val tracker = OwnershipTracker(nowMs = { clock.get() })
        val c = newController(sink, tracker)
        assertTrue(c.start())
        assertTrue(c.applyPartial(hyp("hallo welt", c.sessionId)))
        assertEquals("vor dem Fremdgriff: nie verloren", -1.0, c.ownershipDeniedAtS, 1e-9)

        // Besitz weg, aber Suffix beweisbar: Re-Baseline gelingt, die Hypothese
        // schreibt — das ist KEINE Verweigerung, kein Besitzverlust-Stempel:
        sink.foreign = "User "
        c.onUpdateSelection(0, 0, 2, 5) // Range ⇒ lost=true
        clock.addAndGet(500)
        assertTrue("Re-Baseline gelingt (Suffixbeweis)", c.applyPartial(hyp("hallo welt mehr", c.sessionId)))
        assertEquals("gelungene Re-Baseline zaehlt NICHT", 0, c.ownershipDeniedCount)
        assertEquals("kein Besitzverlust-Stempel", -1.0, c.ownershipDeniedAtS, 1e-9)

        // echter Fremdfall: Zaehler und Stempel gesetzt wie bisher
        sink.own = "GANZ ANDERER TEXT"
        c.onUpdateSelection(0, 0, 2, 5)
        clock.addAndGet(500)
        assertFalse(c.applyPartial(hyp("hallo welt nochmehr", c.sessionId)))
        assertEquals("echte Verweigerung zaehlt", 1, c.ownershipDeniedCount)
        assertTrue("Stempel gesetzt", c.ownershipDeniedAtS >= 0.0)
    }

    // A2b-Rote-Test 4: E1c-Koordinate — frischer Sink-Text hat Vorrang nach
    // Präfixregel vor älterer Tracker-Erwartung; lost=false NUR nach positivem
    // Suffixbeweis; der ownEditFresh-Sonderfall allein gilt NICHT.
    @Test fun `A2b E1c-Praefixregel - lost nur nach Suffixbeweis`() {
        val clock = AtomicLong(1_000_000L)

        // (a) Präfixregel hebt die ältere Erwartung an; Beweis löscht lost:
        val t = OwnershipTracker(nowMs = { clock.get() })
        t.onUpdateSelection(0, 0, 0, 0) // System-Baseline
        t.onOwnEdit("abc def ghi") // ältere (längere) Tracker-Erwartung
        t.onUpdateSelection(0, 0, 2, 5) // Range: Fremdeingabe ⇒ lost=true
        assertTrue("lost nach Fremdeingabe", t.ownershipLost)
        clock.addAndGet(500) // Frische-Fenster geschlossen

        // Ohne Lift fiele der Suffixtest gegen die alte Erwartung ("abc def"
        // ist kein Suffix von "user abc def ghi") — der frischere Sink-Stand
        // (Präfix-Verwandter, kürzer) hat Vorrang:
        val lifted = t.rebaseline("user abc def", "abc def")
        assertTrue("Präfixregel: frischer Sink-Stand hebt die Erwartung an", lifted)
        assertFalse("lost=NUR nach positivem Suffixbeweis löschen", t.ownershipLost)

        // (b) ownEditFresh allein gilt NICHT als Suffixbeweis:
        val t2 = OwnershipTracker(nowMs = { clock.get() })
        t2.onUpdateSelection(0, 0, 0, 0)
        t2.onOwnEdit("alt") // Fenster läuft
        t2.onUpdateSelection(0, 0, 2, 5) // Range ⇒ lost=true (Fenster noch frisch)
        assertTrue("RE-SYNC bleibt zugesagt (true)", t2.rebaseline("FREMDES ZEUG"))
        assertTrue("ownEditFresh allein löscht lost NICHT", t2.ownershipLost)

        // (c) actual==null ist kein Beweis:
        val t3 = OwnershipTracker(nowMs = { clock.get() })
        t3.onUpdateSelection(0, 0, 0, 0)
        t3.onOwnEdit("abc")
        t3.onUpdateSelection(0, 0, 2, 5)
        clock.addAndGet(500)
        assertFalse("null-Lesefenster bleibt ablehnbar", t3.rebaseline(null))
        assertTrue("kein lost-Löschen bei actual==null", t3.ownershipLost)
    }
}
