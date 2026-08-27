package com.localconnect.app.model

enum class MessageType {
    HELLO,
    TEXT,
    FILE_OFFER,
    CALL_OFFER,
    CALL_ANSWER,
    CALL_ICE,
    CALL_END,
    PRESENCE_BYE,
    PEER_LIST   // GO (Group Owner) broadcast danh sách id/tên/IP của mọi người trong nhóm
}
