package com.example.planya.data.mapper

import com.example.planya.data.remote.dto.ChatMessageDto
import com.example.planya.data.remote.dto.ChatPageDto
import com.example.planya.domain.model.ChatMessage
import com.example.planya.domain.model.ChatPage

/** Devuelve null si al mensaje le falta un dato obligatorio; no se rellenan datos inventados. */
fun ChatMessageDto.toDomain(): ChatMessage? {
    return ChatMessage(
        id = id ?: return null,
        planId = planId ?: return null,
        senderId = senderId ?: return null,
        senderName = senderName?.takeIf { it.isNotBlank() } ?: return null,
        text = text ?: return null,
        createdAtMillis = createdAt ?: return null,
        cursor = cursor ?: return null,
        clientMessageId = clientMessageId
    )
}

fun ChatPageDto.toDomain(): ChatPage = ChatPage(
    messages = items.orEmpty().mapNotNull { it.toDomain() }.sortedBy { it.cursor },
    olderKey = nextKey,
    newerKey = afterKey,
    chatExpiresAt = checkNotNull(chatExpiresAt) { "El servidor no indicó el cierre del chat." },
    serverTimeSeconds = checkNotNull(serverTime) { "El servidor no indicó su hora." },
    planTitle = planTitle,
    isHost = isHost == true,
    hostId = hostId
)
