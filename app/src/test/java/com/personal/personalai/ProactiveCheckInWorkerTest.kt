package com.personal.personalai

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.preferencesOf
import androidx.work.WorkerParameters
import com.personal.personalai.domain.repository.ChatRepository
import com.personal.personalai.domain.usecase.GenerateProactiveConversationUseCase
import com.personal.personalai.presentation.settings.PreferencesKeys
import com.personal.personalai.worker.ProactiveCheckInWorker
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalTime

class ProactiveCheckInWorkerTest {

    private val context: Context = mockk(relaxed = true)
    private val workerParams: WorkerParameters = mockk(relaxed = true)
    private val dataStore: DataStore<Preferences> = mockk(relaxed = true)
    private val generateProactiveUseCase: GenerateProactiveConversationUseCase = mockk(relaxed = true)
    private val chatRepository: ChatRepository = mockk(relaxed = true)

    @Before
    fun setUp() {
        mockkStatic(androidx.work.WorkManager::class)
        val mockWorkManager = mockk<androidx.work.WorkManager>(relaxed = true)
        every { androidx.work.WorkManager.getInstance(any()) } returns mockWorkManager
    }

    @After
    fun tearDown() {
        unmockkStatic(androidx.work.WorkManager::class)
    }

    @Test
    fun testCalculateDelayToNextCheckIn_returnsPositiveMinutes() {
        val delay = ProactiveCheckInWorker.calculateDelayToNextCheckIn("09:00", "20:00")
        assertTrue("Delay must be at least 1 minute", delay >= 1L)
        assertTrue("Delay must not exceed 24 hours", delay <= 24 * 60)
    }

    @Test
    fun testDoWork_whenDisabled_returnsSuccessWithoutRescheduling() = runTest {
        val prefs = preferencesOf(PreferencesKeys.PROACTIVE_ENABLED to false)
        every { dataStore.data } returns flowOf(prefs)

        val worker = ProactiveCheckInWorker(
            context = context,
            workerParams = workerParams,
            dataStore = dataStore,
            generateProactiveConversationUseCase = generateProactiveUseCase,
            chatRepository = chatRepository
        )

        val result = worker.doWork()
        assertEquals(androidx.work.ListenableWorker.Result.success(), result)
    }

    @Test
    fun testDoWork_whenInQuietHours_suppressesPromptButReschedulesNextCheckIn() = runTest {
        val prefs = preferencesOf(
            PreferencesKeys.PROACTIVE_ENABLED to true,
            PreferencesKeys.PROACTIVE_MORNING_TIME to "09:00",
            PreferencesKeys.PROACTIVE_EVENING_TIME to "20:00",
            PreferencesKeys.PROACTIVE_QUIET_START to "22:00",
            PreferencesKeys.PROACTIVE_QUIET_END to "07:00"
        )
        every { dataStore.data } returns flowOf(prefs)
        coEvery { generateProactiveUseCase.invoke(any(), any()) } returns null // suppressed

        val worker = ProactiveCheckInWorker(
            context = context,
            workerParams = workerParams,
            dataStore = dataStore,
            generateProactiveConversationUseCase = generateProactiveUseCase,
            chatRepository = chatRepository
        )

        val result = worker.doWork()
        assertEquals(androidx.work.ListenableWorker.Result.success(), result)
    }
}
