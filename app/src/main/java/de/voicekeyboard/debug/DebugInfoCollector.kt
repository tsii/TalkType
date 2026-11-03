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
 * A14/T17: Android thin layer — fills [DebugSnapshot] and exports it
 * (clipboard + share dialog). The format itself is pure JVM
 * ([DebugInfoFormatter], tested there).
 *
 * Rule 9 (docs/DEBUG_INFO_FIELDS.md §2):
 *  - Version via PackageManager (BuildConfig is out in this module —
 *    no buildFeatures block in app/build.gradle.kts).
 *  - Model status: variant short name + byte SUM, NEVER absolute paths
 *    (`modelDir().absolutePath` is forbidden — numbers only).
 *  - Error codes: only the E5 key from `visibleError`, never plain text/
 *    message/stacktrace.
 *  - The export text is NOT logged (not even partially).
 *  - No network, no target package name in the text.
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
        /** F42 (🐞: had_output/output_chars): output counters of the IME (numbers only). */
        hadOutput: Boolean? = null,
        outputChars: Int? = null,
        /** F42 (🐞: fgs_stopped_by): cause code of the last FGS stop (F44). */
        fgsStoppedBy: String? = null,
        /** F52 (🐞: status_res): code/resource name of the last shown
         *  status row text — never the text itself (rule 9). */
        statusRes: String? = null,
        /** AP7-W1: gate_open_at_stop — service sample DIRECTLY before the stop
         *  (0/1; -1 = no stop); overrides the coarser coordinator estimate
         *  in the field gate_at_stop (the name stays unchanged). */
        gateOpenAtStop: Int? = null,
        /** AP7-W1: rejected foreign resets of the hold gate (W1b evidence). */
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
            Log.w(TAG, "package info unavailable") // no export text in the log
            null to null
        }
        val debugBuild = (context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
        // Byte sum over the active variant — numbers only, no file names/paths.
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
            // F13b2: evidence fields for the long-text abort (C1) — the coordinator is
            // already a parameter; VoiceKeyboardService stays untouched.
            ctlState = coordinator?.ctlState,
            ctlFailed = coordinator?.failedThisSession,
            droppedBlocks = coordinator?.droppedBlocks,
            hypCount = coordinator?.hypCount,
            hypGapMs = coordinator?.hypGapMs(),
            audioSeconds = coordinator?.audioSeconds,
            lastStartMs = coordinator?.lastStartMs,
            starting = coordinator?.starting,
            // F17c: decode workload (C2) — same coordinator pass-through as hypGapMs.
            decodeMsMax = coordinator?.decodeMsMax(),
            decodeMsP95 = coordinator?.decodeMsP95(),
            bufferSeconds = coordinator?.bufferSeconds(),
            // F21: cost-aware pace — same pass-through, numbers only (rule 9).
            decodePaceMs = coordinator?.decodePaceMs(),
            decodeSkips = coordinator?.decodeSkips(),
            // F28b1: capture stall measurement (🐞: cap_blocks/cap_stall_ms) — same pass-through.
            capBlocks = coordinator?.capBlocks(),
            capStallMs = coordinator?.capStallMs(),
            // F26a: abort reason (C2 export) — same pass-through, controlled names.
            abortReason = coordinator?.lastAbortReason,
            // AP3 (PLAN_C2_FABLE_R2 §4, export v2 part 1): session forensics —
            // stop_cause (unified), start_src, gate_at_stop,
            // owner_lost_at_s, segment_finalizations, max_pause_ms,
            // restart_inputs, sel_unexpected. Codes/numbers only (rule 9).
            stopCause = coordinator?.stopCause(),
            startSrc = coordinator?.startSrc(),
            // AP7-W1: the service sample (stop moment) takes precedence; otherwise the coordinator.
            gateAtStop = gateOpenAtStop?.takeIf { it >= 0 } ?: coordinator?.gateAtStop(),
            ownerLostAtS = coordinator?.ownerLostAtS(),
            segmentFinalizations = coordinator?.segmentFinalizations(),
            maxPauseMs = coordinator?.maxPauseMs(),
            restartInputs = coordinator?.restartInputs(),
            selUnexpected = coordinator?.selUnexpected(),
            gateForeignResets = gateForeignResets,
            // AP7-E1: re-baselines after restartInput (next to restart_inputs).
            rebases = coordinator?.rebases(),
            // F26b (C2 export): buffer level of the last hypothesis (numbers only, rule 9).
            hypLastBufferSeconds = coordinator?.lastHypothesisBufferSeconds()?.toDouble(),
            // F28b2: who ended the session + what the status row showed
            // (pure codes/ms, rule 9).
            micRel = coordinator?.micRel(),
            statusShown = coordinator?.statusShown(),
            // F52: code/resource name of the last shown status text (rule 9).
            statusRes = statusRes,
            // F31: seconds with a loud signal (silence detection, 🐞: loud_s; numbers only).
            loudS = coordinator?.loudS(),
            // F42 (F41 missing measurement points): output/silence/FGS forensics —
            // numbers/codes only (rule 9); silence measurement fields via the coordinator pass-through.
            hadOutput = hadOutput,
            outputChars = outputChars,
            firstLoudMs = coordinator?.firstLoudMs(),
            first3sLoudBlocks = coordinator?.first3sLoudBlocks(),
            first3sPeakDbfs = coordinator?.first3sPeakDbfs(),
            fgsStoppedBy = fgsStoppedBy,
            // A4a (PLAN_V0133 §A4a): decode/write diagnostics (A2b) — ONLY pass-through
            // (no new text sources): window values from the A2b diagnostics object
            // (lastDecodeWindow), session counters via the coordinator pass-through.
            // Numbers/codes only (rule 9).
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
            // W package (vc34): engine warming — pass-through from the coordinator
            // (numbers/codes only, rule 9): pure load duration, resident flag,
            // warm dictation start, trigger of the last load(), load counter.
            engineLoadMs = coordinator?.engineLoadMs,
            engineResident = coordinator?.engineResident,
            warmHit = coordinator?.warmHit,
            loadTrigger = coordinator?.loadTrigger,
            engineLoads = coordinator?.engineLoads,
        )
    }

    /**
     * Export: text ALWAYS into the clipboard, then share dialog
     * (ACTION_SEND, text/plain, chooser). Without a handler the
     * clipboard stays the fallback — no stall, no crash.
     * Rule 9: the text is not logged; no target package name set
     * (`setPackage` deliberately omitted).
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
            Log.i(TAG, "no share handler; clipboard fallback active") // no text content
        }
    }

    private companion object {
        const val TAG = "VK-DEBUG-INFO"
    }
}
