package com.example.nightagent.evidence

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.*
import androidx.camera.video.VideoCapture
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class CameraCaptureManager(private val context: Context) {

    private val TAG = "CameraCaptureManager"

    // Background executor for CameraX operations; main executor for UI callbacks.
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private val mainExecutor = ContextCompat.getMainExecutor(context)

    private var imageCapture: ImageCapture? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var recording: Recording? = null
    private var cameraProvider: ProcessCameraProvider? = null

    // ── Init ──────────────────────────────────────────────────────────────────

    fun initialize(
        lifecycleOwner: LifecycleOwner,
        surfaceProvider: Preview.SurfaceProvider,
        onReady: () -> Unit
    ) {
        val providerFuture = ProcessCameraProvider.getInstance(context)
        providerFuture.addListener({
            try {
                // Fix: timeout added to prevent indefinite blocking
                cameraProvider = providerFuture.get(5, TimeUnit.SECONDS)
                bindUseCases(lifecycleOwner, surfaceProvider)
                onReady()
            } catch (e: Exception) {
                Log.e(TAG, "Camera init failed: ${e.javaClass.simpleName}")
            }
        }, mainExecutor)
    }

    private fun bindUseCases(
        lifecycleOwner: LifecycleOwner,
        surfaceProvider: Preview.SurfaceProvider
    ) {
        val provider = cameraProvider ?: return

        val preview = Preview.Builder().build().also {
            it.setSurfaceProvider(surfaceProvider)
        }

        imageCapture = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .build()

        val recorder = Recorder.Builder()
            .setQualitySelector(QualitySelector.from(Quality.SD))
            .build()
        videoCapture = VideoCapture.withOutput(recorder)

        try {
            provider.unbindAll()
            provider.bindToLifecycle(
                lifecycleOwner,
                CameraSelector.DEFAULT_BACK_CAMERA,
                preview,
                imageCapture,
                videoCapture
            )
            Log.d(TAG, "Use cases bound successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Use case binding failed: ${e.javaClass.simpleName}")
        }
    }

    // ── Photo capture ─────────────────────────────────────────────────────────

    fun capturePhoto(sosId: String, onResult: (File?) -> Unit) {
        val ic = imageCapture ?: run {
            Log.w(TAG, "capturePhoto: imageCapture is null")
            onResult(null)
            return
        }
        val file = createOutputFile(sosId, "photo", "jpg")
        val options = ImageCapture.OutputFileOptions.Builder(file).build()

        ic.takePicture(options, executor, object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                Log.d(TAG, "Photo saved: ${file.name}")
                mainExecutor.execute { onResult(file) }
            }
            override fun onError(exc: ImageCaptureException) {
                Log.e(TAG, "Photo capture failed: ${exc.javaClass.simpleName} code=${exc.imageCaptureError}")
                mainExecutor.execute { onResult(null) }
            }
        })
    }

    // ── Video recording ───────────────────────────────────────────────────────

    /**
     * Starts video + audio recording.
     *
     * @param onStarted  called on the main thread when CameraX confirms recording is active.
     * @param onFinalized called on the main thread when the file is fully written.
     *                    [uri] is non-null on success; [error] is non-null on failure.
     */
    @androidx.annotation.RequiresPermission(android.Manifest.permission.RECORD_AUDIO)
    fun startVideoRecording(
        sosId: String,
        onStarted: () -> Unit = {},
        onFinalized: (uri: Uri?, error: String?) -> Unit = { _, _ -> }
    ) {
        val vc = videoCapture ?: run {
            Log.w(TAG, "startVideoRecording: videoCapture is null — camera not ready")
            onFinalized(null, "Camera not ready")
            return
        }

        if (recording != null) {
            Log.w(TAG, "startVideoRecording: already recording, ignoring call")
            return
        }

        val file = createOutputFile(sosId, "video", "mp4")
        Log.d(TAG, "startVideoRecording: preparing file=${file.name}")

        val options = FileOutputOptions.Builder(file).build()

        // Fix: callbacks run on executor (background thread).
        // We post UI-state changes back to the main thread via mainExecutor.
        recording = vc.output
            .prepareRecording(context, options)
            .withAudioEnabled()
            .start(executor) { event ->
                when (event) {
                    is VideoRecordEvent.Start -> {
                        Log.d(TAG, "VideoRecordEvent.Start — file=${file.name}")
                        mainExecutor.execute { onStarted() }
                    }
                    is VideoRecordEvent.Status -> {
                        val stats = event.recordingStats
                        Log.d(TAG, "VideoRecordEvent.Status — " +
                                "duration=${stats.recordedDurationNanos / 1_000_000}ms " +
                                "bytes=${stats.numBytesRecorded}")
                    }
                    is VideoRecordEvent.Finalize -> {
                        if (event.hasError()) {
                            val msg = "code=${event.error} cause=${event.cause?.javaClass?.simpleName}"
                            Log.e(TAG, "VideoRecordEvent.Finalize ERROR — $msg")
                            mainExecutor.execute { onFinalized(null, msg) }
                        } else {
                            val uri = event.outputResults.outputUri
                            Log.d(TAG, "VideoRecordEvent.Finalize OK — uri=$uri file=${file.name}")
                            mainExecutor.execute { onFinalized(uri, null) }
                        }
                        recording = null
                    }
                    else -> Unit
                }
            }
        Log.d(TAG, "startVideoRecording: Recording object created, waiting for Start event")
    }

    fun stopVideoRecording() {
        val r = recording
        if (r == null) {
            Log.w(TAG, "stopVideoRecording: no active recording")
            return
        }
        Log.d(TAG, "stopVideoRecording: calling stop()")
        r.stop()
        // recording is set to null inside the Finalize callback, not here,
        // so we don't null it prematurely before the file is flushed.
    }

    // ── Cleanup ───────────────────────────────────────────────────────────────

    fun release() {
        recording?.stop()
        recording = null
        cameraProvider?.unbindAll()
        executor.shutdown()
        Log.d(TAG, "CameraCaptureManager released")
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun createOutputFile(sosId: String, type: String, ext: String): File {
        val dir = File(context.getExternalFilesDir(null), "Evidence/$sosId/$type").also { it.mkdirs() }
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        return File(dir, "${type}_$ts.$ext")
    }
}
