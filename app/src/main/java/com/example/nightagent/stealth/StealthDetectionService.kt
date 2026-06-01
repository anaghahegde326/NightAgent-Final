package com.example.nightagent.stealth

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.nightagent.R
import com.example.nightagent.sos.LocationProvider
import com.example.nightagent.sos.SOSManager

/**
 * Foreground service that runs silently in the background when Stealth Mode is active.
 *
 * Trigger: 3 screen-toggle events (screen ON or OFF) within [SOS_TRIGGER_WINDOW_MS].
 * This is the most reliable proxy for power-button presses on modern Android.
 *
 * On trigger: calls SOSManager.triggerSOS() — reusing all existing SOS logic
 * (SMS, Firestore session, live location tracking, alarm).
 */
class StealthDetectionService : Service() {

    private var pressCount = 0
    private var lastPressTime = 0L
    private var screenReceiver: ScreenToggleReceiver? = null

    // ── Lifecycle ────────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
        registerScreenReceiver()
        Log.d(TAG, "StealthDetectionService started")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int =
        START_STICKY   // restart automatically if killed

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        screenReceiver?.let { unregisterReceiver(it) }
        Log.d(TAG, "StealthDetectionService stopped")
    }

    // ── Screen receiver ──────────────────────────────────────────────────────

    private fun registerScreenReceiver() {
        screenReceiver = ScreenToggleReceiver()
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        }
        registerReceiver(screenReceiver, filter)
    }

    inner class ScreenToggleReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val now = System.currentTimeMillis()

            // Reset counter if outside the detection window
            if (now - lastPressTime > SOS_TRIGGER_WINDOW_MS) {
                pressCount = 0
            }

            pressCount++
            lastPressTime = now

            Log.d(TAG, "Screen toggle #$pressCount (action=${intent.action})")

            if (pressCount >= SOS_TRIGGER_COUNT) {
                pressCount = 0
                triggerStealthSOS()
            }
        }
    }

    // ── SOS trigger ──────────────────────────────────────────────────────────

    private fun triggerStealthSOS() {
        Log.d(TAG, "Stealth SOS triggered via power button")
        // Reuse the existing SOSManager — handles SMS, Firestore, live tracking, alarm
        SOSManager.triggerSOS(applicationContext)

        // Also start audio recording if the setting is enabled
        val recordingIntent = Intent(this, AudioRecordingService::class.java).apply {
            action = AudioRecordingService.ACTION_START_RECORDING
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(recordingIntent)
        } else {
            startService(recordingIntent)
        }
    }

    // ── Notification ─────────────────────────────────────────────────────────

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "NightAgent Protection",
                NotificationManager.IMPORTANCE_MIN   // completely silent, no heads-up
            ).apply {
                description = "Keeps NightAgent active in the background"
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("NightAgent")
            .setContentText("Protection active")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOngoing(true)
            .setSilent(true)
            .build()

    companion object {
        private const val TAG = "StealthDetection"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "stealth_detection_channel"
        private const val SOS_TRIGGER_COUNT = 3
        private const val SOS_TRIGGER_WINDOW_MS = 3000L

        fun start(context: Context) {
            val intent = Intent(context, StealthDetectionService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, StealthDetectionService::class.java))
        }
    }
}
