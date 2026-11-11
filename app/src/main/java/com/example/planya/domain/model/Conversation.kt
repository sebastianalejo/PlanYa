package com.example.planya.domain.model

/** Resumen autorizado por el servidor; no contiene mensajes, direcciones ni contadores ficticios. */
data class Conversation(
    val planId: String,
    val title: String,
    val hostId: String?,
    val hostName: String?,
    val category: PlanCategory?,
    val planExpiresAt: Long,
    val chatExpiresAt: Long,
    val isHost: Boolean
) {
    fun isOpen(nowSeconds: Long) = nowSeconds < chatExpiresAt
}

data class ConversationsPage(val items: List<Conversation>, val nextKey: String?, val serverTime: Long)

class ConversationsCursorExpiredException : IllegalStateException("La lista cambió. Actualiza para continuar.")

fun mergeConversations(current: List<Conversation>, incoming: List<Conversation>): List<Conversation> =
    (current + incoming).associateBy { it.planId }.values.sortedWith(compareBy({ it.chatExpiresAt }, { it.planId }))
