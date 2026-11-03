package de.voicekeyboard.editor

/**
 * Ownership protocol for the dictation text in the editor (DESIGN.md §6).
 *
 * Detects whether the inserted text is still "ours", from:
 * - the expected own text (set after every successful own edit),
 * - "pending own selection callbacks" (our edit itself triggers
 *   `onUpdateSelection` — that is NOT foreign input),
 * - the editor generation (editor/app change revokes ownership).
 *
 * After detected foreign input (user types/moves the cursor, app change)
 * ownership is revoked for this session — [isOwned] then returns false;
 * [reset] (new dictation session) restores it, and A2b
 * (PLAN_V0133 §A2b) re-opens the lock via [rebaseline] after a
 * POSITIVE suffix proof has re-established the own span.
 *
 * Framework-free: the absolute selection positions come from the caller
 * (InputMethodService); patterns (range selection,
 * callback order) are evaluated, not absolute offsets — the cursor end position
 * check is handled by the Android layer (InputConnectionSink, part 3).
 */
class OwnershipTracker(
    /** AP7-E1b: injected clock (VClock pattern, JVM-testable); default wall clock. */
    private val nowMs: () -> Long = { System.currentTimeMillis() },
) {

    var generation: Long = 0L
        private set

    private val lock = Any()
    private var expectedText: String = ""
    private var ownEditPending = false
    private var baselineTaken = false
    private var lost = false

    /** AP7-E1b: time of the last own edit (0 = none yet) —
     *  basis of the freshness window against a restartInput in the MIDDLE of our
     *  own delete/commit sequence. */
    @Volatile private var lastOwnEditMs: Long = 0L

    /** true as soon as ownership has been lost/relinquished due to foreign input. */
    val ownershipLost: Boolean
        get() = synchronized(lock) { lost }

    /** AP7-E1: last own expected text (onOwnEdit) — read-only, never content in logs (rule 9). */
    val expectedOwnText: String
        get() = synchronized(lock) { expectedText }

    /** AP7-E1: number of executed re-baselines after restartInput (🐞 rebases). */
    var rebases: Int = 0
        private set

    /** New dictation session in the same editor: reset expectations, restore ownership. */
    fun reset() = synchronized(lock) {
        expectedText = ""
        ownEditPending = false
        baselineTaken = false
        lost = false
    }

    /**
     * AP7-E1d: re-open the selection baseline ONCE without touching the expectations —
     * for the retry window of a null read window: the app's
     * re-bind callback must still take the baseline (otherwise it sets
     * lost and the retry drives the session into the silent write stop
     * (H-E2 family)).
     */
    fun reopenSelectionBaseline() = synchronized(lock) {
        baselineTaken = false
    }

    /**
     * Editor/app change: new generation, ownership counts as lost until a
     * new session starts with [reset] (the old editor's text is irrelevant).
     */
    fun onEditorChanged() = synchronized(lock) {
        generation++
        lost = true
    }

    /**
     * After a **successful own edit**: new expected text; the own
     * `onUpdateSelection` callback triggered by it is expected as such.
     */
    fun onOwnEdit(newExpectedText: String) = synchronized(lock) {
        expectedText = newExpectedText
        ownEditPending = true
        lastOwnEditMs = nowMs() // AP7-E1b: freshness window restarts on every write
    }

    /**
     * `onUpdateSelection`-like signal (UI thread). The order decides:
     * 1. we expect the callback of our own edit → consume it (no
     *    ownership loss); a range selection is still foreign input,
     * 2. before the first own edit → baseline (system callback at the start),
     * 3. anything else without an expected own callback → **foreign input**,
     *    ownership permanently lost.
     */
    fun onUpdateSelection(
        oldSelStart: Int,
        oldSelEnd: Int,
        newSelStart: Int,
        newSelEnd: Int,
    ) = synchronized(lock) {
        if (newSelStart != newSelEnd) {
            // a selection range never comes from our own edit (commitText sets the cursor)
            lost = true
            ownEditPending = false
            return
        }
        when {
            ownEditPending -> ownEditPending = false // our own callback
            !baselineTaken -> baselineTaken = true    // system baseline before the 1st edit
            else -> lost = true                       // the user intervened
        }
    }

    /**
     * AP7-E1 (Fable §0.2.5 P1, vc33-T1 evidence): re-baseline after
     * `InputMethodManager.restartInput()` — many target apps (Chrome/WebView,
     * Compose < 1.6, Telegram, rich-text notes) call restartInput on EVERY
     * text change, i.e. on our FIRST partial write; previously the unattended
     * restarting=true killed the dictation run via onEditorChanged →
     * abort("editor_change") (user death cause: ~2 s, first fragment).
 *
     * New yield: ownership stays if our own committed text
     * ([expectedOwnText]) is still a SUFFIX of the actual text before the
     * cursor (same check as [isOwned] — user text BEFORE our area is
     * allowed). Then the selection baseline is re-OPENED
     * (baselineTaken=false): the callbacks of the app re-bind take the
     * baseline silently instead of setting `lost=true`; an open
     * ownEditPending is consumed (the commit callback of the old
     * connection never arrives).
     *
     * Returns false ⇒ the app has deleted/replaced our text ⇒ the caller aborts
     * in a controlled way (no ghost writing into a changed editor).
     *
     * A2b (PLAN_V0133 §A2b): `lost` is cleared exclusively after a POSITIVE
     * suffix proof — NEVER on `actual==null`, foreign text or merely a fresh
     * own edit (the ownEditFresh special case alone does NOT count as
     * proof); the RE-SYNC of the fresh edit remains promised (true),
     * without opening the lock.
     *
     * AP7-E1c (vc33 acceptance, P0 blocker `editor_rebaseline_lost` at 8.5 s in
     * restart:20): `restartInput` fires in the afterTextChanged of OUR
     * commit — the service sees a tracker expectation here that is OUTDATED
     * BY ONE write cycle (onOwnEdit runs worker-side only AFTER the
     * sink round trip), while the editor long ago has the longer new state.
     * The suffix test against the outdated expectation then falsifies LOSS.
     * [fresherExpected] (sink's own text, synchronous with the commit round) raises
     * the expectation when it is in a prefix relationship with the tracker state — in
     * both directions (appending as well as W3c rollback). No prefix relationship ⇒
     * distrustful: the tracker state stays authoritative (no foreign replacement).
     */
    fun rebaseline(actualBeforeCursor: CharSequence?, fresherExpected: CharSequence? = null): Boolean = synchronized(lock) {
        rebases += 1
        // AP7-E1c: raise the expectation to the fresher prefix relative.
        if (fresherExpected != null && fresherExpected.isNotEmpty()) {
            val fe = fresherExpected.toString()
            val cur = expectedText
            if ((fe.length > cur.length && fe.startsWith(cur)) ||
                (cur.length > fe.length && cur.startsWith(fe))
            ) {
                expectedText = fe
            }
        }
        // AP7-E1b (vc33 final build acceptance, restart:20 cell): restartInput fires
        // in the MIDDLE of our own delete/commit sequence — the app watcher
        // counts OUR write, the actual text is HALF (delete done, commit
        // open). The suffix test made a FALSE loss decision over that
        // (5th restart ⇒ editor_rebaseline_lost during a running
        // session). Fresh own edit sequence ⇒ RE-SYNC instead of loss:
        // the actual text is the NEW anchor, lost is NOT set; our
        // trailing commit sequence completes the text, continued writing
        // runs from the re-anchored state.
        // A2b (PLAN_V0133 §A2b): the POSITIVE suffix proof — ONE check,
        // shared by both branches; only it re-opens the lock. Without it:
        // RE-SYNC of the fresh edit (below) or rejection — never a
        // silent-lost after proven ownership (H-E2: write stop family).
        val suffixProven = actualBeforeCursor != null && expectedText.isNotEmpty() &&
            actualBeforeCursor.length >= expectedText.length &&
            actualBeforeCursor.toString().endsWith(expectedText)
        if (suffixProven) {
            baselineTaken = false // next callback = NEW baseline, no lost
            ownEditPending = false // commit callback of the OLD connection is obsolete
            lost = false // A2b: proof provided — lift the lock
            return true
        }
        // AP7-E1b (vc33 final build acceptance, restart:20 cell): restartInput fires
        // in the MIDDLE of our own delete/commit sequence — the app watcher
        // counts OUR write, the actual text is HALF (delete done, commit
        // open). Fresh own edit sequence ⇒ RE-SYNC instead of loss:
        // the actual text is the NEW anchor. A2b: NO lost-clearing without proof.
        val ownEditFresh = ownEditPending ||
            (lastOwnEditMs > 0 && nowMs() - lastOwnEditMs <= OWN_EDIT_FRESH_MS)
        if (ownEditFresh) {
            if (!actualBeforeCursor.isNullOrEmpty()) {
                expectedText = actualBeforeCursor.toString() // actual text = anchor
            }
            baselineTaken = false // next callback = NEW baseline, no lost
            ownEditPending = false // sequence application consumed (never arrives)
            return true
        }
        val stillOurs = expectedText.isEmpty()
        if (stillOurs) {
            baselineTaken = false // next callback = NEW baseline, no lost
            ownEditPending = false
        }
        stillOurs
    }

    /**
     * Ownership check before every write: [expectedText] must be the suffix of the
     * actual text before the cursor (user text BEFORE our area is
     * allowed, text written behind it / deviating is not).
     *
     * @param expectedText expected own text (e.g. DeltaTyper.currentText).
     * @param actualBeforeCursor current text before the cursor (same length as
     *   expected, read by the EditorSink) or null if not readable.
     */
    fun isOwned(expectedText: String, actualBeforeCursor: CharSequence?): Boolean =
        synchronized(lock) {
            if (lost) return false
            if (expectedText.isEmpty()) return true
            if (actualBeforeCursor == null) return false
            val actual = actualBeforeCursor.toString()
            actual.length >= expectedText.length && actual.regionMatches(
                actual.length - expectedText.length,
                expectedText,
                0,
                expectedText.length,
            )
        }

    private companion object {
        /** AP7-E1b: freshness window of own edits — within it a
         *  restartInput counts as being in the MIDDLE of our sequence (RE-SYNC, no loss). */
        const val OWN_EDIT_FRESH_MS = 400L
    }
}
