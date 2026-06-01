package com.example.nightagent.ui.stealth

import android.app.Application
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.nightagent.stealth.StealthDetectionService
import com.example.nightagent.stealth.StealthRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class StealthUiState(
    val isStealthEnabled: Boolean = false,
    val isPinSet: Boolean = false,
    // PIN setup / change dialog
    val showSetPinDialog: Boolean = false,
    val showChangePinDialog: Boolean = false,
    // Feedback
    val errorMessage: String? = null,
    val successMessage: String? = null,
    // Lockout
    val isLockedOut: Boolean = false,
    val lockoutSeconds: Int = 0
)

class StealthViewModel(application: Application) : AndroidViewModel(application) {

    val repo = StealthRepository(application)

    private val _uiState = MutableStateFlow(
        StealthUiState(
            isStealthEnabled = repo.isStealthModeEnabled.value,
            isPinSet = repo.isPinSet()
        )
    )
    val uiState: StateFlow<StealthUiState> = _uiState.asStateFlow()

    // ── Toggle stealth mode ──────────────────────────────────────────────────

    fun onStealthToggled(enabled: Boolean) {
        if (enabled) {
            if (!repo.isPinSet()) {
                // Must set a PIN first
                _uiState.value = _uiState.value.copy(showSetPinDialog = true)
            } else {
                enableStealth()
            }
        } else {
            disableStealth()
        }
    }

    private fun enableStealth() {
        repo.enableStealthMode()
        StealthDetectionService.start(getApplication())
        _uiState.value = _uiState.value.copy(
            isStealthEnabled = true,
            successMessage = "Stealth Mode enabled"
        )
        clearMessagesDelayed()
    }

    private fun disableStealth() {
        repo.disableStealthMode()
        StealthDetectionService.stop(getApplication())
        _uiState.value = _uiState.value.copy(
            isStealthEnabled = false,
            successMessage = "Stealth Mode disabled"
        )
        clearMessagesDelayed()
    }

    // ── PIN setup (first time) ───────────────────────────────────────────────

    fun onSetPinDialogDismiss() {
        _uiState.value = _uiState.value.copy(
            showSetPinDialog = false,
            // Revert toggle if user cancelled without setting a PIN
            isStealthEnabled = repo.isStealthModeEnabled.value
        )
    }

    fun onSetPinConfirmed(pin: String): Boolean {
        if (pin.length < 4) {
            _uiState.value = _uiState.value.copy(errorMessage = "PIN must be at least 4 digits")
            return false
        }
        repo.savePin(pin)
        _uiState.value = _uiState.value.copy(
            showSetPinDialog = false,
            isPinSet = true,
            errorMessage = null
        )
        enableStealth()
        return true
    }

    // ── PIN change ───────────────────────────────────────────────────────────

    fun onChangePinClicked() {
        _uiState.value = _uiState.value.copy(showChangePinDialog = true)
    }

    fun onChangePinDialogDismiss() {
        _uiState.value = _uiState.value.copy(
            showChangePinDialog = false,
            errorMessage = null
        )
    }

    /**
     * @param currentPin  the user's existing PIN for verification
     * @param newPin      the new PIN to save
     * @return true if change succeeded
     */
    fun onChangePinConfirmed(currentPin: String, newPin: String): Boolean {
        if (repo.isLockedOut()) {
            _uiState.value = _uiState.value.copy(
                errorMessage = "Too many attempts. Wait ${repo.lockoutRemainingSeconds()}s",
                isLockedOut = true,
                lockoutSeconds = repo.lockoutRemainingSeconds()
            )
            return false
        }

        if (!repo.verifyPin(currentPin)) {
            val attempts = repo.recordFailedAttempt()
            val remaining = StealthRepository.MAX_ATTEMPTS - attempts
            _uiState.value = _uiState.value.copy(
                errorMessage = if (remaining > 0)
                    "Wrong PIN — $remaining attempt(s) left"
                else
                    "Too many attempts. Wait ${repo.lockoutRemainingSeconds()}s"
            )
            return false
        }

        if (newPin.length < 4) {
            _uiState.value = _uiState.value.copy(errorMessage = "New PIN must be at least 4 digits")
            return false
        }

        repo.resetFailedAttempts()
        repo.savePin(newPin)
        _uiState.value = _uiState.value.copy(
            showChangePinDialog = false,
            errorMessage = null,
            successMessage = "PIN updated successfully"
        )
        clearMessagesDelayed()
        return true
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private fun clearMessagesDelayed() {
        viewModelScope.launch {
            kotlinx.coroutines.delay(3000)
            _uiState.value = _uiState.value.copy(
                successMessage = null,
                errorMessage = null,
                isLockedOut = false
            )
        }
    }

    class Factory(private val application: Application) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
            if (modelClass.isAssignableFrom(StealthViewModel::class.java)) {
                return StealthViewModel(application) as T
            }
            throw IllegalArgumentException("Unknown ViewModel: $modelClass")
        }
    }
}
