package com.example.nightagent.evidence

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import com.example.nightagent.R
import com.example.nightagent.firebase.FirebaseConfig
import com.example.nightagent.sos.LocationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/**
 * Foreground service handling ONLY audio recording and GPS tracking.
 *
 * Android 15 Camera FGS rule: a foreground service with type CAMERA can only be
 * started while the app has a visible Activity AND the user has granted CAMERA.
 * Moving CameraX to the UI layer (EvidenceScreen) eliminates this restriction
 * entirely — this service now declares only microphone|location.
 *
 * Foreground types: MICROPHONE | LOCATION  (no CAMERA)
 */
class EvidenceCaptureService : LifecycleService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var evidenceManager: EvidenceManager

    private var sosId: String = ""
    var lastLocation: Location? = null
        private set

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        evidenceManager = EvidenceManager(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_START -> {
                sosId = intent.getStringExtra(EXTRA_SOS_ID) ?: System.currentTimeMillis().toString()
                startForegroundCompat()
                beginCapture()
            }
            ACTION_STOP -> endCapture()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
        runCatching { evidenceManager.stopAudioRecording() }
    }

    // ── Internal ──────────────────────────────────────────────────────────────

    private fun startForegroundCompat() {
        val notification = buildNotification("Recording audio & location…")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun beginCapture() {
        LocationProvider.getLocation(this) { loc -> lastLocation = loc }

        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO)
            == PackageManager.PERMISSION_GRANTED) {
            evidenceManager.startChunkedAudioRecording()
        } else {
            Log.w(TAG, "RECORD_AUDIO not granted — skipping audio recording")
        }
    }

    private fun endCapture() {
        runCatching { evidenceManager.stopAudioRecording() }
        val uid = FirebaseConfig.auth.currentUser?.uid ?: "unknown"
        EvidenceUploadWorker.enqueue(this, sosId, uid)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    // ── Notification ──────────────────────────────────────────────────────────

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "Evidence Capture", NotificationManager.IMPORTANCE_LOW
            ).apply { setShowBadge(false) }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("🎙 Evidence Recording")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setSilent(true)
            .build()

    companion object {
        private const val TAG             = "EvidenceCaptureService"
        private const val CHANNEL_ID      = "evidence_capture_channel"
        private const val NOTIFICATION_ID = 3001

        const val ACTION_START = "com.example.nightagent.EVIDENCE_START"
        const val ACTION_STOP  = "com.example.nightagent.EVIDENCE_STOP"
        const val EXTRA_SOS_ID = "sos_id"

        fun start(context: Context, sosId: String) {
            val intent = Intent(context, EvidenceCaptureService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_SOS_ID, sosId)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.startService(Intent(context, EvidenceCaptureService::class.java).apply {
                action = ACTION_STOP
            })
        }
    }
}
