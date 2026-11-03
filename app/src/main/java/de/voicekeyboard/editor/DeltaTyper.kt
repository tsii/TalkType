package de.voicekeyboard.editor

/**
 * Delta typer per KONZEPT.md §2.7, Rev. E8 (docs/COMMIT_RULE_DECISION_2.md):
 * The transcription stream repeatedly delivers the whole text; only the
 * difference to the already written text is typed.
 *
 * E8.1 rules (W1–W4, order binding):
 * - **W1** ownership not proven → write NOTHING, `divergent=true`. Foreign text
 *   is never touched (ownStart is the hard boundary).
 * - **W2** no divergence (common >= committedLength) → minimal delta in the tail as before;
 *   committedLength may grow.
 * - **W3** divergence (common < committedLength) AND ownership proven → our own
 *   earlier error. Rollback allowed:
 *   **W3a** `committedLength := common` (shrinking explicitly allowed — D2
 *   lifted); **W3c** `toRemove = own.length − common` ≤ 60 codepoints,
 *   no sentence terminator/line break crossed, no ownStart crossed
 *   → delete in ONE atomic revision + type `target[common:]`;
 *   **W3d** limit violated → NO live rollback: committedLength stays,
 *   `divergent=true`, leave the text — A2a: visible ONCE; the
 *   next ownership-secured call revises the own span ENTIRELY.
 * - **W4** inconsistent state (divergence before ownStart) → like W1.
 *
 * Commit ≠ write (D3 fixed): `commitUpTo` is ONLY a marking of the
 * protected area; `revise` always writes the tail when W2/W3c applies.
 *
 * Limits: delete scope in **UTF-16 units** to the sink, limit 60
 * **codepoints**; surrogate pairs are never split.
 */

/** A2a (PLAN_V0133 §1 A2a): closed result kinds of [DeltaTyper.reviseOutcome]. */
enum class ReviseKind {
    /** Identical state — no sink call (also: freeze without a write step). */
    SAME,

    /** Ownership not proven — NOTHING written, foreign text untouched. */
    W1,

    /** Rollback limit violated — no live rollback, visible once. */
    W3d,

    /** Written — [ReviseOutcome.delta] is exactly ONE sink step. */
    DELTA,
}

/**
 * A2a (PLAN_V0133 §1 A2a): reason return of the typer production path — [kind]
 * is ALWAYS set (no null ambiguity like the earlier `TextDelta?`);
 * [delta] is set only for [ReviseKind.DELTA] (exactly one sink step).
 * SAME/W1/W3d write NOTHING.
 */
