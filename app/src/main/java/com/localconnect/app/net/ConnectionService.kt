package com.localconnect.app.net

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.localconnect.app.App
import com.localconnect.app.MainActivity
import com.localconnect.app.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Foreground service giữ cho NSD advertise/discovery + TCP server luôn hoạt động khi app
 * chạy nền, để 5 máy trong nhóm luôn thấy nhau và nhận được tin nhắn/cuộc gọi tới.
 */
class ConnectionService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var nsd: NsdDiscoveryManager

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIF_ID, buildNotification())

        val myId = DeviceIdentity.myId
        val myName = DeviceIdentity.myName

        ConnectionManager.startServer(myId, myName)

        nsd = NsdDiscoveryManager(this)
        nsd.startAdvertising(myId, myName, CONTROL_PORT)
        nsd.startDiscovery(myId)

        // Tự động kết nối TCP tới bất kỳ thiết bị nào mới được tìm thấy trong nhóm
        scope.launch {
            nsd.discoveredPeers.collect { peers ->
                peers.values.forEach { peer ->
                    if (!ConnectionManager.connectedIds().contains(peer.id)) {
                        ConnectionManager.connectToPeer(peer, myId, myName)
                    }
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        nsd.stop()
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
