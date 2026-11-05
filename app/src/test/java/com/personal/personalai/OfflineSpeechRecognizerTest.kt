package com.personal.personalai

import android.content.Context
import android.speech.SpeechRecognizer
import com.personal.personalai.data.audio.AndroidOfflineSpeechRecognizer
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class OfflineSpeechRecognizerTest {

    private val context: Context = mockk(relaxed = true)
    private val testDispatcher = StandardTestDispatcher()
    private val testScope = TestScope(testDispatcher)

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        mockkStatic(SpeechRecognizer::class)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        unmockkStatic(SpeechRecognizer::class)
    }

    @Test
    fun testStartListening_whenOnDeviceRecognitionUnavailable_failsClosedWithoutCloudFallback() = testScope.runTest {
        every { SpeechRecognizer.isOnDeviceRecognitionAvailable(any()) } returns false

        val recognizer = AndroidOfflineSpeechRecognizer(context)
        var capturedError: String? = null

        recognizer.startListening(
            onResult = {},
            onError = { err -> capturedError = err },
            onRmsChanged = null
        )

        testScheduler.advanceUntilIdle()

        assertTrue(capturedError?.contains("LOCAL_ASR_UNAVAILABLE") == true)
        assertTrue(capturedError?.contains("Cloud fallback is blocked") == true)
    }

    @Test
    fun testLanguageSelection_configuresChineseAndEnglish() {
        val recognizer = AndroidOfflineSpeechRecognizer(context)
        recognizer.setLanguage("en")
        recognizer.setLanguage("zh-CN")
    }
}
