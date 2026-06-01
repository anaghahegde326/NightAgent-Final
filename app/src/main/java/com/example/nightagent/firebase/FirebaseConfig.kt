package com.example.nightagent.firebase

import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreSettings
import com.google.firebase.firestore.PersistentCacheSettings
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.storage.FirebaseStorage

object FirebaseConfig {

    val firestore: FirebaseFirestore by lazy {
        FirebaseFirestore.getInstance().also { db ->
            // Enable offline persistence with a 100 MB cache.
            // This means:
            //  - Messages load instantly from cache even with no network
            //  - Firestore listeners still fire from cache while reconnecting
            //  - Writes are queued locally and synced when network returns
            try {
                val settings = FirebaseFirestoreSettings.Builder()
                    .setLocalCacheSettings(
                        PersistentCacheSettings.newBuilder()
                            .setSizeBytes(100L * 1024 * 1024) // 100 MB
                            .build()
                    )
                    .build()
                db.firestoreSettings = settings
                Log.d("FirebaseConfig", "Firestore offline persistence enabled (100 MB cache)")
            } catch (e: Exception) {
                Log.w("FirebaseConfig", "Could not configure Firestore settings: ${e.message}")
            }
        }
    }

    val storage: FirebaseStorage by lazy {
        FirebaseStorage.getInstance()
    }

    val auth: FirebaseAuth by lazy {
        FirebaseAuth.getInstance()
    }

    val messaging: FirebaseMessaging by lazy {
        FirebaseMessaging.getInstance()
    }
}
