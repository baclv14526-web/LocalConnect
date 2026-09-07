package com.localconnect.app.net

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.localconnect.app.App
import com.localconnect.app.MainActivity
import com.localconnect.app.R
import com.localconnect.app.model.Peer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class ConnectionService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var clientConnectJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIF_ID,
                buildNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(NOTIF_ID, buildNotification())
        }

        val myId   = DeviceIdentity.myId
        val myName = DeviceIdentity.myName

        ConnectionManager.startServer(myId, myName)
        WifiDirectManager.startListening()

        scope.launch {
            WifiDirectManager.state.collect { s ->
                when {
                    s.groupFormed && s.isGroupOwner -> {
                        // Máy này là Group Owner: TCP server đã chạy, đợi client nối vào
                        clientConnectJob?.cancel()
                        clientConnectJob = null
                        if (!ConnectionManager.isHost) {
                            android.util.Log.i("ConnectionService", "Tôi là Group Owner, đặt vai trò Host")
                            ConnectionManager.setRole(host = true, myId, myName)
                        }
                    }

                    s.groupFormed && !s.isGroupOwner -> {
                        // Máy này là client: chủ động kết nối và tự động thử lại tới Group Owner
                        val goIp = s.groupOwnerAddress
                        if (goIp != null) {
                            if (clientConnectJob == null || clientConnectJob?.isActive != true) {
                                clientConnectJob = scope.launch {
                                    android.util.Log.i("ConnectionService", "Khởi động retry loop kết nối tới Group Owner @ $goIp")
                                    ConnectionManager.setRole(host = false, myId, myName)
                                    while (isActive) {
                                        val isConnected = ConnectionManager.isConnectedTo(goIp)
                                        if (isConnected) {
                                            delay(3000)
                                            continue
                                        }
                                        android.util.Log.i("ConnectionService", "Đang thử nối TCP tới Group Owner @ $goIp...")
                                        ConnectionManager.connectToPeer(
                                            Peer(id = goIp, name = "GroupOwner", host = goIp, port = CONTROL_PORT),
                                            myId, myName
                                        )
                                        delay(2000)
                                    }
                                }
                            }
                        }
                    }

                    !s.groupFormed -> {
                        // Nhóm tan: reset state
                        if (clientConnectJob != null || ConnectionManager.isHost) {
                            android.util.Log.i("ConnectionService", "Nhóm Wi-Fi Direct đã tan")
                            clientConnectJob?.cancel()
                            clientConnectJob = null
                            ConnectionManager.disconnectAllPeers()
                        }
                    }
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        clientConnectJob?.cancel()
        clientConnectJob = null
        WifiDirectManager.stopListening()
        ConnectionManager.stopAll()
    }

    private fun buildNotification(): Notification {
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, App.CHANNEL_SERVICE)
            .setContentTitle(getString(R.string.notif_service_title))
            .setContentText(getString(R.string.notif_service_text))
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }

    companion object { private const val NOTIF_ID = 1001 }
}
