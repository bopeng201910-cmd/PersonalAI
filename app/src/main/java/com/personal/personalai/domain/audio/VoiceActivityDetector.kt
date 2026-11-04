package com.personal.personalai.domain.audio

interface VoiceActivityDetector {
    /**
     * Feed audio PCM buffer or RMS energy.
     * Returns true if speech endpoint (silence threshold reached after speech) is detected.
     */
    fun processFrame(amplitude: Int, isAiSpeaking: Boolean): Boolean

    /**
     * Returns true if user is actively speaking right now.
     */
    fun isUserSpeaking(): Boolean

    fun reset()

    fun setAdaptiveSilenceThreshold(millis: Long)
}
