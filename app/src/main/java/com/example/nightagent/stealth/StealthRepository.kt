package com.example.nightagent.stealth

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Manages Stealth Mode state and PIN using EncryptedSharedPreferences
 * backed by the Android Keystore (AES256-GCM master key).
 */
class StealthRepository(context: Context) {

    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val prefs = EncryptedSharedPreferences.create(
        context,
        "stealth_prefs",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    private val _isStealthModeEnabled = MutableStateFlow(getStealthModeState())
    val isStealthModeEnabled: StateFlow<Boolean> = _isStealthModeEnabled.asStateFlow()

    // ── PIN ──────────────────────────────────────────────────────────────────

    fun savePin(pin: String) {
        prefs.edit().putString(KEY_PIN, pin).apply()
    }

    fun verifyPin(inputPin: String): Boolean {
        val saved = prefs.getString(KEY_PIN, null) ?: return false
        return saved == inputPin
    }

    fun isPinSet(): Boolean = prefs.getString(KEY_PIN, null) != null

    // ── Stealth Mode toggle ──────────────────────────────────────────────────

    fun enableStealthMode() {
        prefs.edit().putBoolean(KEY_STEALTH, true).apply()
        _isStealthModeEnabled.value = true
    }

    fun disableStealthMode() {
        prefs.edit().putBoolean(KEY_STEALTH, false).apply()
        _isStealthModeEnabled.value = false
    }

    private fun getStealthModeState(): Boolean =
        prefs.getBoolean(KEY_STEALTH, false)

    // ── Failed-attempt lockout ───────────────────────────────────────────────

    fun recordFailedAttempt(): Int {
        val count = getFailedAttempts() + 1
        prefs.edit()
            .putInt(KEY_FAILED_ATTEMPTS, count)
            .putLong(KEY_LAST_FAILED_TIME, System.currentTimeMillis())
            .apply()
        return count
    }

    fun resetFailedAttempts() {
        prefs.edit()
            .putInt(KEY_FAILED_ATTEMPTS, 0)
            .putLong(KEY_LAST_FAILED_TIME, 0L)
            .apply()
    }

    fun getFailedAttempts(): Int = prefs.getInt(KEY_FAILED_ATTEMPTS, 0)

    fun getLastFailedTime(): Long = prefs.getLong(KEY_LAST_FAILED_TIME, 0L)

    /** Returns true if the user is currently locked out (5 failures → 60 s cooldown). */
    fun isLockedOut(): Boolean {
        if (getFailedAttempts() < MAX_ATTEMPTS) return false
        val elapsed = System.currentTimeMillis() - getLastFailedTime()
        return elapsed < LOCKOUT_DURATION_MS
    }

    /** Remaining lockout time in seconds (0 if not locked out). */
    fun lockoutRemainingSeconds(): Int {
        if (!isLockedOut()) return 0
        val elapsed = System.currentTimeMillis() - getLastFailedTime()
        return ((LOCKOUT_DURATION_MS - elapsed) / 1000).toInt().coerceAtLeast(0)
    }

    companion object {
        private const val KEY_PIN = "user_pin"
        private const val KEY_STEALTH = "stealth_mode"
        private const val KEY_FAILED_ATTEMPTS = "failed_attempts"
        private const val KEY_LAST_FAILED_TIME = "last_failed_time"
        const val MAX_ATTEMPTS = 5
        const val LOCKOUT_DURATION_MS = 60_000L
    }
}
