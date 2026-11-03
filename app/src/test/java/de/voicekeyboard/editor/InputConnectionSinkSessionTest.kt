package de.voicekeyboard.editor

import android.view.inputmethod.InputConnection
import de.voicekeyboard.asr.AsrEngine
import de.voicekeyboard.asr.Hypothesis
import de.voicekeyboard.audio.PcmBlock
import de.voicekeyboard.audio.PcmWindow
import de.voicekeyboard.dictation.DictationController
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * A5 (v0.1.34): Sitzungs-Reset der Sink-Eigentumsspanne — die ownSpan-Leiche
 * zwischen Diktat-Sitzungen (Toggle-W1-Fall).
 *
 * Root-Cause (Debugger conv-6cf9903c): [InputConnectionSink.ownSpan] überlebt
 * das Sitzungsende — Coordinator+Sink werden über Sitzungen hinweg
 * wiederverwendet (VoiceKeyboardService), `DictationController.start()` setzt
 * nur Typer/Tracker zurück. Ab der ZWEITEN Hypothese der Folgesitzung
 * scheitert `DeltaTyper.ownershipVerified` (sink.ownLength() ≠
 * currentText.length) ⇒ W1, nichts landet mehr im Editor.
 *
 * Testumgebung: ECHTER [InputConnectionSink] über eine Fake-[InputConnection]
 * (JVM, Direkt-Executor) — genau wie die Produktion, nur ohne Looper.
 */
class InputConnectionSinkSessionTest {

    /** Fake-Engine: liefert konfigurierbaren Text für die aktuelle Session. */
    private class FakeEngine : AsrEngine {
        var nextText = ""
        override fun load(modelDir: File) {}
        override suspend fun transcribe(
            window: PcmWindow,
            sessionId: Long,
            runId: Long,
        ): Hypothesis = Hypothesis(
            tokenIds = IntArray(0),
            text = nextText,
            frameTimesMs = IntArray(0),
            sessionId = sessionId,
            runId = runId,
            audioSamples = window.samples.size,
            incomplete = false,
        )
        override fun close() {}
    }

    /**
     * Fake-InputConnection (StringBuilder) für JVM-Unit-Tests — Muster wie der
     * instrumentierte Fake (DictationPipelineTest), hier minimal: zählt
     * delete/commit-Kontakte, verbotene Operationen (setComposingText) brechen hart.
     */
    private class FakeInputConnection(initial: String = "") : InputConnection {
        private val buf = StringBuilder(initial)
        var deletes = 0
            private set
        var commits = 0
            private set
        val text: String get() = buf.toString()

        /** Simuliert Editor-Austausch/Nutzereingabe zwischen Sitzungen. */
        fun replaceAll(newText: String) {
            buf.setLength(0)
            buf.append(newText)
        }

        override fun getTextBeforeCursor(n: Int, flags: Int): CharSequence? =
            buf.takeLast(minOf(n, buf.length))

        override fun getTextAfterCursor(n: Int, flags: Int): CharSequence? =
            buf.take(minOf(n, buf.length))

        override fun beginBatchEdit(): Boolean = true

        override fun endBatchEdit(): Boolean = true

        override fun commitText(text: CharSequence, newCursorPosition: Int): Boolean {
            commits++
            buf.append(text)
            return true
        }

        override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
            deletes++
            val keep = (buf.length - beforeLength).coerceAtLeast(0)
            buf.delete(keep, buf.length)
            return true
        }

        override fun setComposingText(text: CharSequence, newCursorPosition: Int): Boolean =
            error("setComposingText verboten (DESIGN §6)")

