package com.example.nightagent.streaming

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.nightagent.R
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest

/**
 * Foreground service that hosts the WebRTC caller session.
 * Started when victim triggers SOS; stopped when SOS is cancelled.
 *
 * Extras required on ACTION_START:
 *   EXTRA_CALL_ID   — Firestore callId
 *   EXTRA_CALLEE_UID — guardian UID
 */
class AudioStreamingService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var webRTCManager: WebRTCManager? = null
    private val signalingRepo = SignalingRepository()

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val callId = intent.getStringExtra(EXTRA_CALL_ID) ?: return START_NOT_STICKY
                val calleeUid = intent.getStringExtra(EXTRA_CALLEE_UID) ?: return START_NOT_STICKY
                startForeground(NOTIFICATION_ID, buildNotification("Connecting…"))
                startStreaming(callId, calleeUid)
            }
            ACTION_STOP -> stopStreaming()
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
        webRTCManager?.release()
    }

    // ── Streaming ─────────────────────────────────────────────────────────────

    private fun startStreaming(callId: String, calleeUid: String) {
        val manager = WebRTCManager(
            context    = applicationContext,
            isCaller   = true,
            onLocalCandidate = { candidate ->
                signalingRepo.addCallerCandidate(callId, candidate)
            },
            onLocalSdp = { sdp ->
                signalingRepo.setOffer(callId, sdp)
            }
        )
        webRTCManager = manager
        manager.initConnection()
        manager.createOffer()

        // Listen for answer
        scope.launch {
            signalingRepo.answerFlow(callId).collectLatest { sdp ->
                if (sdp != null) manager.setRemoteAnswer(sdp)
            }
        }

        // Listen for callee ICE candidates
        scope.launch {
            signalingRepo.calleeCandidatesFlow(callId).collect { candidate ->
                manager.addRemoteCandidate(candidate)
            }
        }

        // Watch call status — stop if guardian ends
        scope.launch {
            signalingRepo.statusFlow(callId).collectLatest { status ->
                if (status == SignalingRepository.STATUS_ENDED) stopStreaming()
            }
        }

        // Update notification when connected
        scope.launch {
            manager.state.collectLatest { state ->
                val text = when (state) {
                    StreamState.CONNECTED   -> "Guardian listening live"
                    StreamState.CONNECTING  -> "Connecting…"
                    StreamState.ERROR       -> "Connection lost"
                    StreamState.ENDED       -> { stopStreaming(); return@collectLatest }
                    else -> return@collectLatest
                }
                updateNotification(text)
            }
        }
    }

    private fun stopStreaming() {
        webRTCManager?.release()
        webRTCManager = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    // ── Notification ──────────────────────────────────────────────────────────

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel(CHANNEL_ID, "Live Audio Stream", NotificationManager.IMPORTANCE_LOW)
                .apply { setShowBadge(false) }
                .also { getSystemService(NotificationManager::class.java).createNotificationChannel(it) }
        }
    }

    private fun buildNotification(text: String): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("🔴 Live Audio Streaming")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setSilent(true)
            .build()

    private fun updateNotification(text: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID, buildNotification(text))
    }

    companion object {
        private const val CHANNEL_ID = "audio_stream_channel"
        private const val NOTIFICATION_ID = 2001

        const val ACTION_START     = "com.example.nightagent.STREAM_START"
        const val ACTION_STOP      = "com.example.nightagent.STREAM_STOP"
        const val EXTRA_CALL_ID    = "call_id"
        const val EXTRA_CALLEE_UID = "callee_uid"

        fun start(context: Context, callId: String, calleeUid: String) {
            val intent = Intent(context, AudioStreamingService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_CALL_ID, callId)
                putExtra(EXTRA_CALLEE_UID, calleeUid)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.startService(Intent(context, AudioStreamingService::class.java).apply {
                action = ACTION_STOP
            })
        }
    }
}
