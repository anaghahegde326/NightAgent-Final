package com.example.nightagent.network

import com.example.nightagent.model.SafetyResponse
import retrofit2.http.GET
import retrofit2.http.Query

interface SafetyApiService {
    @GET("getSafetyScore")
    suspend fun getSafetyScore(
        @Query("lat") latitude: Double,
        @Query("lon") longitude: Double
    ): SafetyResponse
}
