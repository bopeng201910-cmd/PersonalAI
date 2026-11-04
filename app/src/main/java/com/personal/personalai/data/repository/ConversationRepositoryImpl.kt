package com.personal.personalai.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.room.withTransaction
import com.personal.personalai.data.local.PersonalAIDatabase
import com.personal.personalai.data.local.dao.ConversationDao
import com.personal.personalai.data.local.dao.MessageDao
import com.personal.personalai.data.local.entity.ConversationEntity
import com.personal.personalai.domain.model.Conversation
import com.personal.personalai.domain.repository.ConversationRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ConversationRepositoryImpl @Inject constructor(
    private val database: PersonalAIDatabase,
    private val conversationDao: ConversationDao,
    private val messageDao: MessageDao,
    private val dataStore: DataStore<Preferences>
) : ConversationRepository {

    override fun getAllConversations(): Flow<List<Conversation>> =
        conversationDao.getAllConversations().map { list ->
            list.map { Conversation(it.id, it.title, it.createdAt, it.updatedAt) }
        }

    override suspend fun getConversationById(id: String): Conversation? =
        conversationDao.getConversationById(id)?.let {
            Conversation(it.id, it.title, it.createdAt, it.updatedAt)
        }

    override suspend fun createConversation(id: String, title: String): Conversation {
        val now = System.currentTimeMillis()
        val entity = ConversationEntity(id = id, title = title, createdAt = now, updatedAt = now)
        conversationDao.insertConversation(entity)
        return Conversation(entity.id, entity.title, entity.createdAt, entity.updatedAt)
    }

    override suspend fun updateTitle(id: String, title: String) {
        val current = conversationDao.getConversationById(id) ?: return
        conversationDao.updateConversation(current.copy(title = title, updatedAt = System.currentTimeMillis()))
    }

    override suspend fun touchConversation(id: String) {
        conversationDao.touchConversation(id, System.currentTimeMillis())
    }

    override suspend fun deleteConversation(id: String) {
        database.withTransaction {
            messageDao.deleteMessagesByConversation(id)
            conversationDao.deleteConversationById(id)
        }
        // Clean up conversation-specific response chain cache in DataStore
        dataStore.edit { prefs ->
            prefs.remove(stringPreferencesKey("last_response_id_$id"))
            prefs.remove(longPreferencesKey("last_response_at_ms_$id"))
        }
    }
}
