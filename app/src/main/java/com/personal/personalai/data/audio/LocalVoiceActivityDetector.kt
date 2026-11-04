package com.personal.personalai.data.audio

import com.personal.personalai.domain.audio.VoiceActivityDetector

class LocalVoiceActivityDetector(
    private var silenceThresholdMs: Long = 900L,
    private val speechEnergyThreshold: Int = 1200
) : VoiceActivityDetector {

    private var speechDetectedInUtterance = false
    private var activeSpeechStartMs = 0L
    private var silenceStartMs = 0L
    private var currentlySpeaking = false

    override fun processFrame(amplitude: Int, isAiSpeaking: Boolean): Boolean {
        val now = System.currentTimeMillis()

        // Acoustic echo prevention: ignore low/moderate amplitude when AI is actively speaking
        val effectiveThreshold = if (isAiSpeaking) speechEnergyThreshold * 3 else speechEnergyThreshold

        if (amplitude > effectiveThreshold) {
            currentlySpeaking = true
            if (!speechDetectedInUtterance) {
                speechDetectedInUtterance = true
                activeSpeechStartMs = now
            }
            silenceStartMs = 0L
            return false
        } else {
            currentlySpeaking = false
            if (speechDetectedInUtterance) {
                if (silenceStartMs == 0L) {
                    silenceStartMs = now
                } else if (now - silenceStartMs >= silenceThresholdMs) {
                    // Endpoint detected!
                    reset()
                    return true
                }
            }
            return false
        }
    }

    override fun isUserSpeaking(): Boolean = currentlySpeaking

    override fun reset() {
        speechDetectedInUtterance = false
        activeSpeechStartMs = 0L
        silenceStartMs = 0L
        currentlySpeaking = false
    }

    override fun setAdaptiveSilenceThreshold(millis: Long) {
        silenceThresholdMs = millis.coerceIn(900L, 1800L)
    }
}
