package de.voicekeyboard.editor

import android.view.inputmethod.InputConnection

/** InputConnection not (any longer) available — orderly abort instead of a crash. */
class EditorUnavailableException(message: String) : IllegalStateException(message)

/** The editor rejected a partial step of the edit — no blind retry (DESIGN §6). */
class EditorWriteException(message: String) : IllegalStateException(message)

/**
 * Android binding of [EditorSink] via the [InputConnection] of the running
 * IME session (DESIGN.md §6).
 *
 * - [replaceBeforeCursor] wraps `beginBatchEdit` … `endBatchEdit` around
 *   `deleteSurroundingText` + `commitText` (no `setComposingText` misuse,
 *   no `setText`, no clipboard).
 * - `deleteSurroundingText` counts **UTF-16 code units**; the delete length is
 *   additionally stepped back to grapheme cluster boundaries via [Graphemes], so that never
 *   an `ä` (a + U+0308), surrogate pair or emoji-ZWJ is split. The
 *   undeleted remainder is prepended to the new text — the result is identical.
 * - Null InputConnection (editor gone/app change): defined error
 *   ([EditorUnavailableException]) resp. null for readers — no crash.
 * - No logs of text contents (privacy, DESIGN §6).
 */
class InputConnectionSink(
    private val connectionProvider: () -> InputConnection?,
    /** Performs editor operations (production: main thread; tests: direct). N-1. */
    private val editExecutor: java.util.concurrent.Executor = MAIN_EXECUTOR,
) : EditorSink {

    /** Default executor: main thread via the main looper (N-1, DESIGN §2.3-1).
     *  Invariant: EVERY InputConnection operation runs on this executor;
     *  the ASR worker only decodes and calls the editor sync. JVM fallback inline,
     *  so that the sink also works without an Android looper (unit test). */
    companion object {
        // lazy: in the unit test without an Android runner, Looper is not touched
        private val MAIN_EXECUTOR: java.util.concurrent.Executor by lazy {
            runCatching { android.os.Looper.getMainLooper() }.getOrNull()?.let { looper ->
                java.util.concurrent.Executor { runnable -> android.os.Handler(looper).post(runnable) }
            } ?: java.util.concurrent.Executor { it.run() } // JVM fallback (no looper)
        }

        /** Test direct executor: same call semantics without a looper. */
        val DIRECT_EXECUTOR: java.util.concurrent.Executor = java.util.concurrent.Executor { it.run() }

        /** S5-2: finite wait deadline for the editor sync (see onEditExecutor). */
        private const val EDIT_TIMEOUT_MS = 2_000L
    }

    /** Executes [block] on the edit executor and WAITS (EditorSink is synchronous).
     *
     *  S5-2 deadlock protection: if the caller is already on the main looper, the
     *  block is executed INLINE (a post to the same looper would never be
     *  processed before we block on await() → guaranteed deadlock).
     *  Otherwise post + finite waiting: EDIT_TIMEOUT_MS = 2000 ms — sized
     *  as a multiple of the observed edit duration (µs range) plus reserve for
     *  a blocked main thread under load (the ANR threshold is at 5 s,
     *  so we report well before the system). On timeout a typed error
     *  (IllegalStateException), never an unbounded await().
     *
     *  A1 ghost writing (REVIEW_STAGE6): the posted block cannot be cancelled
     *  via the Handler — after the timeout it runs later on the main thread.
     *  Therefore it checks the per-call [abandoned] flag BEFORE every editor
     *  touch and becomes a no-op if the caller has given up.
     *
     *  Invariant (A1, binding): after the timeout the block becomes a no-op; there
     *  are EXACTLY ZERO editor touches after giving up (no
     *  deleteSurroundingText, no commitText, no getTextBeforeCursor, no
     *  connectionProvider() call — the block then reads nothing anymore).
     *  EVERY wait stays finite; a hung main thread leads to a visible
     *  error, never to a frozen worker thread. */
    private fun <T> onEditExecutor(block: () -> T): T {
        if (isOnMainLooper()) return block() // (a) reentrancy: inline instead of post
        val latch = java.util.concurrent.CountDownLatch(1)
        val abandoned = java.util.concurrent.atomic.AtomicBoolean(false)
        var result: T? = null
        var error: Throwable? = null
        editExecutor.execute {
            try {
                if (abandoned.get()) {
                    // A1: caller has given up → ZERO editor touches
                    android.util.Log.w("VK-IME", "edit abandoned (timeout) — no-op")
                    return@execute
                }
                result = block()
            } catch (t: Throwable) {
                if (!abandoned.get()) error = t // error after giving up: nobody is waiting
            } finally {
                latch.countDown()
            }
        }
        // (b) finite deadline instead of an unbounded latch.await()
        if (!latch.await(EDIT_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS)) {
            abandoned.set(true) // A1: mark BEFORE throwing — block becomes a no-op
            throw IllegalStateException(
                "Editor-Sync Timeout: Main-Thread hat den Edit nicht in " +
                    EDIT_TIMEOUT_MS + " ms ausgeführt (blockiert/überlastet?)"
            )
        }
        error?.let { throw it }
        @Suppress("UNCHECKED_CAST")
        return result as T
    }

    private fun isOnMainLooper(): Boolean {
        val looper = runCatching { android.os.Looper.getMainLooper() }.getOrNull() ?: return false
        val mine = runCatching { android.os.Looper.myLooper() }.getOrNull()
        return mine != null && mine === looper
    }

    /** Own span in UTF-16 code units (length/structure only, never logged).
     *  AP7-E1c: @Volatile — ownText() is also read outside the edit thread
     *  (re-baseline diagnostics fields); the write set itself stays
     *  edit-thread-serialized. */
    @Volatile private var ownSpan: String = ""

    /** A5 (v0.1.34): session change — eliminate the ownSpan corpse (toggle W1:
     *  coordinator + sink are reused across sessions, the
     *  span of the previous session fakes the ownership proof from the second
     *  hypothesis of the follow-up session in DeltaTyper.ownershipVerified).
     *  Direct @Volatile assignment, synchronous — there are no derivations of
     *  ownSpan; the write set itself stays edit-thread-serialized, and
     *  the caller (DictationController.start()) serializes the
     *  session boundary anyway (one orchestration thread). */
    override fun resetSession() {
        ownSpan = ""
    }

    override fun replaceBeforeCursor(replaceLength: Int, newText: String) {
        onEditExecutor { replaceBeforeCursorSync(replaceLength, newText) }
    }

    private fun replaceBeforeCursorSync(replaceLength: Int, newText: String) {
        val ic = connectionProvider()
            ?: throw EditorUnavailableException("InputConnection nicht verfügbar")
        // Align the delete length with a grapheme boundary (UTF-16 units stay preserved,
        // only the boundary may move forward; the remainder migrates into the text).
        var deleteLength = replaceLength
        while (deleteLength > 0 && !Graphemes.isBoundary(ownSpan, ownSpan.length - deleteLength)) {
            deleteLength--
        }
        val keptPrefix = if (deleteLength < replaceLength) {
            ownSpan.substring(ownSpan.length - replaceLength, ownSpan.length - deleteLength)
        } else {
            ""
        }
        val effectiveText = keptPrefix + newText

        ic.beginBatchEdit() // groups the display update, is not a transaction (DESIGN §6)
        try {
            if (deleteLength > 0 && !ic.deleteSurroundingText(deleteLength, 0)) {
                throw EditorWriteException("deleteSurroundingText wurde abgelehnt")
            }
            if (effectiveText.isNotEmpty() && !ic.commitText(effectiveText, 1)) {
                throw EditorWriteException("commitText wurde abgelehnt")
            }
            ownSpan = ownSpan.dropLast(replaceLength) + newText
        } finally {
            ic.endBatchEdit()
        }
    }

    override fun textBeforeCursor(maxChars: Int): CharSequence? =
        onEditExecutor {
            val ic = connectionProvider() ?: return@onEditExecutor null
            // 0 = no text flags: raw text, no meta text
            ic.getTextBeforeCursor(maxChars, 0)
        }

    override fun ownLength(): Int = ownSpan.length

    // AP7-E1c: synchronous own text from the commit round (see EditorSink).
    override fun ownText(): String = ownSpan
}
