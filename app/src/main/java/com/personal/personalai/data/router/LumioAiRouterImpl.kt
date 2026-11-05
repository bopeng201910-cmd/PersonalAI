package com.personal.personalai.data.router

import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import com.personal.personalai.data.datasource.ai.LocalLlmDataSource
import com.personal.personalai.data.datasource.ai.OllamaDataSource
import com.personal.personalai.data.datasource.ai.OpenAiDataSource
import com.personal.personalai.domain.model.Memory
import com.personal.personalai.domain.router.LumioAiRouter
import com.personal.personalai.domain.router.LumioTaskType
import com.personal.personalai.domain.tools.AgentResponse
import com.personal.personalai.domain.tools.AgentTool
import com.personal.personalai.presentation.settings.PreferencesKeys
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LumioAiRouterImpl @Inject constructor(
    private val openAiDataSource: OpenAiDataSource,
    private val ollamaDataSource: OllamaDataSource,
    private val localLlmDataSource: LocalLlmDataSource,
    private val dataStore: DataStore<Preferences>
) : LumioAiRouter {

    companion object {
        private const val TAG = "LumioAiRouter"
    }

    override suspend fun routeAndExecute(
        conversationItems: JSONArray,
        memories: List<Memory>,
        tools: List<AgentTool>,
        previousResponseId: String?,
        taskType: LumioTaskType
    ): Result<AgentResponse> {
        // Alpha 1.0 STRICT LOCAL-ONLY POLICY:
        // All AI requests are strictly bound to on-device execution.
        // Cloud AI (OpenAI Responses / chat completions / remote Ollama) is completely blocked.
        // No stored preference or missing key can bypass this policy.
        Log.d(TAG, "Routing model invocation (Alpha 1.0 Strict Local-Only): task=$taskType")
        return executeLocalModel(conversationItems, memories, tools, taskType)
    }

    private suspend fun executeLocalModel(
        conversationItems: JSONArray,
        memories: List<Memory>,
        tools: List<AgentTool>,
        taskType: LumioTaskType
    ): Result<AgentResponse> {
        return try {
            localLlmDataSource.sendMessageWithTools(conversationItems, memories, tools)
        } catch (cancellation: kotlinx.coroutines.CancellationException) {
            throw cancellation
        } catch (oom: OutOfMemoryError) {
            Log.e(TAG, "Local model OOM during task $taskType! Refusing silent fallback to cloud.")
            Result.success(
                AgentResponse.Text(
                    "Device memory is low while running local AI. Please close other apps and retry."
                )
            )
        } catch (e: Exception) {
            Log.e(TAG, "Local model failed: ${e.message}")
            Result.failure(e)
        }
    }

    private suspend fun executeOllamaModel(
        prefs: Preferences,
        conversationItems: JSONArray,
        memories: List<Memory>,
        tools: List<AgentTool>,
        taskType: LumioTaskType
    ): Result<AgentResponse> {
        val url = prefs[PreferencesKeys.OLLAMA_URL].orEmpty().trim()
        val model = prefs[PreferencesKeys.OLLAMA_MODEL].orEmpty().trim()
        if (url.isBlank() || model.isBlank()) {
            return Result.success(
                AgentResponse.Text("Ollama server URL or model not configured in Settings → Dev (Ollama).")
            )
        }
        return ollamaDataSource.sendMessageWithTools(url, model, conversationItems, memories, tools)
    }

    private suspend fun executeOpenAiModel(
        prefs: Preferences,
        conversationItems: JSONArray,
        memories: List<Memory>,
        tools: List<AgentTool>,
        previousResponseId: String?,
        taskType: LumioTaskType
    ): Result<AgentResponse> {
        val apiKey = prefs[PreferencesKeys.API_KEY].orEmpty().trim()
        if (apiKey.isBlank()) {
            return Result.success(
                AgentResponse.Text("I need an OpenAI API key to respond. Please configure one in Settings → AI Backend.")
            )
        }
        return openAiDataSource.sendMessageWithTools(
            apiKey = apiKey,
            conversationItems = conversationItems,
            memories = memories,
            tools = tools,
            previousResponseId = previousResponseId
        )
    }
}
