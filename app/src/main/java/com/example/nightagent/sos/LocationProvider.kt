package com.example.nightagent.sos

import android.Manifest
import android.content.Context
import android.location.Location
import android.util.Log
import androidx.core.content.ContextCompat
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.location.*

object LocationProvider {

    fun getLocation(
        context: Context,
        callback: (Location?) -> Unit
    ) {

        // 🔥 Check Google Play Services
        val resultCode = GoogleApiAvailability.getInstance()
            .isGooglePlayServicesAvailable(context)

        if (resultCode != ConnectionResult.SUCCESS) {
            Log.e("LocationProvider", "Google Play Services unavailable")
            callback(null)
            return
        }

        // 🔥 Check permission
        val hasPermission =
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
                    android.content.pm.PackageManager.PERMISSION_GRANTED

        if (!hasPermission) {
            Log.e("LocationProvider", "Location permission not granted")
            callback(null)
            return
        }

        val fusedLocationClient =
            LocationServices.getFusedLocationProviderClient(context)

        val request = CurrentLocationRequest.Builder()
            .setPriority(Priority.PRIORITY_HIGH_ACCURACY)
            .setMaxUpdateAgeMillis(15000)
            .build()

        // FIX: Hold a reference to CancellationTokenSource so its token can
        // be cancelled after the task resolves — previously the source was
        // discarded immediately, keeping the underlying task alive even after
        // the caller's scope was destroyed (leaked FusedLocation task).
        val cts = com.google.android.gms.tasks.CancellationTokenSource()

        fusedLocationClient.getCurrentLocation(request, cts.token)
            .addOnSuccessListener { location ->
                cts.cancel()
                if (location != null) {
                    Log.d("LocationProvider", "Location: ${location.latitude}, ${location.longitude}")
                    callback(location)
                } else {
                    Log.e("LocationProvider", "Location is NULL — GPS may be off")
                    callback(null)
                }
            }
            .addOnFailureListener {
                cts.cancel()
                Log.e("LocationProvider", "Location failed: ${it.message}")
                callback(null)
            }
    }
}