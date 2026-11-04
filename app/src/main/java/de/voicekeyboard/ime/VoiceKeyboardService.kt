package de.voicekeyboard.ime

import android.content.Intent
import android.inputmethodservice.InputMethodService
import android.text.InputType
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import de.voicekeyboard.R
import de.voicekeyboard.asr.ParakeetAsrEngine
import de.voicekeyboard.audio.AudioLevelListener
import de.voicekeyboard.audio.AudioRecordSource
import de.voicekeyboard.audio.AudioRouter
import de.voicekeyboard.capture.CaptureForegroundService
import de.voicekeyboard.editor.InputConnectionSink
import de.voicekeyboard.editor.OwnershipTracker
import de.voicekeyboard.keyboard.KeyboardView
import de.voicekeyboard.layouts.KeyActions
import de.voicekeyboard.layouts.LayoutRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Language switching cycle (E9.1/E9.2, LANGUAGE_EMOJI_DECISIONS.md) — pure JVM logic,
 * no Android types (unit-testable in LanguageCycleTest).
 *
 * Canonical order = KONZEPT §2.2 / LAYOUT_REQUIREMENTS matrix.
 * Persistence: file "vk_layout", keys active_languages (comma-separated,
 * ordered, no StringSet) and current_language.
 */
object LanguageCycle {

    /** Canonical order of the 15 layout IDs (KONZEPT §2.2). */
    val CANONICAL: List<String> = listOf(
        "de-de", "en-us", "fr-fr", "es-es", "it-it", "pt-pt", "nl-nl", "pl-pl",
        "tr-tr", "ru-ru", "cs-cz", "sv-se", "da-dk", "fi-fi", "el-gr",
    )

    /** Endonym (language name in its own language, E9.3) + ISO tag per layout. */
    val ENDONYMS: Map<String, Pair<String, String>> = mapOf(
        "de-de" to ("Deutsch" to "de-DE"),
        "en-us" to ("English (US)" to "en-US"),
        "fr-fr" to ("Français" to "fr-FR"),
        "es-es" to ("Español" to "es-ES"),
        "it-it" to ("Italiano" to "it-IT"),
        "pt-pt" to ("Português" to "pt-PT"),
        "nl-nl" to ("Nederlands" to "nl-NL"),
        "pl-pl" to ("Polski" to "pl-PL"),
        "tr-tr" to ("Türkçe" to "tr-TR"),
        "ru-ru" to ("Русский" to "ru-RU"),
        "cs-cz" to ("Čeština" to "cs-CZ"),
        "sv-se" to ("Svenska" to "sv-SE"),
        "da-dk" to ("Dansk" to "da-DK"),
        "fi-fi" to ("Suomi" to "fi-FI"),
        "el-gr" to ("Ελληνικά" to "el-GR"),
    )

    /** Default active languages (E9.1 rule 2): today's feature scope. */
    val DEFAULT_ACTIVE: List<String> = listOf("de-de", "en-us")

    /**
     * Next active language in the canonical cycle (E9.1 rule 3, wrap at the end).
     * - only one active language ⇒ null (no silent no-op, UI shows a hint)
     * - current language not in `active` ⇒ first active language
     * - order follows CANONICAL, not the input order of `active`
     */
    fun nextActiveLanguage(active: List<String>, current: String): String? {
        val ordered = CANONICAL.filter { it in active }
        if (ordered.isEmpty()) return null
        if (ordered.size == 1) return null
        val idx = ordered.indexOf(current)
        if (idx == -1) return ordered.first()
        return ordered[(idx + 1) % ordered.size]
    }

    /**
     * Start language (E9.1 rule 7): current if in active; else first active;
     * else default. The result is never empty.
     */
    fun initialLanguage(active: List<String>, current: String?): String {
        val ordered = CANONICAL.filter { it in active }
        if (ordered.isEmpty()) return DEFAULT_ACTIVE.first()
        if (current != null && current in ordered) return current
        return ordered.first()
    }

    /**
     * Parses active_languages (comma-separated): trims, discards empty strings and
     * duplicates, filters to known IDs, sorts in canonical order.
     * Empty/unknown ⇒ default (at least 1 language is always active).
     */
    fun parseActive(raw: String?): List<String> {
        if (raw.isNullOrBlank()) return DEFAULT_ACTIVE
        val ids = raw.split(',')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .filter { it in CANONICAL }
            .distinct()
        if (ids.isEmpty()) return DEFAULT_ACTIVE
        return CANONICAL.filter { it in ids }
    }

    /** Serializes in canonical order, comma-separated (E9.1 rule 7). */
    fun serializeActive(active: List<String>): String =
        CANONICAL.filter { it in active }.joinToString(",")

    /** Endonym for the status display (E9.1 rule 5 / M8b: plain text, no ISO code). */
    fun endonym(id: String): String = ENDONYMS[id]?.first ?: id
}

/**
 * T15 (Opus option H): pure insets seam of [VoiceKeyboardService.onComputeInsets] —
 * fills an [InputMethodService.Insets] with the LatinIME pattern values:
 * contentTopInsets = visibleTopInsets = [keyboardTop] (the headroom band above is
 * NOT IME content — app text may run behind it), touchableInsets = REGION with
 * (0, keyboardTop, width, keyboardBottom): touches above the keyboard (in the
 * headroom) pass through to the app. T15b (Chrome-Enter-Bug 0.1.43): [keyboardBottom]
 * = STACK bottom ([ImeRootView.keyboardBottomInWindow]), NOT root.bottom — in
 * fullscreen/resize states the root is almost display-tall and a region ending
 * at root.bottom put the keyboard's footer/Enter row touch-dead. Separated as a
 * top-level function so the values are testable WITHOUT a running service.
 */
internal fun applyImeInsets(
    insets: InputMethodService.Insets,
    keyboardTop: Int,
    keyboardBottom: Int,
    width: Int,
): InputMethodService.Insets {
    insets.contentTopInsets = keyboardTop
    insets.visibleTopInsets = keyboardTop
    insets.touchableInsets = InputMethodService.Insets.TOUCHABLE_INSETS_REGION
    insets.touchableRegion.set(0, keyboardTop, width, keyboardBottom)
    return insets
}

/**
 * T17 (Enter in multiline fields, REVIEW_STAGE1 VK-08; VK-R1 Review V0.1.44):
 * pure seam of [VoiceKeyboardService.performEditorAction] — mirrors AOSP
 * (LatinIME / InputMethodService.sendDefaultEditorAction): only a null
 * EditorInfo, IME_ACTION_NONE, MULTILINE input or IME_FLAG_NO_ENTER_ACTION mean
 * NEWLINE; every other action — including IME_ACTION_UNSPECIFIED (0), the
 * editor's DEFAULT action — is routed to the app via performEditorAction.
 * Top-level so the EditorInfo matrix is testable WITHOUT a running service
 * (EditorActionEnterTest).
 */
