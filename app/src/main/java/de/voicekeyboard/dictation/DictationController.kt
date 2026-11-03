package de.voicekeyboard.dictation

import de.voicekeyboard.asr.AsrEngine
import de.voicekeyboard.asr.Hypothesis
import de.voicekeyboard.audio.PcmBlock
import de.voicekeyboard.audio.PcmWindow
import de.voicekeyboard.commit.CommitPolicy
import de.voicekeyboard.editor.DeltaTyper
import de.voicekeyboard.editor.EditorSink
import de.voicekeyboard.editor.OwnershipTracker
import de.voicekeyboard.editor.ReviseKind
import de.voicekeyboard.editor.ReviseOutcome

/**
 * F13b/C1: result of a stop() call — the caller explicitly sees
 * WHETHER a final ran (small, JVM-testable; no boolean riddle).
 */
sealed class StopOutcome {
    /** stop() without a running recording and without an error: no-op, nothing to report. */
    object IdleNoOp : StopOutcome()

    /**
     * Final ran (whole-buffer decode); finalError only on a final error.
     * F13d/P2-1: SNAPSHOT of the session at stop time — the caller evaluates
     * ONLY this object, never the live controller state that the next
     * start() nulls ("quick release, immediately press again").
     */
    data class FinalRan(
        val finalError: String? = null,
        val failedWith: Boolean = false,
        val droppedWith: Int = 0,
        /** T10 (v0.1.39): did the session final append a space after
         *  punctuation at the end (VK-SESS field trail_sp=0|1, rule 9)? */
        val trailingSpace: Boolean = false,
    ) : StopOutcome()

    /** NO final: the session had already failed silently (fail() during
     *  RECORDING) — [errorCode] names the preserved error code; [failedWith]
     *  true (otherwise it would be IdleNoOp); [droppedWith] = dropped blocks. */
    data class NoFinal(
        val errorCode: String?,
        val failedWith: Boolean = true,
        val droppedWith: Int = 0,
    ) : StopOutcome()
}

/**
 * A2b (PLAN_V0133 §A2b): immutable diagnostic snapshot of ONE decode
 * window (partial, mini-final, session final) — numbers/codes only
 * (rule 9), no UI/Android type. The coordinator enriches it with its
 * session forensics (DecodeWindowDiagnosis) and passes it outward.
 */
data class DecodeWindowStats(
    /** Session audio length at window end (s). */
    val audioS: Double,
    /** Duration of the decode run (ms). */
    val decodeMs: Long,
    /** Currently effective partial interval (ms); 0 = pacing off. */
    val paceMs: Long,
    /** Partial decodes skipped due to pacing in this session. */
    val skips: Int,
    /** Length of the hypothesis (characters). */
    val hypLen: Int,
    /** Length of our own state after the window (characters). */
    val currentLen: Int,
    /** Committed prefix length after the window. */
    val committedLen: Int,
    /** Write result of the window (same|w1|w3d|delta|-). */
    val write: String,
    /** Gap since the last hypothesis (ms); -1 = never. */
    val hypGapMs: Long,
    /** Largest decode duration in the sample (ms). */
    val decodeMsMax: Long,
    /** p95 of the decode duration (ms). */
    val decodeMsP95: Long,
    /** Length of the decoded window (s). */
    val bufferS: Double,
)

/**
 * Push-to-talk state machine (KONZEPT.md §2.1) and dictation pipeline orchestration:
 *
 * AudioSource blocks ([feedAudio]) → ASR ([AsrEngine], injected) →
 * [CommitPolicy]/LocalAgreement → [DeltaTyper] → [EditorSink].
 *
 * Rules (all via injected, framework-free interfaces — JVM-testable):
 * - **Idempotency:** a double [start] during RECORDING is a no-op (false),
 *   [stop] without a running recording is a no-op (KONZEPT §2.1).
 * - **Session isolation:** every recording increments [sessionId]; hypotheses of an
 *   old session (or after a state change) are discarded — late
 *   ASR results never write into a new session ([applyPartial]).
 * - **min_audio_s = 2.0** (ASR_CONTRACT.md §4.4) applies **only to partials**
 *   (hallucination protection, KONZEPT §3.4); on [stop] the final always decodes
 *   over the entire buffer.
 * - **Ownership:** every write intent is checked via [OwnershipTracker.isOwned];
 *   after foreign input nothing is written anymore (DESIGN §6).
 * - **Error path:** engine/sink errors go via [fail] in an orderly way back to
 *   IDLE; text already written stays in place, the error code (never text content)
 *   becomes visible in [lastErrorCode].
 *
 * Callers serialize the accesses (one orchestration thread; on Android:
 * the service coroutine). [onUpdateSelection] signals from the UI thread are
 * guarded by the internally synchronized tracker.
 */
