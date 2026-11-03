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
 * F13b/C1: Ergebnis eines stop()-Aufrufs — der Aufrufer sieht ausdrücklich,
 * OB ein Finale gelaufen ist (klein, JVM-testbar; kein boolean-Rätsel).
 */
sealed class StopOutcome {
    /** stop() ohne laufende Aufnahme und ohne Fehler: No-Op, nichts zu melden. */
    object IdleNoOp : StopOutcome()

    /**
     * Finale lief (whole-buffer decode); finalError nur bei Finale-Fehler.
     * F13d/P2-1: SNAPSHOT der Sitzung zum Stop-Zeitpunkt — der Aufrufer bewertet
     * NUR dieses Objekt, nie den Live-Controller-Zustand, den der nächste
     * start() nullt („schnell loslassen, sofort neu drücken“).
     */
    data class FinalRan(
        val finalError: String? = null,
        val failedWith: Boolean = false,
        val droppedWith: Int = 0,
    ) : StopOutcome()

    /** KEIN Finale: die Session war bereits still gescheitert (fail() während
     *  RECORDING) — [errorCode] nennt den bewahrten Fehlercode; [failedWith]
     *  true (sonst wäre es IdleNoOp); [droppedWith] = verworfene Blocks. */
    data class NoFinal(
        val errorCode: String?,
        val failedWith: Boolean = true,
        val droppedWith: Int = 0,
    ) : StopOutcome()
}

/**
 * A2b (PLAN_V0133 §A2b): unveränderlicher Diagnose-Snapshot EINES Decode-
 * Fensters (Partial, Mini-Finale, Session-Finale) — nur Zahlen/Codes
 * (Regel 9), kein UI-/Android-Typ. Der Coordinator reichert ihn um seine
 * Session-Forensik an (DecodeWindowDiagnosis) und reicht ihn nach außen.
 */
data class DecodeWindowStats(
    /** Session-Audiolänge beim Fensterende (s). */
    val audioS: Double,
    /** Dauer des Decode-Laufs (ms). */
    val decodeMs: Long,
    /** Aktuell geltendes Partial-Intervall (ms); 0 = Pacing aus. */
    val paceMs: Long,
    /** Wegen Takt übersprungene Partial-Decodes dieser Session. */
    val skips: Int,
    /** Länge der Hypothese (Zeichen). */
    val hypLen: Int,
    /** Länge des eigenen Stands nach dem Fenster (Zeichen). */
    val currentLen: Int,
    /** Committete Präfixlänge nach dem Fenster. */
    val committedLen: Int,
    /** Schreib-Ergebnis des Fensters (same|w1|w3d|delta|-). */
    val write: String,
    /** Abstand seit der letzten Hypothese (ms); -1 = nie. */
    val hypGapMs: Long,
    /** Größte Decode-Dauer der Stichprobe (ms). */
    val decodeMsMax: Long,
    /** p95 der Decode-Dauer (ms). */
    val decodeMsP95: Long,
    /** Länge des dekodierten Fensters (s). */
    val bufferS: Double,
)

/**
 * Push-to-Talk-Zustandsmaschine (KONZEPT.md §2.1) und Diktat-Pipeline-Orchestrierung:
 *
 * AudioSource-Blöcke ([feedAudio]) → ASR ([AsrEngine], injiziert) →
 * [CommitPolicy]/LocalAgreement → [DeltaTyper] → [EditorSink].
 *
 * Regeln (alle über injizierte, frameworkfreie Interfaces — JVM-testbar):
 * - **Idempotenz:** doppeltes [start] während RECORDING ist ein No-Op (false),
 *   [stop] ohne laufende Aufnahme ist ein No-Op (KONZEPT §2.1).
 * - **Session-Isolation:** jede Aufnahme erhöht [sessionId]; Hypothesen einer
 *   alten Session (oder nach Zustandswechsel) werden verworfen — verspätete
 *   ASR-Ergebnisse schreiben niemals in eine neue Sitzung ([applyPartial]).
 * - **min_audio_s = 2.0** (ASR_CONTRACT.md §4.4) gilt **nur für Partials**
 *   (Halluzinationsschutz, KONZEPT §3.4); bei [stop] wird immer final über den
 *   Gesamtpuffer dekodiert.
 * - **Besitz:** jede Schreibabsicht wird über [OwnershipTracker.isOwned]
 *   geprüft; nach Fremdeingabe wird nichts mehr geschrieben (DESIGN §6).
 * - **Fehlerpfad:** Engine-/Sink-Fehler führen über [fail] geordnet nach
 *   IDLE; bereits geschriebener Text bleibt stehen, Fehlercode (nie Textinhalt)
 *   wird in [lastErrorCode] sichtbar.
 *
 * Aufrufer serialisieren die Zugriffe (ein Orchestrierungs-Thread; auf Android:
 * Service-Coroutine). [onUpdateSelection]-Signale vom UI-Thread sind über den
 * intern synchronisierten Tracker abgesichert.
 */
