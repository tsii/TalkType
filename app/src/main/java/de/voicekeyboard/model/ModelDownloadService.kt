package de.voicekeyboard.model

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import de.voicekeyboard.R
import android.util.Log
import java.io.File
import java.util.concurrent.Executors

/**
 * Foreground-Service (dataSync) für den 671-MB-Modelldownload — E1/E2 aus
 * docs/SETUP_UI_DECISIONS.md. Der Service BESITZT den [DownloadController]
 * (L9: genau eine Instanz pro Prozess, START_NOT_STICKY); die spätere
 * Setup-Activity beobachtet nur [controller] (State-Reader/Listener) und
 * startet/stopt per Intent-Action.
 *
 * L2: startForeground sofort in onStartCommand, Kanal `model_download`,
 * IMPORTANCE_LOW. L4/L5: Cancel-Action; Ready/Failed/Cancelled → stopSelf.
 * L7: onTimeout → controller.cancel() + stopSelf (nie ohne stopSelf weiter).
 *
 * Logging: Tag "VK-DL", NUR Dateinamen/Dauern/Zustände — nie URLs, nie
 * Audio, nie Textinhalte.
 */
/**
 * E7-Fallback-Kaskade als reine, JVM-testbare Logik (keine Android-Typen):
 * Versuchte Modi in fester Reihenfolge TYPED → UNTYPED → ABORT;
 * [nextAfter] liefert den nächsten Versuch nach einem Fehlschlag, [isFinal]
 * sagt, ob nach beiden fehlgeschlagenen Versuchen abgebrochen wird.
 */
object ForegroundStartPolicy {
    enum class Mode { TYPED, UNTYPED, ABORT }

    /** Erster Versuch (API ≥ 29: typisierter dataSync-Start). */
    val firstAttempt: Mode = Mode.TYPED

    /** Nächster Modus nach einem Fehlschlag (IllegalArgumentException/SecurityException/
     * ForegroundServiceStartNotAllowedException → typloser API-29-Fallback). */
    fun nextAfter(failed: Mode): Mode = when (failed) {
        Mode.TYPED -> Mode.UNTYPED
        else -> Mode.ABORT
    }

    /** true, wenn beide Versuche scheiterten und der Download ohne FGS abgebrochen wird. */
    fun isAbort(mode: Mode): Boolean = mode == Mode.ABORT
}

class ModelDownloadService : Service() {

