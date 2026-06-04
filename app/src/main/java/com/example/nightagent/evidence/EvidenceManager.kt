package com.example.nightagent.evidence

import android.content.Context
import android.location.Location
import android.media.MediaRecorder
import android.os.BatteryManager
import android.os.Build
import android.util.Log
import java.io.File
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class EvidenceMetadata(
    val userId: String,
    val sosId: String,
    val timestamp: Long,
    val latitude: Double?,
    val longitude: Double?,
    val speed: Float?,
    val batteryLevel: Int,
    val deviceModel: String,
    val networkStatus: String,
    val audioFiles: List<String> = emptyList(),
    val photoFiles: List<String> = emptyList(),
    val videoFile: String? = null
)

/**
 * Manages chunked audio recording for evidence collection.
 * Photos and video are handled by [CameraCaptureManager].
 * Upload is handled by [EvidenceUploadWorker].
 */
class EvidenceManager(private val context: Context) {

    private val TAG = "EvidenceManager"

    private var mediaRecorder: MediaRecorder? = null
    private var isRecording = false
    private val audioFiles = mutableListOf<File>()
    private var chunkIndex = 0

    private var chunkTimer: java.util.Timer? = null

    // ── Audio recording ───────────────────────────────────────────────────────

    fun startChunkedAudioRecording() {
        if (isRecording) return
        startNewChunk()

        // Rotate chunk every 30 seconds
        chunkTimer = java.util.Timer().apply {
            scheduleAtFixedRate(object : java.util.TimerTask() {
                override fun run() { rotateChunk() }
            }, CHUNK_INTERVAL_MS, CHUNK_INTERVAL_MS)
        }
    }

    fun stopAudioRecording() {
        chunkTimer?.cancel()
        chunkTimer = null
        stopCurrentRecorder()
        isRecording = false
        Log.d(TAG, "Audio recording stopped. Chunks: ${audioFiles.size}")
    }

    private fun startNewChunk() {
        stopCurrentRecorder()
        val file = createEvidenceFile("audio_${chunkIndex++}", "m4a")
        audioFiles.add(file)

        @Suppress("DEPRECATION")
        mediaRecorder = (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(context)
        } else {
            MediaRecorder()
        }).apply {
            try {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioEncodingBitRate(64_000)
                setAudioSamplingRate(44_100)
                setOutputFile(file.absolutePath)
                prepare()
                start()
                isRecording = true
                Log.d(TAG, "Chunk started: ${file.name}")
            } catch (e: Exception) {
                Log.e(TAG, "Chunk start failed: ${e.message}")
                release()
            }
        }
    }

    private fun rotateChunk() {
        try {
            startNewChunk()
        } catch (e: Exception) {
            Log.e(TAG, "Chunk rotate failed: ${e.message}")
        }
    }

    private fun stopCurrentRecorder() {
        try { mediaRecorder?.stop() } catch (_: Exception) {}
        try { mediaRecorder?.release() } catch (_: Exception) {}
        mediaRecorder = null
    }

    fun getAudioFiles(): List<File> = audioFiles.filter { it.exists() && it.length() > 0 }

    // ── Metadata ──────────────────────────────────────────────────────────────

    fun buildMetadata(
        userId: String,
        sosId: String,
        location: Location?,
        photoFiles: List<String> = emptyList(),
        videoFile: String? = null
    ): EvidenceMetadata {
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val batteryLevel = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val connectivity = context.getSystemService(Context.CONNECTIVITY_SERVICE)
            as android.net.ConnectivityManager
        val networkStatus = if (connectivity.activeNetworkInfo?.isConnected == true) "online" else "offline"

        return EvidenceMetadata(
            userId        = userId,
            sosId         = sosId,
            timestamp     = System.currentTimeMillis(),
            latitude      = location?.latitude,
            longitude     = location?.longitude,
            speed         = location?.speed,
            batteryLevel  = batteryLevel,
            deviceModel   = "${Build.MANUFACTURER} ${Build.MODEL}",
            networkStatus = networkStatus,
            audioFiles    = getAudioFiles().map { it.name },
            photoFiles    = photoFiles,
            videoFile     = videoFile
        )
    }

    // ── Integrity ─────────────────────────────────────────────────────────────

    fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(8192)
            var read: Int
            while (input.read(buffer).also { read = it } != -1) {
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    fun createEvidenceDir(sosId: String): File =
        File(context.getExternalFilesDir(null), "Evidence/$sosId").also { it.mkdirs() }

    private fun createEvidenceFile(name: String, ext: String): File {
        val dir = File(context.getExternalFilesDir(null), "Evidence/temp").also { it.mkdirs() }
        val ts  = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        return File(dir, "${name}_$ts.$ext")
    }

    companion object {
        private const val CHUNK_INTERVAL_MS = 30_000L
    }
}
