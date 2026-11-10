package com.example.planya.presentation.screens.chat

import com.example.planya.domain.model.ChatBlockReason
import com.example.planya.domain.model.ChatMessage

enum class OutgoingStatus { SENDING, FAILED }

/** Mensaje propio que todavía no confirmó el servidor (pendiente o fallido). */
data class OutgoingMessage(
    val clientMessageId: String,
    val text: String,
    val status: OutgoingStatus,
    val error: String? = null
)

data class ChatUiState(
    val planTitle: String? = null,
    /** Mensajes confirmados por el servidor, de más antiguo a más reciente. */
    val messages: List<ChatMessage> = emptyList(),
    /** Envíos propios sin confirmar; se muestran al final. */
    val outgoing: List<OutgoingMessage> = emptyList(),
    val draft: String = "",
    val loading: Boolean = true,
    val loaded: Boolean = false,
    val refreshing: Boolean = false,
    val loadingOlder: Boolean = false,
    val olderKey: String? = null,
    /** Error de la primera carga, cuando todavía no hay nada que mostrar. */
    val error: String? = null,
    /** Error al actualizar; se conservan los mensajes ya cargados. */
    val refreshError: String? = null,
    val olderError: String? = null,
    /** Cierre del chat (segundos Unix del servidor). */
    val chatExpiresAt: Long? = null,
    /** Diferencia entre la hora del servidor y la del teléfono, en segundos. */
    val clockOffsetSeconds: Long = 0,
    val isHost: Boolean = false,
    val blocked: ChatBlockReason? = null,
    val blockedMessage: String? = null,
    val requiresLogin: Boolean = false,
    val lastUpdatedMillis: Long? = null
) {
    /** Segundos que faltan para el cierre según la hora del servidor, no la del teléfono. */
    fun secondsLeft(nowMillis: Long): Long? = chatExpiresAt?.let { it - (nowMillis / 1000 + clockOffsetSeconds) }

    val canWrite: Boolean get() = loaded && blocked == null
}
