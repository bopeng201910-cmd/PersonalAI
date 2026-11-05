package com.personal.personalai

import com.personal.personalai.domain.model.Memory
import com.personal.personalai.domain.model.Message
import com.personal.personalai.domain.model.MessageRole
import com.personal.personalai.domain.repository.ChatRepository
import com.personal.personalai.domain.repository.MemoryRepository
import com.personal.personalai.domain.usecase.ExportPersonalArchiveUseCase
import com.personal.personalai.domain.usecase.SearchPersonalMemoryUseCase
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PersonalMemoryGroundingTest {

    @Test
    fun `test SearchPersonalMemoryUseCase returns grounded evidence when matching records exist`() = runTest {
        val memoryRepo = mockk<MemoryRepository>()
        val chatRepo = mockk<ChatRepository>()

        val memories = listOf(
            Memory(id = 1L, content = "User is allergic to peanuts", topic = "allergy", createdAt = 1000L),
            Memory(id = 2L, content = "User enjoys hiking in the mountains", topic = "hobby", createdAt = 2000L)
        )
        val messages = listOf(
            Message(id = 10L, content = "Remember that I love hiking in Colorado", role = MessageRole.USER, timestamp = 1500L),
            Message(id = 11L, content = "I noted that you love hiking in Colorado!", role = MessageRole.ASSISTANT, timestamp = 1510L)
        )

        coEvery { memoryRepo.getMemories() } returns flowOf(memories)
        coEvery { chatRepo.getMessages() } returns flowOf(messages)

        val useCase = SearchPersonalMemoryUseCase(memoryRepo, chatRepo)
        val result = useCase("hiking")

        assertTrue(result.evidenceFound)
        assertEquals(1, result.matchedMemories.size)
        assertEquals("User enjoys hiking in the mountains", result.matchedMemories[0].content)
        assertEquals(2, result.matchedMessages.size)

        val formatted = useCase.formatEvidenceResponse(result)
        assertTrue(formatted.contains("Confirmed Memories:"))
        assertTrue(formatted.contains("User enjoys hiking in the mountains"))
        assertTrue(formatted.contains("Relevant Conversation Records:"))
        assertTrue(formatted.contains("Remember that I love hiking in Colorado"))
    }

    @Test
    fun `test SearchPersonalMemoryUseCase explicitly reports absence of evidence when no records exist`() = runTest {
        val memoryRepo = mockk<MemoryRepository>()
        val chatRepo = mockk<ChatRepository>()

        coEvery { memoryRepo.getMemories() } returns flowOf(emptyList())
        coEvery { chatRepo.getMessages() } returns flowOf(emptyList())

        val useCase = SearchPersonalMemoryUseCase(memoryRepo, chatRepo)
        val result = useCase("scuba diving")

        assertFalse(result.evidenceFound)
        assertTrue(result.matchedMemories.isEmpty())
        assertTrue(result.matchedMessages.isEmpty())

        val formatted = useCase.formatEvidenceResponse(result)
        // System must report absence of evidence honestly rather than inventing memories
        assertTrue(formatted.contains("could not find any evidence or conversations regarding \"scuba diving\""))
    }

    @Test
    fun `test ExportPersonalArchiveUseCase produces valid structured JSON archive`() = runTest {
        val memoryRepo = mockk<MemoryRepository>()
        val chatRepo = mockk<ChatRepository>()
        val convRepo = mockk<com.personal.personalai.domain.repository.ConversationRepository>()

        val memories = listOf(
            Memory(id = 5L, content = "User preferred language is Mandarin", topic = "language", createdAt = 5000L)
        )
        val convs = listOf(
            com.personal.personalai.domain.model.Conversation("conv-1", "Default Chat", 1000L, 2000L)
        )
        val msgs = listOf(
            Message(id = 20L, content = "Ni hao", role = MessageRole.USER, timestamp = 1200L, conversationId = "conv-1")
        )

        coEvery { memoryRepo.getMemories() } returns flowOf(memories)
        coEvery { convRepo.getAllConversations() } returns flowOf(convs)
        coEvery { chatRepo.getMessagesByConversation("conv-1") } returns flowOf(msgs)

        val useCase = ExportPersonalArchiveUseCase(memoryRepo, chatRepo, convRepo)
        val exportedStr = useCase()

        val json = JSONObject(exportedStr)
        assertEquals(1, json.getInt("exportVersion"))
        assertTrue(json.has("exportedAtMs"))

        val memArray = json.getJSONArray("memories")
        assertEquals(1, memArray.length())
        assertEquals("User preferred language is Mandarin", memArray.getJSONObject(0).getString("content"))

        val convArray = json.getJSONArray("conversations")
        assertEquals(1, convArray.length())
        assertEquals("conv-1", convArray.getJSONObject(0).getString("id"))
        val msgArray = convArray.getJSONObject(0).getJSONArray("messages")
        assertEquals(1, msgArray.length())
        assertEquals("Ni hao", msgArray.getJSONObject(0).getString("content"))
    }
}
