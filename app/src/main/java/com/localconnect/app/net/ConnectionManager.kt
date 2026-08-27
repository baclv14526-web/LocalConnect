package com.localconnect.app.net

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
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets

private const val TAG = "ConnectionManager"
const val CONTROL_PORT = 8988

data class LivePeer(val id: String, val name: String, val host: String)

/**
 * Quản lý toàn bộ kết nối TCP giữa tối đa 5 thiết bị trong nhóm Wi-Fi Direct.
 *
 * Cách hình thành mạng:
 *  1) Wi-Fi Direct tạo nhóm: 1 máy làm Group Owner (GO/"host"), các máy khác join làm client.
 *     Theo đúng bản chất giao thức Wi-Fi Direct, GO LUÔN kết nối trực tiếp được tới mọi client -
 *     đây là điều được đảm bảo, không phụ thuộc cài đặt cô lập của Hotspot thường.
 *  2) GO đóng vai trò "bảng tin" (roster): mỗi khi danh sách client thay đổi, GO gửi broadcast
 *     một message PEER_LIST (id/tên/IP của mọi người) cho cả nhóm. Ai nhận được PEER_LIST sẽ tự
 *     động thử nối TCP trực tiếp tới từng peer chưa có kết nối -> theo thời gian mạng tự hình
 *     thành "full mesh" (ai cũng nối thẳng ai), giữ nguyên hành vi gửi/nhận như bản dùng NSD cũ.
 *  3) Phòng trường hợp hiếm 2 client không nối thẳng được nhau: nếu thiết bị hiện tại là GO và
 *     nhận một message không phải gửi cho chính mình, nó sẽ tự RELAY (chuyển tiếp) hộ.
 */
object ConnectionManager {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val connections = HashMap<String, PeerConnection>()
    private val connectionsMutex = Mutex()
    private var serverSocket: ServerSocket? = null
    private var serverJob: Job? = null

    var isHost: Boolean = false
        private set
    private var localId: String = ""
    private var localName: String = ""

    private val _incomingMessages = MutableSharedFlow<WireMessage>(extraBufferCapacity = 64)
    val incomingMessages = _incomingMessages.asSharedFlow()

    private val _livePeers = MutableStateFlow<Map<String, LivePeer>>(emptyMap())
    val livePeers: StateFlow<Map<String, LivePeer>> = _livePeers

    private class PeerConnection(
        val peerId: String,
        val peerName: String,
        val socket: Socket,
        val out: DataOutputStream,
        val writeLock: Mutex = Mutex()
    )

    /** Gọi khi biết vai trò của mình trong nhóm Wi-Fi Direct (host = Group Owner). */
    fun setRole(host: Boolean, myId: String, myName: String) {
        isHost = host
        localId = myId
        localName = myName
    }