internal fun applyEnterKey(ic: InputConnection, info: EditorInfo?) {
    val action = info?.imeOptions?.and(EditorInfo.IME_MASK_ACTION) ?: EditorInfo.IME_ACTION_NONE
    val treatAsNewline = info == null ||
        action == EditorInfo.IME_ACTION_NONE ||
        (info.inputType and EditorInfo.TYPE_TEXT_FLAG_MULTI_LINE) != 0 ||
        (info.imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0
    if (treatAsNewline) {
        ic.commitText("\n", 1)
    } else {
        ic.performEditorAction(action)
    }
}

/**
 * The IME service: owns the InputConnection access, creates ImeRootView
 * and since M4c-2 wires the dictation operation (push-to-talk via the
 * mic key of the status row).
 *
 * Order at start (TECH_FACTS §4.1, while-in-use):
 * permission check → FGS → Coordinator.start().
 * When the IME is hidden (onWindowHidden/onFinishInputView) every
 * running recording ends in a controlled way — no recording from the background.
 * Logs: tag "VK-IME", only states/error codes, never text or audio.
 */
class VoiceKeyboardService : InputMethodService(), KeyboardView.Listener {

    companion object {
        private const val TAG = "VK-IME"
        private const val MODEL_DIR_NAME = "models"
        private const val REQUEST_DICTATION_PERMISSIONS = 4101

        /**
         * F13a/A1: start deadline (watchdog). Encoder loading takes seconds; the
         * freeze report names 10–15 s — after that the start counts as failed,
         * the status row receives the deadline message and startingUp is released.
         */
        private const val START_DEADLINE_MS = 15_000L

        /**
         * F28c: FGS-ready deadline. startForeground must complete BEFORE the first
         * AudioRecord.startRecording(); longer than this much
         * (ms) without FGS-ready ⇒ no mic start, visible error.
         * 4 s (F31b/REVIEW-P2: was 2 s) lies clearly above the FGS start deadline
         * even at COLD START (first process start, startForeground > 2 s possible)
         * and far below the start deadline [START_DEADLINE_MS].
         */
        private const val FGS_READY_TIMEOUT_MS = 4_000L

        /**
         * F13e: PURE mapping error code → string key (R constant,
         * JVM-testable without an Android runtime — see ConsumerFaultTest).
         * null = unknown ⇒ generic fallback in mapVisibleError.
         */
        internal fun mapErrorTextRes(raw: String): Int? = when {
            raw.startsWith("editor_sync_timeout") -> R.string.dictation_error_editor_busy
            else -> null
        }
        private const val PERMISSION_PREFS = "vk_permissions"
        private const val PERMISSION_DENIED_FOREVER_PREFIX = "denied_forever_"

        /** E9.1 rule 7: persistence file vk_layout (MODE_PRIVATE). */
        const val LAYOUT_PREFS = "vk_layout"
        const val KEY_ACTIVE_LANGUAGES = "active_languages"
        const val KEY_CURRENT_LANGUAGE = "current_language"
    }

    private lateinit var repository: LayoutRepository
    private var rootView: ImeRootView? = null

    // Dictation operation (M4c-2)
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var coordinator: DictationCoordinator? = null

    /**
     * F30 (harness docs option C): debug-only AudioSource entry point (field).
     * In the release build the field ALWAYS stays null (the debug source folder
     * FakeWavAudioSource is not compiled there) → AudioRecordSource,
     * production unchanged. In debug, e. g. [de.voicekeyboard.audio.
     * FakeWavAudioSource.build] builds the scenario and sets this field before the
     * coordinator is built (deterministic WAV feeding, 10-ms blocks).
     */
    var debugAudioSourceFactory: (() -> de.voicekeyboard.audio.AudioSource)? = null

    /** AP1 (PLAN_C2_FABLE_R2 §1.2): debug-only engine override — set by
     *  [de.voicekeyboard.debug.DebugHooks] (debug.vk.engine=stub:<alpha_ms_pro_s>:<base_ms>),
     *  read in buildCoordinator. Release: stays null → real ParakeetAsrEngine. */
    var debugEngineFactory: (() -> de.voicekeyboard.asr.AsrEngine)? = null
    private var router: AudioRouter? = null
    private val ownership = OwnershipTracker()
    private var fgsRunning = false

    /// AP1 (PLAN_C2_FABLE_R2 §1.2): VK-SESS counters (debug log only).
    /** A3 (PLAN_V0133 §A3): segment session log state — baseline per mic DOWN;
     *  pure decision (SegmentSessionLog, JVM-testable), no raw sessLastSeg field anymore. */
    private val segSessionLog = SegmentSessionLog()
    private var sessLostShown = false

    /**
     * AP1 (PLAN_C2_FABLE_R2 §1.2): session log tag VK-SESS for the C2
     * simulation — ONLY state/cause codes and numbers, NEVER text (rule 9).
     * The script reads via `logcat -d -s VK-SESS` (step 8/9).
     */
    private fun sess(cause: String, extra: String = "") {
        val c = coordinator
        android.util.Log.d(
            "VK-SESS",
            "state=${c?.state ?: "NONE"} cause=$cause" +
                " audio_s=" + java.lang.String.format(java.util.Locale.ROOT, "%.1f", c?.audioSeconds ?: 0.0) +
                " out=${outputCounter.outputChars} seg=${c?.segmentFinalizations() ?: 0}" + extra,
        )
    }

    /** F42/F44 (F41 repro, P1 — FGS leak): cause of the LAST FGS stop as a
     *  controlled code (e. g. "abort:audio_error:silence", "stop",
     *  "fgs-timeout") — codes only, never contents (rule 9). Forensics question:
     *  is the micro FGS still running — and if not, who stopped it? */
    @Volatile private var fgsStoppedBy: String = "-"

    // F28c: FGS-ready handshake — true ONLY after startForeground has completed
    // (set by CaptureForegroundService.onForegroundReady); only then may the
    // microphone be opened. Seen only by the serviceScope worker.
    // ReentrantLock/Condition instead of Object.wait/notify: those are hidden on Android
    // from the app API (they do not compile) — java.util.concurrent works.
    private val fgsReadyLock = java.util.concurrent.locks.ReentrantLock()
    private val fgsReadyCondition = fgsReadyLock.newCondition()
    private var fgsReady = false

    /** R-2: a blocking message stays until the next deliberate action. */
    @Volatile private var stickyError: String? = null

    /**
     * F13a/B1: TRANSIENT status text (start message) — strictly separated from
     * stickyError; never sticky, superseded at RECORDING/FINALIZING/newer error.
     */
    @Volatile private var transientStatus: String? = null

    /** F52: the current transient text is a HINT (StatusEvent.Hint), not
     *  the start message — basis for status_res (rule 9: codes only). */
    @Volatile private var transientIsHint = false

    /** F52 (PLAN_V0130 A3): code/resource name of the last shown
     *  status row text (status_res, never the text itself — rule 9). */
    @Volatile private var statusResValue = "idle"

    /** AP3 (hint:<res-name>, review A3-P2): resource NAME of the last hint
     *  event (code, never text — rule 9); status_res shows "hint:<res-name>". */
    @Volatile private var lastHintRes: String? = null

    /** Timestamp of the transient text for the clarity rule — elapsedRealtime
     *  (monotonic INCL. deep sleep; uptimeMillis pauses in deep sleep and would
     *  soften the 15-s start deadline and the clarity comparison, review F13c P2). */
    @Volatile private var transientSetAtMs: Long = 0L

    /** Timestamp of the last observed ERROR (sticky/visible) — clarity rule. */
    @Volatile private var lastErrorSeenAtMs: Long = 0L

    /** F13a: lookup deadline for the asynchronous start (watchdog coroutine, no thread). */
    private var startDeadlineJob: kotlinx.coroutines.Job? = null

    /** F50 (PLAN_V0130 A2, V4): the active start job — the deadline and a
     *  supersede cancel it; invariant: at any point at most
     *  ONE start coroutine runs (no zombie dictation, no double start). */
    @Volatile private var startJob: kotlinx.coroutines.Job? = null

    /**
     * AP7-W1 (Fable §0.1.5/0.1.6, W1b fix): the hold gate is GENERATION-BOUND
     * — pure logic in [HoldGate] (JVM-testable), the service holds only the
     * instance + the touch generation markers (all callers run on main).
     * [begin]/[end] are the ONLY setters of activeGen — a session end of a
     * PRECEDING session pulled up asynchronously can no longer falsely open
     * the gate of a NEWER gesture.
     */
    private val holdGate = HoldGate()
    private var barTouchGen = 0L    // status row mic: DOWN marker for the UP
    private var footerTouchGen = 0L // footer-row mic (onMicHoldStart/End)

    /** AP7-W1: gate_open_at_stop — sample DIRECTLY before the stop (0/1; -1 = no stop). */
    @Volatile private var gateOpenAtStopValue = -1

    // AP7-E1: editor baseline of the ACTIVE session (pkg+fieldId) + last
    // onStartInput state — restartInput() delivers restarting=true with an
    // IDENTICAL field: NOT an editor change (re-baseline instead of abort).
    @Volatile private var lastEditorPkg: String? = null
    @Volatile private var lastEditorFieldId: Int = 0
    @Volatile private var sessionEditorPkg: String? = null
    @Volatile private var sessionEditorFieldId: Int = 0

    /** AP7-W1: endHold wrapper — pure decision + VK-SESS log (codes only). */
    private fun endHold(gen: Long, cause: String) {
        val ok = holdGate.end(gen)
        sess("gate reset gen=$gen cause=$cause ok=${if (ok) 1 else 0} active=${holdGate.activeGen}")
    }

    /** B2 (PLAN §6.2 option A): warning hint "upper limit in 2 min" was already
     *  shown for THIS session (holdWarnDue flag); reset only via the
     *  session end paths (onDictationStopped/abort/onAborted). */
    @Volatile private var holdWarnShown = false

    /** B2: periodic warning check (coroutine, pattern startDeadlineJob) —
     *  5-s tick over the SESSION audio (coordinator.audioMs); terminates
     *  itself when the session is inactive. */
    private var holdWarnJob: kotlinx.coroutines.Job? = null

    /** True if the start deadline expired before the start completed. */
    @Volatile private var startAbandoned = false

    /** F13a: one-time flag "completed success" for R-2b (set by onDictationStopped). */
    @Volatile private var successJustFinished = false

    /**
     * F11 P1: The start now runs in the `serviceScope` because `engine.load()`
     * (encoder ~652 MB, native) produces the same ANR class in the touch
     * listener as the previously fixed model hash. The two flags keep the gesture
     * closed during loading: second tap => ignore, release => catch up.
     */
    @Volatile private var startingUp = false
    @Volatile private var cancelStartRequested = false

    /**
     * D10-rest §1: release during the asynchronous start — kind of catching up.
     * UP (false) = stop with finale; ACTION_CANCEL (true) = abort without finale.
     */
    @Volatile private var cancelStartIsAbort = false

    /**
     * D10-rest §3: character output of the ACTIVE dictation session (numbers only,
     * rule 9 — never text content). Basis for the "No text recognized" toast and
     * the commit check mark; reset at every mic DOWN.
     */
    private val outputCounter = de.voicekeyboard.ime.DictationOutputCounter()

    /** True if a STOP with final decode was initiated (no abort). */
    @Volatile private var finalResultExpected = false

    /**
     * W1 (vc34, debugger recommendation): engine prewarming already ATTEMPTED
     * at the first onStartInputView — exactly ONCE per service lifetime
     * (decision [preloadDue]); a failed attempt is not
     * repeated and does not block the dictation start (the lazy fallback in the
     * coordinator start stays).
     */
    private var preloadTried = false

    /**
     * D10 §3 (b): pre-booked target layout for the NEXT mic DOWN.
     * In working memory only (no sneaky current_language before application,
     * no stale pending after a process restart). It is applied before the start of
     * the new capture — the running session stays completely untouched.
     */
    private var pendingLayoutId: String? = null

    override fun onCreate() {
        super.onCreate()
        // T15 fix (emu 5564 re-measure, PLAN_T13_T15.md): the IME window/decor paints
        // the theme windowBackground (Theme.VoiceKeyboard) over the WHOLE window —
        // also over the transparent T15 headroom band above the keyboard (our root
        // leaves it free: InsetDrawable + paddingTop, stack below paddingTop;
        // structural proof in ImeRootViewLayoutTest). The band must show the APP
        // (idle luminance 1296..1472 == app background, no head bar).
        // RUNTIME-ONLY (no theme/manifest change — SetupActivity shares
        // Theme.VoiceKeyboard and keeps its background). API choice:
        // Window.setBackgroundDrawable — canonical, assigns the decor background
        // even BEFORE the decor is inflated (decorView.background would need the
        // decor to exist). getWindow() creates the IME Dialog lazily; the framework
        // reuses the same instance.
        getWindow()?.window?.setBackgroundDrawable(
            android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT),
        )
        // S12-01-FIX: pass DEVICE RAM (ActivityManager.totalMem) to the ASR
        // config — not the Java heap (regression S12-01: maxMemory triggered
        // the 2-GB capping on every phone ⇒ intra=2 instead of 4).
        val memInfo = android.app.ActivityManager.MemoryInfo()
        (getSystemService(android.content.Context.ACTIVITY_SERVICE) as android.app.ActivityManager)
            .getMemoryInfo(memInfo)
        de.voicekeyboard.asr.AsrThreadConfig.deviceRamMb = memInfo.totalMem / (1024L * 1024L)
        repository = LayoutRepository(applicationContext)
        router = AudioRouter(applicationContext).apply {
            errorListener = de.voicekeyboard.audio.AudioErrorListener { _, _ -> updateStatus() }
            routeListener = { updateStatus() }
        }
    }

    override fun onCreateInputView(): View {
        val view = ImeRootView(this)
        view.keyboardView.listener = this
        // E9.1 rule 5/7: layout comes from current_language (vk_layout),
        // no longer fixed de-de; fallback via loadOrFallback (de-de/en-us).
        view.keyboardView.setLayout(
            repository.loadOrFallback(currentLanguage()),
            resources.displayMetrics.widthPixels,
        )
        // E9.4/V2 (M8b-2/V2): load the emoji asset; insertion only via onTextInput (existing path).
        // Return button (V2 §3): one tap → onDismissRequested → hideEmojiPanel; idempotent,
        // no text commit, no focus request. The panel stays open after insertion.
        view.emojiPanel.listener = object : de.voicekeyboard.keyboard.EmojiPanelView.Listener {
            override fun onEmojiSelected(emoji: String) {
                // V2 §3.6: state ≠ IDLE blocks insertion; the message stays,
                // the return button is never disabled.
                val c = coordinator
                if (c != null && c.state != de.voicekeyboard.dictation.DictationState.IDLE) {
                    postEvent(hintEvent(R.string.emoji_blocked_recording))
                    return
                }
                onTextInput(emoji) // existing write path: ic.commitText(text, 1)
            }

            override fun onDismissRequested() {
                view.emojiPanel.closeAndResetPage() // category stays, page → 1 (V2 §3.3)
                view.hideEmojiPanel()
            }
        }
        view.backToKeyboardButton.setOnClickListener {
            view.emojiPanel.closeAndResetPage()
            view.hideEmojiPanel()
        }
        try {
            val json = assets.open("emoji/emoji_v1.json").bufferedReader().use { it.readText() }
            view.emojiPanel.loadFromJson(json)
        } catch (e: Exception) {
            Log.w(TAG, "emoji asset load failed: ${e.javaClass.simpleName}")
        }
        // Push-to-talk: touch-down starts, touch-up/cancel stops (hold semantics)
        view.micButton.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    v.performClick()
                    // AP7-W1: ONE setter source begin() — new, monotone generation.
                    barTouchGen = holdGate.begin("bar-hold")
                    sess("gate set gen=$barTouchGen src=bar-hold")
                    onMicDown()
                    true
                }
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                    // AP7-W1: UP/CANCEL ends ONLY its own generation.
                    endHold(barTouchGen, if (event.actionMasked == android.view.MotionEvent.ACTION_UP) "up" else "cancel")
                    onMicUp()
                    true
                }
                else -> false
            }
        }

        // P0 (2026-11-01): 🐞 lives FIXED in the statusRow (ImeRootView.debugButton,
        // 48 dp, right) — previously dynamic addView (fragile/unsecured).
        // Only the listener here: one tap = export.
        view.debugButton.setOnClickListener { onDebugInfoRequested() }
        rootView = view
        return view
    }

    /**
     * T15b (Chrome-Enter-Bug 0.1.43, measured corrupt state "Requested w=1080
     * h=2204" + contentTopInsets=1371 + touchableRegion ending 700 px under the
     * keyboard): this product has NO extract/fullscreen UI (no ExtractEditText
     * usage; status band/mic/design presuppose the normal window). Fullscreen
     * evaluation made the IME window almost display-tall and our onComputeInsets
     * anchored the region at that oversized root — footer + Enter row became
     * touch-dead. Unconditional false suppresses the h=2204 case completely.
     */
    override fun onEvaluateFullscreenMode(): Boolean = false

    /**
     * T15 (Opus option H, LatinIME pattern — docs/PLAN_T13_T15.md step 3): the IME
     * window is headroomPx taller than the keyboard (ImeRootView paddingTop, band
     * transparent via InsetDrawable). This override tells the framework where the
     * keyboard REALLY begins, so the app keeps its touches and gestures in the
     * headroom band:
     *  - contentTopInsets = visibleTopInsets = keyboard top ([ImeRootView.keyboardTopInWindow]):
     *    the area ABOVE is not counted as IME content — the app text may run behind it.
     *  - touchableInsets = TOUCHABLE_INSETS_REGION with region (0, keyboardTop,
     *    width, keyboardBottom): touches in the headroom pass through to the app,
     *    the gesture stays in the window it started in.
     * T15b hardening (both bugs measured on Chrome):
     *  - super FIRST — its values stay untouched as the fallback (no more blind
     *    overwrite with root-derived numbers).
     *  - Guard: no laid-out root with a real keyboard surface (root.height == 0,
     *    stack bottom <= top — e.g. fullscreen/resize or before the first layout)
     *    ⇒ super values stand, no insets corruption.
     *  - Region/insets anchored at the STACK ([keyboardTopInWindow]/
     *    [ImeRootView.keyboardBottomInWindow]), NOT at root.bottom.
     */
    override fun onComputeInsets(outInsets: InputMethodService.Insets) {
        super.onComputeInsets(outInsets)
        val root = rootView ?: return
        if (!root.keyboardSurfaceValid) return
        applyImeInsets(
            insets = outInsets,
            keyboardTop = root.keyboardTopInWindow(),
            keyboardBottom = root.keyboardBottomInWindow(),
            width = root.width,
        )
    }

    override fun onStartInput(info: EditorInfo, restarting: Boolean) {
        super.onStartInput(info, restarting)
        if (restarting) coordinator?.noteRestartInput() // AP3: restart_inputs counter
        // AP7-E1 (Fable §0.2.5 P1, vc33-T1): the app's restartInput() on our
        // FIRST partial write is NOT an editor change — before, the
        // unheeded restarting=true killed the session (onEditorChanged →
        // abort("editor_change"), user death cause ~2 s). Pure decision:
        val sessionActive = coordinator?.let {
            it.state == de.voicekeyboard.dictation.DictationState.RECORDING ||
                it.state == de.voicekeyboard.dictation.DictationState.FINALIZING
        } ?: false
        when (
            editorRestartAction(
                restarting = restarting,
                sessionActive = sessionActive,
                baselinePkg = sessionEditorPkg,
                baselineFieldId = sessionEditorFieldId,
                infoPkg = info.packageName,
                infoFieldId = info.fieldId,
            )
        ) {
            EditorRestartAction.REBASELINE -> {
                // Session stays RECORDING; re-anchor possession. If the app
                // deleted/changed our text in the restart: controlled
                // abort (no ghost writing into a changed editor).
                val kept = coordinator?.rebaselineOwnership() ?: true
                // AP7-E1c: length diagnostics (numbers only, rule 9) — exp/act
                // immediately show whether the re-baseline decided against a stale
                // or a foreign editor state.
                sess(
                    "input restarting=$restarting rebaseline=1 kept=${if (kept) 1 else 0}" +
                        " exp=${coordinator?.lastRebaseExpLen ?: -1} act=${coordinator?.lastRebaseActLen ?: -1}",
                )
                if (!kept) {
                    if (
                        rebaseRetryDue(
                            kept = kept,
                            actLen = coordinator?.lastRebaseActLen ?: -1,
                            sessionActive = coordinator?.isActive ?: false,
                        )
                    ) {
                        // AP7-E1d (vc33 acceptance, restart:20 t=12 s): act=-1 =
                        // read window into the re-bind — the getextBeforeCursor
                        // caught the dying old InputConnection. That is
                        // NOT a loss proof. ONE 150-ms retry (not
                        // blocking — the session keeps running RECORDING for that
                        // time; the decision falls over the THEN current state,
                        // new restarts change it along).
                        sess("rebase retry=1 due=150ms")
                        // AP7-E1d: open the baseline IMMEDIATELY — the app's
                        // re-bind callback arrives within the 150 ms and must be
                        // consumed SILENTLY (otherwise: lost ⇒ silent
                        // write stop behind a saved session, H-E2).
                        coordinator?.openRebindBaseline()
                        mainHandler.postDelayed({
                            val c = coordinator ?: return@postDelayed
                            val kept2 = c.rebaselineOwnership()
                            sess(
                                "rebase retry kept=${if (kept2) 1 else 0}" +
                                    " exp=${c.lastRebaseExpLen} act=${c.lastRebaseActLen}",
                            )
                            if (!kept2) {
                                abortDictation("editor_rebaseline_lost")
                                postEvent(hintEvent(R.string.editor_rebaseline_lost_hint))
                            }
                        }, 150L)
                    } else {
                        abortDictation("editor_rebaseline_lost")
                        // AP7-E1-P2 (mini-UX): transient hint instead of a silent
                        // sticky end — no toast, hintEvent seam (res name in
                        // status_res, rule 9).
                        postEvent(hintEvent(R.string.editor_rebaseline_lost_hint))
                    }
                }
            }
            EditorRestartAction.ABORT -> {
                sess("input restarting=$restarting abort=editor_change")
                // F28b2: editor change = session end event (who: editor).
                noteMicRel("editor")
                closeEmojiPanelIfVisible()
                // Editor change: discard possession — no writing into the old context
                coordinator?.onEditorChanged()
            }
            EditorRestartAction.NOTHING -> {
                // No active session: cosmetic old path (idle no-op).
                noteMicRel("editor")
                closeEmojiPanelIfVisible()
                coordinator?.onEditorChanged()
            }
        }
        // AP7-E1: last editor state for the NEXT session baseline.
        lastEditorPkg = info.packageName
        lastEditorFieldId = info.fieldId
    }

    /** V2 §3.7 (pulled out of onStartInput): close the panel — no reappearing
     *  with stale touch events in the next field. */
    private fun closeEmojiPanelIfVisible() {
        rootView?.let { v ->
            if (v.isEmojiPanelVisible) {
                v.emojiPanel.closeAndResetPage()
                v.hideEmojiPanel()
            }
        }
    }

    override fun onStartInputView(info: EditorInfo, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        // T18: re-request insets at EVERY show — the insets listener fires only
        // on CHANGE, so a stale 0 (app temporarily hid the nav bars) survives
        // until the view is re-created; requestApplyInsets closes the visibility
        // race and late insets (PLAN_T18_HEIGHT §2).
        rootView?.requestApplyInsets()
        // T18-A evidence line (rule 9: numbers ONLY): start state next to the
        // apply state of the same tag — the comparison proves the nav-padding cause.
        rootView?.let {
            Log.i(
                INSETS_LOG_TAG,
                insetsEvidenceLine("start", it.rootWindowInsets, it.paddingBottom, it.height),
            )
        }
        // W1 (vc34, debugger recommendation): prewarm the engine at the
        // FIRST visible appearance of the IME — single flight in the coordinator; load() needs neither
        // mic nor FGS. Exactly ONCE per lifetime ([preloadTried], pure
        // decision [preloadDue]); a failed preload does NOT block the
        // later dictation start. ONLY in the worker scope — load is expensive
        // (F11 P1: ~4.5-s cold start, never main thread/ANR).
        if (preloadDue(preloadTried)) {
            preloadTried = true
            val c = coordinator ?: buildCoordinator().also { coordinator = it }
            serviceScope.launch { c.preload() }
        }
        updateStatus()
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        // F28b2: IME disappears = session end event (who: hidden).
        noteMicRel("hidden")
        // B2 (PLAN_V0133 §B2): editor change stops the backspace repeat
        // timer deterministically (no timer after editor change/detach).
        rootView?.keyboardView?.cancelBackspaceRepeat()
        // V2 §3.7: hiding the IME resets the mode back to keyboard.
        rootView?.let { v ->
            if (v.isEmojiPanelVisible) {
                v.emojiPanel.closeAndResetPage()
                v.hideEmojiPanel()
            }
        }
        // While-in-use (TECH_FACTS §4.1): the recording ends as soon as the IME disappears
        stopDictationIfRunning()
        super.onFinishInputView(finishingInput)
    }

    override fun onWindowHidden() {
        noteMicRel("hidden")
        stopDictationIfRunning()
        super.onWindowHidden()
    }

    override fun onDestroy() {
        // N-3: NO stopDictationIfRunning() (would start a finale worker that
        // is killed immediately) — direct abort+close, order guaranteed.
        coordinator?.abort("ime_destroy")
        stopFgs("ime_destroy")
        router?.stopObserving()
        // C-4: close the engine sessions (~863 MB native), then cancel the scope.
        coordinator?.shutdown()
        coordinator = null
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onUpdateSelection(
        oldSelStart: Int, oldSelEnd: Int, newSelStart: Int, newSelEnd: Int,
        candidatesStart: Int, candidatesEnd: Int,
    ) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        coordinator?.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd)
        // AP1: VK-SESS — report possession loss only on CHANGE (no spam per callback).
        if (ownership.ownershipLost && !sessLostShown) {
            sess("sel lost=1")
            sessLostShown = true
        } else if (!ownership.ownershipLost) {
            sessLostShown = false
        }
    }

    // ---- Push-to-talk ----

    private fun onMicDown() {
        sess("mic_down") // AP1: VK-SESS — finger down (numbers/codes only)
        // A3 (PLAN_V0133 §A3): new dictation session — open the segment baseline NEW.
        // Otherwise the counter state of the ENDED session (seg=1) logged an
        // old-artifact `seg_final` against the new counter 0 even BEFORE `start_ok`
        // (finding 18:56, PLAN §0.1) — no belated mini-finale.
        segSessionLog.onMicDown()
        // F50 (PLAN_V0130 A2, V4): start serialization over ONE pure
        // decision (micStartGate) — at any point at most ONE start coroutine
        // runs; an expired one is cancelled, its result
        // discarded (no zombie dictation via the active-CAS duel, V4).
        when (
            micStartGate(
                startingUp = startingUp,
                sessionRecording = coordinator?.state == de.voicekeyboard.dictation.DictationState.RECORDING,
                oldStartJobActive = startJob?.isActive == true,
            )
        ) {
            MicStartGateAction.IGNORE -> return // F11 P1 / running session
            MicStartGateAction.SUPERSEDE -> {
                // F50: detach the remainder of an expired start — cancel +
                // zombie abort + FGS released; the fresh start below builds a
                // NEW coordinator (no shared active instance old/new).
                startJob?.cancel()
                coordinator?.abort("start_superseded")
                coordinator = null
                stopFgs("superseded")
            }
            MicStartGateAction.FRESH_START -> Unit
        }
        // D10 §3: apply the pre-booked layout BEFORE the start of the new capture
        // (only in the IDLE restart; a running start is not redirected).
        if (coordinator?.state == de.voicekeyboard.dictation.DictationState.IDLE) {
            applyPendingLayoutBeforeCapture()
        }
        // V2 §3.6: mic press with the panel open: close the emoji first, then PTT —
        // no operable emoji grid above a recording.
        rootView?.let { v ->
            if (v.isEmojiPanelVisible) {
                v.emojiPanel.closeAndResetPage()
                v.hideEmojiPanel()
            }
        }
        stickyError = null // R-2: a deliberate new action clears the blocking message
        lastErrorSeenAtMs = 0L // F13a: clarity timestamp fresh per session
        transientStatus = null
        transientSetAtMs = 0L
        successJustFinished = false
        outputCounter.reset() // D10-rest §3: verdict per recording (F12: never after-effecting)
        if (!ensurePermissions()) return
        // C-2: the model must be ready BEFORE FGS/coordinator start — no
        // silent microphone above a non-functioning pipeline.
        // F11 P0: isReadyQuick instead of isReady — the full check over ~670 MB on the
        // main thread produced an ANR of the IME (mic tap hung for 5 s).
        val modelDirFile = java.io.File(filesDir, MODEL_DIR_NAME)
        val store = de.voicekeyboard.model.ModelStore(modelDirFile)
        if (!store.isReadyQuick()) {
            postEvent(StatusEvent.Error(getString(R.string.dictation_error_model_absent)))
            Log.i(TAG, "model not ready; status=${store.statusQuick()}")
            return
        }
        startFgs()
        // F11 P1: engine.load() loads the ~652-MB encoder natively and previously ran
        // synchronously in the touch listener (same ANR class as the ModelStore hash).
        // The start works in the serviceScope, the UI work afterwards on the main thread.
        // F13a/B1: The start message is TRANSIENT — it does NOT land in stickyError
        // and thus masks no later errors/states anymore.
        postEvent(StatusEvent.Starting(getString(R.string.dictation_starting)))
        startingUp = true
        // F50 (V4): startAbandoned is set ONLY by the deadline owner — before, EVERY
        // DOWN lifted the flag, so an expired start continued as valid
        // (zombie candidate). A fresh DOWN supersedes instead (above).
        // F13a: cooperative start deadline as a lookup timer (coroutine, no thread,
        // blocks nothing): if the start still runs after the deadline, show the
        // message and release startingUp so the next mic press works again.
        startDeadlineJob?.cancel()
        startDeadlineJob = serviceScope.launch {
            kotlinx.coroutines.delay(START_DEADLINE_MS)
            kotlinx.coroutines.withContext(Dispatchers.Main) {
                if (startingUp) {
                    startingUp = false
                    startAbandoned = true
                    cancelStartRequested = false
                    cancelStartIsAbort = false
                    // F50: the deadline CANCELS the start job (before: only flags — the
                    // coroutine kept running and could start a zombie
                    // dictation after loading; the abandoned flag was only the fallback).
                    startJob?.cancel()
                    transientStatus = null
                    transientSetAtMs = 0L
                    postEvent(StatusEvent.Error(getString(R.string.dictation_start_too_long)))
                }
            }
        }
        // B2 (PLAN §6.2 option A): periodic warning check — from 13 min of session
        // audio (780 s = upper limit 900 s minus 2 min lead time) EXACTLY ONCE a
        // transient hint (pure decision [holdWarnDue], pattern start-
        // deadline job: coroutine, no thread). Self-terminates when the session
        // is inactive; flag [holdWarnShown] reset only at session end.
        holdWarnJob?.cancel()
        holdWarnShown = false
        holdWarnJob = serviceScope.launch {
            while (true) {
                kotlinx.coroutines.delay(5_000)
                val c = coordinator ?: break
                if (!c.isActive) break
                if (holdWarnDue(c.audioMs, holdWarnShown)) {
                    holdWarnShown = true
                    postEvent(
                        hintEvent(R.string.hold_limit_warning),
                    ) // V7 seam: hint → transient channel (StatusRoutingTest)
                }
            }
        }
        startJob = serviceScope.launch {
            var started = false
            var outOfMemory = false
            var thrown: Exception? = null
            var mine: DictationCoordinator? = null // F50: kill only THIS instance
            try {
                // F28c: AudioRecord may only run once startForeground has
                // COMPLETED (before: startForegroundService was only staked
                // asynchronously — the mic could open first and the system
                // stop the recording silently, export #2: end after 1.0 s).
                // FGS failure (timeout) = visible error, NO audio.
                if (!awaitFgsReady(FGS_READY_TIMEOUT_MS)) {
                    Log.w(TAG, "fgs not ready in time; no audio start") // metric only
                    stopFgs("fgs-timeout")
                    withContext(Dispatchers.Main) {
                        startingUp = false
                        postEvent(StatusEvent.Error(getString(R.string.dictation_error_audio_start)))
                    }
                    return@launch
                }
                try { // C-5: never propagate audio/init throws to the touch listener
                    // AP1: debug hooks (system properties, read LIVE) per coordinator build
                    // apply — the release stub is a no-op (release sourceset pairing).
                    de.voicekeyboard.debug.DebugHooks.apply(this@VoiceKeyboardService)
                    if (coordinator == null) {
                        coordinator = buildCoordinator()
                    }
                    mine = coordinator // F50: kill ONLY this instance in the CE catch
                    // F50: cooperative supersede check — if the start job has
                    // been cancelled in the meantime (deadline/second DOWN): NO start(),
                    // otherwise zombie dictation (the second start coroutine would lose
                    // the active-CAS duel, V4).
                    if (!isActive) {
                        Log.w(TAG, "start superseded before audio start") // metric only
                        stopFgs("superseded")
                        return@launch
                    }
                    started = mine?.start() ?: false
                // P0 finding 2(b/c): ABORT observer (before: audio-error aborts
                //  like silence/stall ended the session WITHOUT UI sync — the mic
                //  stayed green, the status row text stood still). The coordinator
                //  hops the callback to main (like onStopped).
                // F44 (F41 repro P1): EVERY abort kind ALSO stops the micro FGS —
                // before, the foreground recording kept running after watchdog/error
                // aborts (~58 s in the emulator). Cause as fgs_stopped_by.
                // AP7-W1: capture the generation BEFORE the (asynchronous) callback —
                // the closure ends ONLY the gesture of its OWN session; a
                // belated abort of a predecessor session can no longer falsely open
                // the gate of the newer gesture (W1b race path).
                val genAtStart = holdGate.activeGen
                mine?.onAborted = { reason ->
                    sess("aborted:$reason") // AP1: VK-SESS — watchdog/error abort
                    syncMicUi()
                    stopFgs("abort:$reason")
                    // B2b: session end — reset the warning flag; the gate runs over
                    // the generation-bound endHold decision (AP7-W1).
                    endHold(genAtStart, "aborted")
                    holdWarnShown = false
                    updateStatus()
                }
                } catch (e: OutOfMemoryError) {
                    // A2/E5 (g): OOM is an Error — catch(Exception) does not see it.
                    outOfMemory = true
                    Log.w(TAG, "start failed: OutOfMemoryError")
                } catch (e: Exception) {
                    // S5-3/E5: raw code ONLY into the log, user-facing text in the UI
                    thrown = e
                    Log.i(TAG, "start failed: ${e.javaClass.simpleName}") // class only, no text
                }
                withContext(Dispatchers.Main) {
                    startingUp = false
                    val wantedStop = cancelStartRequested
                    val wantedAbort = cancelStartIsAbort
                    cancelStartRequested = false
                    cancelStartIsAbort = false
                    val abandoned = startAbandoned
                    startAbandoned = false
                    if (abandoned) {
                        // F13a: the deadline has expired — the belated start does NOT
                        // become a zombie dictation; FGS torn down cleanly.
                        if (started) {
                            coordinator?.abort("start_abandoned")
                        }
                        stopFgs("start_abandoned")
                        syncMicUi() // P0 finding 2(b/c): state follows the session
                        return@withContext // the status row already shows the deadline message
                    }
                    if (started) {
                        // T3 (v0.1.35): a fresh tap (TOGGLE_ON) sets NO flag —
                        // the session runs (RUN). wantedStop is now ONLY the
                        // caught-up hold release (>300 ms): it ends the fresh
                        // session CLEANLY with a finale (STOP_AFTER_START, no longer blindly
                        // KEEP_RUNNING — evidence /tmp/vk_phone_e2.log L.74-97).
                        // CANCEL (wantedAbort) stays an abort without finale (D10-rest §1).
                        when (micStartFinishedAction(wantedStop, wantedAbort)) {
                            MicStartFinishAction.ABORT -> abortDictation("michold_cancel")
                            MicStartFinishAction.STOP_AFTER_START -> stopDictationIfRunning()
                            MicStartFinishAction.RUN -> syncMicUi() // D10-Rest §2
                        }
                        updateStatus()
                        holdGate.bindSession() // AP7-W1: sessionHoldGen = active gesture
                        gateOpenAtStopValue = -1 // AP7-W1: sample fresh per session
                        // AP7-E1: remember the editor baseline of the session — all
                        // restarting=true onStartInputs WITH this field are
                        // the app's restartInput(), NOT an editor change.
                        sessionEditorPkg = lastEditorPkg
                        sessionEditorFieldId = lastEditorFieldId
                        sess("start_ok") // AP1: VK-SESS — session runs (state=RECORDING)
                        return@withContext
                    }
                    stopFgs("start-failed") // prevent FGS leak
                    sess("start_failed") // AP1: VK-SESS — start failed
                    syncMicUi() // P0 finding 2(b/c): failure => never green without a session
                    updateStatus(
                        when {
                            outOfMemory -> getString(R.string.dictation_error_out_of_memory)
                            thrown != null -> startErrorText(store, thrown!!)
                            // P1 (review F6): real reason instead of blanket "audio start"
                            else -> mapVisibleError(coordinator?.visibleError ?: "unknown")
                        },
                    )
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                // F50: the start job was cancelled (deadline or supersede). A
                // session ALREADY STARTED must not remain a zombie dictation; the
                // FGS must not leak. Re-throw: the coroutine ends as
                // "cancelled" — the deadline/supersede owners have already informed
                // the UI (no additional "Dictation start failed" text).
                if (started) mine?.abort("start_superseded")
                stopFgs("superseded")
                throw e
            } finally {
                // F13a/A1: startingUp must NEVER stick — not even on an
                // error outside the caught classes or on cancellation.
                // UI flags on main; no blocking (withContext, no runBlocking).
                // F50: NonCancellable — this finally runs ALSO in the cancelled
                // start job (before, cancellation was practically unreachable).
                kotlinx.coroutines.withContext(
                    Dispatchers.Main + kotlinx.coroutines.NonCancellable,
                ) {
                    startDeadlineJob?.cancel()
                    if (startingUp) {
                        startingUp = false
                        startAbandoned = false
                        cancelStartRequested = false
                        cancelStartIsAbort = false
                    }
                }
            }
        }
    }

    private fun onMicUp() {
        // F28b2: the user released the mic (who: up) — noted at the entry point,
        // because the startingUp branch also catches up exactly this release.
        noteMicRel("up")
        // F50a: pure decision — UP during the (cold) start is NOT a
        // regular stop, but the catch-up plus a transient hint
        // ("Release pre-noted"): the previously SILENT flag was the
        // point of confusion (C2-1). The KEEP_RUNNING design (P0 finding 2(a)) stays.
        when (micUpDuringStart(startingUp)) {
            MicUpDuringStartAction.PENDING_STOP_HINT -> {
                cancelStartIsAbort = false // caught-up release is a STOP, no abort
                cancelStartRequested = true
                postEvent(
                    hintEvent(R.string.dictation_start_pending_stop),
                )
            }
            MicUpDuringStartAction.REGULAR_STOP -> {
                stopDictationIfRunning()
                updateStatus()
            }
        }
    }

    /** F28b2: notes the session end event as a pure code + ms since
     *  session start (rule 9: no contents). Last write wins — the
     *  🐞 export shows the event that ended the session last. */
    private fun noteMicRel(kind: String) {
        val c = coordinator ?: return
        val started = c.sessionStartedAtMs
        val ms = if (started > 0L) (System.currentTimeMillis() - started).coerceAtLeast(0L) else 0L
        c.setMicRel("$kind:$ms")
    }

    /** P0 finding 2(b/c): ONE source for the visible mic state — green
     *  ONLY as long as the session is really RECORDING. Called on ALL
     *  termination paths (start end/error/deadline/stop/abort). */
    private fun syncMicUi() {
        val recording = micUiRecording(coordinator?.state)
        rootView?.let { v ->
            v.keyboardView.micRecording = recording
            v.micButton.isActivated = recording
        }
    }

    /** Fill the snapshot (rule 9: numbers/codes only) and export it (clipboard + share). */
    /**
     * AP3 (hint:<res-name>, review A3-P2): hint event over ONE seam —
     * remembers the resource NAME as a code for status_res ("hint:<res-name>",
     * never the text — rule 9). All hint sites call this function.
     */
    private fun hintEvent(resId: Int, vararg args: Any): StatusEvent.Hint {
        lastHintRes = resources.getResourceEntryName(resId)
        return StatusEvent.Hint(getString(resId, *args))
    }

    private fun onDebugInfoRequested() {
        val collector = de.voicekeyboard.debug.DebugInfoCollector(this)
        val store = de.voicekeyboard.model.ModelStore(java.io.File(filesDir, MODEL_DIR_NAME))
        val permissions = de.voicekeyboard.ime.AndroidPermissionsRequester(
            checkPermission = { checkSelfPermission(it) == android.content.pm.PackageManager.PERMISSION_GRANTED },
            shouldShowRationale = { false }, // the IME does not know the rationale question (C-3 pattern)
        )
        val snapshot = collector.collect(
            modelStore = store,
            modelVariant = de.voicekeyboard.model.ModelVariant.ONNX_CPU, // V0: only production variant (buildCoordinator)
            coordinator = coordinator,
            threadConfig = de.voicekeyboard.asr.AsrThreadConfig.forDevice(
                Runtime.getRuntime().availableProcessors(),
                de.voicekeyboard.asr.AsrThreadConfig.deviceRamMb,
            ),
            permissions = permissions,
            // F10: recording path and input — without these two lines the
            // phone finding "peak_pct: 2" cannot be interpreted (rule 9: short names only).
            audioRoute = routeLabel(router?.currentRoute ?: de.voicekeyboard.audio.Route.BUILTIN_MIC),
            audioSource = de.voicekeyboard.audio.AudioRecordSource.SOURCE_NAME,
            // F42 (🐞: had_output/output_chars/fgs_stopped_by): output forensics
            // (numbers only) + FGS stop cause from F44 — never text contents (rule 9).
            hadOutput = outputCounter.hasOutput,
            outputChars = outputCounter.outputChars,
            fgsStoppedBy = fgsStoppedBy(),
            statusRes = statusResValue, // F52: last shown status text as a code (rule 9)
            // AP7-W1: service-side stop moment sample (finer than the
            // coordinator estimate) + rejected foreign resets (W1b evidence).
            gateOpenAtStop = gateOpenAtStopValue,
            gateForeignResets = holdGate.foreignResets,
        )
        // Rule 9: the export text is NEVER logged — only format/status.
        val text = de.voicekeyboard.debug.DebugInfoFormatter.format(snapshot)
        collector.export(text)
        // AP1 (PLAN_C2_FABLE_R2 §1.2): debug.vk.export_file=1 → ADDITIONALLY to
        // files/debug_export.txt (identical content; release stub = no-op).
        de.voicekeyboard.debug.DebugHooks.onExport(this, text)
        postEvent(hintEvent(R.string.diagnose_copied_share_opened)) // F13c: hint, transient (F51/K2: resource; AP3: res name in status_res)
    }

    private fun stopDictationIfRunning() {
        val c = coordinator
        // AP7-W1: gate_open_at_stop — sample DIRECTLY before the stop (0/1).
        gateOpenAtStopValue = if (holdGate.activeGen == 0L) 1 else 0
        // F34/P2-1 (REVIEW_F29_F33): even WITHOUT a coordinator the mic state must
        // not hang — focus loss/IME hiding are end paths (before:
        // `?: return` saved the view sync and updateStatus entirely).
        if (c != null) {
            if (c.state == de.voicekeyboard.dictation.DictationState.RECORDING ||
                c.state == de.voicekeyboard.dictation.DictationState.STARTING
            ) {
                finalResultExpected = true // D10-rest: finale expected → judge toast/check mark
                c.stop() // final decode until done, drain (Coordinator-M4c-1)
            } else {
                // F13b/C1: release over a session that STILL FAILED (state from
                // fail() already IDLE) — secure the error code BEFORE abort() (abort
                // overwrites lastErrorCode with "aborted"), show sticky.
                if (c.failedThisSession) {
                    val code = c.visibleError
                    c.abort("michold_cancel")
                    stickyError = code?.let { mapVisibleError(it) }
                } else {
                    c.abort("michold_cancel")
                }
            }
        }
        syncMicUi() // D10-rest §2 + F34: state surface always free (green ⇔ RECORDING)
        stopFgs("stop") // regular stop (the F43 auto-stop also runs via c.stop())
        updateStatus()
    }

    private fun buildCoordinator(): DictationCoordinator {
        // D10-rest §3: output counter around the real sink — counts ONLY characters
        // (rule 9), no text content; synchronous on the edit executor (main).
        val sink = CountingSink(InputConnectionSink(connectionProvider = { currentInputConnection }))
        val routerRef = router
        return DictationCoordinator(
            scope = serviceScope,
            // F9 P0: onStopped runs in the worker (serviceScope = Default). Without the
            // main dispatcher the coordinator would run its UI finale in the worker
            // context -> CalledFromWrongThreadException on the new info row TextView and
            // thus a dead IME process (user: "input is ignored").
            mainDispatcher = kotlinx.coroutines.Dispatchers.Main,
            // F49 wiring (PLAN_V0130 A2): auto-stop suppressed while a hold
            // gesture keeps the finger down ("not yet done"); toggle
            // sessions run with an open gate ("pause = end", 2.5 s, V12).
            autoStopSuppressed = { holdGate.activeGen != 0L }, // AP7-W1: generation-bound
            audioSourceFactory = {
                // F30 (harness docs option C): debug-only switchover via the field
                // above (release: null → AudioRecordSource as before).
                debugAudioSourceFactory?.invoke()
                    ?: AudioRecordSource(
                        // B2 (PLAN §6.2 option A): upper limit per session —
                        // hold 900 s (15 min) instead of 300 s; toggle gets the same
                        // value (practically irrelevant: F43 stops toggle anyway
                        // at the first 2.5-s pause). Pure decision
                        // [sessionMaxDurationMs], JVM-testable (StartGateTest).
                        maxDurationMs = sessionMaxDurationMs(holdGesture = true),
                        errorListener = coordinator?.errorListener,
                        levelListener = AudioLevelListener { rms, _ ->
                            // Shows in the diagnostics row whether any sound arrives at all
                            // (rule 9: only a maximum value, never audio data).
                            coordinator?.noteLevel(rms)
                        },
                    )
            },
            engineFactory = {
                // AP1 (PLAN_C2_FABLE_R2 §1.2): the debug hook may replace the engine
                // (LatencyStubEngine, real-time clock sleep) — release: null → real.
                debugEngineFactory?.invoke() ?: ParakeetAsrEngine()
            },
            sink = sink,
            tracker = ownership,
            routeObserver = { on -> if (on) routerRef?.startObserving() else routerRef?.stopObserving() },
            // B2 (PLAN §6.3 (c), I3 curve α=38.5 ms/s, superlinear from ~60 s):
            // segmenter NOW sharp for ALL sessions — the 35-s cap keeps every
            // decode window ≤ ~37 s ⇒ decode ≈ 1.35 s (below the 1.5-s W of the
            // technology stage); sentence pauses cut softly at sentence ends (rule a).
            // Gate-independent: the auto-stop (F43) stays separate from the segmenter.
            segmentFinalizeMs = 35_000,
            // C-2/C-4: load in the coordinator. Important: the store folder itself is
            // NOT the model directory — the files live in
            // versions/<active>. The engine checks directly for vocab.txt and
            // otherwise throws "vocab.txt missing in model folder" even though the store reports READY.
            modelDir = de.voicekeyboard.model.ModelStore(
                java.io.File(filesDir, MODEL_DIR_NAME),
            ).modelDir(),
            onStopped = { onDictationStopped() }, // C-1: update the UI after the worker finale
        ).also { c ->
            // Pass the UI route to the outside (display via onRouteChanged below)
            routerRef?.routeListener = { c.onRouteChanged(routeLabel(it)); updateStatus() }
            // A4b (PLAN_V0133 §A4b): after EVERY decode window exactly ONE compact
            // VK-ASR line — Log.i WITHOUT debug guard (effective in release; the pure
            // formatting [decodeWindowLine] is JVM-tested, never throws, no
            // abort through diagnostics); out synchronous from the existing outputCounter;
            // the engine stage line (ParakeetAsrEngine) stays untouched;
            // no log between windows, no endless loop.
            c.onDecodeWindow = { d ->
                android.util.Log.i("VK-ASR", decodeWindowLine(d, outputCounter.outputChars))
            }
        }
    }

    /**
     * D10-rest §3: counts the net character output of the active session — numbers
     * only (rule 9), no text content, no path. Delegates everything to [inner].
     */
    private inner class CountingSink(private val inner: de.voicekeyboard.editor.EditorSink) :
        de.voicekeyboard.editor.EditorSink {

        override fun replaceBeforeCursor(replaceLength: Int, newText: String) {
            inner.replaceBeforeCursor(replaceLength, newText)
            outputCounter.onCommit(newText.length, replaceLength)
        }

        override fun textBeforeCursor(maxChars: Int): CharSequence? = inner.textBeforeCursor(maxChars)

        // AP7-E1c: ownText MUST be delegated through — the interface default
        // would be empty and the E1c resync would not take hold.
        override fun ownText(): String = inner.ownText()

        override fun ownLength(): Int = inner.ownLength()

        // REVIEW_A5_W P0: reset MUST be delegated through — the interface default
        // would be a no-op and the ownSpan corpse would remain in production (two-session W1).
        override fun resetSession() = inner.resetSession()
    }

    /**
     * D10-rest §2/§3: finale after STOP (not after abort — the coordinator calls
     * onStopped only in the stop() path). Runs on the main dispatcher (N-2).
     *  - Empty output (0 characters) → toast "No text recognized. Please try
     *    again." — the silent empty output is no longer silent; NO check mark.
     *  - Output > 0 → short-lived commit check mark (600 ms) on the mic cell.
     */
    private fun onDictationStopped() {
        syncMicUi() // P0 finding 2(b): stop => mic state moves back (was: ad-hoc false)
        // F45 (retest F41b, P1): the F43 AUTO-STOP runs through THIS finale, without
        // a user stop having called stopFgs() beforehand — the micro FGS kept
        // running until the next IME hide (dumpsys +12/+20 s). If it is STILL
        // running here, it was the auto-stop → end it here (idempotent; a user stop
        // has already stopped it synchronously and keeps its own reason).
        fgsStopOnDictationStopped(fgsRunning)?.let { stopFgs(it) }
        // AP7-W1: stop moment sample (also covers the F43 auto-stop) BEFORE the
        // generation-bound reset — endHold acts only on the session generation.
        gateOpenAtStopValue = if (holdGate.activeGen == 0L) 1 else 0
        endHold(holdGate.sessionGen, "stopped")
        sess("final", " trail_sp=" +
            if ((coordinator?.lastStopOutcome as? de.voicekeyboard.dictation.StopOutcome.FinalRan)
                    ?.trailingSpace == true) 1 else 0
        ) // AP1: VK-SESS — mini/overall finale; T10: trail_sp=0|1 (rule 9: number only)
        holdWarnShown = false // B2: session end ⇒ warning flag back (next session warns anew)
        val c = coordinator
        val hadOutput = outputCounter.hasOutput
        // F13d/P2-1: the verdict reads ONLY the StopOutcome SNAPSHOT (bundled in
        // the worker directly after controller.stop()) — NEVER the live
        // controller state, because an immediate new start() nulls
        // failedThisSession/lastErrorCode and would judge session A with the
        // state of session B.
        val outcome = c?.lastStopOutcome
        val finalError = (outcome as? de.voicekeyboard.dictation.StopOutcome.FinalRan)?.finalError
        val failedWith = (outcome as? de.voicekeyboard.dictation.StopOutcome.FinalRan)?.failedWith == true
        val noFinalCode = (outcome as? de.voicekeyboard.dictation.StopOutcome.NoFinal)?.errorCode
        val cleanSuccess = outcome is de.voicekeyboard.dictation.StopOutcome.FinalRan &&
            finalError == null && !failedWith
        if (finalResultExpected) {
            if (!cleanSuccess) {
                // Reason from the SNAPSHOT — existing error message, sticky (R-2).
                val code = noFinalCode ?: finalError ?: "unknown"
                stickyError = mapVisibleError(code)
                updateStatus()
            } else if (hadOutput) {
                rootView?.keyboardView?.showMicCheck()
                successJustFinished = true // F13a/R-2b: success may clear stickyError
                updateStatus()
            } else {
                toast(getString(R.string.dictation_no_text))
            }
        }
        finalResultExpected = false
        updateStatus()
    }

    /**
     * D10-rest §1: CANCEL of the hold gesture — safe abort without final decode.
     * Already committed text stays; a running start is cleanly
     * aborted, a caught-up stop is prevented.
     */
    private fun abortDictation(reason: String = "editor_change") {
        startingUp = false
        endHold(holdGate.sessionGen, "abort") // AP7-W1: session generation, never null globally
        holdWarnShown = false // B2: abort = session end ⇒ warning flag back
        cancelStartRequested = false
        cancelStartIsAbort = false
        finalResultExpected = false
        syncMicUi() // P0 finding 2(c): abort => never green without a session
        coordinator?.abort(reason)
        stopFgs("abort:$reason")
        sess("abort:$reason") // AP1: VK-SESS — abort path
        updateStatus()
    }

    // ---- D10-rest §1: hold gesture on the footer-row microphone ----

    /** T3 (v0.1.35): coordinator state AT mic DOWN (fresh vs. session/start
     *  already active) — basis of the tap/hold classification in [onMicHoldEnd]
     *  (micHoldReleaseDecision), measured BEFORE the asynchronous start. */
    @Volatile private var micHoldDownState: de.voicekeyboard.dictation.DictationState? = null

    override fun onMicHoldStart() {
        footerTouchGen = holdGate.begin("footer-hold") // AP7-W1: setter source
        sess("gate set gen=$footerTouchGen src=footer-hold")
        // T3: remember freshness at DOWN (BEFORE the asynchronous start) — a second
        // tap on the running session must stay TOGGLE_OFF, a fresh
        // tap must become TOGGLE_ON, no matter when the UP comes.
        micHoldDownState = coordinator?.state
        onMicDown() // DOWN starts (asynchronous start incl. startingUp guard stays)
    }

    override fun onMicHoldEnd(cancelled: Boolean, holdDurationMs: Long) {
        // AP7-W1: end only the OWN generation (carried marker).
        endHold(footerTouchGen, if (cancelled) "holdcancel" else "holdup")
        // F28b2: hold end (with or without cancel) — who: holdup/holdcancel.
        noteMicRel(if (cancelled) "holdcancel" else "holdup")
        // T3 (v0.1.35, debugger design): tap/hold classification instead of a blanket
        // "RECORDING/STARTING + UP ⇒ stop" (MicToggle.micHoldReleaseAction was the
        // finding: four sessions died 10-90 ms after start_ok via their own
        // release). Fresh tap ⇒ TOGGLE_ON; tap on a running session ⇒
        // TOGGLE_OFF; >300 ms ⇒ hold release (pending if the start is still running).
        when (
            micHoldReleaseDecision(
                stateAtDown = micHoldDownState,
                stateAtRelease = coordinator?.state,
                holdDurationMs = holdDurationMs,
                cancelled = cancelled,
                startingUp = startingUp,
            )
        ) {
            MicReleaseDecision.TOGGLE_ON -> Unit // fresh tap: session keeps running (UP before OR after start_ok)
            MicReleaseDecision.TOGGLE_OFF, MicReleaseDecision.HOLD_STOP -> {
                stopDictationIfRunning()
                updateStatus()
            }
            MicReleaseDecision.HOLD_STOP_PENDING -> {
                // Real hold release during the start: catch up the stop — takes effect
                // AFTER the start end via micStartFinishedAction(STOP_AFTER_START);
                // a fresh tap (TOGGLE_ON) does NOT set this flag at all.
                cancelStartIsAbort = false
                cancelStartRequested = true
                postEvent(hintEvent(R.string.dictation_start_pending_stop))
            }
            MicReleaseDecision.ABORT -> {
                if (startingUp) {
                    // Catch up the abort: after the start NO finale, but an abort.
                    cancelStartIsAbort = true
                    cancelStartRequested = true
                    return
                }
                abortDictation("michold_cancel")
            }
            MicReleaseDecision.IGNORE -> Unit
        }
    }

    private fun routeLabel(route: de.voicekeyboard.audio.Route): String = when (route) {
        de.voicekeyboard.audio.Route.WIRED_HEADSET -> "wired headset"
        de.voicekeyboard.audio.Route.BLUETOOTH_SCO -> "BT headset"
        de.voicekeyboard.audio.Route.BUILTIN_MIC -> "built-in"
    }

    // ---- Permissions (order: grant → FGS → start) ----

    private fun ensurePermissions(): Boolean {
        // F28c: API-level dependent — from 33 POST_NOTIFICATIONS belongs to the
        // dictation conditions (FGS type microphone without it: the system can
        // stop the recording silently, export #2). If it is missing: no silent
        // start, but a visible error with a reason (new string).
        val apiLevel = android.os.Build.VERSION.SDK_INT
        val required = DictationPermissions.requiredForApi(apiLevel)
        val granted = required
            .filter { checkSelfPermission(it) == android.content.pm.PackageManager.PERMISSION_GRANTED }
            .toSet()
        // C-3: "don't ask again" via a PERSISTED marker — the
        // IME context does not know shouldShowRequestPermissionRationale; the
        // dialog-carrying activity (SetupActivity) sets the marker after the
        // second denied request. No hard-coded emptySet() anymore:
        // the PermanentlyDenied path is reachable.
        val prefs = getSharedPreferences(PERMISSION_PREFS, MODE_PRIVATE)
        val permanentlyDenied = required
            .filter { prefs.getBoolean(PERMISSION_DENIED_FOREVER_PREFIX + it, false) }
            .toSet()
        return when (
            val decision = DictationPermissions.nextStep(granted, permanentlyDenied, apiLevel)
        ) {
            DictationPermissions.Decision.Ready -> true
            is DictationPermissions.Decision.Ask -> {
                Log.i(TAG, "permission ask: ${decision.permission}") // permission name only
                // Dialog: the IME itself is not a dialog-carrying context — open
                // the SetupActivity via PendingIntent (AndroidPermissionsRequester
                // encapsulates only the facts, the UI handling belongs to the activity).
                val intent = android.content.Intent(this, SetupActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    .putExtra("de.voicekeyboard.ime.REQUEST_PERMISSION", decision.permission)
                android.app.PendingIntent.getActivity(
                    this, REQUEST_DICTATION_PERMISSIONS, intent,
                    android.app.PendingIntent.FLAG_IMMUTABLE,
                ).send()
                postEvent(StatusEvent.Error(getString(permissionErrorRes(decision.permission))))
                false
            }
            is DictationPermissions.Decision.PermanentlyDenied -> {
                Log.i(TAG, "permission permanently denied: ${decision.permission}")
                // C-3: don't ask again — go directly to the app system settings
                try {
                    val settingsIntent = android.content.Intent(
                        android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        android.net.Uri.fromParts("package", packageName, null),
                    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    startActivity(settingsIntent)
                } catch (e: Exception) {
                    Log.i(TAG, "settings intent failed: ${e.javaClass.simpleName}")
                }
                // F28c: the notification keeps its reason (contains the
                // settings hint), the mic the established settings text.
                postEvent(StatusEvent.Error(getString(
                    if (decision.permission == android.Manifest.permission.POST_NOTIFICATIONS) {
                        R.string.dictation_error_permission_notification
                    } else {
                        R.string.dictation_error_permission_settings
                    },
                )))
                false
            }
        }
    }

    /** F28c: error text per missing permission — the notification has its own
     *  reason (export #2: silent stop of the recording), the mic the established one. */
    private fun permissionErrorRes(permission: String): Int =
        if (permission == android.Manifest.permission.POST_NOTIFICATIONS) {
            R.string.dictation_error_permission_notification
        } else {
            R.string.dictation_error_permission_mic
        }

    // ---- FGS (before the first recording start) ----

    private fun startFgs() {
        if (fgsRunning) return
        // F28c: reset the handshake + register BEFORE the service starts —
        // the worker waits on [awaitFgsReady]; only then does AudioRecord start.
        fgsReady = false
        CaptureForegroundService.onForegroundReady = {
            fgsReadyLock.lock()
            try {
                fgsReady = true
                fgsReadyCondition.signalAll()
            } finally {
                fgsReadyLock.unlock()
            }
        }
        startForegroundService(
            Intent(this, CaptureForegroundService::class.java).apply {
                action = CaptureForegroundService.ACTION_START
            },
        )
        fgsRunning = true
    }

    /** F28c: waits (serviceScope worker, NEVER main thread) until the FGS has
     *  completed startForeground; timeout ⇒ false (no mic start). */
    private fun awaitFgsReady(timeoutMs: Long): Boolean {
        fgsReadyLock.lock()
        try {
            val deadline = android.os.SystemClock.elapsedRealtime() + timeoutMs
            while (!fgsReady) {
                val left = deadline - android.os.SystemClock.elapsedRealtime()
                if (left <= 0) return false
                try {
                    fgsReadyCondition.awaitNanos(left * 1_000_000L)
                } catch (e: InterruptedException) {
                    return fgsReady
                }
            }
            return true
        } finally {
            fgsReadyLock.unlock()
        }
    }

    private fun stopFgs(by: String = "-") {
        if (!fgsRunning) return
        fgsStoppedBy = by // F44: remember the cause (code only, rule 9)
        startService(
            Intent(this, CaptureForegroundService::class.java).apply {
                action = CaptureForegroundService.ACTION_STOP
            },
        )
        fgsRunning = false
    }

    /** F42 (🐞: fgs_stopped_by): cause code of the last FGS stop for the
     *  debug export — controlled codes only, never contents (rule 9). */
    fun fgsStoppedBy(): String = fgsStoppedBy

    // ---- Status display (no text/audio content) ----

    /** S5-3/E5: maps internal error codes to user strings (≤45 characters).
     *  Raw code (audioInit:…, model:…, startFailed) only in the log, never in the UI.
     *  Only technically separable things are distinguished; everything else → audio_start. */
    private fun mapVisibleError(raw: String): String {
        Log.w(TAG, "visibleError=$raw") // raw code into the log only
        // F13e: the editor sync timeout has its OWN text (target app blocked),
        // not the wrong "audio start" fallback. Pure mapping see below.
        mapErrorTextRes(raw)?.let { return getString(it) }
        return when {
            raw.startsWith("audioInit:SecurityException") ||
                raw.startsWith("permission") -> getString(R.string.dictation_error_permission)
            raw.startsWith("model:") -> {
                val store = de.voicekeyboard.model.ModelStore(java.io.File(filesDir, MODEL_DIR_NAME))
                if (store.status() == de.voicekeyboard.model.ModelStatus.BROKEN) {
                    getString(R.string.dictation_error_model_broken)
                } else {
                    getString(R.string.dictation_error_model_absent)
                }
            }
            raw.startsWith("btLost") || raw.startsWith("route:bluetooth") ->
                getString(R.string.dictation_error_bt_lost)
            // A2/E5 (a): empty input device catalog
            raw.startsWith("noMic") || raw.startsWith("audioInit:noInputDevice") ->
                getString(R.string.dictation_error_no_mic)
            // A2/E5 (g): allocator pattern of ORT (best-effort)
            raw.contains("alloc") || raw.contains("bad_alloc") || raw.startsWith("oom") ->
                getString(R.string.dictation_error_out_of_memory)
            // A2/E5 fallback: the only place without a diagnostics promise
            raw.startsWith("unknown") || raw.startsWith("internal") ->
                getString(R.string.dictation_error_unknown)
            // F31: blocks are running but everything is silent (−56-dBFS user case) — own,
            // clear text instead of the wrong "audio start" fallback.
            raw.startsWith("audio_error:silence") ->
                getString(R.string.dictation_error_no_signal)
            else -> getString(R.string.dictation_error_audio_start)
        }
    }

    /** E5 priority (permission → model BROKEN before ABSENT → audio): start throw.
     *  A2: unknown exceptions → own fallback text (never raw code). */
    private fun startErrorText(store: de.voicekeyboard.model.ModelStore, e: Exception): String {
        Log.w(TAG, "startError=${e.javaClass.simpleName}")
        return when {
            e is SecurityException -> getString(R.string.dictation_error_permission)
            e is java.io.IOException -> getString(R.string.dictation_error_audio_start)
            store.statusQuick() == de.voicekeyboard.model.ModelStatus.BROKEN ->
                getString(R.string.dictation_error_model_broken)
            else -> getString(R.string.dictation_error_unknown)
        }
    }

    /** Main handler for status messages from worker context (F9 P0, belt and suspenders). */
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    /**
     * F13c/P2-3: wiring seam — EVERY status occasion of the service goes through
     * [StatusRouting.route] (pure table, pinned in StatusRoutingTest):
     * Starting/Hint → transient channel, Error → sticky channel. B1 (start text
     * became sticky) was exactly this seam error and can now no longer
     * return unnoticed — a regression turns the routing test red.
     */
    private fun postEvent(event: StatusEvent) {
        when (StatusRouting.route(event)) {
            StatusChannel.TRANSIENT -> {
                // Display until the next updateStatus (superseded at recording/
                // finalizing/newer error) — sets NO stickyError.
                transientStatus = event.text
                transientIsHint = event is StatusEvent.Hint // F52: kind hint vs. starting
                transientSetAtMs = android.os.SystemClock.elapsedRealtime()
                updateStatus()
            }
            StatusChannel.STICKY -> updateStatus(event.text) // R-2: sticky until the next action
        }
    }

    private fun updateStatus(extra: String? = null) {
        // F9 P0: view access is main-bound. Any callback of the coordinator or
        // of the router may come from a worker - the hop to main happens here.
        if (android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) {
            mainHandler.post { updateStatus(extra) }
            return
        }
        val view = rootView ?: return
        val c = coordinator
        // AP1: VK-SESS — recognize a mini-finale (segment intermediate cut) at a counter change.
        // A3 (PLAN_V0133 §A3): pure decision — only a REAL counter increase
        // logs (previous >= 0 && current > previous); a session change opens
        // the baseline without an entry. No change to the segmenter/counter.
        if (segSessionLog.onSegmentCount(c?.segmentFinalizations() ?: 0)) {
            sess("seg_final")
        }
        val visible = c?.visibleError?.let { mapVisibleError(it) }
        // F13a: timestamp of the clarity rule — an ERROR is never "older" than a
        // freshly running transient start text if it was observed afterwards.
        val now = android.os.SystemClock.elapsedRealtime()
        if ((visible != null || extra != null) && lastErrorSeenAtMs == 0L) {
            lastErrorSeenAtMs = now
        }
        if (stickyError != null && lastErrorSeenAtMs == 0L) lastErrorSeenAtMs = now
        // F13a/B1: text selection over the pure-logical function — the transient
        // start text no longer sets/masks stickyError; extra is only
        // sticky only when the function marks it as an error.
        val recText = buildString { // F52: hoisted — the diagSuffix derivation needs the line before select
            append("Dictating… (")
            append(routeLabel(router?.currentRoute ?: de.voicekeyboard.audio.Route.BUILTIN_MIC))
            append(")")
            // S12b: diagnostics short form only in debug builds (rule 9: numbers only —
            // no audio/text contents, no model paths).
            if ((applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
                c?.diagnosticsLine(
                    de.voicekeyboard.asr.AsrThreadConfig.forDevice(
                        Runtime.getRuntime().availableProcessors(),
                        de.voicekeyboard.asr.AsrThreadConfig.deviceRamMb,
                    ).intraOpThreads,
                )?.let { append(" · $it") }
            }
        }
        val result = StatusBarText.select(
            StatusBarText.Inputs(
                extra = extra,
                transientText = transientStatus,
                stickyError = stickyError,
                visibleError = visible,
                recording = c?.state == de.voicekeyboard.dictation.DictationState.RECORDING,
                finalizing = c?.state == de.voicekeyboard.dictation.DictationState.FINALIZING,
                recordingText = recText,
                finalizingText = getString(R.string.status_finalizing), // F51 (K2)
                idleText = getString(R.string.status_ready), // F51 (K2)
                errorsNewerThanTransient = transientStatus != null &&
                    lastErrorSeenAtMs >= transientSetAtMs && lastErrorSeenAtMs > 0,
                success = successJustFinished,
                transientHint = transientIsHint, // F52: hint vs. starting
                diagSuffix = recText.contains(" · "), // F52: diag vs. recording (structure marker, no content)
            ),
        )
        // F52 (PLAN_V0130 A3): status_res — code of the last shown
        // status text; ERROR carries the resource name from the visible
        // error code (rule 9: never the text itself).
        statusResValue = if (result.kind == StatusKind.HINT) {
            // AP3: hint:<res-name> instead of a bare "hint" (review A3-P2) — code, never text.
            "hint:" + (lastHintRes ?: "-")
        } else {
            StatusBarText.statusResCode(
                result.kind,
                c?.visibleError?.let { code -> mapErrorTextRes(code) }
                    ?.let { resId -> resources.getResourceEntryName(resId) },
            )
        }
        successJustFinished = false // one-time flag (R-2b): judge only the next call
        if (extra != null && result.extraIsSticky) {
            stickyError = extra // R-2: ONLY real errors are sticky — never the start text
            lastErrorSeenAtMs = now
        }
        if (result.stickyConsumed) stickyError = null // R-2b: completed success
        if (result.transientConsumed) {
            transientStatus = null
            transientSetAtMs = 0L
        }
        view.statusText.text = result.text
        // F28b2: which state did the status row last receive — pure code
        // for the export (rule 9); priority like StatusBarText.select.
        if (c != null) {
            c.setStatusShown(
                when {
                    visible != null || stickyError != null -> "error"
                    c.state == de.voicekeyboard.dictation.DictationState.FINALIZING -> "finalizing"
                    c.state == de.voicekeyboard.dictation.DictationState.RECORDING -> "dictat"
                    else -> "ready"
                },
            )
        }
        view.micButton.isEnabled = c?.state != de.voicekeyboard.dictation.DictationState.FINALIZING
        val recording = c?.state == de.voicekeyboard.dictation.DictationState.RECORDING
        view.micButton.isActivated = recording
        // P0 finding 2(b/c): the footer mic green tone follows the same source —
        // EVERY updateStatus call keeps the invariant "green ⇔ RECORDING".
        view.keyboardView.micRecording = recording
    }

    // ---- Keyboard events (unchanged except mic removal from the action list) ----

    override fun onTextInput(text: String) {
        val ic = currentInputConnection ?: return
        ic.commitText(text, 1)
    }

    override fun onAction(action: String) {
        val ic = currentInputConnection ?: return
        when (action) {
            KeyActions.BACKSPACE -> {
                // B2 (PLAN_V0133 §B2): ONE action over the pure editor seam —
                // non-empty selection atomically replaced via commitText("",1),
                // otherwise stock path (grapheme clusters; empty buffer ⇒ DEL key).
                // Repeat AND tap both use THIS branch (KeyboardView calls
                // onAction(BACKSPACE) again on every repeat firing).
                backspaceDeleteOnce(inputConnectionEditor())
            }
            KeyActions.SPACE -> ic.commitText(" ", 1)
            KeyActions.ENTER -> performEditorAction(currentInputEditorInfo)
            KeyActions.CURSOR_LEFT -> ic.sendKeyEvent(
                KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_LEFT),
            ).also { ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_LEFT)) }
            KeyActions.CURSOR_RIGHT -> ic.sendKeyEvent(
                KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT),
            ).also { ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_RIGHT)) }
            KeyActions.LANGUAGE -> switchToNextLanguage() // F25: branch was missing — language switch was dead
            KeyActions.MIC -> {
                // F8 Q6 (P1) + F9 d (P2): the footer-row mic key is a toggle,
                // but only in IDLE/RECORDING. In STARTING/FINALIZING a tap would be a
                // restart attempt that fails at coordinator.start() and shows
                // "Dictation start failed" — hence deliberately nothing there.
                // Holding remains additionally possible: DOWN of the status row bar
                // starts, UP stops.
                when (micToggleAction(coordinator?.state)) {
                    MicToggleAction.START -> onMicDown()
                    MicToggleAction.STOP -> onMicUp()
                    MicToggleAction.IGNORE -> Unit
                }
            }
            KeyActions.EMOJI -> toggleEmojiPanel() // E9.4 (M8b-2)
            else -> Unit
        }
    }

    /**
     * B2w: repeat firing in WORD mode (backspace held ≥ 2000 ms) — ONE whole
     * word per firing over the SAME pure editor seam as the character path
     * ([backspaceDeleteWordOnce]); the tap/character branch (onAction
     * BACKSPACE → backspaceDeleteOnce) stays untouched.
     */
    override fun onBackspaceWord() {
        backspaceDeleteWordOnce(inputConnectionEditor())
    }

    // ---- Language switch (E9.1/E9.2, M8b-1) ----

    private fun layoutPrefs() = getSharedPreferences(LAYOUT_PREFS, MODE_PRIVATE)

    /** Active languages from vk_layout (ordered, canonical; default de-de,en-us). */
    private fun activeLanguages(): List<String> =
        LanguageCycle.parseActive(layoutPrefs().getString(KEY_ACTIVE_LANGUAGES, null))

    /** Current language: current_language if active; else first active (E9.1 rule 7). */
    private fun currentLanguage(): String {
        val active = activeLanguages()
        return LanguageCycle.initialLanguage(
            active,
            layoutPrefs().getString(KEY_CURRENT_LANGUAGE, null),
        )
    }

    /**
     * Short tap 🌐 = next active language (E9.1 (a)).
     * - during RECORDING/STARTING/FINALIZING: ignore + status hint (E9.2),
     *   do NOT abort the dictation
     * - only one active language: switch nothing, status hint (rule 4)
     * - otherwise: pure setLayout in the IME window (E3/E9.1 rule 6) — no
     *   restartInput, no finishInput, currentInputConnection stays untouched.
     */
    private fun switchToNextLanguage() {
        val c = coordinator
        if (c != null && c.state != de.voicekeyboard.dictation.DictationState.IDLE) {
            // D10 §3 variant (b) — "pre-book for the next recording": the deliberate tap
            // is ACCEPTED (instead of being helplessly rejected), the running PTT/edit
            // session stays untouched; no false promise of an ASR language switch
            // ("dictation language auto" — the recognition follows the model).
            val active = activeLanguages()
            // Further taps cycle from the PRE-BOOKED instead of the current ID;
            // an invalid pending (target removed from the active languages) is deleted.
            val pending = pendingLayoutId?.takeIf { it in active }
            val next = LanguageCycle.nextActiveLanguage(active, pending ?: currentLanguage())
            if (next == null) {
                toast(getString(R.string.language_switch_single_active)) // "Only one language active"
                return
            }
            if (next == currentLanguage()) {
                // Returning to the current ID lifts the pre-booking (D10 §3).
                pendingLayoutId = null
                toast("Pre-note cancelled. Recognition auto.")
                return
            }
            pendingLayoutId = next
            toast("Layout ${LanguageCycle.endonym(next)} pre-noted; dictation language auto")
            return
        }
        val active = activeLanguages()
        val next = LanguageCycle.nextActiveLanguage(active, currentLanguage())
        if (next == null) {
            // E9.1 rule 4: no silent no-op
            postEvent(hintEvent(R.string.language_switch_single_active))
            return
        }
        applyLanguage(next)
    }

    /** D10 §3: one-time short toast (no new line in the diagnostics band). */
    private fun toast(text: String) {
        android.widget.Toast.makeText(applicationContext, text, android.widget.Toast.LENGTH_SHORT).show()
    }

    /**
     * D10 §3: apply the pre-booking at the NEXT deliberate mic DOWN — before the
     * start of the new capture; afterwards the pending is gone. Invalid targets
     * (no longer active languages) are silently deleted, never applied surprisingly.
     */
    private fun applyPendingLayoutBeforeCapture() {
        val target = pendingLayoutId ?: return
        pendingLayoutId = null
        if (target in activeLanguages() && target != currentLanguage()) {
            applyLanguage(target)
        }
    }

    /** Loads the target language asset (fallback de-de, rule 5) and shows the endonym.
     *  L-02: a language switch resets shift/caps (no dead shift state into the new layout).
     *  E10.4 rule 2: a language switch with the panel open closes it (category stays,
     *  page → 1), inserts nothing; the editor focus remains. */
    private fun applyLanguage(id: String) {
        val view = rootView ?: return
        if (view.isEmojiPanelVisible) {
            view.emojiPanel.closeAndResetPage() // E10.4: category stays, page → 1
        }
        view.hideEmojiPanel()
        view.keyboardView.resetShiftState()
        view.keyboardView.setLayout(repository.loadOrFallback(id), resources.displayMetrics.widthPixels)
        layoutPrefs().edit().putString(KEY_CURRENT_LANGUAGE, id).apply()
        pendingLayoutId = null // D10 §3: a deliberate IDLE choice replaces any pre-booking
        postEvent(hintEvent(R.string.language_switched_to, LanguageCycle.endonym(id)))
    }

    // ---- Emoji panel (E9.4, M8b-2) ----

    /**
     * 😊 key: show/hide the panel. Locked during a recording (E9.4:
     * a manual foreign edit would end the dictation ownership, DESIGN §6).
     * The panel stays in the IME window (overlay) — no restartInput, no focus loss.
     */
    /**
     * 😊 toggle (E10.1/E10.4, M8b-3b): open → close, closed → open.
     * The panel does NOT touch activeLayerName/capsLock/shift (no savedLayer
     * machinery needed — the bottom key row stays visible, the state
     * survives on its own); no restartInput, no finish().
     * On closing: category stays, page → 1 (E10.4 rule 1).
     */
    /**
     * 😊 action (V2 §3.1): sets the emoji mode — NO visible close toggle anymore.
     * Idempotent (a second opening in the open mode writes nothing). State ≠ IDLE
     * blocks opening (message stays, R-2); the return button stays operable.
     * No setLayout/resetShiftState on opening — layer and shift/caps stay
     * unchanged by preserving the KeyboardView instance (V2 §3.4).
     */
    private fun toggleEmojiPanel() {
        val view = rootView ?: return
        val c = coordinator
        if (c != null && c.state != de.voicekeyboard.dictation.DictationState.IDLE) {
            postEvent(hintEvent(R.string.emoji_blocked_recording))
            return
        }
        view.showEmojiPanel() // idempotent: no closing anymore in the open mode
    }

    private fun performEditorAction(info: EditorInfo?) {
        val ic = currentInputConnection ?: return
        // T17: Enter resolution via the pure seam — AOSP LatinIME behavior:
        // multiline (or no concrete action or IME_FLAG_NO_ENTER_ACTION) =
        // NEWLINE, the keyboard stays open; only a single-line field with a
        // concrete action routes performEditorAction to the app.
        applyEnterKey(ic, info)
    }

    /** UTF-16 units of the last complete grapheme cluster at the end of the string. */
    internal fun lastGraphemeLength(before: String): Int = lastGraphemeLengthOf(before)

    /**
     * B2 (PLAN_V0133 §B2): thin editor seam of backspace over the CURRENT
     * InputConnection — the four operations of the branch as an interface, so
     * that the decision is purely JVM-testable (BackspaceTest; stock pattern
     * stopCauseOf/micToggleAction). NO Android type in the interface.
     */
    private fun inputConnectionEditor(): BackspaceEditor =
        object : BackspaceEditor {
            override fun selection(): CharSequence? = currentInputConnection?.getSelectedText(0)

            override fun commitEmptyReplacement() {
                currentInputConnection?.commitText("", 1)
            }

            override fun textBeforeCursor(maxChars: Int): CharSequence? =
                currentInputConnection?.getTextBeforeCursor(maxChars, 0)

            override fun deleteGraphemeBeforeCursor(units: Int) {
                currentInputConnection?.deleteSurroundingText(units, 0)
            }

            override fun sendDeleteKeyEvent() {
                val conn = currentInputConnection ?: return
                conn.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL))
                conn.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DEL))
            }
        }
}

