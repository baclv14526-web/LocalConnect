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
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets

private const val TAG = "ConnectionManager"
const val CONTROL_PORT = 8988

/**
 * Quản lý toàn bộ kết nối TCP tới tối đa 4 thiết bị còn lại trong nhóm 5 người.
 * Giao thức: mỗi message là JSON, được đóng khung bằng 4 byte độ dài (big-endian) đứng trước.
 * Singleton dùng chung cho toàn app, được khởi động bởi ConnectionService.
 */
object ConnectionManager {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val connections = HashMap<String, PeerConnection>()
    private val connectionsMutex = Mutex()
    private var serverSocket: ServerSocket? = null
    private var serverJob: Job? = null

    private val _incomingMessages = MutableSharedFlow<WireMessage>(extraBufferCapacity = 64)
    val incomingMessages = _incomingMessages.asSharedFlow()

    private val _connectedPeerIds = MutableStateFlow<Set<String>>(emptySet())
    val connectedPeerIds: StateFlow<Set<String>> = _connectedPeerIds

    private class PeerConnection(
        val peerId: String,
        val socket: Socket,
        val out: DataOutputStream,
        val writeLock: Mutex = Mutex()
    )

    fun startServer(myId: String, myName: String) {
        if (serverJob != null) return
        serverJob = scope.launch {
            try {
                val server = ServerSocket(CONTROL_PORT)
                serverSocket = server
                Log.i(TAG, "Server đang lắng nghe tại cổng $CONTROL_PORT")
                while (true) {
                    val socket = server.accept()
                    scope.launch { handleIncomingSocket(socket, myId, myName) }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Server dừng: ${e.message}")
            }
        }
    }

    fun connectToPeer(peer: Peer, myId: String, myName: String) {
        scope.launch {
            if (connections.containsKey(peer.id)) return@launch
            try {
                val socket = Socket()
                socket.connect(java.net.InetSocketAddress(peer.host, peer.port), 4000)
                bindSocket(socket, myId, myName, expectedPeerId = peer.id)
            } catch (e: Exception) {
                Log.w(TAG, "Không kết nối được ${peer.name}: ${e.message}")
            }
        }
    }

    private suspend fun handleIncomingSocket(socket: Socket, myId: String, myName: String) {
        bindSocket(socket, myId, myName, expectedPeerId = null)
    }

    /** Gửi HELLO, đọc HELLO của phía kia, đăng ký kết nối rồi vào vòng lặp đọc message. */
    private suspend fun bindSocket(socket: Socket, myId: String, myName: String, expectedPeerId: String?) {
        try {
            val out = DataOutputStream(socket.getOutputStream())
            val input = DataInputStream(socket.getInputStream())

            val hello = WireMessage(type = MessageType.HELLO, senderId = myId, senderName = myName)
            writeFramed(out, hello.toJson())

            val firstRaw = readFramed(input) ?: return
            val firstMsg = WireMessage.fromJson(firstRaw)
            if (firstMsg.type != MessageType.HELLO) return
            val peerId = firstMsg.senderId

            val conn = PeerConnection(peerId, socket, out)
            connectionsMutex.withLock {
                connections[peerId]?.let { old ->
                    try { old.socket.close() } catch (_: Exception) {}
                }
                connections[peerId] = conn
            }
            _connectedPeerIds.update { it + peerId }
            Log.i(TAG, "Đã kết nối với ${firstMsg.senderName} ($peerId)")

            while (true) {
                val raw = readFramed(input) ?: break
                val msg = WireMessage.fromJson(raw)
                _incomingMessages.emit(msg)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Kết nối lỗi/đóng: ${e.message}")
        } finally {
            val peerId = connections.entries.firstOrNull { it.value.socket == socket }?.key
            if (peerId != null) {
                connectionsMutex.withLock { connections.remove(peerId) }
                _connectedPeerIds.update { it - peerId }
            }
            try { socket.close() } catch (_: Exception) {}
        }
    }

    suspend fun send(msg: WireMessage) {
        val targets = if (msg.targetId != null) {
            listOfNotNull(connections[msg.targetId])
        } else {
            connections.values.toList()
        }
        val json = msg.toJson()
        for (conn in targets) {
            try {
                conn.writeLock.withLock { writeFramed(conn.out, json) }
            } catch (e: Exception) {
                Log.w(TAG, "Gửi tới ${conn.peerId} thất bại: ${e.message}")
            }
        }
    }

    fun connectedIds(): Set<String> = connections.keys.toSet()

    fun stopAll() {
        try { serverSocket?.close() } catch (_: Exception) {}
        connections.values.forEach { try { it.socket.close() } catch (_: Exception) {} }
        connections.clear()
        _connectedPeerIds.value = emptySet()
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
