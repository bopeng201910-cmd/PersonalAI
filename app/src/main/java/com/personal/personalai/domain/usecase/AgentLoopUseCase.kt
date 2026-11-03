package com.personal.personalai.domain.usecase

import android.os.SystemClock
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import com.personal.personalai.domain.model.Memory
import com.personal.personalai.domain.model.Message
import com.personal.personalai.domain.model.MessageRole
import com.personal.personalai.domain.repository.AiRepository
import com.personal.personalai.domain.repository.ChatRepository
import com.personal.personalai.domain.repository.MemoryRepository
import com.personal.personalai.domain.tools.AgentResponse
import com.personal.personalai.domain.tools.AgentTool
import com.personal.personalai.domain.tools.PermissionBroker
import com.personal.personalai.domain.tools.ToolRegistry
import com.personal.personalai.domain.tools.ToolResult
import com.personal.personalai.presentation.settings.PreferencesKeys
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject

sealed class AgentStep {
    /** Emitted each time the LLM decides to call a tool. */
    data class ToolCalling(val toolName: String, val humanReadable: String) : AgentStep()
    /** Emitted when the agent produces a final text response or encounters an error. */
    data class Complete(val result: Result<String>) : AgentStep()
}

private const val MAX_ITERATIONS = 8
private const val TAG = "AgentLoopUseCase"

/** Cold-start history size when no warm chain is available. */
private const val HISTORY_WINDOW = 20

/**
 * Re-use the previous response_id from OpenAI as long as the last call was
 * within this window. Beyond it, we cold-start (last [HISTORY_WINDOW] msgs).
 * Practical effect: OpenAI never accumulates more than one "burst of activity"
 * worth of context per chain.
 */
private const val CHAIN_WARM_MS = 10L * 60L * 1000L

private val DATE_FORMATTER: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")

private fun formatIso(epochMillis: Long): String =
    Instant.ofEpochMilli(epochMillis)
        .atZone(ZoneId.systemDefault())
        .format(DATE_FORMATTER)

/**
 * Orchestrates the multi-turn agent loop:
 * 1. Decide warm-chain vs cold-start from the stored previous_response_id.
 * 2. Build initial input — just the new user message (warm) or last 20 (cold).
 * 3. Call AI. If tool calls → execute, send outputs back chained to the same
 *    response_id, loop. If text → persist response_id, save message, emit.
 *
 * @param backgroundMode When true, skips saving the trigger message to chat history,
 *   uses only background-safe tools, and never reads or writes the chain id (chats
 *   and scheduled-task pings live in separate chains).
 */
