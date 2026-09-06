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

    /** Tái khởi tạo channel — cần thiết sau khi Wi-Fi bị tắt/bật hoặc khi channel bị lỗi. */
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
            override fun onSuccess() { Log.i(TAG, "Bắt đầu quét Wi-Fi Direct") }
            override fun onFailure(reason: Int) {
                if (reason == WifiP2pManager.BUSY) {
                    // BUSY thường do channel cũ — reinit rồi thử lại sau 500ms
                    reinitChannel()
                    mainHandler.postDelayed({ discoverPeers() }, 500)
                } else {
                    _state.update { it.copy(lastError = "Quét thất bại: ${reasonText(reason)}") }
                }
            }
        })
    }

    /**
     * FIX BUG SAMSUNG: createGroup() thường trả về BUSY vì máy còn cache nhóm Wi-Fi Direct cũ
     * (persistent group). Giải pháp: removeGroup() để xoá nhóm cũ, reinit channel, rồi mới
     * createGroup(). Dùng Handler delay nhỏ để đợi framework xử lý xong lệnh remove.
     */
    @SuppressLint("MissingPermission")
    fun createGroup() {
        if (!initialized) return
        _state.update { it.copy(isCreatingGroup = true, lastError = null) }

        // Bước 1: Xoá nhóm cũ (nếu có). Cố tình bỏ qua lỗi của bước này vì có thể không có nhóm nào.
        manager.removeGroup(channel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                Log.i(TAG, "Đã xoá nhóm cũ, reinit channel rồi tạo nhóm mới sau 800ms")
                reinitChannel()
                mainHandler.postDelayed({ doCreateGroup() }, 800)
            }
            override fun onFailure(reason: Int) {
                // Không có nhóm cũ để xoá — reinit channel và tạo ngay
                Log.i(TAG, "Không có nhóm cũ (reason=$reason), tạo nhóm mới ngay")
                reinitChannel()
                mainHandler.postDelayed({ doCreateGroup() }, 400)
            }
        })
    }

    @SuppressLint("MissingPermission")
    private fun doCreateGroup() {
        manager.createGroup(channel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                Log.i(TAG, "createGroup() SUCCESS — chờ broadcast WIFI_P2P_CONNECTION_CHANGED")
                _state.update { it.copy(isCreatingGroup = false) }
            }
            override fun onFailure(reason: Int) {
                Log.e(TAG, "createGroup() FAILED reason=$reason")
                if (reason == WifiP2pManager.BUSY) {
                    // Vẫn còn BUSY sau khi đã reinit — thử thêm 1 lần nữa sau 1 giây
                    Log.w(TAG, "Vẫn BUSY, thử lần 2 sau 1 giây")
                    reinitChannel()
                    mainHandler.postDelayed({
                        manager.createGroup(channel, object : WifiP2pManager.ActionListener {
                            override fun onSuccess() { _state.update { it.copy(isCreatingGroup = false) } }
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
                        it.copy(isCreatingGroup = false, lastError = "Tạo nhóm thất bại: ${reasonText(reason)}")
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

    fun isSystemLocationEnabled(context: Context): Boolean {
        return try {
            val lm = context.getSystemService(Context.LOCATION_SERVICE) as android.location.LocationManager
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
                _state.update { it.copy(lastError = "Quét thất bại: ${reasonText(reason)}") }
            }
        })
    }

    /**
     * Nhiều máy (đặc biệt Samsung/One UI) yêu cầu công tắc "Vị trí" (Location) ở thanh thông
     * báo nhanh đang BẬT thì Wi-Fi Direct mới hoạt động, dù app đã có quyền ACCESS_FINE_LOCATION/
     * NEARBY_WIFI_DEVICES. App KHÔNG dùng vị trí thật của bạn, đây chỉ là yêu cầu phần cứng/hệ
     * điều hành của Android để cho phép quét Wi-Fi lân cận.
     */
    fun isSystemLocationEnabled(context: Context): Boolean {
        return try {
            val lm = context.getSystemService(Context.LOCATION_SERVICE) as android.location.LocationManager
            androidx.core.location.LocationManagerCompat.isLocationEnabled(lm)
        } catch (e: Exception) {
            true // không kiểm tra được thì thôi, không chặn UI vì lý do này
        }
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
                _state.update { it.copy(lastError = "Tạo nhóm thất bại: ${reasonText(reason)}") }
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
                _state.update { it.copy(lastError = "Không tham gia được nhóm: ${reasonText(reason)}") }
            }
        })
    }

    /** Diễn giải mã lỗi của WifiP2pManager sang tiếng Việt dễ hiểu, kèm gợi ý xử lý. */
    private fun reasonText(reason: Int): String = when (reason) {
        WifiP2pManager.ERROR ->
            "Lỗi hệ thống Wi-Fi Direct (ERROR). Hãy tắt/bật lại Wi-Fi rồi thử lại."
        WifiP2pManager.P2P_UNSUPPORTED ->
            "Máy này không hỗ trợ Wi-Fi Direct (P2P_UNSUPPORTED)."
        WifiP2pManager.BUSY ->
            "Wi-Fi Direct đang bận (BUSY) - có thể do máy đang tự dùng Wi-Fi Direct cho tính năng " +
                "khác (Quick Share/Smart View...), hoặc còn dính nhóm cũ. Hãy tắt/bật lại Wi-Fi rồi thử lại."
        WifiP2pManager.NO_SERVICE_REQUESTS ->
            "Không có yêu cầu dịch vụ đang chờ (NO_SERVICE_REQUESTS)."
        else ->
            "Mã lỗi $reason. Kiểm tra: đã bật Wi-Fi, đã cấp quyền Vị trí/Wi-Fi lân cận cho app, " +
                "và Dịch vụ Vị trí (GPS) ở thanh thông báo nhanh đang BẬT chưa."
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
