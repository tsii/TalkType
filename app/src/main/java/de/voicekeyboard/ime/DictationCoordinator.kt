package de.voicekeyboard.ime

import de.voicekeyboard.asr.AsrEngine
import de.voicekeyboard.asr.AsrThreadConfig
import de.voicekeyboard.audio.AudioErrorListener
import de.voicekeyboard.audio.AudioSource
import de.voicekeyboard.audio.PcmBlock
import de.voicekeyboard.dictation.DictationController
import de.voicekeyboard.dictation.DictationState
import de.voicekeyboard.editor.EditorSink
import de.voicekeyboard.editor.OwnershipTracker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Framework-poor dictation coordinator (M4c-1): connects AudioSource (capture
 * thread, blocking read), DictationController (state machine + ASR + commit) and
 * route/error display into a switchable operation. The IME wiring
 * (VoiceKeyboardService) is a separate follow-up task (M4c-2).
 *
 * Threading (DESIGN §2.3):
 *  - Capture thread: blocks only in [AudioSource.read] and puts blocks into
 *    an unbounded channel — it never blocks on the controller.
 *  - Consumer coroutine in the injected [scope]: aggregates blocks and passes them
 *    IN ORDER to [DictationController.feedAudio].
 *  - Re-transcription tick ~1 s, but ONLY if enough new audio has arrived since
 *    the last run (no decode of the same bytes); partials are additionally
 *    subject to the 2-s guard in the controller, the finale at [stop] is not
 *    (ASR_CONTRACT §4.4).
 *
 * Android types (AudioRouter, notification) do not leak in — router
 * start/stop and route display are lambdas that the IME wires in M4c-2.
 * Logs: only durations/error codes, tag "VK-DICT"; never audio or text.
 */
