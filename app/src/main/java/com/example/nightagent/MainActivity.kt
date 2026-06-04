package com.example.nightagent

import android.Manifest
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.snapshotFlow
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope

import com.example.nightagent.navigation.NavGraph
import com.example.nightagent.sos.*
import com.example.nightagent.firebase.AuthManager
import com.example.nightagent.stealth.StealthDetectionService
import com.example.nightagent.stealth.StealthRepository
import com.example.nightagent.ui.theme.NightagentTheme
import com.example.nightagent.voicemessage.firebase.VoiceMessageFirebase
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

class MainActivity : ComponentActivity() {

    private val smsPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (!granted) Toast.makeText(this, "SMS permission required", Toast.LENGTH_LONG).show()
        }

    private val locationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (!granted) Toast.makeText(this, "Location permission required", Toast.LENGTH_LONG).show()
        }

    private val cameraPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (!granted) Toast.makeText(this, "Camera permission required for evidence capture", Toast.LENGTH_LONG).show()
        }

    private val audioPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (!granted) Toast.makeText(this, "Microphone permission required for Voice SOS", Toast.LENGTH_LONG).show()
        }

    private lateinit var voiceSOS: VoiceSOSManager
    private lateinit var shakeDetector: ShakeDetector
    private lateinit var powerReceiver: PowerButtonReceiver

    override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)

    // Draw content edge-to-edge so Compose controls all inset handling
    enableEdgeToEdge()

    requestPermissionsIfNeeded()

    // 🕵️ Resume StealthDetectionService if Stealth Mode was previously enabled
    val stealthRepo = StealthRepository(this)
    if (stealthRepo.isStealthModeEnabled.value) {
        StealthDetectionService.start(this)
    }

    // 🔥 FIREBASE AUTH TEST (UPDATED)
    lifecycleScope.launch(Dispatchers.Main) {
        val result = AuthManager.loginAnonymous()

        result.onSuccess {
            Log.d("AUTH_TEST", "SUCCESS: $it")
            Log.d("Auth", "User ID: $it")
        }.onFailure {
            Log.e("AUTH_TEST", "FAIL: ${it.message}")
            Log.e("Auth", "Login failed: ${it.message}")
        }
    }

    // 🔥 FCM TOKEN — also update in Firestore for voice message push notifications
    FirebaseMessaging.getInstance().token.addOnCompleteListener {
        if (it.isSuccessful) {
            val token = it.result
            Log.d("FCM", "Token: $token")
            AuthManager.saveFCMToken(token)
            // Also update in the users collection for voice messaging
            val uid = FirebaseAuth.getInstance().currentUser?.uid
            if (uid != null) {
                lifecycleScope.launch(Dispatchers.IO) {
                    VoiceMessageFirebase.updateFcmToken(uid, token)
                }
            }
        } else {
            Log.e("FCM", "Token failed", it.exception)
        }
    }
        shakeDetector = ShakeDetector(this) {
            SOSManager.triggerSOS(this)
        }

        voiceSOS = VoiceSOSManager(this) {
            SOSManager.triggerSOS(this)
        }

        // 🔥 Only enable if toggle is already ON (e.g. persisted from last session)
        if (SafetySettings.voiceSOS.value) {
            voiceSOS.enable()
        }

        // 🔥 Observe toggle changes and react in real time
        lifecycleScope.launch {
            snapshotFlow { SafetySettings.voiceSOS.value }
                .collect { isEnabled ->
                    Log.d("VoiceSOS", "Toggle changed → $isEnabled")
                    if (isEnabled) voiceSOS.enable() else voiceSOS.disable()
                }
        }

        powerReceiver = PowerButtonReceiver()
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        }
        registerReceiver(powerReceiver, filter)

        setContent {
            NightagentTheme {
                NavGraph(
                    onShareLocationClick = {
                        if (!hasLocationPermission()) {
                            requestLocationPermission()
                            return@NavGraph
                        }
                        LocationProvider.getLocation(this@MainActivity) { location ->
                            if (location != null) {
                                val link = "https://maps.google.com/?q=${location.latitude},${location.longitude}"
                                val intent = Intent(Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(Intent.EXTRA_TEXT, "My location:\n$link")
                                }
                                startActivity(Intent.createChooser(intent, "Share Location"))
                            } else {
                                Toast.makeText(this@MainActivity, "Location unavailable", Toast.LENGTH_SHORT).show()
                            }
                        }
                    },
                    // Deep-link from FCM notification: "voicechat/{senderId}"
                    initialRoute = intent?.getStringExtra("navigate_to")
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        shakeDetector.start()

        // 🔥 Only resume mic if toggle is ON
        if (SafetySettings.voiceSOS.value) {
            voiceSOS.enable()
        }
    }

    override fun onPause() {
        super.onPause()
        shakeDetector.stop()
        // Stop mic when app is paused but DON'T change isEnabled —
        // so onResume can correctly restart it if toggle is still ON
        voiceSOS.stopListening()
    }

    override fun onDestroy() {
        super.onDestroy()
        voiceSOS.destroy()
        unregisterReceiver(powerReceiver)
    }

    private fun requestPermissionsIfNeeded() {
        if (!hasSmsPermission()) smsPermissionLauncher.launch(Manifest.permission.SEND_SMS)
        if (!hasLocationPermission()) locationPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
        if (!hasAudioPermission()) audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        if (!hasCameraPermission()) cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
    }

    private fun hasSmsPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED

    private fun hasLocationPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun hasAudioPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun hasCameraPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    private fun requestLocationPermission() {
        locationPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
    }
}