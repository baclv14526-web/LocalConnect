package com.localconnect.app.data

import android.content.Context
import com.localconnect.app.model.WireMessage
import kotlinx.coroutines.flow.Flow

const val GROUP_CONVERSATION_ID = "GROUP"

/** Cầu nối giữa tầng mạng (WireMessage) và Room, dùng chung cho chat nhóm và chat riêng. */
class ChatRepository(context: Context) {
    private val dao = AppDatabase.get(context).messageDao()

    fun observeConversation(conversationId: String): Flow<List<MessageEntity>> =
        dao.observeConversation(conversationId)

    suspend fun saveIncomingText(msg: WireMessage) {
        val conversationId = msg.targetId?.let { msg.senderId } ?: GROUP_CONVERSATION_ID
        dao.insert(
            MessageEntity(
                id = msg.id,
                conversationId = conversationId,
                senderId = msg.senderId,
                senderName = msg.senderName,
                timestamp = msg.timestamp,
                text = msg.text,
                isMine = false
            )
        )
    }

    suspend fun saveOutgoingText(conversationId: String, myId: String, myName: String, text: String): WireMessage {
        val wire = WireMessage(
            type = com.localconnect.app.model.MessageType.TEXT,
            senderId = myId,
            senderName = myName,
            targetId = if (conversationId == GROUP_CONVERSATION_ID) null else conversationId,
            text = text
        )
        dao.insert(
            MessageEntity(
                id = wire.id,
                conversationId = conversationId,
                senderId = myId,
                senderName = myName,
                timestamp = wire.timestamp,
                text = text,
                isMine = true
            )
        )
        return wire
    }

    suspend fun saveFileRecord(
        conversationId: String,
        senderId: String,
        senderName: String,
        fileName: String,
        filePath: String?,
        fileSize: Long,
        isMine: Boolean
    ) {
        dao.insert(
            MessageEntity(
                id = java.util.UUID.randomUUID().toString(),
                conversationId = conversationId,
                senderId = senderId,
                senderName = senderName,
                timestamp = System.currentTimeMillis(),
                text = null,
                isMine = isMine,
                fileName = fileName,
                filePath = filePath,
                fileSize = fileSize,
                isFile = true
            )
        )
    }
}
