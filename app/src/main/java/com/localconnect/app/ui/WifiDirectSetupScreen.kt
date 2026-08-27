package com.localconnect.app.ui

import android.net.wifi.p2p.WifiP2pDevice
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.localconnect.app.net.WifiDirectState

@Composable
fun WifiDirectSetupScreen(
    state: WifiDirectState,
    onCreateGroup: () -> Unit,
    onDiscover: () -> Unit,
    onJoin: (WifiP2pDevice) -> Unit
) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(24.dp)
    ) {
        Text("LocalConnect", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(4.dp))
        Text(
            "Tạo hoặc tham gia nhóm Wi-Fi Direct (tối đa 5 người) — không cần Internet, " +
                "không cần bật Điểm phát Wi-Fi thủ công.",
            style = MaterialTheme.typography.bodyMedium
        )
        Spacer(Modifier.height(20.dp))

        if (!state.isWifiP2pEnabled) {
            Surface(color = MaterialTheme.colorScheme.errorContainer, shape = MaterialTheme.shapes.medium) {
                Text(
                    "⚠️ Wi-Fi đang tắt hoặc thiết bị không hỗ trợ Wi-Fi Direct. Hãy bật Wi-Fi rồi thử lại.",
                    modifier = Modifier.padding(12.dp)
                )
            }
            Spacer(Modifier.height(16.dp))
        }

        state.lastError?.let { err ->
            Surface(color = MaterialTheme.colorScheme.errorContainer, shape = MaterialTheme.shapes.medium) {
                Text(err, modifier = Modifier.padding(12.dp))
            }
            Spacer(Modifier.height(16.dp))
        }

        ElevatedCard(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("Bước 1 — Người đầu tiên", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Một người trong nhóm bấm nút dưới đây để làm chủ nhóm. Những người còn lại " +
                        "sẽ tìm và tham gia vào nhóm của người này.",
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(Modifier.height(12.dp))
                Button(onClick = onCreateGroup, modifier = Modifier.fillMaxWidth()) {
                    Text("Tạo nhóm (làm chủ nhóm)")
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        ElevatedCard(modifier = Modifier.fillMaxWidth().weight(1f)) {
            Column(Modifier.padding(16.dp).fillMaxSize()) {
                Text("Bước 2 — Những người còn lại", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Bấm \"Tìm nhóm gần đây\", đợi vài giây rồi chạm vào tên nhóm/máy của người đã " +
                        "tạo nhóm ở bước 1 để tham gia.",
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(Modifier.height(12.dp))
                OutlinedButton(onClick = onDiscover, modifier = Modifier.fillMaxWidth()) {
                    Text("Tìm nhóm gần đây")
                }
                Spacer(Modifier.height(8.dp))

                if (state.peers.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            "Chưa thấy nhóm nào.\nHãy chắc chắn người kia đã bấm \"Tạo nhóm\" và cả hai đều bật Wi-Fi.",
                            textAlign = TextAlign.Center,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                } else {
                    LazyColumn {
                        items(state.peers, key = { it.deviceAddress }) { device ->
                            ListItem(
                                headlineContent = { Text(device.deviceName) },
                                supportingContent = { Text(statusLabel(device.status)) },
                                modifier = Modifier.clickable { onJoin(device) }
                            )
                            HorizontalDivider()
                        }
                    }
                }
            }
        }
    }
}

private fun statusLabel(status: Int): String = when (status) {
    WifiP2pDevice.CONNECTED -> "Đã kết nối"
    WifiP2pDevice.INVITED -> "Đang mời..."
    WifiP2pDevice.FAILED -> "Lỗi kết nối"
    WifiP2pDevice.AVAILABLE -> "Chạm để tham gia"
    WifiP2pDevice.UNAVAILABLE -> "Không khả dụng"
    else -> "Không rõ trạng thái"
}
