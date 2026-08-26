package com.localconnect.app.model

/** Một thiết bị khác trong nhóm 5 người, được tìm thấy qua NSD trên Wi-Fi hotspot 2.4GHz. */
data class Peer(
    val id: String,
    val name: String,
    val host: String,
    val port: Int,
    var isConnected: Boolean = false
)
