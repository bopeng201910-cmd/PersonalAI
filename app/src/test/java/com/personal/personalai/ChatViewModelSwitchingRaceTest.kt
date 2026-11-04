package com.personal.personalai

import android.content.Context
import com.personal.personalai.domain.audio.AudioRecorder
import com.personal.personalai.domain.model.Conversation
import com.personal.personalai.domain.model.Message
import com.personal.personalai.domain.model.MessageRole
import com.personal.personalai.domain.repository.CandidateMemoryRepository
import com.personal.personalai.domain.repository.ChatRepository
import com.personal.personalai.domain.repository.ConversationRepository
import com.personal.personalai.domain.tools.PermissionBroker
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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
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
class ChatViewModelSwitchingRaceTest {

    private val testDispatcher = StandardTestDispatcher()
    private val agentLoopUseCase = mockk<AgentLoopUseCase>(relaxed = true)
    private val getChatHistoryUseCase = mockk<GetChatHistoryUseCase>(relaxed = true)
    private val transcribeAudioUseCase = mockk<TranscribeAudioUseCase>(relaxed = true)
    private val audioRecorder = mockk<AudioRecorder>(relaxed = true)
    private val userInputBroker = mockk<UserInputBroker>(relaxed = true)
    private val permissionBroker = mockk<PermissionBroker>(relaxed = true)
    private val candidateMemoryRepo = mockk<CandidateMemoryRepository>(relaxed = true)
    private val extractCandidateMemoriesUseCase = mockk<ExtractCandidateMemoriesUseCase>(relaxed = true)
    private val conversationRepo = mockk<ConversationRepository>(relaxed = true)
    private val chatRepo = mockk<ChatRepository>(relaxed = true)
    private val context = mockk<Context>(relaxed = true)

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        coEvery { getChatHistoryUseCase() } returns flowOf(emptyList())
        every { userInputBroker.incoming } returns MutableSharedFlow()
        every { permissionBroker.incoming } returns MutableSharedFlow()
        coEvery { candidateMemoryRepo.getPendingCandidateMemories() } returns flowOf(emptyList())
        every { conversationRepo.getAllConversations() } returns flowOf(emptyList())
        coEvery { chatRepo.getMessagesByConversation(any()) } returns flowOf(emptyList())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `test Switching conversations cancels in-flight message generation for previous conversation`() = runTest {
        val viewModel = ChatViewModel(
            agentLoopUseCase, getChatHistoryUseCase, transcribeAudioUseCase,
            audioRecorder, userInputBroker, permissionBroker, candidateMemoryRepo,
            extractCandidateMemoriesUseCase, conversationRepo, chatRepo, context
        )
        testScheduler.advanceUntilIdle()

        // Setup a long-running agent loop
        val agentStarted = CompletableDeferred<Unit>()
        coEvery { agentLoopUseCase(any(), any(), any()) } returns flow {
            agentStarted.complete(Unit)
            kotlinx.coroutines.awaitCancellation()
            emit(AgentStep.Complete(Result.success("Delayed response")))
        }

        viewModel.onInputChanged("Hello conv-1")
        viewModel.sendMessage()
        testScheduler.advanceTimeBy(10)
        agentStarted.await()

        assertTrue(viewModel.uiState.value.isLoading)

        // Switch to a new conversation
        viewModel.openConversation("conv-2")
        testScheduler.advanceUntilIdle()

        // Generation must be cancelled and loading reset
        assertEquals("conv-2", viewModel.uiState.value.activeConversationId)
        assertFalse("isLoading must be false after conversation switch", viewModel.uiState.value.isLoading)
        assertNull("Status message must be cleared", viewModel.uiState.value.agentStatusMessage)
    }

