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
 * All MediaRecorder calls run on Dispatchers.IO — never on the UI thread.
 * Correct call order: setAudioSource → setOutputFormat → setAudioEncoder
 *                     → setAudioEncodingBitRate → setAudioSamplingRate
 *                     → setOutputFile → prepare → start
 */
class VoiceRecorder(private val context: Context) {

    private val _state = MutableStateFlow<RecorderState>(RecorderState.Idle)
    val state: StateFlow<RecorderState> = _state.asStateFlow()

    private val _amplitude = MutableStateFlow(0)
    val amplitude: StateFlow<Int> = _amplitude.asStateFlow()

    // Volatile so reads/writes across IO and Main threads are immediately visible
    @Volatile private var mediaRecorder: MediaRecorder? = null
    @Volatile private var currentFile: File? = null
    @Volatile private var isRecording = false

    private var startTimeMs = 0L
    private var amplitudeJob: Job? = null

    // ── Start ──────────────────────────────────────────────────────────────────

    fun start(scope: CoroutineScope) {
        if (_state.value is RecorderState.Recording) {
            Log.w(TAG, "start() called while already recording — ignored")
            return
        }

        scope.launch(Dispatchers.IO) {
            val file = createOutputFile()
            currentFile = file

            // FIX 1: Use MediaRecorder(context) constructor on all API levels.
            // The no-arg MediaRecorder() constructor is deprecated from API 31.
            // On some OEMs it silently misconfigures audio routing.
            val recorder = MediaRecorder(context)

            try {
                recorder.apply {
                    // Correct call order — changing this order throws IllegalStateException
                    setAudioSource(MediaRecorder.AudioSource.MIC)
                    setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)   // .m4a container
                    setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                    setAudioEncodingBitRate(128_000)
                    setAudioSamplingRate(44_100)
                    setOutputFile(file.absolutePath)
                    prepare()
                    start()
                }

                mediaRecorder = recorder
                isRecording = true
                startTimeMs = System.currentTimeMillis()
                _state.value = RecorderState.Recording(elapsedMs = 0L)
                Log.d(TAG, "Recording started → ${file.absolutePath}")

                // FIX 2: Amplitude polling checks isRecording flag so it stops
                // the moment stop()/cancel() sets it to false, preventing the
                // loop from running after the recorder is released.
                amplitudeJob = scope.launch(Dispatchers.IO) {
                    while (isRecording) {
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
                isRecording = false
                releaseRecorder(recorder)
                file.delete()
                currentFile = null
                mediaRecorder = null
                _state.value = RecorderState.Error("Mic unavailable: ${e.message}")
            }
        }
    }

    // ── Stop ───────────────────────────────────────────────────────────────────

    /**
     * Stop recording and return the result.
     * Safe to call from any thread — runs on Dispatchers.IO internally.
     * Returns null if not currently recording.
     */
    suspend fun stop(): RecordingResult? = withContext(Dispatchers.IO) {
        if (_state.value !is RecorderState.Recording) {
            Log.w(TAG, "stop() called but not recording")
            return@withContext null
        }

        val elapsed = System.currentTimeMillis() - startTimeMs
        if (elapsed < MIN_RECORDING_MS) {
            Log.w(TAG, "Recording too short (${elapsed}ms) — cancelling")
            cancelInternal()
            return@withContext RecordingResult.Failure("Hold longer to record")
        }

        // Signal the amplitude loop to exit before stopping the recorder
        isRecording = false
        amplitudeJob?.cancel()
        amplitudeJob = null

        val durationMs = System.currentTimeMillis() - startTimeMs
        val recorder = mediaRecorder
        val file = currentFile

        // Clear refs before stop/release so concurrent cancel() sees clean state
        mediaRecorder = null
        currentFile = null

        return@withContext try {
            // FIX 3: Only call stop() if recorder is non-null (i.e. start() succeeded).
            // Calling stop() on an un-started recorder throws IllegalStateException.
            if (recorder != null) {
                try { recorder.stop() } catch (e: Exception) {
                    Log.e(TAG, "recorder.stop() threw: ${e.message}")
                    // Even if stop() throws, we must still release to free hardware
                }
                releaseRecorder(recorder)
            }

            _amplitude.value = 0
            _state.value = RecorderState.Idle

            if (file != null && file.exists() && file.length() > 0) {
                Log.d(TAG, "Recording saved: ${file.name} " +
                    "(${file.length()} bytes, ${durationMs}ms)")
                RecordingResult.Success(file = file, durationMs = durationMs)
            } else {
                Log.e(TAG, "Recording file empty or missing: ${file?.absolutePath}")
                file?.delete()
                RecordingResult.Failure("Recording file empty or missing")
            }
        } catch (e: Exception) {
            Log.e(TAG, "stop() unexpected error: ${e.message}", e)
            recorder?.let { releaseRecorder(it) }
            file?.delete()
            _state.value = RecorderState.Idle
            RecordingResult.Failure(e.message ?: "Stop failed")
        }
    }

    // ── Cancel ─────────────────────────────────────────────────────────────────

    fun cancel() {
        isRecording = false
        amplitudeJob?.cancel()
        amplitudeJob = null
        cancelInternal()
        Log.d(TAG, "Recording cancelled")
    }

    private fun cancelInternal() {
        val recorder = mediaRecorder
        val file = currentFile
        mediaRecorder = null
        currentFile = null

        // FIX 3 (cancel path): Wrap stop() in its own try/catch so that
        // release() always runs even if stop() throws IllegalStateException
        // (happens when the recorder was never fully started).
        if (recorder != null) {
            try { recorder.stop() } catch (_: Exception) { }
            releaseRecorder(recorder)
        }

        file?.delete()
        _amplitude.value = 0
        _state.value = RecorderState.Idle
    }

    fun release() = cancel()

    // ── Helpers ────────────────────────────────────────────────────────────────

    private fun releaseRecorder(recorder: MediaRecorder) {
        try {
            recorder.release()
        } catch (e: Exception) {
            Log.e(TAG, "recorder.release() threw: ${e.message}")
        }
    }

    private fun createOutputFile(): File {
        val dir = File(context.cacheDir, "voice_messages").also { it.mkdirs() }
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
        return File(dir, "vm_$ts.m4a")   // .m4a = MPEG-4 Audio container
    }

    companion object {
        private const val TAG = "VoiceRecorder"
        private const val MIN_RECORDING_MS = 500L
    }
}

// ── State / Result ─────────────────────────────────────────────────────────────

sealed class RecorderState {
    object Idle : RecorderState()
    data class Recording(val elapsedMs: Long) : RecorderState()
    data class Error(val message: String) : RecorderState()
}

sealed class RecordingResult {
    data class Success(val file: File, val durationMs: Long) : RecordingResult()
    data class Failure(val reason: String) : RecordingResult()
}
