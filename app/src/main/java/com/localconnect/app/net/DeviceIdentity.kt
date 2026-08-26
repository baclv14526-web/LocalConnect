package com.localconnect.app.net

import android.content.Context
import android.os.Build
import java.util.UUID

/** ID + tên thiết bị ổn định qua các lần mở app, lưu trong SharedPreferences. */
object DeviceIdentity {
    private const val PREFS = "device_identity"
    private lateinit var prefs: android.content.SharedPreferences

    lateinit var myId: String
        private set
    var myName: String = Build.MODEL
        private set

    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        myId = prefs.getString("id", null) ?: UUID.randomUUID().toString().also {
            prefs.edit().putString("id", it).apply()
        }
        myName = prefs.getString("name", null) ?: Build.MODEL.also {
            prefs.edit().putString("name", it).apply()
        }
    }

    fun setName(name: String) {
        myName = name
        prefs.edit().putString("name", name).apply()
    }
}