    @Test
    fun `test Switching conversation immediately clears message list before new messages arrive`() = runTest {
        val conv1Flow = MutableSharedFlow<List<Message>>()
        val conv2Flow = MutableSharedFlow<List<Message>>()

        coEvery { chatRepo.getMessagesByConversation("conv-1") } returns conv1Flow
        coEvery { chatRepo.getMessagesByConversation("conv-2") } returns conv2Flow

        val viewModel = ChatViewModel(
            agentLoopUseCase, getChatHistoryUseCase, transcribeAudioUseCase,
            audioRecorder, userInputBroker, permissionBroker, candidateMemoryRepo,
            extractCandidateMemoriesUseCase, conversationRepo, chatRepo, context
        )
        testScheduler.advanceUntilIdle()

        viewModel.openConversation("conv-1")
        testScheduler.advanceUntilIdle()

        // Populate messages for conv-1
        conv1Flow.emit(listOf(Message(id = 1, content = "Conv 1 Msg", role = MessageRole.USER, conversationId = "conv-1")))
        testScheduler.advanceUntilIdle()
        assertEquals(1, viewModel.uiState.value.messages.size)

        // Switch to conv-2 without emitting yet on conv2Flow
        viewModel.openConversation("conv-2")

        // Old messages must be cleared IMMEDIATELY, avoiding visual stale state
        assertEquals("conv-2", viewModel.uiState.value.activeConversationId)
        assertTrue("Messages must be cleared immediately upon conversation switch", viewModel.uiState.value.messages.isEmpty())
    }

    @Test
    fun `test Stale message collector emission does not overwrite active conversation messages`() = runTest {
        val conv1Flow = MutableSharedFlow<List<Message>>()
        val conv2Flow = MutableSharedFlow<List<Message>>()

        coEvery { chatRepo.getMessagesByConversation("conv-1") } returns conv1Flow
        coEvery { chatRepo.getMessagesByConversation("conv-2") } returns conv2Flow

        val viewModel = ChatViewModel(
            agentLoopUseCase, getChatHistoryUseCase, transcribeAudioUseCase,
            audioRecorder, userInputBroker, permissionBroker, candidateMemoryRepo,
            extractCandidateMemoriesUseCase, conversationRepo, chatRepo, context
        )
        testScheduler.advanceUntilIdle()

        viewModel.openConversation("conv-1")
        testScheduler.advanceUntilIdle()

        viewModel.openConversation("conv-2")
        testScheduler.advanceUntilIdle()

        // Emit message for conv-2
        val conv2Msgs = listOf(
            Message(id = 20, content = "Message in conv 2", role = MessageRole.USER, conversationId = "conv-2")
        )
        conv2Flow.emit(conv2Msgs)
        testScheduler.advanceUntilIdle()

        assertEquals(1, viewModel.uiState.value.messages.size)
        assertEquals("Message in conv 2", viewModel.uiState.value.messages[0].content)

        // Stale delayed emission from conv-1 arrives late
        val staleConv1Msgs = listOf(
            Message(id = 10, content = "Stale message in conv 1", role = MessageRole.USER, conversationId = "conv-1")
        )
        conv1Flow.emit(staleConv1Msgs)
        testScheduler.advanceUntilIdle()

        // Active conversation must remain conv-2 and its messages must not be overwritten
        assertEquals("conv-2", viewModel.uiState.value.activeConversationId)
        assertEquals("Message in conv 2", viewModel.uiState.value.messages[0].content)
        assertEquals(1, viewModel.uiState.value.messages.size)
    }

    @Test
    fun `test Rapid consecutive startNewConversation calls resolve to latest created conversation`() = runTest {
        val createdConversations = mutableListOf<String>()
        val firstCreationProceed = CompletableDeferred<Unit>()

        coEvery { conversationRepo.createConversation(any(), any()) } coAnswers {
            val id = firstArg<String>()
            createdConversations.add(id)
            if (createdConversations.size == 1) {
                firstCreationProceed.await()
            }
            Conversation(id, "New Conversation", 1000L, 1000L)
        }

        val viewModel = ChatViewModel(
            agentLoopUseCase, getChatHistoryUseCase, transcribeAudioUseCase,
            audioRecorder, userInputBroker, permissionBroker, candidateMemoryRepo,
            extractCandidateMemoriesUseCase, conversationRepo, chatRepo, context
        )
        testScheduler.advanceUntilIdle()

        viewModel.startNewConversation()
        viewModel.startNewConversation()
        testScheduler.advanceTimeBy(5)

        assertEquals(2, createdConversations.size)
        val secondId = createdConversations[1]

        firstCreationProceed.complete(Unit)
        testScheduler.advanceUntilIdle()

        // Active conversation must resolve to the second (latest) created conversation
        assertEquals(secondId, viewModel.uiState.value.activeConversationId)
    }

