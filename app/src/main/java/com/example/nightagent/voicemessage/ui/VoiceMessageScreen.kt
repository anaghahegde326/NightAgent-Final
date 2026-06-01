package com.example.nightagent.voicemessage.ui

import android.Manifest
import android.os.Build
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.nightagent.ui.theme.*
import com.example.nightagent.voicemessage.audio.RecorderState
import com.example.nightagent.voicemessage.model.MessageStatus
import com.example.nightagent.voicemessage.model.PlaybackState
import com.example.nightagent.voicemessage.model.VoiceMessage
import com.example.nightagent.voicemessage.model.VoiceMessageUiModel
import com.example.nightagent.voicemessage.viewmodel.ChatScreenState
import com.example.nightagent.voicemessage.viewmodel.VoiceMessageViewModel
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberMultiplePermissionsState

@OptIn(ExperimentalPermissionsApi::class, ExperimentalMaterial3Api::class)
@Composable
fun VoiceMessageScreen(
    otherUserId: String = "",
    onBack: () -> Unit = {}
) {
    val vm: VoiceMessageViewModel = viewModel(factory = VoiceMessageViewModel.factory())

    val messages      by vm.messages.collectAsStateWithLifecycle()
    val screenState   by vm.screenState.collectAsStateWithLifecycle()
    val recorderState by vm.recorderState.collectAsStateWithLifecycle()
    val amplitude     by vm.amplitude.collectAsStateWithLifecycle()
    val receiverName  by vm.receiverName.collectAsStateWithLifecycle()

    val listState = rememberLazyListState()

    // ── Permissions ───────────────────────────────────────────────────────────
    val permissionsToRequest = buildList {
        add(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
    val permissionsState = rememberMultiplePermissionsState(permissionsToRequest)
    val hasMicPermission = permissionsState.permissions
        .first { it.permission == Manifest.permission.RECORD_AUDIO }
        .status.isGranted

    LaunchedEffect(Unit) {
        if (!hasMicPermission) permissionsState.launchMultiplePermissionRequest()
    }

    // Auto-scroll to newest message
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1)
    }

    // Error snackbar
    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(screenState) {
        if (screenState is ChatScreenState.Error) {
            snackbarHostState.showSnackbar(
                (screenState as ChatScreenState.Error).message,
                duration = SnackbarDuration.Long
            )
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            VoiceChatTopBar(
                displayName = receiverName.ifBlank { otherUserId.take(20) },
                onBack = onBack
            )
        },
        containerColor = Color(0xFFF0EAF8),
        contentWindowInsets = WindowInsets(0, 0, 0, 0)
    ) { innerPadding ->

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {

            // ── Message list ─────────────────────────────────────────────────
            Box(modifier = Modifier.weight(1f)) {
                when {
                    screenState is ChatScreenState.Loading -> {
                        Column(
                            modifier = Modifier.align(Alignment.Center),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            CircularProgressIndicator(color = Lavender)
                            Spacer(Modifier.height(12.dp))
                            Text(
                                "Connecting…",
                                color = TextSecondary,
                                fontSize = 13.sp
                            )
                        }
                    }
                    screenState is ChatScreenState.Error -> {
                        Column(
                            modifier = Modifier
                                .align(Alignment.Center)
                                .padding(32.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(
                                Icons.Default.ErrorOutline,
                                null,
                                tint = Color(0xFFFF453A),
                                modifier = Modifier.size(48.dp)
                            )
                            Spacer(Modifier.height(12.dp))
                            Text(
                                (screenState as ChatScreenState.Error).message,
                                textAlign = TextAlign.Center,
                                color = TextSecondary,
                                fontSize = 14.sp
                            )
                        }
                    }
                    messages.isEmpty() -> {
                        EmptyConversationHint(modifier = Modifier.align(Alignment.Center))
                    }
                    else -> {
                        LazyColumn(
                            state = listState,
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            items(messages, key = { it.message.messageId }) { uiModel ->
                                VoiceMessageBubble(
                                    uiModel     = uiModel,
                                    onPlayPause = { vm.togglePlayback(uiModel.message) },
                                    onSeek      = { vm.seekTo(it) }
                                )
                            }
                        }
                    }
                }
            }

            // ── Bottom bar ───────────────────────────────────────────────────
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.surface,
                shadowElevation = 8.dp
            ) {
                if (recorderState is RecorderState.Recording) {
                    RecordingBar(
                        recorderState = recorderState,
                        amplitude     = amplitude,
                        onCancel      = { vm.cancelRecording() }
                    )
                } else {
                    MicInputBar(
                        hasMicPermission    = hasMicPermission,
                        isReady             = screenState is ChatScreenState.Ready,
                        onRequestPermission = { permissionsState.launchMultiplePermissionRequest() },
                        onRecordStart       = { vm.startRecording() },
                        onRecordStop        = { vm.stopAndSend() },
                        onRecordCancel      = { vm.cancelRecording() }
                    )
                }
            }
        }
    }
}

