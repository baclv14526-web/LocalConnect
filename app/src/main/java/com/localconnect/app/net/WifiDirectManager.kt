package com.localconnect.app.net

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pManager
import android.os.Handler
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
    val lastError: String? = null,
    val isCreatingGroup: Boolean = false
)

object WifiDirectManager {

    private lateinit var appContext: Context
    private lateinit var manager: WifiP2pManager
    private lateinit var channel: WifiP2pManager.Channel
    private var receiver: BroadcastReceiver? = null
    private var initialized = false
    private val mainHandler = Handler(Looper.getMainLooper())

    private val _state = MutableStateFlow(WifiDirectState())
    val state: StateFlow<WifiDirectState> = _state

    fun init(context: Context) {
        if (initialized) return
        appContext = context.applicationContext
        manager = appContext.getSystemService(Context.WIFI_P2P_SERVICE) as WifiP2pManager
        channel = manager.initialize(appContext, Looper.getMainLooper(), null)
        initialized = true
    }

    private fun reinitChannel() {
        channel = manager.initialize(appContext, Looper.getMainLooper(), null)
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
                    WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                        @Suppress("DEPRECATION")
                        val p2pInfo = intent.getParcelableExtra<android.net.wifi.p2p.WifiP2pInfo>(WifiP2pManager.EXTRA_WIFI_P2P_INFO)
                        if (p2pInfo != null) {
                            val goIp = p2pInfo.groupOwnerAddress?.hostAddress ?: if (p2pInfo.groupFormed) "192.168.49.1" else null
                            _state.update {
                                it.copy(
                                    groupFormed = p2pInfo.groupFormed,
                                    isGroupOwner = p2pInfo.isGroupOwner,
                                    groupOwnerAddress = goIp
                                )
                            }
                        }
                        requestConnectionInfo()
                    }
                    WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION -> {
                        @Suppress("DEPRECATION")
                        val device = intent.getParcelableExtra<WifiP2pDevice>(
                            WifiP2pManager.EXTRA_WIFI_P2P_DEVICE
                        )
                        _state.update {
                            it.copy(thisDeviceName = device?.deviceName ?: it.thisDeviceName)
                        }
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
            override fun onSuccess() { Log.i(TAG, "Bắt đầu quét Wi-Fi Direct") }
            override fun onFailure(reason: Int) {
                if (reason == WifiP2pManager.BUSY) {
                    reinitChannel()
                    mainHandler.postDelayed({ discoverPeers() }, 500)
                } else {
                    _state.update { it.copy(lastError = "Quét thất bại: ${reasonText(reason)}") }
                }
            }
        })
    }

    /**
     * FIX SAMSUNG BUSY: xoá nhóm cũ (persistent group) → reinit channel → createGroup.
     */
    @SuppressLint("MissingPermission")
    fun createGroup() {
        if (!initialized) return
        _state.update { it.copy(isCreatingGroup = true, lastError = null) }

        manager.removeGroup(channel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                Log.i(TAG, "Đã xoá nhóm cũ, reinit rồi tạo nhóm mới sau 800ms")
                reinitChannel()
                mainHandler.postDelayed({ doCreateGroup() }, 800)
            }
            override fun onFailure(reason: Int) {
                Log.i(TAG, "Không có nhóm cũ (reason=$reason), tạo ngay sau 400ms")
                reinitChannel()
                mainHandler.postDelayed({ doCreateGroup() }, 400)
            }
        })
    }

    @SuppressLint("MissingPermission")
    private fun doCreateGroup() {
        manager.createGroup(channel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                Log.i(TAG, "createGroup() SUCCESS")
                _state.update { it.copy(isCreatingGroup = false) }
            }
            override fun onFailure(reason: Int) {
                Log.e(TAG, "createGroup() FAILED reason=$reason")
                if (reason == WifiP2pManager.BUSY) {
                    Log.w(TAG, "Vẫn BUSY, thử lần 2 sau 1 giây")
                    reinitChannel()
                    mainHandler.postDelayed({
                        manager.createGroup(channel, object : WifiP2pManager.ActionListener {
                            override fun onSuccess() {
                                _state.update { it.copy(isCreatingGroup = false) }
                            }
                            override fun onFailure(r2: Int) {
                                _state.update {
                                    it.copy(
                                        isCreatingGroup = false,
                                        lastError = "Tạo nhóm thất bại: ${reasonText(r2)}"
                                    )
                                }
                            }
                        })
                    }, 1000)
                } else {
                    _state.update {
                        it.copy(
                            isCreatingGroup = false,
                            lastError = "Tạo nhóm thất bại: ${reasonText(reason)}"
                        )
                    }
                }
            }
        })
    }

    @SuppressLint("MissingPermission")
    fun connect(device: WifiP2pDevice) {
        if (!initialized) return
        val config = WifiP2pConfig().apply {
            deviceAddress = device.deviceAddress
            groupOwnerIntent = 0
        }
        manager.connect(channel, config, object : WifiP2pManager.ActionListener {
            override fun onSuccess() { Log.i(TAG, "Đang kết nối tới ${device.deviceName}") }
            override fun onFailure(reason: Int) {
                _state.update { it.copy(lastError = "Không tham gia được nhóm: ${reasonText(reason)}") }
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
            _state.update { it.copy(lastError = "Thiếu quyền vị trí/Wi-Fi lân cận: ${e.message}") }
        }
    }

    fun requestConnectionInfo() {
        if (!initialized) return
        try {
            manager.requestConnectionInfo(channel) { info ->
                if (info == null) return@requestConnectionInfo
                val goIp = info.groupOwnerAddress?.hostAddress ?: if (info.groupFormed) "192.168.49.1" else null
                _state.update {
                    it.copy(
                        groupFormed = info.groupFormed,
                        isGroupOwner = info.isGroupOwner,
                        groupOwnerAddress = goIp
                    )
                }
            }
        } catch (_: Exception) {}
    }

    fun removeGroup() {
        if (!initialized) return
        try {
            manager.removeGroup(channel, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {}
                override fun onFailure(reason: Int) {}
            })
        } catch (_: Exception) {}
        _state.update {
            it.copy(groupFormed = false, isGroupOwner = false, groupOwnerAddress = null)
        }
    }

    fun isSystemLocationEnabled(context: Context): Boolean {
        return try {
            val lm = context.getSystemService(Context.LOCATION_SERVICE)
                as android.location.LocationManager
            androidx.core.location.LocationManagerCompat.isLocationEnabled(lm)
        } catch (e: Exception) {
            true
        }
    }

    private fun reasonText(reason: Int): String = when (reason) {
        WifiP2pManager.ERROR ->
            "Lỗi hệ thống Wi-Fi Direct (ERROR). Hãy tắt/bật lại Wi-Fi rồi thử lại."
        WifiP2pManager.P2P_UNSUPPORTED ->
            "Máy này không hỗ trợ Wi-Fi Direct (P2P_UNSUPPORTED)."
        WifiP2pManager.BUSY ->
            "Wi-Fi Direct đang bận (BUSY). Hãy tắt/bật lại Wi-Fi rồi thử lại."
        WifiP2pManager.NO_SERVICE_REQUESTS ->
            "Không có yêu cầu dịch vụ (NO_SERVICE_REQUESTS)."
        else ->
            "Mã lỗi $reason — hãy bật Vị trí (GPS) ở thanh thông báo nhanh rồi thử lại."
    }

    fun clearError() {
        _state.update { it.copy(lastError = null) }
    }
}
