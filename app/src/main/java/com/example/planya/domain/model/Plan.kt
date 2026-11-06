package com.example.planya.domain.model

data class PlanPhoto(val imageKey: String? = null, val imageUrl: String? = null)

data class Plan(
    val id: String,
    val authorName: String,
    val authorAvatarUrl: String? = null,
    val timeAgo: String,
    val title: String,
    val description: String? = null,
    val distance: String? = null,
    val startsSoon: Boolean = false,
    val category: PlanCategory? = null,
    val createdAt: String? = null,
    val meetingPlace: String? = null,
    val coordinates: GeoPoint? = null,
    val imageUrl: String? = null,
    val expiresAt: Long? = null,
    val capacity: Int? = null,
    val attendees: List<String>? = null,
    val ownerId: String? = null,
    val attendeeIds: List<String>? = null,
    val startsAt: Long? = null,
    val imageKey: String? = null,
    val revision: Long? = null,
    val photos: List<PlanPhoto>? = null
)

fun Plan.gallery(): List<PlanPhoto> = photos ?: if (imageKey != null || imageUrl != null) listOf(PlanPhoto(imageKey, imageUrl)) else emptyList()
