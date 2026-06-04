package com.example.nightagent.streaming

import android.util.Log
import com.example.nightagent.firebase.FirebaseConfig
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await

/**
 * Firestore schema used for WebRTC signaling:
 *
 *   calls/{callId}/
 *     offer:           { sdp: String, type: "offer" }
 *     answer:          { sdp: String, type: "answer" }
 *     callerUid:       String
 *     calleeUid:       String   — one of the emergency contact UIDs
 *     status:          "ringing" | "active" | "ended"
 *     createdAt:       Long
 *
 *   calls/{callId}/callerCandidates/{id}
 *     sdpMid:          String
 *     sdpMLineIndex:   Int
 *     candidate:       String
 *
 *   calls/{callId}/calleeCandidates/{id}
 *     sdpMid:          String
 *     sdpMLineIndex:   Int
 *     candidate:       String
 *
 * Firestore Security Rules (paste into Firebase Console → Firestore → Rules):
 *
 *   rules_version = '2';
 *   service cloud.firestore {
 *     match /databases/{database}/documents {
 *       match /calls/{callId} {
 *         allow read, write: if request.auth != null &&
 *           (request.auth.uid == resource.data.callerUid ||
 *            request.auth.uid == resource.data.calleeUid);
 *         allow create: if request.auth != null &&
 *           request.auth.uid == request.resource.data.callerUid;
 *
 *         match /callerCandidates/{id} {
 *           allow read, write: if request.auth != null;
 *         }
 *         match /calleeCandidates/{id} {
 *           allow read, write: if request.auth != null;
 *         }
 *       }
 *     }
 *   }
 */
class SignalingRepository {

    private val db get() = FirebaseConfig.firestore

    // ── Call document ──────────────────────────────────────────────────────────

    /** Create a new call document. Returns the generated callId. */
    suspend fun createCall(callerUid: String, calleeUid: String): String {
        val doc = db.collection(CALLS).document()
        doc.set(mapOf(
            "callerUid"  to callerUid,
            "calleeUid"  to calleeUid,
            "status"     to STATUS_RINGING,
            "createdAt"  to System.currentTimeMillis()
        )).await()
        Log.d(TAG, "Call document created: ${doc.id}")
        return doc.id
    }

    /** Write the SDP offer. */
    suspend fun setOffer(callId: String, sdp: String) {
        db.collection(CALLS).document(callId)
            .set(mapOf("offer" to mapOf("type" to "offer", "sdp" to sdp)), SetOptions.merge())
            .await()
        Log.d(TAG, "Offer written for $callId")
    }

    /** Write the SDP answer. */
    suspend fun setAnswer(callId: String, sdp: String) {
        db.collection(CALLS).document(callId)
            .set(mapOf(
                "answer" to mapOf("type" to "answer", "sdp" to sdp),
                "status" to STATUS_ACTIVE
            ), SetOptions.merge())
            .await()
        Log.d(TAG, "Answer written for $callId")
    }

    /** Add an ICE candidate from the caller side. */
    suspend fun addCallerCandidate(callId: String, candidate: IceCandidateData) {
        db.collection(CALLS).document(callId)
            .collection(CALLER_CANDIDATES)
            .add(candidate.toMap())
            .await()
    }

    /** Add an ICE candidate from the callee side. */
    suspend fun addCalleeCandidate(callId: String, candidate: IceCandidateData) {
        db.collection(CALLS).document(callId)
            .collection(CALLEE_CANDIDATES)
            .add(candidate.toMap())
            .await()
    }

    /** Mark call as ended so both sides can clean up. */
    suspend fun endCall(callId: String) {
        try {
            db.collection(CALLS).document(callId)
                .update("status", STATUS_ENDED)
                .await()
            Log.d(TAG, "Call ended: $callId")
        } catch (e: Exception) {
            Log.e(TAG, "endCall failed: ${e.message}")
        }
    }

    // ── Real-time listeners ────────────────────────────────────────────────────

    /** Emits the SDP answer as soon as the callee writes it. */
    fun answerFlow(callId: String): Flow<String?> = callbackFlow {
        val reg = db.collection(CALLS).document(callId)
            .addSnapshotListener { snap, err ->
                if (err != null) { Log.e(TAG, "answerFlow error: ${err.message}"); return@addSnapshotListener }
                val answerMap = snap?.get("answer") as? Map<*, *>
                val sdp = answerMap?.get("sdp") as? String
                if (sdp != null) trySend(sdp)
            }
        awaitClose { reg.remove() }
    }

    /** Emits callee ICE candidates as they arrive (caller listens to this). */
    fun calleeCandidatesFlow(callId: String): Flow<IceCandidateData> = callbackFlow {
        val reg = db.collection(CALLS).document(callId)
            .collection(CALLEE_CANDIDATES)
            .addSnapshotListener { snap, err ->
                if (err != null) return@addSnapshotListener
                snap?.documentChanges?.forEach { change ->
                    val d = change.document
                    trySend(IceCandidateData(
                        sdpMid        = d.getString("sdpMid") ?: return@forEach,
                        sdpMLineIndex = d.getLong("sdpMLineIndex")?.toInt() ?: 0,
                        candidate     = d.getString("candidate") ?: return@forEach
                    ))
                }
            }
        awaitClose { reg.remove() }
    }

    /** Emits caller ICE candidates as they arrive (callee listens to this). */
    fun callerCandidatesFlow(callId: String): Flow<IceCandidateData> = callbackFlow {
        val reg = db.collection(CALLS).document(callId)
            .collection(CALLER_CANDIDATES)
            .addSnapshotListener { snap, err ->
                if (err != null) return@addSnapshotListener
                snap?.documentChanges?.forEach { change ->
                    val d = change.document
                    trySend(IceCandidateData(
                        sdpMid        = d.getString("sdpMid") ?: return@forEach,
                        sdpMLineIndex = d.getLong("sdpMLineIndex")?.toInt() ?: 0,
                        candidate     = d.getString("candidate") ?: return@forEach
                    ))
                }
            }
        awaitClose { reg.remove() }
    }

    /** Emits the offer SDP as soon as the caller writes it (callee side). */
    fun offerFlow(callId: String): Flow<String?> = callbackFlow {
        val reg = db.collection(CALLS).document(callId)
            .addSnapshotListener { snap, err ->
                if (err != null) return@addSnapshotListener
                val offerMap = snap?.get("offer") as? Map<*, *>
                val sdp = offerMap?.get("sdp") as? String
                if (sdp != null) trySend(sdp)
            }
        awaitClose { reg.remove() }
    }

    /** Emits call status changes ("ringing", "active", "ended"). */
    fun statusFlow(callId: String): Flow<String> = callbackFlow {
        val reg = db.collection(CALLS).document(callId)
            .addSnapshotListener { snap, err ->
                if (err != null) return@addSnapshotListener
                val status = snap?.getString("status") ?: return@addSnapshotListener
                trySend(status)
            }
        awaitClose { reg.remove() }
    }

    companion object {
        private const val TAG              = "SignalingRepo"
        const val CALLS                    = "calls"
        private const val CALLER_CANDIDATES = "callerCandidates"
        private const val CALLEE_CANDIDATES = "calleeCandidates"
        const val STATUS_RINGING           = "ringing"
        const val STATUS_ACTIVE            = "active"
        const val STATUS_ENDED             = "ended"
    }
}

data class IceCandidateData(
    val sdpMid: String,
    val sdpMLineIndex: Int,
    val candidate: String
) {
    fun toMap() = mapOf(
        "sdpMid"        to sdpMid,
        "sdpMLineIndex" to sdpMLineIndex,
        "candidate"     to candidate
    )
}
