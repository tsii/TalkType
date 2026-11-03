package de.voicekeyboard.ime

import android.content.Intent
import android.inputmethodservice.InputMethodService
import android.text.InputType
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
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
 * Sprachwechsel-Zyklus (E9.1/E9.2, LANGUAGE_EMOJI_DECISIONS.md) — reine JVM-Logik,
 * keine Android-Typen (unit-testbar in LanguageCycleTest).
 *
 * Kanonische Reihenfolge = KONZEPT §2.2 / LAYOUT_REQUIREMENTS-Matrix.
 * Persistenz: Datei "vk_layout", Schlüssel active_languages (komma-getrennt,
 * geordnet, kein StringSet) und current_language.
 */
object LanguageCycle {

    /** Kanonische Reihenfolge der 15 Layout-IDs (KONZEPT §2.2). */
    val CANONICAL: List<String> = listOf(
        "de-de", "en-us", "fr-fr", "es-es", "it-it", "pt-pt", "nl-nl", "pl-pl",
        "tr-tr", "ru-ru", "cs-cz", "sv-se", "da-dk", "fi-fi", "el-gr",
    )

    /** Endonym (Sprachname in eigener Sprache, E9.3) + ISO-Tag je Layout. */
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

    /** Default aktive Sprachen (E9.1 Regel 2): heutiger Funktionsumfang. */
    val DEFAULT_ACTIVE: List<String> = listOf("de-de", "en-us")

    /**
     * Nächste aktive Sprache im kanonischen Zyklus (E9.1 Regel 3, wrap am Ende).
     * - nur eine aktive Sprache ⇒ null (kein stiller No-Op, UI zeigt Hinweis)
     * - aktuelle Sprache nicht in `active` ⇒ erste aktive Sprache
     * - Reihenfolge folgt CANONICAL, nicht der Eingabereihenfolge von `active`
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
     * Startsprache (E9.1 Regel 7): current, falls in active; sonst erste aktive;
     * sonst Default. Ergebnis ist nie leer.
     */
    fun initialLanguage(active: List<String>, current: String?): String {
        val ordered = CANONICAL.filter { it in active }
        if (ordered.isEmpty()) return DEFAULT_ACTIVE.first()
        if (current != null && current in ordered) return current
        return ordered.first()
    }

    /**
     * Parst active_languages (komma-getrennt): trimmt, verwirft Leerstrings und
     * Duplikate, filtert auf bekannte IDs, sortiert in kanonischer Reihenfolge.
     * Leer/unbekannt ⇒ Default (mindestens 1 Sprache ist immer aktiv).
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

    /** Serialisiert in kanonischer Reihenfolge, komma-getrennt (E9.1 Regel 7). */
    fun serializeActive(active: List<String>): String =
        CANONICAL.filter { it in active }.joinToString(",")

    /** Endonym für die Statusanzeige (E9.1 Regel 5 / M8b: Klartext, kein ISO-Code). */
    fun endonym(id: String): String = ENDONYMS[id]?.first ?: id
}

/**
 * Der IME-Service: besitzt den InputConnection-Zugriff, erzeugt ImeRootView
 * und verdrahtet seit M4c-2 den Diktatbetrieb (Push-to-Talk über die
 * Mic-Taste der Statuszeile).
 *
 * Reihenfolge beim Start (TECH_FACTS §4.1, while-in-use):
 * Berechtigungs-Check → FGS → Coordinator.start().
 * Beim Verstecken der IME (onWindowHidden/onFinishInputView) wird jede
 * laufende Aufnahme kontrolliert beendet — keine Aufnahme aus dem Hintergrund.
 * Logs: Tag "VK-IME", nur Zustände/Fehlercodes, nie Text oder Audio.
 */
class VoiceKeyboardService : InputMethodService(), KeyboardView.Listener {

    companion object {
        private const val TAG = "VK-IME"
        private const val MODEL_DIR_NAME = "models"
        private const val REQUEST_DICTATION_PERMISSIONS = 4101

        /**
         * F13a/A1: Start-Frist (Watchdog). Encoder-Ladung dauert Sekunden; der
         * Freeze-Bericht nennt 10–15 s — danach gilt der Start als gescheitert,
         * die Statuszeile erhält die Frist-Meldung und startingUp wird frei.
         */
        private const val START_DEADLINE_MS = 15_000L

        /**
         * F28c: FGS-Ready-Frist. startForeground muss VOR dem ersten
         * AudioRecord.startRecording() abgeschlossen sein; länger als so viel
         * (ms) ohne FGS-Ready ⇒ kein Mikro-Start, sichtbarer Fehler.
         * 4 s (F31b/REVIEW-P2: war 2 s) liegen deutlich über der FGS-Startfrist
         * auch bei KALTSTART (erster Prozessstart, startForeground > 2 s möglich)
         * und weit unter der Start-Frist [START_DEADLINE_MS].
         */
        private const val FGS_READY_TIMEOUT_MS = 4_000L

        /**
         * F13e: PURE Abbildung Fehlercode → String-Schlüssel (R-Konstante,
         * JVM-testbar ohne Android-Runtime — siehe ConsumerFaultTest).
         * null = unbekannt ⇒ generischer Fallback in mapVisibleError.
         */
        internal fun mapErrorTextRes(raw: String): Int? = when {
            raw.startsWith("editor_sync_timeout") -> R.string.dictation_error_editor_busy
            else -> null
        }
        private const val PERMISSION_PREFS = "vk_permissions"
        private const val PERMISSION_DENIED_FOREVER_PREFIX = "denied_forever_"

        /** E9.1 Regel 7: Persistenz Datei vk_layout (MODE_PRIVATE). */
        const val LAYOUT_PREFS = "vk_layout"
        const val KEY_ACTIVE_LANGUAGES = "active_languages"
        const val KEY_CURRENT_LANGUAGE = "current_language"
    }

    private lateinit var repository: LayoutRepository
    private var rootView: ImeRootView? = null

    // Diktat-Betrieb (M4c-2)
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var coordinator: DictationCoordinator? = null

    /**
     * F30 (Harness-Doku Option C): Debug-only AudioSource-Einstieg (Feld).
     * Im Release-Build bleibt das Feld IMMER null (der debug-Quellordner
     * FakeWavAudioSource wird dort nicht kompiliert) → AudioRecordSource,
     * Produktion unverändert. Im Debug baut z. B. [de.voicekeyboard.audio.
     * FakeWavAudioSource.build] das Szenario und setzt dieses Feld, bevor der
     * Coordinator gebaut wird (deterministisches WAV-Feeding, 10-ms-Blöcke).
     */
    var debugAudioSourceFactory: (() -> de.voicekeyboard.audio.AudioSource)? = null

    /** AP1 (PLAN_C2_FABLE_R2 §1.2): Debug-only Engine-Override — gesetzt von
     *  [de.voicekeyboard.debug.DebugHooks] (debug.vk.engine=stub:<alpha_ms_pro_s>:<base_ms>),
     *  gelesen in buildCoordinator. Release: bleibt null → echte ParakeetAsrEngine. */
    var debugEngineFactory: (() -> de.voicekeyboard.asr.AsrEngine)? = null
    private var router: AudioRouter? = null
    private val ownership = OwnershipTracker()
    private var fgsRunning = false

    /// AP1 (PLAN_C2_FABLE_R2 §1.2): VK-SESS-Zählerstände (nur fürs Debug-Protokoll).
    /** A3 (PLAN_V0133 §A3): Segment-Sitzungslog-Zustand — Baseline je Mic-DOWN;
     *  reine Entscheidung (SegmentSessionLog, JVM-testbar), kein sessLastSeg-Rohfeld mehr. */
    private val segSessionLog = SegmentSessionLog()
    private var sessLostShown = false

    /**
     * AP1 (PLAN_C2_FABLE_R2 §1.2): Sitzungsprotokoll-Tag VK-SESS für die C2-
     * Simulation — NUR Zustands-/Ursache-Codes und Zahlen, NIE Text (Regel 9).
     * Skript liest per `logcat -d -s VK-SESS` (Schritt 8/9).
     */
    private fun sess(cause: String) {
        val c = coordinator
        android.util.Log.d(
            "VK-SESS",
            "state=${c?.state ?: "NONE"} cause=$cause" +
                " audio_s=" + java.lang.String.format(java.util.Locale.ROOT, "%.1f", c?.audioSeconds ?: 0.0) +
                " out=${outputCounter.outputChars} seg=${c?.segmentFinalizations() ?: 0}",
        )
    }

    /** F42/F44 (F41-Repro, P1 — FGS-Leak): Ursache des LETZTEN FGS-Stops als
     *  kontrollierter Code (z. B. "abort:audio_error:silence", "stop",
     *  "fgs-timeout") — nur Codes, nie Inhalte (Regel 9). Forensik-Frage:
     *  läuft der Mikro-FGS noch — und wenn nein, wer hat ihn gestoppt? */
    @Volatile private var fgsStoppedBy: String = "-"

    // F28c: FGS-Ready-Handshake — true NUR nach abgeschlossener startForeground
    // (setzt CaptureForegroundService.onForegroundReady); erst dann darf das
    // Mikrofon geöffnet werden. Gesehen wird nur vom serviceScope-Worker.
    // ReentrantLock/Condition statt Object.wait/notify: die sind auf Android
    // aus der App-API gehide (compilieren nicht) — java.util.concurrent geht.
    private val fgsReadyLock = java.util.concurrent.locks.ReentrantLock()
    private val fgsReadyCondition = fgsReadyLock.newCondition()
    private var fgsReady = false

    /** R-2: blockierende Meldung bleibt bis zur nächsten bewussten Aktion stehen. */
    @Volatile private var stickyError: String? = null

    /**
     * F13a/B1: TRANSIENTER Statustext (Startmeldung) — strikt getrennt vom
     * stickyError; nie sticky, überholt bei RECORDING/FINALIZING/neuerem Fehler.
     */
    @Volatile private var transientStatus: String? = null

    /** F52: der aktuelle transiente Text ist ein HINT (StatusEvent.Hint), nicht
     *  die Startmeldung — Grundlage für status_res (Regel 9: nur Codes). */
    @Volatile private var transientIsHint = false

    /** F52 (PLAN_V0130 A3): Code/Ressourcen-Name des zuletzt gezeigten
     *  Statuszeilen-Texts (status_res, nie der Text selbst — Regel 9). */
    @Volatile private var statusResValue = "idle"

    /** AP3 (hint:<res-name>, Review-A3-P2): Ressourcen-NAME des letzten Hint-
     *  Events (Code, nie Text — Regel 9); status_res zeigt "hint:<res-name>". */
    @Volatile private var lastHintRes: String? = null

    /** Zeitstempel des transienten Textes für die Klärheitsregel — elapsedRealtime
     *  (monoton INKL. Deep-Sleep; uptimeMillis pausiert im Deep-Sleep und würde
     *  die 15-s-Startfrist und den Klärheitsvergleich aufweichen, Review F13c P2). */
    @Volatile private var transientSetAtMs: Long = 0L

    /** Zeitstempel des zuletzt beobachteten FEHLERS (sticky/visible) — Klärheitsregel. */
    @Volatile private var lastErrorSeenAtMs: Long = 0L

    /** F13a: Nachschlage-Frist für den asynchronen Start (Watchdog-Coroutine, kein Thread). */
    private var startDeadlineJob: kotlinx.coroutines.Job? = null

    /** F50 (PLAN_V0130 A2, V4): der aktive Start-Job — die Frist und ein
     *  Supersede canceln ihn; Invariante: zu jedem Zeitpunkt läuft höchstens
     *  EINE Start-Coroutine (kein Zombie-Diktat, kein Doppelstart). */
    @Volatile private var startJob: kotlinx.coroutines.Job? = null

