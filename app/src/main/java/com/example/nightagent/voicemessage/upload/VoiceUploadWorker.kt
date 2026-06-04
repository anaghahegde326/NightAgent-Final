package com.example.nightagent.voicemessage.upload

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkRequest
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.example.nightagent.voicemessage.firebase.VoiceMessageFirebase
import com.example.nightagent.voicemessage.model.MessageStatus
import com.example.nightagent.voicemessage.model.UploadStatus
import com.example.nightagent.voicemessage.model.VoiceMessage
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * WorkManager worker — background upload + Firestore save with retry.
 *
 * Survives app backgrounding and process death.
 * Uses exponential backoff: retries up to 3 times on failure.
 *
 * Updated to use the new schema:
 *   - chatId (not conversationId)
 *   - messageStatus (not seenStatus)
 *   - chats/{chatId}/messages path (not voice_messages)
 */
class VoiceUploadWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val filePath   = inputData.getString(KEY_FILE_PATH)   ?: return Result.failure()
        val messageId  = inputData.getString(KEY_MESSAGE_ID)  ?: return Result.failure()
        val senderId   = inputData.getString(KEY_SENDER_ID)   ?: return Result.failure()
        val receiverId = inputData.getString(KEY_RECEIVER_ID) ?: return Result.failure()
        val durationMs = inputData.getLong(KEY_DURATION_MS, 0L)
        val fileSize   = inputData.getLong(KEY_FILE_SIZE, 0L)

        val file = File(filePath)
        if (!file.exists()) {
            Log.e(TAG, "Audio file not found: $filePath — failing permanently")
            return Result.failure()
        }

        Log.d(TAG, "Worker starting: msgId=$messageId attempt=$runAttemptCount")

        // ── Step 1: Upload to Firebase Storage ───────────────────────────────
        val uploadResult = VoiceMessageFirebase.uploadAudio(
            file       = file,
            senderId   = senderId,
            messageId  = messageId,
            onProgress = { pct ->
                setProgressAsync(workDataOf(KEY_PROGRESS to pct))
            }
        )

        if (uploadResult.isFailure) {
            Log.e(TAG, "Upload failed: ${uploadResult.exceptionOrNull()?.message}")
            return if (runAttemptCount < MAX_RETRIES) Result.retry() else Result.failure()
        }

        val audioUrl = uploadResult.getOrThrow()
        val cid = VoiceMessageFirebase.chatId(senderId, receiverId)

        // ── Step 2: Ensure chat room exists ──────────────────────────────────
        VoiceMessageFirebase.ensureChatRoom(senderId, receiverId)

        // ── Step 3: Save Firestore document ──────────────────────────────────
        val message = VoiceMessage(
            messageId     = messageId,
            chatId        = cid,
            senderId      = senderId,
            receiverId    = receiverId,
            audioUrl      = audioUrl,
            localPath     = "",
            durationMs    = durationMs,
            fileSize      = fileSize,
            timestamp     = System.currentTimeMillis(),
            uploadStatus  = UploadStatus.DONE,
            messageStatus = MessageStatus.SENT
        )

        val saveResult = VoiceMessageFirebase.saveMessage(message)
        if (saveResult.isFailure) {
            Log.e(TAG, "Firestore save failed: ${saveResult.exceptionOrNull()?.message}")
            return if (runAttemptCount < MAX_RETRIES) Result.retry() else Result.failure()
        }

        // ── Step 4: Queue push notification ──────────────────────────────────
        val senderProfile = VoiceMessageFirebase.getUserProfile(senderId)
        val senderName = senderProfile?.displayName?.ifBlank { senderId.take(8) } ?: senderId.take(8)
        VoiceMessageFirebase.queuePushNotification(
            receiverUid = receiverId,
            senderUid   = senderId,
            senderName  = senderName,
            chatId      = cid
        )

        // ── Step 5: Clean up local file ───────────────────────────────────────
        try { file.delete() } catch (_: Exception) { }

        Log.d(TAG, "Worker complete: msgId=$messageId chatId=$cid")
        return Result.success()
    }

    companion object {
        private const val TAG          = "VOICE_UPLOAD"
        private const val MAX_RETRIES  = 3

        const val KEY_FILE_PATH   = "file_path"
        const val KEY_MESSAGE_ID  = "message_id"
        const val KEY_SENDER_ID   = "sender_id"
        const val KEY_RECEIVER_ID = "receiver_id"
        const val KEY_DURATION_MS = "duration_ms"
        const val KEY_FILE_SIZE   = "file_size"
        const val KEY_PROGRESS    = "progress"

        /**
         * Enqueue a background upload.
         * Uses KEEP policy — safe to call multiple times with the same messageId.
         */
        fun enqueue(
            context: Context,
            filePath: String,
            messageId: String,
            senderId: String,
            receiverId: String,
            durationMs: Long,
            fileSize: Long = 0L
        ) {
            val data = workDataOf(
                KEY_FILE_PATH   to filePath,
                KEY_MESSAGE_ID  to messageId,
                KEY_SENDER_ID   to senderId,
                KEY_RECEIVER_ID to receiverId,
                KEY_DURATION_MS to durationMs,
                KEY_FILE_SIZE   to fileSize
            )

            val request = OneTimeWorkRequestBuilder<VoiceUploadWorker>()
                .setInputData(data)
                // FIX 10: Require network connectivity before the worker runs.
                // Without this constraint, WorkManager launches the worker
                // immediately with no network, causing an immediate failure
                // followed by exponential backoff delays instead of simply
                // waiting for connectivity to be restored.
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .setBackoffCriteria(
                    BackoffPolicy.EXPONENTIAL,
                    WorkRequest.MIN_BACKOFF_MILLIS,
                    TimeUnit.MILLISECONDS
                )
                .addTag("voice_upload_$messageId")
                .build()

            WorkManager.getInstance(context)
                .enqueueUniqueWork(
                    "voice_upload_$messageId",
                    ExistingWorkPolicy.KEEP,
                    request
                )

            Log.d(TAG, "Enqueued background upload: msgId=$messageId")
        }
    }
}
