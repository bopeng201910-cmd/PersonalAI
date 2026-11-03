package com.personal.personalai

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import com.personal.personalai.data.datasource.ai.LocalLlmDataSource
import com.personal.personalai.data.datasource.ai.OllamaDataSource
import com.personal.personalai.data.datasource.ai.OpenAiDataSource
import com.personal.personalai.data.router.LumioAiRouterImpl
import com.personal.personalai.domain.router.LumioTaskType
import com.personal.personalai.domain.tools.AgentResponse
import com.personal.personalai.presentation.settings.PreferencesKeys
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.junit.Assert.assertTrue
import org.junit.Test

class LumioAiRouterP1Test {

    @Test
    fun `test LumioAiRouter dispatches correctly to on-device LocalLlm`() = runTest {
        val openAi = mockk<OpenAiDataSource>(relaxed = true)
        val ollama = mockk<OllamaDataSource>(relaxed = true)
        val localLlm = mockk<LocalLlmDataSource>()
        val dataStore = mockk<DataStore<Preferences>>()

        val prefs = mockk<Preferences>()
        every { prefs[PreferencesKeys.AI_PROVIDER] } returns "local"
        every { dataStore.data } returns flowOf(prefs)

        coEvery { localLlm.sendMessageWithTools(any(), any(), any()) } returns
                Result.success(AgentResponse.Text("Hello from local"))

        val router = LumioAiRouterImpl(openAi, ollama, localLlm, dataStore)
        val response = router.routeAndExecute(
            conversationItems = JSONArray(),
            memories = emptyList(),
            tools = emptyList(),
            taskType = LumioTaskType.CHAT
        )

        assertTrue(response.isSuccess)
        coVerify(exactly = 1) { localLlm.sendMessageWithTools(any(), any(), any()) }
        coVerify(exactly = 0) { openAi.sendMessageWithTools(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `test LumioAiRouter handles Local model OOM without silent cloud fallback`() = runTest {
        val openAi = mockk<OpenAiDataSource>(relaxed = true)
        val ollama = mockk<OllamaDataSource>(relaxed = true)
        val localLlm = mockk<LocalLlmDataSource>()
        val dataStore = mockk<DataStore<Preferences>>()

        val prefs = mockk<Preferences>()
        every { prefs[PreferencesKeys.AI_PROVIDER] } returns "local"
        every { dataStore.data } returns flowOf(prefs)

        // Native GPU OOM
        coEvery { localLlm.sendMessageWithTools(any(), any(), any()) } throws OutOfMemoryError("Native GPU RAM exhausted")

        val router = LumioAiRouterImpl(openAi, ollama, localLlm, dataStore)
        val response = router.routeAndExecute(
            conversationItems = JSONArray(),
            memories = emptyList(),
            tools = emptyList(),
            taskType = LumioTaskType.BACKGROUND_REMINDER
        )

        assertTrue(response.isSuccess)
        val text = (response.getOrThrow() as AgentResponse.Text).text
        assertTrue(text.contains("memory is low"))
        // Strict verification: OpenAi cloud was NEVER invoked
        coVerify(exactly = 0) { openAi.sendMessageWithTools(any(), any(), any(), any(), any()) }
    }
}
