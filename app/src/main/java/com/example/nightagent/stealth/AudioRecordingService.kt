package com.example.nightagent.stealth

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.nightagent.R
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Foreground service that records audio silently when a Stealth SOS is triggered.
 *
 * Files are saved to the app's private external files directory — no
 * WRITE_EXTERNAL_STORAGE permission required on Android 10+.
 *
 * Start:  send ACTION_START_RECORDING
 * Stop:   send ACTION_STOP_RECORDING  (or the service auto-stops after MAX_DURATION_MS)
 */
class AudioRecordingService : Service() {

    private var mediaRecorder: MediaRecorder? = null
    private var isRecording = false
    private var currentFile: File? = null
    private var recordingStartTime = 0L

    // ── Lifecycle ────────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_RECORDING -> startRecording()
            ACTION_STOP_RECORDING  -> stopRecordingAndSelf()
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        releaseRecorder()
    }

    // ── Recording ────────────────────────────────────────────────────────────

    private fun startRecording() {
        if (isRecording) return

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Log.e(TAG, "RECORD_AUDIO permission not granted — aborting")
            stopSelf()
            return
        }

        try {
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val dir = getExternalFilesDir("SOS_Audio") ?: filesDir
            currentFile = File(dir, "sos_audio_$timestamp.mp4")

            @Suppress("DEPRECATION")
            mediaRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(this)
            } else {
                MediaRecorder()
            }

            mediaRecorder!!.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioEncodingBitRate(128_000)
                setAudioSamplingRate(44_100)
                setOutputFile(currentFile!!.absolutePath)
                prepare()
                start()
            }

            isRecording = true
            recordingStartTime = SystemClock.elapsedRealtime()

            startForeground(NOTIFICATION_ID, buildNotification())
            Log.d(TAG, "Recording started → ${currentFile!!.name}")

            // Auto-stop after MAX_DURATION_MS to avoid draining battery
            android.os.Handler(android.os.Looper.getMainLooper())
                .postDelayed({ stopRecordingAndSelf() }, MAX_DURATION_MS)

        } catch (e: Exception) {
            Log.e(TAG, "Failed to start recording: ${e.message}", e)
            releaseRecorder()
            stopSelf()
        }
    }

    private fun stopRecordingAndSelf() {
        releaseRecorder()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        Log.d(TAG, "Recording stopped → ${currentFile?.name}")
    }

    private fun releaseRecorder() {
        if (!isRecording) return
        try {
            mediaRecorder?.stop()
        } catch (_: Exception) { /* already stopped */ }
        try {
            mediaRecorder?.release()
        } catch (_: Exception) { }
        mediaRecorder = null
        isRecording = false
    }

    // ── Notification ─────────────────────────────────────────────────────────

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Evidence Recording",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Recording audio evidence during emergency"
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val elapsed = (SystemClock.elapsedRealtime() - recordingStartTime) / 1000
        val mm = elapsed / 60
        val ss = elapsed % 60
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Recording Evidence")
            .setContentText("Recording: %02d:%02d".format(mm, ss))
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    companion object {
        private const val TAG = "AudioRecordingService"
        private const val NOTIFICATION_ID = 1002
        private const val CHANNEL_ID = "audio_recording_channel"
        private const val MAX_DURATION_MS = 5 * 60 * 1000L  // 5 minutes max

        const val ACTION_START_RECORDING = "com.example.nightagent.START_RECORDING"
        const val ACTION_STOP_RECORDING  = "com.example.nightagent.STOP_RECORDING"

        fun start(context: Context) {
            val intent = Intent(context, AudioRecordingService::class.java).apply {
                action = ACTION_START_RECORDING
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, AudioRecordingService::class.java).apply {
                action = ACTION_STOP_RECORDING
            }
            context.startService(intent)
        }
    }
}