/**
 * B2 (PLAN_V0133 §B2): thin editor seam of backspace — the operations of the
 * branch as an interface, so that the decision is purely JVM-testable
 * (BackspaceTest; stock pattern stopCauseOf/micToggleAction). NO Android type.
 */
interface BackspaceEditor {
    /** getSelectedText(0): current selection or null (empty = like none). */
    fun selection(): CharSequence?

    /** commitText("", 1): atomic replacement of the selection by empty text. */
    fun commitEmptyReplacement()

    /** getTextBeforeCursor(maxChars, 0). */
    fun textBeforeCursor(maxChars: Int): CharSequence?

    /** deleteSurroundingText(units, 0): ONE grapheme cluster (character mode,
     *  B2) or ONE whole word (word mode, B2w — several UTF-16 units) before the cursor. */
    fun deleteGraphemeBeforeCursor(units: Int)

    /** DEL key DOWN+UP (stock fallback for an empty buffer). */
    fun sendDeleteKeyEvent()
}

/**
 * B2: ONE backspace action — checks the selection FIRST: non-empty ⇒ atomically
 * replaced via [BackspaceEditor.commitEmptyReplacement] (NEVER deleteSurroundingText
 * or DEL key events); otherwise stock path grapheme cluster ([lastGraphemeLengthOf],
 * empty buffer ⇒ DEL key fallback). Repeat AND tap both use THIS
 * branch themselves; one call takes effect EXACTLY once (no double delete from DOWN+UP).
 * Never throws. Pure function, no Android (rule 9: no content in logs).
 */
