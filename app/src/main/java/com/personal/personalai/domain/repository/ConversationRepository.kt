package com.personal.personalai.domain.repository

import com.personal.personalai.domain.model.Conversation
import kotlinx.coroutines.flow.Flow

interface ConversationRepository {
    fun getAllConversations(): Flow<List<Conversation>>
    suspend fun getConversationById(id: String): Conversation?
    suspend fun createConversation(id: String, title: String): Conversation
    suspend fun updateTitle(id: String, title: String)
    suspend fun touchConversation(id: String)
    suspend fun deleteConversation(id: String)
}