        // ---- restliche Interface-Member: neutrale Defaults ----
        override fun getSelectedText(flags: Int): CharSequence? = ""
        override fun setSelection(start: Int, end: Int): Boolean = true
        override fun performEditorAction(actionCode: Int): Boolean = true
        override fun sendKeyEvent(event: android.view.KeyEvent?): Boolean = true
        override fun finishComposingText(): Boolean = true
        override fun clearMetaKeyStates(states: Int): Boolean = true
        override fun commitCorrection(info: android.view.inputmethod.CorrectionInfo?): Boolean = true
        override fun performContextMenuAction(id: Int): Boolean = true
        override fun getCursorCapsMode(reqModes: Int): Int = 0
        override fun reportFullscreenMode(enabled: Boolean): Boolean = true
        override fun performPrivateCommand(action: String?, data: android.os.Bundle?): Boolean = true
        override fun requestCursorUpdates(cursorUpdateMode: Int): Boolean = true
        override fun commitContent(
            inputContentInfo: android.view.inputmethod.InputContentInfo,
            flags: Int, opts: android.os.Bundle?,
        ): Boolean = false
        override fun closeConnection() {}
        override fun commitCompletion(arg0: android.view.inputmethod.CompletionInfo?): Boolean = true
        override fun deleteSurroundingTextInCodePoints(beforeLength: Int, afterLength: Int): Boolean = true
        override fun setComposingRegion(start: Int, end: Int): Boolean = true
        override fun getExtractedText(
            request: android.view.inputmethod.ExtractedTextRequest?, flags: Int,
        ): android.view.inputmethod.ExtractedText? = null
        override fun getHandler(): android.os.Handler? = null
    }

    private fun seconds(n: Int): PcmBlock = PcmBlock(0, 0, ShortArray(n * 16_000))

    /**
     * REVIEW_A5_W P0-Gegenprobe: Produktions-Wrap CountingSink(EditorSink) wie
     * VoiceKeyboardService (:1008/:1081). Ohne resetSession()-Delegation im Wrapper
     * wäre der Interface-Default ein No-op und die Leiche bliebe in Produktion.
     */
    private class CountingWrapper(private val inner: de.voicekeyboard.editor.EditorSink) :
        de.voicekeyboard.editor.EditorSink {
        var commits = 0
        override fun replaceBeforeCursor(replaceLength: Int, newText: String) {
            inner.replaceBeforeCursor(replaceLength, newText)
            commits++
        }

        override fun textBeforeCursor(maxChars: Int): CharSequence? = inner.textBeforeCursor(maxChars)
        override fun ownText(): String = inner.ownText()
        override fun ownLength(): Int = inner.ownLength()
        override fun resetSession() = inner.resetSession()
    }

    // --- A5 (a): Sitzungsübergabe-Reproduktion — ownSpan-Leiche ⇒ W1 ---

    @Test fun `Sitzungswechsel resettet die Sink-Spanne und Sitzung 2 schreibt Delta statt W1`() {
        val ic = FakeInputConnection()
        val sink = InputConnectionSink({ ic }, InputConnectionSink.DIRECT_EXECUTOR)
        val engine = FakeEngine()
        val controller = DictationController(engine, sink)

        // --- Sitzung 1: eigene Spanne > 0 hinterlassen ---
        assertTrue(controller.start())
        engine.nextText = "Hallo"
        runBlocking { controller.feedAudio(seconds(2)) }
        assertEquals("Sitzung 1 hat in den Editor geschrieben", "Hallo", ic.text)
        assertEquals(5, sink.ownLength())
        assertEquals("Hallo", sink.ownText())
        runBlocking { controller.stop() }

        // --- Sitzungswechsel: dieselbe Sink-Instanz, NEUE Diktation ---
        assertTrue(controller.start())
        // A5: die Spanne der Vorgänger-Sitzung ist NICHT mehr unsere —
        // Besitz vererbt sich nicht über die Sitzungsgrenze.
        assertEquals("ownLength nach Sitzungswechsel", 0, sink.ownLength())
        assertEquals("ownText nach Sitzungswechsel", "", sink.ownText())

        // --- Sitzung 2, erste Hypothese: DELTA, Text landet im Sink ---
        engine.nextText = "Welt"
        runBlocking { controller.feedAudio(seconds(2)) }
        assertEquals("delta", controller.writeReason)
        assertEquals("Welt", sink.ownText())

        // --- Sitzung 2, Revision der eigenen Spanne: der Toggle-W1-Fall.
        // Mit ownSpan-Leiche scheitert DeltaTyper.ownershipVerified
        // (ownLength 9 ≠ currentText 4) ⇒ W1; mit Reset ⇒ DELTA. ---
        engine.nextText = "Welt mehr"
        runBlocking { controller.feedAudio(seconds(1)) }
        assertEquals("kein W1 in Sitzung 2 (ownSpan-Leiche)", 0, controller.writeW1Count)
        assertEquals("delta", controller.writeReason)
        assertEquals("Sitzung-1-Text bleibt, Sitzung 2 schreibt", "HalloWelt mehr", ic.text)
        assertEquals("Welt mehr", sink.ownText())
        runBlocking { controller.stop() }
    }

    // --- A5 (b): Der Reset ist nicht-nachträglich — nach resetSession() wendet
    // apply KEINE Längen aus der ALTEN Spanne mehr an: ein verspäteter Delta-Rest
    // der Vorgänger-Sitzung darf den Fremd-Editor nicht mehr anfassen. ---

    @Test fun `resetSession ist nicht nachtraeglich - Laengen der alten Spanne aendern den Fremdeditor nicht`() {
        val ic = FakeInputConnection()
        val sink = InputConnectionSink({ ic }, InputConnectionSink.DIRECT_EXECUTOR)

        // Sitzung-1-Rest: eigene Spanne der Länge 5 hinterlassen
        sink.replaceBeforeCursor(0, "Hallo")
        assertEquals(5, sink.ownLength())
        assertEquals(1, ic.commits)

        // Sitzungswechsel: die alte Spanne ist NICHT mehr unsere …
        sink.resetSession()
        assertEquals(0, sink.ownLength())
        assertEquals("", sink.ownText())

        // … und der Editor ist inzwischen FREMD (App-Austausch/Nutzereingabe):
        ic.replaceAll("Fremdtext")

        // Verspäteter Delta-Rest der ALTEN Sitzung: replaceLength 5 kommt aus der
        // Leiche. Der Aufruf darf den Fremd-Editor NICHT mehr verändern — kein
        // deleteSurroundingText, kein commitText (außerhalb der eigenen Spanne
        // gibt es keinen Besitz; der Edit scheitert kontrolliert VOR jeder
        // Editorberührung).
        val outcome = runCatching { sink.replaceBeforeCursor(5, "X") }
        assertTrue("Anwendung alter Spannlänge muss fehlschlagen", outcome.isFailure)
        assertEquals("Fremd-Editor unangetastet", "Fremdtext", ic.text)
        assertEquals("kein deleteSurroundingText am Fremd-Editor", 0, ic.deletes)
        assertEquals("kein zusaetzlicher commit am Fremd-Editor", 1, ic.commits)
    }

    // --- REVIEW_A5_W P0-Gegenprobe: dieselbe Sitzungsübergabe durch den
    // Produktions-Wrap (CountingWrapper wie VoiceKeyboardService.CountingSink).
    // Der Wrapper MUSS resetSession() an den inneren Sink durchreichen — sonst
    // ist es in Produktion ein Interface-No-op und die Leiche überlebt. ---

    @Test fun `produktions-wrap CountingSink delegiert Sitzungsreset an den inneren Sink`() {
        val ic = FakeInputConnection()
        val inner = InputConnectionSink({ ic }, InputConnectionSink.DIRECT_EXECUTOR)
        val wrapped = CountingWrapper(inner)
        val engine = FakeEngine()
        val controller = DictationController(engine, wrapped)

        assertTrue(controller.start())
        engine.nextText = "Hallo"
        runBlocking { controller.feedAudio(seconds(2)) }
        assertEquals(5, wrapped.ownLength())
        runBlocking { controller.stop() }

        assertTrue(controller.start())
        assertEquals("Wrapper-Reicht Sitzungsreset durch (eigentlicher P0-Beweis)", 0, wrapped.ownLength())
        engine.nextText = "Welt"
        runBlocking { controller.feedAudio(seconds(2)) }
        assertEquals("delta", controller.writeReason)
        assertEquals("Welt", wrapped.ownText())
        assertEquals("Wrapper zählt Commits unveraendert", 2, wrapped.commits)
        runBlocking { controller.stop() }
    }
}