fun backspaceDeleteOnce(editor: BackspaceEditor) {
    val sel = editor.selection()
    if (!sel.isNullOrEmpty()) {
        editor.commitEmptyReplacement()
        return
    }
    val before = editor.textBeforeCursor(32)?.toString()
    if (before.isNullOrEmpty()) {
        editor.sendDeleteKeyEvent()
    } else {
        editor.deleteGraphemeBeforeCursor(lastGraphemeLengthOf(before))
    }
}

/** UTF-16 units of the last complete grapheme cluster at the end of the string
 *  (B2: top-level version of the service stock — pure function, JVM-testable). */
fun lastGraphemeLengthOf(before: String): Int {
    if (before.isEmpty()) return 0
    val it = java.text.BreakIterator.getCharacterInstance()
    it.setText(before)
    val end = it.last()
    val start = it.previous()
    if (start == java.text.BreakIterator.DONE || (end - start) <= 0) {
        val cp = before.codePointBefore(before.length)
        return Character.charCount(cp)
    }
    return end - start
}

/** B2w: context window of the word deletion (getTextBeforeCursor) — every
 *  real word incl. surrounding spaces fits into this; longer runs are
 *  truncated to it (deletion stays bounded, see backspaceWordLengthOf). */
private const val BACKSPACE_WORD_CONTEXT_UNITS = 256

