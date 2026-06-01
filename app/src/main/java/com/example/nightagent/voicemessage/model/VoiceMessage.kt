package com.example.nightagent.voicemessage.model

// ─────────────────────────────────────────────────────────────────────────────
// Firestore schema
// ─────────────────────────────────────────────────────────────────────────────
//
// Collection: chats
//   Document:  {chatId}   ← sorted "uid1_uid2"
//     Fields:
//       chatId:        String
//       participants:  [uid1, uid2]
//       lastMessage:   String
//       lastTimestamp: Long
//       createdAt:     Timestamp (server)
//
// Sub-collection: chats/{chatId}/messages
//   Document:  {messageId}
//     Fields:
//       messageId:     String
//       chatId:        String
//       senderId:      String   ← Firebase Auth UID
//       receiverId:    String   ← Firebase Auth UID
//       audioUrl:      String   ← Firebase Storage download URL
//       localPath:     String   ← always "" in Firestore (device-local only)
//       durationMs:    Long
//       fileSize:      Long     ← bytes
//       timestamp:     Long     ← epoch ms, set by client for ordering
//       uploadStatus:  String   ← PENDING | UPLOADING | DONE | FAILED
//       messageStatus: String   ← SENT | DELIVERED | SEEN
//
// Collection: users
//   Document:  {uid}
//     Fields:
//       uid:           String
//       displayName:   String
//       phoneNumber:   String   ← E.164, e.g. "+919876543210"
//       fcmToken:      String
//
// Collection: fcm_requests
//   Document:  {auto-id}
//     Fields:
//       token:         String
//       chatId:        String
//       senderId:      String
//       senderName:    String
//       timestamp:     Long
//   (Consumed by Cloud Function, then deleted)
//
// ─────────────────────────────────────────────────────────────────────────────

data class VoiceMessage(
    val messageId: String = "",
    val chatId: String = "",
    val senderId: String = "",
    val receiverId: String = "",
    val audioUrl: String = "",
    val localPath: String = "",     // device-local only, never written to Firestore
    val durationMs: Long = 0L,
    val fileSize: Long = 0L,
    val timestamp: Long = 0L,
    val uploadStatus: UploadStatus = UploadStatus.DONE,
    val messageStatus: MessageStatus = MessageStatus.SENT
) {
    /** Converts to a plain Map for Firestore writes. */
    fun toFirestoreMap(): Map<String, Any> = mapOf(
        "messageId"     to messageId,
        "chatId"        to chatId,
        "senderId"      to senderId,
        "receiverId"    to receiverId,
        "audioUrl"      to audioUrl,
        "localPath"     to "",          // never persist local path
        "durationMs"    to durationMs,
        "fileSize"      to fileSize,
        "timestamp"     to timestamp,
        "uploadStatus"  to uploadStatus.name,
        "messageStatus" to messageStatus.name
    )
}

enum class UploadStatus  { PENDING, UPLOADING, DONE, FAILED }
enum class MessageStatus { SENT, DELIVERED, SEEN }

// ── User profile ──────────────────────────────────────────────────────────────

data class UserProfile(
    val uid: String = "",
    val displayName: String = "",
    val phoneNumber: String = "",
    val fcmToken: String = ""
)

// ── Chat room ─────────────────────────────────────────────────────────────────

data class ChatRoom(
    val chatId: String = "",
    val participants: List<String> = emptyList(),
    val lastMessage: String = "",
    val lastTimestamp: Long = 0L
)

// ── UI model ──────────────────────────────────────────────────────────────────

data class VoiceMessageUiModel(
    val message: VoiceMessage,
    val isOwnMessage: Boolean,
    val playbackState: PlaybackState = PlaybackState.IDLE,
    val playbackPositionMs: Long = 0L,
    val uploadProgressPercent: Int = 0
)

enum class PlaybackState { IDLE, LOADING, PLAYING, PAUSED }
