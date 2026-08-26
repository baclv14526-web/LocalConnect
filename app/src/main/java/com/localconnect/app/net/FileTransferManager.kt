package com.localconnect.app.net

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import com.localconnect.app.model.MessageType
import com.localconnect.app.model.WireMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

private const val TAG = "FileTransferManager"

/**
 * Gửi/nhận file qua một kênh TCP RIÊNG (không dùng chung với kênh chat JSON), để dữ liệu nhị phân
 * không lẫn với message điều khiển. Luồng:
 *  1) Bên gửi mở ServerSocket tạm (port ngẫu nhiên), gửi WireMessage FILE_OFFER kèm host:port qua
 *     ConnectionManager cho (các) người nhận.
 *  2) Bên nhận kết nối tới host:port đó, tải đúng fileSize byte, lưu vào bộ nhớ dùng chung.
 */
class FileTransferManager(private val context: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    interface Listener {
        fun onSendProgress(fileName: String, sent: Long, total: Long) {}
        fun onSendComplete(fileName: String) {}
        fun onReceiveProgress(fileName: String, received: Long, total: Long) {}
        fun onReceiveComplete(fileName: String, savedPath: String?) {}
        fun onError(fileName: String, error: String) {}
    }

    /** Gửi file cho một người (targetId) hoặc cả nhóm (targetId = null). */
    fun offerFile(
        inputStream: InputStream,
        fileName: String,
        fileSize: Long,
        mime: String?,
        myId: String,
        myName: String,
        targetId: String?,
        listener: Listener
    ) {
        scope.launch {
            var server: ServerSocket? = null
            try {
                server = ServerSocket(0) // OS tự chọn cổng trống
                val port = server.localPort

                ConnectionManager.send(
                    WireMessage(
                        type = MessageType.FILE_OFFER,
                        senderId = myId,
                        senderName = myName,
                        targetId = targetId,
                        fileName = fileName,
                        fileSize = fileSize,
                        fileMime = mime,
                        filePort = port
                    )
                )

                // Hotspot 5 người: chờ tối đa 4 kết nối tới lấy file (broadcast) hoặc 1 (riêng tư)
                val expected = if (targetId != null) 1 else maxOf(1, ConnectionManager.connectedIds().size)
                val buffers = inputStream.readBytes() // file P2P nội bộ, thường nhỏ/vừa -> đọc hẳn vào RAM cho đơn giản & ổn định
                server.soTimeout = 30_000
                repeat(expected) {
                    try {
                        val client = server.accept()
                        sendBytesTo(client, buffers, fileName, listener)
                    } catch (e: Exception) {
                        Log.w(TAG, "Không có người nhận kết nối kịp: ${e.message}")
                    }
                }
                listener.onSendComplete(fileName)
            } catch (e: Exception) {
                listener.onError(fileName, e.message ?: "Lỗi gửi file")
            } finally {
                try { server?.close() } catch (_: Exception) {}
            }
        }
    }

    private fun sendBytesTo(socket: Socket, data: ByteArray, fileName: String, listener: Listener) {
        socket.use {
            val out: OutputStream = it.getOutputStream()
            var sent = 0L
            val chunk = 64 * 1024
            var offset = 0
            while (offset < data.size) {
                val len = minOf(chunk, data.size - offset)
                out.write(data, offset, len)
                offset += len
                sent += len
                listener.onSendProgress(fileName, sent, data.size.toLong())
            }
            out.flush()
        }
    }

    /** Gọi khi nhận được WireMessage FILE_OFFER từ ConnectionManager. */
    fun receiveFile(offer: WireMessage, listener: Listener) {
        scope.launch {
            try {
                val socket = Socket()
                socket.connect(InetSocketAddress(peerHostOf(offer.senderId), offer.filePort), 5000)
                socket.use { s ->
                    val input = s.getInputStream()
                    val outBytes = java.io.ByteArrayOutputStream()
                    val buffer = ByteArray(64 * 1024)
                    var received = 0L
                    while (received < offer.fileSize) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        outBytes.write(buffer, 0, n)
                        received += n
                        listener.onReceiveProgress(offer.fileName ?: "file", received, offer.fileSize)
                    }
                    val savedPath = saveToSharedStorage(offer.fileName ?: "localconnect_file", offer.fileMime, outBytes.toByteArray())
                    listener.onReceiveComplete(offer.fileName ?: "file", savedPath)
                }
            } catch (e: Exception) {
                listener.onError(offer.fileName ?: "file", e.message ?: "Lỗi nhận file")
            }
        }
    }

    /** Host IP của peer đang gửi được ConnectionManager theo dõi qua NSD; ConnectionService cache lại. */
    private fun peerHostOf(senderId: String): String =
        PeerHostRegistry.hostFor(senderId) ?: throw IllegalStateException("Không rõ địa chỉ IP của người gửi")

    private fun saveToSharedStorage(fileName: String, mime: String?, bytes: ByteArray): String? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val resolver = context.contentResolver
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                    put(MediaStore.Downloads.MIME_TYPE, mime ?: "application/octet-stream")
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/LocalConnect")
                }
                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return null
                resolver.openOutputStream(uri)?.use { it.write(bytes) }
                uri.toString()
            } else {
                val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "LocalConnect")
                if (!dir.exists()) dir.mkdirs()
                val file = File(dir, fileName)
                file.writeBytes(bytes)
                file.absolutePath
            }
        } catch (e: Exception) {
            Log.e(TAG, "Lưu file thất bại: ${e.message}")
            null
        }
    }
}

/** Bảng tra cứu IP hiện tại của từng peer (cập nhật bởi NsdDiscoveryManager) để mở kết nối file/ICE. */
object PeerHostRegistry {
    private val map = HashMap<String, String>()
    @Synchronized fun update(peerId: String, host: String) { map[peerId] = host }
    @Synchronized fun hostFor(peerId: String): String? = map[peerId]
}
