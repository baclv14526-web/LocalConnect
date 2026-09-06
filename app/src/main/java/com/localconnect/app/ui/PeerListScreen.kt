package com.localconnect.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.automirrored.filled.Message
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
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
                title = { Text(if (isHost) "LocalConnect · Chủ nhóm" else "LocalConnect") },
                actions = {
                    IconButton(onClick = onLeaveGroup) {
                        Icon(Icons.AutoMirrored.Filled.ExitToApp, contentDescription = "Rời nhóm")
                    }
                }
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onManualConnect,
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text("Kết nối bằng IP") }
            )
        }
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // ── Chat nhóm ──────────────────────────────────────────────
            ListItem(
                headlineContent = { Text("Chat nhóm") },
                supportingContent = {
                    Text(
                        if (peers.isEmpty()) "Chưa có ai trong nhóm"
                        else "${peers.size} người đang kết nối"
                    )
                },
                leadingContent = {
                    Icon(
                        Icons.Filled.Groups,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                },
                trailingContent = {
                    FilledTonalButton(onClick = onOpenGroupChat) {
                        Icon(
                            Icons.AutoMirrored.Filled.Message,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text("Nhắn tin nhóm")
                    }
                },
                modifier = Modifier.clickable { onOpenGroupChat() }
            )
            HorizontalDivider(thickness = 2.dp)

            // ── Danh sách người ────────────────────────────────────────
            if (peers.isEmpty()) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "Chưa có ai kết nối vào nhóm.\n" +
                            "Mời người khác mở app → \"Tìm nhóm gần đây\" → chọn máy của bạn.",
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            } else {
                LazyColumn(Modifier.weight(1f)) {
                    items(peers, key = { it.id }) { peer ->
                        PeerRow(
                            peer = peer,
                            onChat = { onOpenChat(peer) },
                            onAudioCall = { onCall(peer, false) },
                            onVideoCall = { onCall(peer, true) }
                        )
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}

@Composable
private fun PeerRow(
    peer: Peer,
    onChat: () -> Unit,
    onAudioCall: () -> Unit,
    onVideoCall: () -> Unit
) {
    Column(
        Modifier
            .fillMaxWidth()
            .clickable { onChat() }
            .padding(horizontal = 16.dp, vertical = 10.dp)
    ) {
        // Tên + IP
        Row(verticalAlignment = Alignment.CenterVertically) {
            // Avatar chữ cái đầu
            Surface(
                shape = MaterialTheme.shapes.small,
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.size(40.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        peer.name.firstOrNull()?.uppercaseChar()?.toString() ?: "?",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(peer.name, style = MaterialTheme.typography.bodyLarge)
                Text(
                    "Đã kết nối · ${peer.host}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline
                )
            }
        }

        Spacer(Modifier.height(8.dp))

        // Hàng nút hành động
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Nút Nhắn tin (nổi bật nhất)
            FilledTonalButton(
                onClick = onChat,
                modifier = Modifier.weight(1f)
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.Message,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(Modifier.width(4.dp))
                Text("Nhắn tin")
            }

            // Nút Gọi thoại
            OutlinedButton(
                onClick = onAudioCall,
                modifier = Modifier.weight(1f)
            ) {
                Icon(
                    Icons.Filled.Call,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(Modifier.width(4.dp))
                Text("Thoại")
            }

            // Nút Gọi video
            OutlinedButton(
                onClick = onVideoCall,
                modifier = Modifier.weight(1f)
            ) {
                Icon(
                    Icons.Filled.Videocam,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(Modifier.width(4.dp))
                Text("Video")
            }
        }
    }
}
