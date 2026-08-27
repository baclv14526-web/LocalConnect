package com.localconnect.app.net

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.localconnect.app.App
import com.localconnect.app.MainActivity
import com.localconnect.app.R
import com.localconnect.app.model.Peer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Foreground service giữ cho Wi-Fi Direct + TCP server luôn hoạt động khi app chạy nền.
 *
 * Không còn dùng NSD/mDNS + Hotspot thường (bị một số máy như Samsung cô lập client-to-client).
 * Thay vào đó: WifiDirectManager hình thành nhóm Wi-Fi Direct, service này theo dõi kết quả
 * (mình là Group Owner hay client, địa chỉ IP của GO) để thiết lập kết nối TCP tương ứng.
 */
class ConnectionService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var lastKnownGroupOwnerAddress: String? = null

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIF_ID, buildNotification())

        val myId = DeviceIdentity.myId
        val myName = DeviceIdentity.myName

        // Server TCP luôn chạy: cả GO lẫn client đều cần lắng nghe, vì sau khi có PEER_LIST,
        // các máy có thể chủ động nối thẳng tới nhau (full mesh), không chỉ nối tới GO.
        ConnectionManager.startServer(myId, myName)
        WifiDirectManager.startListening()

        var wasGroupFormed = false
        scope.launch {
            WifiDirectManager.state.collect { s ->
                if (s.groupFormed) {
                    wasGroupFormed = true
                    if (s.isGroupOwner) {
                        if (!ConnectionManager.isHost) {
                            ConnectionManager.setRole(host = true, myId, myName)
                        }
                    } else {
                        val ip = s.groupOwnerAddress
                        if (ip != null && ip != lastKnownGroupOwnerAddress) {
                            lastKnownGroupOwnerAddress = ip
                            ConnectionManager.setRole(host = false, myId, myName)
                            ConnectionManager.connectToPeer(
                                Peer(id = "host-$ip", name = "Host", host = ip, port = CONTROL_PORT),
                                myId, myName
                            )
                        }
                    }
                } else if (wasGroupFormed) {
                    wasGroupFormed = false
                    lastKnownGroupOwnerAddress = null
                    ConnectionManager.disconnectAllPeers()
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        WifiDirectManager.stopListening()
        ConnectionManager.stopAll()
    }

    private fun buildNotification(): Notification {
        val openIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, App.CHANNEL_SERVICE)
            .setContentTitle(getString(R.string.notif_service_title))
            .setContentText(getString(R.string.notif_service_text))
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val NOTIF_ID = 1001
    }
}
