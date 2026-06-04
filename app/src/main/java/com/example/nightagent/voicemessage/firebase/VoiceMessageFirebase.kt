package com.example.nightagent.voicemessage.firebase

import android.util.Log
import com.example.nightagent.firebase.FirebaseConfig
import com.example.nightagent.voicemessage.model.ChatRoom
import com.example.nightagent.voicemessage.model.MessageStatus
import com.example.nightagent.voicemessage.model.UploadStatus
import com.example.nightagent.voicemessage.model.UserProfile
import com.example.nightagent.voicemessage.model.VoiceMessage
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await
import java.io.File


object VoiceMessageFirebase {

    private val db      get() = FirebaseConfig.firestore
    private val storage get() = FirebaseConfig.storage

    // ── chatId ────────────────────────────────────────────────────────────────

    /**
     * Deterministic chatId — identical on both devices regardless of who initiates.
     * Uses sorted UIDs so "abc_xyz" == "xyz_abc" always resolves to "abc_xyz".
     */
    fun chatId(uid1: String, uid2: String): String =
        listOf(uid1, uid2).sorted().joinToString("_")

    // ── User registration ─────────────────────────────────────────────────────

    /**
     * Register or update the current user's profile in Firestore.
     * Call this on every app launch after auth completes.
     * This is what makes UID lookup by phone number possible.
     */
    suspend fun registerUser(profile: UserProfile) {
        try {
            db.collection("users")
                .document(profile.uid)
                .set(profile.toMap(), SetOptions.merge())
                .await()
            Log.d(TAG_SYNC, "User registered: uid=${profile.uid} phone=${profile.phoneNumber}")
        } catch (e: Exception) {
            Log.e(TAG_SYNC, "registerUser failed: ${e.message}", e)
        }
    }

    /**
     * Look up a Firebase UID by phone number.
     * Returns null if the phone number is not registered.
     *
     * This is the KEY function that fixes the "messages not reaching receiver" bug.
     * Without this, we can't know the receiver's UID.
     */
    suspend fun findUidByPhone(phoneNumber: String): String? {
        return try {
            val snapshot = db.collection("users")
                .whereEqualTo("phoneNumber", phoneNumber)
                .limit(1)
                .get()
                .await()
            val uid = snapshot.documents.firstOrNull()?.getString("uid")
            Log.d(TAG_SYNC, "findUidByPhone($phoneNumber) → $uid")
            uid
        } catch (e: Exception) {
            Log.e(TAG_SYNC, "findUidByPhone failed: ${e.message}", e)
            null
        }
    }

    /**
     * Get a user profile by UID.
     */
    suspend fun getUserProfile(uid: String): UserProfile? {
        return try {
            val doc = db.collection("users").document(uid).get().await()
            if (!doc.exists()) return null
            UserProfile(
                uid         = doc.getString("uid") ?: uid,
                displayName = doc.getString("displayName") ?: "",
                phoneNumber = doc.getString("phoneNumber") ?: "",
                fcmToken    = doc.getString("fcmToken") ?: ""
            )
        } catch (e: Exception) {
            Log.e(TAG_SYNC, "getUserProfile failed: ${e.message}", e)
            null
        }
    }

    // ── Chat room ─────────────────────────────────────────────────────────────

    /**
     * Ensure a chat room document exists for these two users.
     * Uses SetOptions.merge() so it's safe to call multiple times.
     */
    suspend fun ensureChatRoom(uid1: String, uid2: String) {
        val cid = chatId(uid1, uid2)
        try {
            val data = mapOf(
                "chatId"        to cid,
                "participants"  to listOf(uid1, uid2),
                "createdAt"     to FieldValue.serverTimestamp()
            )
            db.collection("chats").document(cid)
                .set(data, SetOptions.merge())
                .await()
            Log.d(TAG_SYNC, "Chat room ensured: $cid")
        } catch (e: Exception) {
            Log.e(TAG_SYNC, "ensureChatRoom failed: ${e.message}", e)
        }
    }

    // ── Upload audio ──────────────────────────────────────────────────────────