class AgentLoopUseCase @Inject constructor(
    private val aiRepository: AiRepository,
    private val chatRepository: ChatRepository,
    private val memoryRepository: MemoryRepository,
    private val toolRegistry: ToolRegistry,
    private val permissionBroker: PermissionBroker,
    private val dataStore: DataStore<Preferences>,
) {
    operator fun invoke(
        message: String,
        backgroundMode: Boolean = false
    ): Flow<AgentStep> = flow {
        val loopStartMs = SystemClock.elapsedRealtime()
        // 1. Save user message to chat (foreground only)
        if (!backgroundMode) {
            chatRepository.saveMessage(Message(content = message, role = MessageRole.USER))
        }

        // 2. Load memories and available tools
        val memories = memoryRepository.getMemories().first()
        val tools = if (backgroundMode) toolRegistry.getBackgroundSafeTools()
                    else toolRegistry.getTools()

        // 3. Decide warm/cold and build the initial input items.
        //    - Warm: a valid previous_response_id within CHAIN_WARM_MS. Send only the new turn.
        //    - Cold: no chain or expired. Send the last HISTORY_WINDOW messages.
        var previousResponseId: String? =
            if (backgroundMode) null else readWarmChainId()
        var conversationItems = if (previousResponseId != null) {
            buildWarmInput(message)
        } else {
            buildColdInput(backgroundMode, message)
        }

        val baseTaskType = if (backgroundMode) {
            com.personal.personalai.domain.router.LumioTaskType.BACKGROUND_REMINDER
        } else {
            com.personal.personalai.domain.router.LumioTaskType.CHAT
        }

        // 4. Agent loop
        repeat(MAX_ITERATIONS) { iteration ->
            val llmStartMs = SystemClock.elapsedRealtime()
            val effectiveTaskType = if (iteration > 0) {
                com.personal.personalai.domain.router.LumioTaskType.TOOL_EXECUTION
            } else {
                baseTaskType
            }

            var response = aiRepository.sendMessageWithTools(
                conversationItems, memories, tools, previousResponseId, effectiveTaskType
            ).getOrNull()

            // Stale-chain fallback: if the very first call fails and we were
            // chaining, retry once cold. Only the first iteration is eligible
            // because subsequent ones use a response_id we just received.
            if (response == null && iteration == 0 && previousResponseId != null) {
                Log.w(TAG, "Chained call failed; retrying cold-start")
                clearChainId()
                previousResponseId = null
                conversationItems = buildColdInput(backgroundMode, message)
                response = aiRepository.sendMessageWithTools(
                    conversationItems, memories, tools, null, effectiveTaskType
                ).getOrNull()
            }

            if (response == null) {
                val e = Exception("AI call failed at iteration ${iteration + 1}")
                Log.e(TAG, "iteration=${iteration + 1} llmFailed after ${SystemClock.elapsedRealtime() - llmStartMs}ms")
                emit(AgentStep.Complete(Result.failure(e)))
                return@flow
            }

            Log.d(
                TAG,
                "iteration=${iteration + 1} llmMs=${SystemClock.elapsedRealtime() - llmStartMs} " +
                    "response=${response::class.simpleName} chained=${previousResponseId != null}"
            )

            when (response) {
                is AgentResponse.ToolCalls -> {
                    // Capture the response_id; the next iteration chains to it
                    // and only needs to send the tool outputs.
                    val nextChainId = response.responseId
                    val toolOutputs = JSONArray()
                    response.calls.forEach { call ->
                        emit(AgentStep.ToolCalling(call.name, humanReadable(call.name, call.arguments)))

                        // Execute the tool
                        val toolStartMs = SystemClock.elapsedRealtime()
                        val rawResult = toolRegistry.execute(call.name, call.arguments)
                        Log.d(
                            TAG,
                            "iteration=${iteration + 1} tool=${call.name} rawToolMs=${SystemClock.elapsedRealtime() - toolStartMs} " +
                                "result=${rawResult::class.simpleName}"
                        )

                        // If the tool requires a permission that hasn't been granted, request it
                        val toolResult = if (rawResult is ToolResult.PermissionDenied) {
                            if (backgroundMode) {
                                ToolResult.Error(
                                    "Permission '${rawResult.permission}' not granted. Open the app to grant it."
                                )
                            } else {
                                val granted = permissionBroker.requestAndAwait(rawResult.permission)
                                if (granted) {
                                    toolRegistry.execute(call.name, call.arguments)
                                } else {
                                    ToolResult.Error(
                                        "User denied permission '${rawResult.permission}'. " +
                                        "Tell the user they can grant it in Settings > Apps > PersonalAI > Permissions."
                                    )
                                }
                            }
                        } else rawResult

                        toolOutputs.put(JSONObject().apply {
                            put("type", "function_call_output")
                            put("call_id", call.id)
                            put("output", toolResult.toJson())
                        })
                    }

                    if (nextChainId != null) {
                        // Stateful path: chain to the response we just got, send only outputs.
                        previousResponseId = nextChainId
                        conversationItems = toolOutputs
                    } else {
                        // Stateless path (Ollama/Local): append function_call + outputs to
                        // the existing conversation array and continue.
                        response.calls.forEach { call ->
                            conversationItems.put(JSONObject().apply {
                                put("type", "function_call")
                                put("call_id", call.id)
                                put("name", call.name)
                                put("arguments", call.arguments)
                            })
                        }
                        for (i in 0 until toolOutputs.length()) {
                            conversationItems.put(toolOutputs.getJSONObject(i))
                        }
                    }
                }

                is AgentResponse.Text -> {
                    if (!backgroundMode) {
                        chatRepository.saveMessage(
                            Message(content = response.text, role = MessageRole.ASSISTANT)
                        )
                        // Persist the chain id only after a successful final response.
                        response.responseId?.let { saveChainId(it) }
                    }
                    Log.d(
                        TAG,
                        "completed totalMs=${SystemClock.elapsedRealtime() - loopStartMs} " +
                            "iterations=${iteration + 1} responseChars=${response.text.length}"
                    )
                    emit(AgentStep.Complete(Result.success(response.text)))
                    return@flow
                }
            }
        }

        emit(AgentStep.Complete(Result.failure(Exception("Agent loop exceeded $MAX_ITERATIONS iterations"))))
    }.flowOn(Dispatchers.IO)

    // ── Input builders ────────────────────────────────────────────────────────

    /** Warm-chain input: just the new user message, with its send-time stamp. */
    private fun buildWarmInput(message: String): JSONArray = JSONArray().apply {
        put(JSONObject().apply {
            put("role", "user")
            put("content", "[Sent at: ${formatIso(System.currentTimeMillis())}]\n\n$message")
        })
    }

    /**
     * Cold-start input: the last [HISTORY_WINDOW] messages from chat history,
     * each user turn stamped with its immutable send-time. The just-saved
     * latest user message is the final item.
     */
    private suspend fun buildColdInput(backgroundMode: Boolean, message: String): JSONArray {
        val items = JSONArray()
        if (backgroundMode) {
            items.put(JSONObject().apply {
                put("role", "user")
                put("content", "[Sent at: ${formatIso(System.currentTimeMillis())}]\n\n$message")
            })
            return items
        }
        val all = chatRepository.getMessages().first()
        val startIdx = (all.size - HISTORY_WINDOW).coerceAtLeast(0)
        all.subList(startIdx, all.size).forEach { msg ->
            items.put(JSONObject().apply {
                put("role", if (msg.role == MessageRole.USER) "user" else "assistant")
                put(
                    "content",
                    if (msg.role == MessageRole.USER) {
                        "[Sent at: ${formatIso(msg.timestamp)}]\n\n${msg.content}"
                    } else {
                        msg.content
                    }
                )
            })
        }
        return items
    }

    // ── Chain-id persistence ──────────────────────────────────────────────────

    private suspend fun readWarmChainId(): String? {
        val prefs = dataStore.data.first()
        val id = prefs[PreferencesKeys.LAST_RESPONSE_ID]?.takeIf { it.isNotBlank() } ?: return null
        val at = prefs[PreferencesKeys.LAST_RESPONSE_AT_MS] ?: return null
        return if (System.currentTimeMillis() - at < CHAIN_WARM_MS) id else null
    }

    private suspend fun saveChainId(id: String) {
        dataStore.edit { prefs ->
            prefs[PreferencesKeys.LAST_RESPONSE_ID] = id
            prefs[PreferencesKeys.LAST_RESPONSE_AT_MS] = System.currentTimeMillis()
        }
    }

    private suspend fun clearChainId() {
        dataStore.edit { prefs ->
            prefs.remove(PreferencesKeys.LAST_RESPONSE_ID)
            prefs.remove(PreferencesKeys.LAST_RESPONSE_AT_MS)
        }
    }

    // ── Human-readable tool descriptions ──────────────────────────────────────

    private fun humanReadable(toolName: String, arguments: String): String {
        val args = runCatching { JSONObject(arguments) }.getOrDefault(JSONObject())
        return when (toolName) {
            "schedule_task" -> {
                val title = args.optString("title", "")
                if (title.isNotBlank()) "📅 Scheduling: $title…" else "📅 Scheduling task…"
            }
            "save_memory" -> "🧠 Saving memory…"
            "forget_memory" -> "🗑️ Forgetting memory…"
            "forget_all_memories" -> "🗑️ Clearing all memories…"
            "open_app" -> {
                val name = args.optString("app_name", args.optString("package_name", ""))
                if (name.isNotBlank()) "🔧 Opening $name…" else "🔧 Opening app…"
            }
            "get_installed_apps" -> "📱 Checking installed apps…"
            "read_contacts" -> "📋 Reading contacts…"
            "get_clipboard" -> "📋 Reading clipboard…"
            "ask_user" -> {
                val q = args.optString("question", "")
                if (q.isNotBlank()) "❓ $q" else "❓ Asking you a question…"
            }
            "send_sms" -> {
                val to = args.optString("phone_number", "")
                if (to.isNotBlank()) "💬 Sending SMS to $to…" else "💬 Sending SMS…"
            }
            "dial_phone" -> {
                val num = args.optString("phone_number", "")
                if (num.isNotBlank()) "📞 Dialing $num…" else "📞 Opening dialer…"
            }
            "set_alarm" -> {
                val h = args.optInt("hour", -1)
                val m = args.optInt("minute", -1)
                if (h >= 0 && m >= 0) "⏰ Setting alarm for %02d:%02d…".format(h, m)
                else "⏰ Setting alarm…"
            }
            "get_battery_level" -> "🔋 Checking battery…"
            "send_notification" -> {
                val t = args.optString("title", "")
                if (t.isNotBlank()) "🔔 Sending notification: $t…" else "🔔 Sending notification…"
            }
            "get_location" -> "📍 Getting your location…"
            "add_calendar_event" -> {
                val t = args.optString("title", "")
                if (t.isNotBlank()) "📅 Adding to calendar: $t…" else "📅 Adding calendar event…"
            }
            "open_url" -> {
                val url = args.optString("url", "")
                if (url.isNotBlank()) "🌐 Opening $url…" else "🌐 Opening URL…"
            }
            else -> "🔧 Running $toolName…"
        }
    }
}
