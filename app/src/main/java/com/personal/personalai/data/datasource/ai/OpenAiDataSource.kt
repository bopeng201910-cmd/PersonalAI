package com.personal.personalai.data.datasource.ai

import com.personal.personalai.domain.model.Memory
import com.personal.personalai.domain.model.Message
import com.personal.personalai.domain.model.MessageRole
import com.personal.personalai.domain.tools.AgentResponse
import com.personal.personalai.domain.tools.AgentTool
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject

/**
 * Handles all communication with the OpenAI Responses API.
 * Delegates HTTP execution and JSON parsing to [ResponsesApiClient].
 * Contains no knowledge of DataStore, mock responses, or key management.
 */
class OpenAiDataSource @Inject constructor(
    private val apiClient: ResponsesApiClient
) {

    companion object {
        private const val OPENAI_URL = "https://api.openai.com/v1/responses"
        private const val MODEL = "gpt-4o"

        /** Used by sendMessageWithTools() — instructs the LLM to use function tools instead of tags. */
        private val TOOLS_SYSTEM_PROMPT_TEMPLATE = """
            You are a helpful personal AI assistant running on an Android app.
            You can answer questions and have natural conversations.
            You have access to real-time web search. When the user asks about current events,
            live data, or anything that benefits from searching the internet, use your web
            search capability to find up-to-date information.

            You have access to powerful tools that let you take real actions for the user

            Always use tools when the user's intent matches a tool capability. After using a tool,
            confirm the action in your final text response.

            CRITICAL — Asking questions: You must NEVER ask the user a question in your text
            response. If you need any information to complete a task (a time, a name, a preference,
            a confirmation), you MUST call the ask_user tool instead. Only use your text response
            for final answers and confirmations after all necessary information has been gathered.

            Every user message starts with a [Sent at: yyyy-MM-dd'T'HH:mm:ss] line
            in device local time. Treat the most recent one as the current time.
            Use these stamps for any reasoning that depends on "now", relative time,
            or how long ago an earlier message was sent.
            {{MEMORIES_SECTION}}
        """.trimIndent()
    }

    /**
     * Agent-loop method. Sends accumulated [conversationItems] (Responses API format) and
     * includes function tool definitions. Returns either a text response or function calls.
     */
    suspend fun sendMessageWithTools(
        apiKey: String,
        conversationItems: JSONArray,
        memories: List<Memory>,
        tools: List<AgentTool>,
        previousResponseId: String? = null,
    ): Result<AgentResponse> = withContext(Dispatchers.IO) {
        try {
            val toolsArray = JSONArray()
            toolsArray.put(JSONObject().put("type", "web_search_preview"))
            tools.forEach { tool ->
                toolsArray.put(JSONObject().apply {
                    put("type", "function")
                    put("name", tool.name)
                    put("description", tool.description)
                    put("parameters", tool.parametersSchema())
                })
            }

            val requestBody = JSONObject().apply {
                put("model", MODEL)
                put("instructions", buildToolsSystemPrompt(memories))
                put("tools", toolsArray)
                put("input", conversationItems)
                if (previousResponseId != null) {
                    put("previous_response_id", previousResponseId)
                }
            }

            apiClient.executeRequest(
                url = OPENAI_URL,
                apiKey = apiKey,
                requestBody = requestBody,
                provider = "openai",
                model = MODEL,
                apiType = "responses",
            ) { responseBody ->
                apiClient.parseAgentResponse(responseBody)
            }
        } catch (cancellation: kotlinx.coroutines.CancellationException) {
            throw cancellation
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // ── System prompts ────────────────────────────────────────────────────────

    private fun buildToolsSystemPrompt(memories: List<Memory>): String =
        TOOLS_SYSTEM_PROMPT_TEMPLATE
            .replace("{{MEMORIES_SECTION}}", apiClient.buildMemoriesSection(memories))
}