class DictationCoordinator(
    private val scope: CoroutineScope,
    private val audioSourceFactory: () -> AudioSource,
    private val engineFactory: () -> AsrEngine,
    private val sink: EditorSink,
    private val tracker: OwnershipTracker = OwnershipTracker(),
    /** S12b lever 2: tick from the ASR config (chunkMs), no longer hard-coded.
     *  F21: default chunkMs=2000 ⇒ 32_000 samples @ 16 kHz — halved tick because
     *  of the quadratic re-decode of the whole buffer (user approval 2026-10-30). */
    private val tickSamples: Int = (AsrThreadConfig.DEFAULT.chunkMs * SAMPLE_RATE_HZ / 1000),
    /** Android-side observation of the audio route; null = no route UI. */
    private val routeObserver: ((on: Boolean) -> Unit)? = null,
    /** Model directory; non-null ⇒ [AsrEngine.load] BEFORE the first decode (C-4). */
    private val modelDir: java.io.File? = null,
    /** Completion callback after the non-blocking finale (C-1). */
    private val onStopped: (() -> Unit)? = null,
    /** N-2: dispatcher on which [onStopped] runs (view mutation ⇒ main). */
    private val mainDispatcher: kotlinx.coroutines.CoroutineDispatcher? = null,
    /**
     * F49 (PLAN_V0130 §1 A1): the hold gesture signals "not done yet" — the
     * F43 auto-stop fires only if this gate is OPEN (toggle mode; E1:
     * TRAILING_SILENCE_MS stays 2.5 s). true = suppressed. Silence≠stall
     * contract (F28b1) and the ERR_SILENT-/ERR_STALL watchdogs remain untouched —
     * the gate brackets ONLY the F43 branch. Default false = behavior as before
     * (all existing tests unchanged).
     */
    private val autoStopSuppressed: () -> Boolean = { false },

    /**
     * B1 (PLAN_V0130 §6.3 recommendation (c), I3 curve α=38.5 ms/s): hard
     * segment cap in ms — 0 = segmenter OFF (default: all existing tests
     * unchanged). 35 000 ms ⇒ decode(segment) ≈ 1.35 s at 38.5 ms/s
     * (I3; superlinear from 60 s — hence < 60 s chosen).
     */
    private val segmentFinalizeMs: Long = 0,

    /** F28b1: monotonic clock (ms) for the stall watchdog — injectable for
     *  deterministic unit tests; production: nanoTime (no wall-clock jump,
     *  no deep sleep). */
    private val nowMs: () -> Long = { System.nanoTime() / 1_000_000L },
) {

    companion object {
        const val TAG = "VK-DICT"
        const val TICK_SAMPLES_1S = 16_000
        private const val SAMPLE_RATE_HZ = 16_000

        const val READ_TIMEOUT_MS = 50
        const val ERR_AUDIO = "audioSource"
        const val ERR_OOM = "oom"
        /** F28b1: error code of the stall watchdog (source delivers 0 samples persistently). */
        const val ERR_STALL = "stall"
        /** F28b1: abort if no block (n>0) arrives for longer than this (ms). */
        const val STALL_LIMIT_MS = 3000L
        /**
         * F31/F31b: error code when blocks keep running but the session was NEVER
         * loud (user case: the mic delivers only −56 dBFS noise from the start →
         * dictation ran, text never came, no hint). F31b (review P1): ONLY in this
         * form — after the first loud block the silence detection is disabled
         * (natural speech pauses > 3 s must not abort a good session; missing
         * blocks are still covered by [ERR_STALL]).
         */
        const val ERR_SILENT = "silence"
        /**
         * F31/F31b: abort if the session has not seen a single loud block after
         * start + this duration (but blocks keep running).
         */
        const val SILENT_START_LIMIT_MS = 3000L
        /**
         * F43 (F41 repro /tmp/vk_repro_f41.txt, symptom 1 — the user explicitly
         * expects this): silence AFTER the first loud block ([everLoud]) ends
         * the recording AUTOMATICALLY via the REGULAR stop() path (final decode
         * over the whole buffer, no ERR_* — same place as the user stop).
         * Before speech starts the [SILENT_START_LIMIT_MS] start window
         * (ERR_SILENT) stays unchanged; shorter pauses never interrupt.
         */
        const val TRAILING_SILENCE_MS = 2500L

        /** B1: silence gate of segment rule (a) — analogous to the F43 c-gate: only
         *  STREAMING silence is a sentence pause; a dead source stays with the
         *  ERR_STALL contract (silence ≠ stall, F28b1). */
        const val SEGMENT_STALL_GATE_MS = 2500L

        /** B1: minimum sentence pause (ms) for the soft segment boundary (a) — shorter
         *  than the F43 auto-stop (1200 < 2500): a sentence end may close the window
         *  before the auto-stop ends the whole session. */
        const val SEGMENT_PAUSE_MS = 1200L
        /**
         * F31: block RMS threshold "loud vs. silent" in dBFS (linear RMS value =
         * 10^(dBFS/20)). −40 dBFS ≈ 0.01: the user's measured mic noise
         * (−56 dBFS) lies 16 dB BELOW (factor ≈ 6) → safely "silent";
         * normal speech (block RMS typically −30…−20 dBFS) lies 10–20 dB ABOVE
         * (factor 3–10) → safely "loud". A single silent block does not
         * count — only the duration over [STALL_LIMIT_MS] without a loud block.
         */
        const val SILENCE_RMS_DBFS: Float = -40f
        /** Linear RMS threshold = 10^(−40/20) = exactly 0.01 (given as a constant,
         *  no runtime computation in the companion). */
        const val SILENCE_RMS: Float = 0.01f
    }

    // ---- S12b diagnostics (rule 9: only numbers, never audio/text/paths) ----

    /** Time from start to the first (partial) decode, ms; 0 = none yet. */
    @Volatile var firstHypothesisLatencyMs: Long = 0
        private set

    /** Accumulated processing time of the decodes, ms. */
    @Volatile var processingMs: Long = 0
        private set

    /** Processed audio duration (from samples), ms. */
    @Volatile var audioMs: Long = 0
        private set

    /**
     * Highest audio level seen this session. Rule 9: only a maximum value
     * lives — no samples, no curve. Serves solely the question "is any sound
     * arriving at all?" (diagnostics line `Peak=`).
     */
    val levelPeak = de.voicekeyboard.debug.LevelPeak()

    /** Reported by the audio path (linear 0..1 per block). */
    /**
     * Level for diagnostics. Discarded after `release()` (lines 357ff): the
     * audio thread can still report blocks after closing, and those must not
     * falsify the maximum level of the NEXT session.
     */
    fun noteLevel(rms: Float) {
        if (!closed.get()) levelPeak.note(rms)
    }

    private var startedAtMs = 0L

    /** Diagnostics short form (debug builds): erst=…s RTF=… T=… Peak=… (dB) — numbers only. */
    fun diagnosticsLine(threads: Int): String {
        val erst = if (firstHypothesisLatencyMs > 0) "%.1f".format(firstHypothesisLatencyMs / 1000.0) else "-"
        val rtf = if (audioMs > 0) "%.2f".format(processingMs.toDouble() / audioMs.toDouble()) else "-"
        return "Diagnostics: erst=${erst}s RTF=${rtf} T=$threads Peak=${levelPeak.short()}"
    }

    /** Visible route for the UI (set by the IME from the AudioRouter). */
    @Volatile var route: String = "builtin"
        private set

    /** Last error code (never text content); null in the normal case. */
    @Volatile var lastError: String? = null
        private set

    val state: DictationState
        get() = controller.state

    /**
     * C-2: ALL error channels united — audio errors (errorListener) AND
     * controller/ASR errors (controller.lastErrorCode) — so the status row
     * swallows nothing. Never text content, only codes.
     */
    val visibleError: String?
        get() = lastError ?: controller.lastErrorCode

    private val engine: AsrEngine = engineFactory()
    // F21: cost-aware partial pacing on (C2 mitigation); finale/stop remain untouched.
    private val controller =
        DictationController(engine, sink, tracker = tracker, decodePacing = true)
    private var source: AudioSource? = null
    /** W2 (vc34, debugger recommendation): true until shutdown() — the engine stays
     *  RESIDENT (deliberately NO idle unload after session end; the next
     *  dictation starts warm, 🐞 warm_hit=1). W1: source for warm_hit at start. */
    @Volatile private var engineLoaded = false
    private val closed = java.util.concurrent.atomic.AtomicBoolean(false)

    private val active = AtomicBoolean(false)
    private val seqCounter = AtomicLong(0)
    private var channel: Channel<PcmBlock>? = null
    private var consumer: kotlinx.coroutines.Job? = null
    private var captureThread: Thread? = null

    /**
     * Error channel of the AudioSource (capture/init/system errors): controlled
     * ending (abort path), editor stays unchanged. Public so the
     * AudioSource implementation (and tests) can wire it.
     */
    val errorListener = AudioErrorListener { code, detail ->
        lastError = "$code:$detail"
        // F26a: audio system error (C2 export path E5) — record the reason.
        abort("audio_error:$code")
    }

    /**
     * P0 finding 2 (2026-11-01): observer for [abort] (any reason: audio error,
     * michold_cancel, start_abandoned, ime_destroy …). The callback runs on
     * the main dispatcher (like [onStopped]), so the service does its UI sync
     * there. Default null = behavior as before.
     */
    var onAborted: ((reason: String) -> Unit)? = null

    /**
     * Starts the dictation operation (audio + controller + route observation).
     * Idempotent: only from IDLE; running → false without a restart.
     */
    fun start(): Boolean {
        // F13b2: start duration (🐞: start_ms) and watchdog indicator (🐞: starting)
        // — incl. engine.load(); NEVER uptimeMillis (deep sleep, review F13c P2).
        starting = true
        val t0 = System.nanoTime()
        try {
        if (!active.compareAndSet(false, true)) return false
        if (controller.state != DictationState.IDLE) {
            active.set(false)
            return false
        }
        lastError = null
        lastAbortReason = "-" // F26a: every start begins with an open exit
        // S12b: reset the diagnostics counters per recording
        firstHypothesisLatencyMs = 0
        processingMs = 0
        audioMs = 0
        levelPeak.reset() // start the level fresh per recording (diagnostics)
        startedAtMs = System.currentTimeMillis()
        // F28b1: reset the stall measurement per recording; reference = start time
        // (0 blocks ⇒ stall counts from start, not from infinity).
        capBlocks = 0
        lastBlockAtMs = nowMs()
        // F42: first measurement fresh per recording — reference = start time on the
        // watchdog clock (monotonic, not wall clock).
        startRefMs = nowMs()
        firstLoudMs = -1L
        first3sLoudBlocks = 0
        first3sPeakRms = 0.0
        // F31: reset the silence clock per recording (reference = start time, like the
        // stall clock — 0 loud blocks ⇒ silence counts from start, not from infinity).
        lastLoudAtMs = nowMs()
        loudMs = 0
        everLoud = false // F31b: silence detection freshly active per recording
        // F28b2: session-end diagnostics fresh per recording (export: who ended,
        // what the status row showed — never events of the PREVIOUS session).
        micRelValue = ""
        statusShownValue = "ready"
        // B1: segmenter fresh per recording — acknowledge a pending boundary,
        // the active segment starts NOW, mini-finale counter to 0 (session counter).
        segmentBoundaryRequested = false
        segmentStartedAtAudioMs = 0L
        segmentFinalizations = 0
        segmentPauseCutAtLoudMs = -1L
        // AP3 (export v2 part 1, PLAN_C2_FABLE_R2 §4): session forensics fresh —
        // start_src derived via the F49 gate (hold = finger down at
        // start ⇒ gate closed ⇒ hold; toggle start ⇒ gate open ⇒ toggle; the
        // asynchronous start coroutine makes a toggle-UP before start() invisible
        // — deterministic enough for diagnostic purposes, KDoc).
        startSrcValue = if (autoStopSuppressed()) "hold" else "toggle"
        gateAtStopValue = -1 // no stop yet
        explicitStopCauseValue = "-"
        maxPauseMsValue = 0L
        ownerLostAtAudioMsValue = -1L
        selUnexpectedValue = 0
        restartInputsValue = 0
        // F50 (PLAN_V0130 A2, V4): start_ms fresh per recording — preparation for the
        // write-only-when-0 guard in the finally (the FIRST start of a recording
        // wins; a colliding run does not overwrite).
        lastStartMs = 0L
        // C-2/C-4: load the engine BEFORE the first decode (exactly once); a
        // failure is visible (lastError) and ends the start in a controlled way.
        // W1 (vc34): measure warm_hit (start found the engine already resident —
        // show preload hit) and load via the single-flight gate: preload and
        // start share ONE gate, parallel load() is impossible. The
        // lazy fallback stays untouched — a failed preload does NOT block
        // this start.
        warmHit = engineLoaded
        if (modelDir != null && !engineLoaded) {
            try {
                loadEngineOnce("mic")
            } catch (e: Exception) {
                lastError = "model:" + (e.message?.take(80) ?: e.javaClass.simpleName)
                active.set(false)
                return false // no microphone, no FGS state in the coordinator
            }
        }
        val src = try {
            audioSourceFactory()
        } catch (e: Exception) {
            // C-5: never propagate an audio-init throw through the touch listener
            lastError = "audioInit:" + e.javaClass.simpleName
            active.set(false)
            return false
        }
        source = src
        val ch = Channel<PcmBlock>(Channel.UNLIMITED)
        channel = ch
        if (!src.start(seqCounter.get())) {
            active.set(false)
            source = null
            channel = null
            lastError = "startFailed"
            return false
        }
        routeObserver?.invoke(true)
        // A1 (PLAN_V0133 §1): watchdog baselines at the REAL capture start —
        // engine.load() (cold start, up to ~4.5 s) must not make the stall/silence
        // clocks run ahead: otherwise the first empty poll after a long load time
        // immediately triggered ERR_STALL/ERR_SILENT. ONE measurement, three
        // clocks together; start-duration diagnostics (lastStartMs) still measures
        // from start() incl. engine.load(). No threshold change.
        val captureStartedAtMs = nowMs()
        lastBlockAtMs = captureStartedAtMs
        startRefMs = captureStartedAtMs
        lastLoudAtMs = captureStartedAtMs
        consumer = scope.launch { consume(ch) }
        captureThread = Thread({ captureLoop(src, ch) }, "vk-dict-capture").apply {
            isDaemon = true
            start()
        }
        return controller.start()
        } finally {
            starting = false
            // F50 (V4): a colliding run does NOT overwrite — only the first start
            // of a recording writes start_ms (export clarity).
            if (lastStartMs == 0L) {
                lastStartMs = (System.nanoTime() - t0) / 1_000_000
            }
        }
    }

    /**
     * F13d/P1: is the capture/session still running? After a consumer death
     * active=false is set — the capture thread then produces NO samples
     * anymore (capture-loop while condition), the channel is closed.
     */
    val isActive: Boolean get() = active.get()

    /** F13b2: 0/1 — a coordinator start is running right now (engine.load etc.);
     *  the watchdog case is 1 with an unusually high start_ms. */
    @Volatile var starting: Boolean = false
        private set

    /** F13b2: duration of the last start in ms (0 = not measured yet). */
    @Volatile var lastStartMs: Long = 0L
        private set

    // ---- W package (vc34, debugger recommendation): engine warming diagnostics ----
    // (rule 9: only numbers/codes — engine_load_ms, engine_resident, warm_hit,
    // load_trigger, engine_loads; NEVER model paths/contents.)

    /** W1 (vc34): pure duration of the last engine.load() run in ms (0 = never). */
    @Volatile var engineLoadMs: Long = 0L
        private set

    /** W1 (vc34): number of executed engine.load() runs (monotonic until shutdown). */
    @Volatile var engineLoads: Int = 0
        private set

    /** W1 (vc34): trigger of the last successful load() — mic | ime_show;
     *  "-" = never loaded (🐞 load_trigger). */
    @Volatile var loadTrigger: String = "-"
        private set

    /** W1 (vc34): 0/1 — the last start() found the engine already resident
     *  (show preload hit; 🐞 warm_hit). */
    @Volatile var warmHit: Boolean = false
        private set

    /** W1/W2 (vc34): engine resident? true until shutdown() — NO idle unload. */
    val engineResident: Boolean get() = engineLoaded

    /** W1 (vc34): single-flight gate for engine.load() — show preload (ime_show)
     *  and dictation start (mic) run through ONE gate; a concurrent second
     *  caller sees the result after the gate (double-checked) instead of
     *  kicking off a parallel load() (ParakeetAsrEngine.load throws on the
     *  second call — parallel load() is thus IMPOSSIBLE). */
    private val loadGate = Any()

    /**
     * W1 (vc34, debugger recommendation): loads the engine EXACTLY ONCE (single
     * flight via [loadGate]). Measures the pure load duration ([engineLoadMs]),
     * counts [engineLoads] and remembers the trigger [trigger] (mic|ime_show) —
     * only on SUCCESS; a failed engine leaves engineLoaded=false, the later
     * start is NOT blocked. Callers: start() (error ⇒ controlled start abort)
     * and [preload] (error ⇒ false).
     */
    private fun loadEngineOnce(trigger: String) {
        if (engineLoaded) return
        synchronized(loadGate) {
            if (engineLoaded) return // double-checked: a parallel load() has won
            val dir = modelDir ?: return
            val t0 = System.nanoTime()
            try {
                engine.load(dir)
            } finally {
                engineLoadMs = (System.nanoTime() - t0) / 1_000_000
            }
            engineLoads += 1
            loadTrigger = trigger
            engineLoaded = true // W2: resident until shutdown()
        }
    }

    /**
     * W1 (vc34, debugger recommendation): public prewarming — caller:
     * VoiceKeyboardService at the FIRST onStartInputView, ONLY in the worker
     * scope (load is expensive, never on main; needs neither mic nor FGS).
     * Single flight via [loadGate]: a further call (second show, parallel
     * thread) starts NO second load(). A failure is swallowed (false) and
     * does NOT block the later dictation start — it loads lazily through the
     * same gate afterwards (the existing fallback stays untouched).
     * Return: true ⇔ engine is (now) resident.
     */
    fun preload(): Boolean {
        if (modelDir == null) return false
        if (engineLoaded) return true
        return try {
            loadEngineOnce("ime_show")
            engineLoaded
        } catch (e: Exception) {
            false // failed preload: start() loads lazily later
        }
    }

    // ---- F28b1: capture stall measurement (rule 9: only numbers, never audio contents) ----

    /** F28b1: received audio blocks (n>0) of this session (🐞: cap_blocks). */
    @Volatile private var capBlocks = 0

    /** F28b1: time of the last received block respectively of the session start
     *  (ms on the [nowMs] clock); 0 = session never started. */
    @Volatile private var lastBlockAtMs = 0L

    /** F31: time of the last LOUD block (block RMS ≥ [SILENCE_RMS]) respectively
     *  of the session start (ms on the [nowMs] clock); 0 = session never started.
     *  F31b: as long as [everLoud] is false, this always holds the session start
     *  reference ("silent since start"). */
    @Volatile private var lastLoudAtMs = 0L

    /** F31b (review P1): true as soon as the session has seen ONE loud block
     *  — afterwards the silence watchdog is permanently disabled (speech
     *  pauses are allowed). Reset in [start]. */
    @Volatile private var everLoud = false

    /** F31: total audio duration of LOUD blocks of this session in ms (🐞: loud_s). */
    @Volatile private var loudMs = 0L

    /** F42 (🐞 forensics): start time on the watchdog clock (nowMs) — reference
     *  for first_loud_ms and the 3-s start window; 0 = never started. */
    @Volatile private var startRefMs = 0L

    /** F42 (🐞: first_loud_ms): ms after session start at the everLoud latch; -1 = never. */
    @Volatile private var firstLoudMs = -1L

    /** F42 (🐞: first3s_loud_blocks): loud blocks within the first 3 s (start window). */
    @Volatile private var first3sLoudBlocks = 0

    /** F42 (🐞: first3s_peak_dbfs): highest block RMS of the first 3 s (0 = no block). */
    @Volatile private var first3sPeakRms = 0.0

    // ---- B1 (segmenter core): request handshake capture -> consumer ----

    /** B1: the capture thread has registered a segment boundary; the consumer
     *  (buffer owner) collects it on the next tick. */
    @Volatile private var segmentBoundaryRequested = false

    /** B1: start of the ACTIVE segment as AUDIO POSITION (session ms, consumer
     *  measure) — reference of the length rule (b), which the CONSUMER evaluates
     *  via its own buffer position (deterministic: window ≤ cap + 1 tick).
     *  NOT the watchdog clock — it runs ahead of the capture in self-paced
     *  operation; there (b) would be running (B1 finding of the first runs:
     *  54-s window despite the 35-s cap). */
    @Volatile private var segmentStartedAtAudioMs = 0L

    /** B1: number of executed segment mini-finales of this session (session
     *  counter, monotonic; reset in [start]). */
    @Volatile private var segmentFinalizations = 0

    /** B1: value of lastLoudAtMs at the last CUT pause — guard
     *  against repeated rule-(a) firing in THE SAME pause (streaming silence
     *  keeps (now-lastLoud) ≥ [SEGMENT_PAUSE_MS] permanently true; without a
     *  guard the consumer would finalize empty silent segments until new
     *  speech arrives). A NEW pause ⇒ new lastLoudAtMs ⇒ guard free. -1 = no
     *  boundary executed yet. */
    @Volatile private var segmentPauseCutAtLoudMs = -1L

    /** F31: seconds with loud signal of the session (🐞: loud_s; number only, rule 9). */
    fun loudS(): Double = loudMs / 1000.0

    /** F42 (🐞: first_loud_ms): ms after session start at the everLoud latch; -1 = never
     *  loud (shows whether speech arrived BEFORE the 3-s start window). */
    fun firstLoudMs(): Long = firstLoudMs

    /** F42 (🐞: first3s_loud_blocks): blocks with RMS ≥ threshold within the first 3 s. */
    fun first3sLoudBlocks(): Int = first3sLoudBlocks

    /** F42 (🐞: first3s_peak_dbfs): peak block RMS of the first 3 s in dBFS;
     *  null = no block in the window. Cross-check whether an ERR_SILENT abort
     *  was justified (peak far below −40 dBFS) or signal was lost. */
    fun first3sPeakDbfs(): Double? =
        if (first3sPeakRms <= 0.0) null
        else 20.0 * kotlin.math.log10(first3sPeakRms)

    /** Capture thread: blocks ONLY in read; loses no blocks (unbounded channel). */
    private fun captureLoop(src: AudioSource, ch: Channel<PcmBlock>) {
        val buf = ShortArray(2048)
        var startSample = 0L
        try {
            while (active.get()) {
                val n = src.read(buf, READ_TIMEOUT_MS)
                when {
                    n > 0 -> {
                        val copy = buf.copyOf(n)
                        ch.trySend(PcmBlock(seqCounter.getAndIncrement(), startSample, copy))
                        startSample += n
                        // F28b1: block received — reset the stall clock (🐞: cap_blocks).
                        capBlocks += 1
                        lastBlockAtMs = nowMs()
                        // F31: block RMS straight from the captured block (the same
                        // computation as the level channel — source-independent, works
                        // also with the debug fake source). Loud => silence clock reset.
                        // Numbers only, never contents (rule 9).
                        var acc = 0L
                        for (i in 0 until n) acc += buf[i].toLong() * buf[i].toLong()
                        val rms = kotlin.math.sqrt(acc.toDouble() / n) / 32_768.0
                        // F42 (🐞: first_loud_ms / first3s_loud_blocks /
                        // first3s_peak_dbfs): when did the everLoud latch engage — and
                        // how many loud blocks / which peak lay within the critical
                        // first 3 s (start window of the silent watchdog)?
                        // Cross-check for ERR_SILENT aborts; numbers only (rule 9).
                        val sinceStartMs = (nowMs() - startRefMs).coerceAtLeast(0L)
                        if (sinceStartMs <= SILENT_START_LIMIT_MS) {
                            if (rms >= SILENCE_RMS) first3sLoudBlocks++
                            if (rms > first3sPeakRms) first3sPeakRms = rms
                        }
                        if (rms >= SILENCE_RMS) {
                            if (!everLoud) firstLoudMs = sinceStartMs
                            // AP3: longest loud gap of the session (block RMS criterion
                            // like the auto-stop) — ONLY between loud blocks, never from
                            // session start (0 loud blocks would otherwise be "silence").
                            if (everLoud) {
                                val gapMs = nowMs() - lastLoudAtMs
                                if (gapMs > maxPauseMsValue) maxPauseMsValue = gapMs
                            }
                            everLoud = true // F31b: from here silence is allowed
                            lastLoudAtMs = nowMs()
                            loudMs += n * 1000L / 16_000L
                        }
                    }
                    n < 0 -> { // error: the error channel already triggered abort()
                        return
                    }
                }
                // F28b1: stall watchdog — a source that persistently delivers 0 samples
                // (AudioRecord time out) otherwise let the loop run forever and the
                // session stay "dictation" eternally. After STALL_LIMIT_MS without a
                // block: controlled ending via the EXISTING error/abort path —
                // exactly once (single thread, return after firing), no new
                // thread, no audio content in the error code (rule 9).
                if (capStallMs() > STALL_LIMIT_MS) {
                    errorListener.onError(ERR_STALL, "no_audio")
                    return
                }
                // F31/F31b: blocks are RUNNING, but the session was NEVER loud
                // (user case: −56 dBFS noise from the start). F31b (review P1):
                // !everLoud gate — after the first loud block this
                // watchdog is permanently disabled (natural speech pauses > 3 s
                // must not abort good sessions; missing blocks are still covered
                // by ERR_STALL). Reference = session start on the
                // [nowMs] clock (a last loud block is missing by definition, so
                // lastLoudAtMs is the start time). Same existing
                // error path: only once, no new thread. The no-block stall
                // (ERR_STALL) has priority — checked above; capBlocks > 0 separates
                // the cases.
                if (capBlocks > 0 && !everLoud &&
                    (nowMs() - lastLoudAtMs) > SILENT_START_LIMIT_MS
                ) {
                    errorListener.onError(ERR_SILENT, "no_signal")
                    return
                }
                // F43: silence AFTER speech started (≥ TRAILING_SILENCE_MS since the
                // last loud block) → AUTOMATIC regular ending via
                // stop() — no ERR_*, final decode over the whole buffer;
                // stop() joins the consumer first (already captured blocks are
                // decoded completely, no spoken characters lost). The
                // session afterwards counts as user-ended (onStopped finale in
                // the IME). Shorter pauses (< 2500 ms) never interrupt.
                // F43 release (c-gate): auto-stop ONLY on STREAMING silence
                // (capStallMs < TRAILING_SILENCE_MS — blocks keep running, just
                // quiet). Project doctrine "silence ≠ stall" (F30/F28b1): a DEAD
                // source (no blocks) stays with the ERR_STALL contract — the
                // auto-stop does NOT preempt ERR_STALL (FakeAudioScenarioTest c).
                // F49: the hold gesture signals "not done yet" — auto-stop
                // only if the gate is open (toggle; watchdogs untouched).
                if (everLoud && capStallMs() < TRAILING_SILENCE_MS &&
                    !autoStopSuppressed() &&
                    (nowMs() - lastLoudAtMs) > TRAILING_SILENCE_MS
                ) {
                    // AP3: mark the auto-stop explicitly (stop_cause=autostop, not
                    // final) + gate in the stop moment (here open=1 per guard definition).
                    explicitStopCauseValue = "autostop"
                    gateAtStopValue = 1
                    stop()
                    return
                }
                // B1 (PLAN_V0130 §6.3 (c), I3 α=38.5 ms/s): REGISTER a segment
                // boundary — the consumer executes it on the next tick (it is
                // the sole buffer owner; sequential with feedAudio ⇒ no audio
                // gap, running blocks queue in the channel). The capture
                // registers only the SILENCE rule (a): sentence pause (≥
                // [SEGMENT_PAUSE_MS] streaming silence) AND last hypothesis ends
                // on a sentence punctuation — cuts at SENTENCE ends, where the
                // seam is harmless. The LENGTH rule (b) is carried by the CONSUMER
                // itself (buffer-based, deterministic) — the watchdog clock runs
                // ahead of the capture in self-paced operation, a clock-based (b)
                // would be running; hence segmentStartedAtMs = nowMs() (distance 0).
                // The mini-finale commits PERMANENTLY and resets only buffer/decode
                // statistics — session/FGS/watchdogs keep running.
                if (segmentFinalizeMs > 0 &&
                    lastLoudAtMs != segmentPauseCutAtLoudMs && // each pause only ONCE
                    segmentBoundary(
                        nowMs = nowMs(),
                        lastLoudAtMs = lastLoudAtMs,
                        everLoud = everLoud,
                        capStallMs = capStallMs(),
                        segmentStartedAtMs = nowMs(), // (b) is carried by the consumer
                        lastHypothesisEndsWithPunct = controller.lastHypothesisEndsWithPunct(),
                        segmentFinalizeMs = segmentFinalizeMs,
                    )
                ) {
                    segmentBoundaryRequested = true
                }
            }
        } catch (e: InterruptedException) {
            // stop(): let the capture thread wind down
        } catch (e: Exception) {
            // the capture thread never dies silently: controlled abort path
            errorListener.onError(ERR_AUDIO, e.javaClass.simpleName)
        }
    }

    /** Consumer: aggregates up to ~tickSamples NEW samples, then feedAudio (in order). */
    private suspend fun consume(ch: Channel<PcmBlock>) {
        val acc = ArrayList<ShortArray>()
        var accSamples = 0
        var accStart = -1L
        for (block in ch) {
            if (accStart < 0) accStart = block.startSample
            acc.add(block.samples)
            accSamples += block.samples.size
            if (accSamples >= tickSamples) {
                flush(acc, accStart)
                acc.clear()
                accSamples = 0
                accStart = -1
            }
        }
        flush(acc, accStart) // remnants on stop: do NOT lose them
    }

    private suspend fun flush(blocks: List<ShortArray>, startSample: Long) {
        if (blocks.isEmpty()) return
        val total = blocks.sumOf { it.size }
        val combined = ShortArray(total)
        var off = 0
        for (b in blocks) {
            System.arraycopy(b, 0, combined, off, b.size)
            off += b.size
        }
        try {
            // S12b diagnostics: accumulate processing time per chunk + audio duration
            // (numbers only; RTF = processingMs/audioMs in diagnosticsLine).
            val t0 = System.currentTimeMillis()
            controller.feedAudio(PcmBlock(seqCounter.getAndIncrement(), startSample, combined))
            val dt = System.currentTimeMillis() - t0
            processingMs += dt
            audioMs += total * 1000L / 16_000L
            if (firstHypothesisLatencyMs == 0L && startedAtMs > 0) {
                firstHypothesisLatencyMs = System.currentTimeMillis() - startedAtMs
            }
            // B1: COLLECT a registered segment boundary — here in the consumer,
            // the sole buffer owner, sequential with feedAudio (no
            // audio gap; the unbounded channel bridges the ~α·T decode time).
            // Mini-finale: final decode over the CURRENT segment buffer
            // + PERMANENT commit; afterwards the controller resets ONLY buffer +
            // decode statistics (session counters keep session values).
            // (b) LENGTH rule: the consumer evaluates it via its OWN
            // audio position (audioMs − segment start) — deterministically
            // bounded to cap + 1 tick (~37 s); the (a) inputs are
            // neutralized here (everLoud=false): the consumer cuts only by
            // LENGTH, the silence rule arrives as a request from the capture.
            // Errors of the segment decode run through the existing catches
            // below (OOM/ISE/Exception) — no new error path.
            if (segmentFinalizeMs > 0) {
                val byLength = segmentBoundary(
                    nowMs = audioMs,
                    lastLoudAtMs = 0L,
                    everLoud = false,
                    capStallMs = 0L,
                    segmentStartedAtMs = segmentStartedAtAudioMs,
                    lastHypothesisEndsWithPunct = false,
                    segmentFinalizeMs = segmentFinalizeMs,
                )
                if (byLength || segmentBoundaryRequested) {
                    // B1 race fix: set the guard BEFORE the (suspending) finalize
                    // — otherwise the capture registers another (a) request for the same
                    // pause during the in-flight final decode
                    // (finding: double boundary within one pause), which the next
                    // tick would then collect.
                    segmentPauseCutAtLoudMs = lastLoudAtMs
                    segmentBoundaryRequested = false
                    if (controller.finalizeSegment()) {
                        segmentFinalizations += 1
                        segmentStartedAtAudioMs = audioMs
                        segmentBoundaryRequested = false // race follow-up
                    } else {
                        // B1 fix (review P1): transient error in the mini-finale
                        // (controller discards without fail()) — discard the request
                        // AND reset the guard so the NEXT boundary
                        // attempt (next sentence pause/length rule) may fire
                        // again; session/FGS keep running untouched.
                        segmentPauseCutAtLoudMs = -1L
                    }
                }
            }
        } catch (e: OutOfMemoryError) {
            // F3-R: native ORT memory error during inference lands in
            // THIS worker — without a catch it only kills the coroutine (invisible).
            // Controlled ending + stable error code "oom" via the
            // existing channel (mapVisibleError maps oom →
            // dictation_error_out_of_memory). No text/audio in the code.
            // ONLY OOM — other errors deliberately stay uncaught.
            android.util.Log.w(TAG, "oom during partial decode")
            lastError = "$ERR_OOM:${e.javaClass.simpleName}"
            active.set(false)
            channel?.close()
            controller.abort()
            notifyAborted(ERR_OOM) // F44: kill path observable — service stops the FGS
            lastStopOutcome = de.voicekeyboard.dictation.StopOutcome.NoFinal(
                "$ERR_OOM:${e.javaClass.simpleName}",
            )
            routeObserver?.invoke(false)
            return
        } catch (e: IllegalStateException) {
            // F13d/P1: editor sync timeout or similar — WITHOUT this catch the
            // consumer job dies SILENTLY, the capture thread keeps running and the
            // unbounded channel grows without the user seeing anything.
            // Controlled ending: remember the reason (rule: no swallowing catch
            // without a report), abort the session, close the channel, wind down
            // the capture via active=false, make the exit visible as a StopOutcome.
            // F56 (vc34 device finding, Fable §0.1.5 family): the ONLY ISE type
            // that can arrive here is the RETHROWN editor sync timeout
            // (all other ISEs are swallowed by feedAudio via fail(ASR_PARTIAL_
            // ERROR)) — the controller has set fail(editor_sync_timeout) BEFORE the
            // rethrow. The FINE reason must NOT be MASKED by the generic
            // consumer:<class> (F1 contract: drift visible;
            // previously visibleError=consumer:IllegalStateException showed the
            // else-fallback error text, fgs_stopped_by=abort:consumer:…).
            val iseCode = lastError ?: controller.lastErrorCode
                ?: "consumer:" + e.javaClass.simpleName
            if (lastError == null) lastError = iseCode
            if (lastAbortReason == "-") lastAbortReason = iseCode
            active.set(false)
            channel?.close()
            controller.abort()
            notifyAborted(iseCode) // F44: kill path observable — WITH the fine code
            lastStopOutcome = de.voicekeyboard.dictation.StopOutcome.NoFinal(
                controller.lastErrorCode ?: lastError,
                failedWith = true,
                droppedWith = controller.droppedBlocks,
            )
            routeObserver?.invoke(false)
            return
        } catch (e: Exception) {
            // F13d/P1: every further caught case sets a VISIBLE reason
            // (no silent death of the consumer, no growing channel).
            if (lastError == null) lastError = "consumer:" + e.javaClass.simpleName
            active.set(false)
            channel?.close()
            controller.abort()
            notifyAborted("consumer:" + e.javaClass.simpleName) // F44: kill path observable
            lastStopOutcome = de.voicekeyboard.dictation.StopOutcome.NoFinal(
                controller.lastErrorCode ?: lastError,
                failedWith = true,
                droppedWith = controller.droppedBlocks,
            )
            routeObserver?.invoke(false)
            return
        }
        // C-2b: the controller is IDLE after an ASR error — letting the capture
        // continue would mean an open microphone with no use. Controlled ending.
        if (controller.state == DictationState.IDLE && active.get()) {
            active.set(false)
            channel?.close()
            // AP3 (Fable §0.1 item 5, “line 696 overwrite writer” — quoted from
            // the German spec): first-writer-keeps-
            // detail — a previously set FINE detail (editor_change,
            // michold_cancel, abort:<r>) must NOT be covered by the generic ASR
            // error code of the consumer ending. Only if still open
            // ("-" / no abort reason set) is audio_error the cause.
            if (lastAbortReason == "-") {
                lastAbortReason = "audio_error:${controller.lastErrorCode ?: "unknown"}"
            }
            controller.abort()
            notifyAborted(lastAbortReason) // F44: kill path observable — service stops the FGS
            routeObserver?.invoke(false)
        }
    }

    /**
     * Controlled ending: stop the capture, drain the queue and remnants (in
     * order), then final decode over the whole buffer — independent of the
     * minimum length (the guard applies only to partials, §4.4).
     */
    fun stop() {
        if (!active.compareAndSet(true, false)) {
            // F26a: no-op case with a reason (C2 export: E3/E4/E5 do not blur together).
            if (lastAbortReason == "-") lastAbortReason = "idle_noop"
            return // without a start: no-op
        }
        val src = source ?: return
        val ch = channel ?: return
        // AP3: F43 gate state in the stop moment — 1 = gate OPEN (auto-stop
        // not suppressed), 0 = CLOSED (still held), -1 = no stop yet.
        // Only the FIRST stop of the session wins (W1b: autostop + gate=1 +
        // start_src=hold = gate lost in the middle of holding).
        if (gateAtStopValue == -1) {
            gateAtStopValue = if (autoStopSuppressed()) 0 else 1
        }
        // Capture loop stops (active=false); collect the remnants of the source and
        // put them into the channel BEFORE the close — trySend after close would lose.
        for (block in src.stopAndDrain()) ch.trySend(block)
        ch.close()
        val consumerJob = consumer
        val thread = captureThread
        captureThread = null
        source = null
        channel = null
        // C-1: final decode + drain in the worker scope — NO runBlocking on the
        // IME main thread (DESIGN §2.3). stop() returns immediately.
        scope.launch {
            consumerJob?.join()
            try {
                val outcome = controller.stop() // F13b: outcome BEFORE onStopped runs
                lastStopOutcome = outcome
                // F26a: note the final outcome — does not overwrite an abort reason.
                if (lastAbortReason == "-") {
                    lastAbortReason = when (outcome) {
                        is de.voicekeyboard.dictation.StopOutcome.FinalRan -> "final"
                        is de.voicekeyboard.dictation.StopOutcome.NoFinal -> "no_final:${outcome.errorCode ?: "unknown"}"
                        else -> "idle_noop"
                    }
                }
            } catch (e: OutOfMemoryError) {
                // F3-R: OOM in the final decode — same as the flush() path
                android.util.Log.w(TAG, "oom during final decode")
                lastError = "$ERR_OOM:${e.javaClass.simpleName}"
                controller.abort()
            } catch (e: IllegalStateException) {
                // F56: editor sync timeout in the WHOLE finale — the same F1 rethrow
                // as in the consumer path. WITHOUT this catch the ISE killed the
                // final worker UNCAUGHT (coroutine death: no onStopped finale,
                // no FGS sync in the service; depending on the scope handler a
                // process crash). Identical contract to the consumer catch: fine
                // code visible (editor_sync_timeout), not masked; controlled session
                // ending.
                val iseCode = lastError ?: controller.lastErrorCode
                    ?: "consumer:" + e.javaClass.simpleName
                if (lastError == null) lastError = iseCode
                if (lastAbortReason == "-") lastAbortReason = iseCode
                lastStopOutcome = de.voicekeyboard.dictation.StopOutcome.NoFinal(
                    controller.lastErrorCode ?: lastError,
                    failedWith = true,
                    droppedWith = controller.droppedBlocks,
                )
                controller.abort()
                notifyAborted(iseCode) // F44: kill path observable — service sync runs
            } catch (e: Exception) {
                // F56: any other exception from the final decode — same
                // controlled ending (before: uncaught coroutine death).
                val excCode = lastError ?: controller.lastErrorCode
                    ?: "consumer:" + e.javaClass.simpleName
                if (lastError == null) lastError = excCode
                if (lastAbortReason == "-") lastAbortReason = excCode
                lastStopOutcome = de.voicekeyboard.dictation.StopOutcome.NoFinal(
                    controller.lastErrorCode ?: lastError,
                    failedWith = true,
                    droppedWith = controller.droppedBlocks,
                )
                controller.abort()
                notifyAborted(excCode) // F44: kill path observable
            }
            src.release()
            thread?.let { t -> t.join(500) }
            // N-2: callback (view mutation!) NEVER in the worker context
            if (mainDispatcher != null) {
                kotlinx.coroutines.withContext(mainDispatcher) { onStopped?.invoke() }
            } else {
                onStopped?.invoke()
            }
        }
        routeObserver?.invoke(false)
    }

    /**
     * F13b: outcome of the LAST stop() finale (assigned asynchronously, before
     * onStopped runs) — with this the service evaluates whether the ending was a
     * real success or a silently failed session ([StopOutcome.NoFinal]).
     */
    @Volatile var lastStopOutcome: de.voicekeyboard.dictation.StopOutcome =
        de.voicekeyboard.dictation.StopOutcome.IdleNoOp
        private set

    /** F13b: error state of the current controller session (preserved until the next start). */
    val failedThisSession: Boolean get() = controller.failedThisSession

    /** F13b2: controller state as a short code (IDLE/RECORDING/FINALIZING) — the
     *  🐞 evidence case C1: IDLE while the user is still pressing. */
    val ctlState: String get() = controller.state.name

    /** F13b2: hypotheses produced of the current session (🐞: hyp_count). */
    val hypCount: Int get() = controller.hypothesesThisSession

    /** F13b2: gap since the last hypothesis in ms, -1 if never (🐞: hyp_gap_ms). */
    fun hypGapMs(): Long = controller.currentHypGapMs()

    /** F13b2: recorded audio in seconds (🐞: audio_s, 1 decimal place). */
    /** B1 fix (review P2a): 🐞 audio_s = SESSION audio length (completed
     *  segments + active buffer piece, tracked) — NO longer the
     *  segment-local controller buffer that drops to 0 after every mini-finale.
     *  Without the segmenter identical to the existing value. */
    val audioSeconds: Double get() = controller.sessionAudioSeconds

    /** F13b: discarded audio blocks of the current session (🐞 diagnostics F13b2). */
    val droppedBlocks: Int get() = controller.droppedBlocks

    // F17c: decode workload (C2) — pass-through to the controller, 🐞: decode_ms_*

    /** Largest decode time per transcribe call in ms (0 = never decoded). */
    fun decodeMsMax(): Long = controller.decodeMsMax()

    /** p95 of the decode times in ms (deterministic controller sample). */
    fun decodeMsP95(): Long = controller.decodeMsP95()

    /** Buffer length in seconds at the last decode run (🐞: buffer_s). */
    /** B1 fix (review P2a): 🐞 buffer_s = the same tracked SESSION length as
     *  [audioSeconds] (export semantics); the actual decode window
     *  of the last run is shown by hyp_last_buffer_s (segment-local, unchanged). */
    fun bufferSeconds(): Double = controller.sessionAudioSeconds

    /** F21: currently effective partial interval in ms; 0 = pacing off (🐞: decode_pace_ms). */
    fun decodePaceMs(): Long = controller.decodePaceMs()

    /** F21: partial decodes skipped due to the tick of this session (🐞: decode_skips). */
    fun decodeSkips(): Int = controller.decodeSkips()

    /** F26b (C2 export): buffer level (s) of the last accepted hypothesis. */
    fun lastHypothesisBufferSeconds(): Float = controller.lastHypothesisBufferSeconds

    // ---- F28b2: session-end diagnostics (rule 9: pure codes + ms, no contents) ----

    /** F28b2: timestamp of the session start (wall clock); 0 = never started.
     *  Serves the IME layer for the ms timestamps in [micRel]. */
    val sessionStartedAtMs: Long get() = startedAtMs

    /** F28b2: last releasing/cancelling event of the session (encoded,
     *  +ms since session start); "" = none. Set by the IME layer. */
    @Volatile private var micRelValue: String = ""

    /** F28b2: last state given to the status row
     *  (ready|dictat|finalizing|error). Set by the IME layer. */
    @Volatile private var statusShownValue: String = "ready"

    /** F28b2: who ended the session last? (🐞: mic_rel, pure code). */
    fun micRel(): String = micRelValue

    /** F28b2: sets [micRel] (caller: VoiceKeyboardService; controlled codes). */
    fun setMicRel(value: String) {
        micRelValue = value
    }

    /** F28b2: what did the status row show last? (🐞: status_shown, pure code). */
    fun statusShown(): String = statusShownValue

    /** F28b2: sets [statusShown] (caller: VoiceKeyboardService.updateStatus). */
    fun setStatusShown(code: String) {
        statusShownValue = code
    }

    /** F28b1: received audio blocks (n>0) of this session (🐞: cap_blocks). */
    fun capBlocks(): Int = capBlocks

    /** F28b1: ms since the last audio block respectively since the session start
     *  (🐞: cap_stall_ms); 0 without a running/started session. Still
     *  readable after an abort (export). */
    fun capStallMs(): Long =
        if (lastBlockAtMs == 0L) 0L else (nowMs() - lastBlockAtMs).coerceAtLeast(0L)

    /**
     * F26a: reason of the last ABORT WITHOUT FINALE (C2 export: E3/E4/E5 all
     * ran silently together). String-enum kinds like "michold_cancel" / "editor_change" /
     * "audio_error:<code>" / "ime_destroy"; stop() adds "final" /
     * "no_final:<code>" / "idle_noop" (does not overwrite an abort reason, the
     * finale stays visible). start() sets "-". ONLY controlled names,
     * no contents (rule 9).
     */
    @Volatile var lastAbortReason: String = "-"
        private set

    /** Abort without finale: ordered to IDLE, already committed text stays. */
    fun abort(reason: String = "-") {
        lastAbortReason = reason
        // AP3: gate sample also on the abort path (0 = abort during an
        // active hold gesture — e. g. editor_change while holding).
        if (gateAtStopValue == -1) {
            gateAtStopValue = if (autoStopSuppressed()) 0 else 1
        }
        active.set(false)
        channel?.close()
        val consumerJob = consumer
        val thread = captureThread
        val src = source
        captureThread = null
        source = null
        channel = null
        controller.abort()
        routeObserver?.invoke(false)
        // P0 finding 2(b/c): the abort becomes observable — the service syncs
        // the mic UI state there and stops the micro FGS since F44. Main hop like onStopped.
        notifyAborted(reason)
        // C-1: cleanup in the worker scope, main thread returns immediately.
        scope.launch {
            consumerJob?.join()
            runCatching { src?.stopAndDrain(); src?.release() }
            thread?.let { t -> t.join(200) }
        }
    }

    /**
     * F44 (F41 repro, P1 — micro FGS kept running ~58 s after the watchdog abort):
     * EVERY kill path (watchdog abort, consumer death, OOM, ASR error) makes the
     * abort observable — the service stops the FGS over it (same place as
     * in the regular stop) and remembers the cause as fgs_stopped_by code.
     * Main hop like in [onStopped] (view mutation at the caller).
     */
    private fun notifyAborted(reason: String) {
        val abortedCb = onAborted
        if (abortedCb != null) {
            if (mainDispatcher != null) scope.launch(mainDispatcher) { abortedCb(reason) }
            else abortedCb(reason)
        }
    }

    /**
     * C-4: release engine resources (~863 MB native sessions, ASR_CONTRACT §5).
     * Call: IME onDestroy / editor-change abort. No second load() afterwards.
     *
     * W2 (vc34, debugger recommendation): the engine stays resident UP TO HERE —
     * deliberately NO idle unload after session end (the next dictation starts
     * warm, warm_hit=1). The ~863 MB native sessions are kept beyond the
     * session; release ONLY at IME death (onDestroy).
     */
    fun shutdown() {
        // N-3: idempotent — close the engine only ONCE; running finale workers
        // are aborted via abort() (no transcribe after close anymore:
        // controller.abort() sets IDLE before close() runs).
        if (!closed.compareAndSet(false, true)) return
        abort("ime_destroy")
        engineLoaded = false
        runCatching { engine.close() }
    }

    /**
     * AP7-E1 (Fable §0.2.5 P1): re-baseline after the app's restartInput() — NOT an
     * editor change. Possession stays if our own committed text is still a
     * suffix of the editor text ([OwnershipTracker.rebaseline]); return
     * false ⇒ app deleted/changed the text ⇒ caller aborts in a controlled
     * way (no ghost writing). The sink is connectionless per call
     * (connectionProvider lambda) — after restartInput it reads the NEW
     * connection, nothing to invalidate in a cache.
     *
     * AP7-E1c (P0 blocker): read the context in the length of the FRESHER of
     * the two own states (tracker vs. sink ownText — the sink maintains its
     * ownSpan synchronously in the commit round, the tracker lags behind on
     * the worker side), and pass the sink state to the tracker as fresherExpected.
     * Diagnostic fields exp_len/act_len (only lengths, rule 9) for the
     * VK-SESS line of the service.
     */
    @Volatile var lastRebaseExpLen: Int = -1
        private set
    @Volatile var lastRebaseActLen: Int = -1
        private set

    fun rebaselineOwnership(): Boolean {
        val expected = tracker.expectedOwnText
        val own = sink.ownText()
        val len = maxOf(expected.length, own.length)
        val actual = if (len == 0) null else sink.textBeforeCursor(len)
        lastRebaseExpLen = maxOf(expected.length, own.length)
        lastRebaseActLen = actual?.length ?: -1
        return tracker.rebaseline(actual, own)
    }

    /** AP7-E1: number of re-baselines of this instance (🐞 rebases, rule 9: number only). */
    fun rebases(): Int = tracker.rebases

    /** AP7-E1d: re-bind callback must not set lost in the retry window. */
    fun openRebindBaseline() = tracker.reopenSelectionBaseline()

    /** Editor change: increment generation, discard possession (no writing afterwards). */
    fun onEditorChanged() {
        // F26a: editor change during an active session (C2 export path E4) —
        // the controller aborts in the RECORDING/FINALIZING branch, we note
        // the reason BEFOREHAND (its abort() knows no reasons).
        if (controller.state == DictationState.RECORDING || controller.state == DictationState.FINALIZING) {
            lastAbortReason = "editor_change"
        }
        controller.onEditorChanged()
    }

    /** Selection signal from the UI thread. */
    fun onUpdateSelection(oldSelStart: Int, oldSelEnd: Int, newSelStart: Int, newSelEnd: Int) {
        // AP3 (H-E2, Fable §4): count unexpected selection callbacks and record
        // the possession-loss moment (seconds, session audio clock) — the
        // tracker itself stays untouched; the flip false→true IS the
        // unexpected event (rule 9: counters/times only).
        val lostBefore = tracker.ownershipLost
        controller.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd)
        if (tracker.ownershipLost) {
            selUnexpectedValue += 1
            if (!lostBefore && ownerLostAtAudioMsValue < 0L) {
                ownerLostAtAudioMsValue = audioMs
            }
        }
    }

    /** Route message of the AudioRouter (UI display; caller: M4c-2 wiring). */
    fun onRouteChanged(routeLabel: String) {
        route = routeLabel
    }

    /** B1: number of executed segment mini-finales of this session (monotonic,
     *  0 = segmenter off or no boundary). Number only (rule 9). */
    fun segmentFinalizations(): Int = segmentFinalizations

    // ---- AP3 (export v2 part 1, PLAN_C2_FABLE_R2 §4): session forensics ----

    /** AP3: start kind (hold|toggle) — derived via the F49 gate at start(). */
    @Volatile private var startSrcValue: String = "-"

    /** AP3: F43 gate in the stop moment — 1=open, 0=closed (held), -1=no stop. */
    @Volatile private var gateAtStopValue: Int = -1

    /** AP3: explicit stop marker ("autostop" from the F43 branch, else "-"). */
    @Volatile private var explicitStopCauseValue: String = "-"

    /** AP3: longest loud gap of the session in ms (block RMS). */
    @Volatile private var maxPauseMsValue: Long = 0L

    /** AP3: audio position (session clock, ms) of the first possession loss; -1 = never. */
    @Volatile private var ownerLostAtAudioMsValue: Long = -1L

    /** AP3: unexpected selection callbacks (tracker delivers lost=true). */
    @Volatile private var selUnexpectedValue: Int = 0

    /** AP3: onStartInput(restarting=true) counter (increment from the service). */
    @Volatile private var restartInputsValue: Int = 0

    fun startSrc(): String = startSrcValue
    fun gateAtStop(): Int = gateAtStopValue
    fun maxPauseMs(): Long = maxPauseMsValue
    fun selUnexpected(): Int = selUnexpectedValue
    fun restartInputs(): Int = restartInputsValue

    /** AP3: onStartInput(restarting=true) — caller: VoiceKeyboardService (1 line). */
    fun noteRestartInput() {
        restartInputsValue += 1
    }

    /**
     * AP3: possession-loss moment in SECONDS (-1 = never lost) — earliest
     * of two detectors: unexpected selection callback (here, session
     * audio clock) OR denied write intent in the controller (there its own
     * session audio position; both clocks identically scaled). Number only (rule 9).
     */
    fun ownerLostAtS(): Double {
        val selS = if (ownerLostAtAudioMsValue >= 0) ownerLostAtAudioMsValue / 1000.0 else -1.0
        val wrS = controller.ownershipDeniedAtS
        return when {
            selS < 0 -> wrS
            wrS < 0 -> selS
            else -> minOf(selS, wrS)
        }
    }

    /** AP3: unified stop cause — pure function [stopCauseOf]. */
    fun stopCause(): String =
        stopCauseOf(explicitStopCauseValue, micRel(), lastAbortReason, startSrcValue)

    // ---- A2b (PLAN_V0133 §A2b): decode window diagnosis to the outside ----

    /**
     * A2b: callback after EVERY decode window — receives the enriched,
     * immutable [DecodeWindowDiagnosis] object (only numbers/codes, no
     * UI/Android type, rule 9). null = none. Caller: VoiceKeyboardService
     * (A4b wiring of the VK-ASR window line).
     */
    @Volatile var onDecodeWindow: ((DecodeWindowDiagnosis) -> Unit)? = null

    /** A2b: last diagnosis window (null = none yet) — 🐞 query without callback. */
    @Volatile private var lastDecodeWindowValue: DecodeWindowDiagnosis? = null

    fun lastDecodeWindow(): DecodeWindowDiagnosis? = lastDecodeWindowValue

    // ---- A4a (PLAN_V0133 §A4a): pass the A2b write diagnostics through readably ----
    // (completion of the A2b "readable" contract: the collector holds ONLY the
    // coordinator — the session counters of the controller are invisible in
    // the export without this pass-through. Numbers only, rule 9.)

    /** A4a: write results of this session, monotonic (🐞: write_same). */
    fun writeSameCount(): Int = controller.writeSameCount

    /** A4a: denied write intents (🐞: write_w1). */
    fun writeW1Count(): Int = controller.writeW1Count

    /** A4a: blocked rollbacks — visible once (🐞: write_w3d). */
    fun writeW3dCount(): Int = controller.writeW3dCount

    /** A4a: executed write steps (🐞: write_delta). */
    fun writeDeltaCount(): Int = controller.writeDeltaCount

    /** A4a: session audio seconds of the last DELTA; -1 = none yet
     *  (🐞: last_write_audio_s — NEVER '-', but -1). */
    fun lastWriteAudioS(): Double = controller.lastWriteAudioS

    init {
        // A2b: enrich the controller snapshot (session forensics of the
        // coordinator) and pass it to the outside immutably. `out`
        // (output_chars) is delivered by the service synchronously from its
        // DictationOutputCounter (A4b) — the coordinator does not own it.
        controller.onDecodeWindow = { stats ->
            val diagnosis = DecodeWindowDiagnosis(
                audioS = stats.audioS,
                decodeMs = stats.decodeMs,
                paceMs = stats.paceMs,
                skips = stats.skips,
                hypLen = stats.hypLen,
                currentLen = stats.currentLen,
                committedLen = stats.committedLen,
                write = stats.write,
                ownerLostAtS = ownerLostAtS(),
                selUnexpected = selUnexpectedValue,
                restartInputs = restartInputsValue,
                rebases = tracker.rebases,
                hypGapMs = stats.hypGapMs,
                decodeMsMax = stats.decodeMsMax,
                decodeMsP95 = stats.decodeMsP95,
                bufferS = stats.bufferS,
            )
            lastDecodeWindowValue = diagnosis
            onDecodeWindow?.invoke(diagnosis)
        }
    }
}

