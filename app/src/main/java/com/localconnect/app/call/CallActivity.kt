package com.localconnect.app.call

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.localconnect.app.R
import com.localconnect.app.model.MessageType
import kotlinx.coroutines.launch
import org.webrtc.EglBase
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoTrack

const val EXTRA_PEER_ID     = "peer_id"
const val EXTRA_PEER_NAME   = "peer_name"
const val EXTRA_IS_VIDEO    = "is_video"
const val EXTRA_IS_INCOMING = "is_incoming"
const val EXTRA_REMOTE_SDP  = "remote_sdp"

/**
 * Màn hình gọi thoại/gọi video 1-1.
 * Dùng XML layout (activity_call.xml) vì SurfaceViewRenderer của WebRTC là View cổ điển.
 */
class CallActivity : AppCompatActivity(), CallManager.Listener {

    private lateinit var eglBase: EglBase
    private lateinit var callManager: CallManager
    private lateinit var localView: SurfaceViewRenderer
    private lateinit var remoteView: SurfaceViewRenderer
    private lateinit var statusText: TextView
    private lateinit var btnEndCall: Button
    private lateinit var btnToggleMic: Button
    private lateinit var btnToggleCam: Button

    private var micEnabled = true
    private var camEnabled = true
    private var callStarted = false

    private var peerId   = ""
    private var peerName = ""
    private var isVideo  = false
    private var isIncoming = false
    private var remoteSdp: String? = null
    private var pendingRemoteAnswer: String? = null

    private var audioManager: AudioManager? = null
    private var previousAudioMode: Int = AudioManager.MODE_NORMAL
    private var previousSpeakerphoneOn: Boolean = false

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        val allGranted = grants.values.all { it }
        if (allGranted) {
            startCall()
        } else {
            Toast.makeText(this,
                "Cần cấp quyền Micro${if (isVideo) " và Camera" else ""} để thực hiện cuộc gọi.",
                Toast.LENGTH_LONG).show()
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_call)

        peerId     = intent.getStringExtra(EXTRA_PEER_ID)     ?: return finish()
        peerName   = intent.getStringExtra(EXTRA_PEER_NAME)   ?: peerId
        isVideo    = intent.getBooleanExtra(EXTRA_IS_VIDEO, false)
        isIncoming = intent.getBooleanExtra(EXTRA_IS_INCOMING, false)
        remoteSdp  = intent.getStringExtra(EXTRA_REMOTE_SDP)

        localView  = findViewById(R.id.localView)
        remoteView = findViewById(R.id.remoteView)
        statusText = findViewById(R.id.callStatusText)
        btnEndCall    = findViewById(R.id.btnEndCall)
        btnToggleMic  = findViewById(R.id.btnToggleMic)
        btnToggleCam  = findViewById(R.id.btnToggleCam)

        statusText.text = if (isIncoming) "Cuộc gọi từ $peerName..." else "Đang gọi $peerName..."
        btnToggleCam.isEnabled = isVideo

        eglBase = EglBase.create()
        localView.init(eglBase.eglBaseContext, null)
        remoteView.init(eglBase.eglBaseContext, null)
        localView.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT)
        remoteView.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT)
        localView.setMirror(true)

        // Lắng nghe tín hiệu WebRTC từ CallSignalManager (đã bao gồm các gói đến sớm)
        lifecycleScope.launch {
            CallSignalManager.signalEvents.collect { msg ->
                if (msg.senderId != peerId) return@collect
                when (msg.type) {
                    MessageType.CALL_ANSWER -> {
                        msg.sdp?.let {
                            if (callStarted) {
                                callManager.onRemoteAnswer(it)
                            } else {
                                pendingRemoteAnswer = it
                            }
                        }
                    }
                    MessageType.CALL_ICE -> {
                        msg.iceCandidate?.let {
                            if (callStarted) {
                                callManager.onRemoteIceCandidate(
                                    msg.iceSdpMid, msg.iceSdpMLineIndex, it
                                )
                            }
                        }
                    }
                    MessageType.CALL_END -> {
                        Toast.makeText(this@CallActivity,
                            "$peerName đã kết thúc cuộc gọi", Toast.LENGTH_SHORT).show()
                        finishSafely()
                    }
                    else -> {}
                }
            }
        }

        // Xin quyền; nếu đã có hết thì gọi startCall() ngay
        val needed = buildList {
            add(Manifest.permission.RECORD_AUDIO)
            if (isVideo) add(Manifest.permission.CAMERA)
        }.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (needed.isEmpty()) {
            startCall()
        } else {
            permissionLauncher.launch(needed.toTypedArray())
        }
    }

    private fun setupAudio() {
        try {
            val am = getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
            audioManager = am
            previousAudioMode = am.mode
            previousSpeakerphoneOn = am.isSpeakerphoneOn
            am.mode = AudioManager.MODE_IN_COMMUNICATION
            am.isSpeakerphoneOn = isVideo
        } catch (_: Exception) {}
    }

    private fun restoreAudio() {
        audioManager?.let { am ->
            try {
                am.mode = previousAudioMode
                am.isSpeakerphoneOn = previousSpeakerphoneOn
            } catch (_: Exception) {}
        }
    }

    private fun startCall() {
        setupAudio()
        callManager = CallManager(this, eglBase, peerId, isVideo, this)
        callStarted = true
        callManager.start(makeOffer = !isIncoming)
        if (isIncoming && remoteSdp != null) {
            callManager.onRemoteOffer(remoteSdp!!)
        }
        pendingRemoteAnswer?.let {
            callManager.onRemoteAnswer(it)
            pendingRemoteAnswer = null
        }
        setupButtons()
    }

    private fun setupButtons() {
        btnEndCall.setOnClickListener {
            callManager.hangUp()
            finishSafely()
        }
        btnToggleMic.setOnClickListener {
            micEnabled = !micEnabled
            callManager.setMicEnabled(micEnabled)
            btnToggleMic.text = if (micEnabled) "Mic" else "Mic (tắt)"
        }
        btnToggleCam.setOnClickListener {
            camEnabled = !camEnabled
            callManager.setCameraEnabled(camEnabled)
            btnToggleCam.text = if (camEnabled) "Cam" else "Cam (tắt)"
        }
    }

    override fun onLocalStreamReady(track: VideoTrack?) {
        runOnUiThread { track?.addSink(localView) }
    }

    override fun onRemoteStreamReady(track: VideoTrack?) {
        runOnUiThread { track?.addSink(remoteView) }
    }

    override fun onCallConnected() {
        runOnUiThread { statusText.text = "Đã kết nối ✅" }
    }

    override fun onCallEnded(reason: String) {
        runOnUiThread {
            Toast.makeText(this, reason, Toast.LENGTH_SHORT).show()
            finishSafely()
        }
    }

    private fun finishSafely() {
        if (!isFinishing) finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        restoreAudio()
        CallSignalManager.clear(peerId)
        if (callStarted) {
            try { callManager.release() } catch (_: Exception) {}
        }
        try { localView.release()  } catch (_: Exception) {}
        try { remoteView.release() } catch (_: Exception) {}
        try { eglBase.release()    } catch (_: Exception) {}
    }
}
