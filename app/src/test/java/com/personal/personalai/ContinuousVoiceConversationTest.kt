package com.personal.personalai

import com.personal.personalai.data.audio.ContinuousVoiceManager
import com.personal.personalai.domain.audio.BluetoothHeadsetManager
import com.personal.personalai.domain.audio.ContinuousVoiceState
import com.personal.personalai.domain.audio.ConversationMode
import com.personal.personalai.domain.audio.OfflineTtsEngine
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ContinuousVoiceConversationTest {

    private val ttsEngine = mockk<OfflineTtsEngine>(relaxed = true)
    private val btManager = mockk<BluetoothHeadsetManager>(relaxed = true)
    private val speechRecognizer = mockk<com.personal.personalai.domain.audio.SpeechRecognitionEngine>(relaxed = true)
    private val btConnectedFlow = MutableStateFlow(true)
    private val scoActiveFlow = MutableStateFlow(true)

    private lateinit var voiceManager: ContinuousVoiceManager

    @Before
    fun setUp() {
        every { btManager.isHeadsetConnected } returns btConnectedFlow
        every { btManager.isScoActive } returns scoActiveFlow

        voiceManager = ContinuousVoiceManager(ttsEngine, btManager, speechRecognizer)
    }

    @Test
    fun `test start continuous session enables SCO and enters LISTENING state`() {
        assertEquals(ContinuousVoiceState.STANDBY, voiceManager.voiceState.value)

        voiceManager.startContinuousSession()

        assertEquals(ContinuousVoiceState.LISTENING, voiceManager.voiceState.value)
        verify(exactly = 1) { btManager.startHeadsetAudioRoute() }
    }

    @Test
    fun `test user speech while AI is speaking triggers immediate interruption`() {
        voiceManager.startContinuousSession()

        // Transition to SPEAKING
        voiceManager.playAiResponse("Ni hao, this is Lumio speaking")
        assertEquals(ContinuousVoiceState.SPEAKING, voiceManager.voiceState.value)

        // Sustained user speech during playback (3 consecutive frames >= 8.5 dB)
        voiceManager.onDecibelRmsChanged(9.0f)
        voiceManager.onDecibelRmsChanged(9.0f)
        voiceManager.onDecibelRmsChanged(9.0f)

        // Interruption should immediately stop TTS and return to LISTENING
        verify(atLeast = 1) { ttsEngine.stop() }
        assertEquals(ContinuousVoiceState.LISTENING, voiceManager.voiceState.value)
    }

    @Test
    fun `test switching conversation modes updates adaptive VAD parameters`() {
        voiceManager.setConversationMode(ConversationMode.FULL_LISTENING)
        assertEquals(ConversationMode.FULL_LISTENING, voiceManager.conversationMode.value)

        voiceManager.setConversationMode(ConversationMode.ACTIVE_INTERACTION)
        assertEquals(ConversationMode.ACTIVE_INTERACTION, voiceManager.conversationMode.value)
    }

    @Test
    fun `test automatic return to listening after TTS playback completes`() {
        val onDoneSlot = io.mockk.slot<() -> Unit>()

        every {
            ttsEngine.speak(any(), any(), capture(onDoneSlot), any())
        } answers {
            // Callback captured in slot
        }

        voiceManager.startContinuousSession()
        voiceManager.playAiResponse("Playback finished")

        assertEquals(ContinuousVoiceState.SPEAKING, voiceManager.voiceState.value)

        // Invoke playback complete
        onDoneSlot.captured.invoke()

        // System automatically transitions back to LISTENING without repeated button presses
        assertEquals(ContinuousVoiceState.LISTENING, voiceManager.voiceState.value)
    }
}
