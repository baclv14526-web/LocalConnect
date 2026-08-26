package com.localconnect.app.call

import android.content.Context
import android.util.Log
import com.localconnect.app.model.MessageType
import com.localconnect.app.model.WireMessage
import com.localconnect.app.net.ConnectionManager
import com.localconnect.app.net.DeviceIdentity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.webrtc.*

private const val TAG = "CallManager"

/**
 * Bọc PeerConnectionFactory của WebRTC cho một cuộc gọi 1-1 (thoại hoặc video) trong mạng LAN.
 * Vì cả nhóm cùng một mạng Wi-Fi hotspot 2.4GHz nên KHÔNG cần STUN/TURN: chỉ dùng host ICE
 * candidate nội bộ là đủ kết nối trực tiếp giữa 2 máy.
 */
class CallManager(
    private val context: Context,
    private val eglBase: EglBase,
    private val peerId: String,
    private val isVideoCall: Boolean,
    private val listener: Listener
) {
    interface Listener {
        fun onLocalStreamReady(track: VideoTrack?)
        fun onRemoteStreamReady(track: VideoTrack?)
        fun onCallConnected()
        fun onCallEnded(reason: String)
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private lateinit var factory: PeerConnectionFactory
    private var peerConnection: PeerConnection? = null
    private var videoCapturer: CameraVideoCapturer? = null
    private var localAudioTrack: AudioTrack? = null
    private var localVideoTrack: VideoTrack? = null
    private var surfaceTextureHelper: SurfaceTextureHelper? = null

    fun start(makeOffer: Boolean) {
        initFactory()
        peerConnection = createPeerConnection()
        addLocalTracks()
        if (makeOffer) createOffer()
    }

    private fun initFactory() {
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(context)
                .setEnableInternalTracer(false)
                .createInitializationOptions()
        )
        val encoderFactory = DefaultVideoEncoderFactory(eglBase.eglBaseContext, true, true)
        val decoderFactory = DefaultVideoDecoderFactory(eglBase.eglBaseContext)
        factory = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(encoderFactory)
            .setVideoDecoderFactory(decoderFactory)
            .createPeerConnectionFactory()
    }

    private fun createPeerConnection(): PeerConnection? {
        // Không có STUN/TURN: chỉ cần host candidate trong cùng subnet của hotspot 2.4GHz
        val rtcConfig = PeerConnection.RTCConfiguration(emptyList())
        rtcConfig.sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
        rtcConfig.iceTransportsType = PeerConnection.IceTransportsType.ALL

        return factory.createPeerConnection(rtcConfig, object : PeerConnection.Observer {
            override fun onIceCandidate(candidate: IceCandidate) {
                scope.launch {
                    ConnectionManager.send(
                        WireMessage(
                            type = MessageType.CALL_ICE,
                            senderId = DeviceIdentity.myId,
                            senderName = DeviceIdentity.myName,
                            targetId = peerId,
                            iceSdpMid = candidate.sdpMid,
                            iceSdpMLineIndex = candidate.sdpMLineIndex,
                            iceCandidate = candidate.sdp
                        )
                    )
                }
            }

            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {
                Log.i(TAG, "ICE state: $state")
                if (state == PeerConnection.IceConnectionState.CONNECTED) {
                    scope.launch { listener.onCallConnected() }
                } else if (state == PeerConnection.IceConnectionState.FAILED ||
                    state == PeerConnection.IceConnectionState.DISCONNECTED
                ) {
                    scope.launch { listener.onCallEnded("Mất kết nối") }
                }
            }

            override fun onAddTrack(receiver: RtpReceiver, streams: Array<out MediaStream>) {
                val track = receiver.track()
                if (track is VideoTrack) {
                    scope.launch { listener.onRemoteStreamReady(track) }
                }
            }

            override fun onTrack(transceiver: RtpTransceiver) {}
            override fun onSignalingChange(p0: PeerConnection.SignalingState?) {}
            override fun onIceConnectionReceivingChange(p0: Boolean) {}
            override fun onIceGatheringChange(p0: PeerConnection.IceGatheringState?) {}
            override fun onIceCandidatesRemoved(p0: Array<out IceCandidate>?) {}
            override fun onAddStream(p0: MediaStream?) {}
            override fun onRemoveStream(p0: MediaStream?) {}
            override fun onDataChannel(p0: DataChannel?) {}
            override fun onRenegotiationNeeded() {}
        })
    }

    private fun addLocalTracks() {
        val audioSource = factory.createAudioSource(MediaConstraints())
        val audioTrack = factory.createAudioTrack("AUDIO_$peerId", audioSource)
        localAudioTrack = audioTrack
        peerConnection?.addTrack(audioTrack)

        if (isVideoCall) {
            val capturer = createCameraCapturer() ?: return
            videoCapturer = capturer
            val videoSource = factory.createVideoSource(capturer.isScreencast)
            val helper = SurfaceTextureHelper.create("CaptureThread", eglBase.eglBaseContext)
            surfaceTextureHelper = helper
            capturer.initialize(helper, context, videoSource.capturerObserver)
            capturer.startCapture(1280, 720, 30)
            val videoTrack = factory.createVideoTrack("VIDEO_$peerId", videoSource)
            localVideoTrack = videoTrack
            peerConnection?.addTrack(videoTrack)
        }
        listener.onLocalStreamReady(localVideoTrack)
    }

    private fun createCameraCapturer(): CameraVideoCapturer? {
        val enumerator = Camera2Enumerator(context)
        val deviceNames = enumerator.deviceNames
        val front = deviceNames.firstOrNull { enumerator.isFrontFacing(it) }
        val chosen = front ?: deviceNames.firstOrNull()
        return chosen?.let { enumerator.createCapturer(it, null) }
    }

    private fun createOffer() {
        val constraints = MediaConstraints()
        peerConnection?.createOffer(object : SdpAdapter("createOffer") {
            override fun onCreateSuccess(desc: SessionDescription?) {
                if (desc == null) return
                peerConnection?.setLocalDescription(SdpAdapter("setLocalOffer"), desc)
                scope.launch {
                    ConnectionManager.send(
                        WireMessage(
                            type = MessageType.CALL_OFFER,
                            senderId = DeviceIdentity.myId,
                            senderName = DeviceIdentity.myName,
                            targetId = peerId,
                            isVideoCall = isVideoCall,
                            sdp = desc.description,
                            sdpType = desc.type.canonicalForm()
                        )
                    )
                }
            }
        }, constraints)
    }

    fun onRemoteOffer(sdp: String) {
        val desc = SessionDescription(SessionDescription.Type.OFFER, sdp)
        peerConnection?.setRemoteDescription(SdpAdapter("setRemoteOffer"), desc)
        peerConnection?.createAnswer(object : SdpAdapter("createAnswer") {
            override fun onCreateSuccess(answer: SessionDescription?) {
                if (answer == null) return
                peerConnection?.setLocalDescription(SdpAdapter("setLocalAnswer"), answer)
                scope.launch {
                    ConnectionManager.send(
                        WireMessage(
                            type = MessageType.CALL_ANSWER,
                            senderId = DeviceIdentity.myId,
                            senderName = DeviceIdentity.myName,
                            targetId = peerId,
                            sdp = answer.description,
                            sdpType = answer.type.canonicalForm()
                        )
                    )
                }
            }
        }, MediaConstraints())
    }

    fun onRemoteAnswer(sdp: String) {
        val desc = SessionDescription(SessionDescription.Type.ANSWER, sdp)
        peerConnection?.setRemoteDescription(SdpAdapter("setRemoteAnswer"), desc)
    }

    fun onRemoteIceCandidate(sdpMid: String?, sdpMLineIndex: Int, candidate: String) {
        peerConnection?.addIceCandidate(IceCandidate(sdpMid, sdpMLineIndex, candidate))
    }

    fun setMicEnabled(enabled: Boolean) {
        localAudioTrack?.setEnabled(enabled)
    }

    fun setCameraEnabled(enabled: Boolean) {
        localVideoTrack?.setEnabled(enabled)
    }

    fun switchCamera() {
        videoCapturer?.switchCamera(null)
    }

    fun hangUp() {
        scope.launch {
            ConnectionManager.send(
                WireMessage(
                    type = MessageType.CALL_END,
                    senderId = DeviceIdentity.myId,
                    senderName = DeviceIdentity.myName,
                    targetId = peerId
                )
            )
        }
        release()
    }

    fun release() {
        try { videoCapturer?.stopCapture() } catch (_: Exception) {}
        videoCapturer?.dispose()
        surfaceTextureHelper?.dispose()
        localAudioTrack?.dispose()
        localVideoTrack?.dispose()
        peerConnection?.close()
        peerConnection?.dispose()
        if (::factory.isInitialized) factory.dispose()
    }
}

/** Adapter để không phải override toàn bộ hàm rỗng của SdpObserver mỗi lần dùng. */
private open class SdpAdapter(private val tag: String) : SdpObserver {
    override fun onCreateSuccess(desc: SessionDescription?) {}
    override fun onSetSuccess() {}
    override fun onCreateFailure(error: String?) { Log.e("SdpAdapter", "$tag onCreateFailure: $error") }
    override fun onSetFailure(error: String?) { Log.e("SdpAdapter", "$tag onSetFailure: $error") }
}
