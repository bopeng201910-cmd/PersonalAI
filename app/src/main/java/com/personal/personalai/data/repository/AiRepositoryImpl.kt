package com.personal.personalai.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import com.personal.personalai.data.datasource.ai.WhisperDataSource
import com.personal.personalai.domain.model.Memory
import com.personal.personalai.domain.repository.AiRepository
import com.personal.personalai.domain.router.LumioAiRouter
import com.personal.personalai.domain.router.LumioTaskType
import com.personal.personalai.domain.tools.AgentResponse
import com.personal.personalai.domain.tools.AgentTool
import com.personal.personalai.presentation.settings.PreferencesKeys
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import java.io.File
import javax.inject.Inject

/**
 * Central [AiRepository] implementation.
 * All model text/tool invocations are strictly routed through [LumioAiRouter] to enforce
 * single-gateway governance, local-first policies, and task tracking.
 */
class AiRepositoryImpl @Inject constructor(
    private val lumioAiRouter: LumioAiRouter,
    private val whisperDataSource: WhisperDataSource,
    private val dataStore: DataStore<Preferences>,
) : AiRepository {

    override suspend fun sendMessageWithTools(
        conversationItems: JSONArray,
        memories: List<Memory>,
        tools: List<AgentTool>,
        previousResponseId: String?,
        taskType: LumioTaskType
    ): Result<AgentResponse> {
        return lumioAiRouter.routeAndExecute(
            conversationItems = conversationItems,
            memories = memories,
            tools = tools,
            previousResponseId = previousResponseId,
            taskType = taskType
        )
    }

    override suspend fun transcribeAudio(audioFile: File): Result<String> {
        // Alpha 1.0 STRICT LOCAL-ONLY POLICY:
        // Cloud Whisper API calls are blocked to prevent audio uploads and cloud network traffic.
        return Result.failure(
            Exception("Voice transcription is disabled in Alpha 1.0 local-only mode.")
        )
    }
}
