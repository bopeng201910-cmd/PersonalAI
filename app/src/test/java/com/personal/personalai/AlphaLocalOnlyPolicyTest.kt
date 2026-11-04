package com.personal.personalai

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringPreferencesKey
import com.personal.personalai.data.datasource.ai.LocalLlmDataSource
import com.personal.personalai.data.datasource.ai.OllamaDataSource
import com.personal.personalai.data.datasource.ai.OpenAiDataSource
import com.personal.personalai.data.datasource.ai.WhisperDataSource
import com.personal.personalai.data.repository.AiRepositoryImpl
import com.personal.personalai.data.router.LumioAiRouterImpl
import com.personal.personalai.domain.router.LumioTaskType
import com.personal.personalai.domain.tools.AgentResponse
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class AlphaLocalOnlyPolicyTest {

    @Test
    fun `test missing provider preference routes strictly to local model and cloud count is zero`() = runBlocking {
        val openAiDataSource = mockk<OpenAiDataSource>(relaxed = true)
        val ollamaDataSource = mockk<OllamaDataSource>(relaxed = true)
        val localLlmDataSource = mockk<LocalLlmDataSource>()
        val dataStore = mockk<DataStore<Preferences>>(relaxed = true)

        val mockPrefs = mockk<Preferences>(relaxed = true)
        every { mockPrefs[stringPreferencesKey("ai_provider")] } returns null
        every { dataStore.data } returns flowOf(mockPrefs)

        coEvery {
            localLlmDataSource.sendMessageWithTools(any(), any(), any())
        } returns Result.success(AgentResponse.Text("Local on-device response"))

        val router = LumioAiRouterImpl(openAiDataSource, ollamaDataSource, localLlmDataSource, dataStore)

        val result = router.routeAndExecute(JSONArray(), emptyList(), emptyList(), null, LumioTaskType.CHAT)

        assertTrue(result.isSuccess)
        assertEquals("Local on-device response", (result.getOrThrow() as AgentResponse.Text).text)

        // Strict verification: zero cloud invocations
        coVerify(exactly = 0) { openAiDataSource.sendMessageWithTools(any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) { ollamaDataSource.sendMessageWithTools(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `test stored openai preference cannot route to OpenAI in Alpha mode`() = runBlocking {
        val openAiDataSource = mockk<OpenAiDataSource>(relaxed = true)
        val ollamaDataSource = mockk<OllamaDataSource>(relaxed = true)
        val localLlmDataSource = mockk<LocalLlmDataSource>()
        val dataStore = mockk<DataStore<Preferences>>(relaxed = true)

        val mockPrefs = mockk<Preferences>(relaxed = true)
        // Simulate pre-existing stored "openai" preference
        every { mockPrefs[stringPreferencesKey("ai_provider")] } returns "openai"
        every { mockPrefs[stringPreferencesKey("api_key")] } returns "sk-proj-test12345"
        every { dataStore.data } returns flowOf(mockPrefs)

        coEvery {
            localLlmDataSource.sendMessageWithTools(any(), any(), any())
        } returns Result.success(AgentResponse.Text("Local execution enforced"))

        val router = LumioAiRouterImpl(openAiDataSource, ollamaDataSource, localLlmDataSource, dataStore)

        val result = router.routeAndExecute(JSONArray(), emptyList(), emptyList(), null, LumioTaskType.CHAT)

        assertTrue(result.isSuccess)
        assertEquals("Local execution enforced", (result.getOrThrow() as AgentResponse.Text).text)

        // Must NEVER call cloud OpenAI
        coVerify(exactly = 0) { openAiDataSource.sendMessageWithTools(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `test stored ollama preference cannot route to remote Ollama in Alpha mode`() = runBlocking {
        val openAiDataSource = mockk<OpenAiDataSource>(relaxed = true)
        val ollamaDataSource = mockk<OllamaDataSource>(relaxed = true)
        val localLlmDataSource = mockk<LocalLlmDataSource>()
        val dataStore = mockk<DataStore<Preferences>>(relaxed = true)

        val mockPrefs = mockk<Preferences>(relaxed = true)
        // Simulate pre-existing stored "ollama" preference
        every { mockPrefs[stringPreferencesKey("ai_provider")] } returns "ollama"
        every { dataStore.data } returns flowOf(mockPrefs)

        coEvery {
            localLlmDataSource.sendMessageWithTools(any(), any(), any())
        } returns Result.success(AgentResponse.Text("Local execution enforced"))

        val router = LumioAiRouterImpl(openAiDataSource, ollamaDataSource, localLlmDataSource, dataStore)

        val result = router.routeAndExecute(JSONArray(), emptyList(), emptyList(), null, LumioTaskType.CHAT)

        assertTrue(result.isSuccess)

        // Must NEVER call remote Ollama
        coVerify(exactly = 0) { ollamaDataSource.sendMessageWithTools(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `test local model failure or OOM does NOT fallback to cloud`() = runBlocking {
        val openAiDataSource = mockk<OpenAiDataSource>(relaxed = true)
        val ollamaDataSource = mockk<OllamaDataSource>(relaxed = true)
        val localLlmDataSource = mockk<LocalLlmDataSource>()
        val dataStore = mockk<DataStore<Preferences>>(relaxed = true)

        val mockPrefs = mockk<Preferences>(relaxed = true)
        every { dataStore.data } returns flowOf(mockPrefs)

        // Simulate local OOM
        coEvery {
            localLlmDataSource.sendMessageWithTools(any(), any(), any())
        } throws OutOfMemoryError("Low device RAM")

        val router = LumioAiRouterImpl(openAiDataSource, ollamaDataSource, localLlmDataSource, dataStore)

        val result = router.routeAndExecute(JSONArray(), emptyList(), emptyList(), null, LumioTaskType.CHAT)

        assertTrue("OOM is gracefully handled locally with warning message", result.isSuccess)
        val text = (result.getOrThrow() as AgentResponse.Text).text
        assertTrue(text.contains("Device memory is low"))

        // Cloud transports must still have ZERO invocations
        coVerify(exactly = 0) { openAiDataSource.sendMessageWithTools(any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) { ollamaDataSource.sendMessageWithTools(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `test remote Whisper transcription is blocked in Alpha mode`() {
        runBlocking {
            val lumioAiRouter = mockk<LumioAiRouterImpl>(relaxed = true)
            val whisperDataSource = mockk<WhisperDataSource>(relaxed = true)
            val dataStore = mockk<DataStore<Preferences>>(relaxed = true)

            val mockPrefs = mockk<Preferences>(relaxed = true)
            every { mockPrefs[stringPreferencesKey("api_key")] } returns "sk-proj-test123"
            every { dataStore.data } returns flowOf(mockPrefs)

            val repository = AiRepositoryImpl(lumioAiRouter, whisperDataSource, dataStore)
            val dummyAudio = File.createTempFile("test_audio", ".m4a")

            val result = repository.transcribeAudio(dummyAudio)

            assertTrue(result.isFailure)
            assertEquals("Voice transcription is disabled in Alpha 1.0 local-only mode.", result.exceptionOrNull()?.message)

            // Cloud Whisper call must NEVER be made
            coVerify(exactly = 0) { whisperDataSource.transcribe(any(), any()) }
            dummyAudio.delete()
        }
    }
}
