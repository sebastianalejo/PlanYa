package com.example.planya.domain.repository

import com.example.planya.domain.model.ChatMessage
import com.example.planya.domain.model.ChatPage

/** Contrato del chat temporal de un plan (US-08). */
interface ChatRepository {
    /** Página más reciente del chat. */
    suspend fun latest(planId: String): ChatPage

    /** Mensajes anteriores al cursor indicado. */
    suspend fun older(planId: String, cursor: String): ChatPage

    /** Mensajes nuevos posteriores al cursor indicado. */
    suspend fun newer(planId: String, cursor: String): ChatPage

    /**
     * Envía un texto. Reintentar con el mismo [clientMessageId] no crea un duplicado:
     * el servidor devuelve el mensaje que ya había guardado.
     */
    suspend fun send(planId: String, text: String, clientMessageId: String): ChatMessage
}
