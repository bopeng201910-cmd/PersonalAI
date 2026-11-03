package com.personal.personalai

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import com.personal.personalai.data.datasource.ai.LocalLlmDataSource
import com.personal.personalai.data.datasource.ai.OllamaDataSource
import com.personal.personalai.data.datasource.ai.OpenAiDataSource
import com.personal.personalai.data.network.PrivacyNetworkInterceptor
import com.personal.personalai.data.router.LumioAiRouterImpl
import com.personal.personalai.domain.cost.CostController
import com.personal.personalai.domain.cost.UserTier
import com.personal.personalai.domain.model.Memory
import com.personal.personalai.domain.router.LumioTaskType
import com.personal.personalai.domain.security.MemoryAccessScope
import com.personal.personalai.domain.security.NetworkServiceType
import com.personal.personalai.domain.security.PermissionEngine
import com.personal.personalai.domain.security.PrivacySanitizer
import com.personal.personalai.domain.tools.AgentResponse
import com.personal.personalai.presentation.settings.PreferencesKeys
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import okhttp3.Interceptor
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class LumioAiRouterSecurityTest {

    @Test
    fun `test PrivacySanitizer filters out LOCAL_ONLY memories from cloud transmission`() {
        val memories = listOf(
            Memory(id = 1, content = "User likes black coffee", topic = "coffee"),
            Memory(id = 2, content = "Bank PIN is 1234", topic = "LOCAL_ONLY_finance"),
            Memory(id = 3, content = "Secret diary entry [LOCAL_ONLY]", topic = "diary"),
            Memory(id = 4, content = "Medical history notes", topic = "PRIVATE_health")
        )

        val sanitized = PrivacySanitizer.sanitizeMemoriesForCloud(memories)

        // Only public/shared memory should remain
        assertEquals(1, sanitized.size)
        assertEquals("User likes black coffee", sanitized[0].content)
        assertFalse(sanitized.any { it.content.contains("1234") })
    }

    @Test
    fun `test PrivacySanitizer redacts phone and email in conversation items`() {
        val items = JSONArray().apply {
            put(JSONObject().apply {
                put("role", "user")
                put("content", "Call me at 123-456-7890 or email me at user@example.com.")
            })
        }

        val sanitized = PrivacySanitizer.sanitizeConversationForCloud(items)
        val content = sanitized.getJSONObject(0).getString("content")

        assertFalse(content.contains("123-456-7890"))
        assertFalse(content.contains("user@example.com"))
        assertTrue(content.contains("[PHONE_REDACTED]"))
        assertTrue(content.contains("[EMAIL_REDACTED]"))
    }

    @Test
    fun `test PermissionEngine denies cloud AI when CLOUD_ENHANCEMENT_ENABLED is absent or false`() = runTest {
        val dataStore = mockk<DataStore<Preferences>>()
        val prefs = mockk<Preferences>()
        // User has not explicitly enabled cloud AI
        every { prefs[PreferencesKeys.CLOUD_ENHANCEMENT_ENABLED] } returns null
        every { dataStore.data } returns flowOf(prefs)

        val permissionEngine = PermissionEngine(dataStore)
        val result = permissionEngine.isCloudEnhancementPermitted()

        assertTrue(result is com.personal.personalai.domain.security.CloudPermissionResult.Denied)
    }

    @Test
    fun `test CostController blocks Basic tier user with no BYOK API key from cloud LLM`() = runTest {
        val dataStore = mockk<DataStore<Preferences>>()
        val prefs = mockk<Preferences>()
        every { prefs[PreferencesKeys.USER_TIER] } returns UserTier.BASIC.name
        every { prefs[PreferencesKeys.API_KEY] } returns ""
        every { dataStore.data } returns flowOf(prefs)

        val costController = CostController(dataStore, mockk(relaxed = true))
        val result = costController.checkAndReserveBudget("gpt-4o")

        assertTrue(result is com.personal.personalai.domain.cost.BudgetCheckResult.Denied)
        assertTrue((result as com.personal.personalai.domain.cost.BudgetCheckResult.Denied).reason.contains("BYOK"))
    }

    @Test
    fun `test LumioAiRouter blocks cloud call and returns clear message when cloud is disabled`() = runTest {
        val openAi = mockk<OpenAiDataSource>(relaxed = true)
        val ollama = mockk<OllamaDataSource>(relaxed = true)
        val localLlm = mockk<LocalLlmDataSource>(relaxed = true)
        val permissionEngine = mockk<PermissionEngine>()
        val costController = mockk<CostController>(relaxed = true)
        val networkInterceptor = mockk<PrivacyNetworkInterceptor>(relaxed = true)
        val dataStore = mockk<DataStore<Preferences>>()

        val prefs = mockk<Preferences>()
        every { prefs[PreferencesKeys.AI_PROVIDER] } returns "openai"
        every { dataStore.data } returns flowOf(prefs)
        coEvery { permissionEngine.isCloudEnhancementPermitted() } returns
                com.personal.personalai.domain.security.CloudPermissionResult.Denied("Disabled by user")

        val router = LumioAiRouterImpl(
            openAi, ollama, localLlm, permissionEngine, costController, networkInterceptor, dataStore
        )

        val response = router.routeAndExecute(
            conversationItems = JSONArray(),
            memories = emptyList(),
            tools = emptyList(),
            taskType = LumioTaskType.CHAT
        )

        assertTrue(response.isSuccess)
        val text = (response.getOrThrow() as AgentResponse.Text).text
        assertTrue(text.contains("Cloud AI is disabled"))
        // Strict negative verification: cloud provider was NEVER called
        coVerify(exactly = 0) { openAi.sendMessageWithTools(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `test LumioAiRouter does NOT silently fallback to cloud when local model fails or OOM occurs`() = runTest {
        val openAi = mockk<OpenAiDataSource>(relaxed = true)
        val ollama = mockk<OllamaDataSource>(relaxed = true)
        val localLlm = mockk<LocalLlmDataSource>()
        val permissionEngine = mockk<PermissionEngine>(relaxed = true)
        val costController = mockk<CostController>(relaxed = true)
        val networkInterceptor = mockk<PrivacyNetworkInterceptor>(relaxed = true)
        val dataStore = mockk<DataStore<Preferences>>()

        val prefs = mockk<Preferences>()
        every { prefs[PreferencesKeys.AI_PROVIDER] } returns "local"
        every { dataStore.data } returns flowOf(prefs)

        // Simulate LiteRT-LM crashing with OutOfMemoryError
        coEvery { localLlm.sendMessageWithTools(any(), any(), any()) } throws OutOfMemoryError("Native GPU memory full")

        val router = LumioAiRouterImpl(
            openAi, ollama, localLlm, permissionEngine, costController, networkInterceptor, dataStore
        )

        val response = router.routeAndExecute(
            conversationItems = JSONArray(),
            memories = emptyList(),
            tools = emptyList(),
            taskType = LumioTaskType.CHAT
        )

        assertTrue(response.isSuccess)
        val text = (response.getOrThrow() as AgentResponse.Text).text
        assertTrue(text.contains("memory exhausted"))
        // Strict negative verification: cloud provider was NEVER called as a fallback!
        coVerify(exactly = 0) { openAi.sendMessageWithTools(any(), any(), any(), any(), any()) }
    }

    @Test(expected = IOException::class)
    fun `test PrivacyNetworkInterceptor throws IOException on unauthorized cloud AI connection`() {
        val dataStore = mockk<DataStore<Preferences>>()
        val prefs = mockk<Preferences>()
        every { prefs[PreferencesKeys.CLOUD_ENHANCEMENT_ENABLED] } returns false
        every { prefs[PreferencesKeys.API_KEY] } returns ""
        every { dataStore.data } returns flowOf(prefs)

        val interceptor = PrivacyNetworkInterceptor(dataStore)

        val chain = mockk<Interceptor.Chain>()
        val request = Request.Builder()
            .url("https://api.openai.com/v1/responses")
            .build()
        every { chain.request() } returns request

        // Must throw IOException and abort connection
        interceptor.intercept(chain)
    }

    @Test
    fun `test PrivacySanitizer respects structured MemoryAccessScope LOCAL_ONLY`() {
        val memories = listOf(
            Memory(id = 1, content = "User is 30 years old", topic = "age", accessScope = MemoryAccessScope.GLOBAL_SHARED),
            Memory(id = 2, content = "Secret diary entry", topic = "notes", accessScope = MemoryAccessScope.LOCAL_ONLY)
        )

        val cloudSafe = PrivacySanitizer.sanitizeMemoriesForCloud(memories)

        assertEquals(1, cloudSafe.size)
        assertEquals("User is 30 years old", cloudSafe[0].content)
        assertFalse(cloudSafe.any { it.content.contains("Secret diary") })
    }
}
