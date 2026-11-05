package com.personal.personalai.localllm.engine

import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.ExperimentalApi
import com.google.ai.edge.litertlm.ExperimentalFlags
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.ToolCall
import com.personal.personalai.localllm.api.LocalLlmMessage
import com.personal.personalai.localllm.api.LocalLlmResponse
import com.personal.personalai.localllm.api.LocalLlmTool
import com.personal.personalai.localllm.api.LocalLlmToolCallMessage
import com.personal.personalai.localllm.api.LocalToolCall
import kotlinx.coroutines.flow.toList
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.util.UUID

/**
 * On-device LLM inference engine backed by [com.google.ai.edge.litertlm].
 *
 * - GPU backend is preferred; falls back to CPU if unavailable.
 * - Requires Android 12 (API 31); [isSupported] must be checked before calling [generate].
 * - The [Engine] is created lazily and cached by model path.
 * - **Tool calling** uses LiteRT-LM's native tool-provider interface with automatic execution
 *   disabled, so the app can still own permissions, `ask_user`, and other side effects.
 *   A text-parser fallback is kept as a safety net for model/library mismatches.
 */
class LiteRtLlmEngine(private val context: Context) {

    companion object {
        private const val TAG = "LiteRtLlmEngine"
        /** Minimum Android API level required by Lumio Alpha 1.0. */
        const val MIN_API_LEVEL = 36  // Android 16

        private const val TOP_K       = 64
        private const val TOP_P       = 0.95
        private const val TEMPERATURE = 1.0
        // 8192 gives ~4× more context than the default 4096 while staying within Gemma 4's
        // supported range (up to 32K). Increase further only if your device has enough RAM.
        private const val MAX_TOKENS  = 16256
        // Keep CPU fallback from monopolizing all cores and starving the UI thread.
        private val CPU_FALLBACK_THREADS =
            (Runtime.getRuntime().availableProcessors() / 2).coerceIn(2, 4)
    }

    private var currentModelPath: String? = null
    private var currentBackendLabel: String? = null
    private var engine: Engine? = null
    private var currentConversation: com.google.ai.edge.litertlm.Conversation? = null
    private var currentConversationMessages: List<LocalLlmMessage> = emptyList()
    private var currentVisibleConversationMessages: List<LocalLlmMessage> = emptyList()
    private var currentSystemReuseKey: String? = null
    private var currentToolsSignature: String? = null

    /** Returns true if this device meets the minimum OS requirement for LiteRT-LM. */
    fun isSupported(): Boolean = Build.VERSION.SDK_INT >= MIN_API_LEVEL

