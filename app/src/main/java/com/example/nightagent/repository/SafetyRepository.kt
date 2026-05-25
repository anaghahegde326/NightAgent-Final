package com.example.nightagent.repository

import android.content.Context
import com.example.nightagent.model.SafetyFactors
import com.example.nightagent.model.SafetyResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

data class CachedSafetyMetadata(
    val latitude: Double?,
    val longitude: Double?,
    val fetchedAtMillis: Long
)

class SafetyRepository {
    suspend fun getSafetyScore(latitude: Double, longitude: Double): Result<SafetyResponse> =
        withContext(Dispatchers.IO) {
            try {
                Result.success(calculateOsmSafetyScore(latitude, longitude))
            } catch (exception: IOException) {
                Result.failure(exception)
            } catch (exception: Exception) {
                Result.failure(exception)
            }
        }

    fun getCachedSafetyScore(context: Context): SafetyResponse? {
        val prefs = context.applicationContext.getSharedPreferences(CACHE_NAME, Context.MODE_PRIVATE)
        val score = prefs.getFloat(KEY_SCORE, Float.NaN)
        if (score.isNaN()) return null

        return SafetyResponse(
            safetyScore = score.toDouble(),
            riskLevel = prefs.getString(KEY_RISK_LEVEL, null) ?: return null,
            factors = com.example.nightagent.model.SafetyFactors(
                poiDensity = prefs.getString(KEY_POI_DENSITY, "") ?: "",
                nearestPoliceDistance = prefs.getString(KEY_POLICE_DISTANCE, "") ?: ""
            )
        )
    }

    fun cacheSafetyScore(
        context: Context,
        response: SafetyResponse,
        latitude: Double,
        longitude: Double
    ) {
        context.applicationContext.getSharedPreferences(CACHE_NAME, Context.MODE_PRIVATE)
            .edit()
            .putFloat(KEY_SCORE, response.safetyScore.toFloat())
            .putString(KEY_RISK_LEVEL, response.riskLevel)
            .putString(KEY_POI_DENSITY, response.factors.poiDensity)
            .putString(KEY_POLICE_DISTANCE, response.factors.nearestPoliceDistance)
            .putLong(KEY_FETCHED_AT, System.currentTimeMillis())
            .putString(KEY_LATITUDE, latitude.toString())
            .putString(KEY_LONGITUDE, longitude.toString())
            .apply()
    }

    fun getCachedMetadata(context: Context): CachedSafetyMetadata {
        val prefs = context.applicationContext.getSharedPreferences(CACHE_NAME, Context.MODE_PRIVATE)
        return CachedSafetyMetadata(
            latitude = prefs.getString(KEY_LATITUDE, null)?.toDoubleOrNull(),
            longitude = prefs.getString(KEY_LONGITUDE, null)?.toDoubleOrNull(),
            fetchedAtMillis = prefs.getLong(KEY_FETCHED_AT, 0L)
        )
    }

    fun shouldRefresh(
        latitude: Double,
        longitude: Double,
        previousLatitude: Double?,
        previousLongitude: Double?,
        previousFetchTimeMillis: Long,
        nowMillis: Long = System.currentTimeMillis()
    ): Boolean {
        val hasMovedEnough = previousLatitude == null ||
            previousLongitude == null ||
            abs(latitude - previousLatitude) > LOCATION_DELTA ||
            abs(longitude - previousLongitude) > LOCATION_DELTA
        val hasWaitedEnough = nowMillis - previousFetchTimeMillis > MIN_REFRESH_INTERVAL_MS
        return hasMovedEnough || hasWaitedEnough
    }

