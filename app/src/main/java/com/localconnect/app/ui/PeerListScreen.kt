package com.localconnect.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.ExitToApp
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.localconnect.app.model.Peer

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PeerListScreen(
    peers: List<Peer>,
    isHost: Boolean,
    onOpenChat: (Peer) -> Unit,
    onCall: (Peer, Boolean) -> Unit,
    onOpenGroupChat: () -> Unit,
    onManualConnect: () -> Unit,
    onLeaveGroup: () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (isHost) "Nhóm của bạn (chủ nhóm)" else "Nhóm Wi-Fi Direct") },
                actions = {
                    IconButton(onClick = onLeaveGroup) {
                        Icon(Icons.Filled.ExitToApp, contentDescription = "Rời nhóm")
                    }
                }
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(onClick = onManualConnect, icon = {
                Icon(Icons.Filled.Add, contentDescription = null)
            }, text = { Text("Kết nối bằng IP") })
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            ListItem(
                headlineContent = { Text("Chat nhóm (tối đa 5 người)") },
                supportingContent = { Text("${peers.size} người đang trực tuyến") },
                modifier = Modifier.clickable { onOpenGroupChat() }
            )
            HorizontalDivider()
            if (peers.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        "Đang chờ người khác tham gia nhóm...\n" +
                            "Nếu chờ lâu không thấy, bấm \"Kết nối bằng IP\" bên dưới để nhập IP thủ công.",
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        modifier = Modifier.padding(24.dp)
                    )
                }
            } else {
                LazyColumn {
                    items(peers, key = { it.id }) { peer ->
                        ListItem(
                            headlineContent = { Text(peer.name) },
                            supportingContent = { Text("Đã kết nối · ${peer.host}") },
                            modifier = Modifier.clickable { onOpenChat(peer) },
                            trailingContent = {
                                Row {
                                    IconButton(onClick = { onCall(peer, false) }) {
                                        Icon(Icons.Filled.Call, contentDescription = "Gọi thoại")
                                    }
                                    IconButton(onClick = { onCall(peer, true) }) {
                                        Icon(Icons.Filled.Videocam, contentDescription = "Gọi video")
                                    }
                                }
                            }
                        )
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}
