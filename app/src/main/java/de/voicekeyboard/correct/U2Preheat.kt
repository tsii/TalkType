package de.voicekeyboard.correct

/**
 * T24d (PLAN_V0147 §17.1, Gleis 3 B4): U2 preheat state machine — the ONE
 * owner of the on-demand TEXT engine lifecycle (the one-engine relaxation
 * of §13: alongside the resident ASR engine).
 *
 *  - Trigger: [onTextKey] — the FIRST text-committing key of the session
 *    (the service feeds text keys + SPACE, never Enter and never the
 *    dictation key, §17.1). Repeated keys never double-load.
 *  - Start gates: set downloaded, field eligible, correction switch ON,
 *    dictation IDLE, ASR engine not mid-load, RAM guard freeBytes >=
 *    [MIN_FREE_BYTES] — refusals retry per key up to [MAX_RAM_RETRIES]
 *    per session, then the session waits (budget resets at
 *    [onSessionStart]).
 *  - Load + ONE dummy 8-word warm-up inference on the injected worker
 *    ([runAsync]); UI hops over [postToMain]. A load failure returns to
 *    IDLE (retry on the next key), never propagates (crash net, code-only
 *    log, rule 9).
 *  - UNLOAD: 60 s idle ([armIdleUnload], re-armed on activity) or an
 *    ineligible field ([onIneligibleField]) or [shutdown] (service destroy).
 *  - State/counters for the u2 debug fields (B5/T24e): codes only.
 */
internal class U2Preheat(
    private val loadAndWarmUp: () -> Unit,
    private val unload: () -> Unit,
    private val freeBytes: () -> Long,
    private val setDownloaded: () -> Boolean,
    private val eligibleField: () -> Boolean,
    private val correctionSwitchOn: () -> Boolean,
    private val dictationIdle: () -> Boolean,
    private val asrLoading: () -> Boolean,
    private val postToMain: (run: () -> Unit) -> Unit,
    private val runAsync: (run: () -> Unit) -> Unit,
    private val scheduleIdleUnload: (delayMs: Long, run: () -> Unit) -> Unit,
    private val cancelIdleUnload: () -> Unit,
) {
    enum class State { IDLE, LOADING, READY, UNLOADING }

    var state: State = State.IDLE
        private set

    /** RAM refusals of THIS session (u2 debug counter). */
    var ramRefusals: Int = 0
        private set

    /** Successful load+warm-ups (u2 debug counter). */
    var startedLoads: Int = 0
        private set

    private var gaveUpThisSession = false

    /** First text-committing key of the session (§17.1). */
    @Synchronized
    fun onTextKey() {
        when (state) {
            State.IDLE -> maybeStart()
            State.READY -> armIdleUnload() // activity re-arms the 60 s timer
            State.LOADING, State.UNLOADING -> Unit
        }
    }

    /** New editing session: retry budget + counter reset (§17.1 "wait for
     *  next session" — the refusals are a per-session budget). */
    @Synchronized
    fun onSessionStart() {
        gaveUpThisSession = false
        ramRefusals = 0
    }

    /** onStartInputView into an ineligible field (§17.1 UNLOAD). */
    @Synchronized
    fun onIneligibleField() {
        if (state == State.READY || state == State.LOADING) {
            unloadNow()
        }
    }

    @Synchronized
    fun shutdown() {
        if (state != State.IDLE) {
            unloadNow()
        }
    }

    val isReady: Boolean
        get() = state == State.READY

    // ------------------------------------------------------------ internals

    private fun maybeStart() {
        if (gaveUpThisSession) return
        if (!setDownloaded() || !eligibleField() || !correctionSwitchOn()) return
        if (!dictationIdle() || asrLoading()) return
        if (freeBytes() < MIN_FREE_BYTES) {
            // §17.1: retry per key up to MAX_RAM_RETRIES, then the session waits
            if (ramRefusals < MAX_RAM_RETRIES) {
                ramRefusals++
                if (ramRefusals == MAX_RAM_RETRIES) gaveUpThisSession = true
            }
            return
        }
        state = State.LOADING
        startedLoads++
        runAsync {
            val ok = runCatching { loadAndWarmUp() }.isSuccess
            postToMain {
                if (ok) {
                    state = State.READY
                    armIdleUnload()
                } else {
                    // failed load: back to IDLE, the next key retries (§17.1)
                    state = State.IDLE
                }
            }
        }
    }

    private fun armIdleUnload() {
        cancelIdleUnload()
        scheduleIdleUnload(IDLE_UNLOAD_MS) { unloadNow() }
    }

    @Synchronized
    private fun unloadNow() {
        cancelIdleUnload()
        state = State.UNLOADING
        runAsync {
            runCatching { unload() }
            postToMain { state = State.IDLE }
        }
    }

    companion object {
        /** §17.1 RAM guard: the warm-up never starts below this free memory. */
        const val MIN_FREE_BYTES: Long = 400L * 1024 * 1024

        /** §17.1: retry on the next keypress up to 3x, then wait for the session. */
        const val MAX_RAM_RETRIES = 3

        /** §17.1/§13: UNLOAD 60 s idle. */
        const val IDLE_UNLOAD_MS: Long = 60_000L
    }
}
