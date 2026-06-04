package com.example.nightagent.ui.screens

import android.Manifest
import android.content.Context
import android.content.Intent
import android.location.Location
import android.net.Uri
import android.util.Log
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.nightagent.R
import com.example.nightagent.sos.LocationProvider
import com.example.nightagent.ui.theme.BlushPink
import com.example.nightagent.ui.theme.Lavender
import com.example.nightagent.viewmodel.SafetyViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import java.net.HttpURLConnection
import java.net.URL

private const val TAG = "MapScreen"

@Composable
fun MapScreen(
    safetyViewModel: SafetyViewModel = viewModel(factory = SafetyViewModel.Factory())
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val safetyState by safetyViewModel.uiState.collectAsState()

    var userLocation by remember { mutableStateOf<GeoPoint?>(null) }
    var mapViewRef by remember { mutableStateOf<MapView?>(null) }
    var locationMessage by remember { mutableStateOf<String?>(null) }
    var showRiskDialog by remember { mutableStateOf(false) }

    fun loadLocationAndSafety() {
        LocationProvider.getLocation(context) { location: Location? ->
            if (location == null) {
                Log.w(TAG, "loadLocationAndSafety: location is null")
                locationMessage = "Location unavailable. Turn on GPS and try again."
                safetyViewModel.loadCachedScore(context)
                return@getLocation
            }
            Log.d(TAG, "loadLocationAndSafety: lat=${location.latitude} lon=${location.longitude}")
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
            Log.d(TAG, "Location permission granted — loading location")
            loadLocationAndSafety()
        } else {
            Log.w(TAG, "Location permission denied")
            locationMessage = "Location permission is needed to show your safety score."
            safetyViewModel.loadCachedScore(context)
        }
    }

    // FIX 1: Initialise OsmDroid Configuration with .load() here, once,
    // before the MapView is created. This sets the tile cache path and
    // HTTP parameters — without it, tiles are never written to disk.
    LaunchedEffect(Unit) {
        Configuration.getInstance().apply {
            load(context, context.getSharedPreferences("osmdroid", Context.MODE_PRIVATE))
            userAgentValue = context.packageName
        }
        Log.d(TAG, "OsmDroid configuration loaded. Cache path: ${Configuration.getInstance().osmdroidTileCache}")

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

    // FIX 2: Wire MapView lifecycle into the Compose lifecycle so the tile
    // thread pool starts on resume and is released on pause/destroy.
    // Without onResume(), tiles are queued but never dispatched → blank map.
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    mapViewRef?.onResume()
                    Log.d(TAG, "MapView.onResume() called")
                }
                Lifecycle.Event.ON_PAUSE -> {
                    mapViewRef?.onPause()
                    Log.d(TAG, "MapView.onPause() called")
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            // FIX 3: onDetach() shuts down the tile download executor and
            // removes all overlay listeners — prevents memory leaks.
            mapViewRef?.onDetach()
            Log.d(TAG, "MapView.onDetach() called — resources released")
        }
    }

    // FIX 4: React to overlay updates by going through the mapViewRef that
    // is already set. The previous LaunchedEffect raced against the factory
    // lambda — mapViewRef was null at the moment this fired.
    LaunchedEffect(userLocation, safetyState.safetyResponse) {
        val location = userLocation ?: run {
            Log.d(TAG, "overlay LaunchedEffect: userLocation not yet available")
            return@LaunchedEffect
        }
        val mapView = mapViewRef ?: run {
            Log.d(TAG, "overlay LaunchedEffect: mapViewRef not yet set — update lambda will handle it")
            return@LaunchedEffect
        }
        Log.d(TAG, "overlay LaunchedEffect: updating overlay for $location")
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
        // FIX 5: Render AndroidView unconditionally — do NOT gate it behind
        // `if (userLocation != null)`. Gating it means the MapView is absent
        // from the hierarchy during the entire GPS-wait period. The factory
        // runs once; centering and overlays happen in the `update` lambda
        // which is called on every recomposition after state changes.
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                Log.d(TAG, "AndroidView factory: creating MapView")

                // Configuration is already loaded in LaunchedEffect above;
                // this is a no-op if called again but kept for safety.
                Configuration.getInstance().apply {
                    load(ctx, ctx.getSharedPreferences("osmdroid", Context.MODE_PRIVATE))
                    userAgentValue = ctx.packageName
                }

                MapView(ctx).also { mapView ->
                    mapView.setTileSource(TileSourceFactory.MAPNIK)
                    mapView.setMultiTouchControls(true)
                    mapView.controller.setZoom(16.0)

                    // Start the tile thread pool immediately (onResume is the
                    // OsmDroid API for this — must be called at least once).
                    mapView.onResume()

                    mapViewRef = mapView
                    Log.d(TAG, "MapView created. Tile source: ${mapView.tileProvider.tileSource.name()}")
                }
            },
            update = { mapView ->
                // This runs on every recomposition where mapView state changes.
                userLocation?.let { location ->
                    Log.d(TAG, "AndroidView update: centering on $location")
                    mapView.controller.setCenter(location)
                    updateSafetyOverlay(mapView, location, safetyState.safetyResponse)

                    // Fetch safety places only once per unique location.
                    // Using the tag as a one-shot guard avoids refetching on
                    // every recomposition (e.g. when safety score updates).
                    if (mapView.getTag(R.id.map_places_loaded_tag) == null) {
                        mapView.setTag(R.id.map_places_loaded_tag, true)
                        Log.d(TAG, "Fetching safety places around $location")
                        scope.launch {
                            fetchSafetyPlaces(mapView, location.latitude, location.longitude, ctx = mapView.context)
                        }
                    }
                }
            },
            // FIX 6: onRelease gives us a hook to detach the MapView when
            // Compose removes it from the tree (e.g. navigation pop).
            onRelease = { mapView ->
                mapView.onPause()
                mapView.onDetach()
                Log.d(TAG, "AndroidView onRelease: MapView detached")
            }
        )

        SafetyScoreCard(
            state = safetyState,
            locationMessage = locationMessage,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(16.dp)
        )

        Column(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 20.dp, bottom = 120.dp)
        ) {
            FloatingActionButton(
                onClick = {
                    val uri = Uri.parse("google.navigation:q=police station near me")
                    val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                        setPackage("com.google.android.apps.maps")
                    }
                    context.startActivity(intent)
                },
                containerColor = BlushPink
            ) {
                Icon(Icons.Default.Directions, "Directions")
            }

            Spacer(modifier = Modifier.height(12.dp))

            FloatingActionButton(
                onClick = {
                    userLocation?.let { mapViewRef?.controller?.animateTo(it) }
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
                TextButton(onClick = { showRiskDialog = false }) { Text("Got it") }
            }
        )
    }
}

