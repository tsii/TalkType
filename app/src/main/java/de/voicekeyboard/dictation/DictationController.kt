package de.voicekeyboard.dictation

import de.voicekeyboard.asr.AsrEngine
import de.voicekeyboard.asr.Hypothesis
import de.voicekeyboard.audio.PcmBlock
import de.voicekeyboard.audio.PcmWindow
import de.voicekeyboard.commit.CommitPolicy
import de.voicekeyboard.editor.DeltaTyper
import de.voicekeyboard.editor.EditorSink
import de.voicekeyboard.editor.OwnershipTracker

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

    /** F13b2: Abstand seit der letzten Hypothese in ms; -1 wenn nie eine kam. */
    fun currentHypGapMs(): Long =
        if (lastHypAt == 0L) -1L
        else ((clock() - lastHypAt) / 1_000_000).coerceAtLeast(0) // F13d/P2-2: nie negativ

    private val typer = DeltaTyper(sink)
    private var pcm = ShortArray(0)
    private var totalSamples = 0
    private var runId = 0L

    val audioSeconds: Double
        get() = if (sampleRateHz <= 0) 0.0 else totalSamples.toDouble() / sampleRateHz

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
        policy.reset()
        typer.reset()
        tracker.reset()
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
            try {
                val hypothesis = engine.transcribe(window(), sessionId, ++runId)
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
                val hypothesis = engine.transcribe(window(), sessionId, ++runId)
                noteHypothesis() // F13b2: auch das Finale ist eine erzeugte Hypothese
                // Auch Finals alter Sessions verwerfen (veralteter Lauf)
                if (hypothesis.sessionId == sessionId) {
                    writeHypothesis(hypothesis)
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
     */
    private fun writeHypothesis(hypothesis: Hypothesis): Boolean {
        if (!tracker.isOwned(typer.currentText, contextForOwnText())) return false
        val committed = policy.onHypothesis(
            hypothesisText = hypothesis.text,
            audioSamples = totalSamples.toLong(),
            currentCommitted = typer.committedLength,
            budgetExceeded = hypothesis.incomplete,
        )
        typer.commitUpTo(committed)
        val committedBefore = typer.committedLength
        val delta = typer.revise(hypothesis.text)
        // E8.2 C6: nach einem W3c-Rollback (committedLength GESCHRUMPFT, kein
        // Divergenz-Verweigerung) beginnt die Agreement-Zählung NEU — kein
        // sofortiger Re-Commit desselben (fehlerhaften) Präfixes.
        // Hart bedingung: NUR nach W3c — bei W1 (null + divergent) und W3d
        // (null + divergent) hat sich an der Beweislage nichts geändert.
        if (delta != null && typer.committedLength < committedBefore) {
            policy.reset()
        }
        if (delta != null) {
            tracker.onOwnEdit(typer.currentText)
        }
        return true
    }

    private fun append(samples: ShortArray) {
        if (pcm.size < totalSamples + samples.size) {
            pcm = pcm.copyOf(maxOf(totalSamples + samples.size, pcm.size * 2))
        }
        System.arraycopy(samples, 0, pcm, totalSamples, samples.size)
        totalSamples += samples.size
    }

    private fun window() = PcmWindow(pcm.copyOf(totalSamples), sampleRateHz)

    companion object {
        /**
         * ASR_CONTRACT.md §4.4: Mindest-Audio-Länge für Partials.
         * V12: aus der EINEN Konfig-Quelle (AsrThreadConfig, S12b Hebel 2) statt
         * eigener Konstante — Default 2000 ms ⇒ 2,0 s (Verhalten identisch).
         */
        val MIN_AUDIO_SECONDS_PARTIALS: Double =
            de.voicekeyboard.asr.AsrThreadConfig.DEFAULT.hypothesisMinIntervalMs / 1000.0
        private const val EDITOR_SYNC_TIMEOUT = "editor_sync_timeout"
        private const val ASR_PARTIAL_ERROR = "asr_partial_error"
        private const val ASR_FINAL_ERROR = "asr_final_error"
        private const val EDITOR_SYNC_TIMEOUT_PREFIX = "Editor-Sync Timeout"
    }
}
