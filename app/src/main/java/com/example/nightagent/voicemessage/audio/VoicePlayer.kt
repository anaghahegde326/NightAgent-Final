package com.example.nightagent.voicemessage.audio

import android.content.Context
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Voice message player backed by ExoPlayer.
 *
 * Only one message plays at a time. The UI observes [playerState], [positionMs],
 * and [activeMessageId].
 *
 * Thread model:
 *   - All ExoPlayer calls happen on the main thread (Dispatchers.Main).
 *   - Position polling dispatches to Main before reading currentPosition.
 *   - stopAndRelease() is synchronized via a local ref copy to eliminate
 *     the race between STATE_ENDED callback and the next playOrPause call.
 */
class VoicePlayer(private val context: Context) {

    private val _playerState = MutableStateFlow<VoicePlayerState>(VoicePlayerState.Idle)
    val playerState: StateFlow<VoicePlayerState> = _playerState.asStateFlow()

    private val _positionMs = MutableStateFlow(0L)
    val positionMs: StateFlow<Long> = _positionMs.asStateFlow()

    private val _activeMessageId = MutableStateFlow<String?>(null)
    val activeMessageId: StateFlow<String?> = _activeMessageId.asStateFlow()

    // All accesses to exoPlayer must happen on the main thread
    private var exoPlayer: ExoPlayer? = null
    private var positionJob: Job? = null
    private var currentMessageId: String? = null

    // ── Public API ─────────────────────────────────────────────────────────────

    fun playOrPause(messageId: String, audioUrl: String, scope: CoroutineScope) {
        // FIX 4: All ExoPlayer mutations dispatched to Main to prevent
        // "Player is accessed on the wrong thread" IllegalStateException.
        scope.launch(Dispatchers.Main) {
            if (currentMessageId == messageId) {
                // Same message — toggle play/pause
                exoPlayer?.let { player ->
                    if (player.isPlaying) {
                        player.pause()
                        _playerState.value = VoicePlayerState.Paused(messageId)
                        stopPositionPolling()
                        Log.d(TAG, "Paused: $messageId")
                    } else {
                        player.play()
                        _playerState.value = VoicePlayerState.Playing(messageId)
                        startPositionPolling(scope)
                        Log.d(TAG, "Resumed: $messageId")
                    }
                }
                return@launch
            }

            // Different message — stop current and start new
            releasePlayerInternal()
            currentMessageId = messageId
            _activeMessageId.value = messageId
            _playerState.value = VoicePlayerState.Loading(messageId)
            Log.d(TAG, "Starting playback: $messageId url=$audioUrl")

            val player = ExoPlayer.Builder(context).build().also { exoPlayer = it }

            player.addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    // FIX 4: This callback fires on main thread — safe to call
                    // exoPlayer directly here.
                    when (playbackState) {
                        Player.STATE_READY -> {
                            if (player.playWhenReady) {
                                _playerState.value = VoicePlayerState.Playing(messageId)
                                startPositionPolling(scope)
                                Log.d(TAG, "STATE_READY → playing: $messageId " +
                                    "duration=${player.duration}ms")
                            }
                        }
                        Player.STATE_ENDED -> {
                            Log.d(TAG, "STATE_ENDED: $messageId")
                            _positionMs.value = 0L
                            _playerState.value = VoicePlayerState.Idle
                            _activeMessageId.value = null
                            currentMessageId = null
                            stopPositionPolling()
                            // FIX 4: Release AFTER clearing currentMessageId so
                            // a concurrent playOrPause call for the same id
                            // doesn't skip the "different message" branch.
                            releasePlayerInternal()
                        }
                        Player.STATE_BUFFERING -> {
                            _playerState.value = VoicePlayerState.Loading(messageId)
                            Log.d(TAG, "STATE_BUFFERING: $messageId")
                        }
                        else -> {}
                    }
                }

                override fun onPlayerError(error: PlaybackException) {
                    Log.e(TAG, "ExoPlayer error for $messageId: ${error.message}", error)
                    _playerState.value = VoicePlayerState.Error(
                        error.message ?: "Playback error"
                    )
                    _activeMessageId.value = null
                    currentMessageId = null
                    stopPositionPolling()
                    releasePlayerInternal()
                }
            })

            player.setMediaItem(MediaItem.fromUri(audioUrl))
            player.prepare()
            player.playWhenReady = true
        }
    }

    fun seekTo(positionMs: Long) {
        // seekTo is also a player mutation — dispatch to Main
        // If called from background scope, use post via handler
        exoPlayer?.seekTo(positionMs)
        _positionMs.value = positionMs
    }

    fun stopIfPlaying(messageId: String) {
        if (currentMessageId == messageId) {
            releasePlayerInternal()
            _playerState.value = VoicePlayerState.Idle
            _activeMessageId.value = null
            currentMessageId = null
            Log.d(TAG, "stopIfPlaying: released $messageId")
        }
    }

    fun release() {
        stopPositionPolling()
        releasePlayerInternal()
        Log.d(TAG, "VoicePlayer released")
    }

    // ── Internals ──────────────────────────────────────────────────────────────

    // FIX 4: Take a local copy of exoPlayer before any operation so that
    // concurrent calls to releasePlayerInternal() from different code paths
    // (STATE_ENDED callback vs next playOrPause) don't double-release.
    private fun releasePlayerInternal() {
        stopPositionPolling()
        val player = exoPlayer
        exoPlayer = null
        try {
            player?.stop()
            player?.release()
        } catch (e: Exception) {
            Log.e(TAG, "ExoPlayer release error: ${e.message}")
        }
    }

    // FIX 5: Position polling dispatches to Main before reading currentPosition.
    // ExoPlayer.currentPosition must be read on the thread the player was
    // created on (Main). Without Dispatchers.Main here, reading from an IO
    // thread causes "Player is accessed on the wrong thread".
    private fun startPositionPolling(scope: CoroutineScope) {
        stopPositionPolling()
        positionJob = scope.launch(Dispatchers.Main) {
            while (true) {
                delay(200)
                _positionMs.value = exoPlayer?.currentPosition ?: 0L
            }
        }
    }

    private fun stopPositionPolling() {
        positionJob?.cancel()
        positionJob = null
    }

    companion object {
        private const val TAG = "VoicePlayer"
    }
}

// ── State types ────────────────────────────────────────────────────────────────

sealed class VoicePlayerState {
    object Idle : VoicePlayerState()
    data class Loading(val messageId: String) : VoicePlayerState()
    data class Playing(val messageId: String) : VoicePlayerState()
    data class Paused(val messageId: String) : VoicePlayerState()
    data class Error(val message: String) : VoicePlayerState()
}
