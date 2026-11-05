package com.personal.personalai.domain.repository

import com.personal.personalai.domain.model.Message
import kotlinx.coroutines.flow.Flow

interface ChatRepository {
    fun getMessages(): Flow<List<Message>>
    fun getMessagesByConversation(conversationId: String): Flow<List<Message>>
    suspend fun saveMessage(message: Message): Long
    suspend fun clearHistory()
    suspend fun deleteConversationMessages(conversationId: String)
}
