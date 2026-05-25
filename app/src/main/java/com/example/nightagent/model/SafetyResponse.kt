package com.example.nightagent.model

import com.google.gson.annotations.SerializedName

data class SafetyResponse(
    @SerializedName("safety_score")
    val safetyScore: Double,
    @SerializedName("risk_level")
    val riskLevel: String,
    val factors: SafetyFactors
)

data class SafetyFactors(
    @SerializedName("poi_density")
    val poiDensity: String,
    @SerializedName("nearest_police_distance")
    val nearestPoliceDistance: String
)
