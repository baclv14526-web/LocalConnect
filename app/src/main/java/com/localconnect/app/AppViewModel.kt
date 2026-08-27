package com.localconnect.app

import android.app.Application
import android.net.Uri
import android.net.wifi.p2p.WifiP2pDevice
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.localconnect.app.data.ChatRepository
import com.localconnect.app.data.GROUP_CONVERSATION_ID
import com.localconnect.app.data.MessageEntity
import com.localconnect.app.model.MessageType
import com.localconnect.app.model.Peer
import com.localconnect.app.net.CONTROL_PORT
import com.localconnect.app.net.ConnectionManager
import com.localconnect.app.net.DeviceIdentity
import com.localconnect.app.net.FileTransferManager
import com.localconnect.app.net.WifiDirectManager
import com.localconnect.app.net.WifiDirectState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

data class IncomingCall(val peerId: String, val peerName: String, val isVideo: Boolean, val sdp: String)

class AppViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = ChatRepository(app)
    private val fileManager = FileTransferManager(app)

    val myId: String get() = DeviceIdentity.myId
    val myName: String get() = DeviceIdentity.myName

    val wifiDirectState: StateFlow<WifiDirectState> = WifiDirectManager.state

    /** Danh sách người đang có kết nối TCP thật sự (đã hình thành qua Wi-Fi Direct + roster). */
    val peers: StateFlow<List<Peer>> = ConnectionManager.livePeers
        .map { map ->
            map.values.map { Peer(it.id, it.name, it.host, CONTROL_PORT, isConnected = true) }
                .sortedBy { it.name }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _incomingCall = MutableStateFlow<IncomingCall?>(null)
    val incomingCall: StateFlow<IncomingCall?> = _incomingCall

    fun conversation(id: String): Flow<List<MessageEntity>> = repo.observeConversation(id)

    init {
        viewModelScope.launch {
            ConnectionManager.incomingMessages.collect { msg ->
                when (msg.type) {
                    MessageType.TEXT -> repo.saveIncomingText(msg)
                    MessageType.FILE_OFFER -> {
                        val convId = msg.targetId?.let { msg.senderId } ?: GROUP_CONVERSATION_ID
                        fileManager.receiveFile(msg, object : FileTransferManager.Listener {
                            override fun onReceiveComplete(fileName: String, savedPath: String?) {
                                viewModelScope.launch {
                                    repo.saveFileRecord(convId, msg.senderId, msg.senderName, fileName, savedPath, msg.fileSize, isMine = false)
                                }
                            }
                        })
                    }
                    MessageType.CALL_OFFER -> {
                        _incomingCall.value = IncomingCall(msg.senderId, msg.senderName, msg.isVideoCall, msg.sdp ?: "")
                    }
                    else -> {}
                }
            }
        }
    }

    fun clearIncomingCall() { _incomingCall.value = null }

    // ---------- Wi-Fi Direct: tạo / tham gia / rời nhóm ----------

    fun createGroup() = WifiDirectManager.createGroup()

    fun discoverNearbyGroups() = WifiDirectManager.discoverPeers()

    fun joinGroup(device: WifiP2pDevice) = WifiDirectManager.connect(device)

    fun leaveGroup() {
        WifiDirectManager.removeGroup()
        ConnectionManager.disconnectAllPeers()
    }

    fun clearWifiDirectError() = WifiDirectManager.clearError()

    // ---------- Kết nối thủ công bằng IP (dự phòng khi cần) ----------

    private val _connectStatus = MutableStateFlow<String?>(null)
    val connectStatus: StateFlow<String?> = _connectStatus

    fun connectManually(ip: String) {
        val host = ip.trim()
        if (host.isEmpty()) return
        _connectStatus.value = "Đang thử kết nối tới $host..."
        val before = ConnectionManager.livePeers.value.size
        ConnectionManager.connectToPeer(Peer(id = "manual-$host", name = host, host = host, port = CONTROL_PORT), myId, myName)
        viewModelScope.launch {
            val result = withTimeoutOrNull(6000) {
                ConnectionManager.livePeers.first { it.size > before }
            }
            _connectStatus.value = if (result != null) {
                "Đã kết nối thành công tới $host ✅"
            } else {
                "Không kết nối được tới $host sau 6 giây."
            }
        }
    }

    fun clearConnectStatus() { _connectStatus.value = null }

    // ---------- Chat / gửi file ----------

    fun sendGroupText(text: String) = viewModelScope.launch {
        val wire = repo.saveOutgoingText(GROUP_CONVERSATION_ID, myId, myName, text)
        ConnectionManager.send(wire)
    }

    fun sendDirectText(peerId: String, text: String) = viewModelScope.launch {
        val wire = repo.saveOutgoingText(peerId, myId, myName, text)
        ConnectionManager.send(wire)
    }

    fun sendFile(contentResolver: android.content.ContentResolver, uri: Uri, targetId: String?) {
        val name = queryFileName(contentResolver, uri) ?: "file"
        val size = queryFileSize(contentResolver, uri)
        val mime = contentResolver.getType(uri)
        val input = contentResolver.openInputStream(uri) ?: return
        val convId = targetId ?: GROUP_CONVERSATION_ID
        fileManager.offerFile(input, name, size, mime, myId, myName, targetId, object : FileTransferManager.Listener {
            override fun onSendComplete(fileName: String) {
                viewModelScope.launch {
                    repo.saveFileRecord(convId, myId, myName, fileName, null, size, isMine = true)
                }
            }
        })
    }

    private fun queryFileName(cr: android.content.ContentResolver, uri: Uri): String? {
        cr.query(uri, null, null, null, null)?.use { c ->
            val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && c.moveToFirst()) return c.getString(idx)
        }
        return uri.lastPathSegment
    }

    private fun queryFileSize(cr: android.content.ContentResolver, uri: Uri): Long {
        cr.query(uri, null, null, null, null)?.use { c ->
            val idx = c.getColumnIndex(android.provider.OpenableColumns.SIZE)
            if (idx >= 0 && c.moveToFirst()) return c.getLong(idx)
        }
        return 0L
    }
}
