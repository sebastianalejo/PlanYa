package com.example.planya.domain.model

/** Límite de texto acordado con PlanYaApi (backend/chat.py). */
const val MAX_CHAT_TEXT = 1000

/** Ventana del chat después del final del encuentro (expiresAt del plan + 2 horas). */
const val CHAT_WINDOW_SECONDS = 2 * 60 * 60L

/**
 * Mensaje confirmado por el servidor. El autor sale del token verificado y la hora del
 * reloj del servidor; la app nunca los inventa.
 */
data class ChatMessage(
    val id: String,
    val planId: String,
    val senderId: String,
    val senderName: String,
    val text: String,
    val createdAtMillis: Long,
    /** Orden estable del servidor: "<milisegundos>#<uuid>". */
    val cursor: String,
    val clientMessageId: String? = null
)

data class ChatPage(
    val messages: List<ChatMessage>,
    /** Cursor para pedir mensajes anteriores; null si ya no hay más. */
    val olderKey: String?,
    /** Cursor para seguir pidiendo mensajes nuevos; null si ya están todos. */
    val newerKey: String?,
    /** Cierre del chat en segundos Unix según el servidor. */
    val chatExpiresAt: Long,
    val serverTimeSeconds: Long,
    val planTitle: String?,
    val isHost: Boolean,
    val hostId: String? = null
)

enum class ChatBlockReason { NOT_MEMBER, PLAN_CANCELLED, CHAT_CLOSED, NO_SCHEDULE, PLAN_NOT_FOUND }

/** El servidor bloqueó el chat (403/404/410). La app debe cerrar el acceso. */
class ChatBlockedException(val reason: ChatBlockReason, message: String) : IllegalStateException(message)

/** Une páginas sin duplicar mensajes y con el mismo orden que el servidor. */
fun mergeChatMessages(current: List<ChatMessage>, incoming: List<ChatMessage>): List<ChatMessage> =
    (current + incoming).associateBy { it.id }.values.sortedBy { it.cursor }

/** Texto corto del tiempo restante: "1 h 05 min", "12 min", "menos de 1 min". */
fun chatRemainingLabel(seconds: Long): String = when {
    seconds <= 0 -> "cerrado"
    seconds < 60 -> "menos de 1 min"
    seconds < 3600 -> "${(seconds + 59) / 60} min"
    else -> {
        val minutes = (seconds + 59) / 60
        "${minutes / 60} h ${(minutes % 60).toString().padStart(2, '0')} min"
    }
}
