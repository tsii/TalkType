package de.voicekeyboard.ime

import android.text.InputType
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T17 (Enter in multiline fields, REVIEW_STAGE1 VK-08; VK-R1 Review V0.1.44):
 * EditorInfo matrix of the pure seam [applyEnterKey] — mirrors AOSP: Enter is a
 * NEWLINE exactly for multiline fields, IME_ACTION_NONE,
 * IME_FLAG_NO_ENTER_ACTION and a null EditorInfo; otherwise the concrete
 * action (GO/SEND/DONE/…) — including UNSPECIFIED, the DEFAULT action — is
 * routed to the app via performEditorAction.
 *
 * Test style: fake InputConnection, plain JVM (pattern:
 * InputConnectionSinkSessionTest) — only EditorInfo data fields, no framework.
 * Rule 9: no user content anywhere, only counters and action codes.
 */
class EditorActionEnterTest {

    /** Recording fake: counts newline commits and routed action codes. */
    private class RecordingConnection : InputConnection {
        var newlines = 0
            private set
        val actions = mutableListOf<Int>()

        override fun commitText(text: CharSequence, newCursorPosition: Int): Boolean {
            if (text == "\n") newlines++
            return true
        }

        override fun performEditorAction(actionCode: Int): Boolean {
            actions.add(actionCode)
            return true
        }

        // ---- remaining interface members: neutral defaults ----
        override fun getTextBeforeCursor(n: Int, flags: Int): CharSequence? = null
        override fun getTextAfterCursor(n: Int, flags: Int): CharSequence? = null
        override fun getSelectedText(flags: Int): CharSequence? = null
        override fun getCursorCapsMode(reqModes: Int): Int = 0
        override fun getExtractedText(
            request: android.view.inputmethod.ExtractedTextRequest?,
            flags: Int,
        ): android.view.inputmethod.ExtractedText? = null
        override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean = true
        override fun deleteSurroundingTextInCodePoints(beforeLength: Int, afterLength: Int): Boolean = true
        override fun beginBatchEdit(): Boolean = true
        override fun endBatchEdit(): Boolean = true
        override fun setComposingText(text: CharSequence, newCursorPosition: Int): Boolean = true
        override fun setComposingRegion(start: Int, end: Int): Boolean = true
        override fun finishComposingText(): Boolean = true
        override fun sendKeyEvent(event: android.view.KeyEvent?): Boolean = true
        override fun setSelection(start: Int, end: Int): Boolean = true
        override fun performContextMenuAction(id: Int): Boolean = true
        override fun clearMetaKeyStates(states: Int): Boolean = true
        override fun reportFullscreenMode(enabled: Boolean): Boolean = true
        override fun performPrivateCommand(action: String?, data: android.os.Bundle?): Boolean = true
        override fun requestCursorUpdates(cursorUpdateMode: Int): Boolean = true
        override fun commitCompletion(info: android.view.inputmethod.CompletionInfo?): Boolean = true
        override fun commitCorrection(info: android.view.inputmethod.CorrectionInfo?): Boolean = true
        override fun commitContent(
            inputContentInfo: android.view.inputmethod.InputContentInfo,
            flags: Int,
            opts: android.os.Bundle?,
        ): Boolean = false
        override fun closeConnection() {}
        override fun getHandler(): android.os.Handler? = null
    }

    /** EditorInfo fixture: text class, optional multiline flag, action, optional NO_ENTER_ACTION. */
    private fun editor(
        multiline: Boolean = false,
        action: Int = EditorInfo.IME_ACTION_NONE,
        noEnterAction: Boolean = false,
    ): EditorInfo = EditorInfo().apply {
        inputType = InputType.TYPE_CLASS_TEXT or
            (if (multiline) InputType.TYPE_TEXT_FLAG_MULTI_LINE else 0)
        imeOptions = action or (if (noEnterAction) EditorInfo.IME_FLAG_NO_ENTER_ACTION else 0)
    }

    @Test
    fun `single line + GO - performEditorAction(GO), no newline`() {
        val ic = RecordingConnection()
        applyEnterKey(ic, editor(action = EditorInfo.IME_ACTION_GO))
        assertEquals("action routed to the app", listOf(EditorInfo.IME_ACTION_GO), ic.actions)
        assertEquals("no newline commit", 0, ic.newlines)
    }

    @Test
    fun `single line + DONE - performEditorAction(DONE), no newline`() {
        val ic = RecordingConnection()
        applyEnterKey(ic, editor(action = EditorInfo.IME_ACTION_DONE))
        assertEquals(listOf(EditorInfo.IME_ACTION_DONE), ic.actions)
        assertEquals(0, ic.newlines)
    }

    @Test
    fun `multiline + DONE - commitText newline, NO performEditorAction`() {
        val ic = RecordingConnection()
        applyEnterKey(ic, editor(multiline = true, action = EditorInfo.IME_ACTION_DONE))
        assertEquals("Done in a multiline field = line break (AOSP LatinIME)", 1, ic.newlines)
        assertTrue("no action routed (keyboard stays open, no submit)", ic.actions.isEmpty())
    }

    @Test
    fun `multiline + NONE - commitText newline`() {
        val ic = RecordingConnection()
        applyEnterKey(ic, editor(multiline = true, action = EditorInfo.IME_ACTION_NONE))
        assertEquals(1, ic.newlines)
        assertTrue(ic.actions.isEmpty())
    }

    @Test
    fun `IME_FLAG_NO_ENTER_ACTION + GO - commitText newline`() {
        val ic = RecordingConnection()
        applyEnterKey(
            ic,
            editor(action = EditorInfo.IME_ACTION_GO, noEnterAction = true),
        )
        assertEquals("NO_ENTER_ACTION suppresses the action (AOSP rule)", 1, ic.newlines)
        assertTrue(ic.actions.isEmpty())
    }

    @Test
    fun `no EditorInfo - commitText newline (safe default)`() {
        val ic = RecordingConnection()
        applyEnterKey(ic, null)
        assertEquals(1, ic.newlines)
        assertTrue(ic.actions.isEmpty())
    }

    @Test
    fun `single line + UNSPECIFIED - performEditorAction(UNSPECIFIED), no newline`() {
        val ic = RecordingConnection()
        applyEnterKey(ic, editor(action = EditorInfo.IME_ACTION_UNSPECIFIED))
        // VK-R1 (Review V0.1.44, AOSP InputMethodService.sendDefaultEditorAction /
        // LatinIME): UNSPECIFIED (0) is the editor's DEFAULT action — routed via
        // performEditorAction(0), NEVER commitText (was: newline before VK-R1).
        assertEquals(
            "UNSPECIFIED routes the default action (AOSP)",
            listOf(EditorInfo.IME_ACTION_UNSPECIFIED),
            ic.actions,
        )
        assertEquals("no newline commit", 0, ic.newlines)
    }
}
