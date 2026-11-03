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
 * Foreground service (dataSync) for the 671 MB model download — E1/E2 from
 * docs/SETUP_UI_DECISIONS.md. The service OWNS the [DownloadController]
 * (L9: exactly one instance per process, START_NOT_STICKY); the later
 * setup activity only observes [controller] (state reader/listener) and
 * starts/stops via intent action.
 *
 * L2: startForeground immediately in onStartCommand, channel `model_download`,
 * IMPORTANCE_LOW. L4/L5: cancel action; Ready/Failed/Cancelled → stopSelf.
 * L7: onTimeout → controller.cancel() + stopSelf (never continue without stopSelf).
 *
 * Logging: tag "VK-DL", ONLY file names/durations/states — never URLs, never
 * audio, never text contents.
 */
/**
 * E7 fallback cascade as pure, JVM-testable logic (no Android types):
 * attempted modes in fixed order TYPED → UNTYPED → ABORT;
 * [nextAfter] returns the next attempt after a failure, [isAbort]
 * says whether the download is aborted after both attempts failed.
 */
object ForegroundStartPolicy {
    enum class Mode { TYPED, UNTYPED, ABORT }

    /** First attempt (API ≥ 29: typed dataSync start). */
    val firstAttempt: Mode = Mode.TYPED

    /** Next mode after a failure (IllegalArgumentException/SecurityException/
     * ForegroundServiceStartNotAllowedException → untyped API-29 fallback). */
    fun nextAfter(failed: Mode): Mode = when (failed) {
        Mode.TYPED -> Mode.UNTYPED
        else -> Mode.ABORT
    }

    /** true if both attempts failed and the download is aborted without FGS. */
    fun isAbort(mode: Mode): Boolean = mode == Mode.ABORT
}

class ModelDownloadService : Service() {

    companion object {
        const val ACTION_START = "de.voicekeyboard.model.download.START"
        const val ACTION_CANCEL = "de.voicekeyboard.model.download.CANCEL"
        const val CHANNEL_ID = "model_download"
        const val NOTIFICATION_ID = 42
        private const val TAG = "VK-DL"

        /** Process singleton: exactly one running download per process (L9). */
        @Volatile var controller: DownloadController? = null
            private set

        /** External UI observer (setup activity): informed IN ADDITION to the internal
         *  notification listener — never overrides it. */
        @Volatile var observer: ((DownloadController.State) -> Unit)? = null

        private fun notifyAll(state: DownloadController.State) {
            observer?.invoke(state)
        }

        /** Model root in the private file system (model dir). */
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
        // L2: startForeground BEFORE any init — notification visible immediately.
        // E7 cascade: see startForegroundCompat.
        if (!startForegroundCompat()) {
            // No download without FGS (Doze/process-kill risk) — cancel cleanly.
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
                notifyAll(c.state) // current state to the new UI immediately
                c.start() // idempotent: no-op during Downloading/Verifying/Ready
            }
            ACTION_CANCEL -> {
                controller?.cancel()
                stopSelf()
            }
            else -> stopSelf()
        }
        return START_NOT_STICKY
    }

    /** L7: dataSync system limit (6 h/24 h) — abort in an orderly way, never keep running. */
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
     * and the manifest attribute exist since API 29 (minSdk) — the 3-arg form is
     * the canonical call. New from API 34: type obligation + own permission per
     * type class; violations throw IllegalArgumentException/SecurityException — and
     * a background start throws ForegroundServiceStartNotAllowedException. Hence
     * EXACTLY ONE fallback level: start untyped (API-29 semantics); if that
     * fails too, a download without FGS is indefensible (process killable under
     * memory pressure, Doze throttling) → stopSelf + false.
     *
     * @return true if the service runs as foreground (typed or untyped).
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
        startForeground(NOTIFICATION_ID, notification) // untyped (API-29 fallback)
        true
    } catch (e: Exception) {
        // The untyped start failed too (e. g. background start
        // forbidden, permission revoked): end in an orderly way, NO silent download.
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
                // L5: termination within ~1 s; only durations/state logged
                android.util.Log.i(TAG, "state=${state::class.simpleName} elapsedMs=${System.currentTimeMillis() - startedAtMs}")
                stopSelf()
            }
            else -> {
                // progress notification (numbers/file names only)
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
                "file ${state.currentIndex + 1}/${state.totalFiles} · ${(state.fraction * 100).toInt()}%"
            is DownloadController.State.Verifying -> "checking hash …"
            else -> "loading model …"
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
            .setContentText(text) // numbers/file names only, never contents
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }
}
