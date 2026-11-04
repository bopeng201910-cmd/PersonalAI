package com.personal.personalai

import android.content.Context
import com.personal.personalai.domain.audio.AudioRecorder
import com.personal.personalai.domain.repository.CandidateMemoryRepository
import com.personal.personalai.domain.tools.PermissionBroker
import com.personal.personalai.domain.tools.UserInputBroker
import com.personal.personalai.domain.usecase.AgentLoopUseCase
import com.personal.personalai.domain.usecase.ExtractCandidateMemoriesUseCase
import com.personal.personalai.domain.usecase.GetChatHistoryUseCase
import com.personal.personalai.domain.usecase.TranscribeAudioUseCase
import com.personal.personalai.presentation.chat.ChatViewModel
import com.personal.personalai.presentation.chat.VoiceState
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
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
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModelTranscriptionLifecycleTest {

    private val testDispatcher = StandardTestDispatcher()
    private val agentLoopUseCase = mockk<AgentLoopUseCase>(relaxed = true)
    private val getChatHistoryUseCase = mockk<GetChatHistoryUseCase>(relaxed = true)
    private val transcribeAudioUseCase = mockk<TranscribeAudioUseCase>()
    private val audioRecorder = mockk<AudioRecorder>(relaxed = true)
    private val userInputBroker = mockk<UserInputBroker>(relaxed = true)
    private val permissionBroker = mockk<PermissionBroker>(relaxed = true)
    private val candidateMemoryRepo = mockk<CandidateMemoryRepository>(relaxed = true)
    private val extractCandidateMemoriesUseCase = mockk<ExtractCandidateMemoriesUseCase>(relaxed = true)
    private val context = mockk<Context>(relaxed = true)

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        coEvery { getChatHistoryUseCase() } returns flowOf(emptyList())
        every { userInputBroker.incoming } returns MutableSharedFlow()
        every { permissionBroker.incoming } returns MutableSharedFlow()
        coEvery { candidateMemoryRepo.getPendingCandidateMemories() } returns flowOf(emptyList())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `test transcription lifecycle start and cancel cleans up temporary file and resets voiceState without error`() = runTest {
        val tempAudio = File.createTempFile("voice_test", ".m4a")
        assertTrue(tempAudio.exists())

        every { audioRecorder.stop() } returns tempAudio

        val transcriptionStarted = CompletableDeferred<Unit>()
        coEvery { transcribeAudioUseCase(tempAudio) } coAnswers {
            transcriptionStarted.complete(Unit)
            kotlinx.coroutines.awaitCancellation()
            Result.success("Transcribed text")
        }

        val conversationRepo = mockk<com.personal.personalai.domain.repository.ConversationRepository>(relaxed = true)
        val chatRepo = mockk<com.personal.personalai.domain.repository.ChatRepository>(relaxed = true)
        every { conversationRepo.getAllConversations() } returns flowOf(emptyList())

        val viewModel = ChatViewModel(
            agentLoopUseCase, getChatHistoryUseCase, transcribeAudioUseCase,
            audioRecorder, userInputBroker, permissionBroker, candidateMemoryRepo,
            extractCandidateMemoriesUseCase, conversationRepo, chatRepo, context
        )

        // Start record -> Stop record (triggers transcription)
        viewModel.onRecordStart()
        viewModel.onRecordStop()
        testScheduler.advanceUntilIdle()
        transcriptionStarted.await()

        assertEquals(VoiceState.TRANSCRIBING, viewModel.uiState.value.voiceState)
        assertTrue(viewModel.currentTranscriptionJob!!.isActive)

        // Cancel transcription
        viewModel.cancelTranscription()
        testScheduler.advanceUntilIdle()

        // Assert state reset, job cancelled, no error surfaced, and temp file deleted
        assertEquals(VoiceState.IDLE, viewModel.uiState.value.voiceState)
        assertTrue(viewModel.currentTranscriptionJob == null)
        assertNull(viewModel.uiState.value.error)
        assertFalse("Temporary audio file must be cleaned up in finally block", tempAudio.exists())
    }

    @Test
    fun `test starting a second transcription cancels previous transcription and rejects stale result`() = runTest {
        val file1 = File.createTempFile("voice_test_1", ".m4a")
        val file2 = File.createTempFile("voice_test_2", ".m4a")

        every { audioRecorder.stop() } returnsMany listOf(file1, file2)

        val req1Proceed = CompletableDeferred<Unit>()

        coEvery { transcribeAudioUseCase(file1) } coAnswers {
            req1Proceed.await()
            Result.success("Stale result from first recording")
        }

        coEvery { transcribeAudioUseCase(file2) } coAnswers {
            Result.success("Fresh result from second recording")
        }

        val conversationRepo2 = mockk<com.personal.personalai.domain.repository.ConversationRepository>(relaxed = true)
        val chatRepo2 = mockk<com.personal.personalai.domain.repository.ChatRepository>(relaxed = true)
        every { conversationRepo2.getAllConversations() } returns flowOf(emptyList())

        val viewModel = ChatViewModel(
            agentLoopUseCase, getChatHistoryUseCase, transcribeAudioUseCase,
            audioRecorder, userInputBroker, permissionBroker, candidateMemoryRepo,
            extractCandidateMemoriesUseCase, conversationRepo2, chatRepo2, context
        )

        // First recording
        viewModel.onRecordStart()
        viewModel.onRecordStop()
        testScheduler.advanceUntilIdle()
        val firstJob = viewModel.currentTranscriptionJob

        // Second recording started while first is still waiting to finish
        viewModel.onRecordStart()
        viewModel.onRecordStop()
        testScheduler.advanceUntilIdle()

        assertTrue("First job must be cancelled by the second request", firstJob!!.isCancelled)

        // Allow first request to finish late
        req1Proceed.complete(Unit)
        testScheduler.advanceUntilIdle()

        // UI state must hold only the fresh result from recording 2
        assertEquals(VoiceState.IDLE, viewModel.uiState.value.voiceState)
        assertEquals("Fresh result from second recording", viewModel.uiState.value.inputText)

        assertFalse("File 1 must be deleted", file1.exists())
        assertFalse("File 2 must be deleted", file2.exists())
    }

    @Test
    fun `test cancelTranscription immediately invalidates in-flight transcription request ID`() = runTest {
        val file1 = File.createTempFile("voice_cancel_old", ".m4a")
        val file2 = File.createTempFile("voice_cancel_new", ".m4a")

        every { audioRecorder.stop() } returnsMany listOf(file1, file2)

        val req1Proceed = CompletableDeferred<Unit>()

        coEvery { transcribeAudioUseCase(file1) } coAnswers {
            req1Proceed.await()
            Result.success("Stale result from cancelled request A")
        }

        coEvery { transcribeAudioUseCase(file2) } coAnswers {
            Result.success("Fresh result from request B")
        }

        val conversationRepo3 = mockk<com.personal.personalai.domain.repository.ConversationRepository>(relaxed = true)
        val chatRepo3 = mockk<com.personal.personalai.domain.repository.ChatRepository>(relaxed = true)
        every { conversationRepo3.getAllConversations() } returns flowOf(emptyList())

        val viewModel = ChatViewModel(
            agentLoopUseCase, getChatHistoryUseCase, transcribeAudioUseCase,
            audioRecorder, userInputBroker, permissionBroker, candidateMemoryRepo,
            extractCandidateMemoriesUseCase, conversationRepo3, chatRepo3, context
        )

        // 1. Start Request A
        viewModel.onRecordStart()
        viewModel.onRecordStop()
        testScheduler.advanceUntilIdle()

        // 2. Explicitly cancel transcription for A
        viewModel.cancelTranscription()
        testScheduler.advanceUntilIdle()
        assertEquals(VoiceState.IDLE, viewModel.uiState.value.voiceState)

        // 3. Start a new recording session and start Request B
        viewModel.onRecordStart()
        viewModel.onRecordStop()
        testScheduler.advanceUntilIdle()
        assertEquals(VoiceState.TRANSCRIBING, viewModel.uiState.value.voiceState)

        // 4. Deliver A's delayed result
        req1Proceed.complete(Unit)
        testScheduler.advanceUntilIdle()

        // 5. Verify A cannot modify the UI state and B completes with its fresh result
        assertEquals(VoiceState.IDLE, viewModel.uiState.value.voiceState)
        assertEquals("Fresh result from request B", viewModel.uiState.value.inputText)

        assertFalse("File 1 must be deleted", file1.exists())
        assertFalse("File 2 must be deleted", file2.exists())
    }

    @Test
    fun `test stale failure from old request cannot overwrite new request state`() = runTest {
        val file1 = File.createTempFile("voice_fail_old", ".m4a")
        val file2 = File.createTempFile("voice_success_new", ".m4a")

        every { audioRecorder.stop() } returnsMany listOf(file1, file2)

        val req1Proceed = CompletableDeferred<Unit>()

        coEvery { transcribeAudioUseCase(file1) } coAnswers {
            req1Proceed.await()
            Result.failure(RuntimeException("Stale network timeout"))
        }

        coEvery { transcribeAudioUseCase(file2) } coAnswers {
            Result.success("Valid new transcription")
        }

        val conversationRepo4 = mockk<com.personal.personalai.domain.repository.ConversationRepository>(relaxed = true)
        val chatRepo4 = mockk<com.personal.personalai.domain.repository.ChatRepository>(relaxed = true)
        every { conversationRepo4.getAllConversations() } returns flowOf(emptyList())

        val viewModel = ChatViewModel(
            agentLoopUseCase, getChatHistoryUseCase, transcribeAudioUseCase,
            audioRecorder, userInputBroker, permissionBroker, candidateMemoryRepo,
            extractCandidateMemoriesUseCase, conversationRepo4, chatRepo4, context
        )

        viewModel.onRecordStart()
        viewModel.onRecordStop()
        testScheduler.advanceUntilIdle()

        // Second recording starts
        viewModel.onRecordStart()
        viewModel.onRecordStop()
        testScheduler.advanceUntilIdle()

        // Late failure on req 1 completes
        req1Proceed.complete(Unit)
        testScheduler.advanceUntilIdle()

        // Assert error from req1 was discarded and req2 succeeded
        assertNull("Stale failure must not overwrite UI state with error", viewModel.uiState.value.error)
        assertEquals("Valid new transcription", viewModel.uiState.value.inputText)
        assertEquals(VoiceState.IDLE, viewModel.uiState.value.voiceState)

        assertFalse("File 1 must be deleted", file1.exists())
        assertFalse("File 2 must be deleted", file2.exists())
    }

    @Test
    fun `test transcription failure deletes temporary file and surfaces error`() = runTest {
        val file = File.createTempFile("voice_fail", ".m4a")

        every { audioRecorder.stop() } returns file
        coEvery { transcribeAudioUseCase(file) } returns Result.failure(RuntimeException("Whisper server 500"))

        val conversationRepo5 = mockk<com.personal.personalai.domain.repository.ConversationRepository>(relaxed = true)
        val chatRepo5 = mockk<com.personal.personalai.domain.repository.ChatRepository>(relaxed = true)
        every { conversationRepo5.getAllConversations() } returns flowOf(emptyList())

        val viewModel = ChatViewModel(
            agentLoopUseCase, getChatHistoryUseCase, transcribeAudioUseCase,
            audioRecorder, userInputBroker, permissionBroker, candidateMemoryRepo,
            extractCandidateMemoriesUseCase, conversationRepo5, chatRepo5, context
        )

        viewModel.onRecordStart()
        viewModel.onRecordStop()
        testScheduler.advanceUntilIdle()

        assertEquals(VoiceState.IDLE, viewModel.uiState.value.voiceState)
        assertEquals("Whisper server 500", viewModel.uiState.value.error)
        assertFalse("Temporary file must be deleted on failure", file.exists())
    }
}
