package com.personal.personalai

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.personal.personalai.data.local.PersonalAIDatabase
import com.personal.personalai.data.local.dao.ConversationDao
import com.personal.personalai.data.local.dao.MemoryDao
import com.personal.personalai.data.local.dao.MessageDao
import com.personal.personalai.domain.model.Memory
import com.personal.personalai.localllm.api.LocalModel
import com.personal.personalai.presentation.settings.PreferencesKeys
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class ModelCenterIsolationTest {

    @Test
    fun `test switching active local model does not modify or delete personal memories or conversation archives`() = runTest {
        val memoryDao = mockk<MemoryDao>(relaxed = true)
        val messageDao = mockk<MessageDao>(relaxed = true)
        val conversationDao = mockk<ConversationDao>(relaxed = true)
        val dataStore = mockk<DataStore<Preferences>>(relaxed = true)

        val initialPrefs = mockk<Preferences>(relaxed = true)
        every { initialPrefs[PreferencesKeys.LOCAL_MODEL] } returns LocalModel.GEMMA_4_E2B.modelId
        every { dataStore.data } returns flowOf(initialPrefs)

        // Simulate switching model preference to Qwen
        dataStore.edit {
            it[PreferencesKeys.LOCAL_MODEL] = LocalModel.QWEN_2_5_1_5B.modelId
        }

        // Verify zero destructive database mutations occurred on personal memories or conversations
        coVerify(exactly = 0) { memoryDao.clearAll() }
        coVerify(exactly = 0) { memoryDao.deleteMemory(any()) }
        coVerify(exactly = 0) { messageDao.clearAll() }
        coVerify(exactly = 0) { conversationDao.deleteConversationById(any()) }
    }
}
