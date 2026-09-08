package com.localconnect.app.net

import android.content.Context
import android.net.ConnectivityManager
import android.os.Build
import android.util.Log
import com.localconnect.app.model.MessageType
import com.localconnect.app.model.Peer
import com.localconnect.app.model.WireMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.Collections

private const val TAG = "ConnectionManager"
const val CONTROL_PORT = 8988

data class LivePeer(val id: String, val name: String, val host: String)

object ConnectionManager {

    private var appContext: Context? = null

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // FIX BUG 3: dùng Mutex bảo vệ connections thay vì HashMap thô
    private val connections = LinkedHashMap<String, PeerConnection>()
    private val connectionsMutex = Mutex()

    private var serverSocket: ServerSocket? = null
    private var serverJob: Job? = null

    var isHost: Boolean = false
        private set

    private val _incomingMessages = MutableSharedFlow<WireMessage>(extraBufferCapacity = 128)
    val incomingMessages = _incomingMessages.asSharedFlow()

    private val _livePeers = MutableStateFlow<Map<String, LivePeer>>(emptyMap())
    val livePeers: StateFlow<Map<String, LivePeer>> = _livePeers

    private class PeerConnection(
        val peerId: String,      // ID THẬT từ HELLO handshake, không phải "host-$ip"
        val peerName: String,
        val socket: Socket,
        val out: DataOutputStream,
        val writeLock: Mutex = Mutex()
    )

    fun setRole(host: Boolean, myId: String, myName: String) {
        isHost = host
    }

