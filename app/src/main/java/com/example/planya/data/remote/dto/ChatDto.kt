package com.example.planya.data.remote.dto

// Los nombres corresponden al JSON de backend/chat.py.
data class ChatMessageDto(
    val id: String? = null,
    val planId: String? = null,
    val senderId: String? = null,
    val senderName: String? = null,
    val text: String? = null,
    val createdAt: Long? = null,
    val cursor: String? = null,
    val expiresAt: Long? = null,
    val clientMessageId: String? = null
)

data class ChatPageDto(
    val items: List<ChatMessageDto>? = null,
    val nextKey: String? = null,
    val afterKey: String? = null,
    val chatExpiresAt: Long? = null,
    val serverTime: Long? = null,
    val planTitle: String? = null,
    val isHost: Boolean? = null,
    val realtime: Boolean? = null,
    val hostId: String? = null
)

data class ChatPageResponseDto(val success: Boolean? = null, val data: ChatPageDto? = null, val error: String? = null)

data class ChatMessageResponseDto(val success: Boolean? = null, val data: ChatMessageDto? = null, val duplicate: Boolean? = null)

data class SendChatMessageDto(val text: String, val clientMessageId: String)
