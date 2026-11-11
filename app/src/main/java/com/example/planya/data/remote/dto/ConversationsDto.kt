package com.example.planya.data.remote.dto

data class ConversationDto(
    val planId: String? = null, val title: String? = null,
    val hostId: String? = null, val hostName: String? = null, val category: String? = null,
    val planExpiresAt: Long? = null, val chatExpiresAt: Long? = null, val isHost: Boolean? = null
)
data class ConversationsPageDto(
    val userId: String? = null, val items: List<ConversationDto>? = null,
    val nextKey: String? = null, val serverTime: Long? = null
)
data class ConversationsResponseDto(val success: Boolean? = null, val data: ConversationsPageDto? = null)
