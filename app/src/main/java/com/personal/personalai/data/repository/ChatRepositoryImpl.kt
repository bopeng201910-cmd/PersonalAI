package com.personal.personalai.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import com.personal.personalai.data.local.dao.MessageDao
import com.personal.personalai.data.local.entity.toDomain
import com.personal.personalai.data.local.entity.toEntity
import com.personal.personalai.domain.model.Message
import com.personal.personalai.domain.repository.ChatRepository
import com.personal.personalai.presentation.settings.PreferencesKeys
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject

class ChatRepositoryImpl @Inject constructor(
    private val messageDao: MessageDao,
    private val dataStore: DataStore<Preferences>,
) : ChatRepository {

    override fun getMessages(): Flow<List<Message>> =
        messageDao.getAllMessages().map { entities -> entities.map { it.toDomain() } }

    override fun getMessagesByConversation(conversationId: String): Flow<List<Message>> =
        messageDao.getMessagesByConversation(conversationId).map { entities -> entities.map { it.toDomain() } }

    override suspend fun saveMessage(message: Message): Long =
        messageDao.insertMessage(message.toEntity())

    override suspend fun deleteConversationMessages(conversationId: String) {
        messageDao.deleteMessagesByConversation(conversationId)
    }

    override suspend fun clearHistory() {
        messageDao.clearAll()
        // Drop the OpenAI Responses-API chain id too — a wiped chat can't
        // sensibly chain back to a server-side conversation the user just
        // asked us to forget.
        dataStore.edit { prefs ->
            prefs.remove(PreferencesKeys.LAST_RESPONSE_ID)
            prefs.remove(PreferencesKeys.LAST_RESPONSE_AT_MS)
        }
    }
}