class DictationController(
    private val engine: AsrEngine,
    private val sink: EditorSink,
    private val policy: CommitPolicy = CommitPolicy(),
    private val tracker: OwnershipTracker = OwnershipTracker(),
    private val minAudioSecondsForPartials: Double = MIN_AUDIO_SECONDS_PARTIALS,
    private val sampleRateHz: Int = PcmWindow.SAMPLE_RATE_16K,
    /**
     * F13b2: monotonic clock for hypothesis gaps (🐞 field hyp_gap_ms).
     * Default nanoTime (JVM-testable, including all sleep); Android can
     * instead inject SystemClock.elapsedRealtime() in NANOSECONDS —
     * NEVER uptimeMillis (pauses in deep sleep, review F13c P2).
     */
    private val clock: () -> Long = { System.nanoTime() },
    /**
     * F21: cost-aware partial pacing ([DecodePace], C2 mitigation, user approval
     * 2026-10-30). Default false = behavior as before (every block decoded);
     * production sets true (DictationCoordinator). The final is NEVER affected.
     */
    private val decodePacing: Boolean = false,
) {

    var state: DictationState = DictationState.IDLE
        private set

    /** Monotonically increasing identifier of the current/last recording session. */
    var sessionId: Long = 0L
        private set

    /** Error code of the last error (never text content), null in the normal case. */
    var lastErrorCode: String? = null
        private set

    /**
     * F13b/C1: true as soon as the CURRENT session has failed via fail() —
     * preserved until a NEW START resets it (otherwise the error would
     * have become invisible, analysis line 76). lastErrorCode stays unchanged.
     */
    var failedThisSession: Boolean = false
        private set

    /** F13b: number of audio blocks dropped in feedAudio (🐞 diagnostics). */
    var droppedBlocks: Int = 0
        private set

    /** F13b2: number of hypotheses generated in the current session (🐞: hyp_count). */
    var hypothesesThisSession: Int = 0
        private set

    /** Timestamp (clock) of the last hypothesis; 0 = none yet. */
    private var lastHypAt: Long = 0L

    // ---- F17b/C2: decode-time diagnostics (numbers only, rule 9) ----
    // Fixed-size ring buffer: the last DECODE_SAMPLE_CAP measurements, never grows.
    private val decodeTimesMs = LongArray(DECODE_SAMPLE_CAP)
    // @Volatile: main reads on 🐞 tap, worker writes — the volatile count write
    // conveys the happens-before for the array slots written before it.
    @Volatile private var decodeSamples = 0 // stored measurements (≤ CAP)
    // decodeSeq: main resets it at start (resetDecodeStats from start(),
    // coordinator line 206), the worker increments it — hence @Volatile (F20-K).
    @Volatile private var decodeSeq = 0L // write counter for the ring index
    /** Buffer length (audioSeconds) at the last decode run; 0 = never decoded. */
    /** Buffer length (audioSeconds) at the last decode run; 0 = never decoded. */
    @Volatile private var lastDecodeBufferSeconds = 0.0

    /** F26b (C2 export): buffer level (s) at the time of the LAST accepted
     * hypothesis — proof that the buffer grew during the abort. Per session. */
    @Volatile var lastHypothesisBufferSeconds: Float = 0f

    /** AP3: number of denied write intents (ownership not proven). */
    @Volatile var ownershipDeniedCount: Int = 0

    /** AP3: first ownership-loss moment (session audio seconds); -1.0 = never. */
    @Volatile var ownershipDeniedAtS: Double = -1.0
        private set
    /** F21: duration of the last decode run in ms (input of the pacing, never content).
     *  A2b: publicly readable (🐞: last_decode_ms, numbers/codes — rule 9). */
    @Volatile var lastDecodeMs: Long = 0L
        private set
    /** F21: partial decodes skipped due to pacing in this session (🐞: decode_skips). */
    @Volatile private var decodeSkipsCount = 0

    // ---- A2b (PLAN_V0133 §A2b): write diagnostics (numbers/codes only, rule 9) ----

    /** A2b: write results in this session, monotonic (🐞: write_same). */
    @Volatile var writeSameCount: Int = 0
        private set

    /** A2b: denied write intents (🐞: write_w1). */
    @Volatile var writeW1Count: Int = 0
        private set

    /** A2b: blocked rollbacks — visible once (🐞: write_w3d). */
    @Volatile var writeW3dCount: Int = 0
        private set

    /** A2b: executed write steps (🐞: write_delta). */
    @Volatile var writeDeltaCount: Int = 0
        private set

    /** A2b: session audio seconds of the last DELTA; -1 = none yet
     *  (🐞: last_write_audio_s). */
    @Volatile var lastWriteAudioS: Double = -1.0
        private set

    /** A2b: last write code (same|w1|w3d|delta; "-" = none yet). */
    @Volatile var writeReason: String = "-"
        private set

    /** A2b: length of the last hypothesis (🐞: hyp_len). */
    @Volatile var hypLen: Int = 0
        private set

    /** A2b: our own state length after the last window (🐞: current_len). */
    @Volatile var currentLen: Int = 0
        private set

    /** A2b: committed prefix length after the last window (🐞: committed_len). */
    @Volatile var committedLen: Int = 0
        private set

    /**
     * A2b: callback after EVERY decode window (snapshot [DecodeWindowStats],
     * immutable, numbers/codes only). null = none. Caller: the coordinator
     * (enriches it and passes it outward; A4b wires the service).
     */
    @Volatile var onDecodeWindow: ((DecodeWindowStats) -> Unit)? = null

    /** Largest decode run measured so far in ms; 0 if never decoded.
     *  Read the count ONCE into a local (F20): otherwise the loop bound
     *  can see two different values between check and index → IOOBE. */
    fun decodeMsMax(): Long {
        val n = decodeSamples // read ONCE (F20)
        var m = 0L
        for (i in 0 until n) m = maxOf(m, decodeTimesMs[i])
        return m
    }

    /**
     * p95 of the decode runs in ms — deterministic: sorted sample,
     * index ceil(0.95·n)−1. empty → 0; one run → max == p95. The sample
     * covers the last ≤ 64 runs (ring buffer), not the whole session.
     */
    fun decodeMsP95(): Long {
        val n = decodeSamples // read ONCE (F20, IOOBE lever)
        if (n == 0) return 0L
        val sorted = decodeTimesMs.copyOf(n)
        sorted.sort()
        val idx = ((sorted.size * 95 + 99) / 100 - 1).coerceIn(0, sorted.size - 1)
        return sorted[idx]
    }

    /** Recorded audio (s) at the last decode run; 0.0 if never decoded. */
    fun bufferSeconds(): Double = lastDecodeBufferSeconds

    /** F21: currently effective partial interval in ms; 0 = pacing off (default). */
    fun decodePaceMs(): Long =
        if (!decodePacing) 0L
        else DecodePace.intervalMs(
            lastDecodeMs, (audioSeconds * 1000).toLong(), PACE_BASE_MS, PACE_MAX_MS,
        )

    /** F21: partial decodes skipped due to pacing in this session; 0 if pacing off. */
    fun decodeSkips(): Int = decodeSkipsCount

    /** F21: may the next partial run now? (The final is never checked here.) */
    private fun paceAllowsDecode(): Boolean {
        val secs = audioSeconds // read ONCE (derived getter)
        val intervalMs = DecodePace.intervalMs(
            lastDecodeMs, (secs * 1000).toLong(), PACE_BASE_MS, PACE_MAX_MS,
        )
        return (secs - lastDecodeBufferSeconds) * 1000.0 >= intervalMs
    }

    /** One decode run: elapsed ms (ring) + buffer length at decode time. */
    private fun noteDecode(startNs: Long) {
        val ms = ((clock() - startNs) / 1_000_000).coerceAtLeast(0L) // never negative
        decodeTimesMs[(decodeSeq % DECODE_SAMPLE_CAP).toInt()] = ms
        decodeSeq += 1
        if (decodeSamples < DECODE_SAMPLE_CAP) decodeSamples += 1
        lastDecodeMs = ms // F21: input of the cost-aware pacing
        lastDecodeBufferSeconds = audioSeconds
    }

    /** F13b2: gap since the last hypothesis in ms; -1 if none ever arrived. */
    fun currentHypGapMs(): Long =
        if (lastHypAt == 0L) -1L
        else ((clock() - lastHypAt) / 1_000_000).coerceAtLeast(0) // F13d/P2-2: never negative

    private val typer = DeltaTyper(sink)
    /** F17b/C2: counted per session (like droppedBlocks/hyp_count) — nulled in start(). */
    private fun resetDecodeStats() {
        decodeSamples = 0
        decodeSeq = 0L
        lastDecodeBufferSeconds = 0.0
        lastDecodeMs = 0L // F21: reset the pacing input per session
        decodeSkipsCount = 0
    }
    private var pcm = ShortArray(0)
    private var totalSamples = 0
    private var runId = 0L

    // ---- B1 (segmenter core): text seam across segment boundaries ----

    /** B1: accumulated PERMANENT committed text of all completed
     *  segments (prefix for hypotheses of the ACTIVE segment — the segment decode
     *  yields only segment text; writeHypothesis reconstructs the full
     *  session transcript from it). Empty = no segment boundary so far (behavior
     *  identical to the existing one: whole_buffer from 0). Consumer thread writes; @Volatile for
     *  the export reader (B1 fix P2a: session length via [segmentBaseSamples]). */
    @Volatile private var segmentPrefix = ""

    /** B1: samples of all completed segments (agreement/policy see the
     *  SESSION audio length, not the segment length — semantics of fullText).
     *  @Volatile: consumer writes, main thread reads the export length
     *  ([sessionAudioSeconds], B1 fix P2a) — without a visibility guarantee the
     *  export instead read the segment-local buffer (finding of test 6). */
    @Volatile private var segmentBaseSamples = 0L

    /** B1: does the last hypothesis end with punctuation (. ! ?) — input of
     *  the soft segment rule (a); @Volatile: capture thread reads (rule 9:
     *  boolean only, never text). */
    @Volatile private var lastHypPunct = false

    val audioSeconds: Double
        get() = if (sampleRateHz <= 0) 0.0 else totalSamples.toDouble() / sampleRateHz

    /**
     * B1 fix (review P2a): SESSION audio length = completed segments plus
     * the current buffer piece — after segments the 🐞 export (audio_s/buffer_s)
     * shows the CARRIED-OVER session value instead of the segment-local
     * buffer (which falls back to 0 after each mini-final). Without the segmenter
     * identical to [audioSeconds] (existing behavior).
     */
    val sessionAudioSeconds: Double
        get() = if (sampleRateHz <= 0) 0.0
            else (segmentBaseSamples + totalSamples).toDouble() / sampleRateHz

    /**
     * Start of a recording session. Idempotent: during IDLE→true and state
     * RECORDING; already running → false (no restart, KONZEPT §2.1).
     */
    fun start(): Boolean {
        if (state != DictationState.IDLE) return false
        state = DictationState.STARTING
        sessionId += 1
        runId = 0L
        pcm = ShortArray(0)
        totalSamples = 0
        lastErrorCode = null
        failedThisSession = false // F13b: the error applies per session, never carries over
        droppedBlocks = 0
        hypothesesThisSession = 0 // F13b2: counters per session
        lastHypAt = 0L
        lastHypothesisBufferSeconds = 0f // F26b: fresh per session
        ownershipDeniedCount = 0 // AP3: ownership forensics per session
        ownershipDeniedAtS = -1.0
        // A2b: write diagnostics nulled per session (monotonic afterwards)
        writeSameCount = 0
        writeW1Count = 0
        writeW3dCount = 0
        writeDeltaCount = 0
        lastWriteAudioS = -1.0 // -1 before the first DELTA
        writeReason = "-"
        hypLen = 0
        currentLen = 0
        committedLen = 0
        resetDecodeStats() // F17b: decode diagnostics per session (same pattern as above)
        policy.reset()
        typer.reset()
        tracker.reset()
        sink.resetSession() // A5 (v0.1.34): discard the ownSpan leftover of the previous session
        // B1: segment seam fresh per session — the whole session = ONE whole_buffer,
        // if the segmenter stays off (segmentFinalizeMs=0, existing behavior).
        segmentPrefix = ""
        segmentBaseSamples = 0L
        lastHypPunct = false
        trailingSpaceCommitted = false // T10: trail_sp flag reset per session
        state = DictationState.RECORDING
        return true
    }

    /**
     * New audio block from the capture queue (KONZEPT §2.3: decoupled).
     * Ignored outside of RECORDING. Once the buffer reaches the minimum length
     * for partials (2.0 s), it is transcribed and the result is written
     * into the editor via CommitPolicy + DeltaTyper.
     */
    suspend fun feedAudio(block: PcmBlock) {
        if (state != DictationState.RECORDING) {
            // F13b/C1: after a silent fail() EVERY block was silently
            // dropped here — now counted (F13b2 diagnostics: dropped_blocks).
            droppedBlocks += 1
            return
        }
        if (block.samples.isEmpty()) return
        append(block.samples)
        // ASR_CONTRACT §4.4: min_audio_s is a guard ONLY for partials —
        // short audio levels are not decoded (hallucination-prone).
        if (audioSeconds >= minAudioSecondsForPartials) {
            // F21: cost-aware pacing ONLY for partials — the final (stop) always runs.
            if (decodePacing && !paceAllowsDecode()) {
                decodeSkipsCount += 1 // no error, no fail(): just a skipped run
                return
            }
            try {
                val decodeStartNs = clock() // F17b/C2: decode time with the injected clock
                val hypothesis = engine.transcribe(window(), sessionId, ++runId)
                noteDecode(decodeStartNs) // F17b: diagnostic numbers only, no behavior change
                noteHypothesis() // F13b2: count generated hypothesis + timestamp
                applyPartial(hypothesis)
            } catch (e: OutOfMemoryError) {
                // F3: native ORT memory error during inference — end the session
                // in a controlled way (abort semantics), report the error UPWARD
                // (no swallowing catch), so that the IM can show
                // dictation_error_out_of_memory. ONLY OOM; other Errors deliberately
                // remain uncaught.
                abort()
                throw e
            } catch (e: IllegalStateException) {
                if (isEditorSyncTimeout(e)) {
                    // F1 drift rule (REVIEW_STAGE6_FIXES): edit timeout in the sink ⇒
                    // the main thread is blocked/overloaded — further revisions
                    // would run on the same drift vector (stale editor
                    // states, ghost-write risk). End the session, surface the
                    // error upward (R-2). F13d/P1: fail() instead of abort() —
                    // failedThisSession preserves the reason until the next start
                    // (otherwise stop() reports a silent IdleNoOp).
                    fail(EDITOR_SYNC_TIMEOUT)
                    throw e
                }
                fail(ASR_PARTIAL_ERROR) // other IllegalStateException: normal error path
            } catch (e: Exception) {
                fail(ASR_PARTIAL_ERROR)
            }
        }
    }

    /**
     * Applies a partial hypothesis (can also be called from the external scheduler).
     * Discards results that do not match the CURRENT session and the state
     * RECORDING — this way late results of an old session can no longer
     * insert any text.
     *
     * @return true if the hypothesis was assigned to the current session.
     */
    fun applyPartial(hypothesis: Hypothesis): Boolean {
        if (state != DictationState.RECORDING) return false
        if (hypothesis.sessionId != sessionId) return false
        return writeHypothesis(hypothesis)
    }

    /**
     * Key released: final decode of the ENTIRE buffer (whole-buffer,
     * KONZEPT §5.1) and transition to IDLE. Idempotent: without a running recording
     * a no-op. Even the shortest recordings are decoded finally (the min_audio guard
     * does not apply to the final).
     *
     * F13b/C1: returns [StopOutcome] — the caller sees WHETHER a final
     * ran; a session that failed silently (fail() during RECORDING)
     * yields NoFinal WITH error code instead of a normal completion.
     */
    suspend fun stop(): StopOutcome {
        if (state != DictationState.RECORDING) {
            // Never-recorded (IDLE): no-op without a fake error — BUT: the session
            // may have failed silently (fail() set state to IDLE): report NoFinal.
            // F13d/P2-1: DROPPED value into the snapshot — reading live would be racy.
            return if (failedThisSession) StopOutcome.NoFinal(lastErrorCode, failedWith = true, droppedWith = droppedBlocks)
            else StopOutcome.IdleNoOp
        }
        // F13d/P2-1: snapshot at STOP time — an immediately following start()
        // can no longer falsify the evaluation of this session.
        val failedAtStop = failedThisSession
        val droppedAtStop = droppedBlocks
        state = DictationState.FINALIZING
        var finalError: String? = null
        if (totalSamples > 0) {
            try {
                val decodeStartNs = clock() // F17b/C2: decode time with the injected clock
                val hypothesis = engine.transcribe(window(), sessionId, ++runId)
                noteDecode(decodeStartNs) // F17b: diagnostic numbers only, no behavior change
                noteHypothesis() // F13b2: the final is also a generated hypothesis
                // Also discard finals of old sessions (stale run)
                if (hypothesis.sessionId == sessionId) {
                    // A2b: final via reviseFinal — ownership-secured
                    // FULL revision of our own span (no W3d block).
                    writeHypothesis(hypothesis, final = true)
                }
            } catch (e: OutOfMemoryError) {
                // F3: OOM also in the final decode — abort + upward (see feedAudio)
                abort()
                throw e
            } catch (e: IllegalStateException) {
                if (isEditorSyncTimeout(e)) {
                    // F1 drift rule: edit timeout ⇒ no further write attempts.
                    // F13d/P1: fail() instead of abort() — reason preserved, never silent.
                    fail(EDITOR_SYNC_TIMEOUT)
                    throw e
                }
                fail(ASR_FINAL_ERROR)
                finalError = ASR_FINAL_ERROR
            } catch (e: Exception) {
                fail(ASR_FINAL_ERROR)
                finalError = ASR_FINAL_ERROR
            }
        } else {
            // T10 (v0.1.39): session end WITHOUT a rest final — empty rest
            // buffer (e.g. right after an intermediate-segment finalization, F53):
            // the session final still appends the space to the already
            // committed text (ownership-guarded like every revision; the
            // intermediate-segment finalization ITSELF stays without a space).
            appendTrailingSpaceAtSessionEnd()
        }
        if (state == DictationState.FINALIZING) state = DictationState.IDLE
        return StopOutcome.FinalRan(
            finalError,
            failedWith = failedAtStop,
            droppedWith = droppedAtStop,
            trailingSpace = trailingSpaceCommitted, // T10: trail_sp snapshot in the final
        )
    }

    /**
     * T10 (v0.1.39): space append at SESSION end without a rest final
     * (empty rest buffer after seg_final) — same pure rule
     * ([TrailingSpace.rule]), same ownership gate and same typer bookkeeping
     * as the final writeHypothesis; sets [trailingSpaceCommitted].
     */
    private fun appendTrailingSpaceAtSessionEnd() {
        val text = typer.currentText
        if (!TrailingSpace.rule(text, text.lastOrNull())) return // empty session: nothing
        if (!tracker.isOwned(typer.currentText, contextForOwnText())) return // not our span: no write
        val outcome = typer.reviseFinal(text + " ")
        noteWrite(outcome.kind, text + " ")
        trailingSpaceCommitted = true
    }

    /**
     * Abort without a final decode: orderly transition to IDLE (DESIGN §2.4 CANCELLING);
     * text already committed stays in place.
     */
    fun abort() {
        if (state == DictationState.IDLE) return
        lastErrorCode = "aborted"
        state = DictationState.IDLE
    }

    /** Selection signal from the UI thread (InputMethodService.onUpdateSelection). */
    fun onUpdateSelection(oldSelStart: Int, oldSelEnd: Int, newSelStart: Int, newSelEnd: Int) {
        tracker.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd)
    }

    /**
     * Editor/app switch (A-1): aborts a running recording in a controlled way —
     * NO final commit into the new editor, no further partial writes.
     * Ownership is discarded (new generation), state → IDLE; text already
     * committed stays in the OLD editor.
     */
    fun onEditorChanged() {
        tracker.onEditorChanged()
        if (state == DictationState.RECORDING || state == DictationState.FINALIZING) {
            abort()
        }
    }

    /** Editor context for the expected own text (ownership check). */
    private fun contextForOwnText(): CharSequence? =
        if (typer.currentText.isEmpty()) null else sink.textBeforeCursor(typer.currentText.length)

    /**
     * F1 drift rule: recognizes the edit timeout of InputConnectionSink by its
     * fixed message — only THIS error is a drift abort, every other
     * IllegalStateException stays on the normal fail() path.
     */
    private fun isEditorSyncTimeout(e: IllegalStateException): Boolean =
        e.message?.startsWith(EDITOR_SYNC_TIMEOUT_PREFIX) == true

    /** F13b2: counter + timestamp of a generated hypothesis (metadata only). */
    private fun noteHypothesis() {
        hypothesesThisSession += 1
        lastHypAt = clock()
        // F26b: buffer level of the LAST generated hypothesis (last decode value).
        lastHypothesisBufferSeconds = lastDecodeBufferSeconds.toFloat()
    }

    /** Orderly error path: to IDLE, error code visible, text stays in place. */
    private fun fail(errorCode: String) {
        lastErrorCode = errorCode
        failedThisSession = true // F13b: preserved until a new start follows
        state = DictationState.IDLE
        policy.reset()
    }

    /**
     * Writes a hypothesis (assigned to the current session): commit progress
     * via the policy, then minimal delta via the typer. Without proven
     * ownership nothing is written (DESIGN §6).
     * B1: The segment decode yields only the ACTIVE segment — [withSegmentPrefix]
     *  reconstructs the full session text from it (prefix of the completed
     *  segments + segment text, seam = one space); policy/agreement
     *  receive the SESSION audio length ([segmentBaseSamples] + buffer). Without
     *  segment boundaries both are identical to the existing behavior.
     *
     * A2b (PLAN_V0133 §A2b): the production path is [DeltaTyper.reviseOutcome]
     * (reason return {SAME, W1, W3d, DELTA}); [final]=true (session final)
     * uses [DeltaTyper.reviseFinal] — ownership-secured full revision.
     * W1 re-baseline in exactly the E1c coordinates (length max(currentText,
     * sink.ownText()), actual=sink.textBeforeCursor(len), fresherExpected=
     * sink.ownText()); ONLY after a positive suffix proof may the same
     * hypothesis be retried ONCE. Counters/diagnostics per window
     * via [noteWrite] (rule 9: numbers/codes).
     */
    private fun writeHypothesis(hypothesis: Hypothesis, final: Boolean = false): Boolean {
        // B1: punctuation ending of the last hypothesis (input of the soft
        //  segment rule a) — a boolean only, never the text (rule 9).
        val lastChar = hypothesis.text.trimEnd().lastOrNull()
        lastHypPunct = lastChar == '.' || lastChar == '!' || lastChar == '?'
        val fullText = withSegmentPrefix(hypothesis.text)
        if (!tracker.isOwned(typer.currentText, contextForOwnText())) {
            // AP3 (PLAN_C2_FABLE_R2 §4, H-E2): denied write intent — the
            // second ownership-loss detector besides the selection flip in
            // the coordinator. First moment in session audio seconds (-1 = never),
            // numbers only (rule 9). Counter for persistence (silent write stop).
            // AP3-P2 (review): BEFORE the first OUTPUT, isOwned=false is NORMAL
            // (baseline not yet established; expectedText empty ⇒ only lost can
            // deny) — the detector only counts once the session already has
            // output in the editor: hadOutput guard = typer.currentText
            // NOT empty (the first partial suffices — NOT committedLength:
            // an agreement commit needs 3 stable hypotheses, and exactly the
            // "partial present, then silent write stop WITHOUT ever an agreement
            // commit" case (H-E2) would remain invisible with the stricter guard;
            // the review intention is the partial write stop). currentText
            // resets per session (typer.reset() in start()). Detector 1
            // (selection flip in the coordinator) remains untouched.
            if (typer.currentText.isNotEmpty()) {
                // A2b (E1c): ONE re-baseline attempt with the FRESH sink state
                // (ownText is in sync with the commit round, the tracker expectation
                // lags behind on the worker side). The tracker clears lost ONLY on
                // a positive suffix proof; after that the same hypothesis ONCE
                // again — otherwise W1 without sink access (lost remains).
                val len = maxOf(typer.currentText.length, sink.ownText().length)
                val rebased = tracker.rebaseline(sink.textBeforeCursor(len), sink.ownText())
                if (!rebased || !tracker.isOwned(typer.currentText, contextForOwnText())) {
                    // P2 (REVIEW_A2_A4): ONLY the REAL denial counts — a
                    // SUCCESSFUL re-baseline is not an ownership loss (counter and
                    // stamp were previously taken BEFORE the attempt).
                    ownershipDeniedCount += 1
                    if (ownershipDeniedAtS < 0.0) {
                        ownershipDeniedAtS = sessionAudioSeconds
                    }
                    noteWrite(ReviseKind.W1, fullText)
                    return false
                }
            } else {
                noteWrite(ReviseKind.W1, fullText)
                return false
            }
        }
        val committed = policy.onHypothesis(
            hypothesisText = fullText,
            audioSamples = segmentBaseSamples + totalSamples,
            currentCommitted = typer.committedLength,
            budgetExceeded = hypothesis.incomplete,
        )
        typer.commitUpTo(committed)
        val committedBefore = typer.committedLength
        // T10 (v0.1.39): ONLY the real session final (final=true) appends ONE
        // space after punctuation at the end (pure decision
        // [TrailingSpace.rule], double guard there). Intermediate-segment finals
        // (F53: finalizeSegment calls writeHypothesis WITHOUT final) and the
        // abort path (no writeHypothesis at all) remain untouched. The space
        // is PART of the reviseFinal target ⇒ ownership/tracker chain stays
        // consistent — NO separate commitText outside the typer bookkeeping.
        val finalTarget = if (final && TrailingSpace.rule(fullText, fullText.lastOrNull())) {
            fullText + " "
        } else {
            fullText
        }
        if (final) trailingSpaceCommitted = finalTarget.length > fullText.length
        val outcome = if (final) typer.reviseFinal(finalTarget) else typer.reviseOutcome(fullText)
        noteWrite(outcome.kind, fullText)
        // E8.2 C6: after a rollback (committedLength SHRUNK, no
        // divergence denial) the agreement counting starts NEW — no
        // immediate re-commit of the same (faulty) prefix. Applies to
        // W3c as well as to the A2a/A2b full revision (W3d resolution, final).
        if (outcome.kind == ReviseKind.DELTA && typer.committedLength < committedBefore) {
            policy.reset()
        }
        if (outcome.kind == ReviseKind.DELTA) {
            tracker.onOwnEdit(typer.currentText)
        }
        return true
    }

    /**
     * A2b: write counters/diagnostics of ONE window (rule 9: numbers/codes) +
     * immutable [DecodeWindowStats] snapshot to the observer.
     */
    private fun noteWrite(kind: ReviseKind, fullText: String) {
        when (kind) {
            ReviseKind.SAME -> {
                writeSameCount += 1
                writeReason = "same"
            }
            ReviseKind.W1 -> {
                writeW1Count += 1
                writeReason = "w1"
            }
            ReviseKind.W3d -> {
                writeW3dCount += 1
                writeReason = "w3d"
            }
            ReviseKind.DELTA -> {
                writeDeltaCount += 1
                writeReason = "delta"
                lastWriteAudioS = sessionAudioSeconds
            }
        }
        hypLen = fullText.length
        currentLen = typer.currentText.length
        committedLen = typer.committedLength
        onDecodeWindow?.invoke(
            DecodeWindowStats(
                audioS = sessionAudioSeconds,
                decodeMs = lastDecodeMs,
                paceMs = decodePaceMs(),
                skips = decodeSkipsCount,
                hypLen = hypLen,
                currentLen = currentLen,
                committedLen = committedLen,
                write = writeReason,
                hypGapMs = currentHypGapMs(),
                decodeMsMax = decodeMsMax(),
                decodeMsP95 = decodeMsP95(),
                bufferS = lastDecodeBufferSeconds,
            ),
        )
    }

    private fun append(samples: ShortArray) {
        if (pcm.size < totalSamples + samples.size) {
            pcm = pcm.copyOf(maxOf(totalSamples + samples.size, pcm.size * 2))
        }
        System.arraycopy(samples, 0, pcm, totalSamples, samples.size)
        totalSamples += samples.size
    }

    /**
     * B1: Reconstructs the full session text: prefix of the completed
     * segments + segment text, seam = EXACTLY ONE space (at the segment
     * boundary the decoder does not know the word boundary — a space is the
     * conservative, never-sticking choice; golden-testability is preserved).
     * Without a prefix (segmenter off/first segment) the hypothesis text stays unchanged.
     */
    private fun withSegmentPrefix(text: String): String {
        if (segmentPrefix.isEmpty()) return text
        val tail = text.trimStart()
        if (tail.isEmpty()) return segmentPrefix
        val head = segmentPrefix.trimEnd()
        return if (head.isEmpty()) tail else "$head $tail"
    }

    /**
     * B1 (PLAN_V0130 §6.3 recommendation (c)): INTER-SEGMENT FINALIZATION —
     * mini-final over the ACTIVE segment buffer: final decode (whole_buffer
     * over the segment excerpt) + PERMANENT commit via the established path
     * ([writeHypothesis] with agreement/policy/typer — the same contract as the
     * session final), afterwards reset ONLY buffer + decode statistics; the
     * session stays RECORDING (no audio gap: called in the consumer, the
     * sole buffer owner; blocks queue in the channel). Hypothesis pacing,
     * agreement and the min_audio guard count from the segment start (fresh buffer).
     *
     * B1 fix (review P1, PLAN §6.3(c) product contract): ERROR BEHAVIOR — only
     * OOM remains a HARD abort (abort + rethrow → ERR_OOM existing path,
     * no further decode state possible); EVERY other exception (ASR error,
     * editor sync timeout) is TRANSIENT: no fail(), no lastError — the
     * caller discards request + pause guard and the session keeps running RECORDING;
     * the NEXT boundary attempt tries again. fail() would here be
     * an abort path in the middle of dictation (exactly what the contract excludes); a
     * truly broken editor ends via the next PARTIAL decode through its
     * own fail() path. Idempotent: without RECORDING or without samples in
     * the buffer, false without side effects.
     *
     * @return true if a segment was finalized (the buffer is empty afterwards).
     */
    suspend fun finalizeSegment(): Boolean {
        if (state != DictationState.RECORDING) return false
        if (totalSamples <= 0) return false
        try {
            val decodeStartNs = clock() // F17b/C2: decode time with the injected clock
            val hypothesis = engine.transcribe(window(), sessionId, ++runId)
            noteDecode(decodeStartNs) // diagnostic numbers only
            noteHypothesis() // F13b2: a mini-final is also a hypothesis
            // Also discard mini-finals of old sessions (stale run)
            if (hypothesis.sessionId == sessionId) {
                writeHypothesis(hypothesis)
            }
        } catch (e: OutOfMemoryError) {
            // OOM remains the ONLY hard abort (B1 fix, review P1): without
            // memory no further decode state is possible — established path
            // (consumer catches, ERR_OOM, controlled end).
            abort()
            throw e
        } catch (e: IllegalStateException) {
            // B1 fix (review P1, PLAN §6.3(c) product contract "no abort due to
            // decode latency/memory alone, no audio gap"): a transient
            // ASR/editor error in the mini-final does NOT end the SESSION —
            // fail() would be an abort path in the middle of dictation. The segment request
            // is discarded (caller clears request + guard), the
            // recording continues, the NEXT boundary attempt (next
            // sentence pause / length rule) tries again. A truly broken
            // editor is ended by the NEXT partial decode via its own
            // fail() path — the segment final is not a timer.
            return false
        } catch (e: Exception) {
            // same: transient ASR error — discard, session lives (P1).
            return false
        }
        // Carry the seam forward: the new prefix is the committed state INCLUDING
        // the mini-final; afterwards reset buffer + decode statistics deliberately
        // (session counters like hyp_count keep their session values).
        segmentPrefix = typer.currentText
        segmentBaseSamples += totalSamples
        totalSamples = 0
        resetDecodeStats()
        return true
    }

    /** B1: does the last hypothesis end with punctuation — input of the soft
     *  segment rule (a). Boolean only, never text (rule 9). */
    fun lastHypothesisEndsWithPunct(): Boolean = lastHypPunct

    /** T10 (v0.1.39): did the SESSION final (only final=true) append a space
     *  after punctuation at the end? Per session, lands in the StopOutcome
     *  snapshot (VK-SESS trail_sp=0|1, rule 9: only the number). */
    @Volatile var trailingSpaceCommitted: Boolean = false
        private set

    private fun window() = PcmWindow(pcm.copyOf(totalSamples), sampleRateHz)

    companion object {
        /**
         * ASR_CONTRACT.md §4.4: minimum audio length for partials.
         * V12: from the ONE config source (AsrThreadConfig, S12b lever 2) instead of
         * its own constant — default 2000 ms ⇒ 2.0 s (behavior identical).
         */
        val MIN_AUDIO_SECONDS_PARTIALS: Double =
            de.voicekeyboard.asr.AsrThreadConfig.DEFAULT.hypothesisMinIntervalMs / 1000.0
        /** F21: pacing clamps from the ONE config source (AsrThreadConfig, lever 2). */
        val PACE_BASE_MS: Long = de.voicekeyboard.asr.AsrThreadConfig.DEFAULT.decodePaceBaseMs
        val PACE_MAX_MS: Long = de.voicekeyboard.asr.AsrThreadConfig.DEFAULT.decodePaceMaxMs
        private const val EDITOR_SYNC_TIMEOUT = "editor_sync_timeout"
        /** F17b/C2: cap of the decode sample ring (never grows beyond it). */
        private const val DECODE_SAMPLE_CAP = 64
        private const val ASR_PARTIAL_ERROR = "asr_partial_error"
        private const val ASR_FINAL_ERROR = "asr_final_error"
        private const val EDITOR_SYNC_TIMEOUT_PREFIX = "Editor-Sync Timeout"
    }
}