    @Test
    fun `test Default conversation cannot be deleted`() = runTest {
        val viewModel = ChatViewModel(
            agentLoopUseCase, getChatHistoryUseCase, transcribeAudioUseCase,
            audioRecorder, userInputBroker, permissionBroker, candidateMemoryRepo,
            extractCandidateMemoriesUseCase, conversationRepo, chatRepo, context
        )
        testScheduler.advanceUntilIdle()

        viewModel.deleteConversation("default")
        testScheduler.advanceUntilIdle()

        coVerify(exactly = 0) { conversationRepo.deleteConversation("default") }
    }

    @Test
    fun `test Deleting active conversation cancels generation and resets to default conversation`() = runTest {
        val viewModel = ChatViewModel(
            agentLoopUseCase, getChatHistoryUseCase, transcribeAudioUseCase,
            audioRecorder, userInputBroker, permissionBroker, candidateMemoryRepo,
            extractCandidateMemoriesUseCase, conversationRepo, chatRepo, context
        )
        testScheduler.advanceUntilIdle()

        viewModel.openConversation("conv-active")
        testScheduler.advanceUntilIdle()
        assertEquals("conv-active", viewModel.uiState.value.activeConversationId)

        val agentStarted = CompletableDeferred<Unit>()
        coEvery { agentLoopUseCase(any(), any(), any()) } returns flow {
            agentStarted.complete(Unit)
            kotlinx.coroutines.awaitCancellation()
            emit(AgentStep.Complete(Result.success("Never reached")))
        }

        viewModel.onInputChanged("Run task")
        viewModel.sendMessage()
        testScheduler.advanceTimeBy(10)
        agentStarted.await()
        assertTrue(viewModel.uiState.value.isLoading)

        viewModel.deleteConversation("conv-active")
        testScheduler.advanceUntilIdle()

        coVerify(exactly = 1) { conversationRepo.deleteConversation("conv-active") }
        assertEquals("default", viewModel.uiState.value.activeConversationId)
        assertFalse(viewModel.uiState.value.isLoading)
        assertNull(viewModel.uiState.value.agentStatusMessage)
    }

    @Test
    fun `test Deleting active conversation does not override newer conversation selection made during deletion`() = runTest {
        val deletionProceed = CompletableDeferred<Unit>()
        coEvery { conversationRepo.deleteConversation("conv-to-delete") } coAnswers {
            deletionProceed.await()
        }

        val viewModel = ChatViewModel(
            agentLoopUseCase, getChatHistoryUseCase, transcribeAudioUseCase,
            audioRecorder, userInputBroker, permissionBroker, candidateMemoryRepo,
            extractCandidateMemoriesUseCase, conversationRepo, chatRepo, context
        )
        testScheduler.advanceUntilIdle()

        viewModel.openConversation("conv-to-delete")
        testScheduler.advanceUntilIdle()
        assertEquals("conv-to-delete", viewModel.uiState.value.activeConversationId)

        // 1. Trigger deletion of active conversation
        viewModel.deleteConversation("conv-to-delete")
        testScheduler.advanceTimeBy(5)

        // 2. User immediately selects another conversation "conv-other" while deletion is in flight
        viewModel.openConversation("conv-other")
        testScheduler.advanceTimeBy(5)
        assertEquals("conv-other", viewModel.uiState.value.activeConversationId)

        // 3. Deletion completes late
        deletionProceed.complete(Unit)
        testScheduler.advanceUntilIdle()

        // Active conversation must NOT be clobbered to "default"
        assertEquals("conv-other", viewModel.uiState.value.activeConversationId)
    }

    @Test
    fun `test Deleting non-active conversation keeps active conversation intact`() = runTest {
        val viewModel = ChatViewModel(
            agentLoopUseCase, getChatHistoryUseCase, transcribeAudioUseCase,
            audioRecorder, userInputBroker, permissionBroker, candidateMemoryRepo,
            extractCandidateMemoriesUseCase, conversationRepo, chatRepo, context
        )
        testScheduler.advanceUntilIdle()

        viewModel.openConversation("conv-stay")
        testScheduler.advanceUntilIdle()
        assertEquals("conv-stay", viewModel.uiState.value.activeConversationId)

        viewModel.deleteConversation("conv-other")
        testScheduler.advanceUntilIdle()

        coVerify(exactly = 1) { conversationRepo.deleteConversation("conv-other") }
        assertEquals("conv-stay", viewModel.uiState.value.activeConversationId)
    }
}
