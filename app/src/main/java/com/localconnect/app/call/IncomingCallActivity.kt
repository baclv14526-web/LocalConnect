package com.localconnect.app.call

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.localconnect.app.R
import com.localconnect.app.model.MessageType
import com.localconnect.app.model.WireMessage
import com.localconnect.app.net.ConnectionManager
import com.localconnect.app.net.DeviceIdentity
import com.localconnect.app.util.NotificationHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Màn hình cuộc gọi đến — hiển thị full-screen kể cả khi điện thoại đang khoá.
 * Người dùng bấm "Trả lời" → mở CallActivity và kết thúc màn hình này.
 * Bấm "Từ chối" → gửi CALL_END cho bên kia và đóng.
 */
class IncomingCallActivity : AppCompatActivity() {

    private lateinit var peerId: String
    private lateinit var peerName: String
    private var isVideo = false
    private var remoteSdp: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_incoming_call)

        peerId    = intent.getStringExtra(EXTRA_PEER_ID)   ?: return finish()
        peerName  = intent.getStringExtra(EXTRA_PEER_NAME) ?: peerId
        isVideo   = intent.getBooleanExtra(EXTRA_IS_VIDEO, false)
        remoteSdp = intent.getStringExtra(EXTRA_REMOTE_SDP)

        val callType = if (isVideo) "Video" else "Thoại"
        findViewById<TextView>(R.id.tvCallerName).text = peerName
        findViewById<TextView>(R.id.tvCallType).text   = "Cuộc gọi $callType đến"

        findViewById<Button>(R.id.btnAnswer).setOnClickListener { answer() }
        findViewById<Button>(R.id.btnDecline).setOnClickListener { decline() }

        // Nếu notification bị bấm "Trả lời" trực tiếp
        if (intent.getBooleanExtra(EXTRA_AUTO_ANSWER, false)) answer()
    }

    private fun answer() {
        NotificationHelper.cancelCallNotification(this)
        startActivity(
            Intent(this, CallActivity::class.java).apply {
                putExtra(EXTRA_PEER_ID,     peerId)
                putExtra(EXTRA_PEER_NAME,   peerName)
                putExtra(EXTRA_IS_VIDEO,    isVideo)
                putExtra(EXTRA_IS_INCOMING, true)
                putExtra(EXTRA_REMOTE_SDP,  remoteSdp)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        )
        finish()
    }

    private fun decline() {
        NotificationHelper.cancelCallNotification(this)
        CoroutineScope(Dispatchers.IO).launch {
            ConnectionManager.send(
                WireMessage(
                    type       = MessageType.CALL_END,
                    senderId   = DeviceIdentity.myId,
                    senderName = DeviceIdentity.myName,
                    targetId   = peerId
                )
            )
        }
        finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        // Dừng chuông nếu activity bị huỷ theo cách nào đó (ví dụ bấm Back)
        NotificationHelper.stopRinging()
    }

    companion object {
        const val EXTRA_AUTO_ANSWER = "auto_answer"

        fun newIntent(
            context: Context,
            peerId: String,
            peerName: String,
            isVideo: Boolean,
            remoteSdp: String
        ) = Intent(context, IncomingCallActivity::class.java).apply {
            putExtra(EXTRA_PEER_ID,    peerId)
            putExtra(EXTRA_PEER_NAME,  peerName)
            putExtra(EXTRA_IS_VIDEO,   isVideo)
            putExtra(EXTRA_REMOTE_SDP, remoteSdp)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
    }
}
