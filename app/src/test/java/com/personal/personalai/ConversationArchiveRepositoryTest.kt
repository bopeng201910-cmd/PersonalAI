package com.personal.personalai

import com.personal.personalai.data.local.PersonalAIDatabase
import com.personal.personalai.data.local.dao.ConversationDao
import com.personal.personalai.data.local.dao.MessageDao
import com.personal.personalai.data.local.entity.ConversationEntity
import com.personal.personalai.data.local.entity.MessageEntity
import com.personal.personalai.data.repository.ConversationRepositoryImpl
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ConversationArchiveRepositoryTest {

    private val database = mockk<PersonalAIDatabase>(relaxed = true)
    private val conversationDao = mockk<ConversationDao>(relaxed = true)
    private val messageDao = mockk<MessageDao>(relaxed = true)
    private lateinit var repository: ConversationRepositoryImpl

    @Before
    fun setUp() {
        repository = ConversationRepositoryImpl(database, conversationDao, messageDao)
    }

    @Test
    fun `test createConversation inserts conversation entity with timestamps`() = runBlocking {
        val convSlot = slot<ConversationEntity>()
        coEvery { conversationDao.insertConversation(capture(convSlot)) } returns Unit

        val created = repository.createConversation("conv-123", "Trip to Tokyo")

        assertEquals("conv-123", created.id)
        assertEquals("Trip to Tokyo", created.title)
        assertTrue(created.createdAt > 0)
        coVerify(exactly = 1) { conversationDao.insertConversation(any()) }
    }

    @Test
    fun `test getAllConversations maps domain conversations properly ordered`() = runBlocking {
        val entities = listOf(
            ConversationEntity("conv-2", "Second Chat", 1000L, 2000L),
            ConversationEntity("conv-1", "First Chat", 500L, 1500L)
        )
        every { conversationDao.getAllConversations() } returns flowOf(entities)

        val result = repository.getAllConversations().first()

        assertEquals(2, result.size)
        assertEquals("conv-2", result[0].id)
        assertEquals("Second Chat", result[0].title)
        assertEquals("conv-1", result[1].id)
    }

    @Test
    fun `test deleteConversation deletes messages and conversation record in transaction`() = runBlocking {
        repository.deleteConversation("conv-del-1")

        coVerify(exactly = 1) { messageDao.deleteMessagesByConversation("conv-del-1") }
        coVerify(exactly = 1) { conversationDao.deleteConversationById("conv-del-1") }
    }
}
