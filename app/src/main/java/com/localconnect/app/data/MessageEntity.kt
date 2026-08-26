package com.localconnect.app.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/** Bản ghi lưu lịch sử chat (nhóm + riêng) trong Room, giữ lại khi app tắt/mở lại. */
@Entity(tableName = "messages")
data class MessageEntity(
    @PrimaryKey val id: String,
    val conversationId: String, // "GROUP" cho chat nhóm 5 người, hoặc peerId cho chat riêng
    val senderId: String,
    val senderName: String,
    val timestamp: Long,
    val text: String?,
    val isMine: Boolean,
    val fileName: String? = null,
    val filePath: String? = null,
    val fileSize: Long = 0L,
    val isFile: Boolean = false
)
