package com.personal.personalai.data.datasource.ai

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import com.personal.personalai.domain.model.Memory
import com.personal.personalai.domain.tools.AgentResponse
import com.personal.personalai.domain.tools.AgentTool
import com.personal.personalai.domain.tools.FunctionCall
import com.personal.personalai.localllm.api.LocalLlmMessage
import com.personal.personalai.localllm.api.LocalLlmResponse
import com.personal.personalai.localllm.api.LocalModel
import com.personal.personalai.localllm.api.LocalLlmTool
import com.personal.personalai.localllm.api.LocalLlmToolCallMessage
import com.personal.personalai.localllm.api.LocalLlmToolResponseMessage
import com.personal.personalai.localllm.download.ModelDownloadManager
import com.personal.personalai.localllm.engine.LiteRtLlmEngine
import com.personal.personalai.presentation.settings.PreferencesKeys
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject

/**
 * Bridges `:app`'s domain types to the `:localllm` module for on-device inference.
 *
 * Tool calling uses LiteRT-LM's native tool-provider path, while preserving the app's
 * existing agent loop so permissions, `ask_user`, and other side effects still stay in
 * app-controlled code.
 */
class LocalLlmDataSource @Inject constructor(
    @ApplicationContext private val context: Context,
    private val engine: LiteRtLlmEngine,
    private val dataStore: DataStore<Preferences>,
    private val responsesApiClient: ResponsesApiClient
) {

    companion object {
        private val SYSTEM_PROMPT_TEMPLATE = """
            You are a helpful personal AI assistant running directly on this Android device.
            You can answer questions and have natural conversations.
            You are a model that can do function calling with the available tools.

            You have access to powerful tools that let you take real actions for the user.
            Always use tools when the user's intent matches a tool capability. After using a
            tool, confirm the action in your final text response.

            CRITICAL — Asking questions: You must NEVER ask the user a question in your text
            response. If you need any information to complete a task (a time, a name, a preference,
            a confirmation), you MUST call the ask_user tool instead. Only use your text response
            for final answers and confirmations after all necessary information has been gathered.
            Asking a question in plain text is not allowed — always use ask_user.

            CRITICAL — Exact values: When a tool returns exact text fields such as `summary`,
            `*_text`, `formatted_*`, phone numbers, coordinates, times, URLs, or other
            digit-heavy values, copy those values exactly in your final answer. Do not
            reformat them, do not insert extra punctuation, and do not spell them differently.
            If both numeric and text versions are present, prefer the text version.
            {{MEMORIES_SECTION}}
            Current date and time: {{DATETIME}}
        """.trimIndent()
    }

    suspend fun sendMessageWithTools(
        conversationItems: JSONArray,
        memories: List<Memory>,
        tools: List<AgentTool>
    ): Result<AgentResponse> = withContext(Dispatchers.IO) {
        if (!engine.isSupported()) {
            return@withContext Result.success(
                AgentResponse.Text(
                    "On-device AI requires Android 16 (API 36+) for Lumio Alpha 1.0. Cloud fallback is strictly blocked."
                )
            )
        }

        val prefs = dataStore.data.first()
        val modelId = prefs[PreferencesKeys.LOCAL_MODEL] ?: LocalModel.GEMMA_4_E2B.modelId
        val model = LocalModel.fromId(modelId) ?: LocalModel.GEMMA_4_E2B

        if (!ModelDownloadManager.isDownloaded(context, model)) {
            return@withContext Result.success(
                AgentResponse.Text(
                    "Model \"${model.displayName}\" is not downloaded or verified yet. " +
                    "Please open Model Center and tap Download & Install."
                )
            )
        }

        try {
            val messages = buildMessageList(conversationItems, memories)
            val localTools = tools.map { tool ->
                LocalLlmTool(
                    name = tool.name,
                    description = tool.description,
                    parametersSchemaJson = tool.parametersSchema().toString()
                )
            }
            val modelPath = ModelDownloadManager.modelFile(context, model).absolutePath
            val response  = engine.generate(messages, localTools, modelPath)

            Result.success(
                when (response) {
                    is LocalLlmResponse.Text -> AgentResponse.Text(response.text)
                    is LocalLlmResponse.ToolCalls -> AgentResponse.ToolCalls(
                        response.calls.map { call ->
                            FunctionCall(
                                id        = call.callId,
                                name      = call.name,
                                arguments = call.argumentsJson
                            )
                        }
                    )
                    is LocalLlmResponse.Error -> AgentResponse.Text(
                        "Local model error: ${response.message}"
                    )
                }
            )
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // ── Conversation conversion ───────────────────────────────────────────────

    /**
     * Converts the Responses-API [conversationItems] JSONArray + [memories] into
     * a flat [List<LocalLlmMessage>] that [LiteRtLlmEngine] understands.
     *
     * Responses-API item shapes handled:
     *  - `{"role":"user","content":"..."}` → USER
     *  - `{"role":"assistant","content":"..."}` → ASSISTANT
     *  - `{"type":"function_call",...}` → ASSISTANT tool-call turn
     *  - `{"type":"function_call_output","output":"..."}` → TOOL response turn
     */
    private fun buildMessageList(
        conversationItems: JSONArray,
        memories: List<Memory>
    ): List<LocalLlmMessage> {
        val messages = mutableListOf<LocalLlmMessage>()
        val callIdToName = linkedMapOf<String, String>()

        messages.add(
            LocalLlmMessage(
                role    = LocalLlmMessage.Role.SYSTEM,
                content = buildSystemPrompt(memories)
            )
        )

        for (i in 0 until conversationItems.length()) {
            val item = conversationItems.getJSONObject(i)
            when {
                item.has("role") -> {
                    val role    = item.getString("role")
                    val content = extractContent(item)
                    if (content.isNotBlank()) {
                        messages.add(
                            LocalLlmMessage(
                                role = if (role == "user") LocalLlmMessage.Role.USER
                                       else LocalLlmMessage.Role.ASSISTANT,
                                content = content
                            )
                        )
                    }
                }
                item.optString("type") == "function_call" -> {
                    val name = item.optString("name", "").takeIf { it.isNotBlank() }
                    if (name != null) {
                        val callId = item.optString("call_id", "")
                        if (callId.isNotBlank()) {
                            callIdToName[callId] = name
                        }
                        messages.add(
                            LocalLlmMessage(
                                role = LocalLlmMessage.Role.ASSISTANT,
                                toolCalls = listOf(
                                    LocalLlmToolCallMessage(
                                        name = name,
                                        argumentsJson = normalizeJsonObject(
                                            item.optString("arguments", "{}")
                                        )
                                    )
                                )
                            )
                        )
                    }
                }
                item.optString("type") == "function_call_output" -> {
                    val output = item.optString("output", "").ifBlank { "{}" }
                    val callId = item.optString("call_id", "")
                    val toolName = callIdToName[callId]
                    if (toolName != null) {
                        messages.add(
                            LocalLlmMessage(
                                role = LocalLlmMessage.Role.TOOL,
                                toolResponses = listOf(
                                    LocalLlmToolResponseMessage(
                                        name = toolName,
                                        responseJson = normalizeJsonValue(output)
                                    )
                                )
                            )
                        )
                    } else if (output.isNotBlank()) {
                        messages.add(
                            LocalLlmMessage(
                                role = LocalLlmMessage.Role.ASSISTANT,
                                content = "Tool result: $output"
                            )
                        )
                    }
                }
            }
        }

        return messages
    }

    /**
     * Extracts text content from a conversation item.
     * Handles both `"content": "string"` and `"content": [{"type":"text","text":"..."}]`.
     */
    private fun extractContent(item: JSONObject): String {
        val raw = item.opt("content") ?: return ""
        return when (raw) {
            is String   -> raw
            is JSONArray -> buildString {
                for (i in 0 until raw.length()) {
                    val part = raw.optJSONObject(i) ?: continue
                    val text = part.optString("text").ifBlank { part.optString("content") }
                    if (text.isNotBlank()) append(text)
                }
            }
            else -> raw.toString()
        }
    }

    suspend fun activateModel(model: LocalModel): Result<Unit> = withContext(Dispatchers.IO) {
        if (!ModelDownloadManager.isDownloaded(context, model)) {
            return@withContext Result.failure(
                Exception("Model '${model.displayName}' is not installed or missing verification manifest.")
            )
        }
        val path = ModelDownloadManager.modelFile(context, model).absolutePath
        engine.initModel(path)
    }

    private fun buildSystemPrompt(memories: List<Memory>): String {
        val now = java.time.Instant.ofEpochMilli(System.currentTimeMillis())
            .atZone(ZoneId.systemDefault())
            .format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"))

        return SYSTEM_PROMPT_TEMPLATE
            .replace("{{MEMORIES_SECTION}}", responsesApiClient.buildMemoriesSection(memories))
            .replace("{{DATETIME}}", now)
    }

    private fun normalizeJsonObject(raw: String): String =
        runCatching {
            val parsed = JSONTokener(raw).nextValue()
            if (parsed is JSONObject) parsed.toString() else JSONObject().toString()
        }.getOrElse { JSONObject().toString() }

    private fun normalizeJsonValue(raw: String): String =
        runCatching {
            when (val parsed = JSONTokener(raw).nextValue()) {
                is JSONObject -> parsed.toString()
                is JSONArray -> parsed.toString()
                else -> JSONObject().put("result", parsed).toString()
            }
        }.getOrElse {
            JSONObject().put("result", raw).toString()
        }
}
