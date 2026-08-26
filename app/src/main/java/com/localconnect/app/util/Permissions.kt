package com.localconnect.app.util

import android.Manifest
import android.os.Build

/** Danh sách quyền runtime cần xin khi mở app lần đầu. */
object Permissions {
    fun required(): Array<String> {
        val list = mutableListOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO,
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            list += Manifest.permission.POST_NOTIFICATIONS
            list += Manifest.permission.READ_MEDIA_IMAGES
            list += Manifest.permission.READ_MEDIA_VIDEO
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            list += Manifest.permission.READ_EXTERNAL_STORAGE
        } else {
            list += Manifest.permission.READ_EXTERNAL_STORAGE
            list += Manifest.permission.WRITE_EXTERNAL_STORAGE
        }
        return list.toTypedArray()
    }
}
