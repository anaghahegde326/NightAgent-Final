package com.example.nightagent.sos

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.telephony.SmsManager
import android.util.Log
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.example.nightagent.R
import com.example.nightagent.contacts.ContactManager
import com.example.nightagent.notifications.NotificationHelper
import com.example.nightagent.firebase.FirestoreManager

object SOSManager {

    private var mediaPlayer: MediaPlayer? = null
    private var sosPending = false
    private val mainHandler = Handler(Looper.getMainLooper())

    fun triggerSOS(
        context: Context,
        onSOSComplete: () -> Unit = {}
    ) {
        if (sosPending) return
        sosPending = true

        Log.d("TRACKING", "SOS STARTED")

        Toast.makeText(context, "SOS Triggered!", Toast.LENGTH_SHORT).show()

        if (!SafetySettings.silentMode.value) {
            NotificationHelper.show(context, "SOS Triggered")
            mediaPlayer = MediaPlayer.create(context, R.raw.alarm_sos)
            mediaPlayer?.isLooping = true
            mediaPlayer?.start()
        }

        Log.d("TRACKING", "SOS location request starting")
        LocationProvider.getLocation(context) { location ->

            Log.d("TRACKING", "LOCATION CALLBACK HIT locationNull=${location == null}")

            val message = if (location != null) {
                """🚨 SOS ALERT!

I need help.

My current location:
https://maps.google.com/?q=${location.latitude},${location.longitude}"""
            } else {
                """🚨 SOS ALERT!

I need help."""
            }


            // 🔥 START FIRESTORE SESSION
            FirestoreManager.startSOSSession(
                message,
                location?.latitude,
                location?.longitude
            )

            Log.d("TRACKING", "SESSION STARTED")

            // 🔥 START TRACKING
            startLiveTracking(context)

            // 🔥 SEND SMS
            sendEmergencySMS(context, message)
        }

        // Stop alarm after 15s
        mainHandler.postDelayed({ stopAlarm() }, 15000)

        // End SOS after 30s (IMPORTANT → extended)
        mainHandler.postDelayed({
            sosPending = false
            Log.d("TRACKING", "SOS ENDED")
            Toast.makeText(context, "SOS Completed", Toast.LENGTH_SHORT).show()
            onSOSComplete()
        }, 30000)
    }

    // 🔥 LIVE TRACKING FIXED
    private fun startLiveTracking(context: Context) {

        Log.d("TRACKING", "startLiveTracking CALLED")

        val handler = Handler(Looper.getMainLooper())

        val runnable = object : Runnable {
            override fun run() {

                Log.d("TRACKING", "Loop running, sosPending=$sosPending")

                if (!sosPending) return

                LocationProvider.getLocation(context) { location ->

                    if (location != null) {
                        FirestoreManager.updateLocation(
                            location.latitude,
                            location.longitude
                        )

                        Log.d(
                            "TRACKING",
                            "Location updated: ${location.latitude}, ${location.longitude}"
                        )
                    } else {
                        Log.d("TRACKING", "Location NULL in loop")
                    }
                }

                handler.postDelayed(this, 5000)
            }
        }

        handler.post(runnable)
    }

    private fun sendEmergencySMS(context: Context, message: String) {

        Toast.makeText(context, "Sending SOS SMS...", Toast.LENGTH_SHORT).show()

        val contacts = ContactManager.getContacts(context)
        Log.d("TRACKING", "SOS emergency contacts loaded size=${contacts.size}")

        if (contacts.isEmpty()) {
            Toast.makeText(context, "No emergency contacts found", Toast.LENGTH_SHORT).show()
            Log.e("TRACKING", "NO CONTACTS")
            return
        }

        val hasPermission = ContextCompat.checkSelfPermission(
            context, Manifest.permission.SEND_SMS
        ) == PackageManager.PERMISSION_GRANTED

        Log.d("TRACKING", "SEND_SMS permission granted=$hasPermission")

        if (!hasPermission) {
            Log.e("TRACKING", "NO SMS PERMISSION")
            Toast.makeText(context, "SMS permission not granted", Toast.LENGTH_SHORT).show()
            return
        }

        val smsManager = SmsManager.getDefault()

        var anySmsSent = false
        var anySmsFailed = false

        for (contact in contacts) {
            val phone = contact.phone.trim()
            Log.d("TRACKING", "Attempting SMS to phone=$phone messageLen=${message.length}")

            if (phone.isBlank()) {
                anySmsFailed = true
                Log.e("TRACKING", "Blank phone in contact")
                continue
            }

            try {
                // Split message into parts if needed (SMS length limit: 160 chars for ASCII, 70 for Unicode)
                val parts = smsManager.divideMessage(message)
                Log.d("TRACKING", "Message split into ${parts.size} parts")

                if (parts.size == 1) {
                    // Single SMS
                    smsManager.sendTextMessage(phone, null, message, null, null)
                } else {
                    // Multiple SMS parts (for longer messages)
                    val sentIntents = arrayListOf<android.app.PendingIntent>()
                    val deliveryIntents = arrayListOf<android.app.PendingIntent>()
                    
                    for (partIndex in parts.indices) {
                        sentIntents.add(android.app.PendingIntent.getBroadcast(
                            context, 
                            partIndex, 
                            android.content.Intent("SMS_SENT_$partIndex"), 
                            android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT
                        ))
                        deliveryIntents.add(android.app.PendingIntent.getBroadcast(
                            context, 
                            partIndex, 
                            android.content.Intent("SMS_DELIVERED_$partIndex"), 
                            android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT
                        ))
                    }
                    
                    smsManager.sendMultipartTextMessage(phone, null, parts, sentIntents, deliveryIntents)
                }
                
                anySmsSent = true
                Log.d("TRACKING", "SMS sent to $phone")
            } catch (e: Exception) {
                anySmsFailed = true
                Log.e("TRACKING", "SMS FAILED to $phone type=${e.javaClass.name} msg=${e.message}", e)
            }
        }

        if (anySmsSent) {
            Toast.makeText(context, "SOS SMS sent", Toast.LENGTH_SHORT).show()
        }

        if (anySmsFailed && !anySmsSent) {
            Toast.makeText(context, "Failed to send SOS SMS", Toast.LENGTH_SHORT).show()
        }
    }

    private fun stopAlarm() {
        try {
            mediaPlayer?.stop()
            mediaPlayer?.release()
        } catch (_: Exception) {
        } finally {
            mediaPlayer = null
        }
    }
}