/**
 * B2w: ONE word-delete action (word mode of the backspace repeat, held ≥ 2000 ms):
 * non-empty selection ⇒ atomically replaced (like the character path),
 * otherwise ONE whole word incl. surrounding spaces before the cursor
 * ([backspaceWordLengthOf]). At the line/text start NOTHING happens — the
 * word deletion NEVER crosses a line boundary and NEVER sends a DEL key
 * event. Never throws. Pure function, no Android (rule 9: no content in logs).
 */
fun backspaceDeleteWordOnce(editor: BackspaceEditor) {
    val sel = editor.selection()
    if (!sel.isNullOrEmpty()) {
        editor.commitEmptyReplacement()
        return
    }
    val before = editor.textBeforeCursor(BACKSPACE_WORD_CONTEXT_UNITS)?.toString()
    val units = if (before.isNullOrEmpty()) 0 else backspaceWordLengthOf(before)
    if (units > 0) editor.deleteGraphemeBeforeCursor(units)
}

/**
 * B2w: UTF-16 units of ONE word deletion before the cursor — the contiguous
 * non-whitespace sequence left of the cursor INCLUDING the spaces before and
 * after it, up to the line start ('\n') or text start. Cursor directly after
 * '\n' (line start) or empty text ⇒ 0 (no deletion).
 */
