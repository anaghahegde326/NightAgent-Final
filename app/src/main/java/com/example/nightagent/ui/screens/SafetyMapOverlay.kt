package com.example.nightagent.ui.screens

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color as AndroidColor
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.example.nightagent.model.SafetyResponse
import com.example.nightagent.viewmodel.SafetyUiState
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polygon

@Composable
internal fun SafetyScoreCard(
    state: SafetyUiState,
    locationMessage: String?,
    modifier: Modifier = Modifier
) {
    val response = state.safetyResponse
    val riskColor by animateColorAsState(
        targetValue = riskComposeColor(response?.riskLevel),
        label = "riskColor"
    )

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White.copy(alpha = 0.94f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Real-Time Safety Score",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color(0xFF222222)
                )
                Text(
                    text = locationMessage ?: state.message ?: "OSM safety zone",
                    fontSize = 12.sp,
                    color = Color(0xFF666666)
                )
            }

            Column(horizontalAlignment = Alignment.End) {
                if (state.isLoading) {
                    CircularProgressIndicator(color = riskColor)
                } else {
                    Text(
                        text = response?.safetyScore?.let { "%.1f".format(it) } ?: "--",
                        fontSize = 28.sp,
                        fontWeight = FontWeight.Bold,
                        color = riskColor
                    )
                }
                Text(
                    text = when {
                        state.isOfflineMode -> "Offline mode"
                        response != null -> response.riskLevel.normalizedRiskLabel()
                        else -> "Waiting"
                    },
                    fontSize = 13.sp,
                    color = riskColor
                )
            }
        }

        AnimatedVisibility(visible = response != null) {
            Text(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFFF7F7F7))
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                text = "POI density: ${response?.factors?.poiDensity.orEmpty()}",
                fontSize = 12.sp
            )
        }
    }
}

internal fun updateSafetyOverlay(
    mapView: MapView,
    userLocation: GeoPoint,
    safetyResponse: SafetyResponse?
) {
    mapView.overlays.removeAll { overlay ->
        (overlay is Marker && overlay.title == "You are here") ||
            (overlay is Polygon && overlay.title == SAFETY_ZONE_TITLE)
    }

    val safetyColor = riskAndroidColor(safetyResponse?.riskLevel)
    val safetyCircle = Polygon().apply {
        title = SAFETY_ZONE_TITLE
        points = Polygon.pointsAsCircle(userLocation, SAFETY_RADIUS_METERS)
        fillColor = AndroidColor.argb(
            46,
            AndroidColor.red(safetyColor),
            AndroidColor.green(safetyColor),
            AndroidColor.blue(safetyColor)
        )
        strokeColor = safetyColor
        strokeWidth = 4f
    }

    val userMarker = Marker(mapView)
    userMarker.position = userLocation
    userMarker.setAnchor(
        Marker.ANCHOR_CENTER,
        Marker.ANCHOR_BOTTOM
    )
    userMarker.title = "You are here"

    mapView.overlays.add(0, safetyCircle)
    mapView.overlays.add(userMarker)
    mapView.invalidate()
}

internal fun hasLocationPermission(context: Context): Boolean {
    val fineGranted = ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.ACCESS_FINE_LOCATION
    ) == PackageManager.PERMISSION_GRANTED
    val coarseGranted = ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.ACCESS_COARSE_LOCATION
    ) == PackageManager.PERMISSION_GRANTED
    return fineGranted || coarseGranted
}

private fun riskComposeColor(riskLevel: String?): Color {
    return when (riskLevel?.lowercase()) {
        "safe" -> Color(0xFF2E7D32)
        "moderate" -> Color(0xFFF9A825)
        "risky" -> Color(0xFFC62828)
        else -> Color(0xFF757575)
    }
}

private fun riskAndroidColor(riskLevel: String?): Int {
    return when (riskLevel?.lowercase()) {
        "safe" -> AndroidColor.rgb(46, 125, 50)
        "moderate" -> AndroidColor.rgb(249, 168, 37)
        "risky" -> AndroidColor.rgb(198, 40, 40)
        else -> AndroidColor.rgb(117, 117, 117)
    }
}

private fun String.normalizedRiskLabel(): String {
    return when (lowercase()) {
        "safe" -> "Safe"
        "moderate" -> "Moderate"
        "risky" -> "Risky"
        else -> replaceFirstChar { it.uppercase() }
    }
}

private const val SAFETY_ZONE_TITLE = "Safety Zone"
private const val SAFETY_RADIUS_METERS = 400.0
