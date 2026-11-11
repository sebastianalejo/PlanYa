package com.example.planya.domain.usecase.chat

import com.example.planya.domain.model.ChatPage
import com.example.planya.domain.model.mergeChatMessages
import com.example.planya.domain.repository.ChatRepository

class GetChatMessagesUseCase(private val repository: ChatRepository) {
    suspend fun latest(planId: String): ChatPage = repository.latest(planId)

    suspend fun older(planId: String, cursor: String): ChatPage = repository.older(planId, cursor)

    /**
     * Trae los mensajes nuevos posteriores a [cursor], encadenando como máximo [maxPages]
     * páginas. Si aún quedan más, el resultado conserva newerKey para continuar después.
     */
    suspend fun newer(planId: String, cursor: String, maxPages: Int = 5): ChatPage {
        var page = repository.newer(planId, cursor)
        var collected = page.messages
        var pages = 1
        while (page.newerKey != null && pages < maxPages) {
            page = repository.newer(planId, page.newerKey!!)
            collected = mergeChatMessages(collected, page.messages)
            pages++
        }
        return page.copy(messages = collected)
    }
}

class SendChatMessageUseCase(private val repository: ChatRepository) {
    suspend operator fun invoke(planId: String, text: String, clientMessageId: String) =
        repository.send(planId, text.trim(), clientMessageId)
}
