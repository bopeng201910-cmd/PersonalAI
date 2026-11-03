package com.personal.personalai.presentation.chat

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.personal.personalai.R
import com.personal.personalai.domain.audio.AudioRecorder
import com.personal.personalai.domain.tools.PermissionBroker
import com.personal.personalai.domain.tools.UserInputBroker
import com.personal.personalai.domain.usecase.AgentLoopUseCase
import com.personal.personalai.domain.usecase.AgentStep
import com.personal.personalai.domain.repository.CandidateMemoryRepository
import com.personal.personalai.domain.usecase.ExtractCandidateMemoriesUseCase
import com.personal.personalai.domain.usecase.GetChatHistoryUseCase
import com.personal.personalai.domain.usecase.TranscribeAudioUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ChatViewModel @Inject constructor(
    private val agentLoopUseCase: AgentLoopUseCase,
    private val getChatHistoryUseCase: GetChatHistoryUseCase,
    private val transcribeAudioUseCase: TranscribeAudioUseCase,
    private val audioRecorder: AudioRecorder,
    private val userInputBroker: UserInputBroker,
    private val permissionBroker: PermissionBroker,
    private val candidateMemoryRepository: CandidateMemoryRepository,
    private val extractCandidateMemoriesUseCase: ExtractCandidateMemoriesUseCase,
    @ApplicationContext private val context: Context
) : ViewModel() {

    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    private var currentMessageJob: Job? = null

    init {
        observeMessages()
        observeUserInputRequests()
        observePermissionRequests()
        observePendingReviewCount()
        checkNewMessagesForMemoryReview()
    }

    private fun checkNewMessagesForMemoryReview() {
        viewModelScope.launch {
            // Background scan for any new unreviewed messages upon app launch
            try {
                extractCandidateMemoriesUseCase()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Automatic launch memory review check failed: ${e.message}", e)
            }
        }
    }

    private fun observePendingReviewCount() {
        viewModelScope.launch {
            candidateMemoryRepository.getPendingCandidateMemories().collect { list ->
                _uiState.update { it.copy(pendingReviewCount = list.size) }
            }
        }
    }

    private fun observeMessages() {
        viewModelScope.launch {
            getChatHistoryUseCase().collect { messages ->
                _uiState.update { it.copy(messages = messages) }
            }
        }
    }

    /** Listens for [AskUserTool] questions and surfaces them in the UI. */
    private fun observeUserInputRequests() {
        viewModelScope.launch {
            userInputBroker.incoming.collect { request ->
                _uiState.update {
                    it.copy(
                        pendingInputRequest = request,
                        agentStatusMessage = "💬 Waiting for your response…"
                    )
                }
            }
        }
    }

    /** Listens for permission requests from the agent loop and surfaces them in the UI. */
    private fun observePermissionRequests() {
        viewModelScope.launch {
            permissionBroker.incoming.collect { request ->
                _uiState.update { it.copy(pendingPermissionRequest = request) }
            }
        }
    }

    /** Called by ChatScreen with the system dialog result (true = granted, false = denied). */
    fun resolvePermission(requestId: String, granted: Boolean) {
        _uiState.update { it.copy(pendingPermissionRequest = null) }
        permissionBroker.resolve(requestId, granted)
    }

    fun onInputChanged(text: String) {
        _uiState.update { it.copy(inputText = text) }
    }

    /** Called when the user taps a quick-reply button on the question card. */
    fun answerQuickReply(reply: String) {
        val pending = _uiState.value.pendingInputRequest ?: return
        _uiState.update { it.copy(pendingInputRequest = null) }
        userInputBroker.answer(pending.id, reply)
    }

    fun sendMessage() {
        val text = _uiState.value.inputText.trim()
        if (text.isEmpty()) return

        // If the agent is waiting for the user to answer a question, route the text as the answer.
        val pending = _uiState.value.pendingInputRequest
        if (pending != null) {
            _uiState.update { it.copy(inputText = "", pendingInputRequest = null) }
            userInputBroker.answer(pending.id, text)
            return
        }

        // Cancel any active message generation before starting a new one
        currentMessageJob?.cancel()

        _uiState.update { it.copy(inputText = "", isLoading = true, agentStatusMessage = null, error = null) }

        currentMessageJob = viewModelScope.launch {
            try {
                agentLoopUseCase(text, backgroundMode = false).collect { step ->
                    when (step) {
                        is AgentStep.ToolCalling ->
                            _uiState.update { it.copy(agentStatusMessage = step.humanReadable) }
                        is AgentStep.Complete -> {
                            _uiState.update {
                                it.copy(isLoading = false, agentStatusMessage = null)
                            }
                            step.result.onFailure { e ->
                                _uiState.update { it.copy(error = e.message) }
                            }
                        }
                    }
                }
            } catch (cancellation: CancellationException) {
                // Reset loading and status on cancellation if this job is still the active job;
                // do NOT surface an error message
                if (currentMessageJob === coroutineContext[Job]) {
                    _uiState.update {
                        it.copy(isLoading = false, agentStatusMessage = null)
                    }
                }
            } catch (e: Exception) {
                if (currentMessageJob === coroutineContext[Job]) {
                    _uiState.update {
                        it.copy(isLoading = false, agentStatusMessage = null, error = e.message)
                    }
                }
            }
        }
    }

    /**
     * Cancels the active AI response/agent execution.
     * Safely resets loading and progress status without displaying an error message.
     */
    fun cancelCurrentMessage() {
        currentMessageJob?.cancel()
        currentMessageJob = null
        _uiState.update {
            it.copy(isLoading = false, agentStatusMessage = null)
        }
    }

    // ── Voice recording ──────────────────────────────────────────────────────

    fun onRecordStart() {
        if (_uiState.value.voiceState != VoiceState.IDLE) return
        try {
            audioRecorder.start()
            _uiState.update { it.copy(voiceState = VoiceState.RECORDING) }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start recording: ${e.message}", e)
            _uiState.update { it.copy(error = context.getString(R.string.error_recording_failed, e.message ?: "")) }
        }
    }

    fun onRecordStop() {
        if (_uiState.value.voiceState != VoiceState.RECORDING) return
        val file = audioRecorder.stop() ?: run {
            _uiState.update { it.copy(voiceState = VoiceState.IDLE) }
            return
        }
        _uiState.update { it.copy(voiceState = VoiceState.TRANSCRIBING) }
        viewModelScope.launch {
            transcribeAudioUseCase(file)
                .onSuccess { text ->
                    _uiState.update { it.copy(voiceState = VoiceState.IDLE, inputText = text) }
                }
                .onFailure { e ->
                    Log.e(TAG, "Transcription failed: ${e.message}", e)
                    _uiState.update { it.copy(voiceState = VoiceState.IDLE, error = e.message) }
                }
            file.delete()
        }
    }

    fun onRecordCancel() {
        if (_uiState.value.voiceState != VoiceState.RECORDING) return
        audioRecorder.stop()?.delete()
        _uiState.update { it.copy(voiceState = VoiceState.IDLE) }
    }

    fun onMicPermissionDenied() {
        _uiState.update { it.copy(error = context.getString(R.string.error_mic_permission)) }
    }

    fun dismissError() {
        _uiState.update { it.copy(error = null) }
    }

    override fun onCleared() {
        super.onCleared()
        audioRecorder.release()
    }

    companion object {
        private const val TAG = "ChatViewModel"
    }
}
