package com.personal.personalai.domain.usecase

import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import com.personal.personalai.data.local.dao.MessageDao
import com.personal.personalai.domain.model.CandidateMemory
import com.personal.personalai.domain.model.CandidateStatus
import com.personal.personalai.domain.model.MemoryCategory
import com.personal.personalai.domain.model.MemorySourceType
import com.personal.personalai.domain.repository.AiRepository
import com.personal.personalai.domain.repository.CandidateMemoryRepository
import com.personal.personalai.domain.tools.AgentResponse
import com.personal.personalai.presentation.settings.PreferencesKeys
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject

class ExtractCandidateMemoriesUseCase @Inject constructor(
    private val messageDao: MessageDao,
    private val candidateMemoryRepository: CandidateMemoryRepository,
    private val aiRepository: AiRepository,
    private val dataStore: DataStore<Preferences>
) {
    companion object {
        private const val TAG = "ExtractCandidateMemories"
        private val DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")
    }

    suspend operator fun invoke(): Result<Int> = runCatching {
        val prefs = dataStore.data.first()
        val lastReviewedId = prefs[PreferencesKeys.LAST_REVIEWED_MESSAGE_ID] ?: 0L

        val newMessages = messageDao.getMessagesAfterId(lastReviewedId)
        if (newMessages.isEmpty()) {
            return@runCatching 0
        }

        // Only process if there's at least one user message
        val hasUserMessage = newMessages.any { it.role == "USER" }
        if (!hasUserMessage) {
            // Update cursor since there's no user content to extract
            val maxId = newMessages.maxOf { it.id }
            dataStore.edit { it[PreferencesKeys.LAST_REVIEWED_MESSAGE_ID] = maxId }
            return@runCatching 0
        }

        // Build conversation transcript
        val transcriptBuilder = StringBuilder()
        for (msg in newMessages) {
            val timeStr = Instant.ofEpochMilli(msg.timestamp)
                .atZone(ZoneId.systemDefault())
                .format(DATE_FORMATTER)
            transcriptBuilder.append("[${msg.role}] (id=${msg.id}, time=$timeStr):\n")
            transcriptBuilder.append(msg.content.trim())
            transcriptBuilder.append("\n\n")
        }

        val extractionPrompt = """
            You are an expert AI assistant performing Daily Memory Review.
            Analyze the following recent conversation transcript between the user and assistant.
            Extract any valuable pieces of information that are worthy of long-term retention into a JSON array.

            Each item in the array must be a JSON object with:
            - "content": A concise, clear summary of what to remember.
            - "topic": A short category keyword (e.g. "coffee", "career", "workout", "project").
            - "original_snippet": Exact excerpt or quotation from the dialogue illustrating this memory.
            - "category": One of: "USER_PREFERENCE", "IMPORTANT_DECISION", "LONG_TERM_GOAL", "PROJECT_PROGRESS", "TODO_ITEM", "OTHER_IMPORTANT".
            - "source_type": Either "EXPLICIT_FACT" (if the user directly stated it) or "INFERRED_PREFERENCE" (if deduced by AI).
            - "source_message_id": The message id (integer) where this was stated.
            - "source_timestamp": The timestamp in millis if available, or 0.

            CRITICAL RULES:
            1. If there is NO long-term valuable information (casual chat, greeting, small talk, confirmations), return strictly an empty JSON array: []
            2. Do NOT invent or hallucinate memories.
            3. Return ONLY a valid JSON array string (starts with [ and ends with ]). Do not enclose in markdown blocks.

            Transcript:
            ${transcriptBuilder.toString()}
        """.trimIndent()

        val conversationItems = JSONArray().apply {
            put(JSONObject().apply {
                put("role", "user")
                put("content", extractionPrompt)
            })
        }

        val response = aiRepository.sendMessageWithTools(
            conversationItems = conversationItems,
            memories = emptyList(),
            tools = emptyList(),
            previousResponseId = null
        ).getOrThrow()

        val text = when (response) {
            is AgentResponse.Text -> response.text
            is AgentResponse.ToolCalls -> ""
        }

        val candidates = parseCandidatesJson(text, newMessages.first().id, newMessages.last().timestamp)

        if (candidates.isNotEmpty()) {
            candidateMemoryRepository.saveCandidates(candidates)
        }

        // Advance cursor reliably only AFTER successful extraction and save
        val newMaxId = newMessages.maxOf { it.id }
        dataStore.edit {
            it[PreferencesKeys.LAST_REVIEWED_MESSAGE_ID] = newMaxId
        }

        candidates.size
    }

    private fun parseCandidatesJson(
        rawText: String,
        fallbackMessageId: Long,
        fallbackTimestamp: Long
    ): List<CandidateMemory> {
        val cleaned = rawText.replace(Regex("```[a-z]*\\s*"), "").replace("```", "").trim()
        val start = cleaned.indexOf('[')
        val end = cleaned.lastIndexOf(']')
        if (start == -1 || end == -1 || start >= end) return emptyList()

        val jsonArrayStr = cleaned.substring(start, end + 1)
        val array = runCatching { JSONArray(jsonArrayStr) }.getOrNull() ?: return emptyList()
        val results = mutableListOf<CandidateMemory>()

        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            val content = obj.optString("content").trim()
            if (content.isBlank()) continue

            val topic = obj.optString("topic", "")
            val originalSnippet = obj.optString("original_snippet", "")
            val catStr = obj.optString("category", MemoryCategory.OTHER_IMPORTANT.name)
            val category = runCatching { MemoryCategory.valueOf(catStr) }.getOrDefault(MemoryCategory.OTHER_IMPORTANT)
            val sourceStr = obj.optString("source_type", MemorySourceType.EXPLICIT_FACT.name)
            val sourceType = runCatching { MemorySourceType.valueOf(sourceStr) }.getOrDefault(MemorySourceType.EXPLICIT_FACT)
            val sourceMsgId = obj.optLong("source_message_id", fallbackMessageId)
            val sourceTs = obj.optLong("source_timestamp", fallbackTimestamp)

            results.add(
                CandidateMemory(
                    content = content,
                    topic = topic,
                    originalSnippet = originalSnippet,
                    category = category,
                    sourceType = sourceType,
                    sourceMessageId = sourceMsgId,
                    sourceTimestamp = if (sourceTs > 0) sourceTs else fallbackTimestamp,
                    status = CandidateStatus.PENDING
                )
            )
        }
        return results
    }
}
