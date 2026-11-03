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
 * Framework-armer Diktat-Koordinator (M4c-1): verbindet AudioSource (Capture-
 * Thread, blockierendes read), DictationController (Zustandsmaschine + ASR +
 * Commit) und Routen-/Fehleranzeige zu einem schaltbaren Betrieb. Die IME-
 * Verdrahtung (VoiceKeyboardService) ist ein eigener Folgeauftrag (M4c-2).
 *
 * Threading (DESIGN §2.3):
 *  - Capture-Thread: blockiert nur in [AudioSource.read] und stellt Blöcke in
 *    einen unbeschränkten Kanal — er blockiert nie auf den Controller.
 *  - Consumer-Coroutine im injizierten [scope]: aggregiert Blöcke und gibt sie
 *    IN REIHENFOLGE an [DictationController.feedAudio] weiter.
 *  - Re-Transkriptions-Takt ~1 s, aber NUR wenn seit dem letzten Lauf genug
 *    neues Audio da ist (kein Decode derselben Bytes); Partials unterliegen
 *    zusätzlich dem 2-s-Guard im Controller, das Finale bei [stop] nicht
 *    (ASR_CONTRACT §4.4).
 *
 * Android-Typen (AudioRouter, Notification) dringen nicht ein — Router-Start/
 * -Stopp und Routenanzeige sind Lambdas, die die IME in M4c-2 verdrahtet.
 * Logs: nur Dauern/Fehlercodes, Tag "VK-DICT"; nie Audio oder Text.
 */
