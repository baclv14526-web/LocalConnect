package com.localconnect.app.util

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.app.NotificationCompat
import com.localconnect.app.App
import com.localconnect.app.MainActivity
import com.localconnect.app.R
import com.localconnect.app.call.IncomingCallActivity

/**
 * Quản lý toàn bộ âm thanh + rung + notification cho:
 *  - Tin nhắn đến: rung ngắn + âm thanh notification + badge
 *  - Cuộc gọi đến: nhạc chuông lặp + rung dài + full-screen notification
 */
object NotificationHelper {

    private const val NOTIF_ID_CALL    = 2001
    private const val NOTIF_ID_MESSAGE = 2002

    private var ringtonePlayer: MediaPlayer? = null
    private var vibrator: Vibrator? = null

    // ─── Tin nhắn đến ────────────────────────────────────────────────────────

    fun notifyNewMessage(context: Context, senderName: String, preview: String) {
        // Rung ngắn 150ms
        vibrateOnce(context, longArrayOf(0, 150))

        val openIntent = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notification = NotificationCompat.Builder(context, App.CHANNEL_MESSAGE)
            .setSmallIcon(android.R.drawable.ic_dialog_email)
            .setContentTitle(context.getString(R.string.notif_new_message_title, senderName))
            .setContentText(preview)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(openIntent)
            .setStyle(NotificationCompat.BigTextStyle().bigText(preview))
            .build()

        notificationManager(context).notify(NOTIF_ID_MESSAGE, notification)
    }

    fun cancelMessageNotification(context: Context) {
        notificationManager(context).cancel(NOTIF_ID_MESSAGE)
    }

    // ─── Cuộc gọi đến ────────────────────────────────────────────────────────

    fun notifyIncomingCall(
        context: Context,
        peerId: String,
        peerName: String,
        isVideo: Boolean,
        remoteSdp: String
    ) {
        startRinging(context)

        // Intent mở IncomingCallActivity (full-screen, ngay cả khi màn hình khoá)
        val fullScreenIntent = PendingIntent.getActivity(
            context, 1,
            IncomingCallActivity.newIntent(context, peerId, peerName, isVideo, remoteSdp),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        // Action: trả lời
        val answerIntent = PendingIntent.getActivity(
            context, 2,
            IncomingCallActivity.newIntent(context, peerId, peerName, isVideo, remoteSdp)
                .putExtra(IncomingCallActivity.EXTRA_AUTO_ANSWER, true),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        // Action: từ chối
        val declineIntent = PendingIntent.getBroadcast(
            context, 3,
            Intent(context, DeclineCallReceiver::class.java).apply {
                putExtra("peerId", peerId)
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val callType = if (isVideo) "video" else "thoại"
        val notification = NotificationCompat.Builder(context, App.CHANNEL_CALL)
            .setSmallIcon(if (isVideo) android.R.drawable.ic_menu_camera else android.R.drawable.ic_menu_call)
            .setContentTitle(context.getString(R.string.notif_incoming_call_title, callType))
            .setContentText(context.getString(R.string.notif_incoming_call_text, peerName))
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setFullScreenIntent(fullScreenIntent, true)   // hiển thị khi màn hình khoá
            .setContentIntent(fullScreenIntent)
            .addAction(android.R.drawable.ic_menu_call,
                context.getString(R.string.action_answer), answerIntent)
            .addAction(android.R.drawable.ic_delete,
                context.getString(R.string.action_decline), declineIntent)
            .setOngoing(true)       // không tự biến mất
            .setAutoCancel(false)
            .build()

        notificationManager(context).notify(NOTIF_ID_CALL, notification)
    }

    fun cancelCallNotification(context: Context) {
        stopRinging()
        notificationManager(context).cancel(NOTIF_ID_CALL)
    }

    // ─── Nhạc chuông + rung cuộc gọi ─────────────────────────────────────────

    private fun startRinging(context: Context) {
        stopRinging() // đảm bảo không phát đôi

        // Rung lặp lại: 500ms rung, 300ms nghỉ, lặp
        vibrateRepeat(context, longArrayOf(0, 500, 300), repeatIndex = 0)

        // Nhạc chuông (dùng ringtone mặc định của hệ thống, lặp lại)
        try {
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            val player = MediaPlayer().apply {
                setDataSource(context, uri)
                setAudioStreamType(AudioManager.STREAM_RING)
                isLooping = true
                prepare()
                start()
            }
            ringtonePlayer = player
        } catch (e: Exception) {
            // Fallback: nếu không mở được ringtone thì chỉ rung
        }
    }

    fun stopRinging() {
        try {
            ringtonePlayer?.let {
                if (it.isPlaying) it.stop()
                it.release()
            }
        } catch (_: Exception) {}
        ringtonePlayer = null

        try { vibrator?.cancel() } catch (_: Exception) {}
        vibrator = null
    }

    // ─── Vibrator helpers ─────────────────────────────────────────────────────

    private fun vibrateOnce(context: Context, pattern: LongArray) {
        val v = getVibrator(context) ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            v.vibrate(VibrationEffect.createWaveform(pattern, -1))
        } else {
            @Suppress("DEPRECATION")
            v.vibrate(pattern, -1)
        }
    }

    private fun vibrateRepeat(context: Context, pattern: LongArray, repeatIndex: Int) {
        val v = getVibrator(context) ?: return
        vibrator = v
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            v.vibrate(VibrationEffect.createWaveform(pattern, repeatIndex))
        } else {
            @Suppress("DEPRECATION")
            v.vibrate(pattern, repeatIndex)
        }
    }

    @Suppress("DEPRECATION")
    private fun getVibrator(context: Context): Vibrator? = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        } else {
            context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
    } catch (_: Exception) { null }

    private fun notificationManager(context: Context) =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
}
