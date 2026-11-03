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
 * A5 (v0.1.34): session reset of the sink ownership span — the ownSpan corpse
 * between dictation sessions (toggle-W1 case).
 *
 * Root cause (debugger conv-6cf9903c): [InputConnectionSink.ownSpan] survives
 * the session end — coordinator+sink are reused across sessions
 * (VoiceKeyboardService), `DictationController.start()` resets only
 * typer/tracker. From the SECOND hypothesis of the following session on,
 * `DeltaTyper.ownershipVerified` fails (sink.ownLength() ≠
 * currentText.length) ⇒ W1, nothing reaches the editor anymore.
 *
 * Test environment: REAL [InputConnectionSink] over a fake [InputConnection]
 * (JVM, direct executor) — exactly like production, just without a Looper.
 */
class InputConnectionSinkSessionTest {

    /** Fake engine: returns configurable text for the current session. */
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
     * Fake InputConnection (StringBuilder) for JVM unit tests — pattern like the
     * instrumented fake (DictationPipelineTest), minimal here: counts
     * delete/commit contacts, forbidden operations (setComposingText) abort hard.
     */
    private class FakeInputConnection(initial: String = "") : InputConnection {
        private val buf = StringBuilder(initial)
        var deletes = 0
            private set
        var commits = 0
            private set
        val text: String get() = buf.toString()

        /** Simulates an editor swap/user input between sessions. */
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

        // ---- remaining interface members: neutral defaults ----
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
     * REVIEW_A5_W P0 counter-check: production wrap CountingSink(EditorSink) like
     * VoiceKeyboardService (:1008/:1081). Without resetSession() delegation in
     * the wrapper the interface default would be a no-op and the corpse would
     * survive in production.
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

    // --- A5 (a): session handover reproduction — ownSpan corpse ⇒ W1 ---

    @Test fun `session switch resets the sink span and session 2 writes a delta instead of W1`() {
        val ic = FakeInputConnection()
        val sink = InputConnectionSink({ ic }, InputConnectionSink.DIRECT_EXECUTOR)
        val engine = FakeEngine()
        val controller = DictationController(engine, sink)

        // --- session 1: leave behind an own span > 0 ---
        assertTrue(controller.start())
        engine.nextText = "Hallo"
        runBlocking { controller.feedAudio(seconds(2)) }
        assertEquals("session 1 wrote into the editor", "Hallo", ic.text)
        assertEquals(5, sink.ownLength())
        assertEquals("Hallo", sink.ownText())
        runBlocking { controller.stop() }

        // --- session switch: the same sink instance, NEW dictation ---
        assertTrue(controller.start())
        // A5: the span of the previous session is NOT ours anymore —
        // ownership does not carry over the session boundary.
        assertEquals("ownLength after the session switch", 0, sink.ownLength())
        assertEquals("ownText after the session switch", "", sink.ownText())

        // --- session 2, first hypothesis: DELTA, the text lands in the sink ---
        engine.nextText = "Welt"
        runBlocking { controller.feedAudio(seconds(2)) }
        assertEquals("delta", controller.writeReason)
        assertEquals("Welt", sink.ownText())

        // --- session 2, revision of the own span: the toggle-W1 case.
        // With the ownSpan corpse DeltaTyper.ownershipVerified fails
        // (ownLength 9 ≠ currentText 4) ⇒ W1; with the reset ⇒ DELTA. ---
        engine.nextText = "Welt mehr"
        runBlocking { controller.feedAudio(seconds(1)) }
        assertEquals("no W1 in session 2 (ownSpan corpse)", 0, controller.writeW1Count)
        assertEquals("delta", controller.writeReason)
        assertEquals("session-1 text stays, session 2 writes", "HalloWelt mehr", ic.text)
        assertEquals("Welt mehr", sink.ownText())
        runBlocking { controller.stop() }
    }

    // --- A5 (b): the reset is not retroactive — after resetSession() apply
    // no longer uses lengths from the OLD span: a late delta rest of the
    // previous session must not touch the foreign editor anymore. ---

    @Test fun `resetSession is not retroactive - old span lengths do not modify the foreign editor`() {
        val ic = FakeInputConnection()
        val sink = InputConnectionSink({ ic }, InputConnectionSink.DIRECT_EXECUTOR)

        // session-1 rest: leave behind an own span of length 5
        sink.replaceBeforeCursor(0, "Hallo")
        assertEquals(5, sink.ownLength())
        assertEquals(1, ic.commits)

        // session switch: the old span is NOT ours anymore …
        sink.resetSession()
        assertEquals(0, sink.ownLength())
        assertEquals("", sink.ownText())

        // … and the editor has become FOREIGN in the meantime (app swap/user input):
        ic.replaceAll("Fremdtext")

        // Late delta rest of the OLD session: replaceLength 5 comes from the
        // corpse. The call must NOT modify the foreign editor anymore — no
        // deleteSurroundingText, no commitText (outside our own span there is
        // no ownership; the edit fails in a controlled way BEFORE touching
        // the editor).
        val outcome = runCatching { sink.replaceBeforeCursor(5, "X") }
        assertTrue("applying the old span length must fail", outcome.isFailure)
        assertEquals("Fremd-Editor unangetastet", "Fremdtext", ic.text)
        assertEquals("no deleteSurroundingText on the foreign editor", 0, ic.deletes)
        assertEquals("no additional commit on the foreign editor", 1, ic.commits)
    }

    // --- REVIEW_A5_W P0 counter-check: the same session handover through the
    // production wrap (CountingWrapper like VoiceKeyboardService.CountingSink).
    // The wrapper MUST pass resetSession() through to the inner sink —
    // otherwise it is an interface no-op in production and the corpse survives. ---

    @Test fun `production wrap CountingSink delegates the session reset to the inner sink`() {
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
        assertEquals("the wrapper passes the session reset through (the actual P0 proof)", 0, wrapped.ownLength())
        engine.nextText = "Welt"
        runBlocking { controller.feedAudio(seconds(2)) }
        assertEquals("delta", controller.writeReason)
        assertEquals("Welt", wrapped.ownText())
        assertEquals("wrapper counts commits unchanged", 2, wrapped.commits)
        runBlocking { controller.stop() }
    }
}
