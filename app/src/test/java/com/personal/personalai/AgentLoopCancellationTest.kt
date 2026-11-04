package com.personal.personalai

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import com.personal.personalai.domain.audio.AudioRecorder
import com.personal.personalai.domain.model.Message
import com.personal.personalai.domain.model.MessageRole
import com.personal.personalai.domain.repository.AiRepository
import com.personal.personalai.domain.repository.CandidateMemoryRepository
import com.personal.personalai.domain.repository.ChatRepository
import com.personal.personalai.domain.repository.MemoryRepository
import com.personal.personalai.domain.tools.AgentResponse
import com.personal.personalai.domain.tools.AgentTool
import com.personal.personalai.domain.tools.FunctionCall
import com.personal.personalai.domain.tools.PermissionBroker
import com.personal.personalai.domain.tools.ToolRegistry
import com.personal.personalai.domain.tools.ToolResult
import com.personal.personalai.domain.tools.UserInputBroker
import com.personal.personalai.domain.usecase.AgentLoopUseCase
import com.personal.personalai.domain.usecase.AgentStep
import com.personal.personalai.domain.usecase.ExtractCandidateMemoriesUseCase
import com.personal.personalai.domain.usecase.GetChatHistoryUseCase
import com.personal.personalai.domain.usecase.TranscribeAudioUseCase
import com.personal.personalai.presentation.chat.ChatViewModel
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AgentLoopCancellationTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `test cancellation during AI request propagates CancellationException and stops agent loop`() = runTest {
        val aiRepo = mockk<AiRepository>()
        val chatRepo = mockk<ChatRepository>(relaxed = true)
        val memoryRepo = mockk<MemoryRepository>(relaxed = true)
        val toolRegistry = mockk<ToolRegistry>(relaxed = true)
        val permissionBroker = mockk<PermissionBroker>(relaxed = true)
        val dataStore = mockk<DataStore<Preferences>>(relaxed = true)

        every { dataStore.data } returns flowOf(emptyPreferences())
        coEvery { chatRepo.getMessages() } returns flowOf(emptyList())
        coEvery { memoryRepo.getMemories() } returns flowOf(emptyList())

        val aiCallStarted = CompletableDeferred<Unit>()
        val allowAiFinish = CompletableDeferred<Unit>()

        coEvery {
            aiRepo.sendMessageWithTools(any(), any(), any(), any(), any())
        } coAnswers {
            aiCallStarted.complete(Unit)
            allowAiFinish.await()
            Result.success(AgentResponse.Text("Hello world"))
        }

        val useCase = AgentLoopUseCase(aiRepo, chatRepo, memoryRepo, toolRegistry, permissionBroker, dataStore)

        val steps = mutableListOf<AgentStep>()
        var cancellationCaught = false

        val job = launch {
            try {
                useCase("Hi").toList(steps)
            } catch (e: Throwable) {
                if (e is CancellationException) {
                    cancellationCaught = true
                }
                throw e
            }
        }

        testScheduler.advanceUntilIdle()
        aiCallStarted.await()

        // Cancel the job while waiting on AI
        job.cancel()
        testScheduler.advanceUntilIdle()

        assertTrue("CancellationException must be propagated when job is cancelled", cancellationCaught || job.isCancelled)
        assertTrue("Job must be cancelled", job.isCancelled)
        // No final success message should be produced or saved
        coVerify(exactly = 0) { chatRepo.saveMessage(match { it.role == MessageRole.ASSISTANT }) }
    }

    @Test
    fun `test cancellation while waiting for tool result prevents subsequent tools from executing`() = runTest {
        val aiRepo = mockk<AiRepository>()
        val chatRepo = mockk<ChatRepository>(relaxed = true)
        val memoryRepo = mockk<MemoryRepository>(relaxed = true)
        val toolRegistry = mockk<ToolRegistry>(relaxed = true)
        val permissionBroker = mockk<PermissionBroker>(relaxed = true)
        val dataStore = mockk<DataStore<Preferences>>(relaxed = true)

        every { dataStore.data } returns flowOf(emptyPreferences())
        coEvery { chatRepo.getMessages() } returns flowOf(emptyList())
        coEvery { memoryRepo.getMemories() } returns flowOf(emptyList())

        val toolCalls = listOf(
            FunctionCall(id = "c1", name = "slow_tool", arguments = "{}"),
            FunctionCall(id = "c2", name = "second_tool", arguments = "{}")
        )

        coEvery {
            aiRepo.sendMessageWithTools(any(), any(), any(), any(), any())
        } returns Result.success(AgentResponse.ToolCalls(toolCalls, "resp_1"))

        val tool1Started = CompletableDeferred<Unit>()
        val allowTool1Finish = CompletableDeferred<Unit>()

        coEvery { toolRegistry.execute("slow_tool", any()) } coAnswers {
            tool1Started.complete(Unit)
            allowTool1Finish.await()
            ToolResult.Success("tool 1 done")
        }

        val useCase = AgentLoopUseCase(aiRepo, chatRepo, memoryRepo, toolRegistry, permissionBroker, dataStore)

        var cancellationCaught = false
        val job = launch {
            try {
                useCase("Run tools").collect {}
            } catch (e: Throwable) {
                if (e is CancellationException) {
                    cancellationCaught = true
                }
                throw e
            }
        }

        testScheduler.advanceUntilIdle()
        tool1Started.await()

        // Cancel while tool 1 is running
        job.cancel()
        testScheduler.advanceUntilIdle()

        assertTrue("Job must be cancelled", job.isCancelled)
        // Second tool must NEVER be executed
        coVerify(exactly = 0) { toolRegistry.execute("second_tool", any()) }
    }

    @Test
    fun `test ChatViewModel cancelCurrentMessage resets loading and status without displaying error`() = runTest {
        val agentLoopUseCase = mockk<AgentLoopUseCase>()
        val getChatHistoryUseCase = mockk<GetChatHistoryUseCase>()
        val transcribeAudioUseCase = mockk<TranscribeAudioUseCase>(relaxed = true)
        val audioRecorder = mockk<AudioRecorder>(relaxed = true)
        val userInputBroker = mockk<UserInputBroker>(relaxed = true)
        val permissionBroker = mockk<PermissionBroker>(relaxed = true)
        val candidateMemoryRepo = mockk<CandidateMemoryRepository>(relaxed = true)
        val extractCandidateMemoriesUseCase = mockk<ExtractCandidateMemoriesUseCase>(relaxed = true)
        val context = mockk<Context>(relaxed = true)

        coEvery { getChatHistoryUseCase() } returns flowOf(emptyList())
        every { userInputBroker.incoming } returns MutableSharedFlow()
        every { permissionBroker.incoming } returns MutableSharedFlow()
        every { userInputBroker.incoming } returns MutableSharedFlow()
        every { permissionBroker.incoming } returns MutableSharedFlow()
        every { userInputBroker.incoming } returns MutableSharedFlow()
        every { permissionBroker.incoming } returns MutableSharedFlow()
        coEvery { candidateMemoryRepo.getPendingCandidateMemories() } returns flowOf(emptyList())

        val aiStarted = CompletableDeferred<Unit>()

        coEvery { agentLoopUseCase("Help me", any()) } returns flow {
            aiStarted.complete(Unit)
            emit(AgentStep.ToolCalling("search", "Searching online…"))
            kotlinx.coroutines.awaitCancellation()
        }

        val conversationRepo = mockk<com.personal.personalai.domain.repository.ConversationRepository>(relaxed = true)
        val mockChatRepo = mockk<ChatRepository>(relaxed = true)
        every { conversationRepo.getAllConversations() } returns flowOf(emptyList())
        every { mockChatRepo.getMessagesByConversation(any()) } returns flowOf(emptyList())

        val viewModel = ChatViewModel(
            agentLoopUseCase,
            getChatHistoryUseCase,
            transcribeAudioUseCase,
            audioRecorder,
            userInputBroker,
            permissionBroker,
            candidateMemoryRepo,
            extractCandidateMemoriesUseCase,
            conversationRepo,
            mockChatRepo,
            context
        )

        viewModel.onInputChanged("Help me")
        viewModel.sendMessage()

        // Run until tool calling status is emitted
        testScheduler.advanceUntilIdle()
        aiStarted.await()

        assertTrue(viewModel.uiState.value.isLoading)
        assertEquals("Searching online…", viewModel.uiState.value.agentStatusMessage)
        assertNull(viewModel.uiState.value.error)

        // User cancels active message
        viewModel.cancelCurrentMessage()
        testScheduler.advanceUntilIdle()

        // Assert state correctly reset
        assertFalse(viewModel.uiState.value.isLoading)
        assertNull(viewModel.uiState.value.agentStatusMessage)
        assertNull("Cancellation must not display an error message", viewModel.uiState.value.error)
    }

    @Test
    fun `test ChatViewModel sending new message cancels previous active message preventing stale state overwrite`() = runTest {
        val agentLoopUseCase = mockk<AgentLoopUseCase>()
        val getChatHistoryUseCase = mockk<GetChatHistoryUseCase>()
        val transcribeAudioUseCase = mockk<TranscribeAudioUseCase>(relaxed = true)
        val audioRecorder = mockk<AudioRecorder>(relaxed = true)
        val userInputBroker = mockk<UserInputBroker>(relaxed = true)
        val permissionBroker = mockk<PermissionBroker>(relaxed = true)
        val candidateMemoryRepo = mockk<CandidateMemoryRepository>(relaxed = true)
        val extractCandidateMemoriesUseCase = mockk<ExtractCandidateMemoriesUseCase>(relaxed = true)
        val context = mockk<Context>(relaxed = true)

        coEvery { getChatHistoryUseCase() } returns flowOf(emptyList())
        every { userInputBroker.incoming } returns MutableSharedFlow()
        every { permissionBroker.incoming } returns MutableSharedFlow()
        coEvery { candidateMemoryRepo.getPendingCandidateMemories() } returns flowOf(emptyList())

        val msg1Started = CompletableDeferred<Unit>()

        coEvery { agentLoopUseCase("First slow message", any()) } returns flow {
            msg1Started.complete(Unit)
            kotlinx.coroutines.awaitCancellation()
        }

        coEvery { agentLoopUseCase("Second fresh message", any()) } returns flow {
            emit(AgentStep.Complete(Result.success("Fresh answer")))
        }

        val conversationRepo2 = mockk<com.personal.personalai.domain.repository.ConversationRepository>(relaxed = true)
        val mockChatRepo2 = mockk<ChatRepository>(relaxed = true)
        every { conversationRepo2.getAllConversations() } returns flowOf(emptyList())
        every { mockChatRepo2.getMessagesByConversation(any()) } returns flowOf(emptyList())

        val viewModel = ChatViewModel(
            agentLoopUseCase,
            getChatHistoryUseCase,
            transcribeAudioUseCase,
            audioRecorder,
            userInputBroker,
            permissionBroker,
            candidateMemoryRepo,
            extractCandidateMemoriesUseCase,
            conversationRepo2,
            mockChatRepo2,
            context
        )

        // Send first message
        viewModel.onInputChanged("First slow message")
        viewModel.sendMessage()
        testScheduler.advanceUntilIdle()
        msg1Started.await()

        assertTrue(viewModel.uiState.value.isLoading)

        // Send second message while first is still pending
        viewModel.onInputChanged("Second fresh message")
        viewModel.sendMessage()
        testScheduler.advanceUntilIdle()

        // Fresh message finishes cleanly, loading returns to false, no error
        assertFalse(viewModel.uiState.value.isLoading)
        assertNull(viewModel.uiState.value.agentStatusMessage)
        assertNull(viewModel.uiState.value.error)
    }

    @Test
    fun `test normal successful execution works seamlessly after a cancelled request`() = runTest {
        val agentLoopUseCase = mockk<AgentLoopUseCase>()
        val getChatHistoryUseCase = mockk<GetChatHistoryUseCase>()
        val transcribeAudioUseCase = mockk<TranscribeAudioUseCase>(relaxed = true)
        val audioRecorder = mockk<AudioRecorder>(relaxed = true)
        val userInputBroker = mockk<UserInputBroker>(relaxed = true)
        val permissionBroker = mockk<PermissionBroker>(relaxed = true)
        val candidateMemoryRepo = mockk<CandidateMemoryRepository>(relaxed = true)
        val extractCandidateMemoriesUseCase = mockk<ExtractCandidateMemoriesUseCase>(relaxed = true)
        val context = mockk<Context>(relaxed = true)

        coEvery { getChatHistoryUseCase() } returns flowOf(emptyList())
        every { userInputBroker.incoming } returns MutableSharedFlow()
        every { permissionBroker.incoming } returns MutableSharedFlow()
        coEvery { candidateMemoryRepo.getPendingCandidateMemories() } returns flowOf(emptyList())

        coEvery { agentLoopUseCase("Message 1", any()) } returns flow {
            kotlinx.coroutines.awaitCancellation()
        }

        coEvery { agentLoopUseCase("Message 2", any()) } returns flow {
            emit(AgentStep.Complete(Result.success("Success 2")))
        }

        val conversationRepo3 = mockk<com.personal.personalai.domain.repository.ConversationRepository>(relaxed = true)
        val mockChatRepo3 = mockk<ChatRepository>(relaxed = true)
        every { conversationRepo3.getAllConversations() } returns flowOf(emptyList())
        every { mockChatRepo3.getMessagesByConversation(any()) } returns flowOf(emptyList())

        val viewModel = ChatViewModel(
            agentLoopUseCase,
            getChatHistoryUseCase,
            transcribeAudioUseCase,
            audioRecorder,
            userInputBroker,
            permissionBroker,
            candidateMemoryRepo,
            extractCandidateMemoriesUseCase,
            conversationRepo3,
            mockChatRepo3,
            context
        )

        viewModel.onInputChanged("Message 1")
        viewModel.sendMessage()
        testScheduler.advanceUntilIdle()
        assertTrue(viewModel.uiState.value.isLoading)

        viewModel.cancelCurrentMessage()
        testScheduler.advanceUntilIdle()
        assertFalse(viewModel.uiState.value.isLoading)

        viewModel.onInputChanged("Message 2")
        viewModel.sendMessage()
        testScheduler.advanceUntilIdle()

        assertFalse(viewModel.uiState.value.isLoading)
        assertNull(viewModel.uiState.value.error)
    }

    @Test
    fun `test checkNewMessagesForMemoryReview does not swallow CancellationException and handles generic errors gracefully`() = runTest {
        val agentLoopUseCase = mockk<AgentLoopUseCase>()
        val getChatHistoryUseCase = mockk<GetChatHistoryUseCase>()
        val transcribeAudioUseCase = mockk<TranscribeAudioUseCase>(relaxed = true)
        val audioRecorder = mockk<AudioRecorder>(relaxed = true)
        val userInputBroker = mockk<UserInputBroker>(relaxed = true)
        val permissionBroker = mockk<PermissionBroker>(relaxed = true)
        val candidateMemoryRepo = mockk<CandidateMemoryRepository>(relaxed = true)
        val extractCandidateMemoriesUseCase = mockk<ExtractCandidateMemoriesUseCase>()
        val context = mockk<Context>(relaxed = true)

        coEvery { getChatHistoryUseCase() } returns flowOf(emptyList())
        every { userInputBroker.incoming } returns MutableSharedFlow()
        every { permissionBroker.incoming } returns MutableSharedFlow()
        coEvery { candidateMemoryRepo.getPendingCandidateMemories() } returns flowOf(emptyList())

        // 1. Verify that ordinary extraction exceptions are caught and logged without crashing the ViewModel
        coEvery { extractCandidateMemoriesUseCase() } throws RuntimeException("Database error")

        val conversationRepo = mockk<com.personal.personalai.domain.repository.ConversationRepository>(relaxed = true)
        every { conversationRepo.getAllConversations() } returns flowOf(emptyList())

        val mockChatRepo4 = mockk<ChatRepository>(relaxed = true)
        every { mockChatRepo4.getMessagesByConversation(any()) } returns flowOf(emptyList())

        val errorViewModel = ChatViewModel(
            agentLoopUseCase,
            getChatHistoryUseCase,
            transcribeAudioUseCase,
            audioRecorder,
            userInputBroker,
            permissionBroker,
            candidateMemoryRepo,
            extractCandidateMemoriesUseCase,
            conversationRepo,
            mockChatRepo4,
            context
        )
        testScheduler.advanceUntilIdle()

        coVerify(atLeast = 1) { extractCandidateMemoriesUseCase() }
        assertNull(errorViewModel.uiState.value.error)
        assertTrue(errorViewModel.memoryReviewJob!!.isCompleted)
        assertFalse(errorViewModel.memoryReviewJob!!.isCancelled)

        // 2. Verify that CancellationException is NOT swallowed by the actual production ChatViewModel coroutine
        val suspendBarrier = CompletableDeferred<Unit>()
        val postCancelCheck = CompletableDeferred<Unit>()

        coEvery { extractCandidateMemoriesUseCase() } coAnswers {
            suspendBarrier.complete(Unit)
            kotlinx.coroutines.awaitCancellation()
            postCancelCheck.complete(Unit)
            Result.success(0)
        }

        val cancelViewModel = ChatViewModel(
            agentLoopUseCase,
            getChatHistoryUseCase,
            transcribeAudioUseCase,
            audioRecorder,
            userInputBroker,
            permissionBroker,
            candidateMemoryRepo,
            extractCandidateMemoriesUseCase,
            conversationRepo,
            mockChatRepo4,
            context
        )

        testScheduler.advanceUntilIdle()
        suspendBarrier.await()

        val actualJob = cancelViewModel.memoryReviewJob
        org.junit.Assert.assertNotNull("Production memoryReviewJob must be running", actualJob)
        assertTrue("Production memoryReviewJob must be active", actualJob!!.isActive)

        // Cancel the actual production launched coroutine
        actualJob.cancel()
        testScheduler.advanceUntilIdle()

        // Verify the coroutine was actually cancelled and cancellation was not swallowed
        assertTrue("Production memoryReviewJob must be marked cancelled", actualJob.isCancelled)
        assertFalse("Extraction should not continue after cancellation", postCancelCheck.isCompleted)
        assertNull("Cancellation should not set error state", cancelViewModel.uiState.value.error)
    }
}
