package de.voicekeyboard.debug

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import de.voicekeyboard.asr.AsrThreadConfig
import de.voicekeyboard.ime.AndroidPermissionsRequester
import de.voicekeyboard.ime.DictationCoordinator
import de.voicekeyboard.model.ModelStore
import de.voicekeyboard.model.ModelVariant

/**
 * A14/T17: Android-Dünnschicht — füllt [DebugSnapshot] und exportiert ihn
 * (Zwischenablage + Teilen-Dialog). Das Format selbst ist rein JVM
 * ([DebugInfoFormatter], dort getestet).
 *
 * Regel 9 (docs/DEBUG_INFO_FIELDS.md §2):
 *  - Version über PackageManager (BuildConfig ist in diesem Modul aus —
 *    kein buildFeatures-Block in app/build.gradle.kts).
 *  - Modellstatus: Variante-Kurzname + Byte-SUMME, NIE absolute Pfade
 *    (`modelDir().absolutePath` ist verboten — nur Zahlen).
 *  - Fehlercodes: nur der E5-Schlüssel aus `visibleError`, nie Klartext/
 *    message/Stacktrace.
 *  - Der Exporttext wird NICHT geloggt (auch nicht teilweise).
 *  - Kein Netzwerk, kein Ziel-Paketname im Text.
 */
class DebugInfoCollector(private val context: Context) {

