package com.example.nightagent.voicemessage.viewmodel

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.nightagent.voicemessage.audio.RecorderState
import com.example.nightagent.voicemessage.audio.RecordingResult
import com.example.nightagent.voicemessage.audio.VoicePlayer
import com.example.nightagent.voicemessage.audio.VoicePlayerState
import com.example.nightagent.voicemessage.audio.VoiceRecorder
import com.example.nightagent.voicemessage.firebase.VoiceMessageFirebase
import com.example.nightagent.voicemessage.model.MessageStatus
import com.example.nightagent.voicemessage.model.PlaybackState
import com.example.nightagent.voicemessage.model.VoiceMessage
import com.example.nightagent.voicemessage.model.VoiceMessageUiModel
import com.example.nightagent.voicemessage.repository.VoiceMessageRepository
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

private const val TAG = "CHAT_REALTIME"

class VoiceMessageViewModel(
    application: Application,
    private val savedStateHandle: SavedStateHandle
) : AndroidViewModel(application) {

    // ── Dependencies ──────────────────────────────────────────────────────────

    private val repository = VoiceMessageRepository(application)
    val recorder = VoiceRecorder(application)
    val player   = VoicePlayer(application)

    // ── Identity ──────────────────────────────────────────────────────────────

    // Resolved after auth — starts empty, fills once Firebase Auth completes
    private val _currentUid = MutableStateFlow(
        FirebaseAuth.getInstance().currentUser?.uid ?: ""
    )
    val currentUid: String get() = _currentUid.value

    // The receiver's FIREBASE UID — resolved from phone number lookup
    // Nav argument "otherUserId" may be a phone number OR a UID
    private val _receiverUid = MutableStateFlow("")
    val receiverUid: String get() = _receiverUid.value

    // Display name for the top bar
    private val _receiverName = MutableStateFlow("")
    val receiverName: StateFlow<String> = _receiverName.asStateFlow()

    // chatId — computed once both UIDs are known
    private val _chatId = MutableStateFlow("")
    val chatId: String get() = _chatId.value

    // ── Screen state ──────────────────────────────────────────────────────────

    private val _screenState = MutableStateFlow<ChatScreenState>(ChatScreenState.Loading)
    val screenState: StateFlow<ChatScreenState> = _screenState.asStateFlow()

    private val _uploadProgress = MutableStateFlow<Map<String, Int>>(emptyMap())

    val recorderState: StateFlow<RecorderState> = recorder.state
    val amplitude: StateFlow<Int>               = recorder.amplitude
    val activeMessageId: StateFlow<String?>     = player.activeMessageId
    val playbackPositionMs: StateFlow<Long>     = player.positionMs

    // ── Messages stream ───────────────────────────────────────────────────────

    /**
     * Combines the Firestore real-time stream with playback state.
     * flatMapLatest on _chatId means the listener restarts whenever chatId changes
     * (e.g. after UID resolution completes).
     */
    val messages: StateFlow<List<VoiceMessageUiModel>> = combine(
        _chatId.flatMapLatest { cid ->
            if (cid.isBlank()) flowOf(emptyList())
            else {
                Log.d(TAG, "Starting message stream for chatId=$cid")
                repository.observeMessages(cid)
            }
        },
        player.playerState,
        player.positionMs,
        player.activeMessageId,
        _uploadProgress
    ) { rawMessages, playerState, posMs, activeId, uploadMap ->
        rawMessages.map { msg ->
            val isActive = msg.messageId == activeId
            val pbState = when {
                !isActive                                -> PlaybackState.IDLE
                playerState is VoicePlayerState.Loading  -> PlaybackState.LOADING
                playerState is VoicePlayerState.Playing  -> PlaybackState.PLAYING
                playerState is VoicePlayerState.Paused   -> PlaybackState.PAUSED
                else                                     -> PlaybackState.IDLE
            }
            VoiceMessageUiModel(
                message               = msg,
                isOwnMessage          = msg.senderId == _currentUid.value,
                playbackState         = pbState,
                playbackPositionMs    = if (isActive) posMs else 0L,
                uploadProgressPercent = uploadMap[msg.messageId] ?: 0
            )
        }
    }.stateIn(
        scope        = viewModelScope,
        started      = SharingStarted.WhileSubscribed(5_000),
        initialValue = emptyList()
    )

    // ── Init ──────────────────────────────────────────────────────────────────

    init {
        val navArg = savedStateHandle.get<String>("otherUserId") ?: ""
        Log.d(TAG, "VoiceMessageViewModel init: navArg=$navArg")
        viewModelScope.launch {
            initSession(navArg)
        }
    }

    /**
     * Full initialization sequence:
     * 1. Ensure Firebase Auth has a current user
     * 2. Resolve the receiver's UID (navArg may be phone number or UID)
     * 3. Compute chatId
     * 4. Start the Firestore listener
     */
    private suspend fun initSession(navArg: String) {
        // Step 1 — Auth
        val uid = ensureAuth() ?: run {
            _screenState.value = ChatScreenState.Error("Authentication failed")
            return
        }
        _currentUid.value = uid
        Log.d(TAG, "Auth OK: currentUid=$uid")

        // Step 2 — Resolve receiver UID
        val receiverUid = resolveReceiverUid(navArg, uid)
        if (receiverUid.isNullOrBlank()) {
            _screenState.value = ChatScreenState.Error(
                "Receiver not found. Ask them to open NightAgent first so they register."
            )
            return
        }
        _receiverUid.value = receiverUid
        Log.d(TAG, "Receiver UID resolved: $receiverUid")

        // Step 3 — Load receiver display name
        val profile = VoiceMessageFirebase.getUserProfile(receiverUid)
        _receiverName.value = profile?.displayName?.ifBlank { receiverUid.take(8) }
            ?: receiverUid.take(8)

        // Step 4 — Compute chatId and start listener
        val cid = VoiceMessageFirebase.chatId(uid, receiverUid)
        _chatId.value = cid
        Log.d(TAG, "chatId=$cid — listener starting")

        _screenState.value = ChatScreenState.Ready
    }

    private suspend fun ensureAuth(): String? {
        val auth = FirebaseAuth.getInstance()
        if (auth.currentUser != null) return auth.currentUser!!.uid
        return try {
            val result = auth.signInAnonymously().await()
            result.user?.uid
        } catch (e: Exception) {
            Log.e(TAG, "Auth failed: ${e.message}", e)
            null
        }
    }

    /**
     * Resolve the receiver's Firebase UID from the nav argument.
     *
     * The nav argument can be:
     *   a) A Firebase UID directly (if navigating from a known-UID source)
     *   b) A phone number (if navigating from Contacts screen)
     *
     * We try (a) first by checking if the string looks like a UID (no + or spaces).
     * If that fails, we do a Firestore lookup by phone number.
     */
    private suspend fun resolveReceiverUid(navArg: String, currentUid: String): String? {
        if (navArg.isBlank()) return null

        // If it's already a UID (not a phone number), use it directly
        // UIDs are alphanumeric, 28 chars. Phone numbers contain digits/+/-
        val looksLikeUid = navArg.length > 15 && !navArg.contains("+") && !navArg.contains(" ")
        if (looksLikeUid && navArg != currentUid) {
            Log.d(TAG, "navArg looks like UID: $navArg")
            return navArg
        }

        // Otherwise treat as phone number and look up UID
        Log.d(TAG, "Looking up UID for phone: $navArg")
        return repository.findReceiverUid(navArg)
    }

    // ── Recording ─────────────────────────────────────────────────────────────

    fun startRecording() {
        if (_currentUid.value.isBlank()) {
            _screenState.value = ChatScreenState.Error("Not signed in yet")
            return
        }
        if (_receiverUid.value.isBlank()) {
            _screenState.value = ChatScreenState.Error("Receiver not found")
            return
        }
        recorder.start(viewModelScope)
    }

    fun stopAndSend() {
        viewModelScope.launch {
            val result = recorder.stop() ?: return@launch
            when (result) {
                is RecordingResult.Success -> {
                    if (result.durationMs < MIN_DURATION_MS) {
                        result.file.delete()
                        return@launch
                    }
                    sendVoiceMessage(result)
                }
                is RecordingResult.Failure -> {
                    Log.w(TAG, "Recording result: ${result.reason}")
                    if (!result.reason.contains("longer", ignoreCase = true)) {
                        _screenState.value = ChatScreenState.Error(result.reason)
                    }
                }
            }
        }
    }

    fun cancelRecording() = recorder.cancel()

    // ── Send ──────────────────────────────────────────────────────────────────

    private fun sendVoiceMessage(result: RecordingResult.Success) {
        val uid = _currentUid.value
        val rid = _receiverUid.value
        if (uid.isBlank() || rid.isBlank()) return

        viewModelScope.launch {
            val tempId = "pending_${System.currentTimeMillis()}"
            _uploadProgress.value = _uploadProgress.value + (tempId to 0)

            val sendResult = repository.sendVoiceMessage(
                file       = result.file,
                durationMs = result.durationMs,
                senderId   = uid,
                receiverId = rid,
                onProgress = { pct ->
                    _uploadProgress.value = _uploadProgress.value + (tempId to pct)
                }
            )

            _uploadProgress.value = _uploadProgress.value - tempId

            if (sendResult.isFailure) {
                val msg = sendResult.exceptionOrNull()?.message ?: "Unknown error"
                Log.e(TAG, "Send failed: $msg")
                _screenState.value = ChatScreenState.Error("Send failed: $msg")
            }
        }
    }

    // ── Playback ──────────────────────────────────────────────────────────────

    fun togglePlayback(message: VoiceMessage) {
        if (message.audioUrl.isBlank()) {
            Log.w(TAG, "togglePlayback: audioUrl is blank for ${message.messageId}")
            return
        }
        player.playOrPause(message.messageId, message.audioUrl, viewModelScope)

        // Mark as seen when receiver plays
        if (message.receiverId == _currentUid.value &&
            message.messageStatus != MessageStatus.SEEN) {
            viewModelScope.launch {
                repository.markMessageSeen(_chatId.value, message.messageId)
            }
        }
    }

    fun seekTo(positionMs: Long) = player.seekTo(positionMs)

    // ── User registration ─────────────────────────────────────────────────────

    /**
     * Register the current user's phone number so others can find them.
     * Call this from the Settings screen or on first launch.
     */
    fun registerPhone(phoneNumber: String, displayName: String) {
        viewModelScope.launch {
            repository.registerCurrentUser(phoneNumber, displayName)
        }
    }

    // ── Cleanup ───────────────────────────────────────────────────────────────

    override fun onCleared() {
        super.onCleared()
        recorder.release()
        player.release()
    }

    // ── Factory ───────────────────────────────────────────────────────────────

    companion object {
        private const val MIN_DURATION_MS = 500L

        fun factory(): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app    = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY]!!
                val handle = createSavedStateHandle()
                VoiceMessageViewModel(app as Application, handle)
            }
        }
    }
}

sealed class ChatScreenState {
    object Loading : ChatScreenState()
    object Ready   : ChatScreenState()
    data class Error(val message: String) : ChatScreenState()
}