    /**
     * Upload an M4A/AAC file to Firebase Storage.
     * Returns the public download URL on success.
     *
     * Storage path: voice_messages/{senderId}/{messageId}.m4a
     */
    suspend fun uploadAudio(
        file: File,
        senderId: String,
        messageId: String,
        onProgress: (Int) -> Unit = {}
    ): Result<String> {
        // FIX 8: Use putStream() instead of putFile(Uri.fromFile(file)).
        // Uri.fromFile() produces a file:// URI. On Android 10+ with strict mode
        // this throws FileUriExposedException on some OEMs. putStream() sends
        // the raw bytes directly without any URI conversion — works on all levels.
        if (!file.exists() || file.length() == 0L) {
            Log.e(TAG_UPLOAD, "uploadAudio: file missing or empty — ${file.absolutePath}")
            return Result.failure(IllegalArgumentException("Audio file missing or empty"))
        }

        return try {
            val ref = storage.reference
                .child("voice_messages/$senderId/$messageId.m4a")

            Log.d(TAG_UPLOAD, "Starting upload: ${file.name} (${file.length()} bytes)")

            val uploadTask = ref.putStream(file.inputStream())
            uploadTask.addOnProgressListener { snap ->
                val pct = if (snap.totalByteCount > 0)
                    (100.0 * snap.bytesTransferred / snap.totalByteCount).toInt()
                else 0
                onProgress(pct)
                Log.d(TAG_UPLOAD, "Upload progress: $pct% (${snap.bytesTransferred}/${snap.totalByteCount} bytes)")
            }

            uploadTask.await()
            val url = ref.downloadUrl.await().toString()
            Log.d(TAG_UPLOAD, "Upload complete. URL: $url")
            Result.success(url)
        } catch (e: Exception) {
            Log.e(TAG_UPLOAD, "Upload FAILED: ${e.message}", e)
            Result.failure(e)
        }
    }

    // ── Save message to Firestore ─────────────────────────────────────────────

    /**
     * Write a voice message document to chats/{chatId}/messages/{messageId}.
     *
     * This is what the receiver's real-time listener picks up.
     * The document must contain BOTH senderId and receiverId so the receiver
     * can verify the message is addressed to them.
     */
    suspend fun saveMessage(message: VoiceMessage): Result<String> = try {
        val colRef = db.collection("chats")
            .document(message.chatId)
            .collection("messages")

        val docRef = if (message.messageId.isBlank()) colRef.document()
                     else colRef.document(message.messageId)

        // Use toFirestoreMap() — single source of truth for field names
        docRef.set(message.toFirestoreMap()).await()

        // Update chat room preview — use set+merge so it works even if the
        // chat room document doesn't exist yet (race condition safety)
        db.collection("chats").document(message.chatId)
            .set(
                mapOf(
                    "lastMessage"   to "🎙 Voice message",
                    "lastTimestamp" to message.timestamp
                ),
                SetOptions.merge()
            ).await()

        Log.d(TAG_SYNC, "Message saved: chatId=${message.chatId} msgId=${docRef.id}")
        Result.success(docRef.id)
    } catch (e: Exception) {
        Log.e(TAG_SYNC, "saveMessage FAILED: ${e.message}", e)
        Result.failure(e)
    }

    // ── Real-time listener ────────────────────────────────────────────────────

    /**
     * Returns a cold Flow that emits the full message list whenever Firestore
     * pushes an update. This is what makes messages appear instantly on the
     * receiver's device without polling.
     *
     * Path: chats/{chatId}/messages  ordered by timestamp ASC
     *
     * The listener is registered when the Flow is collected and removed when
     * the Flow is cancelled (e.g. when the screen is closed).
     */
    fun messagesFlow(chatId: String): Flow<List<VoiceMessage>> = callbackFlow {
        Log.d(TAG_REALTIME, "Starting Firestore listener for chatId=$chatId")

        val listener = db.collection("chats")
            .document(chatId)
            .collection("messages")
            .orderBy("timestamp", Query.Direction.ASCENDING)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    Log.e(TAG_REALTIME, "Listener error: ${error.message}", error)
                    return@addSnapshotListener
                }

                if (snapshot == null) {
                    Log.w(TAG_REALTIME, "Null snapshot for chatId=$chatId")
                    return@addSnapshotListener
                }

                Log.d(TAG_REALTIME, "Snapshot received: ${snapshot.size()} docs, " +
                    "fromCache=${snapshot.metadata.isFromCache}, " +
                    "hasPendingWrites=${snapshot.metadata.hasPendingWrites()}")

                val messages = snapshot.documents.mapNotNull { doc ->
                    try {
                        VoiceMessage(
                            messageId     = doc.getString("messageId") ?: doc.id,
                            chatId        = doc.getString("chatId") ?: chatId,
                            senderId      = doc.getString("senderId") ?: "",
                            receiverId    = doc.getString("receiverId") ?: "",
                            audioUrl      = doc.getString("audioUrl") ?: "",
                            localPath     = "",
                            durationMs    = doc.getLong("durationMs") ?: 0L,
                            fileSize      = doc.getLong("fileSize") ?: 0L,
                            timestamp     = doc.getLong("timestamp") ?: 0L,
                            // FIX 9: Use enumValueOrDefault instead of valueOf() to
                            // prevent IllegalArgumentException on unknown/stale enum
                            // values stored in Firestore from older app versions.
                            uploadStatus  = enumValueOrDefault(
                                doc.getString("uploadStatus"), UploadStatus.DONE
                            ),
                            messageStatus = enumValueOrDefault(
                                doc.getString("messageStatus"), MessageStatus.SENT
                            )
                        )
                    } catch (e: Exception) {
                        Log.e(TAG_REALTIME, "Parse error doc=${doc.id}: ${e.message}")
                        null
                    }
                }