    fun collect(
        modelStore: ModelStore,
        modelVariant: ModelVariant,
        coordinator: DictationCoordinator?,
        threadConfig: AsrThreadConfig,
        permissions: AndroidPermissionsRequester,
        audioRoute: String? = null,
        audioSource: String? = null,
        /** F42 (🐞: had_output/output_chars): Ausgabe-Zähler der IME (nur Zahlen). */
        hadOutput: Boolean? = null,
        outputChars: Int? = null,
        /** F42 (🐞: fgs_stopped_by): Ursachencode des letzten FGS-Stops (F44). */
        fgsStoppedBy: String? = null,
        /** F52 (🐞: status_res): Code/Ressourcen-Name des zuletzt gezeigten
         *  Statuszeilen-Texts — nie der Text selbst (Regel 9). */
        statusRes: String? = null,
        /** AP7-W1: gate_open_at_stop — Service-Sample DIREKT vor dem Stopp
         *  (0/1; -1 = kein Stopp); ueberstimmt die grobere Coordinator-Schaetzung
         *  im Feld gate_at_stop (Nennung bleibt unveraendert). */
        gateOpenAtStop: Int? = null,
        /** AP7-W1: abgewiesene Fremd-Resets des Halte-Gates (W1b-Beweis). */
        gateForeignResets: Int? = null,
    ): DebugSnapshot {
        val (versionName, versionCode) = try {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            (info.versionName ?: "") to if (Build.VERSION.SDK_INT >= 28) {
                info.longVersionCode.toInt()
            } else {
                @Suppress("DEPRECATION")
                info.versionCode
            }
        } catch (e: PackageManager.NameNotFoundException) {
            Log.w(TAG, "package info unavailable") // kein Exporttext im Log
            null to null
        }
        val debugBuild = (context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
        // Byte-Summe über die aktive Variante — nur Zahlen, keine Dateinamen/Pfade.
        val modelFiles = try {
            modelStore.modelDir(modelVariant).listFiles()
        } catch (e: Exception) {
            null
        }
        val modelBytes = modelFiles?.takeIf { it.isNotEmpty() }?.sumOf { it.length() }
        val rtf = if (coordinator != null && coordinator.audioMs > 0) {
            coordinator.processingMs.toDouble() / coordinator.audioMs.toDouble()
        } else null
        val granted = try {
            permissions.grantedSet()
        } catch (e: Exception) {
            emptySet()
        }
        return DebugSnapshot(
            versionName = versionName?.takeIf { it.isNotEmpty() },
            versionCode = versionCode,
            debugBuild = debugBuild,
            apiLevel = Build.VERSION.SDK_INT,
            modelStatus = modelStore.statusQuick(modelVariant).name,
            modelVariant = modelVariant.name,
            modelVersionShort = modelStore.activeVersion(modelVariant),
            modelBytes = modelBytes,
            lastError = coordinator?.visibleError,
            rtf = rtf,
            firstHypothesisMs = coordinator?.firstHypothesisLatencyMs,
            intraOpThreads = threadConfig.intraOpThreads,
            interOpThreads = threadConfig.interOpThreads,
            permRecordAudio = android.Manifest.permission.RECORD_AUDIO in granted,
            permPostNotifications = android.Manifest.permission.POST_NOTIFICATIONS in granted,
            peakPercent = coordinator?.levelPeak?.percent(),
            audioRoute = audioRoute,
            audioSource = audioSource,
            peakDbfs = coordinator?.levelPeak?.dbfs(),
            // F13b2: Beweisfelder für den Langtext-Abbruch (C1) — Coordinator ist
            // bereits Parameter; VoiceKeyboardService bleibt unangetastet.
            ctlState = coordinator?.ctlState,
            ctlFailed = coordinator?.failedThisSession,
            droppedBlocks = coordinator?.droppedBlocks,
            hypCount = coordinator?.hypCount,
            hypGapMs = coordinator?.hypGapMs(),
            audioSeconds = coordinator?.audioSeconds,
            lastStartMs = coordinator?.lastStartMs,
            starting = coordinator?.starting,
            // F17c: Decode-Arbeitslast (C2) — gleiche Coordinator-Durchreiche wie hypGapMs.
            decodeMsMax = coordinator?.decodeMsMax(),
            decodeMsP95 = coordinator?.decodeMsP95(),
            bufferSeconds = coordinator?.bufferSeconds(),
            // F21: cost-aware Takt — gleiche Durchreiche, nur Zahlen (Regel 9).
            decodePaceMs = coordinator?.decodePaceMs(),
            decodeSkips = coordinator?.decodeSkips(),
            // F28b1: Capture-Stall-Messung (🐞: cap_blocks/cap_stall_ms) — gleiche Durchreiche.
            capBlocks = coordinator?.capBlocks(),
            capStallMs = coordinator?.capStallMs(),
            // F26a: Abort-Grund (C2-Export) — gleiche Durchreiche, kontrollierte Namen.
            abortReason = coordinator?.lastAbortReason,
            // AP3 (PLAN_C2_FABLE_R2 §4, Export v2 Teil 1): Session-Forensik —
            // stop_cause (vereinheitlicht), start_src, gate_at_stop,
            // owner_lost_at_s, segment_finalizations, max_pause_ms,
            // restart_inputs, sel_unexpected. Nur Codes/Zahlen (Regel 9).
            stopCause = coordinator?.stopCause(),
            startSrc = coordinator?.startSrc(),
            // AP7-W1: Service-Sample (Stop-Moment) hat Vorrang; sonst Coordinator.
            gateAtStop = gateOpenAtStop?.takeIf { it >= 0 } ?: coordinator?.gateAtStop(),
            ownerLostAtS = coordinator?.ownerLostAtS(),
            segmentFinalizations = coordinator?.segmentFinalizations(),
            maxPauseMs = coordinator?.maxPauseMs(),
            restartInputs = coordinator?.restartInputs(),
            selUnexpected = coordinator?.selUnexpected(),
            gateForeignResets = gateForeignResets,
            // AP7-E1: Re-Baselines nach restartInput (neben restart_inputs).
            rebases = coordinator?.rebases(),
            // F26b (C2-Export): Bufferstand der letzten Hypothese (nur Zahlen, Regel 9).
            hypLastBufferSeconds = coordinator?.lastHypothesisBufferSeconds()?.toDouble(),
            // F28b2: Wer hat die Session beendet + was zeigte die Statuszeile
            // (reine Codes/ms, Regel 9).
            micRel = coordinator?.micRel(),
            statusShown = coordinator?.statusShown(),
            // F52: Code/Ressourcen-Name des zuletzt gezeigten Statustexts (Regel 9).
            statusRes = statusRes,
            // F31: Sekunden mit lautem Signal (Stille-Erkennung, 🐞: loud_s; nur Zahlen).
            loudS = coordinator?.loudS(),
            // F42 (F41-Fehlende-Messpunkte): Ausgabe-/Stille-/FGS-Forensik — nur
            // Zahlen/Codes (Regel 9); Stille-Messfelder via Coordinator-Durchreiche.
            hadOutput = hadOutput,
            outputChars = outputChars,
            firstLoudMs = coordinator?.firstLoudMs(),
            first3sLoudBlocks = coordinator?.first3sLoudBlocks(),
            first3sPeakDbfs = coordinator?.first3sPeakDbfs(),
            fgsStoppedBy = fgsStoppedBy,
            // A4a (PLAN_V0133 §A4a): Decode-/Schreib-Diagnose (A2b) — NUR Durchreichen
            // (keine neuen Textquellen): Fensterwerte aus dem A2b-Diagnoseobjekt
            // (lastDecodeWindow), Session-Zähler über die Coordinator-Durchreiche.
            // Nur Zahlen/Codes (Regel 9).
            lastDecodeMs = coordinator?.lastDecodeWindow()?.decodeMs,
            hypLen = coordinator?.lastDecodeWindow()?.hypLen,
            currentLen = coordinator?.lastDecodeWindow()?.currentLen,
            committedLen = coordinator?.lastDecodeWindow()?.committedLen,
            writeReason = coordinator?.lastDecodeWindow()?.write,
            writeSame = coordinator?.writeSameCount(),
            writeW1 = coordinator?.writeW1Count(),
            writeW3d = coordinator?.writeW3dCount(),
            writeDelta = coordinator?.writeDeltaCount(),
            lastWriteAudioS = coordinator?.lastWriteAudioS(),
            // W-Paket (vc34): Engine-Warming — Durchreiche aus dem Coordinator
            // (nur Zahlen/Codes, Regel 9): reine load-Dauer, Resident-Flag,
            // warmer Diktat-Start, Auslöser des letzten load(), Load-Zähler.
            engineLoadMs = coordinator?.engineLoadMs,
            engineResident = coordinator?.engineResident,
            warmHit = coordinator?.warmHit,
            loadTrigger = coordinator?.loadTrigger,
            engineLoads = coordinator?.engineLoads,
        )
    }

    /**
     * Export: Text IMMER in die Zwischenablage, danach Teilen-Dialog
     * (ACTION_SEND, text/plain, Chooser). Ohne Handler bleibt die
     * Zwischenablage der Fallback — kein Stillstand, kein Crash.
     * Regel 9: der Text wird nicht geloggt; kein Ziel-Paketname gesetzt
     * (`setPackage` bewusst weggelassen).
     */
    fun export(text: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("VK Debug", text))
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
        val chooser = Intent.createChooser(send, "Diagnose teilen")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(chooser)
        } catch (e: Exception) {
            Log.i(TAG, "no share handler; clipboard fallback active") // kein Textinhalt
        }
    }

    private companion object {
        const val TAG = "VK-DEBUG-INFO"
    }
}
