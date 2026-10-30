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