                Log.d(TAG_REALTIME, "Emitting ${messages.size} messages for chatId=$chatId")
                trySend(messages)
            }

        awaitClose {
            Log.d(TAG_REALTIME, "Removing Firestore listener for chatId=$chatId")
            listener.remove()
        }
    }

    // ── Mark as seen ──────────────────────────────────────────────────────────

    suspend fun markMessageSeen(chatId: String, messageId: String) {
        try {
            db.collection("chats")
                .document(chatId)
                .collection("messages")
                .document(messageId)
                .update("messageStatus", MessageStatus.SEEN.name)
                .await()
            Log.d(TAG_SYNC, "Marked seen: $messageId")
        } catch (e: Exception) {
            Log.e(TAG_SYNC, "markMessageSeen failed: ${e.message}")
        }
    }

    // ── Update FCM token ──────────────────────────────────────────────────────

    suspend fun updateFcmToken(uid: String, token: String) {
        try {
            db.collection("users").document(uid)
                .update("fcmToken", token)
                .await()
        } catch (e: Exception) {
            // User doc may not exist yet — use set with merge
            try {
                db.collection("users").document(uid)
                    .set(mapOf("fcmToken" to token), SetOptions.merge())
                    .await()
            } catch (_: Exception) { }
        }
    }

    // ── Queue FCM push notification ───────────────────────────────────────────

    /**
     * Write an FCM request document. A Cloud Function watches this collection
     * and sends the actual push notification.
     *
     * Deploy this Cloud Function (index.js):
     *
     * const functions = require('firebase-functions');
     * const admin = require('firebase-admin');
     * admin.initializeApp();
     *
     * exports.sendVoiceNotification = functions.firestore
     *   .document('fcm_requests/{reqId}')
     *   .onCreate(async (snap) => {
     *     const data = snap.data();
     *     if (!data.token) return;
     *     await admin.messaging().send({
     *       token: data.token,
     *       data: {
     *         type: 'voice_message',
     *         chatId: data.chatId,
     *         senderId: data.senderId,
     *         senderName: data.senderName
     *       },
     *       android: { priority: 'high' },
     *       notification: {
     *         title: `Voice message from ${data.senderName}`,
     *         body: '🎙 Tap to listen'
     *       }
     *     });
     *     await snap.ref.delete(); // clean up
     *   });
     */
    suspend fun queuePushNotification(
        receiverUid: String,
        senderUid: String,
        senderName: String,
        chatId: String
    ) {
        try {
            val receiverDoc = db.collection("users").document(receiverUid).get().await()
            val token = receiverDoc.getString("fcmToken") ?: run {
                Log.w(TAG_SYNC, "No FCM token for $receiverUid — skipping push")
                return
            }
            db.collection("fcm_requests").add(
                mapOf(
                    "token"      to token,
                    "chatId"     to chatId,
                    "senderId"   to senderUid,
                    "senderName" to senderName,
                    "timestamp"  to System.currentTimeMillis()
                )
            ).await()
            Log.d(TAG_SYNC, "FCM request queued for $receiverUid")
        } catch (e: Exception) {
            Log.e(TAG_SYNC, "queuePushNotification failed: ${e.message}")
            // Non-fatal — message is in Firestore, receiver sees it on next open
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun UserProfile.toMap() = mapOf(
        "uid"         to uid,
        "displayName" to displayName,
        "phoneNumber" to phoneNumber,
        "fcmToken"    to fcmToken
    )

    /**
     * FIX 9: Safe enum parsing — returns [default] instead of throwing
     * IllegalArgumentException when the stored string doesn't match any value.
     */
    private inline fun <reified T : Enum<T>> enumValueOrDefault(value: String?, default: T): T {
        if (value.isNullOrBlank()) return default
        return try {
            enumValueOf<T>(value)
        } catch (_: IllegalArgumentException) {
            Log.w(TAG, "Unknown enum value '$value' for ${T::class.simpleName} — using $default")
            default
        }
    }

    const val TAG_UPLOAD   = "VOICE_UPLOAD"
    const val TAG_SYNC     = "FIRESTORE_SYNC"
    const val TAG_REALTIME = "CHAT_REALTIME"
    private const val TAG  = "VoiceMessageFirebase"
}
