package com.example.nightagent.ui.stealth

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.nightagent.ui.theme.*

/**
 * Stealth Mode settings screen — accessible from the main app's Settings tab.
 *
 * Features:
 *  • Toggle Stealth Mode on/off (requires PIN to be set first)
 *  • Set PIN (shown automatically when enabling for the first time)
 *  • Change PIN (requires current PIN verification)
 *  • Explains the 3-press power button SOS trigger to the user
 */
@Composable
fun StealthSettingsScreen() {
    val context = LocalContext.current
    val vm: StealthViewModel = viewModel(
        factory = StealthViewModel.Factory(
            context.applicationContext as android.app.Application
        )
    )
    val state by vm.uiState.collectAsState()

    // ── Dialogs ──────────────────────────────────────────────────────────────
    if (state.showSetPinDialog) {
        SetPinDialog(
            onDismiss = { vm.onSetPinDialogDismiss() },
            onConfirm = { pin -> vm.onSetPinConfirmed(pin) },
            errorMessage = state.errorMessage
        )
    }

    if (state.showChangePinDialog) {
        ChangePinDialog(
            onDismiss = { vm.onChangePinDialogDismiss() },
            onConfirm = { current, new -> vm.onChangePinConfirmed(current, new) },
            errorMessage = state.errorMessage
        )
    }

    // ── Main content ─────────────────────────────────────────────────────────
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 20.dp)
    ) {

        // Header
        item {
            Spacer(modifier = Modifier.height(24.dp))
            Text(
                "Stealth Mode",
                fontSize = 28.sp,
                fontWeight = FontWeight.ExtraBold,
                color = MaterialTheme.colorScheme.onBackground
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                "Hide NightAgent behind a calculator. Trigger SOS silently.",
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f)
            )
            Spacer(modifier = Modifier.height(24.dp))
        }

        // Status banner
        item {
            StatusBanner(isEnabled = state.isStealthEnabled)
            Spacer(modifier = Modifier.height(20.dp))
        }

        // Main toggle card
        item {
            StealthToggleCard(
                isEnabled = state.isStealthEnabled,
                isPinSet = state.isPinSet,
                onToggle = { vm.onStealthToggled(it) }
            )
            Spacer(modifier = Modifier.height(16.dp))
        }

        // PIN management card
        item {
            PinManagementCard(
                isPinSet = state.isPinSet,
                onChangePin = { vm.onChangePinClicked() }
            )
            Spacer(modifier = Modifier.height(20.dp))
        }

        // How it works section
        item {
            HowItWorksCard()
            Spacer(modifier = Modifier.height(20.dp))
        }

        // Success / error feedback
        item {
            state.successMessage?.let { msg ->
                FeedbackBanner(message = msg, isError = false)
                Spacer(modifier = Modifier.height(8.dp))
            }
            state.errorMessage?.let { msg ->
                FeedbackBanner(message = msg, isError = true)
                Spacer(modifier = Modifier.height(8.dp))
            }
            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

// ── Sub-components ────────────────────────────────────────────────────────────

@Composable
private fun StatusBanner(isEnabled: Boolean) {
    val bgColor = if (isEnabled) SuccessGreen.copy(alpha = 0.12f) else MaterialTheme.colorScheme.surface
    val iconColor = if (isEnabled) SuccessGreen else TextSecondary
    val label = if (isEnabled) "Stealth Mode Active" else "Stealth Mode Inactive"
    val sub = if (isEnabled) "App disguised as Calculator" else "App visible as NightAgent"

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = bgColor)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = if (isEnabled) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                contentDescription = null,
                tint = iconColor,
                modifier = Modifier.size(32.dp)
            )
            Spacer(modifier = Modifier.width(14.dp))
            Column {
                Text(label, fontWeight = FontWeight.Bold, color = iconColor, fontSize = 16.sp)
                Text(sub, color = iconColor.copy(alpha = 0.7f), fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun StealthToggleCard(
    isEnabled: Boolean,
    isPinSet: Boolean,
    onToggle: (Boolean) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Default.Security,
                contentDescription = null,
                tint = Lavender,
                modifier = Modifier.size(28.dp)
            )
            Spacer(modifier = Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "Enable Stealth Mode",
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 16.sp,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    if (!isPinSet) "Set a PIN to enable" else "Disguise app as Calculator",
                    fontSize = 12.sp,
                    color = TextSecondary
                )
            }
            Switch(
                checked = isEnabled,
                onCheckedChange = onToggle,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Lavender,
                    checkedTrackColor = Lavender.copy(alpha = 0.4f)
                )
            )
        }
    }
}

