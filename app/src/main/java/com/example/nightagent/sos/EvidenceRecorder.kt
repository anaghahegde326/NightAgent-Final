package com.example.nightagent.sos

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaRecorder
import android.os.Build
import android.util.Log
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Records audio evidence to the app's private external files directory.
 *
 * Root cause of the previous crash:
 *   MediaRecorder.setProfile() calls setOutputFormat() internally.
 *   setOutputFormat() requires the recorder to already be in the state
 *   AFTER setAudioSource() / setVideoSource() — not before.
 *   The old code called setProfile() as the very first method, which put
 *   the recorder in state 1 (Initial), causing:
 *     "setOutputFormat called in an invalid state: 1"  → IllegalStateException → crash.
 *
 * Fix: use explicit audio-only setup with the correct MediaRecorder call order:
 *   1. setAudioSource()
 *   2. setOutputFormat()
 *   3. setAudioEncoder()
 *   4. setAudioEncodingBitRate() / setAudioSamplingRate()
 *   5. setOutputFile()
 *   6. prepare()
 *   7. start()
 *
 * Files are saved to getExternalFilesDir("Evidence") — no WRITE_EXTERNAL_STORAGE
 * permission needed on Android 10+, and files are private to the app.
 */
object EvidenceRecorder {

    private const val TAG = "EvidenceRecorder"

    private var mediaRecorder: MediaRecorder? = null
    private var lastRecordedFile: File? = null

    /** Exposed to the UI so the button can toggle between Start/Stop. */
    var isRecording = false
        private set

    // ── Start ─────────────────────────────────────────────────────────────────

    fun startRecording(context: Context, lifecycleOwner: LifecycleOwner) {
        if (isRecording) return

        // Guard: RECORD_AUDIO must be granted
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Toast.makeText(context, "Microphone permission not granted", Toast.LENGTH_SHORT).show()
            return
        }

        val file = createOutputFile(context)
        lastRecordedFile = file

        // Build MediaRecorder with the correct call order
        @Suppress("DEPRECATION")
        val recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(context)
        } else {
            MediaRecorder()
        }

        try {
            recorder.apply {
                // Step 1 — source (must come before setOutputFormat)
                setAudioSource(MediaRecorder.AudioSource.MIC)

                // Step 2 — container format
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)

                // Step 3 — encoder
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)

                // Step 4 — quality settings (64 kbps AAC, 44.1 kHz)
                setAudioEncodingBitRate(64_000)
                setAudioSamplingRate(44_100)

                // Step 5 — output file
                setOutputFile(file.absolutePath)

                // Step 6 — prepare (allocates resources)
                prepare()

                // Step 7 — start
                start()
            }

            mediaRecorder = recorder
            isRecording = true
            Toast.makeText(context, "Recording started", Toast.LENGTH_SHORT).show()
            Log.d(TAG, "Recording started → ${file.name}")

        } catch (e: Exception) {
            Log.e(TAG, "startRecording failed: ${e.message}", e)
            try { recorder.release() } catch (_: Exception) { }
            file.delete()
            lastRecordedFile = null
            isRecording = false
            Toast.makeText(context, "Recording failed: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    // ── Stop ──────────────────────────────────────────────────────────────────

    fun stopRecording(context: Context) {
        if (!isRecording) return

        try {
            mediaRecorder?.stop()
        } catch (e: Exception) {
            Log.e(TAG, "stop() failed: ${e.message}", e)
        }

        try {
            mediaRecorder?.release()
        } catch (_: Exception) { }

        mediaRecorder = null
        isRecording = false

        lastRecordedFile?.let { file ->
            if (file.exists() && file.length() > 0) {
                Toast.makeText(
                    context,
                    "Saved: ${file.name}",
                    Toast.LENGTH_SHORT
                ).show()
                Log.d(TAG, "Recording saved → ${file.absolutePath} (${file.length()} bytes)")
            } else {
                Toast.makeText(context, "Recording file is empty", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    fun getLastRecordedFile(): File? = lastRecordedFile

    /**
     * Save to app-private external storage — no WRITE_EXTERNAL_STORAGE needed
     * on Android 10+ and files survive uninstall for evidence purposes.
     */
    private fun createOutputFile(context: Context): File {
        val dir = File(context.getExternalFilesDir(null), "Evidence").also { it.mkdirs() }
        val ts  = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        return File(dir, "evidence_$ts.m4a")
    }
}
