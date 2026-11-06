package com.example.planya.data.mapper

import com.example.planya.data.remote.dto.PlanDto
import com.example.planya.domain.model.Plan
import com.example.planya.domain.model.PlanCategory
import com.example.planya.domain.model.GeoPoint
import com.example.planya.domain.model.PlanPhoto

fun PlanDto.toDomain(): Plan {
    val planId = requireNotNull(id?.takeIf { it.isNotBlank() }) { "Plan sin identificador" }
    val planTitle = requireNotNull(title?.takeIf { it.isNotBlank() }) { "Plan sin titulo" }
    return Plan(
        id = planId,
        title = planTitle,
        description = description?.takeIf { it.isNotBlank() },
        authorName = authorName?.takeIf { it.isNotBlank() } ?: "Anfitrión por confirmar",
        timeAgo = createdAt?.take(10)?.let { "Publicado: $it" } ?: "Fecha no disponible",
        category = PlanCategory.entries.firstOrNull { it.name == category },
        createdAt = createdAt,
        authorAvatarUrl = authorAvatarUrl?.takeIf { it.startsWith("https://") },
        meetingPlace = meetingPlace?.takeIf { it.isNotBlank() },
        coordinates = if (latitude != null && longitude != null)
            runCatching { GeoPoint(requireNotNull(latitude), requireNotNull(longitude)) }.getOrNull() else null,
        imageUrl = imageUrl?.takeIf { it.startsWith("https://") },
        expiresAt = expiresAt?.takeIf { it > 0 },
        capacity = capacity?.takeIf { it > 0 },
        attendees = attendees?.filter { it.isNotBlank() },
        ownerId = ownerId, attendeeIds = attendeeIds, startsAt = startsAt, imageKey = imageKey,
        revision = revision?.takeIf { it >= 0 },
        photos = photos?.take(4)?.map { PlanPhoto(it.imageKey, it.imageUrl?.takeIf { url -> url.startsWith("https://") }) }
    )
}
