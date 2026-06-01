package com.example.nightagent.ui.screens

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.LifecycleOwner
import com.example.nightagent.sos.EvidenceRecorder
import com.example.nightagent.ui.components.*
import com.example.nightagent.ui.theme.*

@Composable
fun HomeScreen(
    onSOSClick: () -> Unit,
    onFakeCallClick: () -> Unit,
    onSafeWalkClick: () -> Unit,
    onShareLocationClick: () -> Unit
) {
    val context = LocalContext.current as Context
    val lifecycleOwner = LocalLifecycleOwner.current as LifecycleOwner

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        item {
            // Fix 9: Replace hardcoded height(180.dp) with wrapContentHeight() +
            //         statusBarsPadding() so the header expands to fit its content
            //         and never clips on small screens or under the status bar.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .wrapContentHeight()
                    .background(
                        Brush.verticalGradient(colors = listOf(PurpleStart, PurpleEnd)),
                        // Fix 10: Reduce corner radius from 40.dp to 28.dp so the
                        //          curve is never clipped on narrow screens.
                        shape = RoundedCornerShape(bottomStart = 28.dp, bottomEnd = 28.dp)
                    )
                    // Status bar padding keeps text below the camera notch / status icons
                    .statusBarsPadding()
                    .padding(horizontal = 24.dp, vertical = 28.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            "Safety First,",
                            color = Color.White.copy(alpha = 0.8f),
                            fontSize = 16.sp
                        )
                        Text(
                            "Agent Status",
                            color = Color.White,
                            fontSize = 26.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Surface(
                        color = SuccessGreen.copy(alpha = 0.2f),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .background(SuccessGreen, RoundedCornerShape(50))
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                "All Systems Active",
                                color = Color.White,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }
            }
        }

        item {
            SOSButton(onLongPress = onSOSClick)
        }

        item {
            Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                Text(
                    "Quick Actions",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Spacer(modifier = Modifier.height(12.dp))

                // Fix 11: Pass Modifier.weight(1f) to each card so they share the
                //          row width equally regardless of screen size. No more
                //          hardcoded widths fighting each other.
                Row(modifier = Modifier.fillMaxWidth()) {
                    QuickActionCard(
                        title = "Share Location",
                        icon = Icons.Default.LocationOn,
                        iconColor = InfoBlue,
                        onClick = onShareLocationClick,
                        modifier = Modifier.weight(1f)
                    )
                    QuickActionCard(
                        title = "Fake Call",
                        icon = Icons.Default.Call,
                        iconColor = Color(0xFFFF9800),
                        onClick = onFakeCallClick,
                        modifier = Modifier.weight(1f)
                    )
                }

                Row(modifier = Modifier.fillMaxWidth()) {
                    QuickActionCard(
                        title = "Safe Walk",
                        icon = Icons.Default.DirectionsWalk,
                        iconColor = SuccessGreen,
                        onClick = onSafeWalkClick,
                        modifier = Modifier.weight(1f)
                    )
                    // Fix 12: ManualRecordingButtons now also takes weight(1f) so it
                    //          matches the card next to it without overflow.
                    ManualRecordingButtons(
                        context = context,
                        lifecycleOwner = lifecycleOwner,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }

        item {
            Spacer(modifier = Modifier.height(24.dp))
            Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "Recent Activity",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    TextButton(onClick = {}) {
                        Text("See All", color = PurpleEnd)
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
            }
        }

        item {
            Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                ActivityItem(
                    title = "Safe Walk Completed",
                    time = "Today, 10:30 PM",
                    icon = Icons.Default.CheckCircle,
                    iconColor = SuccessGreen
                )
                ActivityItem(
                    title = "Emergency Contacts Updated",
                    time = "Yesterday, 06:15 PM",
                    icon = Icons.Default.Person,
                    iconColor = InfoBlue
                )
            }
            Spacer(modifier = Modifier.height(20.dp))
        }
    }
}

// Fix 13: Accept modifier so the caller can pass weight(1f), making this slot
//          the same width as the QuickActionCard beside it. Use fillMaxWidth()
//          + aspectRatio inside so the button is proportional, not overflowing.
@Composable
private fun ManualRecordingButtons(
    context: Context,
    lifecycleOwner: LifecycleOwner,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .padding(6.dp)
            .aspectRatio(1.2f),
        contentAlignment = Alignment.Center
    ) {
        if (EvidenceRecorder.isRecording) {
            Button(
                onClick = { EvidenceRecorder.stopRecording(context) },
                modifier = Modifier.fillMaxSize(),
                shape = RoundedCornerShape(20.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color.Red)
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Default.Stop,
                        contentDescription = "Stop Recording",
                        modifier = Modifier.size(22.dp)
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text("Stop", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }
        } else {
            Button(
                onClick = { EvidenceRecorder.startRecording(context, lifecycleOwner) },
                modifier = Modifier.fillMaxSize(),
                shape = RoundedCornerShape(20.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF9800))
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Default.Videocam,
                        contentDescription = "Start Recording",
                        modifier = Modifier.size(22.dp)
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text("Record", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}
