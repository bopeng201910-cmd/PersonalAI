package com.personal.personalai

import com.personal.personalai.data.avatar.SpeechLipSyncEngine
import com.personal.personalai.domain.avatar.Viseme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechLipSyncEngineTest {

    @Test
    fun testInitialAvatarState_targets854x480At24Fps() {
        val engine = SpeechLipSyncEngine()
        val state = engine.avatarState.value

        assertEquals(854, state.renderWidth)
        assertEquals(480, state.renderHeight)
        assertEquals(24, state.targetFps)
        assertEquals(Viseme.SILENCE, state.currentViseme)
        assertFalse(state.isSpeaking)
    }

    @Test
    fun testUpdateSpeechEnergy_mapsAmplitudeToPhoneticVisemes() {
        val engine = SpeechLipSyncEngine()

        // Low / silence
        engine.updateSpeechEnergy(0.05f)
        assertEquals(Viseme.SILENCE, engine.avatarState.value.currentViseme)

        // Moderate
        engine.updateSpeechEnergy(0.5f)
        assertEquals(Viseme.EE, engine.avatarState.value.currentViseme)

        // Loud open vowel
        engine.updateSpeechEnergy(0.95f)
        assertEquals(Viseme.AA, engine.avatarState.value.currentViseme)
        assertEquals(0.95f, engine.avatarState.value.mouthOpenAmount, 0.01f)
    }

    @Test
    fun testSpeechCompletion_resetsMouthToSilence() {
        val engine = SpeechLipSyncEngine()
        engine.onSpeechStarted()
        assertTrue(engine.avatarState.value.isSpeaking)

        engine.updateSpeechEnergy(0.8f)
        assertEquals(Viseme.OH, engine.avatarState.value.currentViseme)

        engine.onSpeechCompleted()
        assertFalse(engine.avatarState.value.isSpeaking)
        assertEquals(Viseme.SILENCE, engine.avatarState.value.currentViseme)
        assertEquals(0f, engine.avatarState.value.mouthOpenAmount, 0.001f)
    }
}