class DictationCoordinator(
    private val scope: CoroutineScope,
    private val audioSourceFactory: () -> AudioSource,
    private val engineFactory: () -> AsrEngine,
    private val sink: EditorSink,
    private val tracker: OwnershipTracker = OwnershipTracker(),
    /** S12b Hebel 2: Takt aus der ASR-Konfig (chunkMs), nicht mehr hartkodiert.
     *  F21: Default chunkMs=2000 ⇒ 32_000 Samples @ 16 kHz — halbierter Takt wegen
     *  quadratischem Neudekodieren des ganzen Puffers (Nutzerfreigabe 2026-10-30). */
    private val tickSamples: Int = (AsrThreadConfig.DEFAULT.chunkMs * SAMPLE_RATE_HZ / 1000),
    /** Android-seitige Beobachtung der Audio-Route; null = keine Routen-UI. */
    private val routeObserver: ((on: Boolean) -> Unit)? = null,
    /** Modellverzeichnis; nicht null ⇒ [AsrEngine.load] VOR dem ersten Decode (C-4). */
    private val modelDir: java.io.File? = null,
    /** Completion-Callback nach dem nicht-blockierenden Finale (C-1). */
    private val onStopped: (() -> Unit)? = null,
    /** N-2: Dispatcher, auf dem [onStopped] läuft (View-Mutation ⇒ Main). */
    private val mainDispatcher: kotlinx.coroutines.CoroutineDispatcher? = null,
    /**
     * F49 (PLAN_V0130 §1 A1): Halte-Geste signalisiert „noch nicht fertig" — der
     * F43-Auto-Stop feuert nur, wenn dieses Gate OFFEN ist (Toggle-Modus; E1:
     * TRAILING_SILENCE_MS bleibt 2,5 s). true = unterdrückt. Stille≠Stall-
     * Vertrag (F28b1) und ERR_SILENT-/ERR_STALL-Watchdogs bleiben unberührt —
     * das Gate klammert NUR den F43-Ast. Default false = Verhalten wie bisher
     * (alle bestehenden Tests unverändert).
     */
    private val autoStopSuppressed: () -> Boolean = { false },

    /**
     * B1 (PLAN_V0130 §6.3 Empfehlung (c), I3-Kurve α=38,5 ms/s): harter
     * Segment-Deckel in ms — 0 = Segmenter AUS (Default: alle Bestandstests
     * unverändert). 35 000 ms ⇒ decode(Segment) ≈ 1,35 s bei 38,5 ms/s
     * (I3; superlinear ab 60 s — daher < 60 s gewählt).
     */
    private val segmentFinalizeMs: Long = 0,

    /** F28b1: Monotone Uhr (ms) für den Stall-Watchdog — injizierbar für
     *  deterministische Unit-Tests; Produktion: nanoTime (Kein Wanduhr-Sprung,
     *  kein Deep-Sleep). */
    private val nowMs: () -> Long = { System.nanoTime() / 1_000_000L },
) {

    companion object {
        const val TAG = "VK-DICT"
        const val TICK_SAMPLES_1S = 16_000
        private const val SAMPLE_RATE_HZ = 16_000

        const val READ_TIMEOUT_MS = 50
        const val ERR_AUDIO = "audioSource"
        const val ERR_OOM = "oom"
        /** F28b1: Fehlercode des Stall-Watchdogs (Quelle liefert dauerhaft 0 Samples). */
        const val ERR_STALL = "stall"
        /** F28b1: Abbruch, wenn länger als so viel (ms) kein Block (n>0) ankommt. */
        const val STALL_LIMIT_MS = 3000L
        /**
         * F31/F31b: Fehlercode, wenn Blöcke laufen, die Session aber NOCH NIE
         * laut war (Nutzerfall: Mikro liefert von Anfang an nur −56 dBFS
         * Rauschen → Diktat lief, Text kam nie, kein Hinweis). F31b (REVIEW-
         * P1): NUR in dieser Form — nach dem ersten lauten Block ist die
         * Stille-Erkennung deaktiviert (natürliche Sprechpausen > 3 s dürfen
         * eine gute Session nicht abbrechen; fehlende Blöcke deckt weiterhin
         * [ERR_STALL] ab).
         */
        const val ERR_SILENT = "silence"
        /**
         * F31/F31b: Abbruch, wenn die Session nach Start + dieser Dauer noch
         * KEINEN einzigen lauten Block gesehen hat (aber Blöcke laufen).
         */
        const val SILENT_START_LIMIT_MS = 3000L
        /**
         * F43 (F41-Repro /tmp/vk_repro_f41.txt, Symptom 1 — Nutzer erwartet das
         * ausdrücklich): Stille NACH dem ersten lauten Block ([everLoud]) beendet
         * die Aufnahme AUTOMATISCH über den REGULÄREN stop()-Pfad (finaler Decode
         * über den Gesamtpuffer, kein ERR_* — gleiche Stelle wie der Nutzer-Stopp).
         * Vor Sprechbeginn bleibt das [SILENT_START_LIMIT_MS]-Startfenster
         * (ERR_SILENT) unverändert; kürzere Pausen unterbrechen nie.
         */
        const val TRAILING_SILENCE_MS = 2500L

        /** B1: Stille-Gate der Segmentregel (a) — analog F43 c-Gate: nur
         *  STREAMENDE Stille ist eine Satzpause, eine tote Quelle bleibt beim
         *  ERR_STALL-Vertrag (Stille ≠ Stall, F28b1). */
        const val SEGMENT_STALL_GATE_MS = 2500L

        /** B1: Mindest-Satzpause (ms) für die weiche Segmentgrenze (a) — kürzer
         *  als F43-Auto-Stop (1200 < 2500): ein Satzende darf das Fenster
         *  schließen, bevor der Auto-Stop die ganze Session beendet. */
        const val SEGMENT_PAUSE_MS = 1200L
        /**
         * F31: Block-RMS-Schwelle „laut vs. still" in dBFS (linearer RMS-Wert =
         * 10^(dBFS/20)). −40 dBFS ≈ 0,01: das gemessene Mikro-Rauschen des
         * Nutzers (−56 dBFS) liegt 16 dB DARUNTER (Faktor ≈ 6) → sicher „still";
         * normale Sprache (Block-RMS typ. −30…−20 dBFS) liegt 10–20 dB DARBEE
         * (Faktor 3–10) → sicher „laut". Ein einzelner stummer Block zählt
         * nicht mit — nur die Dauer über [STALL_LIMIT_MS] ohne lauten Block.
         */
        const val SILENCE_RMS_DBFS: Float = -40f
        /** Linearer RMS-Schwellwert = 10^(−40/20) = exakt 0,01 (konstant angegeben,
         *  keine Laufzeit-Rechnung im Companion). */
        const val SILENCE_RMS: Float = 0.01f
    }

    // ---- S12b Diagnose (Regel 9: nur Zahlen, nie Audio/Text/Pfade) ----

    /** Zeit vom Start bis zur ersten (partiellen) Dekodierung, ms; 0 = noch keine. */
    @Volatile var firstHypothesisLatencyMs: Long = 0
        private set

    /** Kumulierte Verarbeitungszeit der Dekodierungen, ms. */
    @Volatile var processingMs: Long = 0
        private set

    /** Verarbeitete Audiodauer (aus Samples), ms. */
    @Volatile var audioMs: Long = 0
        private set

    /**
     * Höchst gesehener Audio-Pegel dieser Sitzung. Regel 9: es lebt nur ein
     * Maximalwert — keine Samples, keine Kurve. Dienet einzig der Frage
     * „kommt überhaupt Ton an?" (Diagnosezeile `Peak=`).
     */
    val levelPeak = de.voicekeyboard.debug.LevelPeak()

    /** Vom Audio-Pfad gemeldet (linear 0..1 pro Block). */
    /**
     * Pegel für die Diagnose. Nach `release()` (Zeile 357ff) verworfen: der
     * Audio-Thread kann nach dem Schließen noch Blöcke melden, und die duerfen
     * den Maximalpegel der naechsten Sitzung nicht verfälschen.
     */
    fun noteLevel(rms: Float) {
        if (!closed.get()) levelPeak.note(rms)
    }

    private var startedAtMs = 0L

    /** Diagnose-Kurzform (Debug-Builds): erst=…s RTF=… T=… Peak=… (dB) — nur Zahlen. */
    fun diagnosticsLine(threads: Int): String {
        val erst = if (firstHypothesisLatencyMs > 0) "%.1f".format(firstHypothesisLatencyMs / 1000.0) else "-"
        val rtf = if (audioMs > 0) "%.2f".format(processingMs.toDouble() / audioMs.toDouble()) else "-"
        return "Diagnose: erst=${erst}s RTF=${rtf} T=$threads Peak=${levelPeak.short()}"
    }

    /** Sichtbare Route für die UI (von der IME aus dem AudioRouter gesetzt). */
    @Volatile var route: String = "builtin"
        private set

    /** Letzter Fehlercode (nie Textinhalt); null im Normalfall. */
    @Volatile var lastError: String? = null
        private set

    val state: DictationState
        get() = controller.state

    /**
     * C-2: ALLE Fehlerkanäle vereint — Audio-Fehler (errorListener) UND
     * Controller/ASR-Fehler (controller.lastErrorCode) — damit die Statuszeile
     * nichts verschluckt. Nie Textinhalt, nur Codes.
     */
    val visibleError: String?
        get() = lastError ?: controller.lastErrorCode

    private val engine: AsrEngine = engineFactory()
    // F21: cost-aware Partial-Takt an (C2-Milderung); Finale/stop bleiben unberührt.
    private val controller =
        DictationController(engine, sink, tracker = tracker, decodePacing = true)
    private var source: AudioSource? = null
    /** W2 (vc34, Debugger-Empfehlung): true bis shutdown() — die Engine bleibt
     *  RESIDENT (bewusst KEIN Idle-Unload nach Sitzungsende; das nächste
     *  Diktat startet warm, 🐞 warm_hit=1). W1: Quelle für warm_hit beim Start. */
    @Volatile private var engineLoaded = false
    private val closed = java.util.concurrent.atomic.AtomicBoolean(false)

    private val active = AtomicBoolean(false)
    private val seqCounter = AtomicLong(0)
    private var channel: Channel<PcmBlock>? = null
    private var consumer: kotlinx.coroutines.Job? = null
    private var captureThread: Thread? = null

    /**
     * Fehlerkanal der AudioSource (Capture-/Init-/Systemfehler): kontrolliertes
     * Beenden (abort-Pfad), Editor bleibt unverändert. Public, damit die
     * AudioSource-Implementierung (und Tests) ihn verdrahten können.
     */
    val errorListener = AudioErrorListener { code, detail ->
        lastError = "$code:$detail"
        // F26a: Audio-Systemfehler (C2-Export-Pfad E5) — Grund mitschreiben.
        abort("audio_error:$code")
    }

    /**
     * P0-Befund2 (2026-11-01): Observer für [abort] (jeden Grund: Audio-Error,
     * michold_cancel, start_abandoned, ime_destroy …). Der Callback läuft auf
     * dem Main-Dispatcher (wie [onStopped]), damit der Service dort seinen
     * UI-Sync macht. Default null = Verhalten wie bisher.
     */
    var onAborted: ((reason: String) -> Unit)? = null

    /**
     * Startet den Diktatbetrieb (Audio + Controller + Router-Beobachtung).
     * Idempotent: nur aus IDLE; laufend → false ohne Neustart.
     */
    fun start(): Boolean {
        // F13b2: Start-Dauer (🐞: start_ms) und Watchdog-Indikator (🐞: starting)
        // — inkl. engine.load(); NIE uptimeMillis (Deep-Sleep, Review F13c P2).
        starting = true
        val t0 = System.nanoTime()
        try {
        if (!active.compareAndSet(false, true)) return false
        if (controller.state != DictationState.IDLE) {
            active.set(false)
            return false
        }
        lastError = null
        lastAbortReason = "-" // F26a: jeder Start beginnt mit offenem Ausgang
        // S12b: Diagnose-Z�hler je Aufnahme zuruecksetzen
        firstHypothesisLatencyMs = 0
        processingMs = 0
        audioMs = 0
        levelPeak.reset() // Pegel je Aufnahme neu beginnen (Diagnose)
        startedAtMs = System.currentTimeMillis()
        // F28b1: Stall-Messung je Aufnahme zurücksetzen; Referenz = Startzeit
        // (0 Blöcke ⇒ Stall zählt ab Start, nicht ab Unendlichkeit).
        capBlocks = 0
        lastBlockAtMs = nowMs()
        // F42: Erstmessung je Aufnahme frisch — Referenz = Startzeit auf der
        // Watchdog-Uhr (monoton, nicht Wallclock).
        startRefMs = nowMs()
        firstLoudMs = -1L
        first3sLoudBlocks = 0
        first3sPeakRms = 0.0
        // F31: Stille-Uhr je Aufnahme zurücksetzen (Referenz = Startzeit, wie die
        // Stall-Uhr — 0 laute Blöcke ⇒ Stille zählt ab Start, nicht ab Unendlichkeit).
        lastLoudAtMs = nowMs()
        loudMs = 0
        everLoud = false // F31b: Stille-Erkennung je Aufnahme neu aktiv
        // F28b2: Session-Ende-Diagnose je Aufnahme frisch (Export: wer beendet,
        // was zeigte die Statuszeile — nie Ereignisse der VORHERIGEN Session).
        micRelValue = ""
        statusShownValue = "ready"
        // B1: Segmenter je Aufnahme neu — Grenzanforderung quittieren, aktives
        // Segment startet JETZT, Mini-Finale-Zähler auf 0 (Session-Zähler).
        segmentBoundaryRequested = false
        segmentStartedAtAudioMs = 0L
        segmentFinalizations = 0
        segmentPauseCutAtLoudMs = -1L
        // AP3 (Export v2 Teil 1, PLAN_C2_FABLE_R2 §4): Session-Forensik neu —
        // start_src über das F49-Gate abgeleitet (Halten = Finger unten beim
        // Start ⇒ Gate zu ⇒ hold; Toggle-Start ⇒ Gate offen ⇒ toggle; die
        // asynchrone Start-Coroutine macht einen Toggle-UP vor start() unsichtbar
        // — für Diagnosezwecke ausreichend deterministisch, KDoc).
        startSrcValue = if (autoStopSuppressed()) "hold" else "toggle"
        gateAtStopValue = -1 // noch kein Stopp
        explicitStopCauseValue = "-"
        maxPauseMsValue = 0L
        ownerLostAtAudioMsValue = -1L
        selUnexpectedValue = 0
        restartInputsValue = 0
        // F50 (PLAN_V0130 A2, V4): start_ms je Aufnahme neu — Vorbereitung für den
        // Nur-wenn-0-Schreibschutz im finally (der ERSTE Start einer Aufnahme
        // gewinnt; ein Kollisionslauf überschreibt nicht).
        lastStartMs = 0L
        // C-2/C-4: Engine VOR dem ersten Decode laden (genau einmal); ein
        // Fehlschlag ist sichtbar (lastError) und beendet den Start kontrolliert.
        // W1 (vc34): warm_hit messen (Start fand die Engine bereits resident —
        // Show-Preload griff) und über das Single-Flight-Gate laden: Preload und
        // Start teilen sich EINE Pforte, parallele load() sind unmöglich. Der
        // lazy-Fallback bleibt unangetastet — ein fehlgeschlagener Preload
        // blockiert diesen Start NICHT.
        warmHit = engineLoaded
        if (modelDir != null && !engineLoaded) {
            try {
                loadEngineOnce("mic")
            } catch (e: Exception) {
                lastError = "model:" + (e.message?.take(80) ?: e.javaClass.simpleName)
                active.set(false)
                return false // kein Mikrofon, kein FGS-Zustand im Coordinator
            }
        }
        val src = try {
            audioSourceFactory()
        } catch (e: Exception) {
            // C-5: Audio-Init-Wurf nie durch den TouchListener propagieren
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
        // A1 (PLAN_V0133 §1): Watchdog-Baselines am ECHTEN Capture-Start —
        // engine.load() (Kaltstart, bis ~4,5 s) darf die Stall-/Stille-Uhren
        // nicht vorlaufend machen: der erste leere Poll nach langer Ladezeit
        // löste sonst sofort ERR_STALL/ERR_SILENT aus. EIN Messwert, drei
        // Uhren gemeinsam; Startdauer-Diagnose (lastStartMs) misst weiter
        // ab start() inkl. engine.load(). Keine Schwellwertänderung.
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
            // F50 (V4): Kollisionslauf überschreibt NICHT — nur der erste Start
            // einer Aufnahme schreibt start_ms (Export-Klarheit).
            if (lastStartMs == 0L) {
                lastStartMs = (System.nanoTime() - t0) / 1_000_000
            }
        }
    }

    /**
     * F13d/P1: Läuft die Capture/Session noch? Nach einem Consumer-Tod wird
     * active=false gesetzt — der Capture-Thread produziert dann KEINE Samples
     * mehr (Capture-Loop-While-Bedingung), der Kanal ist zu.
     */
    val isActive: Boolean get() = active.get()

    /** F13b2: 0/1 — Coordinator-Start läuft gerade (engine.load etc.); der
     *  Watchdog-Fall ist 1 mit ungewöhnlich hohem start_ms. */
    @Volatile var starting: Boolean = false
        private set

    /** F13b2: Dauer des letzten Starts in ms (0 = noch nicht gemessen). */
    @Volatile var lastStartMs: Long = 0L
        private set

    // ---- W-Paket (vc34, Debugger-Empfehlung): Engine-Warming-Diagnose ----
    // (Regel 9: nur Zahlen/Codes — engine_load_ms, engine_resident, warm_hit,
    // load_trigger, engine_loads; NIE Modellpfade/-inhalte.)

    /** W1 (vc34): reine Dauer des letzten engine.load()-Laufs in ms (0 = nie). */
    @Volatile var engineLoadMs: Long = 0L
        private set

    /** W1 (vc34): Anzahl ausgeführter engine.load()-Läufe (monoton bis shutdown). */
    @Volatile var engineLoads: Int = 0
        private set

    /** W1 (vc34): Auslöser des letzten erfolgreichen load() — mic | ime_show;
     *  "-" = noch nie geladen (🐞 load_trigger). */
    @Volatile var loadTrigger: String = "-"
        private set

    /** W1 (vc34): 0/1 — der letzte start() fand die Engine bereits resident
     *  vor (Show-Preload griff; 🐞 warm_hit). */
    @Volatile var warmHit: Boolean = false
        private set

    /** W1/W2 (vc34): Engine resident? true bis shutdown() — KEIN Idle-Unload. */
    val engineResident: Boolean get() = engineLoaded

    /** W1 (vc34): Single-Flight-Gate für engine.load() — Show-Preload (ime_show)
     *  und Diktat-Start (mic) laufen über EINE Pforte; ein nebenläufiger zweiter
     *  Aufrufer sieht nach dem Gate das Ergebnis (double-checked) statt ein
     *  paralleles load() anzustoßen (ParakeetAsrEngine.load wirft beim
     *  Zweitaufruf — parallele load() sind damit UNMÖGLICH). */
    private val loadGate = Any()

    /**
     * W1 (vc34, Debugger-Empfehlung): lädt die Engine GENAU EINMAL (Single-Flight
     * über [loadGate]). Misst die reine load-Dauer ([engineLoadMs]), zählt
     * [engineLoads] und merkt den Auslöser [trigger] (mic|ime_show) — nur bei
     * ERFOLG; eine fehlgeschlagene Engine lässt engineLoaded=false, der spätere
     * Start wird NICHT blockiert. Aufrufer: start() (Fehler ⇒ kontrollierter
     * Start-Abbruch) und [preload] (Fehler ⇒ false).
     */
    private fun loadEngineOnce(trigger: String) {
        if (engineLoaded) return
        synchronized(loadGate) {
            if (engineLoaded) return // double-checked: parallel laufendes load() hat gewonnen
            val dir = modelDir ?: return
            val t0 = System.nanoTime()
            try {
                engine.load(dir)
            } finally {
                engineLoadMs = (System.nanoTime() - t0) / 1_000_000
            }
            engineLoads += 1
            loadTrigger = trigger
            engineLoaded = true // W2: resident bis shutdown()
        }
    }

    /**
     * W1 (vc34, Debugger-Empfehlung): öffentliche Vorwärmung — Aufrufer:
     * VoiceKeyboardService beim ERSTEN onStartInputView, NUR im Worker-Scope
     * (load ist teuer, nie Main; braucht weder Mikro noch FGS). Single-Flight
     * über [loadGate]: ein weiterer Aufruf (zweiter Show, paralleler Thread)
     * startet KEIN zweites load(). Ein Fehlschlag wird geschluckt (false) und
     * blockiert den späteren Diktat-Start NICHT — dieser lädt lazy über
     * dieselbe Pforte nach (Bestands-Fallback bleibt unangetastet).
     * Rückgabe: true ⇔ Engine ist (nun) resident.
     */
    fun preload(): Boolean {
        if (modelDir == null) return false
        if (engineLoaded) return true
        return try {
            loadEngineOnce("ime_show")
            engineLoaded
        } catch (e: Exception) {
            false // fehlgeschlagener Preload: start() lädt später lazy nach
        }
    }

    // ---- F28b1: Capture-Stall-Messung (Regel 9: nur Zahlen, nie Audio-Inhalte) ----

    /** F28b1: empfangene Audio-Blöcke (n>0) dieser Session (🐞: cap_blocks). */
    @Volatile private var capBlocks = 0

    /** F28b1: Zeitpunkt des letzten empfangenen Blocks bzw. des Sessionstarts
     *  (ms auf der [nowMs]-Uhr); 0 = Session nie gestartet. */
    @Volatile private var lastBlockAtMs = 0L

    /** F31: Zeitpunkt des letzten LAUTEN Blocks (Block-RMS ≥ [SILENCE_RMS]) bzw.
     *  des Sessionstarts (ms auf der [nowMs]-Uhr); 0 = Session nie gestartet.
     *  F31b: solange [everLoud] false ist, gilt hier immer die Sessionstart-
     *  Referenz ("Stille seit Start"). */
    @Volatile private var lastLoudAtMs = 0L

    /** F31b (REVIEW-P1): true, sobald die Session EINEN lauten Block gesehen
     *  hat — danach ist der Stille-Watchdog dauerhaft deaktiviert (Sprechpausen
     *  sind erlaubt). Reset in [start]. */
    @Volatile private var everLoud = false

    /** F31: Gesamte Audiodauer LAUTER Blöcke dieser Session in ms (🐞: loud_s). */
    @Volatile private var loudMs = 0L

    /** F42 (🐞-Forensik): Startzeit auf der Watchdog-Uhr (nowMs) — Referenz für
     *  first_loud_ms und das 3-s-Startfenster; 0 = nie gestartet. */
    @Volatile private var startRefMs = 0L

    /** F42 (🐞: first_loud_ms): ms nach Sessionstart beim everLoud-Latch; -1 = nie. */
    @Volatile private var firstLoudMs = -1L

    /** F42 (🐞: first3s_loud_blocks): laute Blöcke in den ersten 3 s (Startfenster). */
    @Volatile private var first3sLoudBlocks = 0

    /** F42 (🐞: first3s_peak_dbfs): höchster Block-RMS der ersten 3 s (0 = kein Block). */
    @Volatile private var first3sPeakRms = 0.0

    // ---- B1 (Segmenter-Kern): Anforderungs-Handshake Capture -> Consumer ----

    /** B1: Capture-Thread hat eine Segmentgrenze angemeldet; der Consumer
     *  (Puffer-Eigentümer) holt sie am nächsten Tick ein. */
    @Volatile private var segmentBoundaryRequested = false

    /** B1: Start des AKTIVEN Segments als AUDIO-POSITION (Session-ms, Consumer-
     *  Maß) — Referenz der Längenregel (b), die der CONSUMER über seine eigene
     *  Pufferposition auswertet (deterministisch: Fenster ≤ Deckel + 1 Tick).
     *  NICHT die Watchdog-Uhr — die eilt im Self-Paced-Betrieb der Capture
     *  voraus, dort wäre (b) rennend (B1-Befund erster Läufe: 54-s-Fenster
     *  trotz 35-s-Deckel). */
    @Volatile private var segmentStartedAtAudioMs = 0L

    /** B1: Anzahl ausgeführter Segment-Mini-Finale dieser Session (Session-
     *  Zähler, monoton; Reset in [start]). */
    @Volatile private var segmentFinalizations = 0

    /** B1: Wert von lastLoudAtMs bei der zuletzt GESCHNITTENEN Pause — Wächter
     *  gegen wiederholtes Regel-(a)-Feuern in DERSELBEN Pause (streamende Stille
     *  hält (now-lastLoud) ≥ [SEGMENT_PAUSE_MS] dauerhaft true; ohne Wächter
     *  würde der Consumer leere Silent-Segmente finalisieren, bis neue Sprache
     *  kommt). Eine NEUE Pause ⇒ neuer lastLoudAtMs ⇒ Wächter frei. -1 = noch
     *  keine Grenze ausgeführt. */
    @Volatile private var segmentPauseCutAtLoudMs = -1L

    /** F31: Sekunden mit lautem Signal der Session (🐞: loud_s; nur Zahl, Regel 9). */
    fun loudS(): Double = loudMs / 1000.0

    /** F42 (🐞: first_loud_ms): ms nach Sessionstart beim everLoud-Latch; -1 = nie
     *  laut (zeigt, ob Sprache VOR dem 3-s-Startfenster ankam). */
    fun firstLoudMs(): Long = firstLoudMs

    /** F42 (🐞: first3s_loud_blocks): Blöcke mit RMS ≥ Schwelle in den ersten 3 s. */
    fun first3sLoudBlocks(): Int = first3sLoudBlocks

    /** F42 (🐞: first3s_peak_dbfs): Peak-Block-RMS der ersten 3 s in dBFS;
     *  null = kein Block im Fenster. Gegencheck, ob ein ERR_SILENT-Abbruch
     *  berechtigt war (Peak weit unter −40 dBFS) oder Signal verloren ging. */
    fun first3sPeakDbfs(): Double? =
        if (first3sPeakRms <= 0.0) null
        else 20.0 * kotlin.math.log10(first3sPeakRms)

    /** Capture-Thread: blockiert NUR in read; verliert keine Blöcke (unbegrenzter Kanal). */
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
                        // F28b1: Block empfangen — Stall-Uhr zurücksetzen (🐞: cap_blocks).
                        capBlocks += 1
                        lastBlockAtMs = nowMs()
                        // F31: Block-RMS direkt aus dem erfassten Block (dieselbe
                        // Rechnung wie der Pegelkanal — source-unabhängig, funktioniert
                        // auch mit der Debug-Fake-Quelle). Laut => Stille-Uhr zurück.
                        // Nur Zahlen, nie Inhalte (Regel 9).
                        var acc = 0L
                        for (i in 0 until n) acc += buf[i].toLong() * buf[i].toLong()
                        val rms = kotlin.math.sqrt(acc.toDouble() / n) / 32_768.0
                        // F42 (🐞: first_loud_ms / first3s_loud_blocks /
                        // first3s_peak_dbfs): Wann griff der everLoud-Latch — und
                        // wie viele laute Blöcke / welcher Peak lagen in den
                        // kritischen ersten 3 s (Startfenster des Silent-Watchdogs)?
                        // Gegencheck für ERR_SILENT-Abbrüche; nur Zahlen (Regel 9).
                        val sinceStartMs = (nowMs() - startRefMs).coerceAtLeast(0L)
                        if (sinceStartMs <= SILENT_START_LIMIT_MS) {
                            if (rms >= SILENCE_RMS) first3sLoudBlocks++
                            if (rms > first3sPeakRms) first3sPeakRms = rms
                        }
                        if (rms >= SILENCE_RMS) {
                            if (!everLoud) firstLoudMs = sinceStartMs
                            // AP3: längste Laut-Lücke der Session (Block-RMS-Kriterium
                            // wie Auto-Stop) — NUR zwischen lauten Blöcken, nie ab
                            // Sessionstart (0 Laut-Blöcke wären sonst "Stille").
                            if (everLoud) {
                                val gapMs = nowMs() - lastLoudAtMs
                                if (gapMs > maxPauseMsValue) maxPauseMsValue = gapMs
                            }
                            everLoud = true // F31b: ab hier ist Stille erlaubt
                            lastLoudAtMs = nowMs()
                            loudMs += n * 1000L / 16_000L
                        }
                    }
                    n < 0 -> { // Fehler: Fehlerkanal hat bereits abort() ausgelöst
                        return
                    }
                }
                // F28b1: Stall-Watchdog — eine Quelle, die dauerhaft 0 Samples liefert
                // (AudioRecord time out), ließ die Loop sonst endlos laufen und die
                // Session ewig "Diktat" bleiben. Nach STALL_LIMIT_MS ohne Block:
                // kontrolliertes Ende über den BESTEHENDEN Fehler-/Abort-Pfad —
                // genau einmal (Single-Thread, return nach dem Feuer), kein neuer
                // Thread, kein Audio-Inhalt im Fehlercode (Regel 9).
                if (capStallMs() > STALL_LIMIT_MS) {
                    errorListener.onError(ERR_STALL, "no_audio")
                    return
                }
                // F31/F31b: Blöcke LAUFEN, die Session war aber NOCH NIE laut
                // (Nutzerfall: −56 dBFS Rauschen von Anfang an). F31b (REVIEW-P1):
                // !everLoud-Gate — nach dem ersten lauten Block ist dieser
                // Watchdog dauerhaft deaktiviert (natürliche Sprechpausen > 3 s
                // dürfen gute Sessions nicht abbrechen; fehlende Blöcke deckt
                // ERR_STALL weiterhin ab). Referenz = Sessionstart auf der
                // [nowMs]-Uhr (letzter lauter Block fehlt per Definition, also
                // ist lastLoudAtMs die Startzeit). Gleicher bestehender
                // Fehlerpfad: nur einmal, kein neuer Thread. Kein-Block-Stall
                // (ERR_STALL) hat Vorrang — checkt oben; capBlocks > 0 trennt
                // die Fälle.
                if (capBlocks > 0 && !everLoud &&
                    (nowMs() - lastLoudAtMs) > SILENT_START_LIMIT_MS
                ) {
                    errorListener.onError(ERR_SILENT, "no_signal")
                    return
                }
                // F43: Stille NACH Sprechbeginn (≥ TRAILING_SILENCE_MS seit dem
                // letzten lauten Block) → AUTOMATISCHER regulärer Abschluss über
                // stop() — kein ERR_*, finaler Decode über den Gesamtpuffer;
                // stop() joint zuerst den Consumer (bereits erfasste Blöcke werden
                // komplett dekodiert, keine gesprochenen Zeichen verloren). Die
                // Session gilt danach wie vom Nutzer beendet (onStopped-Finale in
                // der IME). Kürzere Pausen (< 2500 ms) unterbrechen nie.
                // F43-Freigabe (c-Gate): Auto-Stopp NUR bei STREAMENDER Stille
                // (capStallMs < TRAILING_SILENCE_MS — Blöcke laufen weiter, nur
                // leise). Projektdoktrin „Stille ≠ Stall" (F30/F28b1): eine TOTE
                // Quelle (keine Blöcke) bleibt beim ERR_STALL-Vertrag — der
                // Auto-Stopp preemt ERR_STALL NICHT (FakeAudioScenarioTest c).
                // F49: Halte-Geste signalisiert „noch nicht fertig" — Auto-Stop
                // nur, wenn das Gate offen ist (Toggle; Watchdogs unberührt).
                if (everLoud && capStallMs() < TRAILING_SILENCE_MS &&
                    !autoStopSuppressed() &&
                    (nowMs() - lastLoudAtMs) > TRAILING_SILENCE_MS
                ) {
                    // AP3: Auto-Stop explizit markieren (stop_cause=autostop, nicht
                    // final) + Gate im Stop-Moment (hier per Guarddefinition offen=1).
                    explicitStopCauseValue = "autostop"
                    gateAtStopValue = 1
                    stop()
                    return
                }
                // B1 (PLAN_V0130 §6.3 (c), I3 α=38,5 ms/s): Segmentgrenze ANMELDEN
                // — Ausführung holt der Consumer am nächsten Tick ein (er ist
                // der alleinige Puffer-Eigentümer; sequenziell mit feedAudio ⇒
                // keine Tonlücke, laufende Blöcke queue im Kanal). Der Capture
                // meldet nur die STILLE-Regel (a) an: Satzpause (≥
                // [SEGMENT_PAUSE_MS] streamende Stille) UND letzte Hypothese endet
                // auf Satzzeichen — schneidet an SATZenden, wo die Naht harmlos
                // ist. Die LÄNGEN-Regel (b) trägt der CONSUMER selbst (Puffer-
                // basiert, deterministisch) — die Watchdog-Uhr eilt im Self-
                // Paced-Betrieb der Capture voraus, eine clock-basierte (b) wäre
                // rennend; daher segmentStartedAtMs = nowMs() (Abstand 0).
                // Mini-Finale committet PERMANENT und setzt nur Puffer/Decode-
                // Statistik zurück — Session/FGS/Watchdogs laufen weiter.
                if (segmentFinalizeMs > 0 &&
                    lastLoudAtMs != segmentPauseCutAtLoudMs && // jede Pause nur EINMAL
                    segmentBoundary(
                        nowMs = nowMs(),
                        lastLoudAtMs = lastLoudAtMs,
                        everLoud = everLoud,
                        capStallMs = capStallMs(),
                        segmentStartedAtMs = nowMs(), // (b) trägt der Consumer
                        lastHypothesisEndsWithPunct = controller.lastHypothesisEndsWithPunct(),
                        segmentFinalizeMs = segmentFinalizeMs,
                    )
                ) {
                    segmentBoundaryRequested = true
                }
            }
        } catch (e: InterruptedException) {
            // stop(): Capture-Thread auslaufen lassen
        } catch (e: Exception) {
            // Capture-Thread stirbt nie still: kontrollierter abort-Pfad
            errorListener.onError(ERR_AUDIO, e.javaClass.simpleName)
        }
    }

    /** Consumer: aggregiert bis ~tickSamples NEUE Samples, dann feedAudio (in Reihenfolge). */
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
        flush(acc, accStart) // Reste beim Stop: NICHT verlieren
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
            // S12b Diagnose: Verarbeitungszeit je Chunk + Audiodauer akkumulieren
            // (nur Zahlen; RTF = processingMs/audioMs in diagnosticsLine).
            val t0 = System.currentTimeMillis()
            controller.feedAudio(PcmBlock(seqCounter.getAndIncrement(), startSample, combined))
            val dt = System.currentTimeMillis() - t0
            processingMs += dt
            audioMs += total * 1000L / 16_000L
            if (firstHypothesisLatencyMs == 0L && startedAtMs > 0) {
                firstHypothesisLatencyMs = System.currentTimeMillis() - startedAtMs
            }
            // B1: angemeldete Segmentgrenze EINHOLEN — hier im Consumer, dem
            // alleinigen Puffer-Eigentümer, sequenziell mit feedAudio (keine
            // Tonlücke; die ~α·T Decode-Zeit überbrückt der unbegrenzte Kanal).
            // Mini-Finale: finaler Decode über das AKTUELLE Segment-Puffer
            // + PERMANENTER Commit; danach setzt der Controller NUR Puffer +
            // Decode-Statistik zurück (Session-Zähler bleiben Session-Werte).
            // (b) LÄNGEN-Regel: der Consumer wertet sie über seine EIGENE
            // Audio-Position aus (audioMs − Segmentstart) — deterministisch
            // begrenzt auf Deckel + 1 Tick (~37 s); die (a)-Eingänge sind hier
            // neutralisiert (everLoud=false): der Consumer schneidet nur nach
            // LÄNGE, die Stille-Regel kommt als Anforderung der Capture.
            // Fehler des Segment-Decodes laufen über die bestehenden Fänge
            // unten (OOM/ISE/Exception) — kein neuer Fehlerpfad.
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
                    // B1-Race-Fix: Wächter VOR dem (suspendierenden) Finalize
                    // setzen — sonst setzt der Capture während des in-flight
                    // Final-Decodes erneut einen (a)-Request für dieselbe Pause
                    // (Befund: Doppelgrenze in einer Pause), den der nächste
                    // Tick dann einholt.
                    segmentPauseCutAtLoudMs = lastLoudAtMs
                    segmentBoundaryRequested = false
                    if (controller.finalizeSegment()) {
                        segmentFinalizations += 1
                        segmentStartedAtAudioMs = audioMs
                        segmentBoundaryRequested = false // Race-Nachbereitung
                    } else {
                        // B1-Fix (Review P1): transienter Fehler im Mini-Finale
                        // (Controller verwirft ohne fail()) — Antrag verwerfen
                        // UND Wächter zurücksetzen, damit der NÄCHSTE Boundary-
                        // Versuch (nächste Satzpause/Längenregel) wieder feuern
                        // darf; Session/FGS laufen unberührt weiter.
                        segmentPauseCutAtLoudMs = -1L
                    }
                }
            }
        } catch (e: OutOfMemoryError) {
            // F3-R: nativer ORT-Speicherfehler während der Inferenz läuft in
            // DIESEM Worker — ohne Fang bricht er nur die Coroutine (unsichtbar).
            // Kontrolliert beenden + stabiler Fehlercode "oom" über den
            // bestehenden Kanal (mapVisibleError mappt oom →
            // dictation_error_out_of_memory). Kein Text/Audio im Code.
            // NUR OOM — andere Error bleiben bewusst ungefangen.
            android.util.Log.w(TAG, "oom during partial decode")
            lastError = "$ERR_OOM:${e.javaClass.simpleName}"
            active.set(false)
            channel?.close()
            controller.abort()
            notifyAborted(ERR_OOM) // F44: Kill-Pfad observable — Service stoppt den FGS
            lastStopOutcome = de.voicekeyboard.dictation.StopOutcome.NoFinal(
                "$ERR_OOM:${e.javaClass.simpleName}",
            )
            routeObserver?.invoke(false)
            return
        } catch (e: IllegalStateException) {
            // F13d/P1: Editor-Sync-Timeout o.ä. — OHNE diesen Fang stirbt der
            // Consumer-Job STILL, der Capture-Thread läuft weiter und der
            // unbegrenzte Kanal wächst, ohne dass der Nutzer etwas sieht.
            // Kontrolliertes Ende: Grund merken (Regel: kein schluckendes Fangen
            // ohne Meldung), Sitzung abbrechen, Kanal schließen, Capture über
            // active=false auslaufen lassen, Ausgang als StopOutcome sichtbar.
            // F56 (vc33-Gerätefund, Fable §0.1.5-Familie): der EINZIGE ISE-Typ,
            // der hier ankommen kann, ist der RETHROWN-Editor-Sync-Timeout
            // (alle anderen ISE verschluckt feedAudio über fail(ASR_PARTIAL_
            // ERROR)) — der Controller hat fail(editor_sync_timeout) VOR dem
            // Rethrow gesetzt. Der FEINE Grund darf vom generischen
            // consumer:<Klasse> NICHT MASKIERT werden (F1-Vertrag: Drift sicht-
            // bar; vorher zeigte visibleError=consumer:IllegalStateException
            // → Else-Fallback-Fehlertext, fgs_stopped_by=abort:consumer:…).
            val iseCode = lastError ?: controller.lastErrorCode
                ?: "consumer:" + e.javaClass.simpleName
            if (lastError == null) lastError = iseCode
            if (lastAbortReason == "-") lastAbortReason = iseCode
            active.set(false)
            channel?.close()
            controller.abort()
            notifyAborted(iseCode) // F44: Kill-Pfad observable — MIT feinem Code
            lastStopOutcome = de.voicekeyboard.dictation.StopOutcome.NoFinal(
                controller.lastErrorCode ?: lastError,
                failedWith = true,
                droppedWith = controller.droppedBlocks,
            )
            routeObserver?.invoke(false)
            return
        } catch (e: Exception) {
            // F13d/P1: jeder weitere gefangene Fall setzt einen SICHTBAREN Grund
            // (kein stilles Sterben des Consumers, kein wachsender Kanal).
            if (lastError == null) lastError = "consumer:" + e.javaClass.simpleName
            active.set(false)
            channel?.close()
            controller.abort()
            notifyAborted("consumer:" + e.javaClass.simpleName) // F44: Kill-Pfad observable
            lastStopOutcome = de.voicekeyboard.dictation.StopOutcome.NoFinal(
                controller.lastErrorCode ?: lastError,
                failedWith = true,
                droppedWith = controller.droppedBlocks,
            )
            routeObserver?.invoke(false)
            return
        }
        // C-2b: Controller ist nach einem ASR-Fehler IDLE — Capture weiterlaufen
        // zu lassen hieße ein offenes Mikrofon ohne Nutzen. Kontrolliert beenden.
        if (controller.state == DictationState.IDLE && active.get()) {
            active.set(false)
            channel?.close()
            // AP3 (Fable §0.1 Punkt 5, „:696-Überschreiber“): first-writer-keeps-
            // detail — ein VORHER gesetztes FEINES Detail (editor_change,
            // michold_cancel, abort:<r>) darf von dem generischen ASR-Fehlercode
            // des Consumer-Endes NICHT zugedeckt werden. Nur wenn noch offen
            // ("-" / kein abort-Grund gesetzt), ist audio_error die Ursache.
            if (lastAbortReason == "-") {
                lastAbortReason = "audio_error:${controller.lastErrorCode ?: "unknown"}"
            }
            controller.abort()
            notifyAborted(lastAbortReason) // F44: Kill-Pfad observable — Service stoppt den FGS
            routeObserver?.invoke(false)
        }
    }

    /**
     * Beendet kontrolliert: Capture stoppen, Queue und Reste drainen (in
     * Reihenfolge), dann finales Decode über den Gesamtpuffer — unabhängig
     * von der Mindestlänge (Guard gilt nur für Partials, §4.4).
     */
    fun stop() {
        if (!active.compareAndSet(true, false)) {
            // F26a: No-Op-Fall mit Grund (C2-Export: E3/E4/E5 trennen sich nicht).
            if (lastAbortReason == "-") lastAbortReason = "idle_noop"
            return // ohne Start: No-Op
        }
        val src = source ?: return
        val ch = channel ?: return
        // AP3: F43-Gate-Zustand im Stop-Moment — 1 = Gate OFFEN (Auto-Stop
        // nicht unterdrückt), 0 = ZU (noch gehalten), -1 = noch kein Stopp.
        // Nur der ERSTE Stopp der Session gewinnt (W1b: autostop + gate=1 +
        // start_src=hold = Gate mitten im Halten verloren).
        if (gateAtStopValue == -1) {
            gateAtStopValue = if (autoStopSuppressed()) 0 else 1
        }
        // Capture-Loop stoppt (active=false); Reste der Quelle einsammeln und
        // VOR dem close in den Kanal legen — trySend nach close würde verlieren.
        for (block in src.stopAndDrain()) ch.trySend(block)
        ch.close()
        val consumerJob = consumer
        val thread = captureThread
        captureThread = null
        source = null
        channel = null
        // C-1: Final-Decode + Drain im Worker-Scope — KEIN runBlocking auf dem
        // IME-Main-Thread (DESIGN §2.3). stop() kehrt sofort zurück.
        scope.launch {
            consumerJob?.join()
            try {
                val outcome = controller.stop() // F13b: Ausgang BEVOR onStopped läuft
                lastStopOutcome = outcome
                // F26a: Final-Outcome mitschreiben — ueberschreibt keinen Abort-Grund.
                if (lastAbortReason == "-") {
                    lastAbortReason = when (outcome) {
                        is de.voicekeyboard.dictation.StopOutcome.FinalRan -> "final"
                        is de.voicekeyboard.dictation.StopOutcome.NoFinal -> "no_final:${outcome.errorCode ?: "unknown"}"
                        else -> "idle_noop"
                    }
                }
            } catch (e: OutOfMemoryError) {
                // F3-R: OOM im Final-Decode — dito flush()-Pfad
                android.util.Log.w(TAG, "oom during final decode")
                lastError = "$ERR_OOM:${e.javaClass.simpleName}"
                controller.abort()
            } catch (e: IllegalStateException) {
                // F56: Editor-Sync-Timeout im GESAMT-Final — derselbe F1-Rethrow
                // wie im Consumer-Pfad. OHNE diesen Fang tötete die ISE den
                // Final-Worker UNGEFANGEN (Coroutine-Tod: kein onStopped-Finale,
                // kein FGS-Sync im Service; je nach Scope-Handler Prozess-Crash).
                // Identischer Vertrag wie der Consumer-Catch: feiner Code sichtbar
                // (editor_sync_timeout), nicht maskiert; Session kontrolliert Ende.
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
                notifyAborted(iseCode) // F44: Kill-Pfad observable — Service-Sync läuft
            } catch (e: Exception) {
                // F56: jede andere Exception aus dem Final-Decode — gleiche
                // kontrollierte Endung (vorher: ungefangener Coroutine-Tod).
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
                notifyAborted(excCode) // F44: Kill-Pfad observable
            }
            src.release()
            thread?.let { t -> t.join(500) }
            // N-2: Callback (View-Mutation!) NIEMALS im Worker-Kontext aufrufen
            if (mainDispatcher != null) {
                kotlinx.coroutines.withContext(mainDispatcher) { onStopped?.invoke() }
            } else {
                onStopped?.invoke()
            }
        }
        routeObserver?.invoke(false)
    }

    /**
     * F13b: Ausgang des LETZTEN stop()-Finals (asynchron belegt, bevor onStopped
     * läuft) — der Service bewertet hiermit, ob der Abschluss ein echter Erfolg
     * war oder eine still gescheiterte Session ([StopOutcome.NoFinal]).
     */
    @Volatile var lastStopOutcome: de.voicekeyboard.dictation.StopOutcome =
        de.voicekeyboard.dictation.StopOutcome.IdleNoOp
        private set

    /** F13b: Fehlerzustand der aktuellen Controller-Session (bis zum nächsten Start bewahrt). */
    val failedThisSession: Boolean get() = controller.failedThisSession

    /** F13b2: Controller-Zustand als Kurzcode (IDLE/RECORDING/FINALIZING) — der
     *  🐞-Beweisfall C1: IDLE, während der Nutzer noch drückt. */
    val ctlState: String get() = controller.state.name

    /** F13b2: erzeugte Hypothesen der aktuellen Sitzung (🐞: hyp_count). */
    val hypCount: Int get() = controller.hypothesesThisSession

    /** F13b2: Abstand seit der letzten Hypothese in ms, -1 wenn nie (🐞: hyp_gap_ms). */
    fun hypGapMs(): Long = controller.currentHypGapMs()

    /** F13b2: aufgenommenes Audio in Sekunden (🐞: audio_s, 1 Nachkommastelle). */
    /** B1-Fix (Review P2a): 🐞 audio_s = SESSION-Audiolänge (abgeschlossene
     *  Segmente + aktives Pufferstück, nachgeführt) — NICHT mehr der
     *  segment-lokale Controller-Puffer, der nach jedem Mini-Finale auf 0
     *  fällt. Ohne Segmenter identisch mit dem Bestandswert. */
    val audioSeconds: Double get() = controller.sessionAudioSeconds

    /** F13b: verworfene Audio-Blocks der aktuellen Session (🐞-Diagnose F13b2). */
    val droppedBlocks: Int get() = controller.droppedBlocks

    // F17c: Decode-Arbeitslast (C2) — Durchreichen auf den Controller, 🐞: decode_ms_*

    /** Größte Decode-Zeit je transcribe-Aufruf in ms (0 = nie dekodiert). */
    fun decodeMsMax(): Long = controller.decodeMsMax()

    /** p95 der Decode-Zeiten in ms (deterministische Controller-Stichprobe). */
    fun decodeMsP95(): Long = controller.decodeMsP95()

    /** Pufferlänge in Sekunden beim letzten Dekodierlauf (🐞: buffer_s). */
    /** B1-Fix (Review P2a): 🐞 buffer_s = dieselbe nachgeführte SESSION-Länge
     *  wie [audioSeconds] (Export-Semantik); das tatsächliche Decode-Fenster
     *  des letzten Laufs zeigt hyp_last_buffer_s (segment-lokal, unverändert). */
    fun bufferSeconds(): Double = controller.sessionAudioSeconds

    /** F21: aktuell geltendes Partial-Intervall in ms; 0 = Pacing aus (🐞: decode_pace_ms). */
    fun decodePaceMs(): Long = controller.decodePaceMs()

    /** F21: wegen Takt übersprungene Partial-Decodes dieser Session (🐞: decode_skips). */
    fun decodeSkips(): Int = controller.decodeSkips()

    /** F26b (C2-Export): Bufferstand (s) der letzten akzeptierten Hypothese. */
    fun lastHypothesisBufferSeconds(): Float = controller.lastHypothesisBufferSeconds

    // ---- F28b2: Session-Ende-Diagnose (Regel 9: reine Codes + ms, keine Inhalte) ----

    /** F28b2: Zeitstempel des Sessionstarts (Wallclock); 0 = nie gestartet.
     *  Dient der IME-Schicht für die ms-Zeitstempel in [micRel]. */
    val sessionStartedAtMs: Long get() = startedAtMs

    /** F28b2: letztes loslassende/abbrechende Ereignis der Session (kodiert,
     *  +ms seit Sessionstart); "" = keines. Setzt die IME-Schicht. */
    @Volatile private var micRelValue: String = ""

    /** F28b2: zuletzt an die Statuszeile gegebener Zustand
     *  (ready|dictat|finalizing|error). Setzt die IME-Schicht. */
    @Volatile private var statusShownValue: String = "ready"

    /** F28b2: Wer hat die Session zuletzt beendet? (🐞: mic_rel, reiner Code). */
    fun micRel(): String = micRelValue

    /** F28b2: setzt [micRel] (Aufrufer: VoiceKeyboardService; kontrollierte Codes). */
    fun setMicRel(value: String) {
        micRelValue = value
    }

    /** F28b2: Was zeigte die Statuszeile zuletzt? (🐞: status_shown, reiner Code). */
    fun statusShown(): String = statusShownValue

    /** F28b2: setzt [statusShown] (Aufrufer: VoiceKeyboardService.updateStatus). */
    fun setStatusShown(code: String) {
        statusShownValue = code
    }

    /** F28b1: empfangene Audio-Blöcke (n>0) dieser Session (🐞: cap_blocks). */
    fun capBlocks(): Int = capBlocks

    /** F28b1: ms seit dem letzten Audio-Block bzw. dem Sessionstart
     *  (🐞: cap_stall_ms); 0 ohne laufende/gestartete Session. Nach Abbruch
     *  weiterhin lesbar (Export). */
    fun capStallMs(): Long =
        if (lastBlockAtMs == 0L) 0L else (nowMs() - lastBlockAtMs).coerceAtLeast(0L)

    /**
     * F26a: Grund des letzten ABORT OHNE FINALE (C2-Export: E3/E4/E5 liefen alle
     * still zusammen). String-Enum-Arten wie "michold_cancel" / "editor_change" /
     * "audio_error:<code>" / "ime_destroy"; stop() ergaenzt "final" /
     * "no_final:<code>" / "idle_noop" (ueberschreibt keinen Abort-Grund, das
     * Finale bleibt sichtbar). start() setzt auf "-". NUR kontrollierte Namen,
     * keine Inhalte (Regel 9).
     */
    @Volatile var lastAbortReason: String = "-"
        private set

    /** Abbruch ohne Finale: geordnet nach IDLE, bereits committeter Text bleibt. */
    fun abort(reason: String = "-") {
        lastAbortReason = reason
        // AP3: Gate-Sample auch auf dem Abbruchpfad (0 = Abbruch während
        // aktiver Halte-Geste — z. B. editor_change beim Halten).
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
        // P0-Befund2(b/c): Abbruch wird observable — der Service synchronisiert
        // dort den Mic-UI-Zustand und stoppt seit F44 den Mikro-FGS. Main-Hop wie onStopped.
        notifyAborted(reason)
        // C-1: Aufräumen im Worker-Scope, Main-Thread kehrt sofort zurück.
        scope.launch {
            consumerJob?.join()
            runCatching { src?.stopAndDrain(); src?.release() }
            thread?.let { t -> t.join(200) }
        }
    }

    /**
     * F44 (F41-Repro, P1 — Mikro-FGS lief ~58 s nach Watchdog-Abbruch weiter):
     * JEDE Kill-Pfad (Watchdog-Abort, Consumer-Tod, OOM, ASR-Fehler) macht den
     * Abbruch observable — der Service stoppt darüber den FGS (gleiche Stelle wie
     * beim regulären Stopp) und merkt die Ursache als fgs_stopped_by-Code.
     * Main-Hop wie bei [onStopped] (View-Mutation beim Aufrufer).
     */
    private fun notifyAborted(reason: String) {
        val abortedCb = onAborted
        if (abortedCb != null) {
            if (mainDispatcher != null) scope.launch(mainDispatcher) { abortedCb(reason) }
            else abortedCb(reason)
        }
    }

    /**
     * C-4: Engine-Ressourcen freigeben (≈863 MB native Sessions, ASR_CONTRACT §5).
     * Aufruf: IME-onDestroy / Editorwechsel-Abort. Kein zweites load() danach.
     *
     * W2 (vc34, Debugger-Empfehlung): die Engine bleibt bis HIERHER resident —
     * bewusst KEIN Idle-Unload nach Sitzungsende (das nächste Diktat startet
     * warm, warm_hit=1). Die ~863 MB nativen Sessions bleiben über die
     * Sitzung hinaus gehalten; Freigabe NUR beim IME-Tod (onDestroy).
     */
    fun shutdown() {
        // N-3: idempotent — Engine nur EINMAL schließen; laufende Final-Worker
        // werden durch abort() abgebrochen (kein transcribe nach close mehr:
        // controller.abort() setzt IDLE, bevor close() läuft).
        if (!closed.compareAndSet(false, true)) return
        abort("ime_destroy")
        engineLoaded = false
        runCatching { engine.close() }
    }

    /**
     * AP7-E1 (Fable §0.2.5 P1): Re-Baseline nach restartInput() der App — KEIN
     * Editorwechsel. Besitz bleibt, wenn der eigene committete Text noch
     * Suffix des Editor-Texts ist ([OwnershipTracker.rebaseline]); Rueckgabe
     * false ⇒ App hat den Text geloescht/geaendert ⇒ Aufrufer bricht
     * kontrolliert ab (kein Geisterschreiben). Sink ist verbindungslos je
     * Aufruf (connectionProvider-Lambda) — nach restartInput liest er die
     * NEUE Verbindung, kein Cache zu invalidieren.
     *
     * AP7-E1c (P0-Blocker): lies den Kontext in der Laenge des FRISCHEREN der
     * beiden Eigen-Staende (Tracker vs. Sink-ownText — der Sink fuehrt seinen
     * ownSpan synchron in der commit-Runde, der Tracker hinkt worker-seitig
     * nach), und gib den Sink-Stand als fresherExpected an den Tracker weiter.
     * Diagnostic-Felder exp_len/act_len (nur Laengen, Regel 9) fuer die
     * VK-SESS-Zeile des Services.
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

    /** AP7-E1: Anzahl Re-Baselines dieser Instanz (🐞 rebases, Regel 9: nur Zahl). */
    fun rebases(): Int = tracker.rebases

    /** AP7-E1d: Re-Bind-Callback darf im Retry-Fenster kein lost setzen. */
    fun openRebindBaseline() = tracker.reopenSelectionBaseline()

    /** Editorwechsel: Generation hochzählen, Besitz verwerfen (kein Schreiben danach). */
    fun onEditorChanged() {
        // F26a: Editorwechsel während aktiver Session (C2-Export-Pfad E4) —
        // der Controller bricht im RECORDING-/FINALIZING-Zweig ab, wir merken
        // den Grund VORHER (sein abort() kennt keine Gründe).
        if (controller.state == DictationState.RECORDING || controller.state == DictationState.FINALIZING) {
            lastAbortReason = "editor_change"
        }
        controller.onEditorChanged()
    }

    /** Selektions-Signal vom UI-Thread. */
    fun onUpdateSelection(oldSelStart: Int, oldSelEnd: Int, newSelStart: Int, newSelEnd: Int) {
        // AP3 (H-E2, Fable §4): unerwartete Selection-Callbacks zählen und den
        // Besitzverlust-Moment (Sekunden, Session-Audio-Uhr) festhalten — der
        // Tracker selbst bleibt unangetastet; der Flip false→true IST das
        // unerwartete Ereignis (Regel 9: nur Zähler/Zeiten).
        val lostBefore = tracker.ownershipLost
        controller.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd)
        if (tracker.ownershipLost) {
            selUnexpectedValue += 1
            if (!lostBefore && ownerLostAtAudioMsValue < 0L) {
                ownerLostAtAudioMsValue = audioMs
            }
        }
    }

    /** Route-Meldung des AudioRouter (UI-Anzeige; Aufrufer: M4c-2-Verdrahtung). */
    fun onRouteChanged(routeLabel: String) {
        route = routeLabel
    }

    /** B1: Anzahl ausgeführter Segment-Mini-Finale dieser Session (monoton,
     *  0 = Segmenter aus oder keine Grenze). Nur Zahl (Regel 9). */
    fun segmentFinalizations(): Int = segmentFinalizations

    // ---- AP3 (Export v2 Teil 1, PLAN_C2_FABLE_R2 §4): Session-Forensik ----

    /** AP3: Start-Art (hold|toggle) — über das F49-Gate beim start() abgeleitet. */
    @Volatile private var startSrcValue: String = "-"

    /** AP3: F43-Gate im Stop-Moment — 1=offen, 0=zu (gehalten), -1=kein Stopp. */
    @Volatile private var gateAtStopValue: Int = -1

    /** AP3: explizite Stopp-Markierung ("autostop" aus dem F43-Zweig, sonst "-"). */
    @Volatile private var explicitStopCauseValue: String = "-"

    /** AP3: längste Laut-Lücke der Session in ms (Block-RMS). */
    @Volatile private var maxPauseMsValue: Long = 0L

    /** AP3: Audio-Position (Session-Uhr, ms) des ersten Besitzverlusts; -1 = nie. */
    @Volatile private var ownerLostAtAudioMsValue: Long = -1L

    /** AP3: unerwartete Selection-Callbacks (Tracker liefert lost=true). */
    @Volatile private var selUnexpectedValue: Int = 0

    /** AP3: onStartInput(restarting=true)-Zähler (Zuwachs vom Service). */
    @Volatile private var restartInputsValue: Int = 0

    fun startSrc(): String = startSrcValue
    fun gateAtStop(): Int = gateAtStopValue
    fun maxPauseMs(): Long = maxPauseMsValue
    fun selUnexpected(): Int = selUnexpectedValue
    fun restartInputs(): Int = restartInputsValue

    /** AP3: onStartInput(restarting=true) — Aufrufer: VoiceKeyboardService (1 Zeile). */
    fun noteRestartInput() {
        restartInputsValue += 1
    }

    /**
     * AP3: Besitzverlust-Moment in SEKUNDEN (-1 = nie verloren) — frühester
     * von zwei Detektoren: unerwarteter Selection-Callback (hier, Session-
     * Audio-Uhr) ODER verweigerte Schreibabsicht im Controller (dort eigene
     * Session-Audio-Position; beide Uhren identisch skaliert). Nur Zahl (Regel 9).
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

    /** AP3: vereinheitlichte Stopp-Ursache — reine Funktion [stopCauseOf]. */
    fun stopCause(): String =
        stopCauseOf(explicitStopCauseValue, micRel(), lastAbortReason, startSrcValue)

    // ---- A2b (PLAN_V0133 §A2b): Decode-Fenster-Diagnose nach außen ----

    /**
     * A2b: Callback nach JEDEM Decode-Fenster — erhält das angereicherte,
     * unveränderliche [DecodeWindowDiagnosis]-Objekt (nur Zahlen/Codes, kein
     * UI-/Android-Typ, Regel 9). null = keiner. Aufrufer: VoiceKeyboardService
     * (A4b-Verdrahtung der VK-ASR-Fensterzeile).
     */
    @Volatile var onDecodeWindow: ((DecodeWindowDiagnosis) -> Unit)? = null

    /** A2b: letztes Diagnosefenster (null = noch keins) — 🐞-Abfrage ohne Callback. */
    @Volatile private var lastDecodeWindowValue: DecodeWindowDiagnosis? = null

    fun lastDecodeWindow(): DecodeWindowDiagnosis? = lastDecodeWindowValue

    // ---- A4a (PLAN_V0133 §A4a): A2b-Schreib-Diagnose lesbar durchreichen ----
    // (Vervollständigung des A2b-„lesbar"-Vertrags: Der Collector hält NUR den
    // Coordinator — die Session-Zähler des Controllers sind ohne diese
    // Durchreiche im Export unsichtbar. Nur Zahlen, Regel 9.)

    /** A4a: Schreib-Ergebnisse dieser Session, monoton (🐞: write_same). */
    fun writeSameCount(): Int = controller.writeSameCount

    /** A4a: verweigerte Schreibabsichten (🐞: write_w1). */
    fun writeW1Count(): Int = controller.writeW1Count

    /** A4a: blockierte Rollbacks — einmalig sichtbar (🐞: write_w3d). */
    fun writeW3dCount(): Int = controller.writeW3dCount

    /** A4a: ausgeführte Schreibschritte (🐞: write_delta). */
    fun writeDeltaCount(): Int = controller.writeDeltaCount

    /** A4a: Session-Audio-Sekunden des letzten DELTA; -1 = noch keins
     *  (🐞: last_write_audio_s — NIE '-', sondern -1). */
    fun lastWriteAudioS(): Double = controller.lastWriteAudioS

    init {
        // A2b: Controller-Snapshot anreichern (Session-Forensik des
        // Coordinators) und unveränderlich nach außen reichen. `out`
        // (output_chars) liefert der Service synchron aus seinem
        // DictationOutputCounter (A4b) — der Coordinator besitzt ihn nicht.
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
 * A2b (PLAN_V0133 §A2b): UNVERÄNDERLICHES Decode-Fenster-Diagnoseobjekt des
 * Coordinators — nur Zahlen/Codes, KEIN UI-/Android-Typ (Regel 9). `out`
 * (output_chars) liefert der Service synchron aus seinem DictationOutputCounter
 * (A4b-Verdrahtung) — der Coordinator besitzt diesen Zähler nicht.
 */
data class DecodeWindowDiagnosis(
    /** Session-Audiolänge beim Fensterende (s; 🐞: audio_s). */
    val audioS: Double,
    /** Dauer des Decode-Laufs (ms; 🐞: decode_ms). */
    val decodeMs: Long,
    /** Aktuell geltendes Partial-Intervall (ms; 🐞: pace_ms; 0 = aus). */
    val paceMs: Long,
    /** Wegen Takt übersprungene Partial-Decodes (🐞: skips). */
    val skips: Int,
    /** Länge der Hypothese (🐞: hyp_len). */
    val hypLen: Int,
    /** Eigene Standlänge nach dem Fenster (🐞: current_len). */
    val currentLen: Int,
    /** Committete Präfixlänge nach dem Fenster (🐞: committed_len). */
    val committedLen: Int,
    /** Schreib-Code des Fensters (same|w1|w3d|delta|-; 🐞: write). */
    val write: String,
    /** Besitzverlust-Moment (s; -1 = nie; 🐞: owner_lost_at_s). */
    val ownerLostAtS: Double,
    /** Unerwartete Selection-Callbacks (🐞: sel_unexpected). */
    val selUnexpected: Int,
    /** onStartInput(restarting=true)-Zähler (🐞: restart_inputs). */
    val restartInputs: Int,
    /** Ausgeführte Re-Baselines (🐞: rebases). */
    val rebases: Int,
    /** Abstand seit der letzten Hypothese (ms; -1 = nie; 🐞: hyp_gap_ms). */
    val hypGapMs: Long,
    /** Größte Decode-Dauer der Stichprobe (ms; 🐞: decode_ms_max). */
    val decodeMsMax: Long,
    /** p95 der Decode-Dauer (ms; 🐞: decode_ms_p95). */
    val decodeMsP95: Long,
    /** Länge des dekodierten Fensters (s; 🐞: buffer_s). */
    val bufferS: Double,
)

/**
 * B1 (PLAN_V0130 §6.3 Empfehlung (c) — Zwischensegment-Finalisierung,
 * §5.1-KONFORM: whole_buffer nur ZEITLICH segmentiert): reine Entscheidung
 * „Segmentgrenze jetzt?". Zwei Regeln:
 *  - (a) STILLE-Regel (weiche Naht an Satzenden): [everLoud] und streamende
 *    Stille ([capStallMs] < [SEGMENT_STALL_GATE_MS] — Stille ≠ Stall, F28b1)
 *    und Pause ≥ [SEGMENT_PAUSE_MS] seit dem letzten lauten Block und die
 *    letzte Hypothese endet auf Satzzeichen (. ! ?) — dort ist die Text-Naht
 *    am harmlosesten.
 *  - (b) LÄNGEN-Regel (Zwangssegment): [nowMs] - [segmentStartedAtMs] ≥
 *    [segmentFinalizeMs] — begrenzt das Decode-Fenster auch bei pausenlosem
 *    Monolog. [segmentFinalizeMs] ≤ 0 ⇒ Segmenter AUS (immer false).
 *
 * I3-Kurvenbezug (reports/engine_cost_curve.csv, α=38,5 ms/s, superlinear ab
 * ~60 s): 35 s Deckel ⇒ decode(Segment) ≈ 1,35 s — unter der 1,5-s-W der
 * Technik-Stufe; kürzere Satzpausen (< [SEGMENT_PAUSE_MS]) schneiden NIE.
 * Reine Funktion ohne Android — JVM-testbar (SegmentFinalizeTest).
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
    // (b) Längenregel: hartes Segmentende — gilt auch ohne Stille/Satzzeichen.
    if (nowMs - segmentStartedAtMs >= segmentFinalizeMs) return true
    // (a) Stille-Regel: Satzpause mit Satzzeichen-Ende (weiche Naht).
    return everLoud &&
        capStallMs < DictationCoordinator.SEGMENT_STALL_GATE_MS &&
        (nowMs - lastLoudAtMs) >= DictationCoordinator.SEGMENT_PAUSE_MS &&
        lastHypothesisEndsWithPunct
}

/**
 * AP3 (PLAN_C2_FABLE_R2 §4, Export v2): vereinheitlichte Stopp-Ursache als
 * REINE Funktion über die drei bestehenden Forensik-Quellen — deterministische
 * Priorität, nur Codes (Regel 9):
 *
 * 1. [explicitCause] — Marker des erfassenden Pfads selbst (heute: "autostop"
 *    aus dem F43-Zweig; W1b: autostop bei start_src=hold + gate_at_stop=1).
 * 2. editor_change / michold_cancel — FEINE Abbruch-Details (lastAbortReason).
 * 3. maxDuration / systemStop — Audio-Quellen-Enden (audio_error-Code).
 * 4. audio_error:<code> — sonstige ASR-/Audio-Fehler.
 * 5. holdup / toggle / up / hidden / systemStop / editor_change — das
 *    physische Nutzer-/Lifecycle-Ereignis (micRel; "up" bei Toggle-Start ⇒
 *    "toggle" — der Mikro-Schalter, nicht der Statusleisten-UP).
 * 6. abort:<reason> — weitere Abbruchgründe (start_superseded, no_final:* …).
 * 7. final — reguläres Gesamtfinal ohne anderes Ereignis.
 * 8. idle_noop — kein Stopp/kein Ereignis (Default).
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