@Composable
private fun PinManagementCard(isPinSet: Boolean, onChangePin: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.Lock,
                    contentDescription = null,
                    tint = BlushPink,
                    modifier = Modifier.size(28.dp)
                )
                Spacer(modifier = Modifier.width(14.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "Calculator PIN",
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 16.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        if (isPinSet) "PIN is set — enter it in the calculator to unlock" else "No PIN set yet",
                        fontSize = 12.sp,
                        color = TextSecondary
                    )
                }
                if (isPinSet) {
                    Icon(
                        Icons.Default.CheckCircle,
                        contentDescription = "PIN set",
                        tint = SuccessGreen,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }

            if (isPinSet) {
                Spacer(modifier = Modifier.height(14.dp))
                OutlinedButton(
                    onClick = onChangePin,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Lavender)
                ) {
                    Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Change PIN")
                }
            }
        }
    }
}

@Composable
private fun HowItWorksCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = PurpleStart.copy(alpha = 0.08f)
        )
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.Info,
                    contentDescription = null,
                    tint = PurpleStart,
                    modifier = Modifier.size(22.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    "How Stealth Mode Works",
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp,
                    color = PurpleStart
                )
            }
            Spacer(modifier = Modifier.height(14.dp))

            HowItWorksStep(
                number = "1",
                title = "App disguise",
                detail = "Your home screen shows a Calculator icon. No one knows NightAgent is installed."
            )
            HowItWorksStep(
                number = "2",
                title = "Unlock the real app",
                detail = "Open the Calculator, type your PIN, then press \"=\" to open NightAgent."
            )
            HowItWorksStep(
                number = "3",
                title = "Silent SOS trigger",
                detail = "Press the power button 3 times within 3 seconds to silently send an SOS — no screen needed."
            )
            HowItWorksStep(
                number = "4",
                title = "Background protection",
                detail = "A silent foreground service keeps detection active even when the screen is off."
            )
        }
    }
}

@Composable
private fun HowItWorksStep(number: String, title: String, detail: String) {
    Row(
        modifier = Modifier.padding(vertical = 6.dp),
        verticalAlignment = Alignment.Top
    ) {
        Surface(
            shape = RoundedCornerShape(50),
            color = PurpleStart.copy(alpha = 0.15f),
            modifier = Modifier.size(26.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(number, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = PurpleStart)
            }
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column {
            Text(title, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = MaterialTheme.colorScheme.onBackground)
            Text(detail, fontSize = 12.sp, color = TextSecondary, lineHeight = 17.sp)
        }
    }
}

@Composable
private fun FeedbackBanner(message: String, isError: Boolean) {
    val color = if (isError) Color(0xFFFF453A) else SuccessGreen
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = color.copy(alpha = 0.12f))
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = if (isError) Icons.Default.Error else Icons.Default.CheckCircle,
                contentDescription = null,
                tint = color,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(10.dp))
            Text(message, color = color, fontSize = 14.sp)
        }
    }
}

// ── Dialogs ───────────────────────────────────────────────────────────────────

