package com.example.planya.model

data class Plan(
    val id: String,
    val authorName: String,
    val authorAvatarUrl: String? = null,
    val timeAgo: String,
    val title: String,
    val description: String? = null,
    val distance: String,
    val startsSoon: Boolean = false
)

enum class PlanCategory(val label: String) {
    CAFE("Café"), ALMUERZO("Almuerzo"), CAMINATA("Caminata")
}
