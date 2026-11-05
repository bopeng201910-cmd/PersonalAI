package com.personal.personalai

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.preferencesOf
import com.personal.personalai.localllm.api.LocalModel
import com.personal.personalai.presentation.modelcenter.ModelCenterViewModel
import com.personal.personalai.presentation.settings.PreferencesKeys
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ModelCenterViewModelTest {

    private val context = mockk<Context>(relaxed = true)
    private val dataStore = mockk<DataStore<Preferences>>(relaxed = true)
    private val localLlmDataSource = mockk<com.personal.personalai.data.datasource.ai.LocalLlmDataSource>(relaxed = true)
    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        val tempDir = java.io.File(System.getProperty("java.io.tmpdir") ?: "/tmp", "models").apply { mkdirs() }
        every { context.getExternalFilesDir(any()) } returns tempDir
        every { context.filesDir } returns tempDir
        val prefs = preferencesOf(PreferencesKeys.LOCAL_MODEL to LocalModel.GEMMA_4_E2B.modelId)
        every { dataStore.data } returns flowOf(prefs)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun testActiveModelDeletionProtection_blocksRemovingActiveModel() = runTest(testDispatcher) {
        val viewModel = ModelCenterViewModel(context, dataStore, localLlmDataSource)
        testScheduler.advanceUntilIdle()

        // Attempt to delete currently active model GEMMA_4_E2B
        viewModel.deleteModel(LocalModel.GEMMA_4_E2B)

        assertNotNull(viewModel.uiState.value.error)
        assertTrue(viewModel.uiState.value.error!!.contains("Cannot remove active model"))
    }
}