@Composable
private fun SetPinDialog(
    onDismiss: () -> Unit,
    onConfirm: (String) -> Boolean,
    errorMessage: String?
) {
    var pin by remember { mutableStateOf("") }
    var confirmPin by remember { mutableStateOf("") }
    var localError by remember { mutableStateOf<String?>(null) }
    var showPin by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text("Set Calculator PIN", fontWeight = FontWeight.Bold)
        },
        text = {
            Column {
                Text(
                    "This PIN unlocks NightAgent from the Calculator screen.",
                    fontSize = 13.sp,
                    color = TextSecondary
                )
                Spacer(modifier = Modifier.height(16.dp))

                PinTextField(
                    value = pin,
                    onValueChange = { pin = it.filter { c -> c.isDigit() }.take(8) },
                    label = "New PIN (4–8 digits)",
                    showPin = showPin,
                    onToggleVisibility = { showPin = !showPin }
                )
                Spacer(modifier = Modifier.height(10.dp))
                PinTextField(
                    value = confirmPin,
                    onValueChange = { confirmPin = it.filter { c -> c.isDigit() }.take(8) },
                    label = "Confirm PIN",
                    showPin = showPin,
                    onToggleVisibility = { showPin = !showPin }
                )

                val displayError = localError ?: errorMessage
                if (displayError != null) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(displayError, color = Color(0xFFFF453A), fontSize = 12.sp)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                when {
                    pin.length < 4 -> localError = "PIN must be at least 4 digits"
                    pin != confirmPin -> localError = "PINs do not match"
                    else -> {
                        localError = null
                        onConfirm(pin)
                    }
                }
            }) {
                Text("Save", color = Lavender, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
private fun ChangePinDialog(
    onDismiss: () -> Unit,
    onConfirm: (String, String) -> Boolean,
    errorMessage: String?
) {
    var currentPin by remember { mutableStateOf("") }
    var newPin by remember { mutableStateOf("") }
    var confirmPin by remember { mutableStateOf("") }
    var localError by remember { mutableStateOf<String?>(null) }
    var showPin by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Change PIN", fontWeight = FontWeight.Bold) },
        text = {
            Column {
                PinTextField(
                    value = currentPin,
                    onValueChange = { currentPin = it.filter { c -> c.isDigit() }.take(8) },
                    label = "Current PIN",
                    showPin = showPin,
                    onToggleVisibility = { showPin = !showPin }
                )
                Spacer(modifier = Modifier.height(10.dp))
                PinTextField(
                    value = newPin,
                    onValueChange = { newPin = it.filter { c -> c.isDigit() }.take(8) },
                    label = "New PIN (4–8 digits)",
                    showPin = showPin,
                    onToggleVisibility = { showPin = !showPin }
                )
                Spacer(modifier = Modifier.height(10.dp))
                PinTextField(
                    value = confirmPin,
                    onValueChange = { confirmPin = it.filter { c -> c.isDigit() }.take(8) },
                    label = "Confirm New PIN",
                    showPin = showPin,
                    onToggleVisibility = { showPin = !showPin }
                )

                val displayError = localError ?: errorMessage
                if (displayError != null) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(displayError, color = Color(0xFFFF453A), fontSize = 12.sp)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                when {
                    newPin.length < 4 -> localError = "New PIN must be at least 4 digits"
                    newPin != confirmPin -> localError = "New PINs do not match"
                    else -> {
                        localError = null
                        onConfirm(currentPin, newPin)
                    }
                }
            }) {
                Text("Update", color = Lavender, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
private fun PinTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    showPin: Boolean,
    onToggleVisibility: () -> Unit
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
        visualTransformation = if (showPin) VisualTransformation.None else PasswordVisualTransformation(),
        trailingIcon = {
            IconButton(onClick = onToggleVisibility) {
                Icon(
                    imageVector = if (showPin) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                    contentDescription = if (showPin) "Hide PIN" else "Show PIN"
                )
            }
        },
        modifier = Modifier.fillMaxWidth()
    )
}
