package com.example.nightagent.evidence

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.example.nightagent.ui.theme.SuccessGreen
import java.io.File

private const val TAG = "EVIDENCE"

@Composable
fun EvidenceScreen() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var isCapturing by remember { mutableStateOf(false) }
    var sosId by remember { mutableStateOf("") }
    val uploadState = rememberWorkManagerState(context)

    val cameraManager = remember { CameraCaptureManager(context) }
    // Fix 3: isVideoRecording is driven by CameraX callbacks, not just button clicks.
    // CameraCaptureManager exposes it as a lambda so the UI state is always in sync.
    var isVideoRecording by remember { mutableStateOf(false) }

    DisposableEffect(Unit) { onDispose { cameraManager.release() } }

    // Fix 1: Re-check camera permission on every resume, not just on first composition.
    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, android.Manifest.permission.CAMERA) ==
                    PackageManager.PERMISSION_GRANTED
        )
    }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                hasCameraPermission = ContextCompat.checkSelfPermission(
                    context, android.Manifest.permission.CAMERA
                ) == PackageManager.PERMISSION_GRANTED
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val hasAudioPermission = remember(hasCameraPermission) {
        ContextCompat.checkSelfPermission(context, android.Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 20.dp)
    ) {
        item {
            Spacer(modifier = Modifier.height(24.dp))
            Text(
                "Evidence Center", fontSize = 28.sp, fontWeight = FontWeight.ExtraBold,
                color = MaterialTheme.colorScheme.onBackground
            )
            Spacer(modifier = Modifier.height(20.dp))
        }

        // ── Status row ────────────────────────────────────────────────────────
        item {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StatusCard(
                    modifier = Modifier.weight(1f),
                    icon = Icons.Default.Mic,
                    label = "Audio",
                    value = if (isCapturing) "Active" else "Idle",
                    color = if (isCapturing) Color.Red else Color.Gray
                )
                StatusCard(
                    modifier = Modifier.weight(1f),
                    icon = Icons.Default.CloudUpload,
                    label = "Upload",
                    value = uploadState,
                    color = if (uploadState == "Done") SuccessGreen else Color(0xFFFF9800)
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StatusCard(
                    modifier = Modifier.weight(1f),
                    icon = Icons.Default.Storage,
                    label = "Stored",
                    value = getEvidenceSize(context),
                    color = MaterialTheme.colorScheme.primary
                )
                StatusCard(
                    modifier = Modifier.weight(1f),
                    icon = Icons.Default.Videocam,
                    label = "Video",
                    // Fix 3: driven by callback-updated state
                    value = if (isVideoRecording) "Recording…" else "Idle",
                    color = if (isVideoRecording) Color.Red else Color.Gray
                )
            }
            Spacer(modifier = Modifier.height(24.dp))
        }

        // ── CameraX preview ───────────────────────────────────────────────────
        if (hasCameraPermission) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    AndroidView(
                        factory = { ctx: Context ->
                            PreviewView(ctx).also { previewView ->
                                cameraManager.initialize(lifecycleOwner, previewView.surfaceProvider) {
                                    Log.d(TAG, "Camera ready")
                                }
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(240.dp)
                    )
                }
                Spacer(modifier = Modifier.height(12.dp))
            }

            // ── Camera controls ───────────────────────────────────────────────
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // Fix 4: auto-generate sosId instead of silently blocking the button
                    Button(
                        onClick = {
                            if (sosId.isBlank()) sosId = System.currentTimeMillis().toString()
                            Log.d(TAG, "Photo button clicked, sosId=$sosId")
                            cameraManager.capturePhoto(sosId) { file ->
                                Log.d(TAG, if (file != null) "Photo saved: ${file.absolutePath}" else "Photo failed")
                            }
                        },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(Icons.Default.Camera, contentDescription = null)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Photo")
                    }

                    // Fix 2 + Fix 3: check RECORD_AUDIO; state updated via callback
                    Button(
                        onClick = {
                            Log.d(TAG, "Record button clicked, isVideoRecording=$isVideoRecording")
                            if (isVideoRecording) {
                                cameraManager.stopVideoRecording()
                                Log.d(TAG, "stopVideoRecording called")
                                // isVideoRecording will be set false by the Finalize callback
                            } else {
                                if (!hasAudioPermission) {
                                    Log.w(TAG, "RECORD_AUDIO not granted — cannot record video with audio")
                                    return@Button
                                }
                                if (sosId.isBlank()) sosId = System.currentTimeMillis().toString()
                                Log.d(TAG, "startVideoRecording called, sosId=$sosId")
                                cameraManager.startVideoRecording(
                                    sosId = sosId,
                                    onStarted = {
                                        isVideoRecording = true
                                        Log.d(TAG, "Video recording STARTED")
                                    },
                                    onFinalized = { uri, error ->
                                        isVideoRecording = false
                                        if (error != null) {
                                            Log.e(TAG, "Video recording error: $error")
                                        } else {
                                            Log.d(TAG, "Video saved: $uri")
                                        }
                                    }
                                )
                            }
                        },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isVideoRecording) Color.Red
                            else MaterialTheme.colorScheme.primary
                        )
                    ) {
                        Icon(
                            if (isVideoRecording) Icons.Default.Stop else Icons.Default.Videocam,
                            contentDescription = null
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(if (isVideoRecording) "Stop" else "Record")
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
            }
        } else {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(120.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            "Camera permission required.\nGrant it in Settings and return here.",
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                            fontSize = 14.sp,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
            }
        }

        // ── Start/stop capture session ────────────────────────────────────────
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Text("Manual Evidence Capture", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    Spacer(modifier = Modifier.height(12.dp))
                    Button(
                        onClick = {
                            if (isCapturing) {
                                Log.d(TAG, "Stop Capture clicked")
                                if (isVideoRecording) {
                                    cameraManager.stopVideoRecording()
                                }
                                EvidenceCaptureService.stop(context)
                                isCapturing = false
                                sosId = ""
                                Log.d(TAG, "Audio recording stopped")
                            } else {
                                sosId = System.currentTimeMillis().toString()
                                Log.d(TAG, "Start Capture clicked, sosId=$sosId")
                                EvidenceCaptureService.start(context, sosId)
                                isCapturing = true
                                Log.d(TAG, "Audio recording started")
                            }
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isCapturing) Color.Red
                            else MaterialTheme.colorScheme.primary
                        ),
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(
                            if (isCapturing) Icons.Default.Stop else Icons.Default.FiberManualRecord,
                            contentDescription = null
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            if (isCapturing) "Stop Capture" else "Start Capture",
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(24.dp))
        }

        // ── Timeline ──────────────────────────────────────────────────────────
        item {
            Text(
                "SOS Timeline", fontSize = 18.sp, fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground
            )
            Spacer(modifier = Modifier.height(12.dp))
        }

        item { TimelineEvent("SOS Triggered", Icons.Default.Warning, Color.Red, "Today") }
        item { TimelineEvent("Audio Recording Start", Icons.Default.Mic, Color(0xFFFF9800), "Today") }
        item { TimelineEvent("Photo Captured", Icons.Default.Camera, SuccessGreen, "Today") }
        item {
            TimelineEvent(
                "Upload Queued", Icons.Default.CloudUpload,
                MaterialTheme.colorScheme.primary, "Today"
            )
        }

        item { Spacer(modifier = Modifier.height(24.dp)) }
    }
}