    /**
     * Initializes and loads [modelPath] into the engine runtime, confirming health.
     */
    @Synchronized
    fun initModel(modelPath: String): Result<Unit> {
        if (!isSupported()) {
            return Result.failure(Exception("On-device AI requires Android 16 (API 36+) for Lumio Alpha 1.0."))
        }
        return try {
            ensureEngine(modelPath)
            if (engine != null) Result.success(Unit)
            else Result.failure(Exception("Failed to initialize LiteRT engine for $modelPath"))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Generates a response for the [messages] conversation.
     *
     * Tool schemas are passed separately in [tools]. When the model returns a structured
     * tool call, [generate] returns [LocalLlmResponse.ToolCalls]; otherwise it returns
     * [LocalLlmResponse.Text].
     *
     * Tokens are streamed to [onToken] as they arrive.
     *
     * @param messages Full conversation: first message is SYSTEM (prompt + memories), then
     *   alternating USER / ASSISTANT / TOOL history, with the final USER turn sent live.
     * @param tools Tool definitions exposed to LiteRT-LM's tool manager.
     * @param modelPath Absolute path to the downloaded `.litertlm` model file.
     * @param onToken Optional per-token streaming callback.
     */
    @OptIn(ExperimentalApi::class)
    suspend fun generate(
        messages: List<LocalLlmMessage>,
        tools: List<LocalLlmTool>,
        modelPath: String,
        onToken: (String) -> Unit = {}
    ): LocalLlmResponse {
        if (!isSupported()) {
            return LocalLlmResponse.Error(
                "On-device AI requires Android 16 (API 36+). " +
                "Your device is running API ${Build.VERSION.SDK_INT}."
            )
        }
        return try {
            val generationStartMs = SystemClock.elapsedRealtime()
            ensureEngine(modelPath)
            val eng = engine ?: return LocalLlmResponse.Error("Failed to load model.")

            val systemContents = buildSystemContents(messages)
            val nonSystemMessages = messages.filter { it.role != LocalLlmMessage.Role.SYSTEM }
            val systemReuseKey = buildSystemReuseKey(messages)
            val toolsSignature = buildToolsSignature(tools)
            val toolProviders   = buildToolProviders(tools) { name, _ ->
                JSONObject()
                    .put("error", "Automatic tool execution is disabled for $name")
                    .toString()
            }

            val rawPrefixMatch =
                currentConversation != null &&
                    currentSystemReuseKey == systemReuseKey &&
                    currentToolsSignature == toolsSignature &&
                    hasMessagePrefix(
                        messages = nonSystemMessages,
                        prefix = currentConversationMessages
                    )
            val visiblePrefixMatch =
                !rawPrefixMatch &&
                    currentConversation != null &&
                    currentSystemReuseKey == systemReuseKey &&
                    currentToolsSignature == toolsSignature &&
                    hasMessagePrefix(
                        messages = nonSystemMessages,
                        prefix = currentVisibleConversationMessages
                    )
            val reusableConversation = rawPrefixMatch || visiblePrefixMatch

            val initialPlan = if (reusableConversation) null else planInitialConversation(messages)

            var conversationMs = 0L
            val conversation = if (reusableConversation) {
                currentConversation!!
                    .also {
                        Log.d(
                            TAG,
                            "Reusing LiteRT conversation backend=${currentBackendLabel ?: "unknown"} " +
                                "mode=${if (rawPrefixMatch) "raw" else "visible"} " +
                                "knownMessages=${currentConversationMessages.size} " +
                                "visibleMessages=${currentVisibleConversationMessages.size} " +
                                "incomingMessages=${nonSystemMessages.size}"
                        )
                    }
            } else {
                closeCurrentConversation()
                val config = ConversationConfig(
                    systemInstruction = systemContents,
                    initialMessages = buildHistoryMessages(initialPlan!!.historyMessages),
                    tools = toolProviders,
                    samplerConfig = SamplerConfig(topK = TOP_K, topP = TOP_P, temperature = TEMPERATURE),
                    automaticToolCalling = false
                )

                val conversationStartMs = SystemClock.elapsedRealtime()
                ExperimentalFlags.enableConversationConstrainedDecoding = true
                val createdConversation = try {
                    eng.createConversation(config)
                } finally {
                    ExperimentalFlags.enableConversationConstrainedDecoding = false
                }
                currentConversation = createdConversation
                currentConversationMessages = initialPlan.historyMessages
                currentVisibleConversationMessages =
                    projectVisibleMessages(initialPlan.historyMessages)
                currentSystemReuseKey = systemReuseKey
                currentToolsSignature = toolsSignature
                conversationMs = SystemClock.elapsedRealtime() - conversationStartMs
                createdConversation
            }

            val newLocalMessages = if (reusableConversation) {
                nonSystemMessages.drop(
                    if (rawPrefixMatch) {
                        currentConversationMessages.size
                    } else {
                        currentVisibleConversationMessages.size
                    }
                )
            } else {
                initialPlan!!.liveMessages
            }
            val outboundMessages = buildOutboundMessages(newLocalMessages)
            if (outboundMessages.isEmpty()) {
                return LocalLlmResponse.Error("No new message to send to the local conversation.")
            }

            val assembled = StringBuilder()
            val nativeToolCalls = mutableListOf<LocalToolCall>()
            val inferenceStartMs = SystemClock.elapsedRealtime()
            outboundMessages.forEachIndexed { index, outboundMessage ->
                conversation.sendMessageAsync(outboundMessage)
                    .toList()
                    .forEach { msg ->
                        if (msg.toolCalls.isNotEmpty()) {
                            nativeToolCalls += msg.toolCalls.map { call ->
                                LocalToolCall(
                                    callId = UUID.randomUUID().toString(),
                                    name = call.name,
                                    argumentsJson = JSONObject(call.arguments).toString()
                                )
                            }
                        }

                        val text = msg.contents
                            .contents
                            .filterIsInstance<Content.Text>()
                            .joinToString("") { it.text }
                        if (index == outboundMessages.lastIndex) {
                            onToken(text)
                            assembled.append(text)
                        }
                    }
            }
            currentConversationMessages = currentConversationMessages + newLocalMessages
            currentVisibleConversationMessages =
                currentVisibleConversationMessages + projectVisibleMessages(newLocalMessages)
            val inferenceMs = SystemClock.elapsedRealtime() - inferenceStartMs
            val totalMs = SystemClock.elapsedRealtime() - generationStartMs
            Log.d(
                TAG,
                "generate backend=${currentBackendLabel ?: "unknown"} " +
                    "conversationMs=$conversationMs inferenceMs=$inferenceMs totalMs=$totalMs " +
                    "history=${currentConversationMessages.size} tools=${tools.size} " +
                    "toolCalls=${nativeToolCalls.size} chars=${assembled.length}"
            )

            if (nativeToolCalls.isNotEmpty()) {
                currentConversationMessages =
                    currentConversationMessages +
                        buildAssistantToolCallMessages(nativeToolCalls)
                LocalLlmResponse.ToolCalls(nativeToolCalls)
            } else {
                val fullText = assembled.toString().trim()
                val toolCalls = parseToolCalls(fullText)
                if (toolCalls != null) {
                    currentConversationMessages =
                        currentConversationMessages +
                            buildAssistantToolCallMessages(toolCalls)
                    LocalLlmResponse.ToolCalls(toolCalls)
                } else {
                    val assistantMessage = LocalLlmMessage(
                        role = LocalLlmMessage.Role.ASSISTANT,
                        content = fullText
                    )
                    currentConversationMessages =
                        currentConversationMessages + assistantMessage
                    currentVisibleConversationMessages =
                        currentVisibleConversationMessages + assistantMessage
                    LocalLlmResponse.Text(fullText)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Local generation failed: ${e.message}", e)
            closeCurrentConversation()
            LocalLlmResponse.Error(e.message ?: "Unknown inference error")
        }
    }

    /** Releases the current conversation session without destroying the engine. */
    @Synchronized
    private fun closeCurrentConversation() {
        currentConversation?.close()
        currentConversation = null
        currentConversationMessages = emptyList()
        currentVisibleConversationMessages = emptyList()
        currentSystemReuseKey = null
        currentToolsSignature = null
    }

    /** Releases the conversation and the native engine. Call when switching away from Local. */
    @Synchronized
    fun close() {
        closeCurrentConversation()
        engine?.close()
        engine = null
        currentModelPath = null
    }

    // ── Engine session management ─────────────────────────────────────────────

    @Synchronized
    private fun ensureEngine(modelPath: String) {
        if (currentModelPath == modelPath && engine != null) return
        closeCurrentConversation()
        engine?.close()
        engine = null
        currentModelPath = null
        currentBackendLabel = null

        val cacheDir = context.cacheDir.absolutePath

        val newEngine = try {
            val createStartMs = SystemClock.elapsedRealtime()
            Engine(
                EngineConfig(
                    modelPath = modelPath,
                    backend = Backend.GPU(),
                    maxNumTokens = MAX_TOKENS,
                    cacheDir = cacheDir
                )
            ).also {
                currentBackendLabel = "GPU"
                Log.d(TAG, "Created LiteRT engine with GPU backend in ${SystemClock.elapsedRealtime() - createStartMs}ms")
            }
        } catch (gpuError: Exception) {
            // GPU not available — fall back to CPU
            val createStartMs = SystemClock.elapsedRealtime()
            Engine(
                EngineConfig(
                    modelPath = modelPath,
                    backend = Backend.CPU(numOfThreads = CPU_FALLBACK_THREADS),
                    maxNumTokens = MAX_TOKENS,
                    cacheDir = cacheDir
                )
            ).also {
                currentBackendLabel = "CPU($CPU_FALLBACK_THREADS)"
                Log.w(
                    TAG,
                    "GPU backend unavailable, using CPU($CPU_FALLBACK_THREADS). " +
                        "Reason: ${gpuError.message}. " +
                        "CreateMs=${SystemClock.elapsedRealtime() - createStartMs}"
                )
            }
        }
        val initStartMs = SystemClock.elapsedRealtime()
        newEngine.initialize()
        Log.d(
            TAG,
            "Initialized LiteRT engine backend=${currentBackendLabel ?: "unknown"} " +
                "initMs=${SystemClock.elapsedRealtime() - initStartMs}"
        )
        engine = newEngine
        currentModelPath = modelPath
    }

    // ── Tool call parsing ─────────────────────────────────────────────────────

    /**
     * Detects and parses tool calls from model output.
     *
     * Three strategies are tried in order:
     * 1. **Strict JSON** — `{"tool_calls":[{"name":"...","arguments":{...}}]}`
     * 2. **Gemma 4 native** — `<|tool_call>call:name{args}<tool_call|>`
     * 3. **Regex fallback** — handles malformed JSON like `"name":"send":"send_sms"`
     *    where the model almost followed our format but produced invalid JSON.
     *
     * Returns null when no tool call is detected (plain text response).
     */
    private fun parseToolCalls(text: String): List<LocalToolCall>? {
        if (text.contains("\"tool_calls\"")) {
            parseJsonToolCalls(text)?.let { return it }
        }
        if (text.contains("<|tool_call>")) {
            parseGemmaToolCalls(text)?.let { return it }
        }
        if (text.contains("\"name\"") && text.contains("\"arguments\"")) {
            parseRegexToolCalls(text)?.let { return it }
        }
        return null
    }

    private fun parseJsonToolCalls(text: String): List<LocalToolCall>? {
        return try {
            val cleaned = text.replace(Regex("```[a-z]*\\s*"), "").replace("```", "").trim()
            val jsonStr = extractBalancedBraces(cleaned, cleaned.indexOf('{')) ?: return null
            val root = JSONObject(jsonStr)
            val callsArr: JSONArray = root.optJSONArray("tool_calls") ?: return null
            (0 until callsArr.length()).mapNotNull { i ->
                val call = callsArr.optJSONObject(i) ?: return@mapNotNull null
                val name = call.optString("name").ifBlank { return@mapNotNull null }
                val argsJson = when (val args = call.opt("arguments")) {
                    is JSONObject -> args.toString()
                    is String     -> args
                    else          -> "{}"
                }
                LocalToolCall(callId = UUID.randomUUID().toString(), name = name, argumentsJson = argsJson)
            }.takeIf { it.isNotEmpty() }
        } catch (_: Exception) { null }
    }

    /**
     * Regex-based fallback for malformed JSON the model sometimes produces.
     *
     * Handles cases like `"name":"send":"send_sms"` (doubled value — takes the last
     * quoted string) and text mixed around the JSON fragment.
     */
    private fun parseRegexToolCalls(text: String): List<LocalToolCall>? {
        // Matches "name" : (optional extra "X":)* "actual_name"
        val nameRegex = Regex(""""name"\s*:\s*(?:"[^"]*"\s*:\s*)*"([^"]+)"""")
        val calls = mutableListOf<LocalToolCall>()

        for (nameMatch in nameRegex.findAll(text)) {
            val toolName = nameMatch.groupValues[1]
            val afterName = nameMatch.range.last

            // Find "arguments" after this name match
            val argsKeyIdx = text.indexOf("\"arguments\"", afterName)
            if (argsKeyIdx == -1) continue

            // Find the opening '{' of the arguments object
            val colonAfterArgs = text.indexOf(':', argsKeyIdx + "\"arguments\"".length)
            if (colonAfterArgs == -1) continue
            val braceIdx = text.indexOf('{', colonAfterArgs)
            if (braceIdx == -1) continue

            val argsBlock = extractBalancedBraces(text, braceIdx) ?: continue
            val argsJson = try { JSONObject(argsBlock); argsBlock } catch (_: Exception) { "{}" }

            calls.add(LocalToolCall(
                callId = UUID.randomUUID().toString(),
                name = toolName,
                argumentsJson = argsJson
            ))
        }

        return calls.takeIf { it.isNotEmpty() }
    }

    /**
     * Parses Gemma 4's native `<|tool_call>call:name{args}<tool_call|>` format.
     *
     * The args block typically has an unquoted outer wrapper around the real JSON args, e.g.:
     *   `{phone_number:"05553552473",message:"hi"}` — almost-JSON, unquoted keys
     *   `{message:{"phone_number":"...", "message":"..."}}` — inner JSON is the real args
     *
     * Strategy: try the block as JSON; if that fails, find the first `{"` sub-object.
     */
    private fun parseGemmaToolCalls(text: String): List<LocalToolCall>? {
        val calls = mutableListOf<LocalToolCall>()
        val prefix = "<|tool_call>call:"
        var searchFrom = 0

        while (true) {
            val start = text.indexOf(prefix, searchFrom)
            if (start == -1) break

            val nameStart = start + prefix.length
            val braceStart = text.indexOf('{', nameStart)
            if (braceStart == -1) break

            val toolName = text.substring(nameStart, braceStart).trim()
            if (toolName.isBlank()) { searchFrom = braceStart + 1; continue }

            val argsBlock = extractBalancedBraces(text, braceStart)
            if (argsBlock == null) { searchFrom = braceStart + 1; continue }

            val argsJson = extractJsonArgsFromGemmaBlock(argsBlock)
            calls.add(LocalToolCall(callId = UUID.randomUUID().toString(), name = toolName, argumentsJson = argsJson))
            searchFrom = braceStart + argsBlock.length
        }

        return calls.takeIf { it.isNotEmpty() }
    }

    /**
     * Extracts usable JSON arguments from a Gemma args block like
     * `{message:{"phone_number":"...","message":"..."}}`.
     *
     * Tries in order:
     * 1. The whole block as JSON (e.g., already `{"key":"value"}`)
     * 2. The first inner `{"` sub-object (real args wrapped in an outer unquoted key)
     * 3. Empty args `{}` as a last resort
     */
    private fun extractJsonArgsFromGemmaBlock(argsBlock: String): String {
        // 1. Try as-is
        try { JSONObject(argsBlock); return argsBlock } catch (_: Exception) {}

        // 2. Find first properly-quoted JSON sub-object: look for {"
        var searchAt = 0
        while (true) {
            val quotedStart = argsBlock.indexOf("{\"", searchAt)
            if (quotedStart == -1) break
            val inner = extractBalancedBraces(argsBlock, quotedStart) ?: break
            try { JSONObject(inner); return inner } catch (_: Exception) {}
            searchAt = quotedStart + 1
        }

        return "{}"
    }

    /**
     * Extracts the balanced `{...}` block starting at [start] in [text].
     * Returns null if braces are unbalanced.
     */
    private fun extractBalancedBraces(text: String, start: Int): String? {
        if (start < 0 || start >= text.length) return null
        var depth = 0
        for (i in start until text.length) {
            when (text[i]) {
                '{' -> depth++
                '}' -> { depth--; if (depth == 0) return text.substring(start, i + 1) }
            }
        }
        return null
    }

    // ── Prompt helpers ────────────────────────────────────────────────────────

    private data class ConversationPlan(
        val historyMessages: List<LocalLlmMessage>,
        val liveMessages: List<LocalLlmMessage>
    )

    private fun planInitialConversation(messages: List<LocalLlmMessage>): ConversationPlan {
        val nonSystemMessages = messages.filter { it.role != LocalLlmMessage.Role.SYSTEM }
        require(nonSystemMessages.isNotEmpty()) { "No conversation messages to send" }

        val liveStart = when (nonSystemMessages.last().role) {
            LocalLlmMessage.Role.TOOL ->
                nonSystemMessages.indexOfLast { it.role != LocalLlmMessage.Role.TOOL } + 1
            LocalLlmMessage.Role.USER ->
                nonSystemMessages.lastIndex
            else ->
                error("The last local message must be a USER or TOOL message.")
        }

        return ConversationPlan(
            historyMessages = nonSystemMessages.take(liveStart),
            liveMessages = nonSystemMessages.drop(liveStart)
        )
    }

    private fun buildOutboundMessages(messages: List<LocalLlmMessage>): List<Message> {
        if (messages.isEmpty()) return emptyList()
        return when (messages.first().role) {
            LocalLlmMessage.Role.USER -> {
                val user = messages.singleOrNull()
                    ?: error("Expected a single USER message to send.")
                listOf(Message.user(user.content))
            }
            LocalLlmMessage.Role.TOOL -> {
                val responses = messages
                    .takeWhile { it.role == LocalLlmMessage.Role.TOOL }
                    .flatMap { toolMessage ->
                        toolMessage.toolResponses.map { response ->
                            Content.ToolResponse(
                                name = response.name,
                                response = jsonStringToKotlinValue(response.responseJson)
                            )
                        }
                    }
                if (responses.isEmpty()) emptyList() else listOf(Message.tool(Contents.of(responses)))
            }
            else -> error("Unsupported outbound message role: ${messages.first().role}")
        }
    }

    private fun buildAssistantToolCallMessages(toolCalls: List<LocalToolCall>): List<LocalLlmMessage> =
        toolCalls.map { call ->
            LocalLlmMessage(
                role = LocalLlmMessage.Role.ASSISTANT,
                toolCalls = listOf(
                    LocalLlmToolCallMessage(
                        name = call.name,
                        argumentsJson = call.argumentsJson
                    )
                )
            )
        }

    private fun buildSystemReuseKey(messages: List<LocalLlmMessage>): String =
        messages.firstOrNull { it.role == LocalLlmMessage.Role.SYSTEM }
            ?.content
            ?.replace(Regex("""Current date and time:\s+.*"""), "Current date and time: <dynamic>")
            .orEmpty()

    private fun buildToolsSignature(tools: List<LocalLlmTool>): String =
        tools.joinToString("\n") { tool ->
            "${tool.name}|${tool.description}|${tool.parametersSchemaJson}"
        }

    private fun hasMessagePrefix(
        messages: List<LocalLlmMessage>,
        prefix: List<LocalLlmMessage>
    ): Boolean {
        if (prefix.size > messages.size) return false
        return prefix.indices.all { index -> messages[index] == prefix[index] }
    }

    private fun projectVisibleMessages(messages: List<LocalLlmMessage>): List<LocalLlmMessage> =
        messages.mapNotNull { message ->
            when {
                message.role == LocalLlmMessage.Role.USER && message.content.isNotBlank() -> message
                message.role == LocalLlmMessage.Role.ASSISTANT &&
                    message.content.isNotBlank() &&
                    message.toolCalls.isEmpty() -> message
                else -> null
            }
        }

    /**
     * Builds a [Contents] object for the system instruction from the SYSTEM message
     * in [messages].
     */
    private fun buildSystemContents(messages: List<LocalLlmMessage>): Contents {
        val systemText = messages.firstOrNull { it.role == LocalLlmMessage.Role.SYSTEM }?.content
            ?: return Contents.of("")
        return Contents.of(systemText)
    }

    /**
     * Converts prior USER / ASSISTANT / TOOL turns into [Message] objects for
     * [ConversationConfig.initialMessages]. History is capped at the last 20 messages to fit
     * within the context window when a new conversation must be created.
     */
    private fun buildHistoryMessages(messages: List<LocalLlmMessage>): List<Message> {
        return messages.takeLast(20).mapNotNull { msg ->
            when (msg.role) {
                LocalLlmMessage.Role.USER ->
                    Message.user(msg.content)

                LocalLlmMessage.Role.ASSISTANT -> when {
                    msg.toolCalls.isNotEmpty() -> Message.model(
                        toolCalls = msg.toolCalls.map { toolCall ->
                            ToolCall(
                                name = toolCall.name,
                                arguments = jsonObjectStringToMap(toolCall.argumentsJson)
                            )
                        }
                    )
                    msg.content.isNotBlank() -> Message.model(Contents.of(msg.content))
                    else -> null
                }

                LocalLlmMessage.Role.TOOL -> {
                    val responses = msg.toolResponses.map { response ->
                        Content.ToolResponse(
                            name = response.name,
                            response = jsonStringToKotlinValue(response.responseJson)
                        )
                    }
                    if (responses.isEmpty()) null else Message.tool(Contents.of(responses))
                }

                LocalLlmMessage.Role.SYSTEM -> null
            }
        }
    }

    private fun jsonObjectStringToMap(json: String): Map<String, Any?> =
        runCatching {
            val parsed = JSONTokener(json).nextValue()
            if (parsed is JSONObject) {
                when (val kotlinValue = jsonValueToKotlin(parsed)) {
                    is Map<*, *> -> kotlinValue.entries.associate { (key, value) ->
                        key.toString() to value
                    }
                    else -> emptyMap()
                }
            } else {
                emptyMap()
            }
        }.getOrDefault(emptyMap())

    private fun jsonStringToKotlinValue(json: String): Any? =
        runCatching { jsonValueToKotlin(JSONTokener(json).nextValue()) }
            .getOrDefault(json)

    private fun jsonValueToKotlin(value: Any?): Any? = when (value) {
        null, JSONObject.NULL -> null
        is JSONObject -> buildMap {
            val keys = value.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                put(key, jsonValueToKotlin(value.opt(key)))
            }
        }
        is JSONArray -> buildList {
            for (i in 0 until value.length()) {
                add(jsonValueToKotlin(value.opt(i)))
            }
        }
        else -> value
    }
}