fun backspaceWordLengthOf(before: String): Int {
    val n = before.length
    if (n == 0 || before[n - 1] == '\n') return 0
    var i = n
    while (i > 0 && before[i - 1] != '\n' && before[i - 1].isWhitespace()) i-- // spaces after the word
    if (i == 0 || before[i - 1] == '\n') return n - i // only spaces up to the line/text start
    while (i > 0 && !before[i - 1].isWhitespace()) i-- // the word itself
    while (i > 0 && before[i - 1] != '\n' && before[i - 1].isWhitespace()) i-- // spaces before the word
    return n - i
}

/**
 * P0 finding 2 (2026-11-01, user finding after 0.1.24): pure mic UI decisions
 * of the VoiceKeyboardService — as top-level functions of the same file, so that the
 * state machine can be proven without an IME life cycle (pattern:
 * MicToggle/StatusBarText). No Android type.
 */

/** Start success: what happens to the fresh session when the release
 *  came during the (cold) start. */
enum class MicStartFinishAction { RUN, STOP_AFTER_START, ABORT }

/**
 * T3 (v0.1.35, debugger design, evidence /tmp/vk_phone_e2.log L.74-97): wantedStop
 * now comes ONLY from the hold release (> [MIC_TAP_MAX_MS]) during the start
 * ⇒ it REALLY stops the fresh session after the start end ([STOP_AFTER_START]).
 * The former blind KEEP_RUNNING (P0 finding 2(a), rescue of the quick tap) has
 * been replaced by the tap/hold classification: a fresh tap is TOGGLE_ON
 * (micHoldReleaseDecision) and does not set the wantedStop flag at all.
 */
