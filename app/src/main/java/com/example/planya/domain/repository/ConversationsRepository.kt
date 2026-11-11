package com.example.planya.domain.repository

import com.example.planya.domain.model.ConversationsPage

interface ConversationsRepository {
    suspend fun page(nextKey: String? = null): ConversationsPage
}