    private fun calculateOsmSafetyScore(latitude: Double, longitude: Double): SafetyResponse {
        val elements = JSONObject(fetchOverpassJson(latitude, longitude)).getJSONArray("elements")
        var policeCount = 0
        var hospitalCount = 0
        var publicPlaceCount = 0
        var nearestPoliceMeters: Double? = null

        for (index in 0 until elements.length()) {
            val place = elements.getJSONObject(index)
            val tags = place.optJSONObject("tags") ?: continue
            val type = tags.optString("amenity")

            when (type) {
                "police" -> {
                    policeCount++
                    val distance = distanceMeters(
                        latitude,
                        longitude,
                        place.getDouble("lat"),
                        place.getDouble("lon")
                    )
                    nearestPoliceMeters = minOf(nearestPoliceMeters ?: distance, distance)
                }
                "hospital", "clinic", "pharmacy" -> hospitalCount++
                "cafe", "restaurant", "fast_food", "bank", "atm", "fuel", "bus_station" -> publicPlaceCount++
            }
        }

        val poiDensity = when {
            publicPlaceCount >= 12 -> "high"
            publicPlaceCount >= 5 -> "medium"
            else -> "low"
        }

        val policeScore = when {
            nearestPoliceMeters == null -> 1.0
            nearestPoliceMeters <= 500.0 -> 3.0
            nearestPoliceMeters <= 1_000.0 -> 2.0
            nearestPoliceMeters <= 2_000.0 -> 1.0
            else -> 0.0
        }
        val publicScore = when (poiDensity) {
            "high" -> 3.0
            "medium" -> 2.0
            else -> 0.8
        }
        val medicalScore = when {
            hospitalCount >= 2 -> 2.0
            hospitalCount == 1 -> 1.2
            else -> 0.3
        }
        val coverageScore = when {
            elements.length() >= 20 -> 2.0
            elements.length() >= 8 -> 1.2
            else -> 0.5
        }

        val safetyScore = (policeScore + publicScore + medicalScore + coverageScore)
            .coerceIn(0.0, 10.0)
            .roundToSingleDecimal()
        val riskLevel = when {
            safetyScore >= 7.0 -> "Safe"
            safetyScore >= 4.0 -> "Moderate"
            else -> "Risky"
        }

        return SafetyResponse(
            safetyScore = safetyScore,
            riskLevel = riskLevel,
            factors = SafetyFactors(
                poiDensity = poiDensity,
                nearestPoliceDistance = nearestPoliceMeters?.toReadableDistance() ?: "not found nearby"
            )
        )
    }

    private fun fetchOverpassJson(latitude: Double, longitude: Double): String {
        val query = """
            [out:json][timeout:2];
            (
              node["amenity"="police"](around:2000,$latitude,$longitude);
              node["amenity"="hospital"](around:2000,$latitude,$longitude);
              node["amenity"="clinic"](around:2000,$latitude,$longitude);
              node["amenity"="pharmacy"](around:2000,$latitude,$longitude);
              node["amenity"="cafe"](around:2000,$latitude,$longitude);
              node["amenity"="restaurant"](around:2000,$latitude,$longitude);
              node["amenity"="fast_food"](around:2000,$latitude,$longitude);
              node["amenity"="bank"](around:2000,$latitude,$longitude);
              node["amenity"="atm"](around:2000,$latitude,$longitude);
              node["amenity"="fuel"](around:2000,$latitude,$longitude);
              node["amenity"="bus_station"](around:2000,$latitude,$longitude);
            );
            out;
        """.trimIndent()
        val url = URL("https://overpass-api.de/api/interpreter?data=${java.net.URLEncoder.encode(query, "UTF-8")}")
        val connection = url.openConnection() as HttpURLConnection
        return connection.run {
            requestMethod = "GET"
            connectTimeout = 2_000
            readTimeout = 2_000
            inputStream.bufferedReader().use { it.readText() }
        }
    }

    private fun distanceMeters(
        startLatitude: Double,
        startLongitude: Double,
        endLatitude: Double,
        endLongitude: Double
    ): Double {
        val earthRadiusMeters = 6_371_000.0
        val deltaLatitude = Math.toRadians(endLatitude - startLatitude)
        val deltaLongitude = Math.toRadians(endLongitude - startLongitude)
        val startLatitudeRadians = Math.toRadians(startLatitude)
        val endLatitudeRadians = Math.toRadians(endLatitude)
        val a = sin(deltaLatitude / 2) * sin(deltaLatitude / 2) +
            cos(startLatitudeRadians) * cos(endLatitudeRadians) *
            sin(deltaLongitude / 2) * sin(deltaLongitude / 2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return earthRadiusMeters * c
    }

    private fun Double.roundToSingleDecimal(): Double = (this * 10).roundToInt() / 10.0

    private fun Double.toReadableDistance(): String {
        return if (this < 1_000) {
            "${roundToInt()}m"
        } else {
            String.format(Locale.US, "%.1fkm", this / 1_000)
        }
    }

    companion object {
        private const val CACHE_NAME = "safety_score_cache"
        private const val KEY_SCORE = "score"
        private const val KEY_RISK_LEVEL = "risk_level"
        private const val KEY_POI_DENSITY = "poi_density"
        private const val KEY_POLICE_DISTANCE = "police_distance"
        private const val KEY_FETCHED_AT = "fetched_at"
        private const val KEY_LATITUDE = "latitude"
        private const val KEY_LONGITUDE = "longitude"
        private const val LOCATION_DELTA = 0.001
        private const val MIN_REFRESH_INTERVAL_MS = 30_000L
    }
}
