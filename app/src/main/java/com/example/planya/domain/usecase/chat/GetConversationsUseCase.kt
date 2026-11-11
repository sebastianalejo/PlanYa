package com.example.planya.domain.usecase.chat

import com.example.planya.domain.repository.ConversationsRepository

class GetConversationsUseCase(private val repository: ConversationsRepository) {
    suspend operator fun invoke(nextKey: String? = null) = repository.page(nextKey)
}