// FIX 7: Run entirely on Dispatchers.IO via a coroutine — no raw Thread.
// The previous Thread-based approach meant startActivity was called from a
// background thread inside setOnMarkerClickListener, which is a main-thread-
// only API and causes CalledFromWrongThreadException on some devices.
// mapView.post() dispatches back to the main thread for all UI mutations.
suspend fun fetchSafetyPlaces(
    mapView: MapView,
    latitude: Double,
    longitude: Double,
    ctx: Context
) = withContext(Dispatchers.IO) {
    val query = """
        [out:json][timeout:15];
        (
          node["amenity"="police"](around:2000,$latitude,$longitude);
          node["amenity"="hospital"](around:2000,$latitude,$longitude);
          node["amenity"="cafe"](around:2000,$latitude,$longitude);
          node["amenity"="restaurant"](around:2000,$latitude,$longitude);
          node["amenity"="fast_food"](around:2000,$latitude,$longitude);
        );
        out;
    """.trimIndent()

    try {
        val encodedQuery = java.net.URLEncoder.encode(query, "UTF-8")
        val connection = URL("https://overpass-api.de/api/interpreter?data=$encodedQuery")
            .openConnection() as HttpURLConnection
        connection.connectTimeout = 15_000
        connection.readTimeout = 15_000
        connection.requestMethod = "GET"

        val response = connection.inputStream.bufferedReader().readText()
        val elements = JSONObject(response).getJSONArray("elements")
        Log.d(TAG, "fetchSafetyPlaces: received ${elements.length()} POIs")

        for (i in 0 until elements.length()) {
            val place = elements.getJSONObject(i)
            val tags = place.optJSONObject("tags") ?: continue
            val type = tags.optString("amenity")
            val lat = place.getDouble("lat")
            val lon = place.getDouble("lon")
            val name = tags.optString("name", "Safety Point")

            // FIX 8: All MapView mutations must happen on the main thread.
            // mapView.post() guarantees that even if the surrounding coroutine
            // is on IO, the overlay modification runs on the UI thread.
            mapView.post {
                val marker = Marker(mapView).apply {
                    position = GeoPoint(lat, lon)
                    title = name
                    // FIX 9: setAnchor with ANCHOR_CENTER/ANCHOR_BOTTOM so the
                    // pin tip sits exactly on the coordinate. Without this, the
                    // default top-left anchor displaces the icon by its full
                    // width and height — it looks missing at normal zoom.
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                    icon = when (type) {
                        "police" -> ContextCompat.getDrawable(ctx, android.R.drawable.ic_lock_lock)
                        "hospital" -> ContextCompat.getDrawable(ctx, android.R.drawable.ic_menu_info_details)
                        else -> ContextCompat.getDrawable(ctx, android.R.drawable.ic_menu_myplaces)
                    }
                    setOnMarkerClickListener { m, _ ->
                        // FIX 10: startActivity is a main-thread call — safe
                        // here because setOnMarkerClickListener fires on the
                        // main thread and we are already in mapView.post {}.
                        val uri = Uri.parse("google.navigation:q=${m.position.latitude},${m.position.longitude}")
                        ctx.startActivity(
                            Intent(Intent.ACTION_VIEW, uri).apply {
                                setPackage("com.google.android.apps.maps")
                            }
                        )
                        true
                    }
                }
                mapView.overlays.add(marker)
                mapView.invalidate()
                Log.d(TAG, "Marker added: $name ($type) at $lat,$lon")
            }
        }
    } catch (e: Exception) {
        Log.e(TAG, "fetchSafetyPlaces failed: ${e.message}", e)
    }
}