fun micStartFinishedAction(wantedStop: Boolean, wantedAbort: Boolean): MicStartFinishAction = when {
    wantedAbort -> MicStartFinishAction.ABORT
    wantedStop -> MicStartFinishAction.STOP_AFTER_START
    else -> MicStartFinishAction.RUN
}

/** P0 finding 2(b/c): the visible mic state (green) is derived ONLY from the
 *  coordinator state — there is no state in which the mic stays green
 *  without a running session (after stop/abort/error/deadline timeout
 *  the session is IDLE => false). */
fun micUiRecording(state: de.voicekeyboard.dictation.DictationState?): Boolean =
    state == de.voicekeyboard.dictation.DictationState.RECORDING

/**
 * F45 (retest F41b, P1): FGS decision in the onStopped finale. The user stop
 * calls stopFgs("stop") SYNCHRONOUSLY in stopDictationIfRunning (fgsRunning=false,
 * so no-op here); the F43 AUTO-STOP on the other hand reaches the finale WITHOUT
 * a prior stop — in the retest the micro FGS kept running until the next IME hide
 * (dumpsys +12/+20 s foreground, notification stayed, fgs_stopped_by
 * showed the STALE last stop). If the FGS is STILL running in the finale, it was the
 * auto-stop → reason "autostop" for stopFgs (idempotent, overwrites no
 * earlier reason). Pure function (no Android) — JVM-testable (F45 test).
 */
