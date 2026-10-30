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
     *  Default chunkMs=1000 ⇒ 16_000 Samples @ 16 kHz = exakt TICK_SAMPLES_1S. */
    private val tickSamples: Int = (AsrThreadConfig.DEFAULT.chunkMs * SAMPLE_RATE_HZ / 1000),
    /** Android-seitige Beobachtung der Audio-Route; null = keine Routen-UI. */
    private val routeObserver: ((on: Boolean) -> Unit)? = null,
    /** Modellverzeichnis; nicht null ⇒ [AsrEngine.load] VOR dem ersten Decode (C-4). */
    private val modelDir: java.io.File? = null,
    /** Completion-Callback nach dem nicht-blockierenden Finale (C-1). */
    private val onStopped: (() -> Unit)? = null,
    /** N-2: Dispatcher, auf dem [onStopped] läuft (View-Mutation ⇒ Main). */
    private val mainDispatcher: kotlinx.coroutines.CoroutineDispatcher? = null,
) {

    companion object {
        const val TAG = "VK-DICT"
        const val TICK_SAMPLES_1S = 16_000
        private const val SAMPLE_RATE_HZ = 16_000

        const val READ_TIMEOUT_MS = 50
        const val ERR_AUDIO = "audioSource"
        const val ERR_OOM = "oom"
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
    private val controller = DictationController(engine, sink, tracker = tracker)
    private var source: AudioSource? = null
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
        abort()
    }

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
        // S12b: Diagnose-Z�hler je Aufnahme zuruecksetzen
        firstHypothesisLatencyMs = 0
        processingMs = 0
        audioMs = 0
        levelPeak.reset() // Pegel je Aufnahme neu beginnen (Diagnose)
        startedAtMs = System.currentTimeMillis()
        // C-2/C-4: Engine VOR dem ersten Decode laden (genau einmal); ein
        // Fehlschlag ist sichtbar (lastError) und beendet den Start kontrolliert.
        if (modelDir != null && !engineLoaded) {
            try {
                engine.load(modelDir)
                engineLoaded = true
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
        consumer = scope.launch { consume(ch) }
        captureThread = Thread({ captureLoop(src, ch) }, "vk-dict-capture").apply {
            isDaemon = true
            start()
        }
        return controller.start()
        } finally {
            starting = false
            lastStartMs = (System.nanoTime() - t0) / 1_000_000
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
                    }
                    n < 0 -> { // Fehler: Fehlerkanal hat bereits abort() ausgelöst
                        return
                    }
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
            if (lastError == null) lastError = "consumer:" + e.javaClass.simpleName
            active.set(false)
            channel?.close()
            controller.abort()
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
            controller.abort()
            routeObserver?.invoke(false)
        }
    }

    /**
     * Beendet kontrolliert: Capture stoppen, Queue und Reste drainen (in
     * Reihenfolge), dann finales Decode über den Gesamtpuffer — unabhängig
     * von der Mindestlänge (Guard gilt nur für Partials, §4.4).
     */
    fun stop() {
        if (!active.compareAndSet(true, false)) return // ohne Start: No-Op
        val src = source ?: return
        val ch = channel ?: return
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
                lastStopOutcome = controller.stop() // F13b: Ausgang BEVOR onStopped läuft
            } catch (e: OutOfMemoryError) {
                // F3-R: OOM im Final-Decode — dito flush()-Pfad
                android.util.Log.w(TAG, "oom during final decode")
                lastError = "$ERR_OOM:${e.javaClass.simpleName}"
                controller.abort()
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
    val audioSeconds: Double get() = controller.audioSeconds

    /** F13b: verworfene Audio-Blocks der aktuellen Session (🐞-Diagnose F13b2). */
    val droppedBlocks: Int get() = controller.droppedBlocks

    /** Abbruch ohne Finale: geordnet nach IDLE, bereits committeter Text bleibt. */
    fun abort() {
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
        // C-1: Aufräumen im Worker-Scope, Main-Thread kehrt sofort zurück.
        scope.launch {
            consumerJob?.join()
            runCatching { src?.stopAndDrain(); src?.release() }
            thread?.let { t -> t.join(200) }
        }
    }

    /**
     * C-4: Engine-Ressourcen freigeben (≈863 MB native Sessions, ASR_CONTRACT §5).
     * Aufruf: IME-onDestroy / Editorwechsel-Abort. Kein zweites load() danach.
     */
    fun shutdown() {
        // N-3: idempotent — Engine nur EINMAL schließen; laufende Final-Worker
        // werden durch abort() abgebrochen (kein transcribe nach close mehr:
        // controller.abort() setzt IDLE, bevor close() läuft).
        if (!closed.compareAndSet(false, true)) return
        abort()
        engineLoaded = false
        runCatching { engine.close() }
    }

    /** Editorwechsel: Generation hochzählen, Besitz verwerfen (kein Schreiben danach). */
    fun onEditorChanged() {
        controller.onEditorChanged()
    }

    /** Selektions-Signal vom UI-Thread. */
    fun onUpdateSelection(oldSelStart: Int, oldSelEnd: Int, newSelStart: Int, newSelEnd: Int) {
        controller.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd)
    }

    /** Route-Meldung des AudioRouter (UI-Anzeige; Aufrufer: M4c-2-Verdrahtung). */
    fun onRouteChanged(routeLabel: String) {
        route = routeLabel
    }
}
