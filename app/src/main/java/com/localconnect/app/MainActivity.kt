package com.localconnect.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.ContextCompat.startForegroundService
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.localconnect.app.call.CallActivity
import com.localconnect.app.call.EXTRA_IS_INCOMING
import com.localconnect.app.call.EXTRA_IS_VIDEO
import com.localconnect.app.call.EXTRA_PEER_ID
import com.localconnect.app.call.EXTRA_PEER_NAME
import com.localconnect.app.call.EXTRA_REMOTE_SDP
import com.localconnect.app.data.GROUP_CONVERSATION_ID
import com.localconnect.app.model.Peer
import com.localconnect.app.net.ConnectionService
import com.localconnect.app.ui.ChatScreen
import com.localconnect.app.ui.PeerListScreen
import com.localconnect.app.ui.WifiDirectSetupScreen
import com.localconnect.app.ui.theme.LocalConnectTheme
import com.localconnect.app.util.Permissions

class MainActivity : ComponentActivity() {

    private val vm: AppViewModel by viewModels()
    private var pendingFileTarget: String? = null // null = gửi cho cả nhóm

    private val pickFileLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri?.let { vm.sendFile(contentResolver, it, pendingFileTarget) }
    }

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        startConnectionService()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Permissions.required().all {
                ContextCompat.checkSelfPermission(this, it) == android.content.pm.PackageManager.PERMISSION_GRANTED
            }) {
            startConnectionService()
        } else {
            permissionLauncher.launch(Permissions.required())
        }

        setContent {
            LocalConnectTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    AppNav()
                }
            }
        }
    }

    private fun startConnectionService() {
        val intent = Intent(this, ConnectionService::class.java)
        startForegroundService(this, intent)
    }

    @Composable
    private fun AppNav() {
        var screen by remember { mutableStateOf<Screen>(Screen.PeerList) }
        val peers by vm.peers.collectAsStateWithLifecycle()
        val incomingCall by vm.incomingCall.collectAsStateWithLifecycle()
        val connectStatus by vm.connectStatus.collectAsStateWithLifecycle()
        val wifiDirectState by vm.wifiDirectState.collectAsStateWithLifecycle()
        var showManualConnectDialog by remember { mutableStateOf(false) }
        var manualIp by remember { mutableStateOf("") }

        if (showManualConnectDialog) {
            AlertDialog(
                onDismissRequest = { showManualConnectDialog = false },
                title = { Text("Kết nối bằng IP") },
                text = {
                    Column {
                        Text("Nhập địa chỉ IP của máy kia (xem trong Cài đặt Wi-Fi trên máy đó), ví dụ 192.168.49.5")
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = manualIp,
                            onValueChange = { manualIp = it },
                            placeholder = { Text("192.168.49.5") },
                            singleLine = true
                        )
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        vm.connectManually(manualIp)
                        showManualConnectDialog = false
                    }) { Text("Kết nối") }
                },
                dismissButton = {
                    TextButton(onClick = { showManualConnectDialog = false }) { Text("Huỷ") }
                }
            )
        }

        connectStatus?.let { status ->
            AlertDialog(
                onDismissRequest = { vm.clearConnectStatus() },
                title = { Text("Kết nối thủ công") },
                text = { Text(status) },
                confirmButton = {
                    TextButton(onClick = { vm.clearConnectStatus() }) { Text("Đóng") }
                }
            )
        }

        incomingCall?.let { call ->
            AlertDialog(
                onDismissRequest = { vm.clearIncomingCall() },
                title = { Text(if (call.isVideo) "Cuộc gọi video đến" else "Cuộc gọi thoại đến") },
                text = { Text("${call.peerName} đang gọi cho bạn") },
                confirmButton = {
                    TextButton(onClick = {
                        launchCall(call.peerId, call.peerName, call.isVideo, incoming = true, remoteSdp = call.sdp)
                        vm.clearIncomingCall()
                    }) { Text("Trả lời") }
                },
                dismissButton = {
                    TextButton(onClick = { vm.clearIncomingCall() }) { Text("Từ chối") }
                }
            )
        }

        if (!wifiDirectState.groupFormed) {
            // Chưa vào nhóm nào -> hiện màn hình Tạo nhóm / Tìm & tham gia nhóm Wi-Fi Direct
            WifiDirectSetupScreen(
                state = wifiDirectState,
                onCreateGroup = { vm.createGroup() },
                onDiscover = { vm.discoverNearbyGroups() },
                onJoin = { device -> vm.joinGroup(device) }
            )
            return
        }

        when (val s = screen) {
            Screen.PeerList -> PeerListScreen(
                peers = peers,
                isHost = wifiDirectState.isGroupOwner,
                onOpenChat = { peer -> screen = Screen.DirectChat(peer) },
                onCall = { peer, isVideo -> launchCall(peer.id, peer.name, isVideo, incoming = false, remoteSdp = null) },
                onOpenGroupChat = { screen = Screen.GroupChat },
                onManualConnect = { manualIp = ""; showManualConnectDialog = true },
                onLeaveGroup = { vm.leaveGroup(); screen = Screen.PeerList }
            )
            Screen.GroupChat -> {
                val messages by vm.conversation(GROUP_CONVERSATION_ID).collectAsStateWithLifecycle(initialValue = emptyList())
                ChatScreen(
                    title = "Chat nhóm",
                    messages = messages,
                    onSend = { text -> vm.sendGroupText(text) },
                    onPickFile = { pendingFileTarget = null; pickFileLauncher.launch(arrayOf("*/*")) }
                )
                BackHandlerCompat { screen = Screen.PeerList }
            }
            is Screen.DirectChat -> {
                val peer = s.peer
                val messages by vm.conversation(peer.id).collectAsStateWithLifecycle(initialValue = emptyList())
                ChatScreen(
                    title = peer.name,
                    messages = messages,
                    onSend = { text -> vm.sendDirectText(peer.id, text) },
                    onPickFile = { pendingFileTarget = peer.id; pickFileLauncher.launch(arrayOf("*/*")) }
                )
                BackHandlerCompat { screen = Screen.PeerList }
            }
        }
    }

    private fun launchCall(peerId: String, peerName: String, isVideo: Boolean, incoming: Boolean, remoteSdp: String?) {
        startActivity(Intent(this, CallActivity::class.java).apply {
            putExtra(EXTRA_PEER_ID, peerId)
            putExtra(EXTRA_PEER_NAME, peerName)
            putExtra(EXTRA_IS_VIDEO, isVideo)
            putExtra(EXTRA_IS_INCOMING, incoming)
            putExtra(EXTRA_REMOTE_SDP, remoteSdp)
        })
    }
}

sealed class Screen {
    data object PeerList : Screen()
    data object GroupChat : Screen()
    data class DirectChat(val peer: Peer) : Screen()
}

@Composable
private fun BackHandlerCompat(onBack: () -> Unit) {
    androidx.activity.compose.BackHandler(onBack = onBack)
}
