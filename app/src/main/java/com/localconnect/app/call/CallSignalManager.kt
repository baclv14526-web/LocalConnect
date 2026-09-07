package com.localconnect.app.call

import com.localconnect.app.model.MessageType
import com.localconnect.app.model.WireMessage
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

data class IceCandidateModel(
    val sdpMid: String?,
    val sdpMLineIndex: Int,
    val sdp: String
)

/**
 * Quản lý hàng đợi tín hiệu WebRTC (đặc biệt là ICE candidates).
 * Đảm bảo các gói CALL_ICE gửi đến khi máy đang đổ chuông (chưa mở CallActivity)
 * không bị vứt bỏ, mà được lưu đệm và nạp ngay khi CallActivity khởi động.
 */
object CallSignalManager {

    private val _signalEvents = MutableSharedFlow<WireMessage>(extraBufferCapacity = 64)
    val signalEvents: SharedFlow<WireMessage> = _signalEvents.asSharedFlow()

    // Bộ đệm ICE candidates theo peerId
    private val iceCandidateBuffer = ConcurrentHashMap<String, CopyOnWriteArrayList<IceCandidateModel>>()

    fun onSignalReceived(msg: WireMessage) {
        when (msg.type) {
            MessageType.CALL_ICE -> {
                val candidateSdp = msg.iceCandidate
                if (!candidateSdp.isNullOrBlank()) {
                    val list = iceCandidateBuffer.getOrPut(msg.senderId) { CopyOnWriteArrayList() }
                    list.add(IceCandidateModel(msg.iceSdpMid, msg.iceSdpMLineIndex, candidateSdp))
                }
            }
            MessageType.CALL_END -> {
                clear(msg.senderId)
            }
            else -> {}
        }
        _signalEvents.tryEmit(msg)
    }

    /**
     * Lấy toàn bộ ICE candidates đã được lưu đệm cho peerId và xoá khỏi bộ nhớ tạm.
     */
    fun drainBufferedIce(peerId: String): List<IceCandidateModel> {
        val list = iceCandidateBuffer.remove(peerId) ?: return emptyList()
        return list.toList()
    }

    fun clear(peerId: String) {
        iceCandidateBuffer.remove(peerId)
    }
}
