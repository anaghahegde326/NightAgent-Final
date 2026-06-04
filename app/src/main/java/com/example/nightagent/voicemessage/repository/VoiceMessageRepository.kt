package com.example.nightagent.voicemessage.repository

import android.content.Context
import android.util.Log
import com.example.nightagent.voicemessage.firebase.VoiceMessageFirebase
import com.example.nightagent.voicemessage.firebase.VoiceMessageFirebase.TAG_SYNC
import com.example.nightagent.voicemessage.firebase.VoiceMessageFirebase.TAG_UPLOAD
import com.example.nightagent.voicemessage.model.MessageStatus
import com.example.nightagent.voicemessage.model.UploadStatus
import com.example.nightagent.voicemessage.model.UserProfile
import com.example.nightagent.voicemessage.model.VoiceMessage
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.flow.Flow
import java.io.File
import java.util.UUID

/**
 * Single source of truth for voice messaging.
 *
 * Responsibilities:
 *  1. User registration (maps phone → UID in Firestore)
 *  2. Receiver UID lookup (phone → UID)
 *  3. Chat room creation
 *  4. Full send pipeline: upload → Firestore write → push notification
 *  5. Real-time message stream
 *  6. Seen status updates
 */
class VoiceMessageRepository(private val context: Context) {

    // ── User registration ─────────────────────────────────────────────────────

    /**
     * Register the current user's phone number in Firestore.
     * Must be called after auth + after the user provides their phone number.
     * This is what allows other users to find this device's UID by phone number.
     */
    suspend fun registerCurrentUser(phoneNumber: String, displayName: String) {
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return
        val profile = UserProfile(
            uid         = uid,
            displayName = displayName,
            phoneNumber = normalizePhone(phoneNumber),
            fcmToken    = ""   // updated separately by FCM token refresh
        )
        VoiceMessageFirebase.registerUser(profile)
    }

    /**
     * Look up the Firebase UID for a given phone number.
     * Returns null if the number is not registered in the app.
     */
    suspend fun findReceiverUid(phoneNumber: String): String? =
        VoiceMessageFirebase.findUidByPhone(normalizePhone(phoneNumber))

    // ── Messages stream ───────────────────────────────────────────────────────

    /**
     * Real-time Flow of messages for a chat.
     * Emits a new list every time Firestore pushes an update.
     * Works on BOTH sender and receiver devices simultaneously.
     */
    fun observeMessages(chatId: String): Flow<List<VoiceMessage>> {
        Log.d(TAG_SYNC, "observeMessages: chatId=$chatId")
        return VoiceMessageFirebase.messagesFlow(chatId)
    }

    // ── Send pipeline ─────────────────────────────────────────────────────────

    /**
     * Complete send pipeline:
     *   1. Ensure chat room exists in Firestore
     *   2. Upload audio file to Firebase Storage
     *   3. Write message document to chats/{chatId}/messages
     *   4. Queue FCM push notification to receiver
     *
     * @param file        Recorded M4A file from VoiceRecorder
     * @param durationMs  Recording duration
     * @param senderId    Current user's Firebase UID
     * @param receiverId  Receiver's Firebase UID (looked up via findReceiverUid)
     * @param onProgress  Upload progress 0–100
     */
    suspend fun sendVoiceMessage(
        file: File,
        durationMs: Long,
        senderId: String,
        receiverId: String,
        onProgress: (Int) -> Unit = {}
    ): Result<VoiceMessage> {
        val messageId = UUID.randomUUID().toString()
        val cid = VoiceMessageFirebase.chatId(senderId, receiverId)

        Log.d(TAG_UPLOAD, "sendVoiceMessage: chatId=$cid senderId=$senderId receiverId=$receiverId")

        // Step 1 — Ensure chat room document exists
        VoiceMessageFirebase.ensureChatRoom(senderId, receiverId)

        // Step 2 — Upload audio to Storage
        val uploadResult = VoiceMessageFirebase.uploadAudio(
            file       = file,
            senderId   = senderId,
            messageId  = messageId,
            onProgress = onProgress
        )
        if (uploadResult.isFailure) {
            Log.e(TAG_UPLOAD, "Upload failed — aborting send")
            return Result.failure(uploadResult.exceptionOrNull()!!)
        }
        val audioUrl = uploadResult.getOrThrow()

        // Step 3 — Write Firestore document
        val message = VoiceMessage(
            messageId     = messageId,
            chatId        = cid,
            senderId      = senderId,
            receiverId    = receiverId,
            audioUrl      = audioUrl,
            localPath     = "",
            durationMs    = durationMs,
            fileSize      = file.length(),
            timestamp     = System.currentTimeMillis(),
            uploadStatus  = UploadStatus.DONE,
            messageStatus = MessageStatus.SENT
        )
        val saveResult = VoiceMessageFirebase.saveMessage(message)
        if (saveResult.isFailure) {
            Log.e(TAG_SYNC, "Firestore save failed — audio uploaded but message lost")
            return Result.failure(saveResult.exceptionOrNull()!!)
        }

        // Step 4 — Push notification (non-fatal if it fails)
        val senderProfile = VoiceMessageFirebase.getUserProfile(senderId)
        val senderName = senderProfile?.displayName?.ifBlank { senderId.take(8) } ?: senderId.take(8)
        VoiceMessageFirebase.queuePushNotification(
            receiverUid = receiverId,
            senderUid   = senderId,
            senderName  = senderName,
            chatId      = cid
        )

        // Step 5 — Clean up local cache file
        try { file.delete() } catch (_: Exception) { }

        Log.d(TAG_SYNC, "Message sent successfully: msgId=$messageId chatId=$cid")
        return Result.success(message.copy(messageId = saveResult.getOrThrow()))
    }

    // ── Seen status ───────────────────────────────────────────────────────────

    suspend fun markMessageSeen(chatId: String, messageId: String) {
        VoiceMessageFirebase.markMessageSeen(chatId, messageId)
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * FIX 7: Normalize phone to E.164 for consistent Firestore lookup.
     *
     * The lookup uses whereEqualTo() which requires an EXACT string match.
     * Both the registering device and the looking-up device must produce
     * the same normalized string, otherwise the lookup returns null and
     * the session never initialises ("Receiver not found" error).
     *
     * Normalization rules:
     *   - Strip all whitespace, dashes, parentheses
     *   - If already starts with +, keep as-is
     *   - If 10 digits (no country code), prepend +91 (India — adjust as needed)
     *   - Otherwise keep digits only
     */
    private fun normalizePhone(phone: String): String {
        // Step 1: remove all formatting characters except digits and +
        val cleaned = phone.replace(Regex("[\\s\\-().]"), "")
        val digits  = cleaned.filter { it.isDigit() || it == '+' }
        return when {
            digits.startsWith("+") -> digits          // already E.164
            digits.length == 10   -> "+91$digits"     // India 10-digit
            digits.length == 11 && digits.startsWith("0") ->
                "+91${digits.drop(1)}"                // 011-digit with leading 0
            else -> digits
        }.also { Log.d("VoiceRepo", "normalizePhone: '$phone' → '$it'") }
    }
}