    companion object {
        const val ACTION_START = "de.voicekeyboard.model.download.START"
        const val ACTION_CANCEL = "de.voicekeyboard.model.download.CANCEL"
        const val CHANNEL_ID = "model_download"
        const val NOTIFICATION_ID = 42
        private const val TAG = "VK-DL"

        /** Prozess-Singleton: genau ein laufender Download pro Prozess (L9). */
        @Volatile var controller: DownloadController? = null
            private set

        /** Externer UI-Beobachter (Setup-Activity): wird ZUSÄTZLICH zum internen
         *  Notification-Listener informiert — überschreibt ihn nie. */
        @Volatile var observer: ((DownloadController.State) -> Unit)? = null

        private fun notifyAll(state: DownloadController.State) {
            observer?.invoke(state)
        }

        /** Modelwurzel im privaten Dateisystem (model dir). */
        private fun modelRoot(context: Context): File = File(context.filesDir, "models")
    }

    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "vk-download") }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        if (action == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        // L2: startForeground VOR jeder Init — Notification sofort sichtbar.
        // E7-Kaskade: siehe startForegroundCompat.
        if (!startForegroundCompat()) {
            // Ohne FGS kein Download (Doze/Prozess-Kill-Gefahr) — sauber abbrechen.
            stopSelf()
            return START_NOT_STICKY
        }

        when (action) {
            ACTION_START -> {
                val c = controller ?: createController().also { controller = it }
                val startedAt = System.currentTimeMillis()
                c.listener = { state ->
                    notifyAll(state)
                    onStateChanged(state, startedAt)
                }
                notifyAll(c.state) // aktueller Zustand sofort an neue UI
                c.start() // idempotent: No-Op während Downloading/Verifying/Ready
            }
            ACTION_CANCEL -> {
                controller?.cancel()
                stopSelf()
            }
            else -> stopSelf()
        }
        return START_NOT_STICKY
    }

    /** L7: dataSync-Systemlimit (6 h/24 h) — geordnet abbrechen, nie weiterlaufen. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        controller?.cancel()
        stopSelf()
    }

    override fun onDestroy() {
        controller?.cancel()
        super.onDestroy()
    }

    /**
     * E7 (ERROR_UX_DECISIONS.md): `ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC`
     * und das Manifest-Attribut existieren seit API 29 (minSdk) — die 3-Arg-Form ist
     * der kanonische Aufruf. Neu ab API 34 sind Typ-Pflicht + eigene Permission je
     * Typklasse; Verstöße werfen IllegalArgumentException/SecurityException — und
     * ein Hintergrund-Start wirft ForegroundServiceStartNotAllowedException. Daher
     * GENAU EINE Fallback-Stufe: typlos starten (API-29-Semantik); schlägt auch
     * das fehl, ist ein Download ohne FGS unvertretbar (Prozess unter Speicher-
     * druck killbar, Doze-Throttling) → stopSelf + false.
     *
     * @return true, wenn der Service als Foreground läuft (typisiert oder typlos).
     */
    private fun startForegroundCompat(): Boolean {
        val notification = buildNotification()
        return try {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            true
        } catch (e: Exception) {
            Log.w(TAG, "typed FGS start failed: ${e.javaClass.simpleName}")
            fallbackUntyped(notification)
        }
    }

    private fun fallbackUntyped(notification: Notification): Boolean = try {
        @Suppress("DEPRECATION")
        startForeground(NOTIFICATION_ID, notification) // typlos (API-29-Fallback)
        true
    } catch (e: Exception) {
        // Auch der typlose Start scheiterte (z. B. Start aus dem Hintergrund
        // verboten, Permission entzogen): geordnet beenden, KEIN stiller Download.
        Log.w(TAG, "untyped FGS start failed too: ${e.javaClass.simpleName}")
        stopSelf()
        false
    }

    private fun createController(): DownloadController {
        val store = ModelStore(modelRoot(this))
        val downloader = ModelDownloader(store = store)
        return DownloadController(
            store = store,
            downloaderFactory = { DownloadController.opOf(downloader) },
            manifest = ModelManifest.DEFAULT,
            executor = executor,
        )
    }

    private fun onStateChanged(state: DownloadController.State, startedAtMs: Long) {
        when (state) {
            is DownloadController.State.Ready,
            is DownloadController.State.Failed,
            is DownloadController.State.Cancelled,
            -> {
                // L5: Terminierung innerhalb ~1 s; nur Dauern/Zustand geloggt
                android.util.Log.i(TAG, "state=${state::class.simpleName} elapsedMs=${System.currentTimeMillis() - startedAtMs}")
                stopSelf()
            }
            else -> {
                // Fortschritts-Notification (nur Zahlen/Dateinamen)
                val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                nm.notify(NOTIFICATION_ID, buildNotification(state))
            }
        }
    }

    private fun createChannel() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.download_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        )
        channel.description = getString(R.string.download_notification_loading)
        nm.createNotificationChannel(channel)
    }

    private fun buildNotification(state: DownloadController.State? = null): Notification {
        val text = when (state) {
            is DownloadController.State.Downloading ->
                "Datei ${state.currentIndex + 1}/${state.totalFiles} · ${(state.fraction * 100).toInt()}%"
            is DownloadController.State.Verifying -> "Prüfe Hash …"
            else -> "Modell wird geladen …"
        }
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(getString(R.string.download_notification_title))
            .setContentText(text) // nur Zahlen/Dateinamen, nie Inhalte
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }
}
