package com.example.planya.data.mapper

import com.example.planya.data.remote.dto.ConversationDto
import com.example.planya.domain.model.Conversation
import com.example.planya.domain.model.PlanCategory
import java.util.UUID

fun ConversationDto.toDomain(): Conversation? {
    val id = planId?.takeIf { runCatching { UUID.fromString(it).toString() == it }.getOrDefault(false) } ?: return null
    val heading = title?.takeIf { it.isNotBlank() } ?: return null
    val ends = planExpiresAt?.takeIf { it >= 0 } ?: return null
    val closes = chatExpiresAt?.takeIf { it > ends } ?: return null
    return Conversation(id, heading, hostId, hostName?.takeIf { it.isNotBlank() },
        PlanCategory.entries.firstOrNull { it.name == category }, ends, closes, isHost == true)
}