    fun startServer(myId: String, myName: String) {
        if (serverJob != null) return
        serverJob = scope.launch {
            try {
                val server = ServerSocket().apply {
                    reuseAddress = true
                    bind(InetSocketAddress(CONTROL_PORT))
                }
                serverSocket = server
                Log.i(TAG, "TCP server lắng nghe cổng $CONTROL_PORT")
                while (true) {
                    val socket = server.accept()
                    scope.launch { bindSocket(socket) }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Server dừng: ${e.message}")
            }
        }
    }

    /**
     * Gán socket vào card mạng Wi-Fi Direct / P2P để tránh Android định tuyến
     * nhầm qua mạng di động 4G/5G khi cả 2 cùng bật.
     */
    fun bindSocketToP2p(socket: Socket) {
        // Cách 1: Tìm IP nội bộ của interface p2p (hoặc 192.168.49.x) rồi bind local endpoint
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            var localP2pAddr: java.net.InetAddress? = null
            if (interfaces != null) {
                for (iface in Collections.list(interfaces)) {
                    if (iface.isUp && (iface.name.contains("p2p", ignoreCase = true) || iface.name.contains("wlan", ignoreCase = true))) {
                        for (addr in Collections.list(iface.inetAddresses)) {
                            if (!addr.isLoopbackAddress && addr is Inet4Address && addr.hostAddress?.startsWith("192.168.49.") == true) {
                                localP2pAddr = addr
                                break
                            }
                        }
                    }
                    if (localP2pAddr != null) break
                }
            }
            if (localP2pAddr != null) {
                socket.bind(InetSocketAddress(localP2pAddr, 0))
                Log.i(TAG, "Đã bind socket tới local P2P IP: ${localP2pAddr.hostAddress}")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Không thể bind socket tới local P2P IP: ${e.message}")
        }

        // Cách 2: Nếu có Context và Android >= M, tìm Network P2P trong ConnectivityManager
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && appContext != null) {
                val cm = appContext?.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                cm?.allNetworks?.forEach { net ->
                    val lp = cm.getLinkProperties(net)
                    if (lp != null) {
                        val isP2p = lp.interfaceName?.contains("p2p", ignoreCase = true) == true ||
                                lp.linkAddresses.any { it.address.hostAddress?.startsWith("192.168.49.") == true }
                        if (isP2p) {
                            try {
                                net.bindSocket(socket)
                                Log.i(TAG, "Đã bind socket vào P2P Network (${lp.interfaceName})")
                            } catch (_: Exception) {}
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Không thể bind socket vào ConnectivityManager network: ${e.message}")
        }
    }

    /**
     * Kết nối TCP tới một peer.
     * Tự động bind socket vào mạng Wi-Fi Direct để không bị định tuyến qua 4G/5G.
     */
    fun connectToPeer(peer: Peer, myId: String, myName: String) {
        scope.launch {
            // Kiểm tra theo IP để tránh kết nối đôi trước khi có ID thật
            val alreadyConnected = connectionsMutex.withLock {
                connections.values.any { it.socket.inetAddress?.hostAddress == peer.host }
            }
            if (alreadyConnected) {
                Log.d(TAG, "Đã có kết nối tới ${peer.host}, bỏ qua")
                return@launch
            }
            try {
                Log.i(TAG, "Đang nối TCP tới ${peer.host}:${peer.port}")
                val socket = Socket()
                bindSocketToP2p(socket)
                socket.connect(InetSocketAddress(peer.host, peer.port), 6000)
                bindSocket(socket)
            } catch (e: Exception) {
                Log.w(TAG, "Không kết nối được ${peer.host}: ${e.message}")
            }
        }
    }

    /**
     * Giao thức bắt tay:
     *  1) Gửi HELLO của mình (myId, myName) ngay sau khi socket mở.
     *  2) Đọc HELLO của phía kia → lấy peerId THẬT.
     *  3) Lưu kết nối theo peerId thật → mọi send/relay sau đó dùng đúng key.
     *  4) Vào vòng lặp đọc message liên tục cho tới khi socket đóng.
     */
    private suspend fun bindSocket(socket: Socket) {
        var truePeerId: String? = null
        try {
            val out   = DataOutputStream(socket.getOutputStream())
            val input = DataInputStream(socket.getInputStream())

            // --- Bắt tay HELLO ---
            writeFramed(out, WireMessage(
                type = MessageType.HELLO,
                senderId = DeviceIdentity.myId,
                senderName = DeviceIdentity.myName
            ).toJson())

            val helloRaw = readFramed(input) ?: run {
                Log.w(TAG, "Không nhận được HELLO từ ${socket.inetAddress?.hostAddress}")
                return
            }
            val helloMsg = WireMessage.fromJson(helloRaw)
            if (helloMsg.type != MessageType.HELLO) {
                Log.w(TAG, "Gói đầu tiên không phải HELLO: ${helloMsg.type}")
                return
            }

            val peerId   = helloMsg.senderId
            val peerName = helloMsg.senderName
            truePeerId   = peerId

            if (peerId == DeviceIdentity.myId) {
                Log.w(TAG, "Kết nối tới chính mình, bỏ qua")
                return
            }

            val remoteHost = socket.inetAddress?.hostAddress ?: ""
            PeerHostRegistry.update(peerId, remoteHost)

            // --- Đăng ký kết nối theo ID THẬT ---
            val conn = PeerConnection(peerId, peerName, socket, out)
            connectionsMutex.withLock {
                val old = connections[peerId]
                if (old != null) {
                    // Đã có kết nối với peer này rồi (hai bên cùng nối nhau) → đóng cái mới
                    Log.d(TAG, "Đã có kết nối với $peerName, đóng socket thừa")
                    socket.close()
                    truePeerId = null
                    return
                }
                connections[peerId] = conn
            }
            _livePeers.update { it + (peerId to LivePeer(peerId, peerName, remoteHost)) }
            Log.i(TAG, "✅ Đã kết nối với $peerName ($peerId) @ $remoteHost")

            // Nếu là Host: gửi roster mới để client biết mọi người
            broadcastRosterIfHost()

            // --- Vòng lặp đọc message ---
            while (true) {
                val raw = readFramed(input) ?: break
                val msg = try { WireMessage.fromJson(raw) } catch (e: Exception) {
                    Log.w(TAG, "Parse message lỗi: ${e.message}")
                    continue
                }

                // Roster: client dùng để tự kết nối thêm peer
                if (msg.type == MessageType.PEER_LIST) {
                    handleRoster(msg)
                    continue  // PEER_LIST không emit lên UI
                }

                // Emit để UI / ViewModel xử lý
                _incomingMessages.emit(msg)

                // Host relay tin cho peer khác nếu cần
                if (isHost && msg.senderId != DeviceIdentity.myId) {
                    relay(msg, fromPeerId = peerId)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Socket lỗi (${truePeerId ?: socket.inetAddress?.hostAddress}): ${e.message}")
        } finally {
            if (truePeerId != null) {
                connectionsMutex.withLock { connections.remove(truePeerId) }
                _livePeers.update { it - truePeerId!! }
                Log.i(TAG, "🔌 Mất kết nối với $truePeerId")
                broadcastRosterIfHost()
            }
            try { socket.close() } catch (_: Exception) {}
        }
    }

    // --- Roster (Host → broadcast danh sách peer cho mọi client) ---

    private suspend fun broadcastRosterIfHost() {
        if (!isHost) return
        val snapshot = connectionsMutex.withLock { connections.values.toList() }
        if (snapshot.isEmpty()) return
        val arr = JSONArray()
        snapshot.forEach { conn ->
            arr.put(JSONObject()
                .put("id",   conn.peerId)
                .put("name", conn.peerName)
                .put("host", conn.socket.inetAddress?.hostAddress ?: ""))
        }
        val msg = WireMessage(
            type = MessageType.PEER_LIST,
            senderId = DeviceIdentity.myId,
            senderName = DeviceIdentity.myName,
            text = arr.toString()
        )
        val payload = msg.toJson()
        snapshot.forEach { conn ->
            try { conn.writeLock.withLock { writeFramed(conn.out, payload) } } catch (_: Exception) {}
        }
    }

    private fun handleRoster(msg: WireMessage) {
        val json = msg.text ?: return
        try {
            val arr = JSONArray(json)
            for (i in 0 until arr.length()) {
                val o    = arr.getJSONObject(i)
                val id   = o.getString("id")
                val name = o.getString("name")
                val host = o.getString("host")
                if (id == DeviceIdentity.myId || host.isEmpty()) continue
                scope.launch {
                    val already = connectionsMutex.withLock { connections.containsKey(id) }
                    if (!already) {
                        connectToPeer(Peer(id = id, name = name, host = host, port = CONTROL_PORT), DeviceIdentity.myId, DeviceIdentity.myName)
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "handleRoster lỗi: ${e.message}")
        }
    }

    // --- Relay (Host chuyển tiếp tin cho peer không kết nối thẳng nhau) ---

    private suspend fun relay(msg: WireMessage, fromPeerId: String) {
        when {
            msg.targetId == null                    -> sendExcept(msg, fromPeerId)   // broadcast
            msg.targetId != DeviceIdentity.myId     -> sendDirect(msg.targetId, msg) // gửi riêng tới peer khác
            // targetId == localId: tin gửi cho Host, đã emit ở trên rồi
        }
    }

    // --- Public send API ---

    /**
     * Gửi tin nhắn đi.
     * - targetId == null  → broadcast cho mọi peer đang kết nối
     * - targetId != null  → gửi riêng cho đúng peer đó
     */
    suspend fun send(msg: WireMessage) {
        val snapshot = connectionsMutex.withLock { connections.values.toList() }
        if (snapshot.isEmpty()) {
            Log.w(TAG, "send() gọi nhưng chưa có kết nối nào!")
            return
        }
        val json = msg.toJson()
        val targets = if (msg.targetId != null) {
            val direct = snapshot.filter { it.peerId == msg.targetId }
            if (direct.isNotEmpty()) {
                direct
            } else if (!isHost) {
                // Client không có đường nối trực tiếp với peer đích -> gửi cho Host để Host relay
                Log.d(TAG, "Không có kết nối trực tiếp với ${msg.targetId}, chuyển qua Host để relay")
                snapshot.take(1)
            } else {
                Log.w(TAG, "Host không tìm thấy kết nối với targetId=${msg.targetId}")
                emptyList()
            }
        } else {
            snapshot
        }
        targets.forEach { conn ->
            try {
                conn.writeLock.withLock { writeFramed(conn.out, json) }
                Log.d(TAG, "Đã gửi ${msg.type} tới ${conn.peerName}")
            } catch (e: Exception) {
                Log.w(TAG, "Gửi tới ${conn.peerId} thất bại: ${e.message}")
            }
        }
    }

    private suspend fun sendExcept(msg: WireMessage, excludeId: String) {
        val json = msg.toJson()
        val snapshot = connectionsMutex.withLock {
            connections.values.filter { it.peerId != excludeId }
        }
        snapshot.forEach { conn ->
            try { conn.writeLock.withLock { writeFramed(conn.out, json) } } catch (_: Exception) {}
        }
    }

    private suspend fun sendDirect(targetId: String, msg: WireMessage) {
        val conn = connectionsMutex.withLock { connections[targetId] } ?: return
        val json = msg.toJson()
        try { conn.writeLock.withLock { writeFramed(conn.out, json) } } catch (_: Exception) {}
    }

    fun connectedIds(): Set<String> = connections.keys.toSet()

    suspend fun isConnectedTo(hostOrId: String): Boolean {
        return connectionsMutex.withLock {
            connections.containsKey(hostOrId) ||
                    connections.values.any { it.socket.inetAddress?.hostAddress == hostOrId }
        }
    }

    fun disconnectAllPeers() {
        scope.launch {
            val snapshot = connectionsMutex.withLock {
                val list = connections.values.toList()
                connections.clear()
                list
            }
            snapshot.forEach { try { it.socket.close() } catch (_: Exception) {} }
            _livePeers.value = emptyMap()
            isHost = false
        }
    }

    fun stopAll() {
        disconnectAllPeers()
        try { serverSocket?.close() } catch (_: Exception) {}
        serverSocket = null
        serverJob?.cancel()
        serverJob = null
    }

    // --- Framing: 4-byte length prefix + UTF-8 JSON body ---

    private fun writeFramed(out: DataOutputStream, json: String) {
        val bytes = json.toByteArray(StandardCharsets.UTF_8)
        out.writeInt(bytes.size)
        out.write(bytes)
        out.flush()
    }

    private fun readFramed(input: DataInputStream): String? {
        return try {
            val len = input.readInt()
            if (len <= 0 || len > 10_000_000) return null
            val buf = ByteArray(len)
            input.readFully(buf)
            String(buf, StandardCharsets.UTF_8)
        } catch (e: Exception) {
            null
        }
    }
}
