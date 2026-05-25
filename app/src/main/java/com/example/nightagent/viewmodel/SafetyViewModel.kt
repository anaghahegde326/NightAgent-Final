package com.example.nightagent.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.nightagent.model.SafetyResponse
import com.example.nightagent.repository.SafetyRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class SafetyUiState(
    val isLoading: Boolean = false,
    val safetyResponse: SafetyResponse? = null,
    val message: String? = null,
    val isOfflineMode: Boolean = false
)

class SafetyViewModel(
    private val repository: SafetyRepository = SafetyRepository()
) : ViewModel() {

    private val _uiState = MutableStateFlow(SafetyUiState())
    val uiState: StateFlow<SafetyUiState> = _uiState.asStateFlow()

    private var lastLatitude: Double? = null
    private var lastLongitude: Double? = null
    private var lastFetchTimeMillis: Long = 0L

    fun loadCachedScore(context: Context) {
        val cachedScore = repository.getCachedSafetyScore(context) ?: return
        _uiState.value = SafetyUiState(
            safetyResponse = cachedScore,
            message = "Showing last known safety score",
            isOfflineMode = true
        )
    }

    fun fetchSafetyScore(context: Context, latitude: Double, longitude: Double) {
        val appContext = context.applicationContext
        val cachedMetadata = repository.getCachedMetadata(appContext)
        val previousLatitude = lastLatitude ?: cachedMetadata.latitude
        val previousLongitude = lastLongitude ?: cachedMetadata.longitude
        val previousFetchTime = if (lastFetchTimeMillis > 0L) {
            lastFetchTimeMillis
        } else {
            cachedMetadata.fetchedAtMillis
        }

        if (!repository.shouldRefresh(latitude, longitude, previousLatitude, previousLongitude, previousFetchTime)) {
            repository.getCachedSafetyScore(appContext)?.let { cachedScore ->
                _uiState.value = _uiState.value.copy(safetyResponse = cachedScore)
            }
            return
        }

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, message = null)

            repository.getSafetyScore(latitude, longitude)
                .onSuccess { response ->
                    lastLatitude = latitude
                    lastLongitude = longitude
                    lastFetchTimeMillis = System.currentTimeMillis()
                    repository.cacheSafetyScore(appContext, response, latitude, longitude)
                    _uiState.value = SafetyUiState(safetyResponse = response)
                }
                .onFailure {
                    val cachedScore = repository.getCachedSafetyScore(appContext)
                    _uiState.value = SafetyUiState(
                        safetyResponse = cachedScore,
                        message = if (cachedScore == null) {
                            "Unable to load OSM safety data. Check your connection and try again."
                        } else {
                            "Offline mode: showing last known safety score"
                        },
                        isOfflineMode = cachedScore != null
                    )
                }
        }
    }

    @Suppress("UNCHECKED_CAST")
    class Factory(
        private val repository: SafetyRepository = SafetyRepository()
    ) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (modelClass.isAssignableFrom(SafetyViewModel::class.java)) {
                return SafetyViewModel(repository) as T
            }
            throw IllegalArgumentException("Unknown ViewModel class")
        }
    }
}
