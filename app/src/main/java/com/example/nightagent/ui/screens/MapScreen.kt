package com.example.nightagent.ui.screens

import android.Manifest
import android.content.Context
import android.content.Intent
import android.location.Location
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Directions
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.nightagent.sos.LocationProvider
import com.example.nightagent.ui.theme.BlushPink
import com.example.nightagent.ui.theme.Lavender
import com.example.nightagent.viewmodel.SafetyViewModel
import org.json.JSONObject
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import java.net.HttpURLConnection
import java.net.URL

@Composable
fun MapScreen(
    safetyViewModel: SafetyViewModel = viewModel(factory = SafetyViewModel.Factory())
) {
    val context = LocalContext.current
    val safetyState by safetyViewModel.uiState.collectAsState()
    var userLocation by remember { mutableStateOf<GeoPoint?>(null) }
    var mapViewRef by remember { mutableStateOf<MapView?>(null) }
    var locationMessage by remember { mutableStateOf<String?>(null) }
    var showRiskDialog by remember { mutableStateOf(false) }

    fun loadLocationAndSafety() {
        LocationProvider.getLocation(context) { location: Location? ->
            if (location == null) {
                locationMessage = "Location unavailable. Turn on GPS and try again."
                safetyViewModel.loadCachedScore(context)
                return@getLocation
            }

            locationMessage = null
            userLocation = GeoPoint(location.latitude, location.longitude)
            safetyViewModel.fetchSafetyScore(context, location.latitude, location.longitude)
        }
    }

    val locationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val granted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true

        if (granted) {
            loadLocationAndSafety()
        } else {
            locationMessage = "Location permission is needed to show your safety score."
            safetyViewModel.loadCachedScore(context)
        }
    }

    // Get current location
    LaunchedEffect(Unit) {
        if (hasLocationPermission(context)) {
            loadLocationAndSafety()
        } else {
            locationPermissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
        }
    }

    LaunchedEffect(userLocation, safetyState.safetyResponse) {
        val location = userLocation ?: return@LaunchedEffect
        val mapView = mapViewRef ?: return@LaunchedEffect
        updateSafetyOverlay(mapView, location, safetyState.safetyResponse)
    }

    LaunchedEffect(safetyState.safetyResponse?.riskLevel) {
        showRiskDialog = safetyState.safetyResponse?.riskLevel.equals("Risky", ignoreCase = true)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        if (userLocation != null) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    Configuration.getInstance()
                        .setUserAgentValue(ctx.packageName)

                    val mapView = MapView(ctx)

                    mapView.setTileSource(TileSourceFactory.MAPNIK)
                    mapView.setMultiTouchControls(true)

                    val controller = mapView.controller
                    controller.setZoom(16.0)
                    controller.setCenter(userLocation)

                    updateSafetyOverlay(mapView, userLocation!!, safetyState.safetyResponse)

                    // Fetch safety places
                    fetchSafetyPlaces(
                        mapView,
                        userLocation!!.latitude,
                        userLocation!!.longitude,
                        ctx
                    )

                    mapViewRef = mapView

                    mapView
                },
                update = { mapView ->
                    userLocation?.let {
                        updateSafetyOverlay(mapView, it, safetyState.safetyResponse)
                    }
                }
            )
        }

        SafetyScoreCard(
            state = safetyState,
            locationMessage = locationMessage,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(16.dp)
        )

        // Floating buttons
        Column(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 20.dp, bottom = 120.dp)
        ) {
            // Directions button
            FloatingActionButton(
                onClick = {
                    val uri = Uri.parse(
                        "google.navigation:q=police station near me"
                    )

                    val intent = Intent(Intent.ACTION_VIEW, uri)
                    intent.setPackage("com.google.android.apps.maps")

                    context.startActivity(intent)
                },
                containerColor = BlushPink
            ) {
                Icon(Icons.Default.Directions, "Directions")
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Locate Me button
            FloatingActionButton(
                onClick = {
                    userLocation?.let {
                        mapViewRef?.controller?.animateTo(it)
                    }
                },
                containerColor = Lavender
            ) {
                Icon(Icons.Default.MyLocation, "Locate Me")
            }
        }
    }

    if (showRiskDialog) {
        AlertDialog(
            onDismissRequest = { showRiskDialog = false },
            title = { Text("Risky area detected") },
            text = { Text("Stay alert and consider moving toward a busy public area or nearby police station.") },
            confirmButton = {
                TextButton(onClick = { showRiskDialog = false }) {
                    Text("Got it")
                }
            }
        )
    }
}

fun fetchSafetyPlaces(
    mapView: MapView,
    latitude: Double,
    longitude: Double,
    context: Context
) {
    val url = """
https://overpass-api.de/api/interpreter?data=
[out:json];
(
node["amenity"="police"](around:2000,$latitude,$longitude);
node["amenity"="hospital"](around:2000,$latitude,$longitude);
node["amenity"="cafe"](around:2000,$latitude,$longitude);
node["amenity"="restaurant"](around:2000,$latitude,$longitude);
node["amenity"="fast_food"](around:2000,$latitude,$longitude);
);
out;
""".trimIndent()

    Thread {
        try {
            val connection = URL(url).openConnection() as HttpURLConnection
            connection.requestMethod = "GET"

            val response = connection.inputStream.bufferedReader().readText()

            val json = JSONObject(response)
            val elements = json.getJSONArray("elements")

            for (i in 0 until elements.length()) {
                val place = elements.getJSONObject(i)

                val tags = place.getJSONObject("tags")
                val type = tags.optString("amenity")

                val lat = place.getDouble("lat")
                val lon = place.getDouble("lon")

                val name = tags.optString("name", "Safety Point")

                val marker = Marker(mapView)
                marker.position = GeoPoint(lat, lon)
                marker.title = name

                // Different icons
                when (type) {
                    "police" -> marker.icon =
                        ContextCompat.getDrawable(context, android.R.drawable.ic_lock_lock)

                    "hospital" -> marker.icon =
                        ContextCompat.getDrawable(context, android.R.drawable.ic_menu_info_details)

                    else -> marker.icon =
                        ContextCompat.getDrawable(context, android.R.drawable.ic_menu_myplaces)
                }

                // Click marker -> open navigation
                marker.setOnMarkerClickListener { m, _ ->
                    val latNav = m.position.latitude
                    val lonNav = m.position.longitude

                    val uri = Uri.parse(
                        "google.navigation:q=$latNav,$lonNav"
                    )

                    val intent = Intent(Intent.ACTION_VIEW, uri)
                    intent.setPackage("com.google.android.apps.maps")

                    context.startActivity(intent)

                    true
                }

                mapView.post {
                    mapView.overlays.add(marker)
                    mapView.invalidate()
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }.start()
}