// ── Top bar ───────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VoiceChatTopBar(displayName: String, onBack: () -> Unit) {
    TopAppBar(
        title = {
            Column {
                Text("Voice Messages", fontWeight = FontWeight.Bold, fontSize = 17.sp)
                if (displayName.isNotBlank()) {
                    Text(displayName, fontSize = 12.sp, color = TextSecondary)
                }
            }
        },
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(Icons.Default.ArrowBack, "Back", tint = Lavender)
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    )
}

// ── Empty state ───────────────────────────────────────────────────────────────

@Composable
private fun EmptyConversationHint(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            Icons.Default.Mic, null,
            tint = Lavender.copy(alpha = 0.35f),
            modifier = Modifier.size(72.dp)
        )
        Spacer(Modifier.height(16.dp))
        Text(
            "Hold the mic button to record\na voice message",
            textAlign = TextAlign.Center,
            color = TextSecondary,
            fontSize = 14.sp,
            lineHeight = 20.sp
        )
    }
}

// ── Message bubble ────────────────────────────────────────────────────────────

@Composable
fun VoiceMessageBubble(
    uiModel: VoiceMessageUiModel,
    onPlayPause: () -> Unit,
    onSeek: (Long) -> Unit
) {
    val msg   = uiModel.message
    val isOwn = uiModel.isOwnMessage

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isOwn) Arrangement.End else Arrangement.Start
    ) {
        Card(
            modifier = Modifier.widthIn(min = 180.dp, max = 280.dp),
            shape = RoundedCornerShape(
                topStart    = 18.dp, topEnd      = 18.dp,
                bottomStart = if (isOwn) 18.dp else 4.dp,
                bottomEnd   = if (isOwn) 4.dp  else 18.dp
            ),
            colors = CardDefaults.cardColors(
                containerColor = if (isOwn) PurpleStart else MaterialTheme.colorScheme.surface
            ),
            elevation = CardDefaults.cardElevation(2.dp)
        ) {
            Column(modifier = Modifier.padding(10.dp)) {

                // Upload progress bar
                if (uiModel.uploadProgressPercent in 1..99) {
                    LinearProgressIndicator(
                        progress = { uiModel.uploadProgressPercent / 100f },
                        modifier = Modifier.fillMaxWidth().height(2.dp)
                            .clip(RoundedCornerShape(1.dp)),
                        color      = if (isOwn) Color.White else Lavender,
                        trackColor = Color.Transparent
                    )
                    Spacer(Modifier.height(6.dp))
                }

                // Play/Pause + waveform
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    IconButton(
                        onClick = onPlayPause,
                        modifier = Modifier
                            .size(40.dp)
                            .background(
                                if (isOwn) Color.White.copy(0.2f) else Lavender.copy(0.15f),
                                CircleShape
                            )
                    ) {
                        when (uiModel.playbackState) {
                            PlaybackState.LOADING -> CircularProgressIndicator(
                                Modifier.size(20.dp), strokeWidth = 2.dp,
                                color = if (isOwn) Color.White else Lavender
                            )
                            PlaybackState.PLAYING -> Icon(
                                Icons.Default.Pause, "Pause",
                                tint = if (isOwn) Color.White else Lavender,
                                modifier = Modifier.size(20.dp)
                            )
                            else -> Icon(
                                Icons.Default.PlayArrow, "Play",
                                tint = if (isOwn) Color.White else Lavender,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }

                    Spacer(Modifier.width(8.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        WaveformDisplay(
                            isPlaying = uiModel.playbackState == PlaybackState.PLAYING,
                            isOwn     = isOwn,
                            modifier  = Modifier.fillMaxWidth().height(28.dp)
                        )
                        Spacer(Modifier.height(2.dp))

                        val progress = if (msg.durationMs > 0)
                            (uiModel.playbackPositionMs.toFloat() / msg.durationMs).coerceIn(0f, 1f)
                        else 0f

                        Slider(
                            value         = progress,
                            onValueChange = { onSeek((it * msg.durationMs).toLong()) },
                            modifier      = Modifier.fillMaxWidth().height(16.dp),
                            colors        = SliderDefaults.colors(
                                thumbColor         = if (isOwn) Color.White else Lavender,
                                activeTrackColor   = if (isOwn) Color.White.copy(0.8f) else Lavender,
                                inactiveTrackColor = if (isOwn) Color.White.copy(0.3f) else Lavender.copy(0.3f)
                            )
                        )
                    }
                }

                Spacer(Modifier.height(4.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        formatDuration(
                            if (uiModel.playbackState == PlaybackState.PLAYING ||
                                uiModel.playbackState == PlaybackState.PAUSED)
                                uiModel.playbackPositionMs else msg.durationMs
                        ),
                        fontSize = 11.sp,
                        color = if (isOwn) Color.White.copy(0.7f) else TextSecondary
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            formatTimestamp(msg.timestamp),
                            fontSize = 10.sp,
                            color = if (isOwn) Color.White.copy(0.6f) else TextSecondary
                        )
                        if (isOwn) {
                            Spacer(Modifier.width(4.dp))
                            MessageStatusIcon(msg.messageStatus)
                        }
                    }
                }
            }
        }
    }
}

