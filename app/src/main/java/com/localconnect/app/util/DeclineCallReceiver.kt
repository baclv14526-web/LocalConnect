package com.localconnect.app.util

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.localconnect.app.model.MessageType
import com.localconnect.app.model.WireMessage
import com.localconnect.app.net.ConnectionManager
import com.localconnect.app.net.DeviceIdentity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** Nhận sự kiện bấm "Từ chối" trên notification cuộc gọi, gửi CALL_END cho bên kia. */
class DeclineCallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val peerId = intent.getStringExtra("peerId") ?: return
        NotificationHelper.cancelCallNotification(context)
        CoroutineScope(Dispatchers.IO).launch {
            ConnectionManager.send(
                WireMessage(
                    type = MessageType.CALL_END,
                    senderId = DeviceIdentity.myId,
                    senderName = DeviceIdentity.myName,
                    targetId = peerId
                )
            )
        }
    }
}
