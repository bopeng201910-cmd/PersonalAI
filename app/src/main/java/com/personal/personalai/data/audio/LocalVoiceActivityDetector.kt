package com.personal.personalai.data.audio

import com.personal.personalai.domain.audio.VoiceActivityDetector

/**
 * Playback-aware Voice Activity Detector for continuous conversation.
 *
 * Operates on decibel-scale RMS values (typically -2dB to 10+ dB from Android SpeechRecognizer)
 * or linear PCM RMS energy values, avoiding arbitrary multiplication factors.
 * Default silence threshold is configurable around 1200ms - 1500ms to preserve natural pauses.
 */
class LocalVoiceActivityDetector(
    private var silenceThresholdMs: Long = 1350L,
    /**
     * Speech decibel RMS threshold above ambient noise floor.
     * Android SpeechRecognizer.onRmsChanged delivers values in dB (generally -2.0 to 12.0+).
     */
    private val speechDbThreshold: Float = 3.5f
) : VoiceActivityDetector {

    private var speechDetectedInUtterance = false
    private var activeSpeechStartMs = 0L
    private var silenceStartMs = 0L
    private var currentlySpeaking = false

    /**
     * Process raw decibel RMS level reported by speech recognizer or audio pipeline.
     */
    fun processDbLevel(rmsdB: Float, isAiSpeaking: Boolean): Boolean {
        val now = System.currentTimeMillis()
        // Echo prevention: raise detection threshold if AI TTS is playing
        val threshold = if (isAiSpeaking) speechDbThreshold + 4.0f else speechDbThreshold

        if (rmsdB >= threshold) {
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
                    reset()
                    return true
                }
            }
            return false
        }
    }

    override fun processFrame(amplitude: Int, isAiSpeaking: Boolean): Boolean {
        // Fallback for linear PCM amplitude (e.g. 0..32767)
        val normalizedDb = if (amplitude > 0) {
            (20.0 * Math.log10(amplitude.toDouble() / 100.0)).toFloat()
        } else {
            0.0f
        }
        return processDbLevel(normalizedDb, isAiSpeaking)
    }

    override fun isUserSpeaking(): Boolean = currentlySpeaking

    override fun reset() {
        speechDetectedInUtterance = false
        activeSpeechStartMs = 0L
        silenceStartMs = 0L
        currentlySpeaking = false
    }

    override fun setAdaptiveSilenceThreshold(millis: Long) {
        // Range configured around 1000ms - 1800ms to accommodate natural pauses
        silenceThresholdMs = millis.coerceIn(1000L, 2000L)
    }
}