class DictationController(
    private val engine: AsrEngine,
    private val sink: EditorSink,
    private val policy: CommitPolicy = CommitPolicy(),
    private val tracker: OwnershipTracker = OwnershipTracker(),
    private val minAudioSecondsForPartials: Double = MIN_AUDIO_SECONDS_PARTIALS,
    private val sampleRateHz: Int = PcmWindow.SAMPLE_RATE_16K,
    /**
     * F13b2: monotone Uhr für Hypothesen-Abstände (🐞-Feld hyp_gap_ms).
     * Default nanoTime (JVM-testbar, inkl. allem Sleep); Android kann statt
     * dessen SystemClock.elapsedRealtime() in NANOSECONDS injizieren —
     * NIE uptimeMillis (pausiert im Deep-Sleep, Review F13c P2).
     */
    private val clock: () -> Long = { System.nanoTime() },
    /**
     * F21: cost-aware Partial-Takt ([DecodePace], C2-Milderung, Nutzerfreigabe
     * 2026-10-30). Default false = Verhalten wie bisher (jeder Block dekodiert);
     * Produktion setzt true (DictationCoordinator). Das Finale ist NIE betroffen.
     */
    private val decodePacing: Boolean = false,
) {

    var state: DictationState = DictationState.IDLE
        private set

    /** Monoton steigende Kennung der aktuellen/letzten Aufnahme-Sitzung. */
    var sessionId: Long = 0L
        private set

    /** Fehlercode des letzten Fehlers (nie Textinhalt), null im Normalfall. */
    var lastErrorCode: String? = null
        private set

    /**
     * F13b/C1: True, sobald die AKTIELLE Session über fail() gescheitert ist —
     * bewahrt, bis ein NEUER START ihn zurücksetzt (dann wäre der Fehler
     * unsichtbar geworden, Analyse Zeile 76). lastErrorCode bleibt unverändert.
     */
    var failedThisSession: Boolean = false
        private set

    /** F13b: Anzahl der Audio-Blocks, die in feedAudio verworfen wurden (🐞-Diagnose). */
    var droppedBlocks: Int = 0
        private set

    /** F13b2: Anzahl erzeugter Hypothesen der aktuellen Sitzung (🐞: hyp_count). */
    var hypothesesThisSession: Int = 0
        private set

    /** Zeitstempel (clock) der letzten Hypothese; 0 = noch keine. */
    private var lastHypAt: Long = 0L

    // ---- F17b/C2: Decode-Zeit-Diagnose (nur Zahlen, Regel 9) ----
    // Ringpuffer fester Größe: letzte DECODE_SAMPLE_CAP Messwerte, wächst nie.
    private val decodeTimesMs = LongArray(DECODE_SAMPLE_CAP)
    // @Volatile: Main liest beim 🐞-Tippen, Worker schreibt — der volatile-Count-Write
    // erzählt das Happens-before für die zuvor geschriebenen Array-Slots.
    @Volatile private var decodeSamples = 0 // gespeicherte Messwerte (≤ CAP)
    // decodeSeq: Main setzt ihn beim Start zurück (resetDecodeStats aus start(),
    // Coordinator Z. 206), der Worker erhöht ihn — daher @Volatile (F20-K).
    @Volatile private var decodeSeq = 0L // Schreibzähler für den Ringindex
    /** Pufferlänge (audioSeconds) beim letzten Dekodierlauf; 0 = nie dekodiert. */
    /** Pufferlänge (audioSeconds) beim letzten Dekodierlauf; 0 = nie dekodiert. */
    @Volatile private var lastDecodeBufferSeconds = 0.0

    /** F26b (C2-Export): Bufferstand (s) zum Zeitpunkt der LETZTEN akzeptierten
     * Hypothese — Beweis, dass der Puffer waehrend des Abbruchs wuchs. Je Session. */
    @Volatile var lastHypothesisBufferSeconds: Float = 0f

    /** AP3: Anzahl verweigeter Schreibabsichten (Besitz nicht bewiesen). */
    @Volatile var ownershipDeniedCount: Int = 0

    /** AP3: erster Besitzverlust-Moment (Session-Audio-Sekunden); -1.0 = nie. */
    @Volatile var ownershipDeniedAtS: Double = -1.0
        private set
    /** F21: Dauer des letzten Decode-Laufs in ms (Eingang des Takts, nie Inhalt).
     *  A2b: öffentlich lesbar (🐞: last_decode_ms, Zahlen/Codes — Regel 9). */
    @Volatile var lastDecodeMs: Long = 0L
        private set
    /** F21: wegen Takt übersprungene Partial-Decodes dieser Session (🐞: decode_skips). */
    @Volatile private var decodeSkipsCount = 0

    // ---- A2b (PLAN_V0133 §A2b): Schreib-Diagnose (nur Zahlen/Codes, Regel 9) ----

    /** A2b: Schreib-Ergebnisse dieser Session, monoton (🐞: write_same). */
    @Volatile var writeSameCount: Int = 0
        private set

    /** A2b: verweigerte Schreibabsichten (🐞: write_w1). */
    @Volatile var writeW1Count: Int = 0
        private set

    /** A2b: blockierte Rollbacks — einmalig sichtbar (🐞: write_w3d). */
    @Volatile var writeW3dCount: Int = 0
        private set

    /** A2b: ausgeführte Schreibschritte (🐞: write_delta). */
    @Volatile var writeDeltaCount: Int = 0
        private set

    /** A2b: Session-Audio-Sekunden des letzten DELTA; -1 = noch keins
     *  (🐞: last_write_audio_s). */
    @Volatile var lastWriteAudioS: Double = -1.0
        private set

    /** A2b: letzter Schreib-Code (same|w1|w3d|delta; "-" = noch keiner). */
    @Volatile var writeReason: String = "-"
        private set

    /** A2b: Länge der letzten Hypothese (🐞: hyp_len). */
    @Volatile var hypLen: Int = 0
        private set

    /** A2b: eigene Standlänge nach dem letzten Fenster (🐞: current_len). */
    @Volatile var currentLen: Int = 0
        private set

    /** A2b: committete Präfixlänge nach dem letzten Fenster (🐞: committed_len). */
    @Volatile var committedLen: Int = 0
        private set

    /**
     * A2b: Callback nach JEDEM Decode-Fenster (Snapshot [DecodeWindowStats],
     * unveränderlich, nur Zahlen/Codes). null = keiner. Aufrufer: Coordinator
     * (reichert an und reicht nach außen; A4b verdrahtet den Service).
     */
    @Volatile var onDecodeWindow: ((DecodeWindowStats) -> Unit)? = null

    /** Größter bisher gemessener Decode-Lauf in ms; 0, wenn nie dekodiert.
     *  Count EINMAL in ein Lokal lesen (F20): sonst kann die Schleifengrenze
     *  zwischen Prüfung und Index zwei verschiedene Werte sehen → IOOBE. */
    fun decodeMsMax(): Long {
        val n = decodeSamples // EINMAL lesen (F20)
        var m = 0L
        for (i in 0 until n) m = maxOf(m, decodeTimesMs[i])
        return m
    }

    /**
     * p95 der Decode-Läufe in ms — deterministisch: sortierte Stichprobe,
     * Index ceil(0.95·n)−1. leer → 0; ein Lauf → max == p95. Die Stichprobe
     * umfasst die letzten ≤ 64 Läufe (Ringpuffer), nicht die ganze Sitzung.
     */
    fun decodeMsP95(): Long {
        val n = decodeSamples // EINMAL lesen (F20, IOOBE-Hebel)
        if (n == 0) return 0L
        val sorted = decodeTimesMs.copyOf(n)
        sorted.sort()
        val idx = ((sorted.size * 95 + 99) / 100 - 1).coerceIn(0, sorted.size - 1)
        return sorted[idx]
    }

    /** Aufgenommenes Audio (s) beim letzten Dekodierlauf; 0,0 wenn nie dekodiert. */
    fun bufferSeconds(): Double = lastDecodeBufferSeconds

    /** F21: aktuell geltendes Partial-Intervall in ms; 0 = Pacing aus (Default). */
    fun decodePaceMs(): Long =
        if (!decodePacing) 0L
        else DecodePace.intervalMs(
            lastDecodeMs, (audioSeconds * 1000).toLong(), PACE_BASE_MS, PACE_MAX_MS,
        )

    /** F21: wegen Takt übersprungene Partial-Decodes dieser Session; 0 wenn Pacing aus. */
    fun decodeSkips(): Int = decodeSkipsCount

    /** F21: darf der nächste Partial jetzt laufen? (Finale wird hier nie geprüft.) */
    private fun paceAllowsDecode(): Boolean {
        val secs = audioSeconds // EINMAL lesen (abgeleiteter Getter)
        val intervalMs = DecodePace.intervalMs(
            lastDecodeMs, (secs * 1000).toLong(), PACE_BASE_MS, PACE_MAX_MS,
        )
        return (secs - lastDecodeBufferSeconds) * 1000.0 >= intervalMs
    }

    /** Ein Decode-Lauf: verstrichene ms (Ring) + Pufferlänge zum Decode-Zeitpunkt. */
    private fun noteDecode(startNs: Long) {
        val ms = ((clock() - startNs) / 1_000_000).coerceAtLeast(0L) // nie negativ
        decodeTimesMs[(decodeSeq % DECODE_SAMPLE_CAP).toInt()] = ms
        decodeSeq += 1
        if (decodeSamples < DECODE_SAMPLE_CAP) decodeSamples += 1
        lastDecodeMs = ms // F21: Eingang des cost-aware Takts
        lastDecodeBufferSeconds = audioSeconds
    }

    /** F13b2: Abstand seit der letzten Hypothese in ms; -1 wenn nie eine kam. */
    fun currentHypGapMs(): Long =
        if (lastHypAt == 0L) -1L
        else ((clock() - lastHypAt) / 1_000_000).coerceAtLeast(0) // F13d/P2-2: nie negativ

    private val typer = DeltaTyper(sink)
    /** F17b/C2: pro Session gezählt (wie droppedBlocks/hyp_count) — in start() nullt. */
    private fun resetDecodeStats() {
        decodeSamples = 0
        decodeSeq = 0L
        lastDecodeBufferSeconds = 0.0
        lastDecodeMs = 0L // F21: Takt-Eingang je Session zurück
        decodeSkipsCount = 0
    }
    private var pcm = ShortArray(0)
    private var totalSamples = 0
    private var runId = 0L

    // ---- B1 (Segmenter-Kern): Text-Naht über Segmentgrenzen ----

    /** B1: Akkumulierter PERMANENT committeter Text aller abgeschlossenen
     *  Segmente (Präfix für Hypothesen des AKTIVEN Segments — der Segment-Decode
     *  liefert nur Segment-Text; writeHypothesis rekonstruiert daraus den
     *  Session-Transkript). Leer = keine Segmentgrenze bisher (Bestandsverhalten
     *  identisch: whole_buffer von 0). Consumer-Thread schreibt; @Volatile für
     *  den Export-Leser (B1-Fix P2a: Session-Länge über [segmentBaseSamples]). */
    @Volatile private var segmentPrefix = ""

    /** B1: Samples aller abgeschlossenen Segmente (Agreement/Policy sehen die
     *  SESSION-Audiolänge, nicht die Segment-Länge — Semantik des vollTexts).
     *  @Volatile: Consumer schreibt, Main-Thread liest die Export-Länge
     *  ([sessionAudioSeconds], B1-Fix P2a) — ohne Sichtbarkeitsgarantie las
     *  der Export stattdessen den segment-lokalen Puffer (Befund Test 6). */
    @Volatile private var segmentBaseSamples = 0L

    /** B1: Endet die letzte Hypothese auf Satzzeichen (. ! ?) — Eingang der
     *  weichen Segmentregel (a); @Volatile: Capture-Thread liest (Regel 9:
     *  nur Boolean, nie Text). */
    @Volatile private var lastHypPunct = false

    val audioSeconds: Double
        get() = if (sampleRateHz <= 0) 0.0 else totalSamples.toDouble() / sampleRateHz

    /**
     * B1-Fix (Review P2a): SESSION-Audiolänge = abgeschlossene Segmente plus
     * aktuelles Pufferstück — der 🐞-Export (audio_s/buffer_s) zeigt nach
     * Segmenten den NACHGEFÜHRTEN Session-Wert statt des segment-lokalen
     * Puffers (der nach jedem Mini-Finale auf 0 zurückfällt). Ohne Segmenter
     * identisch mit [audioSeconds] (Bestandsverhalten).
     */
    val sessionAudioSeconds: Double
        get() = if (sampleRateHz <= 0) 0.0
            else (segmentBaseSamples + totalSamples).toDouble() / sampleRateHz

    /**
     * Beginn einer Aufnahme-Sitzung. Idempotent: während IDLE→true und Zustand
     * RECORDING; bereits laufend → false (kein Neustart, KONZEPT §2.1).
     */
    fun start(): Boolean {
        if (state != DictationState.IDLE) return false
        state = DictationState.STARTING
        sessionId += 1
        runId = 0L
        pcm = ShortArray(0)
        totalSamples = 0
        lastErrorCode = null
        failedThisSession = false // F13b: Fehler gilt je Session, nie nachwirkend
        droppedBlocks = 0
        hypothesesThisSession = 0 // F13b2: Zähler je Sitzung
        lastHypAt = 0L
        lastHypothesisBufferSeconds = 0f // F26b: je Sitzung neu
        ownershipDeniedCount = 0 // AP3: Besitz-Forensik je Sitzung
        ownershipDeniedAtS = -1.0
        // A2b: Schreib-Diagnose je Sitzung genullt (monoton danach)
        writeSameCount = 0
        writeW1Count = 0
        writeW3dCount = 0
        writeDeltaCount = 0
        lastWriteAudioS = -1.0 // -1 vor dem ersten DELTA
        writeReason = "-"
        hypLen = 0
        currentLen = 0
        committedLen = 0
        resetDecodeStats() // F17b: Decode-Diagnose je Sitzung (Muster wie oben)
        policy.reset()
        typer.reset()
        tracker.reset()
        sink.resetSession() // A5 (v0.1.34): ownSpan-Leiche der Vorgänger-Sitzung verwerfen
        // B1: Segment-Naht je Sitzung neu — ganze Session = EIN whole_buffer,
        // wenn der Segmenter aus bleibt (segmentFinalizeMs=0, Bestand).
        segmentPrefix = ""
        segmentBaseSamples = 0L
        lastHypPunct = false
        state = DictationState.RECORDING
        return true
    }

    /**
     * Neuer Audio-Block aus der Capture-Queue (KONZEPT §2.3: entkoppelt).
     * Ignoriert außerhalb von RECORDING. Erreicht der Puffer die Mindestlänge
     * für Partials (2.0 s), wird er transkribiert und das Ergebnis über
     * CommitPolicy + DeltaTyper in den Editor geschrieben.
     */
    suspend fun feedAudio(block: PcmBlock) {
        if (state != DictationState.RECORDING) {
            // F13b/C1: nach einem stillen fail() wurde JEDE Block hier stumm
            // verworfen — jetzt gezählt (F13b2-Diagnose: dropped_blocks).
            droppedBlocks += 1
            return
        }
        if (block.samples.isEmpty()) return
        append(block.samples)
        // ASR_CONTRACT §4.4: min_audio_s ist ein Guard NUR für Partials —
        // kurze Audiostände werden nicht (halluzinationsgefährdet) dekodiert.
        if (audioSeconds >= minAudioSecondsForPartials) {
            // F21: cost-aware Takt NUR für Partials — das Finale (stop) läuft immer.
            if (decodePacing && !paceAllowsDecode()) {
                decodeSkipsCount += 1 // kein Fehler, kein fail(): nur ein übersprungener Lauf
                return
            }
            try {
                val decodeStartNs = clock() // F17b/C2: Decode-Zeit mit injizierter Uhr
                val hypothesis = engine.transcribe(window(), sessionId, ++runId)
                noteDecode(decodeStartNs) // F17b: nur Diagnose-Zahlen, kein Verhalten
                noteHypothesis() // F13b2: erzeugte Hypothese zählen + Zeitstempel
                applyPartial(hypothesis)
            } catch (e: OutOfMemoryError) {
                // F3: nativer ORT-Speicherfehler während der Inferenz — Session
                // kontrolliert beenden (abort-Semantik), Fehler NACH OBEN melden
                // (kein schluckendes catch), damit die IM dictation_error_out_of_memory
                // zeigen kann. NUR OOM; andere Error bleiben bewusst ungefangen.
                abort()
                throw e
            } catch (e: IllegalStateException) {
                if (isEditorSyncTimeout(e)) {
                    // F1-Drift-Regel (REVIEW_STAGE6_FIXES): Edit-Timeout im Sink ⇒
                    // der Main-Thread ist blockiert/überlastet — weitere Revisionen
                    // würden auf demselben Driftvektor laufen (veraltete Editor-
                    // Zustände, Geisterschreib-Risiko). Session beenden, Fehler
                    // sichtbar nach oben (R-2). F13d/P1: fail() statt abort() —
                    // failedThisSession bewahrt den Grund bis zum nächsten Start
                    // (sonst meldet stop() ein stummes IdleNoOp).
                    fail(EDITOR_SYNC_TIMEOUT)
                    throw e
                }
                fail(ASR_PARTIAL_ERROR) // andere IllegalStateException: normaler Fehlerpfad
            } catch (e: Exception) {
                fail(ASR_PARTIAL_ERROR)
            }
        }
    }

    /**
     * Wendet eine partielle Hypothese an (auch vom externen Scheduler aufrufbar).
     * Verwirft Ergebnisse, die nicht zur AKTUELLEN Session und zum Zustand
     * RECORDING passen — so können verspätete Ergebnisse einer alten Session
     * keinen Text mehr einfügen.
     *
     * @return true, wenn die Hypothese der aktuellen Session zugeordnet wurde.
     */
    fun applyPartial(hypothesis: Hypothesis): Boolean {
        if (state != DictationState.RECORDING) return false
        if (hypothesis.sessionId != sessionId) return false
        return writeHypothesis(hypothesis)
    }

    /**
     * Taste losgelassen: finales Decode des GESAMTEN Puffers (whole-buffer,
     * KONZEPT §5.1) und Übergang nach IDLE. Idempotent: ohne laufende Aufnahme
     * ein No-Op. Auch kürzeste Aufnahmen werden final dekodiert (min_audio-Guard
     * gilt nicht für das Finale).
     *
     * F13b/C1: Rückgabe [StopOutcome] — der Aufrufer sieht, OB ein Finale
     * gelaufen ist; eine still gescheiterte Session (fail() während RECORDING)
     * liefert NoFinal MIT Fehlercode statt eines normalen Abschlusses.
     */
    suspend fun stop(): StopOutcome {
        if (state != DictationState.RECORDING) {
            // Nie-Aufnahme (IDLE): No-Op ohne Fake-Fehler — ABER: die Session war
            // evtl. still gescheitert (state von fail() auf IDLE): NoFinal melden.
            // F13d/P2-1: DROPPED-Wert in den Snapshot — Live-Lesen wäre rennbar.
            return if (failedThisSession) StopOutcome.NoFinal(lastErrorCode, failedWith = true, droppedWith = droppedBlocks)
            else StopOutcome.IdleNoOp
        }
        // F13d/P2-1: Snapshot zum STOP-Zeitpunkt — ein sofortiger neuer start()
        // kann die Bewertung dieser Sitzung nicht mehr verfälschen.
        val failedAtStop = failedThisSession
        val droppedAtStop = droppedBlocks
        state = DictationState.FINALIZING
        var finalError: String? = null
        if (totalSamples > 0) {
            try {
                val decodeStartNs = clock() // F17b/C2: Decode-Zeit mit injizierter Uhr
                val hypothesis = engine.transcribe(window(), sessionId, ++runId)
                noteDecode(decodeStartNs) // F17b: nur Diagnose-Zahlen, kein Verhalten
                noteHypothesis() // F13b2: auch das Finale ist eine erzeugte Hypothese
                // Auch Finals alter Sessions verwerfen (veralteter Lauf)
                if (hypothesis.sessionId == sessionId) {
                    // A2b: Finale über reviseFinal — besitzgesicherte
                    // GESAMTREVISION der eigenen Spanne (kein W3d-Block).
                    writeHypothesis(hypothesis, final = true)
                }
            } catch (e: OutOfMemoryError) {
                // F3: OOM auch im Final-Decode — abort + nach oben (s. feedAudio)
                abort()
                throw e
            } catch (e: IllegalStateException) {
                if (isEditorSyncTimeout(e)) {
                    // F1-Drift-Regel: Edit-Timeout ⇒ keine weiteren Schreibversuche.
                    // F13d/P1: fail() statt abort() — Grund bewahrt, nie stumm.
                    fail(EDITOR_SYNC_TIMEOUT)
                    throw e
                }
                fail(ASR_FINAL_ERROR)
                finalError = ASR_FINAL_ERROR
            } catch (e: Exception) {
                fail(ASR_FINAL_ERROR)
                finalError = ASR_FINAL_ERROR
            }
        }
        if (state == DictationState.FINALIZING) state = DictationState.IDLE
        return StopOutcome.FinalRan(finalError, failedWith = failedAtStop, droppedWith = droppedAtStop)
    }

    /**
     * Abbruch ohne finales Decode: geordnet nach IDLE (DESIGN §2.4 CANCELLING);
     * bereits committeter Text bleibt stehen.
     */
    fun abort() {
        if (state == DictationState.IDLE) return
        lastErrorCode = "aborted"
        state = DictationState.IDLE
    }

    /** Selektions-Signal vom UI-Thread (InputMethodService.onUpdateSelection). */
    fun onUpdateSelection(oldSelStart: Int, oldSelEnd: Int, newSelStart: Int, newSelEnd: Int) {
        tracker.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd)
    }

    /**
     * Editor-/App-Wechsel (A-1): bricht eine laufende Aufnahme kontrolliert ab —
     * KEIN Final-Commit in den neuen Editor, kein weiteres Partialschreiben.
     * Besitz wird verworfen (neue Generation), Zustand → IDLE; bereits
     * committeter Text bleibt im ALTEN Editor stehen.
     */
    fun onEditorChanged() {
        tracker.onEditorChanged()
        if (state == DictationState.RECORDING || state == DictationState.FINALIZING) {
            abort()
        }
    }

    /** Editor-Kontext für den erwarteten eigenen Text (Besitzprüfung). */
    private fun contextForOwnText(): CharSequence? =
        if (typer.currentText.isEmpty()) null else sink.textBeforeCursor(typer.currentText.length)

    /**
     * F1-Drift-Regel: erkennt den Edit-Timeout des InputConnectionSink an dessen
     * fester Meldung — nur DIESER Fehler ist ein Drift-Abbruch, jede andere
     * IllegalStateException bleibt im normalen fail()-Pfad.
     */
    private fun isEditorSyncTimeout(e: IllegalStateException): Boolean =
        e.message?.startsWith(EDITOR_SYNC_TIMEOUT_PREFIX) == true

    /** F13b2: Zähler + Zeitstempel einer erzeugten Hypothese (nur Metadaten). */
    private fun noteHypothesis() {
        hypothesesThisSession += 1
        lastHypAt = clock()
        // F26b: Bufferstand der LETZTEN erzeugten Hypothese (letzter Decode-Wert).
        lastHypothesisBufferSeconds = lastDecodeBufferSeconds.toFloat()
    }

    /** Geordneter Fehlerpfad: nach IDLE, Fehlercode sichtbar, Text bleibt stehen. */
    private fun fail(errorCode: String) {
        lastErrorCode = errorCode
        failedThisSession = true // F13b: bewahren, bis ein neuer Start folgt
        state = DictationState.IDLE
        policy.reset()
    }

    /**
     * Schreibt eine (der aktuellen Session zugeordnete) Hypothese: Commit-Fortschritt
     * über die Policy, danach minimales Delta über den Typer. Ohne bewiesenen
     * Besitz wird nichts geschrieben (DESIGN §6).
     * B1: Der Segment-Decode liefert nur das AKTIVE Segment — [withSegmentPrefix]
     *  rekonstruiert daraus den vollen Session-Text (Präfix der abgeschlossenen
     *  Segmente + Segment-Text, Naht = ein Leerzeichen); Policy/Agreement
     *  erhalten die SESSION-Audiolänge ([segmentBaseSamples] + Puffer). Ohne
     *  Segmentgrenzen ist beides identisch mit dem Bestandsverhalten.
     *
     * A2b (PLAN_V0133 §A2b): Produktionspfad ist [DeltaTyper.reviseOutcome]
     * (Grundrückgabe {SAME, W1, W3d, DELTA}); [final]=true (Session-Finale)
     * nutzt [DeltaTyper.reviseFinal] — besitzgesicherte Gesamtrevision.
     * W1-Re-Baseline in exakt den E1c-Koordinaten (Länge max(currentText,
     * sink.ownText()), actual=sink.textBeforeCursor(len), fresherExpected=
     * sink.ownText()); NUR nach positivem Suffixbeweis darf dieselbe
     * Hypothese EINMAL erneut versucht werden. Zähler/Diagnose je Fenster
     * über [noteWrite] (Regel 9: Zahlen/Codes).
     */
    private fun writeHypothesis(hypothesis: Hypothesis, final: Boolean = false): Boolean {
        // B1: Satzzeichen-Ende der letzten Hypothese (Eingang der weichen
        //  Segmentregel a) — nur ein Boolean, nie der Text (Regel 9).
        val lastChar = hypothesis.text.trimEnd().lastOrNull()
        lastHypPunct = lastChar == '.' || lastChar == '!' || lastChar == '?'
        val fullText = withSegmentPrefix(hypothesis.text)
        if (!tracker.isOwned(typer.currentText, contextForOwnText())) {
            // AP3 (PLAN_C2_FABLE_R2 §4, H-E2): verweigerte Schreibabsicht — der
            // zweite Besitzverlust-Detektor neben dem Selection-Flip im
            // Coordinator. Erster Moment in Session-Audio-Sekunden (-1 = nie),
            // nur Zahlen (Regel 9). Zähler für Persistenz (stiller Schreibstopp).
            // AP3-P2 (Review): VOR der ersten AUSGABE ist isOwned=false NORMAL
            // (Baseline noch nicht etabliert; expectedText leer ⇒ nur lost kann
            // verweigern) — der Detektor zählt erst, wenn die Session schon
            // Output im Editor hat: hadOutput-Guard = typer.currentText
            // NICHT leer (erster Partieller genügt — NICHT committedLength:
            // ein Agreement-Commit braucht 3 stabile Hypothesen, und genau der
            // „Partielle da, dann stiller Schreibstopp OHNE je einen Agreement-
            // Commit"-Fall (H-E2) bliebe mit dem strengeren Guard unsichtbar;
            // Review-Intention ist der partielle Schreibstopp). currentText
            // resettet je Session (typer.reset() in start()). Detektor 1
            // (Selection-Flip im Coordinator) bleibt unangetastet.
            if (typer.currentText.isNotEmpty()) {
                // A2b (E1c): EIN Re-Baseline-Versuch mit dem FRISCHEN Sink-Stand
                // (ownText ist synchron zur commit-Runde, die Tracker-Erwartung
                // hinkt worker-seitig nach). Tracker löscht lost NUR bei
                // positivem Suffixbeweis; danach dieselbe Hypothese EINMAL
                // erneut — sonst W1 ohne Sink-Zugriff (lost bleibt).
                val len = maxOf(typer.currentText.length, sink.ownText().length)
                val rebased = tracker.rebaseline(sink.textBeforeCursor(len), sink.ownText())
                if (!rebased || !tracker.isOwned(typer.currentText, contextForOwnText())) {
                    // P2 (REVIEW_A2_A4): NUR die ECHTE Verweigerung zählt — eine
                    // GELUNGENE Re-Baseline ist kein Besitzverlust (Zähler und
                    // Stempel standen vorher VOR dem Versuch).
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
        val outcome = if (final) typer.reviseFinal(fullText) else typer.reviseOutcome(fullText)
        noteWrite(outcome.kind, fullText)
        // E8.2 C6: nach einem Rollback (committedLength GESCHRUMPFT, kein
        // Divergenz-Verweigerung) beginnt die Agreement-Zählung NEU — kein
        // sofortiger Re-Commit desselben (fehlerhaften) Präfixes. Gilt für
        // W3c wie für die A2a/A2b-Gesamtrevision (W3d-Auflösung, Finale).
        if (outcome.kind == ReviseKind.DELTA && typer.committedLength < committedBefore) {
            policy.reset()
        }
        if (outcome.kind == ReviseKind.DELTA) {
            tracker.onOwnEdit(typer.currentText)
        }
        return true
    }

    /**
     * A2b: Schreib-Zähler/Diagnose EINES Fensters (Regel 9: Zahlen/Codes) +
     * unveränderlicher [DecodeWindowStats]-Snapshot an den Beobachter.
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
     * B1: Rekonstruiert den vollen Session-Text: Präfix der abgeschlossenen
     * Segmente + Segment-Text, Naht = GENAU EIN Leerzeichen (an der Segment-
     * grenze kennt der Decoder die Wortgrenze nicht — ein Space ist die
     * konservative, nie klebende Wahl; Golden-Testbarkeit bleibt gegeben).
     * Ohne Präfix (Segmenter aus/erste Segment) unverändert der Hypothesen-Text.
     */
    private fun withSegmentPrefix(text: String): String {
        if (segmentPrefix.isEmpty()) return text
        val tail = text.trimStart()
        if (tail.isEmpty()) return segmentPrefix
        val head = segmentPrefix.trimEnd()
        return if (head.isEmpty()) tail else "$head $tail"
    }

    /**
     * B1 (PLAN_V0130 §6.3 Empfehlung (c)): ZWISCHENSEGMENT-FINALISIERUNG —
     * Mini-Finale über das AKTIVE Segment-Puffer: finaler Decode (whole_buffer
     * über den Segment-Ausschnitt) + PERMANENTER Commit über den Bestandsweg
     * ([writeHypothesis] mit Agreement/Policy/Typer — derselbe Vertrag wie das
     * Session-Finale), danach NUR Puffer + Decode-Statistik zurücksetzen; die
     * Session bleibt RECORDING (keine Tonlücke: aufgerufen im Consumer, der
     * einzige Puffer-Eigentümer; Blöcke queue im Kanal). Hypothesen-Takt,
     * Agreement und min_audio-Guard rechnen ab Segmentstart (frischer Puffer).
     *
     * B1-Fix (Review P1, PLAN §6.3(c) Produktvertrag): FEHLERVERHALTEN — nur
     * OOM bleibt ein HARTER Abbruch (abort + rethrow → ERR_OOM-Bestandspfad,
     * kein Decode-Zustand mehr möglich); JEDE andere Exception (ASR-Fehler,
     * Editor-Sync-Timeout) ist TRANSIENT: kein fail(), kein lastError — der
     * Aufrufer verwirft Antrag + Pause-Wächter und die Session läuft RECORDING
     * weiter; der NÄCHSTE Boundary-Versuch versucht erneut. fail() wäre hier
     * ein Abbruchpfad mitten im Diktat (genau was der Vertrag ausschließt); ein
     * wirklich defekter Editor endet über den nächsten PARTIAL-Decode dessen
     * eigenen fail()-Pfad. Idempotent: ohne RECORDING oder ohne Samples im
     * Puffer false ohne Nebeneffekt.
     *
     * @return true, wenn ein Segment finalisiert wurde (Puffer steht danach leer).
     */
    suspend fun finalizeSegment(): Boolean {
        if (state != DictationState.RECORDING) return false
        if (totalSamples <= 0) return false
        try {
            val decodeStartNs = clock() // F17b/C2: Decode-Zeit mit injizierter Uhr
            val hypothesis = engine.transcribe(window(), sessionId, ++runId)
            noteDecode(decodeStartNs) // nur Diagnose-Zahlen
            noteHypothesis() // F13b2: auch ein Mini-Finale ist eine Hypothese
            // Auch Mini-Finale alter Sessions verwerfen (veralteter Lauf)
            if (hypothesis.sessionId == sessionId) {
                writeHypothesis(hypothesis)
            }
        } catch (e: OutOfMemoryError) {
            // OOM bleibt der EINZIGE harte Abbruch (B1-Fix, Review P1): ohne
            // Speicher ist kein weiterer Decode-Zustand möglich — Bestandspfad
            // (Consumer fängt, ERR_OOM, kontrolliertes Ende).
            abort()
            throw e
        } catch (e: IllegalStateException) {
            // B1-Fix (Review P1, PLAN §6.3(c) Produktvertrag „kein Abbruch durch
            // Decode-Latenz/Speicher allein, keine Tonlücke“): transienter
            // ASR-/Editor-Fehler im Mini-Finale beendet die SESSION NICHT —
            // fail() wäre ein Abbruchpfad mitten im Diktat. Der Segment-Antrag
            // wird verworfen (Aufrufer löscht Anforderung + Wächter), die
            // Aufnahme läuft weiter, der NÄCHSTE Boundary-Versuch (nächste
            // Satzpause / Längenregel) versucht erneut. Ein wirklich defekter
            // Editor wird vom NÄCHSTEN Partial-Decode über dessen eigenen
            // fail()-Pfad beendet — das Segment-Finale ist kein Timer.
            return false
        } catch (e: Exception) {
            // dito: transienter ASR-Fehler — verwerfen, Session lebt (P1).
            return false
        }
        // Naht fortschreiben: der neue Präfix ist der committete Stand INKLUSIVE
        // des Mini-Finals; danach Puffer + Decode-Statistik gezielt zurücksetzen
        // (Session-Zähler wie hyp_count bleiben Session-Werte).
        segmentPrefix = typer.currentText
        segmentBaseSamples += totalSamples
        totalSamples = 0
        resetDecodeStats()
        return true
    }

    /** B1: Endet die letzte Hypothese auf Satzzeichen — Eingang der weichen
     *  Segmentregel (a). Nur Boolean, nie Text (Regel 9). */
    fun lastHypothesisEndsWithPunct(): Boolean = lastHypPunct

    private fun window() = PcmWindow(pcm.copyOf(totalSamples), sampleRateHz)

    companion object {
        /**
         * ASR_CONTRACT.md §4.4: Mindest-Audio-Länge für Partials.
         * V12: aus der EINEN Konfig-Quelle (AsrThreadConfig, S12b Hebel 2) statt
         * eigener Konstante — Default 2000 ms ⇒ 2,0 s (Verhalten identisch).
         */
        val MIN_AUDIO_SECONDS_PARTIALS: Double =
            de.voicekeyboard.asr.AsrThreadConfig.DEFAULT.hypothesisMinIntervalMs / 1000.0
        /** F21: Takt-Klammern aus der EINEN Konfig-Quelle (AsrThreadConfig, Hebel 2). */
        val PACE_BASE_MS: Long = de.voicekeyboard.asr.AsrThreadConfig.DEFAULT.decodePaceBaseMs
        val PACE_MAX_MS: Long = de.voicekeyboard.asr.AsrThreadConfig.DEFAULT.decodePaceMaxMs
        private const val EDITOR_SYNC_TIMEOUT = "editor_sync_timeout"
        /** F17b/C2: Deckel des Decode-Stichprobenrings (wächst nie darüber). */
        private const val DECODE_SAMPLE_CAP = 64
        private const val ASR_PARTIAL_ERROR = "asr_partial_error"
        private const val ASR_FINAL_ERROR = "asr_final_error"
        private const val EDITOR_SYNC_TIMEOUT_PREFIX = "Editor-Sync Timeout"
    }
}
