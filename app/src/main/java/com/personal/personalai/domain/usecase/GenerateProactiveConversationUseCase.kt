package com.personal.personalai.domain.usecase

import android.util.Log
import com.personal.personalai.domain.model.Memory
import com.personal.personalai.domain.model.ProactiveCheckInConfig
import com.personal.personalai.domain.repository.MemoryRepository
import com.personal.personalai.domain.router.LumioAiRouter
import com.personal.personalai.domain.router.LumioTaskType
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalTime
import javax.inject.Inject

class GenerateProactiveConversationUseCase @Inject constructor(
    private val memoryRepository: MemoryRepository,
    private val lumioAiRouter: LumioAiRouter
) {
    companion object {
        private const val TAG = "GenerateProactiveUseCase"
    }

    suspend operator fun invoke(config: ProactiveCheckInConfig, currentTime: LocalTime = LocalTime.now()): String? {
        if (!config.isEnabled) return null

        // Check quiet hours
        val startQuiet = runCatching { LocalTime.parse(config.quietHoursStart) }.getOrDefault(LocalTime.of(22, 0))
        val endQuiet = runCatching { LocalTime.parse(config.quietHoursEnd) }.getOrDefault(LocalTime.of(7, 0))

        val inQuietHours = if (startQuiet.isBefore(endQuiet)) {
            currentTime.isAfter(startQuiet) && currentTime.isBefore(endQuiet)
        } else {
            // Over midnight (e.g. 22:00 to 07:00)
            currentTime.isAfter(startQuiet) || currentTime.isBefore(endQuiet)
        }

        if (inQuietHours) return null

        // Fetch verified personal memories to produce grounded follow-up
        val memories: List<Memory> = memoryRepository.getMemories().first()
        val verifiedMemories = memories.filter { it.topic.isNotBlank() && it.content.isNotBlank() }

        val timeOfDay = when (currentTime.hour) {
            in 5..11 -> "morning"
            in 12..17 -> "afternoon"
            else -> "evening"
        }

        // Try generating with local AI model grounded in memories
        if (verifiedMemories.isNotEmpty()) {
            try {
                val promptInstructions = """
                    You are Lumio, a personal AI companion. Generate a single short, warm, natural proactive check-in question for the user (in Chinese or English based on the memories).
                    Time of day: $timeOfDay.
                    Ground your message ONLY on the provided personal memories without making up facts. Keep it under 2 sentences.
                """.trimIndent()

                val conversationItems = JSONArray().apply {
                    put(
                        JSONObject().apply {
                            put("role", "user")
                            put("content", promptInstructions)
                        }
                    )
                }

                val aiResult = lumioAiRouter.routeAndExecute(
                    conversationItems = conversationItems,
                    memories = verifiedMemories.take(3),
                    tools = emptyList(),
                    previousResponseId = null,
                    taskType = LumioTaskType.OTHER
                )

                val agentResp = aiResult.getOrNull()
                val generatedText = when (agentResp) {
                    is com.personal.personalai.domain.tools.AgentResponse.Text -> agentResp.text.trim()
                    else -> null
                }
                if (!generatedText.isNullOrBlank()) {
                    return generatedText
                }
            } catch (e: Exception) {
                Log.w(TAG, "Local AI proactive generation fallback: ${e.message}")
            }
        }

        // Safe local fallback when local model is not loaded or memories are empty
        val recentMemory = verifiedMemories.firstOrNull()
        return when {
            recentMemory != null && timeOfDay == "morning" -> {
                "Good morning! I remembered that you mentioned \"${recentMemory.content}\". How is that going today?"
            }
            recentMemory != null && timeOfDay == "evening" -> {
                "Good evening! Checking in regarding your note on \"${recentMemory.topic}\". How did everything turn out?"
            }
            timeOfDay == "morning" -> {
                "Good morning! I'm here if you'd like to plan your day or discuss anything on your mind."
            }
            timeOfDay == "evening" -> {
                "Good evening! How was your day? Let me know if you want to reflect or organize tomorrow's schedule."
            }
            else -> {
                "Hello! Just checking in to see if there's anything you'd like to talk through or record."
            }
        }
    }
}
