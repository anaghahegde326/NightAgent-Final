package com.example.nightagent.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

import com.example.nightagent.ui.components.ToggleRow
import com.example.nightagent.ui.theme.*
import com.example.nightagent.sos.SafetySettings

@Composable
fun SettingsScreen(
    onStealthClick: () -> Unit = {},
    onRegisterClick: () -> Unit = {}
) {

    // ✅ Proper reactive state
    val pushNotifications by SafetySettings.pushNotifications
    val darkMode by SafetySettings.darkMode
    val locationSharing by SafetySettings.locationSharing
    val autoRecording by SafetySettings.autoRecording
    val autoRecordingConsent by SafetySettings.autoRecordingConsent

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 20.dp)
    ) {

        item {

            Spacer(modifier = Modifier.height(24.dp))

            Text(
                text = "Settings",
                fontSize = 28.sp,
                fontWeight = FontWeight.ExtraBold,
                color = MaterialTheme.colorScheme.onBackground
            )

            Spacer(modifier = Modifier.height(24.dp))
        }

        // 🔔 Notifications
        item {

            Text(
                text = "Notifications & Sounds",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground
            )

            Spacer(modifier = Modifier.height(12.dp))

            ToggleRow(
                title = "Push Notifications",
                subtitle = "Receive important safety alerts",
                icon = Icons.Default.Notifications,
                checked = pushNotifications
            ) {
                SafetySettings.pushNotifications.value = it
            }

            Spacer(modifier = Modifier.height(16.dp))
        }

        // 🎨 Appearance
        item {

            Text(
                text = "Appearance",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground
            )

            Spacer(modifier = Modifier.height(12.dp))

            ToggleRow(
                title = "Dark Mode",
                subtitle = "Switch to dark theme",
                icon = Icons.Default.DarkMode,
                checked = darkMode
            ) {
                SafetySettings.darkMode.value = it
            }

            Spacer(modifier = Modifier.height(16.dp))
        }

        // 🔐 Privacy & Safety
        item {

            Text(
                text = "Privacy & Safety",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = TextPrimary
            )

            Spacer(modifier = Modifier.height(12.dp))

            ToggleRow(
                title = "Location Sharing",
                subtitle = "Share location with contacts",
                icon = Icons.Default.LocationOn,
                checked = locationSharing
            ) {
                SafetySettings.locationSharing.value = it
            }

            Spacer(modifier = Modifier.height(8.dp))

            // 🔥 FIXED: Actual recording toggle
            ToggleRow(
                title = "Auto Recording",
                subtitle = "Record video/audio on SOS",
                icon = Icons.Default.Videocam,
                checked = autoRecording
            ) {
                SafetySettings.autoRecording.value = it
            }

            Spacer(modifier = Modifier.height(8.dp))

            ToggleRow(
                title = "Recording Consent",
                subtitle = "User consent for recording",
                icon = Icons.Default.Videocam,
                checked = autoRecordingConsent
            ) {
                SafetySettings.autoRecordingConsent.value = it
            }

            Spacer(modifier = Modifier.height(16.dp))
        }

        // 🕵️ Stealth Mode
        item {

            Text(
                text = "Stealth & Disguise",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = TextPrimary
            )

            Spacer(modifier = Modifier.height(12.dp))

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 6.dp),
                shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
                onClick = onStealthClick
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.VisibilityOff,
                        contentDescription = null,
                        tint = com.example.nightagent.ui.theme.Lavender,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(14.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Stealth Mode", fontWeight = FontWeight.SemiBold)
                        Text(
                            "Disguise app as Calculator",
                            fontSize = 12.sp,
                            color = TextSecondary
                        )
                    }
                    Icon(
                        Icons.Default.ChevronRight,
                        contentDescription = null,
                        tint = TextSecondary
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
        }

        // 📱 Voice Messaging Registration
        item {

            Text(
                text = "Voice Messaging",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = TextPrimary
            )

            Spacer(modifier = Modifier.height(12.dp))

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 6.dp),
                shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
                onClick = onRegisterClick
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.Mic,
                        contentDescription = null,
                        tint = com.example.nightagent.ui.theme.Lavender,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(14.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Register Phone Number", fontWeight = FontWeight.SemiBold)
                        Text(
                            "Required so contacts can send you voice messages",
                            fontSize = 12.sp,
                            color = TextSecondary
                        )
                    }
                    Icon(
                        Icons.Default.ChevronRight,
                        contentDescription = null,
                        tint = TextSecondary
                    )
                }
            }

            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}