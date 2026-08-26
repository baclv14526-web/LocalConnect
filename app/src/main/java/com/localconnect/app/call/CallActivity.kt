package com.localconnect.app.call

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.localconnect.app.R
import com.localconnect.app.model.MessageType
import com.localconnect.app.model.WireMessage
import com.localconnect.app.net.ConnectionManager
import com.localconnect.app.net.DeviceIdentity
import kotlinx.coroutines.launch
import org.webrtc.EglBase
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoTrack

const val EXTRA_PEER_ID = "peer_id"
const val EXTRA_PEER_NAME = "peer_name"
const val EXTRA_IS_VIDEO = "is_video"
const val EXTRA_IS_INCOMING = "is_incoming"
const val EXTRA_REMOTE_SDP = "remote_sdp"

/**
 * Màn hình gọi thoại/gọi video 1-1 trong mạng LAN. Dùng XML thay vì Compose vì
 * SurfaceViewRenderer của WebRTC là một View cổ điển (không có bản Compose chính thức).
 */
class CallActivity : AppCompatActivity(), CallManager.Listener {

    private lateinit var eglBase: EglBase
    private lateinit var callManager: CallManager
    private lateinit var localView: SurfaceViewRenderer
    private lateinit var remoteView: SurfaceViewRenderer
    private lateinit var statusText: TextView
    private var micEnabled = true
    private var camEnabled = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_call)

        val peerId = intent.getStringExtra(EXTRA_PEER_ID) ?: return finish()
        val peerName = intent.getStringExtra(EXTRA_PEER_NAME) ?: peerId
        val isVideo = intent.getBooleanExtra(EXTRA_IS_VIDEO, false)
        val isIncoming = intent.getBooleanExtra(EXTRA_IS_INCOMING, false)
        val remoteSdp = intent.getStringExtra(EXTRA_REMOTE_SDP)

        localView = findViewById(R.id.localView)
        remoteView = findViewById(R.id.remoteView)
        statusText = findViewById(R.id.callStatusText)
        statusText.text = if (isIncoming) "Cuộc gọi từ $peerName..." else "Đang gọi $peerName..."

        if (!hasCallPermissions()) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA),
                REQ_PERMISSIONS
            )
        }

        eglBase = EglBase.create()
        localView.init(eglBase.eglBaseContext, null)
        remoteView.init(eglBase.eglBaseContext, null)
        localView.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT)
        remoteView.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT)
        localView.setMirror(true)

        callManager = CallManager(this, eglBase, peerId, isVideo, this)
        callManager.start(makeOffer = !isIncoming)
        if (isIncoming && remoteSdp != null) {
            callManager.onRemoteOffer(remoteSdp)
        }

        findViewById<android.widget.Button>(R.id.btnEndCall).setOnClickListener {
            callManager.hangUp()
            finish()
        }
        findViewById<android.widget.Button>(R.id.btnToggleMic).setOnClickListener {
            micEnabled = !micEnabled
            callManager.setMicEnabled(micEnabled)
        }
        findViewById<android.widget.Button>(R.id.btnToggleCam).setOnClickListener {
            camEnabled = !camEnabled
            callManager.setCameraEnabled(camEnabled)
        }

        lifecycleScope.launch {
            ConnectionManager.incomingMessages.collect { msg ->
                if (msg.senderId != peerId) return@collect
                when (msg.type) {
                    MessageType.CALL_ANSWER -> msg.sdp?.let { callManager.onRemoteAnswer(it) }
                    MessageType.CALL_ICE -> msg.iceCandidate?.let {
                        callManager.onRemoteIceCandidate(msg.iceSdpMid, msg.iceSdpMLineIndex, it)
                    }
                    MessageType.CALL_END -> {
                        Toast.makeText(this@CallActivity, "$peerName đã kết thúc cuộc gọi", Toast.LENGTH_SHORT).show()
                        finish()
                    }
                    else -> {}
                }
            }
        }
    }

    private fun hasCallPermissions(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    override fun onLocalStreamReady(track: VideoTrack?) {
        track?.addSink(localView)
    }

    override fun onRemoteStreamReady(track: VideoTrack?) {
        track?.addSink(remoteView)
    }

    override fun onCallConnected() {
        runOnUiThread { statusText.text = "Đã kết nối" }
    }

    override fun onCallEnded(reason: String) {
        runOnUiThread {
            Toast.makeText(this, reason, Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        callManager.release()
        localView.release()
        remoteView.release()
        eglBase.release()
    }

    companion object {
        private const val REQ_PERMISSIONS = 501
    }
}
