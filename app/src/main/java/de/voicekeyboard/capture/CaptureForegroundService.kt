package de.voicekeyboard.capture

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.SystemClock
import de.voicekeyboard.R
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Foreground service with `foregroundServiceType="microphone"` (TECH_FACTS §4.1):
 *
 * - Type `microphone` (flag 0x80) requires `FOREGROUND_SERVICE_MICROPHONE` from targetSdk 34
 *   **and** a microphone permission — both are in the manifest.
 * - `startForeground` is called immediately in [onStartCommand] (within the
 *   FGS start deadline), before any audio/model initialization happens.
 * - No audio over Binder (DESIGN §2.2, quoted from the German spec:
 *   “capture-service … no audio over binder”):
 *   this service only holds the foreground presence; recording runs in the
 *   IME process via the AudioRecord pipeline. Start/stop are idempotent.
 * - Without `POST_NOTIFICATIONS` the service still runs (notification invisible);
 *   that is reported to the target UI, not worked around here.
 */
class CaptureForegroundService : Service() {

    companion object {
        const val CHANNEL_ID = "voice_keyboard_capture"
        const val NOTIFICATION_ID = 1
        const val ACTION_START = "de.voicekeyboard.capture.START"
        const val ACTION_STOP = "de.voicekeyboard.capture.STOP"
        private const val TAG = "VK-Capture"

        /** F28c: in-process handshake (IME + service run in the same process):
         *  called AFTER [startForeground] has completed. The IME waits
         *  for it BEFORE it opens AudioRecord.startRecording() — from api 33
         *  the FGS type microphone must be active, otherwise the system can
         *  silently stop the recording (export #2: end after 1.0 s). */
        @Volatile var onForegroundReady: (() -> Unit)? = null
    }

    private val running = AtomicBoolean(false)
    private var startedAtMs = 0L

    override fun onBind(intent: Intent?): IBinder? = null // no binder, no audio over IPC

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                if (running.compareAndSet(true, false)) {
                    android.util.Log.i(TAG, "stop; durationMs=${SystemClock.elapsedRealtime() - startedAtMs}")
                }
                stopSelf()
                return START_NOT_STICKY
            }
            // null intent after restart (avoiding the STICKY case): end in a controlled way,
            // no automatic resumption (DESIGN §3, quoted from the German spec:
            // “no automatic restart”)
            null -> {
                stopSelf()
                return START_NOT_STICKY
            }
        }
        // START (or unknown action): start idempotently
        if (running.compareAndSet(false, true)) {
            startedAtMs = SystemClock.elapsedRealtime()
        }
        startForeground(NOTIFICATION_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        // F28c: only NOW is the FGS guaranteed active — the IME may open the
        // microphone (synchronous call, lock/notify only, no UI work).
        onForegroundReady?.invoke()
        return START_NOT_STICKY
    }

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Dictation recording",
            NotificationManager.IMPORTANCE_LOW, // no sound: recording indication, no alarm
        ).apply {
            description = "Shown while the microphone is active for dictation"
            setShowBadge(false)
        }
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val builder =
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                Notification.Builder(this, CHANNEL_ID)
            } else {
                @Suppress("DEPRECATION")
                Notification.Builder(this, CHANNEL_ID)
            }
        return builder
            .setContentTitle(getString(R.string.capture_notification_title)) // F51 (K2): resource instead of hardcoded
            .setContentText("Microphone is active")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()
    }

    override fun onDestroy() {
        if (running.compareAndSet(true, false)) {
            // Metrics only, never audio data or text.
            android.util.Log.i(TAG, "destroy; durationMs=${SystemClock.elapsedRealtime() - startedAtMs}")
        }
        running.set(false)
        super.onDestroy()
    }
}
