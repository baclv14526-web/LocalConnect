package com.localconnect.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import com.localconnect.app.net.DeviceIdentity
import com.localconnect.app.net.WifiDirectManager

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        DeviceIdentity.init(this)
        WifiDirectManager.init(this)
        createNotificationChannels()
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(NotificationManager::class.java)

        // Channel 1: foreground service (im lặng)
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_SERVICE,
                getString(R.string.notif_channel_service),
                NotificationManager.IMPORTANCE_LOW
            ).apply { setShowBadge(false) }
        )

        // Channel 2: cuộc gọi đến — HIGH + nhạc chuông + rung
        val ringtoneUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
        val audioAttr = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_CALL,
                getString(R.string.notif_channel_call),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                setSound(ringtoneUri, audioAttr)
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 500, 300, 500, 300, 500)
                enableLights(true)
                setShowBadge(true)
            }
        )

        // Channel 3: tin nhắn đến — DEFAULT + rung nhẹ
        val msgUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_MESSAGE,
                getString(R.string.notif_channel_message),
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                setSound(msgUri, AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                    .build())
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 150)
                setShowBadge(true)
            }
        )
    }

    companion object {
        const val CHANNEL_SERVICE = "service_channel"
        const val CHANNEL_CALL    = "call_channel"
        const val CHANNEL_MESSAGE = "message_channel"
    }
}