fun fgsStopOnDictationStopped(fgsRunning: Boolean): String? =
    if (fgsRunning) "autostop" else null

/**
 * A3 (PLAN_V0133 §A3): segment session log decision — pure, JVM-testable
 * (stock pattern micStartGate/stopCauseOf; SegmentSessionLogTest). State
 * = last seen segment mini-finale counter; mic DOWN ([onMicDown])
 * resets the session baseline.
 *
 * Finding (18:56, PLAN §0.1): a `seg_final` BEFORE `start_ok` arose from
 * the OLD counter state (sessLastSeg=1) of the ended session against the
 * NEW session counter 0 — not from a belated mini-finale.
 * Decision: log an entry only on a REAL counter increase
 * (previous >= 0 && current > previous); the session change (previous=-1)
 * opens the baseline without an entry. No change to the segmenter or
 * its counter; numbers only, never contents (rule 9).
 */
class SegmentSessionLog {
    private var lastSeg = -1

    /** Mic DOWN: new dictation session — reopen the baseline, NO entry. */
    fun onMicDown() {
        lastSeg = -1
    }

    /**
     * New observation of the segment mini-finale counter. true ⇒ log exactly ONE
     * `seg_final`; the baseline always moves to [current] — also on a
     * reset (counter states are session values).
     */
    fun onSegmentCount(current: Int): Boolean {
        val logged = lastSeg >= 0 && current > lastSeg
        lastSeg = current
        return logged
    }
}

/**
 * A4b (PLAN_V0133 §A4b): compact VK-ASR window line — PURE formatting
 * (no Android, JVM-testable: DecodeWindowLogTest; stock pattern
 * stopCauseOf/micStartGate). EXACTLY ONE line: `event=window` + exactly 17
 * metrics in fixed order; numbers/closed codes only, never
 * text/audio/path contents (rule 9). [out] comes synchronously from the existing
 * DictationOutputCounter (caller: service wiring of
 * [DictationCoordinator.onDecodeWindow]). A foreign write code collapses
 * defensively to '-', so that the line stays closed and single-line.
 */
fun decodeWindowLine(d: DecodeWindowDiagnosis, out: Int): String =
    "event=window" +
        " audio_s=" + java.lang.String.format(java.util.Locale.ROOT, "%.1f", d.audioS) +
        " out=" + out +
        " decode_ms=" + d.decodeMs +
        " pace_ms=" + d.paceMs +
        " skips=" + d.skips +
        " hyp_len=" + d.hypLen +
        " current_len=" + d.currentLen +
        " committed_len=" + d.committedLen +
        " write=" + (if (d.write in WINDOW_WRITE_CODES) d.write else "-") +
        " owner_lost_at_s=" +
        (if (d.ownerLostAtS < 0) "-1" else java.lang.String.format(java.util.Locale.ROOT, "%.1f", d.ownerLostAtS)) +
        " sel_unexpected=" + d.selUnexpected +
        " restart_inputs=" + d.restartInputs +
        " rebases=" + d.rebases +
        " hyp_gap_ms=" + d.hypGapMs +
        " decode_ms_max=" + d.decodeMsMax +
        " decode_ms_p95=" + d.decodeMsP95 +
        " buffer_s=" + java.lang.String.format(java.util.Locale.ROOT, "%.1f", d.bufferS)

/** A4b: closed write codes of the window line (rule 9 — never contents). */
private val WINDOW_WRITE_CODES = setOf("same", "w1", "w3d", "delta", "-")

/**
 * F50 (PLAN_V0130 A2, V4): pure start gate decision for onMicDown —
 * serialization of the asynchronous start. IGNORE: a start is already running
 * (F11 P1) or a dictation session is running (no restart via DOWN).
 * SUPERSEDE: the deadline has expired (or a window rest), the old
 * start job is still running — it is cancelled (cancel + abort + FGS released),
 * then a FRESH start with a new coordinator begins. FRESH_START:
 * normal case. Pure function (no Android) — JVM-testable (StartGateTest).
 */
enum class MicStartGateAction { IGNORE, SUPERSEDE, FRESH_START }

fun micStartGate(
    startingUp: Boolean,
    sessionRecording: Boolean,
    oldStartJobActive: Boolean,
): MicStartGateAction = when {
    startingUp -> MicStartGateAction.IGNORE // F11 P1: loading runs — no second appointment
    sessionRecording -> MicStartGateAction.IGNORE // running session: DOWN starts nothing
    oldStartJobActive -> MicStartGateAction.SUPERSEDE // F50: detach the deadline remainder
    else -> MicStartGateAction.FRESH_START
}

/**
 * F50a (PLAN_V0130 A2): UP during the asynchronous (cold) start — the pure
 * decision whether the release acts as a REGULAR stop or as a
 * CAUGHT-UP stop with a transient hint ("Release pre-noted — recording ends
 * shortly"). Removes the silent flag (point of confusion C2-1) without
 * changing the KEEP_RUNNING design (P0 finding 2(a)). JVM-testable (StartGateTest).
 */
enum class MicUpDuringStartAction { PENDING_STOP_HINT, REGULAR_STOP }

fun micUpDuringStart(startingUp: Boolean): MicUpDuringStartAction =
    if (startingUp) MicUpDuringStartAction.PENDING_STOP_HINT
    else MicUpDuringStartAction.REGULAR_STOP

/**
 * B2 (PLAN §6.2 option A): upper limit per session — 900 s (15 min) for hold
 * AND toggle sessions (toggle practically irrelevant: F43 stops at the first
 * 2.5-s pause). Pure decision, JVM-testable (StartGateTest) — the source
 * itself (MAX_DURATION_MS 300 s) stays untouched, the service controls it.
 */
@Suppress("UNUSED_PARAMETER")
fun sessionMaxDurationMs(holdGesture: Boolean): Long = 900_000L

/**
 * B2 (PLAN §6.2 option A + §6.3-B2): warning due? — true EXACTLY ONCE per
 * session, from 13 min of session audio (780 000 ms = upper limit 900 s minus
 * 2 min lead time); only the session end path resets the
 * warned flag (onDictationStopped/abortDictation/onAborted). Hysteresis-free, pure.
 */
fun holdWarnDue(sessionMs: Long, warned: Boolean): Boolean =
    !warned && sessionMs >= 780_000L

/** AP7-E1 (Fable §0.2.5): decision at onStartInput — pure function, JVM-testable. */
internal enum class EditorRestartAction { REBASELINE, ABORT, NOTHING }

/**
 * W1 (vc34, debugger recommendation): preload decision at onStartInputView —
 * pure function, JVM-testable (pattern [editorRestartAction]): prewarm EXACTLY ONCE
 * per service lifetime. The second show stays a no-op (the engine
 * is resident or the dictation start loads lazily afterwards); a failed
 * attempt is NOT repeated.
 */
fun preloadDue(preloadTried: Boolean): Boolean = !preloadTried

/**
 * AP7-E1d (vc33 acceptance P0 blocker, restart:20 cell t=12 s): the re-baseline
 * must NOT abort immediately when the only loss reason is an
 * unreadable editor context (actLen=-1: getTextBeforeCursor caught
 * the old InputConnection dying in the restartInput re-bind) and the session
 * is still active — then the 150-ms retry takes the decision.
 * Real loss evidence (actLen>=0, suffix test verifiably failed) still aborts
 * immediately — no deferral in the face of proven danger.
 */
internal fun rebaseRetryDue(kept: Boolean, actLen: Int, sessionActive: Boolean): Boolean =
    !kept && actLen == -1 && sessionActive

/**
 * AP7-E1 (Fable §0.2.5 P1, vc33-T1 evidence): many target apps call
 * `InputMethodManager.restartInput()` on EVERY text change — so on
 * our FIRST partial write. Before, onStartInput killed the dictation
 * regardless of restarting=true via onEditorChanged → abort("editor_change")
 * (user death cause: ~2 s, first grammatically closed fragment).
 *
 * Decision rules (deterministic):
 *  - NO active session ⇒ [NOTHING] (cosmetic old path, idle no-op).
 *  - restarting=true AND same package AND field agreement: ⇒ [REBASELINE]
 *    — the same editor was rebound; possession re-anchor instead of abort
 *    (the tracker rebaseline checks whether our text is still there).
 *    AP7-E1-P2 (review): fieldId sentinels — if on ONE side fieldId ≤ 0
 *    ("unknown"; Android delivers 0/-1 per editor), the field does not block
 *    THIS side: then ONLY the package match decides (otherwise -1 vs 0
 *    would be a FALSE abort in real apps). If BOTH are > 0, the fields
 *    must be identical.
 *  - everything else (restarting=false — a real new bind; a different
 *    packageName/fieldId — a real editor change) ⇒ [ABORT] as before.
 */
internal fun editorRestartAction(
    restarting: Boolean,
    sessionActive: Boolean,
    baselinePkg: String?,
    baselineFieldId: Int,
    infoPkg: String?,
    infoFieldId: Int,
): EditorRestartAction {
    // AP7-E1-P2: the field counts as bound only if BOTH sides are > 0 — otherwise
    // "unknown" and NOT abort-effective (the sentinel does not block).
    val fieldAgrees =
        if (baselineFieldId > 0 && infoFieldId > 0) baselineFieldId == infoFieldId else true
    return when {
        !sessionActive -> EditorRestartAction.NOTHING
        restarting && infoPkg == baselinePkg && fieldAgrees -> EditorRestartAction.REBASELINE
        else -> EditorRestartAction.ABORT
    }
}

/**
 * AP7-W1 (Fable §0.1.5/0.1.6): generation-bound hold gate logic — PURE
 * class, JVM-testable ([de.voicekeyboard.ime.HoldGateGenerationTest]). The
 * service holds only the instance; all callers run on the main thread.
 *
 * Fix W1b: the gate was a global boolean that EVERY asynchronous session end
 * (onAborted of a predecessor session, a late onDictationStopped) nulled — without
 * knowing whether it was the session of the CURRENT gesture. Consequence: the
 * F43 auto-stop fired while the finger was held ("until the first punctuation → Ready").
 * Now every gesture gets a monotone generation ([begin]); [end] acts ONLY
 * on its own generation — a foreign reset is REJECTED and counted
 * ([foreignResets] ⇒ 🐞 gate_foreign_resets = positive W1b evidence).
 *
 * Foreign reset counting: only if a FOREIGN gesture would be active (activeGen != 0
 * and != gen) — a catch-up over an already open gate (normal
 * hold session: UP cleans up first, the asynchronous session end runs empty afterwards)
 * is harmless and does NOT increase the counter (no per-session noise).
 */
internal class HoldGate {
    private val seq = java.util.concurrent.atomic.AtomicLong(0)

    /** Active gesture generation; 0 = no gesture (gate open). */
    @Volatile var activeGen: Long = 0L
        private set

    /** Origin of the active gesture: "bar-hold" | "footer-hold" | "-" (none). */
    @Volatile var src: String = "-"
        private set

    /** Gesture under which the ACTIVE session started (0 = without a gesture, toggle). */
    @Volatile var sessionGen: Long = 0L

    /** Rejected foreign resets (would have deleted a NEWER, foreign gesture). */
    @Volatile var foreignResets: Int = 0
        private set

    /** ONLY setter source: new gesture with a fresh generation; return = gen for the UP. */
    fun begin(src: String): Long {
        val g = seq.incrementAndGet()
        activeGen = g
        this.src = src
        return g
    }

    /** start_ok: bind the active session to the CURRENT gesture (also 0 = toggle). */
    fun bindSession() {
        sessionGen = activeGen
    }

    /**
     * Generation-bound reset — nulls ONLY when activeGen == gen (also
     * 0 == 0: harmless catch-up over an open gate, NO foreign counter).
     * activeGen == 0 with gen != 0: equally harmless (UP came first) — ok=0.
     * Foreign reset (activeGen != 0 and != gen): REJECTED + counted (W1b).
     */
    fun end(gen: Long): Boolean {
        if (activeGen == gen) {
            activeGen = 0L
            return true
        }
        if (activeGen != 0L) foreignResets++
        return false
    }
}
