package com.example.nightagent.streaming

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.nightagent.firebase.FirebaseConfig
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

data class CallUiState(
    val callId: String = "",
    val streamState: StreamState = StreamState.IDLE,
    val isMuted: Boolean = false,
    val error: String? = null
)

class CallViewModel : ViewModel() {

    private val signalingRepo = SignalingRepository()
    private var guardianWebRTC: WebRTCManager? = null

    private val _uiState = MutableStateFlow(CallUiState())
    val uiState: StateFlow<CallUiState> = _uiState.asStateFlow()

    private val currentUid get() = FirebaseConfig.auth.currentUser?.uid ?: ""

    // ── Victim side ───────────────────────────────────────────────────────────

    /**
     * Called when victim triggers SOS. Creates a call document and starts
     * AudioStreamingService. Returns the callId to embed in FCM notification.
     */
    fun startSOSStream(context: Context, guardianUid: String) {
        viewModelScope.launch {
            runCatching {
                val callId = signalingRepo.createCall(
                    callerUid = currentUid,
                    calleeUid = guardianUid
                )
                _uiState.value = _uiState.value.copy(callId = callId, streamState = StreamState.CONNECTING)
                AudioStreamingService.start(context, callId, guardianUid)

                // Monitor call status to reflect end on victim side
                signalingRepo.statusFlow(callId).collect { status ->
                    if (status == SignalingRepository.STATUS_ENDED) {
                        _uiState.value = _uiState.value.copy(streamState = StreamState.ENDED)
                    }
                }
            }.onFailure { e ->
                _uiState.value = _uiState.value.copy(error = e.message, streamState = StreamState.ERROR)
            }
        }
    }

    fun stopSOSStream(context: Context) {
        AudioStreamingService.stop(context)
        val callId = _uiState.value.callId
        if (callId.isNotBlank()) {
            viewModelScope.launch { signalingRepo.endCall(callId) }
        }
        _uiState.value = _uiState.value.copy(streamState = StreamState.ENDED)
    }

    // ── Guardian side ─────────────────────────────────────────────────────────

    /**
     * Guardian opens the incoming SOS alert and joins audio.
     * Creates a WebRTC callee connection, listens for the offer, answers it.
     */
    fun joinAsGuardian(context: Context, callId: String) {
        _uiState.value = _uiState.value.copy(callId = callId, streamState = StreamState.CONNECTING)

        val manager = WebRTCManager(
            context    = context.applicationContext,
            isCaller   = false,
            onLocalCandidate = { candidate ->
                signalingRepo.addCalleeCandidate(callId, candidate)
            },
            onLocalSdp = { sdp ->
                signalingRepo.setAnswer(callId, sdp)
            }
        )
        guardianWebRTC = manager
        manager.initConnection()

        viewModelScope.launch {
            // Observe connection state
            launch {
                manager.state.collectLatest { state ->
                    _uiState.value = _uiState.value.copy(streamState = state)
                }
            }

            // Wait for offer then answer
            launch {
                signalingRepo.offerFlow(callId).collectLatest { sdp ->
                    if (sdp != null) {
                        manager.setRemoteOffer(sdp)
                        manager.createAnswer()
                    }
                }
            }

            // Receive caller ICE candidates
            launch {
                signalingRepo.callerCandidatesFlow(callId).collect { candidate ->
                    manager.addRemoteCandidate(candidate)
                }
            }

            // Auto-end when caller ends
            launch {
                signalingRepo.statusFlow(callId).collectLatest { status ->
                    if (status == SignalingRepository.STATUS_ENDED) leaveCall()
                }
            }
        }
    }

    fun leaveCall() {
        guardianWebRTC?.release()
        guardianWebRTC = null
        val callId = _uiState.value.callId
        if (callId.isNotBlank()) {
            viewModelScope.launch { signalingRepo.endCall(callId) }
        }
        _uiState.value = _uiState.value.copy(streamState = StreamState.ENDED)
    }

    fun toggleMute() {
        // Mute/unmute is handled at guardian side (no mic — listener only)
        _uiState.value = _uiState.value.copy(isMuted = !_uiState.value.isMuted)
    }

    override fun onCleared() {
        super.onCleared()
        guardianWebRTC?.release()
    }
}