// ── Waveform ──────────────────────────────────────────────────────────────────

@Composable
private fun WaveformDisplay(isPlaying: Boolean, isOwn: Boolean, modifier: Modifier = Modifier) {
    val infiniteTransition = rememberInfiniteTransition(label = "waveform")
    val animOffset by infiniteTransition.animateFloat(
        0f, 1f,
        infiniteRepeatable(tween(800, easing = LinearEasing), RepeatMode.Restart),
        label = "waveOffset"
    )
    val barHeights = remember {
        listOf(0.3f,0.6f,0.9f,0.5f,0.7f,1.0f,0.4f,0.8f,0.6f,0.3f,
               0.7f,0.5f,0.9f,0.4f,0.6f,0.8f,0.3f,0.7f,0.5f,0.9f)
    }
    val barColor = if (isOwn) Color.White.copy(0.8f) else Lavender

    Row(modifier, Arrangement.spacedBy(2.dp), Alignment.CenterVertically) {
        barHeights.forEachIndexed { i, base ->
            val h = if (isPlaying) {
                val wave = kotlin.math.sin((animOffset * 2 * Math.PI + i * 0.5).toFloat())
                (base + wave * 0.2f).coerceIn(0.15f, 1f)
            } else base
            Box(
                Modifier.weight(1f).fillMaxHeight(h)
                    .clip(RoundedCornerShape(2.dp))
                    .background(barColor.copy(if (isPlaying) 1f else 0.5f))
            )
        }
    }
}

// ── Recording bar ─────────────────────────────────────────────────────────────