/**
 * A2b (PLAN_V0133 §A2b): IMMUTABLE decode window diagnosis object of the
 * coordinator — only numbers/codes, NO UI/Android type (rule 9). `out`
 * (output_chars) is delivered by the service synchronously from its
 * DictationOutputCounter (A4b wiring) — the coordinator does not own this
 * counter.
 */
data class DecodeWindowDiagnosis(
    /** Session audio length at the window end (s; 🐞: audio_s). */
    val audioS: Double,
    /** Duration of the decode run (ms; 🐞: decode_ms). */
    val decodeMs: Long,
    /** Currently effective partial interval (ms; 🐞: pace_ms; 0 = off). */
    val paceMs: Long,
    /** Partial decodes skipped due to the tick (🐞: skips). */
    val skips: Int,
    /** Length of the hypothesis (🐞: hyp_len). */
    val hypLen: Int,
    /** Own state length after the window (🐞: current_len). */
    val currentLen: Int,
    /** Committed prefix length after the window (🐞: committed_len). */
    val committedLen: Int,
    /** Write code of the window (same|w1|w3d|delta|-; 🐞: write). */
    val write: String,
    /** Possession-loss moment (s; -1 = never; 🐞: owner_lost_at_s). */
    val ownerLostAtS: Double,
    /** Unexpected selection callbacks (🐞: sel_unexpected). */
    val selUnexpected: Int,
    /** onStartInput(restarting=true) counter (🐞: restart_inputs). */
    val restartInputs: Int,
    /** Executed re-baselines (🐞: rebases). */
    val rebases: Int,
    /** Gap since the last hypothesis (ms; -1 = never; 🐞: hyp_gap_ms). */
    val hypGapMs: Long,
    /** Largest decode duration of the sample (ms; 🐞: decode_ms_max). */
    val decodeMsMax: Long,
    /** p95 of the decode duration (ms; 🐞: decode_ms_p95). */
    val decodeMsP95: Long,
    /** Length of the decoded window (s; 🐞: buffer_s). */
    val bufferS: Double,
)

