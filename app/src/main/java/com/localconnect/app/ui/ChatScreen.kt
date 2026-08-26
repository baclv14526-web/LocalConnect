package com.localconnect.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.localconnect.app.data.MessageEntity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun ChatScreen(
    title: String,
    messages: List<MessageEntity>,
    onSend: (String) -> Unit,
    onPickFile: () -> Unit
) {
    var input by remember { mutableStateOf("") }

    Column(Modifier.fillMaxSize()) {
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(16.dp)
        )
        HorizontalDivider()
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp),
            reverseLayout = false
        ) {
            items(messages, key = { it.id }) { msg -> MessageBubble(msg) }
        }
        HorizontalDivider()
        Row(
            Modifier.fillMaxWidth().padding(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onPickFile) {
                Icon(Icons.Filled.AttachFile, contentDescription = "Gửi file")
            }
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("Nhập tin nhắn...") }
            )
            IconButton(onClick = {
                if (input.isNotBlank()) {
                    onSend(input.trim())
                    input = ""
                }
            }) {
                Icon(Icons.Filled.Send, contentDescription = "Gửi")
            }
        }
    }
}

@Composable
private fun MessageBubble(msg: MessageEntity) {
    val alignment = if (msg.isMine) Alignment.End else Alignment.Start
    val bubbleColor = if (msg.isMine) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
    val timeFmt = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }

    Column(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalAlignment = alignment
    ) {
        if (!msg.isMine) {
            Text(msg.senderName, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
        }
        Surface(
            color = bubbleColor,
            shape = MaterialTheme.shapes.medium
        ) {
            Column(Modifier.padding(10.dp)) {
                if (msg.isFile) {
                    Text("📎 ${msg.fileName ?: "Tệp tin"} (${(msg.fileSize / 1024).coerceAtLeast(1)} KB)")
                } else {
                    Text(msg.text ?: "")
                }
                Text(
                    timeFmt.format(Date(msg.timestamp)),
                    style = MaterialTheme.typography.labelSmall
                )
            }
        }
    }
}