@Composable
private fun RecordingBar(
    recorderState: RecorderState,
    amplitude: Int,
    onCancel: () -> Unit
) {
    val elapsedMs = (recorderState as? RecorderState.Recording)?.elapsedMs ?: 0L

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        TextButton(onClick = onCancel) {
            Icon(Icons.Default.KeyboardArrowLeft, null, tint = TextSecondary)
            Text("Cancel", color = TextSecondary, fontSize = 14.sp)
        }
        Spacer(Modifier.weight(1f))
        LiveWaveform(amplitude, Modifier.width(80.dp).height(32.dp))
        Spacer(Modifier.width(12.dp))

        val infiniteTransition = rememberInfiniteTransition(label = "dot")
        val dotAlpha by infiniteTransition.animateFloat(
            1f, 0.2f,
            infiniteRepeatable(tween(600), RepeatMode.Reverse),
            label = "dotAlpha"
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(10.dp).background(Color.Red.copy(dotAlpha), CircleShape))
            Spacer(Modifier.width(6.dp))
            Text(
                formatDuration(elapsedMs),
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

// ── Live waveform ─────────────────────────────────────────────────────────────

@Composable
private fun LiveWaveform(amplitude: Int, modifier: Modifier = Modifier) {
    val samples = remember { mutableStateListOf<Float>() }
    LaunchedEffect(amplitude) {
        val n = (amplitude / 32767f).coerceIn(0.05f, 1f)
        if (samples.size >= 20) samples.removeAt(0)
        samples.add(n)
    }
    val display = if (samples.size < 20) List(20 - samples.size) { 0.05f } + samples
                  else samples.toList()
    Row(modifier, Arrangement.spacedBy(2.dp), Alignment.CenterVertically) {
        display.forEach { h ->
            Box(
                Modifier.weight(1f).fillMaxHeight(h)
                    .clip(RoundedCornerShape(2.dp)).background(PurpleStart)
            )
        }
    }
}

// ── Mic input bar ─────────────────────────────────────────────────────────────

@Composable
private fun MicInputBar(
    hasMicPermission: Boolean,
    isReady: Boolean,
    onRequestPermission: () -> Unit,
    onRecordStart: () -> Unit,
    onRecordStop: () -> Unit,
    onRecordCancel: () -> Unit
) {
    var isHolding   by remember { mutableStateOf(false) }
    var dragOffsetX by remember { mutableStateOf(0f) }
    val cancelThresholdPx = -180f

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        if (isHolding) {
            Row(
                modifier = Modifier.align(Alignment.CenterStart),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Default.KeyboardArrowLeft, null,
                    tint = TextSecondary, modifier = Modifier.size(18.dp)
                )
                Text("Slide to cancel", color = TextSecondary, fontSize = 13.sp)
            }
        }

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .background(
                        Brush.radialGradient(
                            when {
                                !isReady      -> listOf(Color.Gray, Color.DarkGray)
                                isHolding     -> listOf(PurpleEnd, PurpleStart)
                                else          -> listOf(Lavender, BlushPink)
                            }
                        ),
                        CircleShape
                    )
                    .pointerInput(hasMicPermission, isReady) {
                        detectTapGestures(
                            onPress = { _ ->
                                when {
                                    !hasMicPermission -> {
                                        onRequestPermission()
                                        return@detectTapGestures
                                    }
                                    !isReady -> return@detectTapGestures
                                }

                                isHolding   = true
                                dragOffsetX = 0f
                                onRecordStart()

                                val released = tryAwaitRelease()
                                isHolding = false

                                if (released && dragOffsetX >= cancelThresholdPx) {
                                    onRecordStop()
                                } else {
                                    onRecordCancel()
                                }
                            }
                        )
                    }
                    .pointerInput(Unit) {
                        awaitPointerEventScope {
                            while (true) {
                                val event  = awaitPointerEvent()
                                val change = event.changes.firstOrNull() ?: continue
                                if (change.pressed && isHolding) {
                                    dragOffsetX = change.position.x - (size.width / 2f)
                                    if (dragOffsetX < cancelThresholdPx) {
                                        isHolding = false
                                        onRecordCancel()
                                    }
                                    change.consume()
                                }
                            }
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    if (isHolding) Icons.Default.Mic else Icons.Default.MicNone,
                    "Hold to record",
                    tint     = Color.White,
                    modifier = Modifier.size(28.dp)
                )
            }

            Spacer(Modifier.height(4.dp))

            Text(
                text = when {
                    !hasMicPermission -> "Tap to allow microphone"
                    !isReady          -> "Connecting…"
                    isHolding         -> "Release to send"
                    else              -> "Hold to record"
                },
                fontSize = 11.sp,
                color = TextSecondary
            )
        }
    }
}

// ── Message status icon ───────────────────────────────────────────────────────

@Composable
private fun MessageStatusIcon(status: MessageStatus) {
    val (icon, tint) = when (status) {
        MessageStatus.SENT      -> Icons.Default.Check   to Color.White.copy(0.6f)
        MessageStatus.DELIVERED -> Icons.Default.DoneAll to Color.White.copy(0.6f)
        MessageStatus.SEEN      -> Icons.Default.DoneAll to Color(0xFF4FC3F7)
    }
    Icon(icon, status.name, tint = tint, modifier = Modifier.size(14.dp))
}

// ── Formatters ────────────────────────────────────────────────────────────────

private fun formatDuration(ms: Long): String {
    val s = ms / 1000
    return "%d:%02d".format(s / 60, s % 60)
}

private fun formatTimestamp(ts: Long): String {
    if (ts == 0L) return ""
    return java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
        .format(java.util.Date(ts))
}
