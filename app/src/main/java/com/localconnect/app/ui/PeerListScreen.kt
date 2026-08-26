package com.localconnect.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.localconnect.app.model.Peer

@Composable
fun PeerListScreen(
    peers: List<Peer>,
    onOpenChat: (Peer) -> Unit,
    onCall: (Peer, Boolean) -> Unit,
    onOpenGroupChat: () -> Unit
) {
    Column(Modifier.fillMaxSize()) {
        ListItem(
            headlineContent = { Text("Chat nhóm (tối đa 5 người)") },
            supportingContent = { Text("${peers.count { it.isConnected }} người đang trực tuyến") },
            modifier = Modifier.clickable { onOpenGroupChat() }
        )
        HorizontalDivider()
        if (peers.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Đang tìm thiết bị khác trong mạng Wi-Fi...\nHãy chắc chắn mọi người cùng kết nối vào 1 điểm phát Wi-Fi.", textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            }
        } else {
            LazyColumn {
                items(peers, key = { it.id }) { peer ->
                    ListItem(
                        headlineContent = { Text(peer.name) },
                        supportingContent = { Text(if (peer.isConnected) "Đã kết nối · ${peer.host}" else "Đang kết nối...") },
                        modifier = Modifier.clickable { onOpenChat(peer) },
                        trailingContent = {
                            Row {
                                IconButton(onClick = { onCall(peer, false) }, enabled = peer.isConnected) {
                                    Icon(Icons.Filled.Call, contentDescription = "Gọi thoại")
                                }
                                IconButton(onClick = { onCall(peer, true) }, enabled = peer.isConnected) {
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
