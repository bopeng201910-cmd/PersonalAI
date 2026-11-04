package com.personal.personalai.presentation.chat

import com.personal.personalai.domain.model.Conversation
import com.personal.personalai.domain.model.Message
import com.personal.personalai.domain.tools.PermissionBroker
import com.personal.personalai.domain.tools.UserInputBroker

/** Three mutually-exclusive voice-input states. Using a single enum prevents
 *  illegal combinations such as recording + transcribing simultaneously. */
enum class VoiceState { IDLE, RECORDING, TRANSCRIBING }

data class ChatUiState(
    val activeConversationId: String = "default",
    val activeConversationTitle: String = "Current Chat",
    val conversations: List<Conversation> = emptyList(),
    val isHistoryDrawerOpen: Boolean = false,
    val messages: List<Message> = emptyList(),
    val inputText: String = "",
    val isLoading: Boolean = false,
    val error: String? = null,
    val voiceState: VoiceState = VoiceState.IDLE,
    val continuousVoiceState: com.personal.personalai.domain.audio.ContinuousVoiceState = com.personal.personalai.domain.audio.ContinuousVoiceState.STANDBY,
    val isBluetoothConnected: Boolean = false,
    val isContinuousModeActive: Boolean = false,
    val conversationMode: com.personal.personalai.domain.audio.ConversationMode = com.personal.personalai.domain.audio.ConversationMode.NATURAL_CONVERSATION,
    val agentStatusMessage: String? = null,
    /** Non-null when the agent loop is paused waiting for the user to answer a question. */
    val pendingInputRequest: UserInputBroker.Request? = null,
    /** Non-null when the agent loop is paused waiting for the user to grant a permission. */
    val pendingPermissionRequest: PermissionBroker.Request? = null,
    /** Number of pending candidate memories waiting for daily review. */
    val pendingReviewCount: Int = 0
)
