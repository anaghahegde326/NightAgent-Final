package com.example.nightagent.firebase

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.nightagent.MainActivity
import com.example.nightagent.R
import com.example.nightagent.notifications.NotificationHelper
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

/**
 * Handles incoming FCM messages.
 *
 * Voice message payload format (sent by the sender's device via a Cloud Function
 * or directly from the app — see VoiceMessageFirebase.sendPushNotification):
 *
 *   data: {
 *     type:        "voice_message"
 *     senderId:    "<uid>"
 *     senderName:  "<display name or phone>"
 *     conversationId: "<convId>"
 *   }
 *
 * When the app is in the background, FCM delivers this as a data-only message
 * so Android shows our custom notification (not a generic FCM one).
 */
class MyFirebaseMessagingService : FirebaseMessagingService() {

    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        Log.d(TAG, "FCM received from: ${remoteMessage.from}")

        val data = remoteMessage.data

        when (data["type"]) {
            "voice_message" -> handleVoiceMessageNotification(data)
            else            -> handleGenericNotification(remoteMessage)
        }
    }

    // ── Voice message notification ────────────────────────────────────────────

    private fun handleVoiceMessageNotification(data: Map<String, String>) {
        val senderName     = data["senderName"] ?: "Someone"
        val senderId       = data["senderId"] ?: return
        val conversationId = data["conversationId"] ?: ""

        // Deep-link intent: opens MainActivity which navigates to voicechat/{senderId}
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("navigate_to", "voicechat/$senderId")
        }
        val pendingIntent = PendingIntent.getActivity(
            this, senderId.hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        createVoiceMessageChannel()

        val notification = NotificationCompat.Builder(this, CHANNEL_VOICE)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("🎙 Voice message from $senderName")
            .setContentText("Tap to listen")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(senderId.hashCode(), notification)
    }

    // ── Generic notification ──────────────────────────────────────────────────

    private fun handleGenericNotification(remoteMessage: RemoteMessage) {
        val title = remoteMessage.notification?.title
            ?: remoteMessage.data["title"]
            ?: "NightAgent"
        val body = remoteMessage.notification?.body
            ?: remoteMessage.data["body"]
            ?: "New notification"
        NotificationHelper.show(this, "$title: $body")
    }

    // ── Token refresh ─────────────────────────────────────────────────────────

    override fun onNewToken(token: String) {
        Log.d(TAG, "FCM token refreshed")
        // Save the new token to Firestore so other users can reach this device
        AuthManager.saveFCMToken(token)
    }

    // ── Channel setup ─────────────────────────────────────────────────────────

    private fun createVoiceMessageChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_VOICE,
                "Voice Messages",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Incoming voice message notifications"
                enableVibration(true)
            }
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }
    }

    companion object {
        private const val TAG          = "MyFCMService"
        private const val CHANNEL_VOICE = "voice_messages_channel"
    }
}
