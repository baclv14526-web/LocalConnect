package com.localconnect.app.model

import org.json.JSONObject
import java.util.UUID

data class WireMessage(
    val id: String = UUID.randomUUID().toString(),
    val type: MessageType,
    val senderId: String,
    val senderName: String,
    val targetId: String? = null,
    val timestamp: Long = System.currentTimeMillis(),
    val text: String? = null,
    val isVideoCall: Boolean = false,
    val fileName: String? = null,
    val fileSize: Long = 0L,
    val fileMime: String? = null,
    val filePort: Int = 0,
    val sdp: String? = null,
    val sdpType: String? = null,
    val iceSdpMid: String? = null,
    val iceSdpMLineIndex: Int = 0,
    val iceCandidate: String? = null
) {
    fun toJson(): String {
        val o = JSONObject()
        o.put("id", id)
        o.put("type", type.name)
        o.put("senderId", senderId)
        o.put("senderName", senderName)
        o.put("targetId", targetId ?: JSONObject.NULL)
        o.put("timestamp", timestamp)
        o.put("text", text ?: JSONObject.NULL)
        o.put("isVideoCall", isVideoCall)
        o.put("fileName", fileName ?: JSONObject.NULL)
        o.put("fileSize", fileSize)
        o.put("fileMime", fileMime ?: JSONObject.NULL)
        o.put("filePort", filePort)
        o.put("sdp", sdp ?: JSONObject.NULL)
        o.put("sdpType", sdpType ?: JSONObject.NULL)
        o.put("iceSdpMid", iceSdpMid ?: JSONObject.NULL)
        o.put("iceSdpMLineIndex", iceSdpMLineIndex)
        o.put("iceCandidate", iceCandidate ?: JSONObject.NULL)
        return o.toString()
    }

    companion object {
        fun fromJson(raw: String): WireMessage {
            val o = JSONObject(raw)
            fun str(k: String): String? = if (o.isNull(k)) null else o.getString(k)
            return WireMessage(
                id = o.getString("id"),
                type = MessageType.valueOf(o.getString("type")),
                senderId = o.getString("senderId"),
                senderName = o.getString("senderName"),
                targetId = str("targetId"),
                timestamp = o.getLong("timestamp"),
                text = str("text"),
                isVideoCall = o.optBoolean("isVideoCall", false),
                fileName = str("fileName"),
                fileSize = o.optLong("fileSize", 0L),
                fileMime = str("fileMime"),
                filePort = o.optInt("filePort", 0),
                sdp = str("sdp"),
                sdpType = str("sdpType"),
                iceSdpMid = str("iceSdpMid"),
                iceSdpMLineIndex = o.optInt("iceSdpMLineIndex", 0),
                iceCandidate = str("iceCandidate")
            )
        }
    }
}
