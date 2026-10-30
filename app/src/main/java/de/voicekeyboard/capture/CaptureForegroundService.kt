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
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Foreground-Service mit `foregroundServiceType="microphone"` (TECH_FACTS §4.1):
 *
 * - Typ `microphone` (Flag 0x80) verlangt ab targetSdk 34 `FOREGROUND_SERVICE_MICROPHONE`
 *   **und** eine Mikrofon-Permission — beide stehen im Manifest.
 * - `startForeground` wird sofort in [onStartCommand] gerufen (innerhalb der
 *   FGS-Startfrist), bevor irgendwelche Audio-/Modell-Initials passiert.
 * - Kein Audio über Binder (DESIGN §2.2 „capture-service … kein Audio über Binder"):
 *   dieser Service hält nur die Vordergrund-Präsenz; Aufnahme läuft im
 *   IME-Prozess über die AudioRecord-Pipeline. Start/Stop sind idempotent.
 * - Ohne `POST_NOTIFICATIONS` läuft der Service trotzdem (Notification unsichtbar);
 *   das wird der Ziel-UI gemeldet, nicht hier umgangen.
 */
class CaptureForegroundService : Service() {

    companion object {
        const val CHANNEL_ID = "voice_keyboard_capture"
        const val NOTIFICATION_ID = 1
        const val ACTION_START = "de.voicekeyboard.capture.START"
        const val ACTION_STOP = "de.voicekeyboard.capture.STOP"
        private const val TAG = "VK-Capture"
    }

    private val running = AtomicBoolean(false)
    private var startedAtMs = 0L

    override fun onBind(intent: Intent?): IBinder? = null // kein Binder, kein Audio über IPC

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
            // null-Intent nach Neustart (STICKY-Fall vermeiden): kontrolliert beenden,
            // keine automatische Wiederaufnahme (DESIGN §3 „kein automatisches Wiederanlaufen")
            null -> {
                stopSelf()
                return START_NOT_STICKY
            }
        }
        // START (oder unbekannte Aktion): idempotent starten
        if (running.compareAndSet(false, true)) {
            startedAtMs = SystemClock.elapsedRealtime()
        }
        startForeground(NOTIFICATION_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        return START_NOT_STICKY
    }

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Diktat-Aufnahme",
            NotificationManager.IMPORTANCE_LOW, // kein Sound: Aufnahme-Indikation, kein Alarm
        ).apply {
            description = "Zeigt an, solange das Mikrofon für das Diktat aktiv ist"
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
            .setContentTitle("Diktat läuft")
            .setContentText("Mikrofon ist aktiv")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()
    }

    override fun onDestroy() {
        if (running.compareAndSet(true, false)) {
            // Nur Kennzahlen, nie Audiodaten oder Text.
            android.util.Log.i(TAG, "destroy; durationMs=${SystemClock.elapsedRealtime() - startedAtMs}")
        }
        running.set(false)
        super.onDestroy()
    }
}
