package com.personal.personalai.domain.router

import com.personal.personalai.domain.cost.CostType
import com.personal.personalai.domain.model.Memory
import com.personal.personalai.domain.tools.AgentResponse
import com.personal.personalai.domain.tools.AgentTool
import org.json.JSONArray

enum class LumioTaskType {
    CHAT,
    DAILY_REVIEW,
    BACKGROUND_REMINDER,
    TOOL_EXECUTION
}

data class LumioRouteReason(
    val selectedProvider: String,
    val costType: CostType,
    val explanation: String
)

interface LumioAiRouter {
    suspend fun routeAndExecute(
        conversationItems: JSONArray,
        memories: List<Memory>,
        tools: List<AgentTool>,
        previousResponseId: String? = null,
        taskType: LumioTaskType = LumioTaskType.CHAT
    ): Result<AgentResponse>
}
