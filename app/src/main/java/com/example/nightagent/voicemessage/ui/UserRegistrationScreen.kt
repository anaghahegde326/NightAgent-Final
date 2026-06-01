package com.example.nightagent.voicemessage.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.nightagent.ui.theme.*

/**
 * One-time registration screen.
 *
 * Users must register their phone number so other users can find them by phone.
 * This is the KEY step that makes app-to-app messaging work:
 *   - User A registers phone "+919876543210" → stored in Firestore users/{uid}
 *   - User B opens chat with "+919876543210" → looks up UID → finds User A
 *   - Both devices compute the same chatId → messages flow both ways
 *
 * Show this screen on first launch or from Settings.
 */
@Composable
fun UserRegistrationScreen(
    onRegister: (phone: String, name: String) -> Unit,
    onSkip: () -> Unit = {}
) {
    var phone by remember { mutableStateOf("") }
    var name  by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            Icons.Default.Phone,
            contentDescription = null,
            tint = Lavender,
            modifier = Modifier.size(64.dp)
        )

        Spacer(Modifier.height(24.dp))

        Text(
            "Register Your Number",
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground
        )

        Spacer(Modifier.height(8.dp))

        Text(
            "So friends can find you and send voice messages",
            fontSize = 14.sp,
            color = TextSecondary,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )

        Spacer(Modifier.height(32.dp))

        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text("Your name") },
            leadingIcon = { Icon(Icons.Default.Person, null) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )

        Spacer(Modifier.height(16.dp))

        OutlinedTextField(
            value = phone,
            onValueChange = { phone = it },
            label = { Text("Phone number (with country code)") },
            leadingIcon = { Icon(Icons.Default.Phone, null) },
            placeholder = { Text("+919876543210") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )

        error?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
        }

        Spacer(Modifier.height(24.dp))

        Button(
            onClick = {
                when {
                    name.isBlank()  -> error = "Please enter your name"
                    phone.isBlank() -> error = "Please enter your phone number"
                    else -> {
                        error = null
                        onRegister(phone.trim(), name.trim())
                    }
                }
            },
            modifier = Modifier.fillMaxWidth().height(52.dp),
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Lavender)
        ) {
            Text("Register", fontSize = 16.sp, fontWeight = FontWeight.Bold)
        }

        Spacer(Modifier.height(12.dp))

        TextButton(onClick = onSkip) {
            Text("Skip for now", color = TextSecondary)
        }
    }
}
