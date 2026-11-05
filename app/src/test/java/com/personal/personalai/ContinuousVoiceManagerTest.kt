package com.personal.personalai

import com.personal.personalai.data.audio.ContinuousVoiceManager
import com.personal.personalai.data.audio.LocalVoiceActivityDetector
import com.personal.personalai.domain.audio.BluetoothHeadsetManager
import com.personal.personalai.domain.audio.ContinuousVoiceState
import com.personal.personalai.domain.audio.ConversationMode
import com.personal.personalai.domain.audio.OfflineTtsEngine
import com.personal.personalai.domain.audio.SpeechRecognitionEngine
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ContinuousVoiceManagerTest {

    private val ttsEngine: OfflineTtsEngine = mockk(relaxed = true)
    private val bluetoothHeadsetManager: BluetoothHeadsetManager = mockk(relaxed = true)
    private val speechRecognitionEngine: SpeechRecognitionEngine = mockk(relaxed = true)

    private val isHeadsetConnectedFlow = MutableStateFlow(true)
    private val isScoActiveFlow = MutableStateFlow(true)

    private lateinit var manager: ContinuousVoiceManager

    @Before
    fun setUp() {
        every { bluetoothHeadsetManager.isHeadsetConnected } returns isHeadsetConnectedFlow
        every { bluetoothHeadsetManager.isScoActive } returns isScoActiveFlow
        every { speechRecognitionEngine.isListening() } returns true

        manager = ContinuousVoiceManager(
            ttsEngine = ttsEngine,
            bluetoothHeadsetManager = bluetoothHeadsetManager,
            speechRecognitionEngine = speechRecognitionEngine
        )
    }

    @Test
    fun testStartContinuousSession_initializesBluetoothAndEntersListening() {
        manager.startContinuousSession()

        assertEquals(ContinuousVoiceState.LISTENING, manager.voiceState.value)
        verify { bluetoothHeadsetManager.startHeadsetAudioRoute() }
        verify { speechRecognitionEngine.startListening(any(), any(), any()) }
    }

    @Test
    fun testUserInterruptionDuringSpeaking_haltsTtsImmediatelyAndReturnsToListening() {
        manager.startContinuousSession()

        // Transition to SPEAKING
        manager.playAiResponse("这是正在播放的回答")
        assertEquals(ContinuousVoiceState.SPEAKING, manager.voiceState.value)

        var interruptionNotified = false
        manager.onUserInterruption = {
            interruptionNotified = true
        }

        // Simulate user speaking while AI is speaking (loud RMS level >= 7.5 dB)
        manager.onDecibelRmsChanged(8.0f)

        // Verifications
        assertTrue(interruptionNotified)
        verify { ttsEngine.stop() }
        assertEquals(ContinuousVoiceState.LISTENING, manager.voiceState.value)
    }

    @Test
    fun testPauseSession_stopsMicrophoneAndTtsCleanly() {
        manager.startContinuousSession()
        manager.pauseSession()

        assertEquals(ContinuousVoiceState.PAUSED, manager.voiceState.value)
        verify { speechRecognitionEngine.stopListening() }
        verify { ttsEngine.stop() }
    }

    @Test
    fun testLocalVoiceActivityDetector_detectsSpeechAndEndpointsAfterConfiguredSilence() {
        val vad = LocalVoiceActivityDetector(silenceThresholdMs = 1200L, speechDbThreshold = 3.5f)

        // 1. Initial silence
        assertFalse(vad.processDbLevel(1.0f, isAiSpeaking = false))
        assertFalse(vad.isUserSpeaking())

        // 2. Speech starts (RMS 6.0 dB > 3.5 dB)
        assertFalse(vad.processDbLevel(6.0f, isAiSpeaking = false))
        assertTrue(vad.isUserSpeaking())

        // 3. Natural pause inside utterance (e.g. 200ms)
        assertFalse(vad.processDbLevel(2.0f, isAiSpeaking = false))
        assertFalse(vad.isUserSpeaking())

        // 4. Continued speech
        assertFalse(vad.processDbLevel(7.0f, isAiSpeaking = false))
        assertTrue(vad.isUserSpeaking())
    }

    @Test
    fun testEchoRejection_ignoresAcousticEchoDuringAiPlayback() {
        val vad = LocalVoiceActivityDetector(silenceThresholdMs = 1200L, speechDbThreshold = 3.5f)

        // When AI is speaking, effective threshold is 7.5 dB (3.5 + 4.0)
        // A 5.0 dB echo from speakerphone should NOT trigger user speaking
        assertFalse(vad.processDbLevel(5.0f, isAiSpeaking = true))
        assertFalse(vad.isUserSpeaking())

        // Loud distinct speech (8.5 dB) should trigger user speech
        assertFalse(vad.processDbLevel(8.5f, isAiSpeaking = true))
        assertTrue(vad.isUserSpeaking())
    }
}
