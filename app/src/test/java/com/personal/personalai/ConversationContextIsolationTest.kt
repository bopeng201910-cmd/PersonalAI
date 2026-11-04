package com.personal.personalai

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import com.personal.personalai.domain.model.Message
import com.personal.personalai.domain.model.MessageRole
import com.personal.personalai.domain.repository.AiRepository
import com.personal.personalai.domain.repository.ChatRepository
import com.personal.personalai.domain.repository.MemoryRepository
import com.personal.personalai.domain.tools.AgentResponse
import com.personal.personalai.domain.tools.PermissionBroker
import com.personal.personalai.domain.tools.ToolRegistry
import com.personal.personalai.domain.usecase.AgentLoopUseCase
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationContextIsolationTest {

    @Test
    fun `test AgentLoopUseCase queries only active conversation messages and does not leak cross conversation context`() = runBlocking {
        val aiRepo = mockk<AiRepository>()
        val chatRepo = mockk<ChatRepository>(relaxed = true)
        val memoryRepo = mockk<MemoryRepository>(relaxed = true)
        val toolRegistry = mockk<ToolRegistry>(relaxed = true)
        val permissionBroker = mockk<PermissionBroker>(relaxed = true)
        val dataStore = mockk<DataStore<Preferences>>(relaxed = true)

        every { dataStore.data } returns flowOf(emptyPreferences())
        coEvery { memoryRepo.getMemories() } returns flowOf(emptyList())

        // Conversation 1 messages
        val conv1Messages = listOf(
            Message(id = 1L, content = "User secret in Conv 1", role = MessageRole.USER, conversationId = "conv-1"),
            Message(id = 2L, content = "Assistant response in Conv 1", role = MessageRole.ASSISTANT, conversationId = "conv-1")
        )
        // Conversation 2 messages
        val conv2Messages = listOf(
            Message(id = 3L, content = "User query in Conv 2", role = MessageRole.USER, conversationId = "conv-2")
        )

        coEvery { chatRepo.getMessagesByConversation("conv-1") } returns flowOf(conv1Messages)
        coEvery { chatRepo.getMessagesByConversation("conv-2") } returns flowOf(conv2Messages)

        val capturedInputSlot = slot<JSONArray>()
        coEvery {
            aiRepo.sendMessageWithTools(
                capture(capturedInputSlot),
                any(), any(), any(), any()
            )
        } returns Result.success(AgentResponse.Text("Response for Conv 2"))

        val useCase = AgentLoopUseCase(aiRepo, chatRepo, memoryRepo, toolRegistry, permissionBroker, dataStore)

        // Run agent loop for Conv 2
        useCase(message = "New turn in Conv 2", backgroundMode = false, conversationId = "conv-2").toList()

        // Verify messages query was scoped to conv-2 ONLY
        coVerify(exactly = 1) { chatRepo.getMessagesByConversation("conv-2") }
        coVerify(exactly = 0) { chatRepo.getMessagesByConversation("conv-1") }
        coVerify(exactly = 0) { chatRepo.getMessages() }

        // Inspect captured conversation input to prove Conv 1 content is completely absent
        val sentPrompt = capturedInputSlot.captured.toString()
        assertTrue("Sent prompt must contain Conv 2 user query", sentPrompt.contains("User query in Conv 2"))
        assertFalse("Sent prompt must NEVER contain Conv 1 secrets", sentPrompt.contains("User secret in Conv 1"))
    }
}
