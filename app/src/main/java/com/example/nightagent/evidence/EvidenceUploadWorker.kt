package com.example.nightagent.evidence

import android.content.Context
import android.util.Log
import androidx.work.*
import com.example.nightagent.firebase.FirebaseConfig
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * WorkManager worker that uploads all evidence files for a given sosId.
 *
 * Firebase Storage layout:
 *   evidence/{userId}/{sosId}/audio/file.m4a
 *   evidence/{userId}/{sosId}/photos/photo.jpg
 *   evidence/{userId}/{sosId}/video/video.mp4
 *
 * Firestore record written to:
 *   evidence/{sosId}  → { userId, sosId, timestamp, fileUrls, hashes }
 */
class EvidenceUploadWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val sosId  = inputData.getString(KEY_SOS_ID)  ?: return@withContext Result.failure()
        val userId = inputData.getString(KEY_USER_ID) ?: return@withContext Result.failure()

        val evidenceDir = File(applicationContext.getExternalFilesDir(null), "Evidence/$sosId")
        if (!evidenceDir.exists()) return@withContext Result.failure()

        val evidenceManager = EvidenceManager(applicationContext)
        val uploadedUrls = mutableMapOf<String, String>()
        val hashes       = mutableMapOf<String, String>()

        try {
            // Walk all sub-directories (audio/, photos/, video/)
            evidenceDir.walkTopDown()
                .filter { it.isFile && it.length() > 0 }
                .forEach { file ->
                    val relativePath = file.relativeTo(evidenceDir).path
                    val storagePath  = "evidence/$userId/$sosId/$relativePath"

                    val storageRef = FirebaseConfig.storage.reference.child(storagePath)
                    val snapshot   = storageRef.putFile(android.net.Uri.fromFile(file)).await()
                    val url        = snapshot.storage.downloadUrl.await().toString()

                    uploadedUrls[relativePath] = url
                    hashes[relativePath]       = evidenceManager.sha256(file)

                    Log.d(TAG, "Uploaded: $relativePath → $url")
                }

            // Write Firestore evidence record
            val record = mapOf(
                "userId"    to userId,
                "sosId"     to sosId,
                "timestamp" to System.currentTimeMillis(),
                "fileUrls"  to uploadedUrls,
                "hashes"    to hashes
            )
            FirebaseConfig.firestore
                .collection("evidence")
                .document(sosId)
                .set(record, SetOptions.merge())
                .await()

            Log.d(TAG, "Evidence record written for $sosId")
            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Upload failed: ${e.message}")
            if (runAttemptCount < 3) Result.retry() else Result.failure()
        }
    }

    companion object {
        private const val TAG         = "EvidenceUploadWorker"
        const val KEY_SOS_ID          = "sos_id"
        const val KEY_USER_ID         = "user_id"

        fun enqueue(context: Context, sosId: String, userId: String) {
            val data = workDataOf(KEY_SOS_ID to sosId, KEY_USER_ID to userId)
            val request = OneTimeWorkRequestBuilder<EvidenceUploadWorker>()
                .setInputData(data)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()

            WorkManager.getInstance(context)
                .enqueueUniqueWork(
                    "evidence_upload_$sosId",
                    ExistingWorkPolicy.REPLACE,
                    request
                )
        }
    }
}
