package com.example.nightagent.streaming

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel

// ── Victim: SOS Streaming Screen ──────────────────────────────────────────────

@Composable
fun VictimStreamScreen(
    guardianUid: String,
    callViewModel: CallViewModel = viewModel(),
    onStopSOS: () -> Unit
) {
    val context = LocalContext.current
    val uiState by callViewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(guardianUid) {
        if (uiState.streamState == StreamState.IDLE) {
            callViewModel.startSOSStream(context, guardianUid)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF1A0A0A))
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            "🚨 SOS ACTIVE",
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            color = Color.Red
        )

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            PulsingCircle(active = uiState.streamState == StreamState.CONNECTED)

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = when (uiState.streamState) {
                    StreamState.CONNECTING -> "Connecting to guardian…"
                    StreamState.CONNECTED  -> "Guardian is listening"
                    StreamState.ERROR      -> "Connection lost — retrying…"
                    StreamState.ENDED      -> "Stream ended"
                    else -> ""
                },
                color = Color.White,
                fontSize = 16.sp
            )

            uiState.error?.let {
                Spacer(modifier = Modifier.height(8.dp))
                Text(it, color = Color.Red.copy(alpha = 0.8f), fontSize = 12.sp)
            }
        }

        Button(
            onClick = {
                callViewModel.stopSOSStream(context)
                onStopSOS()
            },
            colors = ButtonDefaults.buttonColors(containerColor = Color.Red),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
        ) {
            Icon(Icons.Default.Stop, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text("Stop SOS & Streaming", fontWeight = FontWeight.Bold)
        }
    }
}

// ── Guardian: Listening Screen ─────────────────────────────────────────────────

@Composable
fun GuardianListenScreen(
    callId: String,
    callViewModel: CallViewModel = viewModel(),
    onLeave: () -> Unit
) {
    val context = LocalContext.current
    val uiState by callViewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(callId) {
        callViewModel.joinAsGuardian(context, callId)
    }

    // Auto-navigate if victim ended stream
    LaunchedEffect(uiState.streamState) {
        if (uiState.streamState == StreamState.ENDED) onLeave()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0A1A0A))
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Text("Guardian Mode", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Color.White)

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            PulsingCircle(active = uiState.streamState == StreamState.CONNECTED, color = Color.Green)

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = when (uiState.streamState) {
                    StreamState.CONNECTING -> "Joining audio session…"
                    StreamState.CONNECTED  -> "🔴 Live — Listening"
                    StreamState.ERROR      -> "Connection issue"
                    StreamState.ENDED      -> "Session ended"
                    else -> ""
                },
                color = Color.White,
                fontSize = 16.sp
            )

            if (uiState.streamState == StreamState.CONNECTED) {
                Spacer(modifier = Modifier.height(24.dp))
                AudioLevelIndicator()
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedButton(
                onClick = { callViewModel.toggleMute() },
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(16.dp)
            ) {
                Icon(
                    if (uiState.isMuted) Icons.Default.VolumeOff else Icons.Default.VolumeUp,
                    contentDescription = null,
                    tint = Color.White
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(if (uiState.isMuted) "Unmute" else "Mute", color = Color.White)
            }

            Button(
                onClick = {
                    callViewModel.leaveCall()
                    onLeave()
                },
                colors = ButtonDefaults.buttonColors(containerColor = Color.Red),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.weight(1f)
            ) {
                Icon(Icons.Default.CallEnd, contentDescription = null)
                Spacer(modifier = Modifier.width(6.dp))
                Text("Leave")
            }
        }
    }
}

// ── Shared UI components ───────────────────────────────────────────────────────

@Composable
private fun PulsingCircle(active: Boolean, color: Color = Color.Red) {
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val scale by infiniteTransition.animateFloat(
        initialValue = 0.85f,
        targetValue  = if (active) 1.15f else 0.85f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = EaseInOut),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse_scale"
    )
    Box(
        modifier = Modifier
            .size(120.dp)
            .scale(scale)
            .background(color.copy(alpha = 0.15f), CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(80.dp)
                .background(color.copy(alpha = 0.4f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Default.Mic,
                contentDescription = null,
                tint = color,
                modifier = Modifier.size(40.dp)
            )
        }
    }
}

@Composable
private fun AudioLevelIndicator() {
    val transition = rememberInfiniteTransition(label = "audio")
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        repeat(5) { i ->
            val height by transition.animateFloat(
                initialValue = 4f,
                targetValue  = (12 + i * 6).toFloat(),
                animationSpec = infiniteRepeatable(
                    animation = tween(300 + i * 80, easing = EaseInOut),
                    repeatMode = RepeatMode.Reverse
                ),
                label = "bar$i"
            )
            Box(
                modifier = Modifier
                    .width(6.dp)
                    .height(height.dp)
                    .background(Color.Green, RoundedCornerShape(3.dp))
            )
        }
    }
}