/**
 * B1 (PLAN_V0130 §6.3 recommendation (c) — intermediate segment finalization,
 * §5.1-CONFORM: whole_buffer only segmented IN TIME): pure decision
 * "Segment boundary now?". Two rules:
 *  - (a) SILENCE rule (soft seam at sentence ends): [everLoud] and streaming
 *    silence ([capStallMs] < [SEGMENT_STALL_GATE_MS] — silence ≠ stall, F28b1)
 *    and pause ≥ [SEGMENT_PAUSE_MS] since the last loud block and the
 *    last hypothesis ends on a sentence punctuation (. ! ?) — there the text
 *    seam is the most harmless.
 *  - (b) LENGTH rule (forced segment): [nowMs] - [segmentStartedAtMs] ≥
 *    [segmentFinalizeMs] — bounds the decode window even for a pauseless
 *    monologue. [segmentFinalizeMs] ≤ 0 ⇒ segmenter OFF (always false).
 *
 * I3 curve reference (reports/engine_cost_curve.csv, α=38.5 ms/s, superlinear from
 * ~60 s): 35 s cap ⇒ decode(segment) ≈ 1.35 s — below the 1.5-s threshold of
 * the technical stage; shorter sentence pauses (< [SEGMENT_PAUSE_MS]) never cut.
 * Pure function without Android — JVM-testable (SegmentFinalizeTest).
 */
