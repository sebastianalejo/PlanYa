package com.example.planya.domain.model

import java.text.Normalizer
import java.util.Locale

enum class PlanFeedScope(val label: String) { ALL("Explorar"), OWNED("Mis planes"), JOINED("Me uní") }
enum class PlanFeedOrder(val label: String) { RECOMMENDED("Para ti"), DISTANCE("Más cercanos"), EXPIRY("Por vencer") }

// Trabaja únicamente sobre páginas cargadas, no simula una consulta global a AWS.
fun visibleFeed(plans: List<Plan>, query: String, category: PlanCategory?, scope: PlanFeedScope,
    order: PlanFeedOrder, userId: String?, interests: Set<String>, origin: GeoPoint?, now: Long): List<Plan> {
    fun normalized(value: String) = Normalizer.normalize(value, Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "").lowercase(Locale.ROOT)
    val words = normalized(query).split(Regex("\\s+")).filter(String::isNotBlank)
    val matching = plans.filter { plan ->
        !plan.isExpired(now) && (category == null || plan.category == category) && when (scope) {
            PlanFeedScope.ALL -> true
            PlanFeedScope.OWNED -> userId != null && plan.ownerId == userId
            PlanFeedScope.JOINED -> userId != null && plan.ownerId != userId && plan.attendeeIds?.contains(userId) == true
        } && words.all { it in normalized(listOfNotNull(plan.title, plan.description, plan.meetingPlace, plan.authorName, plan.id).joinToString(" ")) }
    }
    return when (order) {
        PlanFeedOrder.RECOMMENDED -> matching.sortedWith(compareByDescending<Plan> { it.category?.name in interests }.thenBy { it.startsAt ?: Long.MAX_VALUE })
        PlanFeedOrder.DISTANCE -> matching.sortedBy { plan -> origin?.let { here -> plan.coordinates?.let(here::metersTo) } ?: Double.POSITIVE_INFINITY }
        PlanFeedOrder.EXPIRY -> matching.sortedBy { it.expiresAt ?: Long.MAX_VALUE }
    }
}