// ── Composable helpers ────────────────────────────────────────────────────────

@Composable
private fun StatusCard(
    modifier: Modifier,
    icon: ImageVector,
    label: String,
    value: String,
    color: Color
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .background(color.copy(alpha = 0.15f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(22.dp))
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(label, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
            Text(value, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = color)
        }
    }
}

@Composable
private fun TimelineEvent(title: String, icon: ImageVector, color: Color, time: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .background(color.copy(alpha = 0.15f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(18.dp))
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                title, fontSize = 14.sp, fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onBackground
            )
            Text(
                time, fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f)
            )
        }
    }
}

// ── WorkManager state observer ────────────────────────────────────────────────

// Fix 5: observeForever leaked the observer. Use observeAsState pattern with
// DisposableEffect so the observer is removed when the composable leaves composition.
@Composable
private fun rememberWorkManagerState(context: Context): String {
    var state by remember { mutableStateOf("Idle") }
    DisposableEffect(context) {
        val liveData = WorkManager.getInstance(context)
            .getWorkInfosByTagLiveData("evidence_upload")
        val observer = androidx.lifecycle.Observer<List<WorkInfo>> { infos ->
            state = when {
                infos.isNullOrEmpty() -> "Idle"
                infos.any { it.state == WorkInfo.State.RUNNING } -> "Uploading…"
                infos.any { it.state == WorkInfo.State.SUCCEEDED } -> "Done"
                infos.any { it.state == WorkInfo.State.FAILED } -> "Failed"
                else -> "Queued"
            }
        }
        liveData.observeForever(observer)
        onDispose { liveData.removeObserver(observer) }
    }
    return state
}

private fun getEvidenceSize(context: Context): String {
    val dir = File(context.getExternalFilesDir(null), "Evidence")
    if (!dir.exists()) return "0 KB"
    val bytes = dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    return when {
        bytes >= 1_048_576 -> "%.1f MB".format(bytes / 1_048_576f)
        bytes >= 1024 -> "%.0f KB".format(bytes / 1024f)
        else -> "$bytes B"
    }
}
