package com.personal.personalai.domain.router

import com.personal.personalai.domain.model.Memory
import com.personal.personalai.domain.tools.AgentResponse
import com.personal.personalai.domain.tools.AgentTool
import org.json.JSONArray

enum class LumioTaskType {
    CHAT,
    QUICK_CHAT,
    BACKGROUND_REMINDER,
    DAILY_REVIEW,
    TOOL_EXECUTION,
    OTHER
}

interface LumioAiRouter {
    suspend fun routeAndExecute(
        conversationItems: JSONArray,
        memories: List<Memory>,
        tools: List<AgentTool>,
        previousResponseId: String? = null,
        taskType: LumioTaskType = LumioTaskType.CHAT
    ): Result<AgentResponse>
}
