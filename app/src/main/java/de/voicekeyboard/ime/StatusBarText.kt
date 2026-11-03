package de.voicekeyboard.ime

/**
 * F13a/B1 (docs/STREAMING_FREEZE_ANALYSE.md §2): pure-logic text selection for
 * the IME status row — no Android types, so that the status freezing
 * („starte Aufnahme…“ staying sticky and covering errors) is proven on the
 * JVM (see StatusBarTextTest).
 *
 * Two strictly separated categories:
 *  - TRANSIENT (start message): only until overtaken; sets NO stickyError.
 *  - STICKY (errors): stays until the next deliberate action (R-2) or until
 *    a completed success resolves it (R-2b, `success`).
 *
 * Clarity rule (2 lines): a transient text never covers an ERROR that is
 * newer — newer = the caller passes `errorsNewerThanTransient`
 * (timestamp comparison at the caller). Older errors must not cover the
 * start text; during RECORDING/FINALIZING the transient text is
 * overtaken in principle.
 */
/**
 * F52 (PLAN_V0130 A3): kind of the last chosen status text — code for the
 * 🐞 export (status_res, rule 9: only codes/resource names, never contents).
 * RAW is the never-allowed fallback (assertion in the test).
 */
enum class StatusKind { STARTING, RECORDING, FINALIZING, IDLE, HINT, ERROR, DIAG, RAW }

object StatusBarText {

    data class Inputs(
        /** Newly passed text (error) OR the transient start text itself. */
        val extra: String? = null,
        /** Currently active transient text (e. g. „Starte Aufnahme…“); null = none. */
        val transientText: String? = null,
        /** Blocking error message (R-2): stays until the next deliberate action. */
        val stickyError: String? = null,
        /** Visible error code (audio/ASR errors, N-4) — can never become sticky. */
        val visibleError: String? = null,
        val recording: Boolean = false,
        val finalizing: Boolean = false,
        /** Recording text composed by the caller (route + debug diagnosis). */
        val recordingText: String,
        val finalizingText: String,
        val idleText: String,
        /** Clarity: errors (sticky/visible) are NEWER than the transient text. */
        val errorsNewerThanTransient: Boolean = false,
        /** A success has completed (e. g. final decode with output) → R-2b. */
        val success: Boolean = false,
        /** F52: the current transient text is a HINT (not the start message). */
        val transientHint: Boolean = false,
        /** F52: the recording text line carries the debug diagnosis suffix (diag). */
        val diagSuffix: Boolean = false,
    )

    data class Result(
        val text: String,
        /** True ⇒ the caller MAY clear stickyError (only on a completed success). */
        val stickyConsumed: Boolean,
        /** True ⇒ the caller MAY/SHOULD clear the transient text (it is overtaken). */
        val transientConsumed: Boolean,
        /** True ⇒ extra is to be tracked as stickyError (real error, not transient). */
        val extraIsSticky: Boolean,
        /** F52: kind of the chosen text — basis for status_res (rule 9). */
        val kind: StatusKind,
    )

    fun select(inputs: Inputs): Result {
        // An extra that IS the transient text itself is NOT an error.
        val extraTransient = inputs.extra != null && inputs.extra == inputs.transientText
        val effectiveTransient = inputs.transientText ?: inputs.extra?.takeIf { extraTransient }

        // 1. A real error extra: immediately visible and sticky (R-2).
        if (inputs.extra != null && !extraTransient) {
            return Result(inputs.extra, stickyConsumed = false, transientConsumed = true, extraIsSticky = true, kind = StatusKind.ERROR)
        }
        val errorsPresent = inputs.visibleError != null || inputs.stickyError != null
        // 2. TRANSIENT: only as long as no recording/finalizing runs and no
        //    NEWER error is present (clarity rule, see class comment).
        if (effectiveTransient != null && !inputs.recording && !inputs.finalizing &&
            !(errorsPresent && inputs.errorsNewerThanTransient)
        ) {
            return Result(effectiveTransient, stickyConsumed = false, transientConsumed = false, extraIsSticky = false, kind = if (inputs.transientHint) StatusKind.HINT else StatusKind.STARTING)
        }
        // 3. Success (recording completed, state back in Idle): R-2b —
        //    stickyError may be cleared, the display is the idle text.
        if (inputs.success && !inputs.recording && !inputs.finalizing) {
            return Result(inputs.idleText, stickyConsumed = true, transientConsumed = true, extraIsSticky = false, kind = StatusKind.IDLE)
        }
        // 4. New errors (N-4: visibleError before stickyError).
        inputs.visibleError?.let {
            return Result(it, stickyConsumed = false, transientConsumed = true, extraIsSticky = false, kind = StatusKind.ERROR)
        }
        inputs.stickyError?.let {
            return Result(it, stickyConsumed = false, transientConsumed = true, extraIsSticky = false, kind = StatusKind.ERROR)
        }
        // 5./6. Operation: recording and finalizing text (transient is overtaken).
        if (inputs.recording) {
            return Result(inputs.recordingText, stickyConsumed = false, transientConsumed = true, extraIsSticky = false, kind = if (inputs.diagSuffix) StatusKind.DIAG else StatusKind.RECORDING)
        }
        if (inputs.finalizing) {
            return Result(inputs.finalizingText, stickyConsumed = false, transientConsumed = true, extraIsSticky = false, kind = StatusKind.FINALIZING)
        }
        // 7. Idle without success: sticky stays untouched (already handled above),
        //    here no error is active anymore, guaranteed.
        return Result(inputs.idleText, stickyConsumed = false, transientConsumed = true, extraIsSticky = false, kind = StatusKind.IDLE)
    }

    /**
     * F52 (PLAN_V0130 A3): status_res code from the kind — only codes or
     * resource NAMES, never contents (rule 9). ERROR carries the resource
     * name if the caller can derive it (otherwise error:unknown);
     * HINT deliberately has no name (the resource name is not centrally
     * derivable at the seam — StatusEvent only carries text).
     */
    fun statusResCode(kind: StatusKind, errorResName: String? = null): String = when (kind) {
        StatusKind.STARTING -> "starting"
        StatusKind.RECORDING -> "recording"
        StatusKind.FINALIZING -> "finalizing"
        StatusKind.IDLE -> "idle"
        StatusKind.HINT -> "hint"
        StatusKind.DIAG -> "diag"
        StatusKind.ERROR -> "error:" + (errorResName ?: "unknown")
        StatusKind.RAW -> "raw"
    }
}
