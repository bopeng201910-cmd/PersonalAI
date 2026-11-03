package com.personal.personalai.data.router

import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import com.personal.personalai.data.datasource.ai.LocalLlmDataSource
import com.personal.personalai.data.datasource.ai.OllamaDataSource
import com.personal.personalai.data.datasource.ai.OpenAiDataSource
import com.personal.personalai.data.network.PrivacyNetworkInterceptor
import com.personal.personalai.domain.cost.BudgetCheckResult
import com.personal.personalai.domain.cost.CostController
import com.personal.personalai.domain.cost.CostType
import com.personal.personalai.domain.model.Memory
import com.personal.personalai.domain.router.LumioAiRouter
import com.personal.personalai.domain.router.LumioTaskType
import com.personal.personalai.domain.security.CloudPermissionResult
import com.personal.personalai.domain.security.NetworkServiceType
import com.personal.personalai.domain.security.PermissionEngine
import com.personal.personalai.domain.security.PrivacySanitizer
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
    private val permissionEngine: PermissionEngine,
    private val costController: CostController,
    private val networkInterceptor: PrivacyNetworkInterceptor,
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
        val prefs = dataStore.data.first()
        val provider = prefs[PreferencesKeys.AI_PROVIDER] ?: "openai"

        // ── 1. Dispatch LOCAL (LiteRT-LM) ─────────────────────────────────────
        if (provider == "local") {
            return executeLocalModel(conversationItems, memories, tools, taskType)
        }

        // ── 2. Dispatch LAN_PRIVATE (Ollama) ──────────────────────────────────
        if (provider == "ollama") {
            return executeOllamaModel(prefs, conversationItems, memories, tools, taskType)
        }

        // ── 3. Dispatch CLOUD (OpenAI) ────────────────────────────────────────
        return executeCloudModel(prefs, conversationItems, memories, tools, previousResponseId, taskType)
    }

    private suspend fun executeLocalModel(
        conversationItems: JSONArray,
        memories: List<Memory>,
        tools: List<AgentTool>,
        taskType: LumioTaskType
    ): Result<AgentResponse> {
        try {
            Log.d(TAG, "Routing to on-device LiteRT-LM (task=$taskType, cost=FREE_LOCAL)")
            return localLlmDataSource.sendMessageWithTools(conversationItems, memories, tools)
        } catch (oom: OutOfMemoryError) {
            Log.e(TAG, "Local model OutOfMemoryError! Refusing cloud fallback to protect privacy.")
            // Explicit error reporting without silent cloud fallback
            return Result.success(
                AgentResponse.Text(
                    "Device memory exhausted while running local AI. Please close other background apps and retry."
                )
            )
        } catch (e: Exception) {
            Log.e(TAG, "Local model execution failed: ${e.message}")
            // Hard barrier: Do NOT fall back to cloud!
            return Result.failure(e)
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
                AgentResponse.Text("Ollama server or model not configured in Settings → Dev (Ollama).")
            )
        }

        Log.d(TAG, "Routing to Ollama private LAN at $url (task=$taskType)")
        return ollamaDataSource.sendMessageWithTools(url, model, conversationItems, memories, tools)
    }

    private suspend fun executeCloudModel(
        prefs: Preferences,
        conversationItems: JSONArray,
        memories: List<Memory>,
        tools: List<AgentTool>,
        previousResponseId: String?,
        taskType: LumioTaskType
    ): Result<AgentResponse> {
        // Step A: Permission Engine Check (Global cloud toggle)
        val cloudPerm = permissionEngine.isCloudEnhancementPermitted()
        if (cloudPerm is CloudPermissionResult.Denied) {
            Log.w(TAG, "Cloud AI rejected by PermissionEngine: ${cloudPerm.reason}")
            networkInterceptor.updatePolicy(NetworkServiceType.CLOUD_AI, false)
            return Result.success(
                AgentResponse.Text(
                    "Cloud AI is disabled in Settings. Please enable 'Cloud Enhancement' or switch to Local AI."
                )
            )
        }

        // Step B: Cost Control & Budget Reservation Check
        val budgetCheck = costController.checkAndReserveBudget("gpt-4o")
        if (budgetCheck is BudgetCheckResult.Denied) {
            Log.w(TAG, "Cloud AI rejected by CostController: ${budgetCheck.reason}")
            networkInterceptor.updatePolicy(NetworkServiceType.CLOUD_AI, false)
            return Result.success(
                AgentResponse.Text(budgetCheck.reason)
            )
        }

        val allowed = budgetCheck as BudgetCheckResult.Allowed
        val reservedUsd = allowed.reservedUsd
        val apiKey = prefs[PreferencesKeys.API_KEY].orEmpty().trim()

        // Step C: Privacy Firewall & LOCAL_ONLY Sanitization
        val cloudMemories = PrivacySanitizer.sanitizeMemoriesForCloud(memories)
        val cloudConversation = PrivacySanitizer.sanitizeConversationForCloud(conversationItems)

        return try {
            Log.d(
                TAG,
                "Routing to OpenAI cloud: task=$taskType, model=gpt-4o, costType=${allowed.costType}, " +
                "contextItems=${cloudConversation.length()}, memories=${cloudMemories.size}"
            )
            val result = openAiDataSource.sendMessageWithTools(
                apiKey = apiKey,
                conversationItems = cloudConversation,
                memories = cloudMemories,
                tools = tools,
                previousResponseId = previousResponseId
            )
            result
        } catch (e: Exception) {
            Log.e(TAG, "Cloud call failed: ${e.message}")
            Result.failure(e)
        } finally {
            // Settle / release concurrency reservation
            costController.releaseReservation(reservedUsd)
        }
    }
}