    /**
     * AP7-W1 (Fable §0.1.5/0.1.6, W1b-Fix): Halte-Gate ist GENERATIONENGEBUNDEN
     * — reine Logik in [HoldGate] (JVM-testbar), der Service hält nur die
     * Instanz + die Touch-Generations-Merker (alle Aufrufer laufen auf Main).
     * [begin]/[end] sind die EINZIGEN Setz-Quellen von activeGen — ein
     * asynchron nachgezogenes Session-Ende einer VORGEHÄNGIGEN Session kann
     * das Gate einer NEUEREN Geste nicht mehr fälschlich öffnen.
     */
    private val holdGate = HoldGate()
    private var barTouchGen = 0L    // Statusleisten-Mikro: DOWN-Merker fürs UP
    private var footerTouchGen = 0L // Fußreihen-Mikro (onMicHoldStart/End)

    /** AP7-W1: gate_open_at_stop — Sample DIREKT vor dem Stopp (0/1; -1 = kein Stopp). */
    @Volatile private var gateOpenAtStopValue = -1

    // AP7-E1: Editor-Baseline der AKTIVEN Session (pkg+fieldId) + letzter
    // onStartInput-Stand — restartInput() liefert restarting=true mit
    // IDENTISCHEM Feld: KEIN Editorwechsel (Re-Baseline statt Abort).
    @Volatile private var lastEditorPkg: String? = null
    @Volatile private var lastEditorFieldId: Int = 0
    @Volatile private var sessionEditorPkg: String? = null
    @Volatile private var sessionEditorFieldId: Int = 0

    /** AP7-W1: endHold-Wrapper — reine Entscheidung + VK-SESS-Log (Codes only). */
    private fun endHold(gen: Long, cause: String) {
        val ok = holdGate.end(gen)
        sess("gate reset gen=$gen cause=$cause ok=${if (ok) 1 else 0} active=${holdGate.activeGen}")
    }

    /** B2 (PLAN §6.2 Option A): Warn-Hinweis „Obergrenze in 2 min" wurde für
     *  DIESE Session schon gezeigt (holdWarnDue-Flag); zurück nur über die
     *  Session-Ende-Pfade (onDictationStopped/abort/onAborted). */
    @Volatile private var holdWarnShown = false

    /** B2: Periodische Warnprüfung (Coroutine, Muster startDeadlineJob) —
     *  5-s-Takt über die SESSION-Audio (coordinator.audioMs); beendet sich
     *  selbst, wenn die Session inaktiv ist. */
    private var holdWarnJob: kotlinx.coroutines.Job? = null

    /** True, wenn die Start-Frist abgelaufen ist, bevor der Start fertig wurde. */
    @Volatile private var startAbandoned = false

    /** F13a: Einmal-Flag „abgeschlossener Erfolg" für R-2b (setzt onDictationStopped). */
    @Volatile private var successJustFinished = false

    /**
     * F11 P1: Der Start laeuft jetzt im `serviceScope`, weil `engine.load()`
     * (Encoder ~652 MB, nativ) im Touch-Listener dieselbe ANR-Klasse erzeugt wie
     * der zuvor behobene Modell-Hash. Die beiden Flags halten die Geste waehrend
     * des Ladens dicht: zweiter Tap => ignorieren, Loslassen => Nachreichen.
     */
    @Volatile private var startingUp = false
    @Volatile private var cancelStartRequested = false

    /**
     * D10-Rest §1: Loslassen während des asynchronen Starts — Art des Nachreichens.
     * UP (false) = Stop mit Finale; ACTION_CANCEL (true) = Abbruch ohne Finale.
     */
    @Volatile private var cancelStartIsAbort = false

    /**
     * D10-Rest §3: Zeichen-Ausgabe der AKTIVEN Diktat-Session (nur Zahlen,
     * Regel 9 — nie Textinhalt). Basis für „Kein Text erkannt“-Toast und
     * Commit-Häkchen; zurückgesetzt bei jedem Mic-DOWN.
     */
    private val outputCounter = de.voicekeyboard.ime.DictationOutputCounter()

    /** True, wenn ein STOP mit Final-Decode initiiert wurde (kein Abort). */
    @Volatile private var finalResultExpected = false

    /**
     * W1 (vc34, Debugger-Empfehlung): Engine-Vorwärmung beim ersten
     * onStartInputView bereits VERSUCHT — genau EINMAL pro Service-Lebenszeit
     * (Entscheidung [preloadDue]); ein fehlgeschlagener Versuch wird nicht
     * wiederholt und blockiert den Diktat-Start nicht (lazy-Fallback im
     * Coordinator-Start bleibt).
     */
    private var preloadTried = false

    /**
     * D10 §3 (b): vorgemerktes Ziel-Layout für den NÄCHSTEN Mic-DOWN.
     * Nur im Arbeitsspeicher (kein heimliches current_language vor Anwendung,
     * kein altes Pending nach Prozessneustart). Angewendet wird es vor Beginn
     * des neuen Captures — die laufende Session bleibt vollständig unberührt.
     */
    private var pendingLayoutId: String? = null

