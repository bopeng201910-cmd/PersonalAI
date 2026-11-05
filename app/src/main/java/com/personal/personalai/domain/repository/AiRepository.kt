package com.personal.personalai.domain.repository

import com.personal.personalai.domain.model.Memory
import com.personal.personalai.domain.router.LumioTaskType
import com.personal.personalai.domain.tools.AgentResponse
import com.personal.personalai.domain.tools.AgentTool
import org.json.JSONArray
import java.io.File

interface AiRepository {
    /**
     * @param previousResponseId If non-null and the current backend is OpenAI,
     *   sent as `previous_response_id` to chain the call to a prior response.
     *   In that mode, [conversationItems] should contain only the new turn(s)
     *   to append. Ignored by Ollama and on-device backends.
     * @param taskType Contextual task source: CHAT, QUICK_CHAT, BACKGROUND_REMINDER, DAILY_REVIEW, TOOL_EXECUTION.
     */
    suspend fun sendMessageWithTools(
        conversationItems: JSONArray,
        memories: List<Memory>,
        tools: List<AgentTool>,
        previousResponseId: String? = null,
        taskType: LumioTaskType = LumioTaskType.CHAT
    ): Result<AgentResponse>

    suspend fun transcribeAudio(audioFile: File): Result<String>
}
