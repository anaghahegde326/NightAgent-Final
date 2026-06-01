package com.example.nightagent.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import com.example.nightagent.contacts.ContactManager
import com.example.nightagent.ui.theme.*

data class EmergencyContact(
    val name: String,
    val phone: String,
    val isPriority: Boolean
)

@Composable
fun ContactsScreen(onVoiceChat: (String) -> Unit = {}) {

    val context = LocalContext.current

    var showDialog by remember { mutableStateOf(false) }

    var nameInput by remember { mutableStateOf("") }
    var phoneInput by remember { mutableStateOf("") }

    // ✅ FIXED STATE HANDLING
    var contacts by remember { mutableStateOf(listOf<EmergencyContact>()) }

    // 🔥 LOAD CONTACTS PROPERLY
    LaunchedEffect(Unit) {
        contacts = ContactManager.getContacts(context)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp)
        ) {

            item {
                Spacer(modifier = Modifier.height(24.dp))

                Text(
                    "Emergency Contacts",
                    fontSize = 28.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = MaterialTheme.colorScheme.onBackground
                )

                Spacer(modifier = Modifier.height(20.dp))

                Text(
                    "Primary contact highlighted",
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
                    fontSize = 14.sp
                )

                Spacer(modifier = Modifier.height(16.dp))
            }

            // ✅ EMPTY STATE
            if (contacts.isEmpty()) {
                item {
                    Spacer(modifier = Modifier.height(40.dp))
                    Text(
                        text = "No contacts added yet",
                        color = Color.Gray,
                        fontSize = 16.sp
                    )
                }
            }

            items(contacts) { contact ->

                ContactCard(
                    contact = contact,
                    onDelete = {
                        val updated = contacts.filter { it != contact }
                        ContactManager.saveContacts(context, updated)
                        contacts = ContactManager.getContacts(context)
                    },
                    onVoiceChat = { onVoiceChat(contact.phone) }
                )
            }

            item {
                Spacer(modifier = Modifier.height(100.dp))
            }
        }

        FloatingActionButton(
            onClick = { showDialog = true },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 20.dp, bottom = 20.dp),
            containerColor = BlushPink,
            contentColor = PurpleEnd
        ) {
            Icon(Icons.Default.Add, contentDescription = "Add Contact")
        }
    }

    // 🔥 ADD CONTACT DIALOG
    if (showDialog) {

        AlertDialog(

            onDismissRequest = { showDialog = false },

            confirmButton = {

                TextButton(
                    onClick = {

                        if (phoneInput.isNotBlank()) {

                            val updated = contacts + EmergencyContact(
                                nameInput,
                                phoneInput,
                                false
                            )

                            ContactManager.saveContacts(context, updated)

                            // 🔥 RELOAD AFTER SAVE
                            contacts = ContactManager.getContacts(context)

                            nameInput = ""
                            phoneInput = ""

                            showDialog = false
                        }
                    }
                ) {
                    Text("Save")
                }
            },

            dismissButton = {

                TextButton(
                    onClick = { showDialog = false }
                ) {
                    Text("Cancel")
                }

            },

            title = { Text("Add Emergency Contact") },

            text = {

                Column {

                    OutlinedTextField(
                        value = nameInput,
                        onValueChange = { nameInput = it },
                        label = { Text("Name") }
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    OutlinedTextField(
                        value = phoneInput,
                        onValueChange = { phoneInput = it },
                        label = { Text("Phone Number") }
                    )
                }
            }
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ContactCard(
    contact: EmergencyContact,
    onDelete: (EmergencyContact) -> Unit,
    onVoiceChat: () -> Unit = {}
) {

    val context = LocalContext.current

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {

        Row(
            modifier = Modifier
                .padding(20.dp)
                .combinedClickable(
                    onClick = {},
                    onLongClick = { onDelete(contact) }
                ),
            verticalAlignment = Alignment.CenterVertically
        ) {

            Box(
                modifier = Modifier
                    .size(52.dp)
                    .clip(CircleShape)
                    .background(
                        if (contact.isPriority) BlushPink.copy(alpha = 0.3f)
                        else Lavender.copy(alpha = 0.2f)
                    ),
                contentAlignment = Alignment.Center
            ) {

                Text(
                    text = contact.name.take(1).uppercase(),
                    fontWeight = FontWeight.Bold,
                    fontSize = 22.sp,
                    color = if (contact.isPriority) PurpleEnd else Lavender
                )
            }

            Spacer(modifier = Modifier.width(16.dp))

            Column(modifier = Modifier.weight(1f)) {

                Row(verticalAlignment = Alignment.CenterVertically) {

                    Text(
                        text = contact.name,
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp,
                        color = TextPrimary
                    )

                    if (contact.isPriority) {

                        Spacer(modifier = Modifier.width(8.dp))

                        Icon(
                            Icons.Default.Star,
                            contentDescription = "Primary",
                            tint = Color(0xFFFFD700),
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }

                Text(
                    text = contact.phone,
                    color = TextSecondary,
                    fontSize = 15.sp
                )
            }

            IconButton(
                onClick = {
                    val intent = Intent(Intent.ACTION_DIAL).apply {
                        data = Uri.parse("tel:${contact.phone}")
                    }
                    context.startActivity(intent)
                },
                modifier = Modifier
                    .size(48.dp)
                    .background(SuccessGreen.copy(alpha = 0.15f), CircleShape)
            ) {
                Icon(Icons.Default.Call, null, tint = SuccessGreen, modifier = Modifier.size(24.dp))
            }

            Spacer(modifier = Modifier.width(8.dp))

            // Voice message button — opens the voice chat screen for this contact
            IconButton(
                onClick = onVoiceChat,
                modifier = Modifier
                    .size(48.dp)
                    .background(
                        androidx.compose.ui.graphics.Color(0xFF9C27B0).copy(alpha = 0.12f),
                        CircleShape
                    )
            ) {
                Icon(
                    Icons.Default.Mic,
                    contentDescription = "Voice message",
                    tint = androidx.compose.ui.graphics.Color(0xFF9C27B0),
                    modifier = Modifier.size(22.dp)
                )
            }
        }
    }
}