    override fun onCreate() {
        super.onCreate()
        // S12-01-FIX: GERAETE-RAM (ActivityManager.totalMem) an die ASR-Konfig
        // reichen — nicht den Java-Heap (Regression S12-01: maxMemory löste
        // auf jedem Telefon die 2-GB-Dekkelung aus ⇒ intra=2 statt 4).
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
        // E9.1 Regel 5/7: Layout kommt aus current_language (vk_layout),
        // nicht mehr fest de-de; Fallback über loadOrFallback (de-de/en-us).
        view.keyboardView.setLayout(
            repository.loadOrFallback(currentLanguage()),
            resources.displayMetrics.widthPixels,
        )
        // E9.4/V2 (M8b-2/V2): Emoji-Asset laden; Einfuegen nur ueber onTextInput (bestehender Weg).
        // Rueckweg-Button (V2 §3): ein Tap → onDismissRequested → hideEmojiPanel; idempotent,
        // kein Textcommit, kein Fokusanfordern. Panel bleibt nach Einfuegen offen.
        view.emojiPanel.listener = object : de.voicekeyboard.keyboard.EmojiPanelView.Listener {
            override fun onEmojiSelected(emoji: String) {
                // V2 §3.6: Zustand ≠ IDLE sperrt das Einfuegen; Meldung bleibt,
                // der Rueckbutton wird nie deaktiviert.
                val c = coordinator
                if (c != null && c.state != de.voicekeyboard.dictation.DictationState.IDLE) {
                    postEvent(hintEvent(R.string.emoji_blocked_recording))
                    return
                }
                onTextInput(emoji) // bestehender Schreibweg: ic.commitText(text, 1)
            }

            override fun onDismissRequested() {
                view.emojiPanel.closeAndResetPage() // Kategorie bleibt, Seite → 1 (V2 §3.3)
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
        // Push-to-Talk: Touch-Down startet, Touch-Up/Cancel stoppt (Hold-Semantik)
        view.micButton.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    v.performClick()
                    // AP7-W1: EINE Setz-Quelle begin() — neue, monitone Generation.
                    barTouchGen = holdGate.begin("bar-hold")
                    sess("gate set gen=$barTouchGen src=bar-hold")
                    onMicDown()
                    true
                }
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                    // AP7-W1: UP/CANCEL endet NUR die eigene Generation.
                    endHold(barTouchGen, if (event.actionMasked == android.view.MotionEvent.ACTION_UP) "up" else "cancel")
                    onMicUp()
                    true
                }
                else -> false
            }
        }

        // P0 (2026-11-01): 🐞 lebt FEST in der statusRow (ImeRootView.debugButton,
        // 48 dp, rechts) — vorher dynamisches addView (brüchig/ungesichert).
        // Hier nur der Listener: ein Tap = Export.
        view.debugButton.setOnClickListener { onDebugInfoRequested() }
        rootView = view
        return view
    }

    override fun onStartInput(info: EditorInfo, restarting: Boolean) {
        super.onStartInput(info, restarting)
        if (restarting) coordinator?.noteRestartInput() // AP3: restart_inputs-Zaehler
        // AP7-E1 (Fable §0.2.5 P1, vc33-T1): restartInput() der App auf unseren
        // ERSTEN Partial-Schrieb ist KEIN Editorwechsel — vorher tötete das
        // ungeachtete restarting=true die Session (onEditorChanged →
        // abort("editor_change"), Nutzer-Todesursache ~2 s). Reine Entscheidung:
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
                // Session bleibt RECORDING; Besitz neu verankern. Hat die App
                // unseren Text im Restart gelöscht/geändert: kontrollierter
                // Abort (kein Geisterschreiben in einen veränderten Editor).
                val kept = coordinator?.rebaselineOwnership() ?: true
                // AP7-E1c: Laengen-Diagnose (nur Zahlen, Regel 9) — exp/act
                // zeigen sofort, ob die Re-Baseline gegen einen veralteten
                // oder einen fremden Editor-Stand entschied.
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
                        // AP7-E1d (vc33-Abnahme, restart:20 t=12 s): act=-1 =
                        // Lesefenster ins Re-Bind — die getextBeforeCursor
                        // erwischte die sterbende alte InputConnection. Das ist
                        // KEIN Verlustbeweis. EINMAL 150 ms Retry (nicht
                        // blockierend — die Session laeuft solange RECORDING
                        // weiter; die Entscheidung faellt ueber den DANN
                        // aktuellen Zustand, neue Restarts veraendern ihn mit).
                        sess("rebase retry=1 due=150ms")
                        // AP7-E1d: Baseline SOFORT öffnen — der Re-Bind-Callback
                        // der App kommt innerhalb der 150 ms und muss STILL
                        // verbraucht werden (sonst: lost ⇒ stiller
                        // Schreibstopp hinter geretteter Session, H-E2).
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
                        // AP7-E1-P2 (Mini-UX): transienter Hinweis statt stillem
                        // Sticky-Ende — kein Toast, hintEvent-Naht (res-Name in
                        // status_res, Regel 9).
                        postEvent(hintEvent(R.string.editor_rebaseline_lost_hint))
                    }
                }
            }
            EditorRestartAction.ABORT -> {
                sess("input restarting=$restarting abort=editor_change")
                // F28b2: Editorwechsel = Session-Ende-Ereignis (wer: editor).
                noteMicRel("editor")
                closeEmojiPanelIfVisible()
                // Editorwechsel: Besitz verwerfen — kein Schreiben in den alten Kontext
                coordinator?.onEditorChanged()
            }
            EditorRestartAction.NOTHING -> {
                // Keine aktive Session: kosmetischer Alter Pfad (Idle-No-Op).
                noteMicRel("editor")
                closeEmojiPanelIfVisible()
                coordinator?.onEditorChanged()
            }
        }
        // AP7-E1: letzter Editor-Stand für die NÄCHSTE Session-Baseline.
        lastEditorPkg = info.packageName
        lastEditorFieldId = info.fieldId
    }

    /** V2 §3.7 (aus onStartInput gezogen): Panel zu — kein Wiedererscheinen
     *  mit alten Touchereignissen im nächsten Feld. */
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
        // W1 (vc34, Debugger-Empfehlung): Beim ERSTEN Sichtbarwerden der IME die
        // Engine vorwärmen — Single-Flight im Coordinator; load() braucht weder
        // Mikro noch FGS. Genau EINMAL pro Lebenszeit ([preloadTried], reine
        // Entscheidung [preloadDue]); ein fehlgeschlagener Preload blockiert den
        // späteren Diktat-Start NICHT. NUR im Worker-Scope — load ist teuer
        // (F11 P1: ~4,5 s Kaltstart, nie Main-Thread/ANR).
        if (preloadDue(preloadTried)) {
            preloadTried = true
            val c = coordinator ?: buildCoordinator().also { coordinator = it }
            serviceScope.launch { c.preload() }
        }
        updateStatus()
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        // F28b2: IME verschwindet = Session-Ende-Ereignis (wer: hidden).
        noteMicRel("hidden")
        // B2 (PLAN_V0133 §B2): Editorwechsel stoppt den Backspace-Repeat-
        // Timer deterministisch (kein Timer nach Editorwechsel/Detach).
        rootView?.keyboardView?.cancelBackspaceRepeat()
        // V2 §3.7: IME-Ausblenden setzt den Modus zurueck auf Tastatur.
        rootView?.let { v ->
            if (v.isEmojiPanelVisible) {
                v.emojiPanel.closeAndResetPage()
                v.hideEmojiPanel()
            }
        }
        // While-in-use (TECH_FACTS §4.1): Aufnahme endet, sobald die IME verschwindet
        stopDictationIfRunning()
        super.onFinishInputView(finishingInput)
    }

    override fun onWindowHidden() {
        noteMicRel("hidden")
        stopDictationIfRunning()
        super.onWindowHidden()
    }

    override fun onDestroy() {
        // N-3: KEIN stopDictationIfRunning() (würde einen Final-Worker starten,
        // der sofort gekillt wird) — direkt abort+close, Reihenfolge garantiert.
        coordinator?.abort("ime_destroy")
        stopFgs("ime_destroy")
        router?.stopObserving()
        // C-4: Engine-Sessions (≈863 MB nativ) schließen, dann Scope kündigen.
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
        // AP1: VK-SESS — Besitzverlust nur bei WECHSEL melden (kein Spam je Callback).
        if (ownership.ownershipLost && !sessLostShown) {
            sess("sel lost=1")
            sessLostShown = true
        } else if (!ownership.ownershipLost) {
            sessLostShown = false
        }
    }

    // ---- Push-to-Talk ----

    private fun onMicDown() {
        sess("mic_down") // AP1: VK-SESS — Finger runter (Zahlen/Codes only)
        // A3 (PLAN_V0133 §A3): neue Diktat-Sitzung — Segment-Baseline NEU öffnen.
        // Sonst loggte der Zählerstand der BEENDETEN Session (seg=1) gegen den
        // neuen Zähler 0 ein Altartefakt-`seg_final` noch VOR `start_ok`
        // (Befund 18:56, PLAN §0.1) — kein verspätetes Mini-Finale.
        segSessionLog.onMicDown()
        // F50 (PLAN_V0130 A2, V4): Start-Serialisierung über EINE reine
        // Entscheidung (micStartGate) — zu jedem Zeitpunkt läuft höchstens EINE
        // Start-Coroutine; eine abgelaufene wird abgebrochen, ihr Ergebnis
        // verworfen (kein Zombie-Diktat über das active-CAS-Duell, V4).
        when (
            micStartGate(
                startingUp = startingUp,
                sessionRecording = coordinator?.state == de.voicekeyboard.dictation.DictationState.RECORDING,
                oldStartJobActive = startJob?.isActive == true,
            )
        ) {
            MicStartGateAction.IGNORE -> return // F11 P1 / laufende Session
            MicStartGateAction.SUPERSEDE -> {
                // F50: Rest eines abgelaufenen Starts ablösen — cancel +
                // Zombie-Abbruch + FGS frei; der frische Start unten baut einen
                // NEUEN Coordinator (keine geteilte active-Instanz alt/neu).
                startJob?.cancel()
                coordinator?.abort("start_superseded")
                coordinator = null
                stopFgs("superseded")
            }
            MicStartGateAction.FRESH_START -> Unit
        }
        // D10 §3: vorgemerktes Layout VOR Beginn des neuen Captures anwenden
        // (nur im IDLE-Neustart; ein laufender Start wird nicht umgeleitet).
        if (coordinator?.state == de.voicekeyboard.dictation.DictationState.IDLE) {
            applyPendingLayoutBeforeCapture()
        }
        // V2 §3.6: Mic-Druck bei offenem Panel: erst Emoji schliessen, dann PTT —
        // kein bedienbares Emoji-Grid ueber einer Aufnahme.
        rootView?.let { v ->
            if (v.isEmojiPanelVisible) {
                v.emojiPanel.closeAndResetPage()
                v.hideEmojiPanel()
            }
        }
        stickyError = null // R-2: bewusste neue Aktion löscht die blockierende Meldung
        lastErrorSeenAtMs = 0L // F13a: Klärheits-Zeitstempel je Session neu
        transientStatus = null
        transientSetAtMs = 0L
        successJustFinished = false
        outputCounter.reset() // D10-Rest §3: Urteil je Aufnahme (F12: nie nachwirkend)
        if (!ensurePermissions()) return
        // C-2: Modell muss bereit sein, BEVOR FGS/Coordinator starten — kein
        // stilles Mikrofon über einer nicht funktionierenden Pipeline.
        // F11 P0: isReadyQuick statt isReady — die Vollprüfung über ~670 MB auf dem
        // Main-Thread erzeugte eine ANR der IME (Mic-Tap blieb 5 s hängen).
        val modelDirFile = java.io.File(filesDir, MODEL_DIR_NAME)
        val store = de.voicekeyboard.model.ModelStore(modelDirFile)
        if (!store.isReadyQuick()) {
            postEvent(StatusEvent.Error(getString(R.string.dictation_error_model_absent)))
            Log.i(TAG, "model not ready; status=${store.statusQuick()}")
            return
        }
        startFgs()
        // F11 P1: engine.load() laedt den ~652-MB-Encoder nativ und lief bisher
        // synchron im Touch-Listener (dieselbe ANR-Klasse wie der ModelStore-Hash).
        // Der Start arbeitet im serviceScope, die UI-Arbeit danach auf dem Main-Thread.
        // F13a/B1: Die Startmeldung ist TRANSIENT — sie landet NICHT im stickyError
        // und verdeckt damit keine späteren Fehler/Zustände mehr.
        postEvent(StatusEvent.Starting(getString(R.string.dictation_starting)))
        startingUp = true
        // F50 (V4): startAbandoned setzt NUR der Frist-Owner — vorher hob JEDES
        // DOWN das Flag auf, sodass ein abgelaufener Start als gültig weiterlief
        // (Zombie-Kandidat). Ein frischer DOWN superseded stattdessen (oben).
        // F13a: kooperative Start-Frist als Nachschlagetimer (Coroutine, kein Thread,
        // blockiert nichts): läuft der Start nach der Frist noch, Meldung anzeigen
        // und startingUp freigeben, damit der nächste Mic-Druck wieder funktioniert.
        startDeadlineJob?.cancel()
        startDeadlineJob = serviceScope.launch {
            kotlinx.coroutines.delay(START_DEADLINE_MS)
            kotlinx.coroutines.withContext(Dispatchers.Main) {
                if (startingUp) {
                    startingUp = false
                    startAbandoned = true
                    cancelStartRequested = false
                    cancelStartIsAbort = false
                    // F50: Die Frist CANCELT den Start-Job (vorher: nur Flags — die
                    // Coroutine lief weiter und konnte nach dem Laden ein Zombie-
                    // Diktat starten; das Abandoned-Flag blieb nur der Rückfall).
                    startJob?.cancel()
                    transientStatus = null
                    transientSetAtMs = 0L
                    postEvent(StatusEvent.Error(getString(R.string.dictation_start_too_long)))
                }
            }
        }
        // B2 (PLAN §6.2 Option A): Periodische Warnprüfung — ab 13 min Session-
        // Audio (780 s = Obergrenze 900 s minus 2 min Vorlauf) GENAU EINMAL ein
        // transienter Hint (reine Entscheidung [holdWarnDue], Muster start-
        // DeadlineJob: Coroutine, kein Thread). Selbstbeendet, wenn die Session
        // inaktiv ist; Flag [holdWarnShown] zurück nur bei Session-Ende.
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
                    ) // V7-Naht: Hint → transienter Kanal (StatusRoutingTest)
                }
            }
        }
        startJob = serviceScope.launch {
            var started = false
            var outOfMemory = false
            var thrown: Exception? = null
            var mine: DictationCoordinator? = null // F50: nur DIESE Instanz abwürgen
            try {
                // F28c: AudioRecord darf erst laufen, wenn startForeground
                // ABGESCHLOSSEN ist (vorher: startForegroundService war nur
                // asynchron gesteckt — das Mikro konnte zuerst öffnen und das
                // System die Aufnahme stumm stoppen, Export #2: Ende nach 1,0 s).
                // FGS-Scheitern (Timeout) = sichtbarer Fehler, KEIN Audio.
                if (!awaitFgsReady(FGS_READY_TIMEOUT_MS)) {
                    Log.w(TAG, "fgs not ready in time; no audio start") // nur Kennzahl
                    stopFgs("fgs-timeout")
                    withContext(Dispatchers.Main) {
                        startingUp = false
                        postEvent(StatusEvent.Error(getString(R.string.dictation_error_audio_start)))
                    }
                    return@launch
                }
                try { // C-5: Audio-/Init-Wuerfe nie an den TouchListener durchreichen
                    // AP1: Debug-Hooks (System-Properties, LIVE gelesen) je Coordinator-Bau
                    // anwenden — Release-Stub ist no-op (release-Sourceset-Paarung).
                    de.voicekeyboard.debug.DebugHooks.apply(this@VoiceKeyboardService)
                    if (coordinator == null) {
                        coordinator = buildCoordinator()
                    }
                    mine = coordinator // F50: NUR diese Instanz im CE-Fang abwürgen
                    // F50: kooperativer Supersede-Check — ist der Start-Job
                    // inzwischen abgebrochen (Frist/zweiter DOWN): KEIN start(),
                    // sonst Zombie-Diktat (die zweite Start-Coroutine verlöre
                    // das active-CAS-Duell, V4).
                    if (!isActive) {
                        Log.w(TAG, "start superseded before audio start") // nur Kennzahl
                        stopFgs("superseded")
                        return@launch
                    }
                    started = mine?.start() ?: false
                // P0-Befund2(b/c): ABORT-Beobachter (vorher: Audio-Error-Aborts
                //  wie silence/stall/endeten die Session OHNE UI-Sync — das Mikro
                // blieb grün, der Statuszeilen-Text stand still). Der Coordinator
                // hopt den Callback auf Main (wie onStopped).
                // F44 (F41-Repro P1): JEDE Abort-Art stoppt AUCH den Mikro-FGS —
                // vorher lief die Vordergrund-Aufnahme nach Watchdog-/Fehler-
                // Abbrüchen weiter (~58 s im Emulator). Ursache als fgs_stopped_by.
                // AP7-W1: Generation VOR dem (asynchronen) Callback einfangen —
                // die Closure endet NUR die Geste ihrer EIGENEN Session; ein
                // verspäteter Abort einer Vorgänger-Session kann das Gate der
                // neueren Geste nicht mehr fälschlich öffnen (W1b-Race-Pfad).
                val genAtStart = holdGate.activeGen
                mine?.onAborted = { reason ->
                    sess("aborted:$reason") // AP1: VK-SESS — Watchdog-/Fehlerabbruch
                    syncMicUi()
                    stopFgs("abort:$reason")
                    // B2b: Session-Ende — Warn-Flag zurück; das Gate läuft über
                    // die generationengebundene endHold-Entscheidung (AP7-W1).
                    endHold(genAtStart, "aborted")
                    holdWarnShown = false
                    updateStatus()
                }
                } catch (e: OutOfMemoryError) {
                    // A2/E5 (g): OOM ist ein Error — catch(Exception) sieht ihn nicht.
                    outOfMemory = true
                    Log.w(TAG, "start failed: OutOfMemoryError")
                } catch (e: Exception) {
                    // S5-3/E5: Rohcode NUR ins Log, in der UI Nutzertext
                    thrown = e
                    Log.i(TAG, "start failed: ${e.javaClass.simpleName}") // nur Klasse, kein Text
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
                        // F13a: Die Frist ist abgelaufen — der verspätete Start wird
                        // NICHT zum Zombie-Diktat; FGS sauber abgebaut.
                        if (started) {
                            coordinator?.abort("start_abandoned")
                        }
                        stopFgs("start_abandoned")
                        syncMicUi() // P0-Befund2(b/c): Zustand folgt der Session
                        return@withContext // Statuszeile zeigt bereits die Frist-Meldung
                    }
                    if (started) {
                        // T3 (v0.1.35): ein frischer Tap (TOGGLE_ON) setzt KEIN Flag —
                        // die Session läuft (RUN). wantedStop ist jetzt NUR der nach-
                        // gereichte Hold-Release (>300 ms): er beendet die frische
                        // Session SAUBER mit Finale (STOP_AFTER_START, nicht mehr blind
                        // KEEP_RUNNING — Beweis /tmp/vk_phone_e2.log Z.74-97).
                        // CANCEL (wantedAbort) bleibt Abbruch ohne Finale (D10-Rest §1).
                        when (micStartFinishedAction(wantedStop, wantedAbort)) {
                            MicStartFinishAction.ABORT -> abortDictation("michold_cancel")
                            MicStartFinishAction.STOP_AFTER_START -> stopDictationIfRunning()
                            MicStartFinishAction.RUN -> syncMicUi() // D10-Rest §2
                        }
                        updateStatus()
                        holdGate.bindSession() // AP7-W1: sessionHoldGen = aktive Geste
                        gateOpenAtStopValue = -1 // AP7-W1: Sample je Session neu
                        // AP7-E1: Editor-Baseline der Session merken — alle
                        // restarting=true onStartInputs MIT diesem Feld sind
                        // restartInput() der App, KEIN Editorwechsel.
                        sessionEditorPkg = lastEditorPkg
                        sessionEditorFieldId = lastEditorFieldId
                        sess("start_ok") // AP1: VK-SESS — Session läuft (state=RECORDING)
                        return@withContext
                    }
                    stopFgs("start-failed") // FGS-Leak verhindern
                    sess("start_failed") // AP1: VK-SESS — Start gescheitert
                    syncMicUi() // P0-Befund2(b/c): Fehlschlag => nie grün ohne Session
                    updateStatus(
                        when {
                            outOfMemory -> getString(R.string.dictation_error_out_of_memory)
                            thrown != null -> startErrorText(store, thrown!!)
                            // P1 (Review F6): echter Grund statt pauschal „Audio-Start“
                            else -> mapVisibleError(coordinator?.visibleError ?: "unknown")
                        },
                    )
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                // F50: Start-Job wurde abgebrochen (Frist oder Supersede). Eine
                // bereits GESTARTETE Session darf kein Zombie-Diktat bleiben; der
                // FGS darf nicht leaken. Re-throw: die Coroutine endet als
                // „abgebrochen“ — die Frist-/Supersede-Owner haben die UI bereits
                // informiert (kein zusätzlicher „Start fehlgeschlagen“-Text).
                if (started) mine?.abort("start_superseded")
                stopFgs("superseded")
                throw e
            } finally {
                // F13a/A1: startingUp darf NIE haften bleiben — auch nicht bei einem
                // Error ausserhalb der gefangenen Klassen oder bei Cancellation.
                // UI-Flags auf Main; kein Blockieren (withContext, kein runBlocking).
                // F50: NonCancellable — dieser finally läuft AUCH im abgebrochenen
                // Start-Job (vorher war Cancellation praktisch unerreichbar).
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
        // F28b2: Nutzer hat das Mikro losgelassen (wer: up) — am Eintrag notiert,
        // denn auch der startingUp-Zweig reicht genau dieses Loslassen nach.
        noteMicRel("up")
        // F50a: reine Entscheidung — UP während des (Kalt-)Starts ist KEIN
        // regulärer Stopp, sondern die Nachreichung plus ein transienter Hint
        // („Loslassen vorgemerkt"): das bisher STILLE Flag war der
        // Verwirrungspunkt (C2-1). KEEP_RUNNING-Design (P0-Befund2(a)) bleibt.
        when (micUpDuringStart(startingUp)) {
            MicUpDuringStartAction.PENDING_STOP_HINT -> {
                cancelStartIsAbort = false // nachgereichtes Loslassen ist ein STOP, kein Abort
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

    /** F28b2: notiert das Session-Ende-Ereignis als reinen Code + ms seit
     *  Sessionstart (Regel 9: keine Inhalte). Letzter Write gewinnt — der
     *  🐞-Export zeigt das Ereignis, das die Session zuletzt beendet hat. */
    private fun noteMicRel(kind: String) {
        val c = coordinator ?: return
        val started = c.sessionStartedAtMs
        val ms = if (started > 0L) (System.currentTimeMillis() - started).coerceAtLeast(0L) else 0L
        c.setMicRel("$kind:$ms")
    }

    /** P0-Befund2(b/c): EINE Quelle für den sichtbaren Mic-Zustand — grün
     *  NUR, solange die Session wirklich RECORDING ist. Wird an ALLEN
     *  Terminierwegen aufgerufen (Start-Ende/Fehler/Frist/Stop/Abort). */
    private fun syncMicUi() {
        val recording = micUiRecording(coordinator?.state)
        rootView?.let { v ->
            v.keyboardView.micRecording = recording
            v.micButton.isActivated = recording
        }
    }

    /** Snapshot füllen (Regel 9: nur Zahlen/Codes) und exportieren (Clipboard + Teilen). */
    /**
     * AP3 (hint:<res-name>, Review-A3-P2): Hint-Ereignis über EINE Naht —
     * merkt den Ressourcen-NAMEN als Code fuer status_res ("hint:<res-name>",
     * nie der Text — Regel 9). Alle Hint-Stellen rufen diese Funktion.
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
            shouldShowRationale = { false }, // IME kennt rationale-Frage nicht (C-3-Muster)
        )
        val snapshot = collector.collect(
            modelStore = store,
            modelVariant = de.voicekeyboard.model.ModelVariant.ONNX_CPU, // V0: einzige Produktionsvariante (buildCoordinator)
            coordinator = coordinator,
            threadConfig = de.voicekeyboard.asr.AsrThreadConfig.forDevice(
                Runtime.getRuntime().availableProcessors(),
                de.voicekeyboard.asr.AsrThreadConfig.deviceRamMb,
            ),
            permissions = permissions,
            // F10: Aufnahmeweg und -eingang — ohne diese beiden Zeilen ist der
            // Telefonbefund „peak_pct: 2" nicht zu deuten (Regel 9: nur Kurznamen).
            audioRoute = routeLabel(router?.currentRoute ?: de.voicekeyboard.audio.Route.BUILTIN_MIC),
            audioSource = de.voicekeyboard.audio.AudioRecordSource.SOURCE_NAME,
            // F42 (🐞: had_output/output_chars/fgs_stopped_by): Ausgabe-Forensik
            // (nur Zahlen) + FGS-Stopp-Ursache aus F44 — nie Textinhalte (Regel 9).
            hadOutput = outputCounter.hasOutput,
            outputChars = outputCounter.outputChars,
            fgsStoppedBy = fgsStoppedBy(),
            statusRes = statusResValue, // F52: zuletzt gezeigter Statustext als Code (Regel 9)
            // AP7-W1: Service-seitiges Stop-Moment-Sample (feiner als die
            // Coordinator-Schätzung) + abgewiesene Fremd-Resets (W1b-Beweis).
            gateOpenAtStop = gateOpenAtStopValue,
            gateForeignResets = holdGate.foreignResets,
        )
        // Regel 9: der Exporttext wird NIE geloggt — nur Format/Status.
        val text = de.voicekeyboard.debug.DebugInfoFormatter.format(snapshot)
        collector.export(text)
        // AP1 (PLAN_C2_FABLE_R2 §1.2): debug.vk.export_file=1 → ZUSÄTZLICH nach
        // files/debug_export.txt (identischer Inhalt; Release-Stub = no-op).
        de.voicekeyboard.debug.DebugHooks.onExport(this, text)
        postEvent(hintEvent(R.string.diagnose_copied_share_opened)) // F13c: Hinweis, transient (F51/K2: Ressource; AP3: res-Name in status_res)
    }

    private fun stopDictationIfRunning() {
        val c = coordinator
        // AP7-W1: gate_open_at_stop — Sample DIREKT vor dem Stopp (0/1).
        gateOpenAtStopValue = if (holdGate.activeGen == 0L) 1 else 0
        // F34/P2-1 (REVIEW_F29_F33): auch OHNE Coordinator darf der Mic-Zustand
        // nicht hängen — Fokusverlust/IME-Ausblenden sind Endpfade (vorher:
        // `?: return` sparte View-Sync und updateStatus komplett).
        if (c != null) {
            if (c.state == de.voicekeyboard.dictation.DictationState.RECORDING ||
                c.state == de.voicekeyboard.dictation.DictationState.STARTING
            ) {
                finalResultExpected = true // D10-Rest: Finale erwartet → Toast/Häkchen bewerten
                c.stop() // Final-Decode bis fertig, drain (Coordinator-M4c-1)
            } else {
                // F13b/C1: Loslassen über einer STILL GESCHEITERTEN Session (state von
                // fail() bereits IDLE) — der Fehlercode VOR abort() sichern (abort
                // überschreibt lastErrorCode mit "aborted"), sticky anzeigen.
                if (c.failedThisSession) {
                    val code = c.visibleError
                    c.abort("michold_cancel")
                    stickyError = code?.let { mapVisibleError(it) }
                } else {
                    c.abort("michold_cancel")
                }
            }
        }
        syncMicUi() // D10-Rest §2 + F34: Zustandsfläche immer frei (grün ⇔ RECORDING)
        stopFgs("stop") // regulärer Stopp (auch F43-Auto-Stop läuft über c.stop())
        updateStatus()
    }

    private fun buildCoordinator(): DictationCoordinator {
        // D10-Rest §3: Ausgabe-Zähler um den echten Sink — zählt NUR Zeichen
        // (Regel 9), kein Textinhalt; auf dem Edit-Executor (Main) synchron.
        val sink = CountingSink(InputConnectionSink(connectionProvider = { currentInputConnection }))
        val routerRef = router
        return DictationCoordinator(
            scope = serviceScope,
            // F9 P0: onStopped laeuft im Worker (serviceScope = Default). Ohne Main-
            // Dispatcher ruft der Coordinator sein UI-Finale im Worker-Kontext ->
            // CalledFromWrongThreadException auf der neuen Infoleisten-TextView und
            // damit ein toter IME-Prozess (Nutzer: „Eingaben werden ignoriert“).
            mainDispatcher = kotlinx.coroutines.Dispatchers.Main,
            // F49-Wiring (PLAN_V0130 A2): Auto-Stop unterdrückt, solange eine
            // Halte-Geste den Finger unten hält („noch nicht fertig"); Toggle-
            // Sessions laufen mit offenem Gate („Pause = Ende", 2,5 s, V12).
            autoStopSuppressed = { holdGate.activeGen != 0L }, // AP7-W1: generationengebunden
            audioSourceFactory = {
                // F30 (Harness-Doku Option C): Debug-only-Umschaltung über das Feld
                // oben (Release: null → AudioRecordSource wie vorher).
                debugAudioSourceFactory?.invoke()
                    ?: AudioRecordSource(
                        // B2 (PLAN §6.2 Option A): Obergrenze je Session —
                        // Hold 900 s (15 min) statt 300 s; Toggle erhält denselben
                        // Wert (praktisch irrelevant: F43 stoppt Toggle ohnehin
                        // an der ersten 2,5-s-Pause). Reine Entscheidung
                        // [sessionMaxDurationMs], JVM-testbar (StartGateTest).
                        maxDurationMs = sessionMaxDurationMs(holdGesture = true),
                        errorListener = coordinator?.errorListener,
                        levelListener = AudioLevelListener { rms, _ ->
                            // Zeigt in der Diagnosezeile, ob ueberhaupt Ton ankommt
                            // (Regel 9: nur ein Maximalwert, nie Audiodaten).
                            coordinator?.noteLevel(rms)
                        },
                    )
            },
            engineFactory = {
                // AP1 (PLAN_C2_FABLE_R2 §1.2): Debug-Hook darf die Engine ersetzen
                // (LatencyStubEngine, Echtzeituhr-Sleep) — Release: null → echt.
                debugEngineFactory?.invoke() ?: ParakeetAsrEngine()
            },
            sink = sink,
            tracker = ownership,
            routeObserver = { on -> if (on) routerRef?.startObserving() else routerRef?.stopObserving() },
            // B2 (PLAN §6.3 (c), I3-Kurve α=38,5 ms/s, superlinear ab ~60 s):
            // Segmenter JETZT scharf für ALLE Sessions — 35-s-Deckel hält jedes
            // Decode-Fenster ≤ ~37 s ⇒ decode ≈ 1,35 s (unter der 1,5-s-W der
            // Technik-Stufe); Satzpausen schneiden weich an Satzenden (Regel a).
            // Gate-unabhängig: der Auto-Stop (F43) bleibt vom Segmenter getrennt.
            segmentFinalizeMs = 35_000,
            // C-2/C-4: load im Coordinator. Wichtig: der Store-Ordner selbst ist
            // NICHT das Modellverzeichnis — die Dateien liegen in
            // versions/<aktiv>. Die Engine prueft direkt auf vocab.txt und
            // wirft sonst „vocab.txt fehlt“, obwohl der Store READY meldet.
            modelDir = de.voicekeyboard.model.ModelStore(
                java.io.File(filesDir, MODEL_DIR_NAME),
            ).modelDir(),
            onStopped = { onDictationStopped() }, // C-1: UI nach dem Worker-Finale aktualisieren
        ).also { c ->
            // Route der UI nach außen reichen (Anzeige über onRouteChanged unten)
            routerRef?.routeListener = { c.onRouteChanged(routeLabel(it)); updateStatus() }
            // A4b (PLAN_V0133 §A4b): nach JEDEM Decode-Fenster genau EINE kompakte
            // VK-ASR-Zeile — Log.i OHNE Debug-Guard (Release-wirksam; die reine
            // Formatierung [decodeWindowLine] ist JVM-getestet, wirft nie, kein
            // Abort durch Diagnose); out synchron vom bestehenden outputCounter;
            // die Engine-Stufenzeile (ParakeetAsrEngine) bleibt unangetastet;
            // kein Log zwischen Fenstern, keine Dauerschleife.
            c.onDecodeWindow = { d ->
                android.util.Log.i("VK-ASR", decodeWindowLine(d, outputCounter.outputChars))
            }
        }
    }

    /**
     * D10-Rest §3: zählt die Nettto-Zeichen-Ausgabe der aktiven Session — nur
     * Zahlen (Regel 9), kein Textinhalt, kein Pfad. Delegiert alles an [inner].
     */
    private inner class CountingSink(private val inner: de.voicekeyboard.editor.EditorSink) :
        de.voicekeyboard.editor.EditorSink {

        override fun replaceBeforeCursor(replaceLength: Int, newText: String) {
            inner.replaceBeforeCursor(replaceLength, newText)
            outputCounter.onCommit(newText.length, replaceLength)
        }

        override fun textBeforeCursor(maxChars: Int): CharSequence? = inner.textBeforeCursor(maxChars)

        // AP7-E1c: ownText MUSS durchdelegiert werden — der Default des
        // Interfaces wäre leer und der E1c-Resync griff nicht.
        override fun ownText(): String = inner.ownText()

        override fun ownLength(): Int = inner.ownLength()

        // REVIEW_A5_W P0: Reset MUSS durchdelegiert werden — der Interface-Default
        // wäre No-op und die ownSpan-Leiche bliebe in Produktion (Zwei-Sitzungs-W1).
        override fun resetSession() = inner.resetSession()
    }

    /**
     * D10-Rest §2/§3: Finale nach STOP (nicht nach Abort — der Coordinator ruft
     * onStopped nur im stop()-Pfad). Läuft auf dem Main-Dispatcher (N-2).
     *  - Leere Ausgabe (0 Zeichen) → Toast „Kein Text erkannt. Bitte erneut
     *    versuchen.“ — stumme Leerausgabe ist nicht mehr stumm; KEIN Häkchen.
     *  - Ausgabe > 0 → kurzlebiges Commit-Häkchen (600 ms) auf der Mic-Zelle.
     */
    private fun onDictationStopped() {
        syncMicUi() // P0-Befund2(b): Stop => Mic-Zustand rückt zurück (war: ad-hoc false)
        // F45 (Retest F41b, P1): Der F43-AUTO-STOP läuft über DIESES Finale, ohne
        // dass ein Nutzer-Stopp vorher stopFgs() gerufen hat — der Mikro-FGS lief
        // bis zum nächsten IME-Hide weiter (dumpsys +12/+20 s). Läuft er hier
        // NOCH, war es der Auto-Stop → hier beenden (idempotent; ein Nutzer-Stopp
        // hat ihn schon synchron gestoppt und behält seinen eigenen Grund).
        fgsStopOnDictationStopped(fgsRunning)?.let { stopFgs(it) }
        // AP7-W1: Stop-Moment-Sample (deckt auch den F43-Auto-Stop ab) VOR dem
        // generationengebundenen Reset — endHold wirkt nur auf die Session-Gen.
        gateOpenAtStopValue = if (holdGate.activeGen == 0L) 1 else 0
        endHold(holdGate.sessionGen, "stopped")
        sess("final") // AP1: VK-SESS — Mini-/Gesamtfinale erreicht (state=FINALIZING→IDLE)
        holdWarnShown = false // B2: Session-Ende ⇒ Warn-Flag zurück (nächste Session warnt neu)
        val c = coordinator
        val hadOutput = outputCounter.hasOutput
        // F13d/P2-1: Die Bewertung liest NUR den StopOutcome-SNAPSHOT (im Worker
        // direkt nach controller.stop() gebündelt) — NIE den Live-Controller-
        // Zustand, denn ein sofortiger neuer start() nullt failedThisSession/
        // lastErrorCode und würde Sitzung A mit Zustand von Sitzung B beurteilen.
        val outcome = c?.lastStopOutcome
        val finalError = (outcome as? de.voicekeyboard.dictation.StopOutcome.FinalRan)?.finalError
        val failedWith = (outcome as? de.voicekeyboard.dictation.StopOutcome.FinalRan)?.failedWith == true
        val noFinalCode = (outcome as? de.voicekeyboard.dictation.StopOutcome.NoFinal)?.errorCode
        val cleanSuccess = outcome is de.voicekeyboard.dictation.StopOutcome.FinalRan &&
            finalError == null && !failedWith
        if (finalResultExpected) {
            if (!cleanSuccess) {
                // Grund aus dem SNAPSHOT — bestehende Fehlermeldung, sticky (R-2).
                val code = noFinalCode ?: finalError ?: "unknown"
                stickyError = mapVisibleError(code)
                updateStatus()
            } else if (hadOutput) {
                rootView?.keyboardView?.showMicCheck()
                successJustFinished = true // F13a/R-2b: Erfolg darf stickyError lösen
                updateStatus()
            } else {
                toast(getString(R.string.dictation_no_text))
            }
        }
        finalResultExpected = false
        updateStatus()
    }

    /**
     * D10-Rest §1: CANCEL der Haltegeste — sicherer Abbruch ohne Final-Decode.
     * Bereits committeter Text bleibt; ein laufender Start wird sauber
     * abgebrochen, ein nachgezogener Stop wird verhindert.
     */
    private fun abortDictation(reason: String = "editor_change") {
        startingUp = false
        endHold(holdGate.sessionGen, "abort") // AP7-W1: Session-Gen, nie global nullen
        holdWarnShown = false // B2: Abort = Session-Ende ⇒ Warn-Flag zurück
        cancelStartRequested = false
        cancelStartIsAbort = false
        finalResultExpected = false
        syncMicUi() // P0-Befund2(c): Abort => nie grün ohne Session
        coordinator?.abort(reason)
        stopFgs("abort:$reason")
        sess("abort:$reason") // AP1: VK-SESS — Abbruchpfad
        updateStatus()
    }

    // ---- D10-Rest §1: Haltegeste auf dem Fussreihen-Mikrofon ----

    /** T3 (v0.1.35): Coordinator-Zustand BEIM Mic-DOWN (frisch vs. Session/Start
     *  bereits aktiv) — Basis der Tap/Hold-Klassifikation in [onMicHoldEnd]
     *  (micHoldReleaseDecision), VOR dem asynchronen Start gemessen. */
    @Volatile private var micHoldDownState: de.voicekeyboard.dictation.DictationState? = null

    override fun onMicHoldStart() {
        footerTouchGen = holdGate.begin("footer-hold") // AP7-W1: Setz-Quelle
        sess("gate set gen=$footerTouchGen src=footer-hold")
        // T3: Frische beim DOWN merken (VOR dem asynchronen Start) — ein zweiter
        // Tap auf die laufende Session muss TOGGLE_OFF bleiben, ein frischer
        // Tap TOGGLE_ON werden, egal wann das UP kommt.
        micHoldDownState = coordinator?.state
        onMicDown() // DOWN startet (asynchroner Start inkl. startingUp-Guard bleibt)
    }

    override fun onMicHoldEnd(cancelled: Boolean, holdDurationMs: Long) {
        // AP7-W1: Ende nur die EIGENE Generation (mitgeführter Merker).
        endHold(footerTouchGen, if (cancelled) "holdcancel" else "holdup")
        // F28b2: Halte-Ende (mit oder ohne Cancel) — wer: holdup/holdcancel.
        noteMicRel(if (cancelled) "holdcancel" else "holdup")
        // T3 (v0.1.35, Debugger-Design): Tap/Hold-Klassifikation statt pauschalem
        // „RECORDING/STARTING + UP ⇒ Stop" (MicToggle.micHoldReleaseAction war der
        // Befund: vier Sessions starben 10-90 ms nach start_ok über das eigene
        // Loslassen). Frischer Tap ⇒ TOGGLE_ON; Tap auf laufende Session ⇒
        // TOGGLE_OFF; >300 ms ⇒ Hold-Release (pending, wenn der Start noch läuft).
        when (
            micHoldReleaseDecision(
                stateAtDown = micHoldDownState,
                stateAtRelease = coordinator?.state,
                holdDurationMs = holdDurationMs,
                cancelled = cancelled,
                startingUp = startingUp,
            )
        ) {
            MicReleaseDecision.TOGGLE_ON -> Unit // frischer Tap: Session läuft weiter (UP vor ODER nach start_ok)
            MicReleaseDecision.TOGGLE_OFF, MicReleaseDecision.HOLD_STOP -> {
                stopDictationIfRunning()
                updateStatus()
            }
            MicReleaseDecision.HOLD_STOP_PENDING -> {
                // Echter Hold-Release während des Starts: Stop nachreichen — greift
                // NACH Startende über micStartFinishedAction(STOP_AFTER_START);
                // ein frischer Tap (TOGGLE_ON) setzt dieses Flag GAR NICHT.
                cancelStartIsAbort = false
                cancelStartRequested = true
                postEvent(hintEvent(R.string.dictation_start_pending_stop))
            }
            MicReleaseDecision.ABORT -> {
                if (startingUp) {
                    // Abbruch nachreichen: nach dem Start KEIN Finale, sondern Abort.
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
        de.voicekeyboard.audio.Route.WIRED_HEADSET -> "Kabel-Headset"
        de.voicekeyboard.audio.Route.BLUETOOTH_SCO -> "BT-Headset"
        de.voicekeyboard.audio.Route.BUILTIN_MIC -> "intern"
    }

    // ---- Berechtigungen (Reihenfolge: Grant → FGS → Start) ----

    private fun ensurePermissions(): Boolean {
        // F28c: api-Levelabhängig — ab 33 gehört POST_NOTIFICATIONS zu den
        // Diktat-Bedingungen (FGS-Typ microphone ohne sie: System kann die
        // Aufnahme stumm stoppen, Export #2). Fehlt sie: kein stummer Start,
        // sondern sichtbarer Fehler mit Begründung (neuer String).
        val apiLevel = android.os.Build.VERSION.SDK_INT
        val required = DictationPermissions.requiredForApi(apiLevel)
        val granted = required
            .filter { checkSelfPermission(it) == android.content.pm.PackageManager.PERMISSION_GRANTED }
            .toSet()
        // C-3: "Nicht erneut fragen" über einen PERSISTIERTEN Marker — der
        // IME-Context kennt shouldShowRequestPermissionRationale nicht; die
        // dialogtragende Activity (SetupActivity) setzt den Marker nach der
        // zweiten verweigerten Anfrage. Kein hartkodiertes emptySet() mehr:
        // der PermanentlyDenied-Pfad ist erreichbar.
        val prefs = getSharedPreferences(PERMISSION_PREFS, MODE_PRIVATE)
        val permanentlyDenied = required
            .filter { prefs.getBoolean(PERMISSION_DENIED_FOREVER_PREFIX + it, false) }
            .toSet()
        return when (
            val decision = DictationPermissions.nextStep(granted, permanentlyDenied, apiLevel)
        ) {
            DictationPermissions.Decision.Ready -> true
            is DictationPermissions.Decision.Ask -> {
                Log.i(TAG, "permission ask: ${decision.permission}") // nur Berechtigungsname
                // Dialog: IME selbst ist kein dialogtragender Kontext — die
                // SetupActivity über PendingIntent öffnen (AndroidPermissionsRequester
                // kapselt nur die Fakten, die UI-Handhabung gehört der Activity).
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
                // C-3: nicht erneut fragen — direkt zu den App-Systemeinstellungen
                try {
                    val settingsIntent = android.content.Intent(
                        android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        android.net.Uri.fromParts("package", packageName, null),
                    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    startActivity(settingsIntent)
                } catch (e: Exception) {
                    Log.i(TAG, "settings intent failed: ${e.javaClass.simpleName}")
                }
                // F28c: Notification behält ihre Begründung (enthält den
                // Settings-Hinweis), das Mikro den etablierten Settings-Text.
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

    /** F28c: Fehlertext je fehlender Berechtigung — Notification hat die eigene
     *  Begründung (Export #2: stille Stoppung der Aufnahme), das Mikro die etablierte. */
    private fun permissionErrorRes(permission: String): Int =
        if (permission == android.Manifest.permission.POST_NOTIFICATIONS) {
            R.string.dictation_error_permission_notification
        } else {
            R.string.dictation_error_permission_mic
        }

    // ---- FGS (vor dem ersten Aufnahme-Start) ----

    private fun startFgs() {
        if (fgsRunning) return
        // F28c: Handshake zurücksetzen + registrieren, BEVOR der Service startet —
        // der Worker wartet auf [awaitFgsReady], erst dann läuft AudioRecord an.
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

    /** F28c: wartet (serviceScope-Worker, NIE Main-Thread), bis der FGS
     *  startForeground abgeschlossen hat; Timeout ⇒ false (kein Mikro-Start). */
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
        fgsStoppedBy = by // F44: Ursache merken (nur Code, Regel 9)
        startService(
            Intent(this, CaptureForegroundService::class.java).apply {
                action = CaptureForegroundService.ACTION_STOP
            },
        )
        fgsRunning = false
    }

    /** F42 (🐞: fgs_stopped_by): Ursachencode des letzten FGS-Stops für den
     *  Debug-Export — nur kontrollierte Codes, nie Inhalte (Regel 9). */
    fun fgsStoppedBy(): String = fgsStoppedBy

    // ---- Statusanzeige (kein Text-/Audio-Inhalt) ----

    /** S5-3/E5: mappt interne Fehlercodes auf Nutzer-Strings (≤45 Zeichen).
     *  Rohcode (audioInit:…, model:…, startFailed) nur im Log, nie in der UI.
     *  Nur technisch Trennbares wird unterschieden; alles andere → audio_start. */
    private fun mapVisibleError(raw: String): String {
        Log.w(TAG, "visibleError=$raw") // Rohcode nur ins Log
        // F13e: Editor-Sync-Timeout hat einen EIGENEN Text (Ziel-App blockiert),
        // nicht den falschen „Audio-Start“-Fallback. Reine Abbildung s. unten.
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
            // A2/E5 (a): leerer Eingabegeräte-Katalog
            raw.startsWith("noMic") || raw.startsWith("audioInit:noInputDevice") ->
                getString(R.string.dictation_error_no_mic)
            // A2/E5 (g): Allocator-Muster des ORT (best-effort)
            raw.contains("alloc") || raw.contains("bad_alloc") || raw.startsWith("oom") ->
                getString(R.string.dictation_error_out_of_memory)
            // A2/E5 Fallback: einzige Stelle ohne Diagnoseversprechen
            raw.startsWith("unknown") || raw.startsWith("internal") ->
                getString(R.string.dictation_error_unknown)
            // F31: Blöcke laufen, aber alles still (−56-dBFS-Nutzerfall) — eigener,
            // klarer Text statt des falschen „Audio-Start“-Fallbacks.
            raw.startsWith("audio_error:silence") ->
                getString(R.string.dictation_error_no_signal)
            else -> getString(R.string.dictation_error_audio_start)
        }
    }

    /** E5-Priorität (Berechtigung → Modell BROKEN vor ABSENT → Audio): Start-Wurf.
     *  A2: unbekannte Ausnahmen → eigener Fallback-Text (nie Rohcode). */
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

    /** Main-Handler fuer Status-Meldungen aus Worker-Kontext (F9 P0, Guertel+Traeger). */
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    /**
     * F13c/P2-3: Verdrahtungsnaht — JEDER Statusanlass des Service geht über
     * [StatusRouting.route] (reine Tabelle, fixiert in StatusRoutingTest):
     * Starting/Hint → transienter Kanal, Error → Sticky-Kanal. B1 (Starttext
     * wurde sticky) war genau dieser Nahtfehler und kann jetzt nicht mehr
     * unbemerkt zurückkommen — ein Rückfall macht den Routing-Test rot.
     */
    private fun postEvent(event: StatusEvent) {
        when (StatusRouting.route(event)) {
            StatusChannel.TRANSIENT -> {
                // Anzeige bis zum nächsten updateStatus (überholt bei Recording/
                // Finalizing/neuerem Fehler) — setzt KEIN stickyError.
                transientStatus = event.text
                transientIsHint = event is StatusEvent.Hint // F52: kind hint vs. starting
                transientSetAtMs = android.os.SystemClock.elapsedRealtime()
                updateStatus()
            }
            StatusChannel.STICKY -> updateStatus(event.text) // R-2: sticky bis nächste Aktion
        }
    }

    private fun updateStatus(extra: String? = null) {
        // F9 P0: View-Zugriff ist main-gebunden. Jeder Callback des Coordinators oder
        // des Routers darf von einem Worker kommen - hier landet der Hop nach Main.
        if (android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) {
            mainHandler.post { updateStatus(extra) }
            return
        }
        val view = rootView ?: return
        val c = coordinator
        // AP1: VK-SESS — Mini-Finale (Segmentzwischenschnitt) an Zählerwechsel erkennen.
        // A3 (PLAN_V0133 §A3): reine Entscheidung — nur ECHTE Zählererhöhung
        // loggt (previous >= 0 && current > previous); Sessionwechsel öffnet
        // die Baseline ohne Eintrag. Keine Änderung am Segmenter/Zähler.
        if (segSessionLog.onSegmentCount(c?.segmentFinalizations() ?: 0)) {
            sess("seg_final")
        }
        val visible = c?.visibleError?.let { mapVisibleError(it) }
        // F13a: Zeitstempel der Klärheitsregel — ein FEHLER ist nie „älter“ als ein
        // frisch laufender transienter Starttext, wenn er danach beobachtet wurde.
        val now = android.os.SystemClock.elapsedRealtime()
        if ((visible != null || extra != null) && lastErrorSeenAtMs == 0L) {
            lastErrorSeenAtMs = now
        }
        if (stickyError != null && lastErrorSeenAtMs == 0L) lastErrorSeenAtMs = now
        // F13a/B1: Textauswahl über die reinlogische Funktion — der transiente
        // Starttext setzt/verdeckt KEIN stickyError mehr; extra wird nur noch
        // dann sticky, wenn die Funktion es als Fehler kennzeichnet.
        val recText = buildString { // F52: gehoben — diagSuffix-Ableitung braucht die Zeile vor dem select
            append("Diktat… (")
            append(routeLabel(router?.currentRoute ?: de.voicekeyboard.audio.Route.BUILTIN_MIC))
            append(")")
            // S12b: Diagnose-Kurzform nur in Debug-Builds (Regel 9: nur Zahlen —
            // keine Audio-/Textinhalte, keine Modellpfade).
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
                diagSuffix = recText.contains(" · "), // F52: diag vs. recording (Strukturmarker, kein Inhalt)
            ),
        )
        // F52 (PLAN_V0130 A3): status_res — Code des zuletzt gezeigten
        // Statustexts; ERROR trägt den Ressourcen-Namen aus dem sichtbaren
        // Fehlercode (Regel 9: nie der Text selbst).
        statusResValue = if (result.kind == StatusKind.HINT) {
            // AP3: hint:<res-name> statt kahl "hint" (Review-A3-P2) — Code, nie Text.
            "hint:" + (lastHintRes ?: "-")
        } else {
            StatusBarText.statusResCode(
                result.kind,
                c?.visibleError?.let { code -> mapErrorTextRes(code) }
                    ?.let { resId -> resources.getResourceEntryName(resId) },
            )
        }
        successJustFinished = false // Einmal-Flag (R-2b): nur den nächsten Aufruf bewerten
        if (extra != null && result.extraIsSticky) {
            stickyError = extra // R-2: NUR echte Fehler sind sticky — nie der Starttext
            lastErrorSeenAtMs = now
        }
        if (result.stickyConsumed) stickyError = null // R-2b: abgeschlossener Erfolg
        if (result.transientConsumed) {
            transientStatus = null
            transientSetAtMs = 0L
        }
        view.statusText.text = result.text
        // F28b2: Welchen Zustand bekam die Statuszeile zuletzt — reiner Code
        // für den Export (Regel 9); Priorität wie StatusBarText.select.
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
        // P0-Befund2(b/c): der Fußzeilen-Mic-Grünton folgt derselben Quelle —
        // JEDER updateStatus-Aufruf hält die Invariante "grün ⇔ RECORDING".
        view.keyboardView.micRecording = recording
    }

    // ---- Tastaturereignisse (unverändert außer Mic-Entfernung aus der Action-Liste) ----

    override fun onTextInput(text: String) {
        val ic = currentInputConnection ?: return
        ic.commitText(text, 1)
    }

    override fun onAction(action: String) {
        val ic = currentInputConnection ?: return
        when (action) {
            KeyActions.BACKSPACE -> {
                // B2 (PLAN_V0133 §B2): EINE Aktion über die reine Editor-Naht —
                // nichtleere Selektion atomar per commitText("",1) ersetzt,
                // sonst Bestandspfad (Graphemcluster; leerer Puffer ⇒ DEL-Key).
                // Repeat UND Tap nutzen SELBST diesen Zweig (KeyboardView ruft
                // onAction(BACKSPACE) je Repeat-Feuerung erneut).
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
            KeyActions.LANGUAGE -> switchToNextLanguage() // F25: Zweig fehlte — Sprachwechsel war tot
            KeyActions.MIC -> {
                // F8 Q6 (P1) + F9 d (P2): Die Fußreihen-Mic-Taste ist ein Schalter,
                // aber nur in IDLE/RECORDING. In STARTING/FINALIZING wäre ein Tap ein
                // Neustart-Versuch, der an coordinator.start() scheitert und
                // „Start fehlgeschlagen“ zeigt — deshalb dort bewusst nichts.
                // Halten bleibt zusätzlich möglich: DOWN der Statuszeilen-Leiste
                // startet, UP stoppt.
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

    // ---- Sprachwechsel (E9.1/E9.2, M8b-1) ----

    private fun layoutPrefs() = getSharedPreferences(LAYOUT_PREFS, MODE_PRIVATE)

    /** Aktive Sprachen aus vk_layout (geordnet, kanonisch; Default de-de,en-us). */
    private fun activeLanguages(): List<String> =
        LanguageCycle.parseActive(layoutPrefs().getString(KEY_ACTIVE_LANGUAGES, null))

    /** Aktuelle Sprache: current_language, falls aktiv; sonst erste aktive (E9.1 Regel 7). */
    private fun currentLanguage(): String {
        val active = activeLanguages()
        return LanguageCycle.initialLanguage(
            active,
            layoutPrefs().getString(KEY_CURRENT_LANGUAGE, null),
        )
    }

    /**
     * Kurztipp 🌐 = nächste aktive Sprache (E9.1 (a)).
     * - während RECORDING/STARTING/FINALIZING: ignorieren + Statushinweis (E9.2),
     *   Diktat NICHT abbrechen
     * - nur eine aktive Sprache: nichts wechseln, Statushinweis (Regel 4)
     * - sonst: reines setLayout im IME-Fenster (E3/E9.1 Regel 6) — kein
     *   restartInput, kein finishInput, currentInputConnection bleibt unberührt.
     */
    private fun switchToNextLanguage() {
        val c = coordinator
        if (c != null && c.state != de.voicekeyboard.dictation.DictationState.IDLE) {
            // D10 §3 Variante (b) — „Für nächste Aufnahme vormerken": bewusster Tap
            // wird ANGENOMMEN (statt ratlos abgelehnt), laufende PTT-/Edit-Session
            // bleibt unberührt; keine falsche Zusage einer ASR-Sprachumstellung
            // („Diktatsprache Auto" — die Erkennung folgt dem Modell).
            val active = activeLanguages()
            // Weitere Taps zyklizieren von der VORGEMERKTEN statt der aktuellen ID;
            // ungültiges Pending (Ziel aus aktiven Sprachen entfernt) wird gelöscht.
            val pending = pendingLayoutId?.takeIf { it in active }
            val next = LanguageCycle.nextActiveLanguage(active, pending ?: currentLanguage())
            if (next == null) {
                toast(getString(R.string.language_switch_single_active)) // „Nur ein Layout aktiv."
                return
            }
            if (next == currentLanguage()) {
                // Rückkehr zur aktuellen ID hebt die Vormerkung auf (D10 §3).
                pendingLayoutId = null
                toast("Vormerkung aufgehoben. Erkennung Auto.")
                return
            }
            pendingLayoutId = next
            toast("Layout ${LanguageCycle.endonym(next)} vorgemerkt; Diktatsprache Auto")
            return
        }
        val active = activeLanguages()
        val next = LanguageCycle.nextActiveLanguage(active, currentLanguage())
        if (next == null) {
            // E9.1 Regel 4: kein stiller No-Op
            postEvent(hintEvent(R.string.language_switch_single_active))
            return
        }
        applyLanguage(next)
    }

    /** D10 §3: einmaliger kurzer Toast (keine neue Zeile im Diagnoseband). */
    private fun toast(text: String) {
        android.widget.Toast.makeText(applicationContext, text, android.widget.Toast.LENGTH_SHORT).show()
    }

    /**
     * D10 §3: Vormerkung beim NÄCHSTEN bewussten Mic-DOWN anwenden — vor Beginn
     * des neuen Captures; danach ist das Pending weg. Ungültige Ziele (nicht
     * mehr aktive Sprache) werden stilllos gelöscht, nie überraschend angewandt.
     */
    private fun applyPendingLayoutBeforeCapture() {
        val target = pendingLayoutId ?: return
        pendingLayoutId = null
        if (target in activeLanguages() && target != currentLanguage()) {
            applyLanguage(target)
        }
    }

    /** Lädt das Asset der Zielsprache (Fallback de-de, Regel 5) und zeigt das Endonym.
     *  L-02: Sprachwechsel resettet Shift/Caps (kein toter Shift-Zustand ins neue Layout).
     *  E10.4 Regel 2: Sprachwechsel bei offenem Panel schliesst es (Kategorie bleibt,
     *  Seite → 1), fuegt nichts ein; der Editorfokus bleibt. */
    private fun applyLanguage(id: String) {
        val view = rootView ?: return
        if (view.isEmojiPanelVisible) {
            view.emojiPanel.closeAndResetPage() // E10.4: Kategorie bleibt, Seite → 1
        }
        view.hideEmojiPanel()
        view.keyboardView.resetShiftState()
        view.keyboardView.setLayout(repository.loadOrFallback(id), resources.displayMetrics.widthPixels)
        layoutPrefs().edit().putString(KEY_CURRENT_LANGUAGE, id).apply()
        pendingLayoutId = null // D10 §3: bewusste IDLE-Wahl ersetzt jede Vormerkung
        postEvent(hintEvent(R.string.language_switched_to, LanguageCycle.endonym(id)))
    }

    // ---- Emoji-Panel (E9.4, M8b-2) ----

    /**
     * 😊-Taste: Panel ein-/ausblenden. Waehrend einer Aufnahme gesperrt (E9.4:
     * manueller Fremd-Edit wuerde die Diktat-Ownership beenden, DESIGN §6).
     * Panel bleibt im IME-Fenster (Overlay) — kein restartInput, kein Fokusverlust.
     */
    /**
     * 😊-Toggle (E10.1/E10.4, M8b-3b): offen → schliessen, zu → oeffnen.
     * Das Panel fasst activeLayerName/capsLock/Shift NICHT an (keine savedLayer-
     * Maschinerie nötig — die unterste Tastenreihe bleibt sichtbar, der Zustand
     * bleibt von allein erhalten); kein restartInput, kein finish().
     * Beim Schliessen: Kategorie bleibt, Seite → 1 (E10.4 Regel 1).
     */
    /**
     * 😊-Aktion (V2 §3.1): setzt den Emoji-Modus — KEIN sichtbarer Close-Toggle mehr.
     * Idempotent (zweite Öffnung im offenen Modus schreibt nichts). Zustand ≠ IDLE
     * sperrt das Öffnen (Meldung bleibt, R-2); der Rueckbutton bleibt bedienbar.
     * Kein setLayout/resetShiftState beim Oeffnen — Ebene und Shift/Caps bleiben
     * durch Erhalt der KeyboardView-Instanz unverändert (V2 §3.4).
     */
    private fun toggleEmojiPanel() {
        val view = rootView ?: return
        val c = coordinator
        if (c != null && c.state != de.voicekeyboard.dictation.DictationState.IDLE) {
            postEvent(hintEvent(R.string.emoji_blocked_recording))
            return
        }
        view.showEmojiPanel() // idempotent: im offenen Modus kein Schliessen mehr
    }

    private fun performEditorAction(info: EditorInfo?) {
        val ic = currentInputConnection ?: return
        val action = info?.imeOptions?.and(EditorInfo.IME_MASK_ACTION) ?: EditorInfo.IME_ACTION_NONE
        if (action != EditorInfo.IME_ACTION_NONE) {
            ic.performEditorAction(action)
        } else {
            ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
            ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))
        }
    }

    /** UTF-16-Einheiten des letzten vollständigen Graphemclusters am String-Ende. */
    internal fun lastGraphemeLength(before: String): Int = lastGraphemeLengthOf(before)

    /**
     * B2 (PLAN_V0133 §B2): dünne Editor-Naht des Backspace über die AKTUELLE
     * InputConnection — die vier Operationen des Zweigs als Interface, damit
     * die Entscheidung rein JVM-testbar ist (BackspaceTest; Bestandsmuster
     * stopCauseOf/micToggleAction). KEIN Android-Typ im Interface.
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
 * B2 (PLAN_V0133 §B2): dünne Editor-Naht des Backspace — die Operationen des
 * Zweigs als Interface, damit die Entscheidung rein JVM-testbar ist
 * (BackspaceTest; Bestandsmuster stopCauseOf/micToggleAction). KEIN Android-Typ.
 */
interface BackspaceEditor {
    /** getSelectedText(0): aktuelle Selektion oder null (leer = wie keine). */
    fun selection(): CharSequence?

    /** commitText("", 1): atomare Ersetzung der Selektion durch leeren Text. */
    fun commitEmptyReplacement()

    /** getTextBeforeCursor(maxChars, 0). */
    fun textBeforeCursor(maxChars: Int): CharSequence?

    /** deleteSurroundingText(units, 0): EIN Graphemcluster vor dem Cursor. */
    fun deleteGraphemeBeforeCursor(units: Int)

    /** DEL-Taste DOWN+UP (Bestands-Fallback bei leerem Puffer). */
    fun sendDeleteKeyEvent()
}

/**
 * B2: EINE Backspace-Aktion — prüft ZUERST die Selektion: nichtleer ⇒ atomar
 * per [BackspaceEditor.commitEmptyReplacement] ersetzt (NIE deleteSurroundingText
 * oder DEL-KeyEvents); sonst Bestandspfad Graphemcluster ([lastGraphemeLengthOf],
 * leerer Puffer ⇒ DEL-Key-Fallback). Repeat UND Tap nutzen SELBST diesen
 * Zweig; ein Aufruf wirkt GENAU einmal (kein Doppeldelete aus DOWN+UP).
 * Wirft nie. Reine Funktion, kein Android (Regel 9: kein Inhalt in Logs).
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

/** UTF-16-Einheiten des letzten vollständigen Graphemclusters am String-Ende
 *  (B2: Top-Level-Fassung des Service-Bestands — reine Funktion, JVM-testbar). */
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

/**
 * P0-Befund2 (2026-11-01, Nutzerbefund nach 0.1.24): reine Mic-UI-Entscheidungen
 * des VoiceKeyboardService — als Top-Level-Funktionen derselben Datei, damit die
 * Zustandsmaschine ohne IME-Lebenszyklus bewiesen werden kann (Muster:
 * MicToggle/StatusBarText). Kein Android-Typ.
 */

/** Start-Erfolg: was mit der frischen Session passiert, wenn das Loslassen
 *  während des (Kalt-)Starts kam. */
enum class MicStartFinishAction { RUN, STOP_AFTER_START, ABORT }

/**
 * T3 (v0.1.35, Debugger-Design, Beweis /tmp/vk_phone_e2.log Z.74-97): wantedStop
 * kommt jetzt NUR noch vom Hold-Release (> [MIC_TAP_MAX_MS]) während des Starts
 * ⇒ es stoppt die frische Session WIRKLICH nach Startende ([STOP_AFTER_START]).
 * Das frühere blinde KEEP_RUNNING (P0-Befund2(a), Rettung des Quick-Taps) ist
 * durch die Tap/Hold-Klassifikation ersetzt: ein frischer Tap ist TOGGLE_ON
 * (micHoldReleaseDecision) und setzt das wantedStop-Flag GAR NICHT erst.
 */
fun micStartFinishedAction(wantedStop: Boolean, wantedAbort: Boolean): MicStartFinishAction = when {
    wantedAbort -> MicStartFinishAction.ABORT
    wantedStop -> MicStartFinishAction.STOP_AFTER_START
    else -> MicStartFinishAction.RUN
}

/** P0-Befund2(b/c): der sichtbare Mic-Zustand (grün) wird NUR aus dem
 *  Coordinator-Zustand abgeleitet — es gibt keinen Zustand, in dem das Mikro
 *  ohne laufende Session grün bleibt (nach Stop/Abort/Fehler/Frist-Timeout
 *  ist die Session IDLE => false). */
fun micUiRecording(state: de.voicekeyboard.dictation.DictationState?): Boolean =
    state == de.voicekeyboard.dictation.DictationState.RECORDING

/**
 * F45 (Retest F41b, P1): FGS-Entscheidung im onStopped-Finale. Der Nutzer-Stopp
 * ruft stopFgs("stop") SYNCHRON in stopDictationIfRunning (fgsRunning=false,
 * hier also kein Op); der F43-AUTO-STOP erreicht das Finale dagegen OHNE
 * vorherigen Stopp — der Mikro-FGS lief im Retest bis zum nächsten IME-Hide
 * weiter (dumpsys +12/+20 s foreground, Notifikation blieb, fgs_stopped_by
 * zeigte den STALEN letzten Stopp). Läuft der FGS im Finale NOCH, war es der
 * Auto-Stop → Grund "autostop" für stopFgs (idempotent, überschreibt keinen
 * früheren Grund). Reine Funktion (kein Android) — JVM-testbar (F45-Test).
 */
fun fgsStopOnDictationStopped(fgsRunning: Boolean): String? =
    if (fgsRunning) "autostop" else null

/**
 * A3 (PLAN_V0133 §A3): Segment-Sitzungslog-Entscheidung — rein, JVM-testbar
 * (Bestandsmuster micStartGate/stopCauseOf; SegmentSessionLogTest). Zustand
 * = letzter gesehener Segment-Mini-Finale-Zähler; Mic-DOWN ([onMicDown])
 * setzt die Sessionbasis neu.
 *
 * Befund (18:56, PLAN §0.1): ein `seg_final` VOR `start_ok` entstand durch
 * den ALTEN Zählerstand (sessLastSeg=1) der beendeten Session gegen den
 * NEUEN Sessionzähler 0 — nicht durch ein verspätetes Mini-Finale.
 * Entscheidung: Eintrag nur bei ECHTER Zählererhöhung
 * (previous >= 0 && current > previous); der Sessionwechsel (previous=-1)
 * öffnet die Baseline ohne Eintrag. Keine Änderung am Segmenter oder
 * dessen Zähler; nur Zahlen, nie Inhalte (Regel 9).
 */
class SegmentSessionLog {
    private var lastSeg = -1

    /** Mic-DOWN: neue Diktat-Sitzung — Baseline neu öffnen, KEIN Eintrag. */
    fun onMicDown() {
        lastSeg = -1
    }

    /**
     * Neue Beobachtung des Segment-Mini-Finale-Zählers. true ⇒ genau EIN
     * `seg_final` loggen; die Baseline wandert immer auf [current] — auch bei
     * Rücksetzung (Zählerstände sind Session-Werte).
     */
    fun onSegmentCount(current: Int): Boolean {
        val logged = lastSeg >= 0 && current > lastSeg
        lastSeg = current
        return logged
    }
}

/**
 * A4b (PLAN_V0133 §A4b): kompakte VK-ASR-Fensterzeile — REINE Formatierung
 * (kein Android, JVM-testbar: DecodeWindowLogTest; Bestandsmuster
 * stopCauseOf/micStartGate). GENAU EINE Zeile: `event=window` + exakt 17
 * Metriken in fester Reihenfolge; nur Zahlen/geschlossene Codes, nie
 * Text-/Audio-/Pfad-Inhalte (Regel 9). [out] kommt synchron vom bestehenden
 * DictationOutputCounter (Aufrufer: Service-Verdrahtung von
 * [DictationCoordinator.onDecodeWindow]). Ein fremder Write-Code kollabiert
 * defensiv auf '-', damit die Zeile geschlossen und einzeilig bleibt.
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

/** A4b: geschlossene Schreib-Codes der Fensterzeile (Regel 9 — nie Inhalte). */
private val WINDOW_WRITE_CODES = setOf("same", "w1", "w3d", "delta", "-")

/**
 * F50 (PLAN_V0130 A2, V4): Reine Start-Gate-Entscheidung für onMicDown —
 * Serialisierung des asynchronen Starts. IGNORE: ein Start läuft bereits
 * (F11 P1) oder eine Diktat-Session läuft (kein Neustart über DOWN).
 * SUPERSEDE: Die Frist ist abgelaufen (oder ein Fenster-Rest), der alte
 * Start-Job läuft noch — er wird abgebrochen (cancel + abort + FGS frei),
 * dann startet ein FRISCHER Start mit neuem Coordinator. FRESH_START:
 * normaler Fall. Reine Funktion (kein Android) — JVM-testbar (StartGateTest).
 */
enum class MicStartGateAction { IGNORE, SUPERSEDE, FRESH_START }

fun micStartGate(
    startingUp: Boolean,
    sessionRecording: Boolean,
    oldStartJobActive: Boolean,
): MicStartGateAction = when {
    startingUp -> MicStartGateAction.IGNORE // F11 P1: Laden läuft — kein Zweittermin
    sessionRecording -> MicStartGateAction.IGNORE // laufende Session: DOWN startet nichts
    oldStartJobActive -> MicStartGateAction.SUPERSEDE // F50: Frist-Rest ablösen
    else -> MicStartGateAction.FRESH_START
}

/**
 * F50a (PLAN_V0130 A2): UP während des asynchronen (Kalt-)Starts — die reine
 * Entscheidung, ob das Loslassen als REGULÄRER Stopp wirkt oder als
 * NACHGEREICHTER Stop mit transientem Hint („Loslassen vorgemerkt — Aufnahme
 * endet gleich"). Beseitigt das stille Flag (Verwirrungspunkt C2-1), ohne das
 * KEEP_RUNNING-Design (P0-Befund2(a)) zu ändern. JVM-testbar (StartGateTest).
 */
enum class MicUpDuringStartAction { PENDING_STOP_HINT, REGULAR_STOP }

fun micUpDuringStart(startingUp: Boolean): MicUpDuringStartAction =
    if (startingUp) MicUpDuringStartAction.PENDING_STOP_HINT
    else MicUpDuringStartAction.REGULAR_STOP

/**
 * B2 (PLAN §6.2 Option A): Obergrenze je Session — 900 s (15 min) für Halte-
 * UND Toggle-Sessions (Toggle praktisch irrelevant: F43 stoppt an der ersten
 * 2,5-s-Pause). Reine Entscheidung, JVM-testbar (StartGateTest) — die Quelle
 * selbst (MAX_DURATION_MS 300 s) bleibt unangetastet, der Service steuert.
 */
@Suppress("UNUSED_PARAMETER")
fun sessionMaxDurationMs(holdGesture: Boolean): Long = 900_000L

/**
 * B2 (PLAN §6.2 Option A + §6.3-B2): Warnung fällig? — wahr GENAU EINMAL pro
 * Session, ab 13 min Session-Audio (780 000 ms = Obergrenze 900 s minus
 * 2 min Vorlauf); das warned-Flag setzt nur der Session-Ende-Pfad zurück
 * (onDictationStopped/abortDictation/onAborted). Hysterese-frei, rein.
 */
fun holdWarnDue(sessionMs: Long, warned: Boolean): Boolean =
    !warned && sessionMs >= 780_000L

/** AP7-E1 (Fable §0.2.5): Entscheidung beim onStartInput — reine Funktion, JVM-testbar. */
internal enum class EditorRestartAction { REBASELINE, ABORT, NOTHING }

/**
 * W1 (vc34, Debugger-Empfehlung): Preload-Entscheidung beim onStartInputView —
 * reine Funktion, JVM-testbar (Muster [editorRestartAction]): GENAU EINMAL
 * pro Service-Lebenszeit vorwärmen. Der zweite Show bleibt No-Op (die Engine
 * ist resident oder der Diktat-Start lädt lazy nach); ein fehlgeschlagener
 * Versuch wird NICHT wiederholt.
 */
fun preloadDue(preloadTried: Boolean): Boolean = !preloadTried

/**
 * AP7-E1d (vc33-Abnahme P0-Blocker, restart:20-Zelle t=12 s): Re-Baseline
 * duerf NICHT sofort abbrechen, wenn der einzige Verlustgrund eine
 * nicht lesbare Editor-Kontext war (actLen=-1: getTextBeforeCursor erwischte
 * die im restartInput-Re-Bind sterbende alte InputConnection) und die Session
 * noch aktiv ist — dann faellt der 150-ms-Retry die Entscheidung.
 * Echte Verlustbeweise (actLen>=0, Suffix-Test erfassbar gescheitert) brechen
 * weiterhin sofort ab — kein Aufschub bei nachgewiesener Gefahr.
 */
internal fun rebaseRetryDue(kept: Boolean, actLen: Int, sessionActive: Boolean): Boolean =
    !kept && actLen == -1 && sessionActive

/**
 * AP7-E1 (Fable §0.2.5 P1, vc33-T1-Beweis): Viele Ziel-Apps rufen
 * `InputMethodManager.restartInput()` auf JEDE Textänderung — also auf
 * unseren ERSTEN Partial-Schrieb. Vorher tötete onStartInput das Diktat
 * ungeachtet restarting=true über onEditorChanged → abort("editor_change")
 * (Nutzer-Todesursache: ~2 s, erstes grammatikalisch geschlossenes Fragment).
 *
 * Entscheidungsregeln (deterministisch):
 *  - KEINE aktive Session ⇒ [NOTHING] (kosmetischer Alter Pfad, Idle-No-Op).
 *  - restarting=true UND gleiches Paket UND Feld-Einigung: ⇒ [REBASELINE]
 *    — derselbe Editor wurde neu gebunden; Besitz-Re-Anker statt Abbruch
 *    (die Tracker-Rebaseline prüft, ob unser Text noch da ist).
 *    AP7-E1-P2 (Review): fieldId-Sentinels — ist auf EINER Seite fieldId ≤ 0
 *    („unbekannt"; Android liefert je Editor 0/-1), blockiert das Feld DIESE
 *    Seite nicht: dann entscheidet NUR der Paket-Match (sonst wäre -1 vs 0
 *    ein FALSCHER Abort in echten Apps). Sind BEIDE > 0, muss das Feld
 *    identisch sein.
 *  - alles Andere (restarting=false — echtes neues Binden; anderes
 *    packageName/fieldId — echter Editorwechsel) ⇒ [ABORT] wie bisher.
 */
internal fun editorRestartAction(
    restarting: Boolean,
    sessionActive: Boolean,
    baselinePkg: String?,
    baselineFieldId: Int,
    infoPkg: String?,
    infoFieldId: Int,
): EditorRestartAction {
    // AP7-E1-P2: Feld gilt als gebunden, nur wenn BEIDE Seiten > 0 — sonst
    // „unbekannt" und NICHT abbruchwirksam (Sentinel blockiert nicht).
    val fieldAgrees =
        if (baselineFieldId > 0 && infoFieldId > 0) baselineFieldId == infoFieldId else true
    return when {
        !sessionActive -> EditorRestartAction.NOTHING
        restarting && infoPkg == baselinePkg && fieldAgrees -> EditorRestartAction.REBASELINE
        else -> EditorRestartAction.ABORT
    }
}

/**
 * AP7-W1 (Fable §0.1.5/0.1.6): generationengebundene Halte-Gate-Logik — REINE
 * Klasse, JVM-testbar ([de.voicekeyboard.ime.HoldGateGenerationTest]). Der
 * Service hält nur die Instanz; alle Aufrufer laufen auf dem Main-Thread.
 *
 * Fix W1b: das Gate war ein globales Boolean, das JEDES asynchrone Session-Ende
 * (onAborted einer Vorgänger-Session, spätes onDictationStopped) nullte — ohne
 * zu wissen, ob es die Session der AKTUELLEN Geste war. Folge: F43-Auto-Stop
 * feuerte bei gehaltenem Finger („bis zum ersten Satzzeichen → Bereit").
 * Jetzt erhält jede Geste eine monotone Generation ([begin]); [end] wirkt NUR
 * auf die eigene Generation — ein Fremd-Reset wird ABGEWIESEN und gezählt
 * ([foreignResets] ⇒ 🐞 gate_foreign_resets = positiver W1b-Beweis).
 *
 * Fremd-Reset-Zählung: nur wenn eine FREMDE Geste aktiv wäre (activeGen != 0
 * und != gen) — ein Nachziehen über einem bereits offenen Gate (normale
 * Halte-Session: UP räumt zuerst, das asynchrone Session-Ende läuft danach
 * leer) ist harmlos und erhöht den Zähler NICHT (kein Rauschen je Session).
 */
internal class HoldGate {
    private val seq = java.util.concurrent.atomic.AtomicLong(0)

    /** Aktive Gesten-Generation; 0 = keine Geste (Gate offen). */
    @Volatile var activeGen: Long = 0L
        private set

    /** Ursprung der aktiven Geste: "bar-hold" | "footer-hold" | "-" (keine). */
    @Volatile var src: String = "-"
        private set

    /** Geste, unter der die AKTIVE Session startete (0 = ohne Geste, Toggle). */
    @Volatile var sessionGen: Long = 0L

    /** Abgewiesene Fremd-Resets (hätten eine NEUERE, fremde Geste gelöscht). */
    @Volatile var foreignResets: Int = 0
        private set

    /** EINZIGE Setz-Quelle: neue Geste mit frischer Generation; Rückgabe = Gen fürs UP. */
    fun begin(src: String): Long {
        val g = seq.incrementAndGet()
        activeGen = g
        this.src = src
        return g
    }

    /** start_ok: aktive Session an die AKTUELLE Geste binden (auch 0 = Toggle). */
    fun bindSession() {
        sessionGen = activeGen
    }

    /**
     * Generationsgebundener Reset — nullt NUR bei activeGen == gen (auch
     * 0 == 0: harmloses Nachziehen über offenem Gate, KEIN Fremd-Zähler).
     * activeGen == 0 mit gen != 0: ebenso harmlos (UP kam zuerst) — ok=0.
     * Fremd-Reset (activeGen != 0 und != gen): ABGEWIESEN + gezählt (W1b).
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
