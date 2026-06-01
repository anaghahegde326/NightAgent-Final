package com.example.nightagent.voicemessage.audio

import android.content.Context
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Singleton-style voice message player backed by ExoPlayer.
 *
 * Only one message plays at a time — starting a new one automatically
 * stops the previous one. The UI observes [playerState] and [positionMs].
 */
class VoicePlayer(private val context: Context) {

    // ── Public state ─────────────────────────────────────────────────────────

    private val _playerState = MutableStateFlow<VoicePlayerState>(VoicePlayerState.Idle)
    val playerState: StateFlow<VoicePlayerState> = _playerState.asStateFlow()

    /** Current playback position in ms — updated every 200 ms while playing. */
    private val _positionMs = MutableStateFlow(0L)
    val positionMs: StateFlow<Long> = _positionMs.asStateFlow()

    /** messageId of the currently active (playing or paused) message. */
    private val _activeMessageId = MutableStateFlow<String?>(null)
    val activeMessageId: StateFlow<String?> = _activeMessageId.asStateFlow()

    // ── Internals ─────────────────────────────────────────────────────────────

    private var exoPlayer: ExoPlayer? = null
    private var positionJob: Job? = null
    private var currentMessageId: String? = null

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Play or pause a voice message.
     *
     * @param messageId  Unique ID of the message (used to track active item in UI).
     * @param audioUrl   Remote URL or local file path.
     * @param scope      CoroutineScope for position polling.
     */
    fun playOrPause(
        messageId: String,
        audioUrl: String,
        scope: CoroutineScope
    ) {
        if (currentMessageId == messageId) {
            // Same message — toggle play/pause
            exoPlayer?.let { player ->
                if (player.isPlaying) {
                    player.pause()
                    _playerState.value = VoicePlayerState.Paused(messageId)
                    stopPositionPolling()
                } else {
                    player.play()
                    _playerState.value = VoicePlayerState.Playing(messageId)
                    startPositionPolling(scope)
                }
            }
            return
        }

        // Different message — stop current and start new
        stopAndRelease()
        currentMessageId = messageId
        _activeMessageId.value = messageId
        _playerState.value = VoicePlayerState.Loading(messageId)

        val player = ExoPlayer.Builder(context).build().also { exoPlayer = it }

        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                when (playbackState) {
                    Player.STATE_READY -> {
                        _playerState.value = VoicePlayerState.Playing(messageId)
                        startPositionPolling(scope)
                    }
                    Player.STATE_ENDED -> {
                        _positionMs.value = 0L
                        _playerState.value = VoicePlayerState.Idle
                        _activeMessageId.value = null
                        currentMessageId = null
                        stopPositionPolling()
                        stopAndRelease()
                    }
                    Player.STATE_BUFFERING -> {
                        _playerState.value = VoicePlayerState.Loading(messageId)
                    }
                    else -> {}
                }
            }

            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                Log.e(TAG, "ExoPlayer error: ${error.message}", error)
                _playerState.value = VoicePlayerState.Error(error.message ?: "Playback error")
                _activeMessageId.value = null
                currentMessageId = null
                stopPositionPolling()
                stopAndRelease()
            }
        })

        player.setMediaItem(MediaItem.fromUri(audioUrl))
        player.prepare()
        player.playWhenReady = true
    }

    /**
     * Seek to a specific position (called from seekbar interaction).
     */
    fun seekTo(positionMs: Long) {
        exoPlayer?.seekTo(positionMs)
        _positionMs.value = positionMs
    }

    /**
     * Stop playback of a specific message (e.g. when its item scrolls off screen).
     */
    fun stopIfPlaying(messageId: String) {
        if (currentMessageId == messageId) {
            stopAndRelease()
            _playerState.value = VoicePlayerState.Idle
            _activeMessageId.value = null
            currentMessageId = null
        }
    }

    /**
     * Release all resources. Call from ViewModel.onCleared().
     */
    fun release() {
        stopPositionPolling()
        stopAndRelease()
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun stopAndRelease() {
        stopPositionPolling()
        try {
            exoPlayer?.stop()
            exoPlayer?.release()
        } catch (e: Exception) {
            Log.e(TAG, "ExoPlayer release error: ${e.message}")
        }
        exoPlayer = null
    }

    private fun startPositionPolling(scope: CoroutineScope) {
        stopPositionPolling()
        positionJob = scope.launch {
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

// ── State types ───────────────────────────────────────────────────────────────

sealed class VoicePlayerState {
    object Idle : VoicePlayerState()
    data class Loading(val messageId: String) : VoicePlayerState()
    data class Playing(val messageId: String) : VoicePlayerState()
    data class Paused(val messageId: String) : VoicePlayerState()
    data class Error(val message: String) : VoicePlayerState()
}
