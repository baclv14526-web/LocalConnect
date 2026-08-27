package com.localconnect.app.net

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pManager
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

private const val TAG = "WifiDirectManager"

data class WifiDirectState(
    val isWifiP2pEnabled: Boolean = false,
    val thisDeviceName: String = "",
    val peers: List<WifiP2pDevice> = emptyList(),
    val groupFormed: Boolean = false,
    val isGroupOwner: Boolean = false,
    val groupOwnerAddress: String? = null,
    val lastError: String? = null
)

/**
 * Bọc WifiP2pManager để tạo/tham gia một nhóm Wi-Fi Direct tối đa 5 máy, KHÔNG cần bật
 * Điểm phát Wi-Fi (Hotspot) thủ công và KHÔNG bị ảnh hưởng bởi tính năng "cô lập client"
 * mà nhiều hãng (Samsung, Xiaomi...) bật mặc định trên Hotspot chia sẻ mạng di động.
 *
 * Luồng dùng:
 *  - 1 máy gọi createGroup() -> trở thành Group Owner (GO), giữ vai trò "host" của phiên chat.
 *  - Các máy khác gọi discoverPeers() để quét, thấy máy GO trong `state.peers`, gọi connect(device)
 *    để tham gia nhóm đó làm client.
 *  - Khi nhóm hình thành xong (groupFormed = true), ConnectionService sẽ đọc isGroupOwner /
 *    groupOwnerAddress để thiết lập kết nối TCP (xem ConnectionService.kt).
 */
object WifiDirectManager {

    private lateinit var appContext: Context
    private lateinit var manager: WifiP2pManager
    private lateinit var channel: WifiP2pManager.Channel
    private var receiver: BroadcastReceiver? = null
    private var initialized = false

    private val _state = MutableStateFlow(WifiDirectState())
    val state: StateFlow<WifiDirectState> = _state

    fun init(context: Context) {
        if (initialized) return
        appContext = context.applicationContext
        manager = appContext.getSystemService(Context.WIFI_P2P_SERVICE) as WifiP2pManager
        channel = manager.initialize(appContext, Looper.getMainLooper(), null)
        initialized = true
    }

    fun startListening() {
        if (!initialized || receiver != null) return
        val filter = IntentFilter().apply {
            addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION)
        }
        val r = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                        val enabled = intent.getIntExtra(
                            WifiP2pManager.EXTRA_WIFI_STATE, -1
                        ) == WifiP2pManager.WIFI_P2P_STATE_ENABLED
                        _state.update { it.copy(isWifiP2pEnabled = enabled) }
                    }
                    WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> requestPeers()
                    WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> requestConnectionInfo()
                    WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION -> {
                        @Suppress("DEPRECATION")
                        val device = intent.getParcelableExtra<WifiP2pDevice>(WifiP2pManager.EXTRA_WIFI_P2P_DEVICE)
                        _state.update { it.copy(thisDeviceName = device?.deviceName ?: it.thisDeviceName) }
                    }
                }
            }
        }
        receiver = r
        appContext.registerReceiver(r, filter)
    }

    fun stopListening() {
        receiver?.let { try { appContext.unregisterReceiver(it) } catch (_: Exception) {} }
        receiver = null
    }

    @SuppressLint("MissingPermission")
    fun discoverPeers() {
        if (!initialized) return
        manager.discoverPeers(channel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() { Log.i(TAG, "Bắt đầu quét thiết bị Wi-Fi Direct lân cận") }
            override fun onFailure(reason: Int) {
                _state.update { it.copy(lastError = "Quét thất bại (mã lỗi $reason)") }
            }
        })
    }

    @SuppressLint("MissingPermission")
    private fun requestPeers() {
        if (!initialized) return
        try {
            manager.requestPeers(channel) { list ->
                _state.update { it.copy(peers = list.deviceList.toList()) }
            }
        } catch (e: SecurityException) {
            _state.update { it.copy(lastError = "Thiếu quyền vị trí/Wi-Fi lân cận") }
        }
    }

    private fun requestConnectionInfo() {
        if (!initialized) return
        manager.requestConnectionInfo(channel) { info ->
            _state.update {
                it.copy(
                    groupFormed = info.groupFormed,
                    isGroupOwner = info.isGroupOwner,
                    groupOwnerAddress = info.groupOwnerAddress?.hostAddress
                )
            }
        }
    }

    /** Trở thành chủ nhóm (Group Owner) - người khác sẽ tìm thấy và tham gia nhóm của mình. */
    @SuppressLint("MissingPermission")
    fun createGroup() {
        if (!initialized) return
        manager.createGroup(channel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() { Log.i(TAG, "Đã tạo nhóm Wi-Fi Direct, đang chờ người khác tham gia") }
            override fun onFailure(reason: Int) {
                _state.update { it.copy(lastError = "Tạo nhóm thất bại (mã lỗi $reason)") }
            }
        })
    }

    /** Tham gia nhóm của một thiết bị đã quét được. */
    @SuppressLint("MissingPermission")
    fun connect(device: WifiP2pDevice) {
        if (!initialized) return
        val config = WifiP2pConfig().apply {
            deviceAddress = device.deviceAddress
            groupOwnerIntent = 0 // ưu tiên để máy kia (đã tạo nhóm sẵn) làm Group Owner
        }
        manager.connect(channel, config, object : WifiP2pManager.ActionListener {
            override fun onSuccess() { Log.i(TAG, "Đang kết nối tới ${device.deviceName}...") }
            override fun onFailure(reason: Int) {
                _state.update { it.copy(lastError = "Không tham gia được nhóm (mã lỗi $reason)") }
            }
        })
    }

    /** Rời/giải tán nhóm hiện tại. */
    fun removeGroup() {
        if (!initialized) return
        try {
            manager.removeGroup(channel, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {}
                override fun onFailure(reason: Int) {}
            })
        } catch (_: Exception) {}
        _state.update { it.copy(groupFormed = false, isGroupOwner = false, groupOwnerAddress = null) }
    }

    fun clearError() {
        _state.update { it.copy(lastError = null) }
    }
}
