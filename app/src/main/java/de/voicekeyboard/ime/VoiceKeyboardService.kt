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
    private var router: AudioRouter? = null
    private val ownership = OwnershipTracker()
    private var fgsRunning = false

    /** R-2: blockierende Meldung bleibt bis zur nächsten bewussten Aktion stehen. */
    @Volatile private var stickyError: String? = null

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
                    updateStatus(getString(R.string.emoji_blocked_recording))
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
                    onMicDown()
                    true
                }
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                    onMicUp()
                    true
                }
                else -> false
            }
        }

        // T17/A14: Diagnose-Taste 🐞 RECHTS in der Statuszeile (48 dp Trefferfläche).
        // Statusleiste statt Q-Reihe: die Tastaturreihe ist an 15 Assets + Parser-Allowlist
        // verdrahtet — hier ist reine UI, keine neue Tastenaktion, kein Asset-Rückbau.
        addDebugInfoButton(view)
        rootView = view
        return view
    }

    override fun onStartInput(info: EditorInfo, restarting: Boolean) {
        super.onStartInput(info, restarting)
        // V2 §3.7: Editorwechsel setzt den Modus zurueck auf Tastatur — kein
        // Panel-Wiedererscheinen mit alten Touchereignissen im naechsten Feld.
        rootView?.let { v ->
            if (v.isEmojiPanelVisible) {
                v.emojiPanel.closeAndResetPage()
                v.hideEmojiPanel()
            }
        }
        // Editorwechsel: Besitz verwerfen — kein Schreiben in den alten Kontext
        coordinator?.onEditorChanged()
    }

    override fun onStartInputView(info: EditorInfo, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        updateStatus()
    }

    override fun onFinishInputView(finishingInput: Boolean) {
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
        stopDictationIfRunning()
        super.onWindowHidden()
    }

    override fun onDestroy() {
        // N-3: KEIN stopDictationIfRunning() (würde einen Final-Worker starten,
        // der sofort gekillt wird) — direkt abort+close, Reihenfolge garantiert.
        coordinator?.abort()
        stopFgs()
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
    }

    // ---- Push-to-Talk ----

    private fun onMicDown() {
        if (startingUp) return // F11 P1: Laden laeuft bereits — kein Zweittermin
        if (coordinator?.state == de.voicekeyboard.dictation.DictationState.RECORDING) return
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
        outputCounter.reset() // D10-Rest §3: Urteil je Aufnahme (F12: nie nachwirkend)
        if (!ensurePermissions()) return
        // C-2: Modell muss bereit sein, BEVOR FGS/Coordinator starten — kein
        // stilles Mikrofon über einer nicht funktionierenden Pipeline.
        // F11 P0: isReadyQuick statt isReady — die Vollprüfung über ~670 MB auf dem
        // Main-Thread erzeugte eine ANR der IME (Mic-Tap blieb 5 s hängen).
        val modelDirFile = java.io.File(filesDir, MODEL_DIR_NAME)
        val store = de.voicekeyboard.model.ModelStore(modelDirFile)
        if (!store.isReadyQuick()) {
            updateStatus(getString(R.string.dictation_error_model_absent))
            Log.i(TAG, "model not ready; status=${store.statusQuick()}")
            return
        }
        startFgs()
        // F11 P1: engine.load() laedt den ~652-MB-Encoder nativ und lief bisher
        // synchron im Touch-Listener (dieselbe ANR-Klasse wie der ModelStore-Hash).
        // Der Start arbeitet im serviceScope, die UI-Arbeit danach auf dem Main-Thread.
        updateStatus(getString(R.string.dictation_starting))
        startingUp = true
        serviceScope.launch {
            var started = false
            var outOfMemory = false
            var thrown: Exception? = null
            try { // C-5: Audio-/Init-Wuerfe nie an den TouchListener durchreichen
                if (coordinator == null) {
                    coordinator = buildCoordinator()
                }
                started = coordinator?.start() ?: false
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
                if (started) {
                    if (wantedStop) {
                        // D10-Rest §1: nachgereichtes Loslassen — CANCEL = Abbruch
                        // ohne Finale, UP = Stop mit Finale.
                        if (wantedAbort) abortDictation() else stopDictationIfRunning()
                    } else {
                        rootView?.keyboardView?.micRecording = true // D10-Rest §2: Aufnahmezustand
                    }
                    updateStatus()
                    return@withContext
                }
                stopFgs() // FGS-Leak verhindern
                rootView?.keyboardView?.micRecording = false
                updateStatus(
                    when {
                        outOfMemory -> getString(R.string.dictation_error_out_of_memory)
                        thrown != null -> startErrorText(store, thrown!!)
                        // P1 (Review F6): echter Grund statt pauschal „Audio-Start“
                        else -> mapVisibleError(coordinator?.visibleError ?: "unknown")
                    },
                )
            }
        }
    }

    private fun onMicUp() {
        if (startingUp) {
            // F11 P1: noch laeuft der Start — Stop wird nachgereicht, nicht verloren.
            cancelStartIsAbort = false // nachgereichtes Loslassen ist ein STOP, kein Abort
            cancelStartRequested = true
            return
        }
        stopDictationIfRunning()
        updateStatus()
    }

    // ---- T17/A14: Debug-Info-Export (docs/DEBUG_INFO_FIELDS.md) ----

    /** 🐞-Taste rechts in der Statuszeile: 48 dp, contentDescription, ein Tap = Export. */
    private fun addDebugInfoButton(view: ImeRootView) {
        val density = resources.displayMetrics.density
        val button = android.widget.Button(this).apply {
            text = "🐞"
            contentDescription = "Diagnose kopieren/teilen"
            isAllCaps = false
            minWidth = 0
            minimumWidth = 0
            setPadding(0, 0, 0, 0)
            layoutParams = android.widget.LinearLayout.LayoutParams(
                (48 * density).toInt(),
                (48 * density).toInt(),
            )
            setOnClickListener { onDebugInfoRequested() }
        }
        // Statuszeile = micButton.parent (ImeRootView legt statusText+micButton dort ab);
        // anhängen = rechts neben dem Mikrofon-Button.
        (view.micButton.parent as? android.widget.LinearLayout)?.addView(button)
    }

    /** Snapshot füllen (Regel 9: nur Zahlen/Codes) und exportieren (Clipboard + Teilen). */
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
        )
        // Regel 9: der Exporttext wird NIE geloggt — nur Format/Status.
        val text = de.voicekeyboard.debug.DebugInfoFormatter.format(snapshot)
        collector.export(text)
        updateStatus("Diagnose kopiert — Teilen-Dialog geöffnet") // T17: kein res-Rückbau (4-Dateien-Grenze)
    }

    private fun stopDictationIfRunning() {
        val c = coordinator ?: return
        if (c.state == de.voicekeyboard.dictation.DictationState.RECORDING ||
            c.state == de.voicekeyboard.dictation.DictationState.STARTING
        ) {
            finalResultExpected = true // D10-Rest: Finale erwartet → Toast/Häkchen bewerten
            c.stop() // Final-Decode bis fertig, drain (Coordinator-M4c-1)
        } else {
            c.abort()
        }
        rootView?.keyboardView?.micRecording = false // D10-Rest §2: Zustandsfläche frei
        stopFgs()
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
            audioSourceFactory = {
                AudioRecordSource(
                    errorListener = coordinator?.errorListener,
                    levelListener = AudioLevelListener { rms, _ ->
                        // Zeigt in der Diagnosezeile, ob ueberhaupt Ton ankommt
                        // (Regel 9: nur ein Maximalwert, nie Audiodaten).
                        coordinator?.noteLevel(rms)
                    },
                )
            },
            engineFactory = { ParakeetAsrEngine() },
            sink = sink,
            tracker = ownership,
            routeObserver = { on -> if (on) routerRef?.startObserving() else routerRef?.stopObserving() },
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

        override fun ownLength(): Int = inner.ownLength()
    }

    /**
     * D10-Rest §2/§3: Finale nach STOP (nicht nach Abort — der Coordinator ruft
     * onStopped nur im stop()-Pfad). Läuft auf dem Main-Dispatcher (N-2).
     *  - Leere Ausgabe (0 Zeichen) → Toast „Kein Text erkannt. Bitte erneut
     *    versuchen.“ — stumme Leerausgabe ist nicht mehr stumm; KEIN Häkchen.
     *  - Ausgabe > 0 → kurzlebiges Commit-Häkchen (600 ms) auf der Mic-Zelle.
     */
    private fun onDictationStopped() {
        rootView?.keyboardView?.micRecording = false
        val hadOutput = outputCounter.hasOutput
        if (finalResultExpected) {
            if (hadOutput) {
                rootView?.keyboardView?.showMicCheck()
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
    private fun abortDictation() {
        startingUp = false
        cancelStartRequested = false
        cancelStartIsAbort = false
        finalResultExpected = false
        rootView?.keyboardView?.micRecording = false
        coordinator?.abort()
        stopFgs()
        updateStatus()
    }

    // ---- D10-Rest §1: Haltegeste auf dem Fussreihen-Mikrofon ----

    override fun onMicHoldStart() {
        onMicDown() // DOWN startet (asynchroner Start inkl. startingUp-Guard bleibt)
    }

    override fun onMicHoldEnd(cancelled: Boolean) {
        when (micHoldReleaseAction(coordinator?.state, cancelled)) {
            MicHoldAction.STOP -> {
                if (startingUp) {
                    // F11 P1: Start laeuft noch — Stop nachreichen (wie bisher).
                    cancelStartIsAbort = false
                    cancelStartRequested = true
                    return
                }
                stopDictationIfRunning()
                updateStatus()
            }
            MicHoldAction.ABORT -> {
                if (startingUp) {
                    // Abbruch nachreichen: nach dem Start KEIN Finale, sondern Abort.
                    cancelStartIsAbort = true
                    cancelStartRequested = true
                    return
                }
                abortDictation()
            }
            MicHoldAction.START, MicHoldAction.IGNORE -> Unit
        }
    }

    private fun routeLabel(route: de.voicekeyboard.audio.Route): String = when (route) {
        de.voicekeyboard.audio.Route.WIRED_HEADSET -> "Kabel-Headset"
        de.voicekeyboard.audio.Route.BLUETOOTH_SCO -> "BT-Headset"
        de.voicekeyboard.audio.Route.BUILTIN_MIC -> "intern"
    }

    // ---- Berechtigungen (Reihenfolge: Grant → FGS → Start) ----

    private fun ensurePermissions(): Boolean {
        val granted = DictationPermissions.REQUIRED_IN_ORDER
            .filter { checkSelfPermission(it) == android.content.pm.PackageManager.PERMISSION_GRANTED }
            .toSet()
        // C-3: "Nicht erneut fragen" über einen PERSISTIERTEN Marker — der
        // IME-Context kennt shouldShowRequestPermissionRationale nicht; die
        // dialogtragende Activity (SetupActivity) setzt den Marker nach der
        // zweiten verweigerten Anfrage. Kein hartkodiertes emptySet() mehr:
        // der PermanentlyDenied-Pfad ist erreichbar.
        val prefs = getSharedPreferences(PERMISSION_PREFS, MODE_PRIVATE)
        val permanentlyDenied = DictationPermissions.REQUIRED_IN_ORDER
            .filter { prefs.getBoolean(PERMISSION_DENIED_FOREVER_PREFIX + it, false) }
            .toSet()
        return when (
            val decision = DictationPermissions.nextStep(granted, permanentlyDenied)
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
                updateStatus(getString(R.string.dictation_error_permission_mic))
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
                updateStatus(getString(R.string.dictation_error_permission_settings))
                false
            }
        }
    }

    // ---- FGS (vor dem ersten Aufnahme-Start) ----

    private fun startFgs() {
        if (fgsRunning) return
        startForegroundService(
            Intent(this, CaptureForegroundService::class.java).apply {
                action = CaptureForegroundService.ACTION_START
            },
        )
        fgsRunning = true
    }

    private fun stopFgs() {
        if (!fgsRunning) return
        startService(
            Intent(this, CaptureForegroundService::class.java).apply {
                action = CaptureForegroundService.ACTION_STOP
            },
        )
        fgsRunning = false
    }

    // ---- Statusanzeige (kein Text-/Audio-Inhalt) ----

    /** S5-3/E5: mappt interne Fehlercodes auf Nutzer-Strings (≤45 Zeichen).
     *  Rohcode (audioInit:…, model:…, startFailed) nur im Log, nie in der UI.
     *  Nur technisch Trennbares wird unterschieden; alles andere → audio_start. */
    private fun mapVisibleError(raw: String): String {
        Log.w(TAG, "visibleError=$raw") // Rohcode nur ins Log
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

    private fun updateStatus(extra: String? = null) {
        // F9 P0: View-Zugriff ist main-gebunden. Jeder Callback des Coordinators oder
        // des Routers darf von einem Worker kommen - hier landet der Hop nach Main.
        if (android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) {
            mainHandler.post { updateStatus(extra) }
            return
        }
        if (extra != null) stickyError = extra // R-2: sticky bis nächste Aktion
        val view = rootView ?: return
        val c = coordinator
        val text = buildString {
            when {
                // R-2: blockierende Meldung (extra) bleibt STICKY — reines Loslassen
                // (onMicUp) setzt sie nicht zurück; erst ein neuer Mic-Down oder ein
                // Erfolg überschreibt. onMicUp ruft updateStatus() nur OHNE extra auf
                // und sieht stickyError zuerst.
                extra != null -> append(extra)
                stickyError != null -> append(stickyError)
                // N-4: visibleError (Audio- UND ASR-Fehler) statt lastError
                c?.visibleError != null -> append(mapVisibleError(c.visibleError ?: "unknown"))
                c?.state == de.voicekeyboard.dictation.DictationState.RECORDING -> {
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
                c?.state == de.voicekeyboard.dictation.DictationState.FINALIZING -> append("Verarbeite…")
                else -> {
                    // R-2b: Erfolg löscht einen gesetzten stickyError (nur wenn
                    // sichtbar kein Fehler mehr aktiv ist)
                    stickyError = null
                    append("Bereit")
                }
            }
        }
        view.statusText.text = text
        view.micButton.isEnabled = c?.state != de.voicekeyboard.dictation.DictationState.FINALIZING
        val recording = c?.state == de.voicekeyboard.dictation.DictationState.RECORDING
        view.micButton.isActivated = recording
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
                val before = ic.getTextBeforeCursor(32, 0)?.toString()
                if (before.isNullOrEmpty()) {
                    ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL))
                    ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DEL))
                } else {
                    val units = lastGraphemeLength(before)
                    ic.deleteSurroundingText(units, 0)
                }
            }
            KeyActions.SPACE -> ic.commitText(" ", 1)
            KeyActions.ENTER -> performEditorAction(currentInputEditorInfo)
            KeyActions.CURSOR_LEFT -> ic.sendKeyEvent(
                KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_LEFT),
            ).also { ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_LEFT)) }
            KeyActions.CURSOR_RIGHT -> ic.sendKeyEvent(
                KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT),
            ).also { ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_RIGHT)) }
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
            updateStatus(getString(R.string.language_switch_single_active))
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
        updateStatus(getString(R.string.language_switched_to, LanguageCycle.endonym(id)))
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
            updateStatus(getString(R.string.emoji_blocked_recording))
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
    internal fun lastGraphemeLength(before: String): Int {
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
}