data class ReviseOutcome(val kind: ReviseKind, val delta: TextDelta?) {
    companion object {
        val SAME = ReviseOutcome(ReviseKind.SAME, null)
        val W1 = ReviseOutcome(ReviseKind.W1, null)
        val W3d = ReviseOutcome(ReviseKind.W3d, null)
    }
}
class DeltaTyper(
    private val sink: EditorSink,
    private val maxRevisionChars: Int = MAX_REVISION_CHARS,
) {

    /** Everything written so far by the dictation (own) text. */
    var currentText: String = ""
        private set

    /** Length of the committed prefix of [currentText] (E8.1 W3a: may shrink). */
    var committedLength: Int = 0
        private set

    /** W1/W3d/W4: divergence or ownership-loss message for the status row. */
    var divergent: Boolean = false
        private set

    /**
     * A2a (PLAN_V0133 §1 A2a): a W3d block is visible ONCE (no
     * permanent stall): the NEXT ownership-secured call resolves it
     * via an atomic full revision of the own span. true = the
     * last call reported W3d and has not yet been resolved.
     */
    private var w3dPending = false

    /** New session: reset the state (editor change / dictation restart). */
    fun reset() {
        currentText = ""
        committedLength = 0
        divergent = false
        w3dPending = false
    }

    /**
     * Marks the committed prefix as [length] (caller: CommitPolicy after
     * the word boundary release). D3: ONLY marking — no write gate.
     * E8.1 W3a: shrinking is allowed (rollback); clamped to [currentText].
     */
    fun commitUpTo(length: Int) {
        committedLength = length.coerceIn(0, currentText.length)
    }

    /**
     * A2a (PLAN_V0133 §1 A2a): reason return of the PRODUCTION PATH — closed
     * results instead of null ambiguity:
     *  - [ReviseKind.SAME] identical state — NO sink call (idempotency);
     *  - [ReviseKind.W1]   ownership not proven — NOTHING written;
     *  - [ReviseKind.W3d]  rollback limit violated — NO live rollback,
     *      visible ONCE (no permanent stall);
     *  - [ReviseKind.DELTA] written — [ReviseOutcome.delta] is exactly ONE
     *      sink step (atomic also in the rollback case W3c).
     *
     * W3d resolution: the NEXT ownership-secured call revises the own
     * span ENTIRELY and atomically (60-codepoint limit crossed once;
     * the foreign text boundary stays — replaceLength ≤ own length) and reports DELTA.
     * `committedLength` shrinks to the common prefix in the process (W3a analog).
     *
     * Throws if the [EditorSink] throws — the state then stays unchanged
     * (the block is retryable).
     */
    fun reviseOutcome(target: String): ReviseOutcome {
        divergent = false // re-evaluate per hypothesis
        if (!ownershipVerified()) { // W1: ownership not proven
            divergent = true
            return ReviseOutcome.W1
        }
        if (w3dPending) { // A2a: resolve the block — atomic full revision of the own span
            if (target == currentText) {
                w3dPending = false
                return ReviseOutcome.SAME
            }
            val common = commonPrefixLength(currentText, target)
            val delta = apply(TextDelta(currentText.length, target), target)
            w3dPending = false
            committedLength = common
            return ReviseOutcome(ReviseKind.DELTA, delta)
        }
        val common = commonPrefixLength(currentText, target)

        if (common >= committedLength) {
            // W2: no divergence — minimal delta in the tail
            return reviseTail(target, common)
        }

        // W3: divergence in the own area — our earlier error (case c).
        val toRemoveCodepoints = currentText.codePointCount(common, currentText.length)
        if (!rollbackAllowed(common, toRemoveCodepoints)) {
            // W3d: no live correction — leave the text, visible ONCE
            divergent = true
            w3dPending = true
            return ReviseOutcome.W3d
        }
        // W3a/W3c: committedLength shrinks to the common prefix,
        // then ONE atomic revision (deleting UTF-16-correct + rewriting).
        committedLength = common
        val removeUtf16 = currentText.length - common
        val toAdd = target.substring(common)
        if (removeUtf16 == 0 && toAdd.isEmpty()) {
            currentText = target
            return ReviseOutcome.SAME
        }
        return ReviseOutcome(ReviseKind.DELTA, apply(TextDelta(removeUtf16, toAdd), target))
    }

    /**
     * A2a/A2b (PLAN_V0133 §A2b): ownership-secured FINALE — checks ownership first
     *  (W1 without a write attempt) and CANNOT be blocked (§3.3: "finale
     *  W3d block excluded", no old partial hypothesis stays):
     *  - identical state ⇒ SAME (no duplicate, no sink call);
     *  - normal case ⇒ MINIMAL delta (W2 tail resp. W3c rollback — the
     *    stock expectation "Finale replaces the partial tail minimally" stays);
     *  - rollback limits violated (W3d case) OR freeze case (> 60 in the tail)
     *    ⇒ atomic FULL REVISION of the own span in exactly ONE sink step
     *    (60 limit crossed once; the foreign text boundary stays — the
     *    ownership proof prevents any foreign-text access).
     * Resolves an existing W3d block; committedLength shrinks in the
     * full/rollback case to the common prefix (W3a analog). Throws
     * if the [EditorSink] throws — the state then stays unchanged.
     */
    fun reviseFinal(target: String): ReviseOutcome {
        divergent = false
        if (!ownershipVerified()) { // ownership first — without proof no finale
            divergent = true
            return ReviseOutcome.W1
        }
        w3dPending = false
        if (target == currentText) return ReviseOutcome.SAME
        val common = commonPrefixLength(currentText, target)
        if (common >= committedLength) {
            // W2: minimal delta in the tail (like reviseOutcome) — EXCEPTION:
            // the freeze case becomes a full revision (the finale leaves nothing old standing).
            val oldTail = currentText.substring(committedLength)
            val newTail = target.substring(committedLength)
            val tailCommon = commonPrefixLength(oldTail, newTail)
            val toRemove = oldTail.length - tailCommon
            val toAdd = newTail.substring(tailCommon)
            if (toRemove == 0 && toAdd.isEmpty()) {
                currentText = target
                return ReviseOutcome.SAME
            }
            if (toRemove <= maxRevisionChars) {
                return ReviseOutcome(ReviseKind.DELTA, apply(TextDelta(toRemove, toAdd), target))
            }
        } else if (rollbackAllowed(common, currentText.codePointCount(common, currentText.length))) {
            // W3c: allowed rollback — minimal atomic revision (stock behavior).
            committedLength = common
            val removeUtf16 = currentText.length - common
            val toAdd = target.substring(common)
            if (removeUtf16 == 0 && toAdd.isEmpty()) {
                currentText = target
                return ReviseOutcome.SAME
            }
            return ReviseOutcome(ReviseKind.DELTA, apply(TextDelta(removeUtf16, toAdd), target))
        }
        // W3d/freeze case: the finale must NEVER be blocked — atomic
        // FULL REVISION of the own span in exactly ONE sink step.
        val delta = apply(TextDelta(currentText.length, target), target)
        committedLength = common
        return ReviseOutcome(ReviseKind.DELTA, delta)
    }

    // A2a/A2b (PLAN_V0133): the controller calls exclusively
    // reviseOutcome/reviseFinal — the former null compatibility bridge
    // (`revise(): TextDelta?`) is REMOVED (no compatibility null path).

    /** W2 path: minimal delta from [common] (existing logic). */
    private fun reviseTail(target: String, common: Int): ReviseOutcome {
        val oldTail = currentText.substring(committedLength)
        val newTail = target.substring(committedLength)
        val tailCommon = commonPrefixLength(oldTail, newTail)
        val toRemove = oldTail.length - tailCommon
        val toAdd = newTail.substring(tailCommon)

        if (toRemove == 0 && toAdd.isEmpty()) {
            currentText = target
            return ReviseOutcome.SAME // identical state: no delta, no sink call
        }

        if (toRemove > maxRevisionChars) {
            // > maxRevisionChars: commit back (freeze) and append
            if (toAdd.isEmpty()) {
                committedLength = currentText.length
                return ReviseOutcome.SAME // freeze without a write step
            }
            val frozenLength = currentText.length
            val delta = apply(TextDelta(0, toAdd), currentText + toAdd)
            committedLength = frozenLength
            return ReviseOutcome(ReviseKind.DELTA, delta)
        }
        return ReviseOutcome(ReviseKind.DELTA, apply(TextDelta(toRemove, toAdd), target))
    }

    /**
     * W3c limits (E8.3 §3): ≤ 60 codepoints, ≤ 2 committed words, no
     * sentence terminator (`. ! ? … :`), no line break in the delete scope.
     * Violations ⇒ false ⇒ W3d.
     */
    private fun rollbackAllowed(common: Int, toRemoveCodepoints: Int): Boolean {
        if (toRemoveCodepoints > maxRevisionChars) return false // 60-codepoint limit
        // check the delete scope [common, currentText.length)
        if (common >= currentText.length) return false // W4-like inconsistency case
        val removed = currentText.substring(common)
        if (removed.contains('\n') || removed.contains('\r')) return false // line break
        for (ch in removed) {
            if (ch == '.' || ch == '!' || ch == '?' || ch == '…' || ch == ':') return false
        }
        // ≤ 2 committed words: spaces in the delete scope count words
        val spaces = removed.count { it == ' ' }
        if (spaces >= 2) return false // 3+ word boundaries ⇒ more than 2 words
        return true
    }

    private fun apply(delta: TextDelta, newCurrent: String): TextDelta {
        sink.replaceBeforeCursor(delta.replaceLength, delta.newText)
        currentText = newCurrent
        return delta
    }

    /**
     * Ownership check before every write (DESIGN.md §6): length and context of the
     * own area must match the expectations, otherwise no access.
     */
    private fun ownershipVerified(): Boolean {
        if (currentText.isEmpty()) return true
        val own = sink.ownLength()
        if (own >= 0 && own != currentText.length) return false
        val context = sink.textBeforeCursor(currentText.length) ?: return false
        return context.toString() == currentText
    }

    companion object {
        const val MAX_REVISION_CHARS = 60

        /** Codepoint count of s[0, until). */
        fun codePointCount(s: String, until: Int): Int = s.codePointCount(0, until)

        /**
         * Length of the common prefix of two texts. Subtracts a high surrogate
         * at the end if needed, so that no surrogate pair is split (DESIGN.md §6).
         */
        fun commonPrefixLength(a: String, b: String): Int {
            val n = minOf(a.length, b.length)
            var i = 0
            while (i < n && a[i] == b[i]) i++
            if (i > 0 && i <= a.length && Character.isHighSurrogate(a[i - 1])) i--
            return i
        }
    }
}