fun segmentBoundary(
    nowMs: Long,
    lastLoudAtMs: Long,
    everLoud: Boolean,
    capStallMs: Long,
    segmentStartedAtMs: Long,
    lastHypothesisEndsWithPunct: Boolean,
    segmentFinalizeMs: Long,
): Boolean {
    if (segmentFinalizeMs <= 0L) return false
    // (b) length rule: hard segment end — applies also without silence/punctuation.
    if (nowMs - segmentStartedAtMs >= segmentFinalizeMs) return true
    // (a) silence rule: sentence pause with sentence-punctuation ending (soft seam).
    return everLoud &&
        capStallMs < DictationCoordinator.SEGMENT_STALL_GATE_MS &&
        (nowMs - lastLoudAtMs) >= DictationCoordinator.SEGMENT_PAUSE_MS &&
        lastHypothesisEndsWithPunct
}

/**
 * AP3 (PLAN_C2_FABLE_R2 §4, export v2): unified stop cause as a
 * PURE function over the three existing forensics sources — deterministic
 * priority, codes only (rule 9):
 *
 * 1. [explicitCause] — marker of the capturing path itself (today: "autostop"
 *    from the F43 branch; W1b: autostop at start_src=hold + gate_at_stop=1).
 * 2. editor_change / michold_cancel — FINE abort details (lastAbortReason).
 * 3. maxDuration / systemStop — audio source endings (audio_error code).
 * 4. audio_error:<code> — other ASR/audio errors.
 * 5. holdup / toggle / up / hidden / systemStop / editor_change — the
 *    physical user/lifecycle event (micRel; "up" at toggle start ⇒
 *    "toggle" — the mic switch, not the status bar UP).
 * 6. abort:<reason> — further abort reasons (start_superseded, no_final:* …).
 * 7. final — regular overall finale without another event.
 * 8. idle_noop — no stop/no event (default).
 */
fun stopCauseOf(
    explicitCause: String,
    micRel: String,
    lastAbortReason: String,
    startSrc: String,
): String = when {
    explicitCause != "-" -> explicitCause
    lastAbortReason == "editor_change" -> "editor_change"
    lastAbortReason == "michold_cancel" -> "michold_cancel"
    lastAbortReason == "audio_error:maxDuration" -> "maxDuration"
    lastAbortReason == "audio_error:systemStop" -> "systemStop"
    lastAbortReason.startsWith("audio_error:") -> lastAbortReason
    micRel.startsWith("holdup") -> "holdup"
    micRel.startsWith("up") -> if (startSrc == "toggle") "toggle" else "up"
    micRel.startsWith("toggle") -> "toggle"
    micRel.startsWith("hidden") -> "hidden"
    micRel.startsWith("systemStop") -> "systemStop"
    micRel.startsWith("editor") -> "editor_change"
    lastAbortReason == "final" -> "final"
    lastAbortReason !in listOf("-", "idle_noop") -> "abort:" + lastAbortReason
    else -> "idle_noop"
}
