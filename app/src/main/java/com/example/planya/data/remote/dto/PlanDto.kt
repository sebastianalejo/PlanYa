package com.example.planya.data.remote.dto

data class PlanPhotoDto(val imageKey: String? = null, val imageUrl: String? = null)

// Los nombres corresponden al JSON que devuelve PlanYaApi.
data class PlanDto(
    val id: String? = null,
    val title: String? = null,
    val description: String? = null,
    val category: String? = null,
    val createdAt: String? = null,
    val authorName: String? = null,
    val authorAvatarUrl: String? = null,
    val meetingPlace: String? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val imageUrl: String? = null,
    val expiresAt: Long? = null,
    val capacity: Int? = null,
    val attendees: List<String>? = null,
    val ownerId: String? = null,
    val attendeeIds: List<String>? = null,
    val startsAt: Long? = null,
    val imageKey: String? = null,
    val revision: Long? = null,
    val photos: List<PlanPhotoDto>? = null
)

data class PlansDataDto(
    val items: List<PlanDto>? = null,
    val nextKey: String? = null
)

data class PlansResponseDto(
    val success: Boolean? = null,
    val data: PlansDataDto? = null,
    val error: String? = null
)

data class PlanResponseDto(val success: Boolean? = null, val data: PlanDto? = null)
