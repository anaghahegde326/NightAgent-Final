package com.example.nightagent.voicemessage.audio

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Thread-safe audio recorder using MediaRecorder.
 *
 * CRITICAL fixes vs previous version:
 *
 * 1. Output format changed from AAC_ADTS → MPEG_4
 *    AAC_ADTS is a raw bitstream — it does NOT support setOutputFile(path).
 *    Calling prepare() with AAC_ADTS + a file path throws IllegalStateException
 *    on most devices, crashing the app on button touch.
 *    MPEG_4 (.m4a) is the correct container for AAC on Android.
 *
 * 2. All MediaRecorder calls run on Dispatchers.IO, never on the main/UI thread.
 *    The previous version called start() synchronously from awaitPointerEventScope
 *    (composition thread), which caused ANR on some devices.
 *
 * 3. Correct MediaRecorder call order enforced:
 *    setAudioSource → setOutputFormat → setAudioEncoder →
 *    setAudioEncodingBitRate → setAudioSamplingRate → setOutputFile →
 *    prepare → start
 */
class VoiceRecorder(private val context: Context) {

    private val _state = MutableStateFlow<RecorderState>(RecorderState.Idle)
    val state: StateFlow<RecorderState> = _state.asStateFlow()

    private val _amplitude = MutableStateFlow(0)
    val amplitude: StateFlow<Int> = _amplitude.asStateFlow()

    private var mediaRecorder: MediaRecorder? = null
    private var currentFile: File? = null
    private var startTimeMs = 0L
    private var amplitudeJob: Job? = null

    // ── Start ─────────────────────────────────────────────────────────────────

    /**
     * Start recording on Dispatchers.IO.
     * Safe to call from any thread including the UI/composition thread.
     */
    fun start(scope: CoroutineScope) {
        if (_state.value is RecorderState.Recording) return

        scope.launch(Dispatchers.IO) {
            val file = createOutputFile()
            currentFile = file

            @Suppress("DEPRECATION")
            val recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(context)
            } else {
                MediaRecorder()
            }

            try {
                recorder.apply {
                    // ── Correct call order ────────────────────────────────────
                    // 1. Source — must be first
                    setAudioSource(MediaRecorder.AudioSource.MIC)

                    // 2. Container — MPEG_4 supports setOutputFile(path)
                    //    AAC_ADTS does NOT → was the crash cause
                    setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)

                    // 3. Encoder — after setOutputFormat
                    setAudioEncoder(MediaRecorder.AudioEncoder.AAC)

                    // 4. Quality — 128 kbps, 44.1 kHz
                    setAudioEncodingBitRate(128_000)
                    setAudioSamplingRate(44_100)

                    // 5. Output file — after encoder settings
                    setOutputFile(file.absolutePath)

                    // 6. Prepare — allocates hardware resources
                    prepare()

                    // 7. Start — begins recording
                    start()
                }

                mediaRecorder = recorder
                startTimeMs = System.currentTimeMillis()
                _state.value = RecorderState.Recording(elapsedMs = 0L)
                Log.d(TAG, "Recording started → ${file.name}")

                // Amplitude polling on IO thread
                amplitudeJob = scope.launch(Dispatchers.IO) {
                    while (true) {
                        delay(100)
                        val amp = try {
                            mediaRecorder?.maxAmplitude ?: 0
                        } catch (_: Exception) { 0 }
                        _amplitude.value = amp
                        val elapsed = System.currentTimeMillis() - startTimeMs
                        _state.value = RecorderState.Recording(elapsedMs = elapsed)
                    }
                }

            } catch (e: Exception) {
                Log.e(TAG, "start() failed: ${e.message}", e)
                try { recorder.release() } catch (_: Exception) { }
                file.delete()
                currentFile = null
                mediaRecorder = null
                _state.value = RecorderState.Error("Mic unavailable: ${e.message}")
            }
        }
    }

    // ── Stop ──────────────────────────────────────────────────────────────────

    /**
     * Stop recording and return the result.
     * Runs on Dispatchers.IO — safe to call from any thread.
     * Returns null if not currently recording.
     */
    suspend fun stop(): RecordingResult? = withContext(Dispatchers.IO) {
        if (_state.value !is RecorderState.Recording) return@withContext null

        val elapsed = System.currentTimeMillis() - startTimeMs
        if (elapsed < 500L) {
            // Too short — cancel cleanly instead of crashing
            cancelInternal()
            return@withContext RecordingResult.Failure("Hold longer to record")
        }

        amplitudeJob?.cancel()
        amplitudeJob = null

        val durationMs = System.currentTimeMillis() - startTimeMs

        return@withContext try {
            mediaRecorder?.stop()
            mediaRecorder?.release()
            mediaRecorder = null
            _amplitude.value = 0
            _state.value = RecorderState.Idle

            val file = currentFile
            currentFile = null

            if (file != null && file.exists() && file.length() > 0) {
                Log.d(TAG, "Recording saved: ${file.name} (${file.length()} bytes, ${durationMs}ms)")
                RecordingResult.Success(file = file, durationMs = durationMs)
            } else {
                file?.delete()
                RecordingResult.Failure("Recording file empty or missing")
            }
        } catch (e: Exception) {
            Log.e(TAG, "stop() failed: ${e.message}", e)
            mediaRecorder?.release()
            mediaRecorder = null
            currentFile?.delete()
            currentFile = null
            _state.value = RecorderState.Idle
            RecordingResult.Failure(e.message ?: "Stop failed")
        }
    }

    // ── Cancel ────────────────────────────────────────────────────────────────

    fun cancel() {
        amplitudeJob?.cancel()
        amplitudeJob = null
        cancelInternal()
    }

    private fun cancelInternal() {
        try { mediaRecorder?.stop() } catch (_: Exception) { }
        try { mediaRecorder?.release() } catch (_: Exception) { }
        mediaRecorder = null
        currentFile?.delete()
        currentFile = null
        _amplitude.value = 0
        _state.value = RecorderState.Idle
    }

    fun release() = cancel()

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun createOutputFile(): File {
        val dir = File(context.cacheDir, "voice_messages").also { it.mkdirs() }
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
        // .m4a = MPEG-4 Audio — correct extension for MPEG_4 + AAC
        return File(dir, "vm_$ts.m4a")
    }

    companion object {
        private const val TAG = "VoiceRecorder"
    }
}

// ── State / Result ────────────────────────────────────────────────────────────

sealed class RecorderState {
    object Idle : RecorderState()
    data class Recording(val elapsedMs: Long) : RecorderState()
    data class Error(val message: String) : RecorderState()
}

sealed class RecordingResult {
    data class Success(val file: File, val durationMs: Long) : RecordingResult()
    data class Failure(val reason: String) : RecordingResult()
}