    fun startServer(myId: String, myName: String) {
        localId = myId
        localName = myName
        if (serverJob != null) return
        serverJob = scope.launch {
            try {
                val server = ServerSocket(CONTROL_PORT)
                serverSocket = server
                Log.i(TAG, "Server đang lắng nghe tại cổng $CONTROL_PORT")
                while (true) {
                    val socket = server.accept()
                    scope.launch { bindSocket(socket, myId, myName) }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Server dừng: ${e.message}")
            }
        }
    }

    fun connectToPeer(peer: Peer, myId: String, myName: String) {
        scope.launch {
            if (peer.id == myId) return@launch
            if (connections.containsKey(peer.id)) return@launch
            try {
                val socket = Socket()
                socket.connect(InetSocketAddress(peer.host, peer.port), 5000)
                bindSocket(socket, myId, myName)
            } catch (e: Exception) {
                Log.w(TAG, "Không kết nối được ${peer.name} (${peer.host}): ${e.message}")
            }
        }
    }

    private suspend fun bindSocket(socket: Socket, myId: String, myName: String) {
        try {
            val out = DataOutputStream(socket.getOutputStream())
            val input = DataInputStream(socket.getInputStream())

            writeFramed(out, WireMessage(type = MessageType.HELLO, senderId = myId, senderName = myName).toJson())

            val firstRaw = readFramed(input) ?: return
            val firstMsg = WireMessage.fromJson(firstRaw)
            if (firstMsg.type != MessageType.HELLO) return
            val peerId = firstMsg.senderId
            if (peerId == myId) { socket.close(); return }

            val remoteHost = socket.inetAddress?.hostAddress ?: ""
            PeerHostRegistry.update(peerId, remoteHost)

            val conn = PeerConnection(peerId, firstMsg.senderName, socket, out)
            connectionsMutex.withLock {
                connections[peerId]?.let { old -> try { old.socket.close() } catch (_: Exception) {} }
                connections[peerId] = conn
            }
            _livePeers.update { it + (peerId to LivePeer(peerId, firstMsg.senderName, remoteHost)) }
            Log.i(TAG, "Đã kết nối với ${firstMsg.senderName} ($peerId) @ $remoteHost")
            broadcastRosterIfHost()

            while (true) {
                val raw = readFramed(input) ?: break
                val msg = WireMessage.fromJson(raw)

                if (msg.type == MessageType.PEER_LIST) {
                    handleRoster(msg)
                }

                _incomingMessages.emit(msg)

                if (isHost && msg.senderId != localId) {
                    relay(msg, fromPeerId = peerId)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Kết nối lỗi/đóng: ${e.message}")
        } finally {
            val peerId = connections.entries.firstOrNull { it.value.socket == socket }?.key
            if (peerId != null) {
                connectionsMutex.withLock { connections.remove(peerId) }
                _livePeers.update { it - peerId }
                broadcastRosterIfHost()
            }
            try { socket.close() } catch (_: Exception) {}
        }
    }

    /** Chỉ chạy trên máy GO: chuyển tiếp message không phải của/​cho chính mình. */
    private suspend fun relay(msg: WireMessage, fromPeerId: String) {
        if (msg.type == MessageType.PEER_LIST) return
        when {
            msg.targetId == null -> sendExcept(msg, fromPeerId)
            msg.targetId != localId -> sendTo(msg, msg.targetId)
            else -> {} // gửi thẳng cho host, không cần relay
        }
    }

    private fun buildRosterJson(): String {
        val arr = JSONArray()
        connections.values.forEach { conn ->
            arr.put(
                JSONObject()
                    .put("id", conn.peerId)
                    .put("name", conn.peerName)
                    .put("host", conn.socket.inetAddress?.hostAddress ?: "")
            )
        }
        return arr.toString()
    }

    private suspend fun broadcastRosterIfHost() {
        if (!isHost) return
        val json = buildRosterJson()
        val msg = WireMessage(type = MessageType.PEER_LIST, senderId = localId, senderName = localName, text = json)
        val payload = msg.toJson()
        connections.values.forEach { conn ->
            try { conn.writeLock.withLock { writeFramed(conn.out, payload) } } catch (_: Exception) {}
        }
    }

    private fun handleRoster(msg: WireMessage) {
        val json = msg.text ?: return
        try {
            val arr = JSONArray(json)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val id = o.getString("id")
                val name = o.getString("name")
                val host = o.getString("host")
                if (id == localId || host.isEmpty() || connections.containsKey(id)) continue
                connectToPeer(Peer(id = id, name = name, host = host, port = CONTROL_PORT), localId, localName)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Không đọc được danh sách peer: ${e.message}")
        }
    }

    /** targetId = null -> gửi cho cả nhóm (mọi kết nối hiện có); targetId != null -> gửi riêng. */
    suspend fun send(msg: WireMessage) {
        val targets = if (msg.targetId != null) listOfNotNull(connections[msg.targetId]) else connections.values.toList()
        val json = msg.toJson()
        for (conn in targets) {
            try {
                conn.writeLock.withLock { writeFramed(conn.out, json) }
            } catch (e: Exception) {
                Log.w(TAG, "Gửi tới ${conn.peerId} thất bại: ${e.message}")
            }
        }
    }

    private suspend fun sendExcept(msg: WireMessage, excludePeerId: String) {
        val json = msg.toJson()
        connections.filterKeys { it != excludePeerId }.values.forEach { conn ->
            try { conn.writeLock.withLock { writeFramed(conn.out, json) } } catch (_: Exception) {}
        }
    }

    private suspend fun sendTo(msg: WireMessage, targetId: String) {
        val conn = connections[targetId] ?: return
        val json = msg.toJson()
        try { conn.writeLock.withLock { writeFramed(conn.out, json) } } catch (_: Exception) {}
    }

    fun connectedIds(): Set<String> = connections.keys.toSet()

    /** Đóng hết kết nối peer hiện tại (khi rời nhóm) nhưng GIỮ server đang lắng nghe. */
    fun disconnectAllPeers() {
        connections.values.forEach { try { it.socket.close() } catch (_: Exception) {} }
        connections.clear()
        _livePeers.value = emptyMap()
        isHost = false
    }

    /** Tắt hẳn (khi service bị huỷ / app đóng). */
    fun stopAll() {
        disconnectAllPeers()
        try { serverSocket?.close() } catch (_: Exception) {}
        serverSocket = null
        serverJob?.cancel()
        serverJob = null
    }

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
