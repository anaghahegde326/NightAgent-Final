package com.example.nightagent.streaming

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.webrtc.*

enum class StreamState { IDLE, CONNECTING, CONNECTED, ENDED, ERROR }

/**
 * Manages a single audio-only WebRTC peer connection.
 *
 * Caller (victim)  → createOffer()  → ICE candidates via onLocalCandidate
 * Callee (guardian) → createAnswer() → ICE candidates via onLocalCandidate
 *
 * Both sides use [addRemoteCandidate] as Firestore delivers remote ICE candidates.
 */
class WebRTCManager(
    context: Context,
    private val isCaller: Boolean,
    private val onLocalCandidate: suspend (IceCandidateData) -> Unit,
    private val onLocalSdp: suspend (String) -> Unit   // SDP offer or answer
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _state = MutableStateFlow(StreamState.IDLE)
    val state: StateFlow<StreamState> = _state

    private lateinit var factory: PeerConnectionFactory
    private var peerConnection: PeerConnection? = null
    private var localAudioTrack: AudioTrack? = null

    init {
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(context.applicationContext)
                .setEnableInternalTracer(false)
                .createInitializationOptions()
        )
        val options = PeerConnectionFactory.Options()
        factory = PeerConnectionFactory.builder()
            .setOptions(options)
            .createPeerConnectionFactory()
    }

    // ── Setup ─────────────────────────────────────────────────────────────────

    fun initConnection() {
        val iceServers = listOf(
            PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
            PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").createIceServer()
        )
        val config = PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        }

        peerConnection = factory.createPeerConnection(config, object : PeerConnection.Observer {
            override fun onIceCandidate(candidate: IceCandidate) {
                scope.launch {
                    onLocalCandidate(
                        IceCandidateData(candidate.sdpMid, candidate.sdpMLineIndex, candidate.sdp)
                    )
                }
            }
            override fun onConnectionChange(newState: PeerConnection.PeerConnectionState) {
                Log.d(TAG, "Connection state: $newState")
                _state.value = when (newState) {
                    PeerConnection.PeerConnectionState.CONNECTED    -> StreamState.CONNECTED
                    PeerConnection.PeerConnectionState.CONNECTING,
                    PeerConnection.PeerConnectionState.NEW          -> StreamState.CONNECTING
                    PeerConnection.PeerConnectionState.FAILED,
                    PeerConnection.PeerConnectionState.DISCONNECTED -> StreamState.ERROR
                    PeerConnection.PeerConnectionState.CLOSED       -> StreamState.ENDED
                    else -> _state.value
                }
            }
            override fun onSignalingChange(p0: PeerConnection.SignalingState?) {}
            override fun onIceConnectionChange(p0: PeerConnection.IceConnectionState?) {}
            override fun onIceConnectionReceivingChange(p0: Boolean) {}
            override fun onIceGatheringChange(p0: PeerConnection.IceGatheringState?) {}
            override fun onIceCandidatesRemoved(p0: Array<out IceCandidate>?) {}
            override fun onAddStream(p0: MediaStream?) {}
            override fun onRemoveStream(p0: MediaStream?) {}
            override fun onDataChannel(p0: DataChannel?) {}
            override fun onRenegotiationNeeded() {}
            override fun onAddTrack(p0: RtpReceiver?, p1: Array<out MediaStream>?) {}
        }) ?: run { _state.value = StreamState.ERROR; return }

        if (isCaller) addLocalAudioTrack()
        _state.value = StreamState.CONNECTING
    }

    private fun addLocalAudioTrack() {
        val audioConstraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("googNoiseSuppression", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googEchoCancellation", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googAutoGainControl", "true"))
        }
        val audioSource = factory.createAudioSource(audioConstraints)
        localAudioTrack = factory.createAudioTrack("audio0", audioSource)
        peerConnection?.addTrack(localAudioTrack, listOf("stream0"))
    }

    // ── Offer / Answer ────────────────────────────────────────────────────────

    fun createOffer() {
        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "false"))
        }
        peerConnection?.createOffer(sdpObserver { sdp ->
            peerConnection?.setLocalDescription(simpleSdpObserver(), sdp)
            scope.launch { onLocalSdp(sdp.description) }
        }, constraints)
    }

    fun createAnswer() {
        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "false"))
        }
        peerConnection?.createAnswer(sdpObserver { sdp ->
            peerConnection?.setLocalDescription(simpleSdpObserver(), sdp)
            scope.launch { onLocalSdp(sdp.description) }
        }, constraints)
    }

    fun setRemoteOffer(sdp: String) {
        val desc = SessionDescription(SessionDescription.Type.OFFER, sdp)
        peerConnection?.setRemoteDescription(simpleSdpObserver(), desc)
    }

    fun setRemoteAnswer(sdp: String) {
        val desc = SessionDescription(SessionDescription.Type.ANSWER, sdp)
        peerConnection?.setRemoteDescription(simpleSdpObserver(), desc)
    }

    fun addRemoteCandidate(data: IceCandidateData) {
        peerConnection?.addIceCandidate(
            IceCandidate(data.sdpMid, data.sdpMLineIndex, data.candidate)
        )
    }

    // ── Teardown ──────────────────────────────────────────────────────────────

    fun release() {
        try {
            localAudioTrack?.dispose()
            peerConnection?.dispose()
            factory.dispose()
        } catch (e: Exception) {
            Log.e(TAG, "release error: ${e.message}")
        }
        _state.value = StreamState.ENDED
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun sdpObserver(onSuccess: (SessionDescription) -> Unit) =
        object : SdpObserver {
            override fun onCreateSuccess(sdp: SessionDescription) = onSuccess(sdp)
            override fun onSetSuccess() {}
            override fun onCreateFailure(msg: String?) { Log.e(TAG, "SDP create fail: $msg") }
            override fun onSetFailure(msg: String?) { Log.e(TAG, "SDP set fail: $msg") }
        }

    private fun simpleSdpObserver() = object : SdpObserver {
        override fun onCreateSuccess(p0: SessionDescription?) {}
        override fun onSetSuccess() {}
        override fun onCreateFailure(p0: String?) {}
        override fun onSetFailure(p0: String?) { Log.e(TAG, "setDesc fail: $p0") }
    }

    companion object { private const val TAG = "WebRTCManager" }
}
