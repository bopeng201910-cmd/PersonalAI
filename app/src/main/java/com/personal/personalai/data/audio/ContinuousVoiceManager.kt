package com.personal.personalai.data.audio

import android.util.Log
import com.personal.personalai.domain.audio.BluetoothHeadsetManager
import com.personal.personalai.domain.audio.ContinuousVoiceState
import com.personal.personalai.domain.audio.ConversationMode
import com.personal.personalai.domain.audio.OfflineTtsEngine
import com.personal.personalai.domain.audio.VoiceActivityDetector
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ContinuousVoiceManager @Inject constructor(
    private val ttsEngine: OfflineTtsEngine,
    private val bluetoothHeadsetManager: BluetoothHeadsetManager,
    private val speechRecognitionEngine: com.personal.personalai.domain.audio.SpeechRecognitionEngine
) {
    companion object {
        private const val TAG = "ContinuousVoiceMgr"
    }

    private val scope = CoroutineScope(Dispatchers.Default)

    private val _voiceState = MutableStateFlow(ContinuousVoiceState.STANDBY)
    val voiceState: StateFlow<ContinuousVoiceState> = _voiceState.asStateFlow()

    private val _conversationMode = MutableStateFlow(ConversationMode.NATURAL_CONVERSATION)
    val conversationMode: StateFlow<ConversationMode> = _conversationMode.asStateFlow()

    private val vad: LocalVoiceActivityDetector = LocalVoiceActivityDetector(silenceThresholdMs = 1350L)

    private var activeTurnJob: Job? = null
    private var interruptionMonitorJob: Job? = null
    var onSpeechSubmitted: ((String) -> Unit)? = null
    var onUserInterruption: (() -> Unit)? = null

    // Sustained speech counter to prevent false triggers from single acoustic spikes
    private var consecutiveInterruptionFrames = 0

    init {
        updateVadThresholdForMode(_conversationMode.value)
    }

    fun setConversationMode(mode: ConversationMode) {
        _conversationMode.value = mode
        updateVadThresholdForMode(mode)
    }

    private fun updateVadThresholdForMode(mode: ConversationMode) {
        // Configurable silence endpoint duration: 1.2s - 1.5s baseline for natural pauses
        val thresholdMs = when (mode) {
            ConversationMode.NATURAL_CONVERSATION -> 1350L
            ConversationMode.ACTIVE_INTERACTION -> 1100L
            ConversationMode.FULL_LISTENING -> 1600L
        }
        vad.setAdaptiveSilenceThreshold(thresholdMs)
    }

    fun startContinuousSession() {
        if (_voiceState.value == ContinuousVoiceState.STANDBY || _voiceState.value == ContinuousVoiceState.PAUSED) {
            bluetoothHeadsetManager.startHeadsetAudioRoute()
            vad.reset()
            _voiceState.value = ContinuousVoiceState.LISTENING
            startListeningForTurn()
            Log.d(TAG, "Started continuous voice session in mode ${_conversationMode.value}")
        }
    }

    fun onAiGenerationFinished(success: Boolean) {
        if (_voiceState.value == ContinuousVoiceState.THINKING && !success) {
            Log.w(TAG, "AI generation failed, restoring LISTENING state.")
            _voiceState.value = ContinuousVoiceState.LISTENING
            startListeningForTurn()
        }
    }

    private fun startListeningForTurn() {
        if (_voiceState.value != ContinuousVoiceState.LISTENING) return
        speechRecognitionEngine.startListening(
            onResult = { recognizedText ->
                if (recognizedText.isNotBlank()) {
                    Log.d(TAG, "Recognized user speech: $recognizedText")
                    _voiceState.value = ContinuousVoiceState.THINKING
                    onSpeechSubmitted?.invoke(recognizedText)
                } else {
                    Log.w(TAG, "Recognized empty text, re-arming listening.")
                    if (_voiceState.value == ContinuousVoiceState.LISTENING) {
                        startListeningForTurn()
                    }
                }
            },
            onError = { err ->
                Log.w(TAG, "Speech recognition error or silence: $err")
                // In continuous listening, re-arm listening if still active
                if (_voiceState.value == ContinuousVoiceState.LISTENING || _voiceState.value == ContinuousVoiceState.THINKING) {
                    _voiceState.value = ContinuousVoiceState.LISTENING
                    startListeningForTurn()
                }
            },
            onRmsChanged = { rmsdB ->
                onDecibelRmsChanged(rmsdB)
            }
        )
    }

    /**
     * Process real-time decibel RMS values from single microphone owner (SpeechRecognizer).
     * Avoids concurrent AudioRecord microphone contention.
     */
    fun onDecibelRmsChanged(rmsdB: Float) {
        val currentState = _voiceState.value
        val isAiSpeaking = (currentState == ContinuousVoiceState.SPEAKING)

        // 1. Interruption handling: User speaking during AI TTS playback
        // Requires sustained speech (>= 3 consecutive frames above calibrated 8.5 dB threshold)
        // to reject speaker echo spikes and momentary ambient noise.
        if (isAiSpeaking) {
            if (rmsdB >= 8.5f) {
                consecutiveInterruptionFrames++
                if (consecutiveInterruptionFrames >= 3) {
                    consecutiveInterruptionFrames = 0
                    handleUserInterruption()
                }
            } else {
                consecutiveInterruptionFrames = 0
            }
            return
        } else {
            consecutiveInterruptionFrames = 0
        }

        // 2. Endpoint detection during LISTENING
        if (currentState == ContinuousVoiceState.LISTENING) {
            val endpointReached = vad.processDbLevel(rmsdB, isAiSpeaking = false)
            if (endpointReached && speechRecognitionEngine.isListening()) {
                Log.d(TAG, "VAD silence endpoint reached. Stopping recognition to generate result.")
                speechRecognitionEngine.stopListening()
            }
        }
    }

    /**
     * Immediate cancellation and interruption cleanup.
     */
    fun handleUserInterruption() {
        Log.i(TAG, "User interruption detected! Halting TTS playback and invalidating previous response.")
        stopInterruptionMonitor()
        ttsEngine.stop()
        activeTurnJob?.cancel()
        activeTurnJob = null
        onUserInterruption?.invoke()
        vad.reset()
        _voiceState.value = ContinuousVoiceState.LISTENING
        startListeningForTurn()
    }

    /**
     * Call when local AI response has been generated and ready to speak.
     */
    fun playAiResponse(text: String, onCompletion: () -> Unit = {}) {
        if (_voiceState.value == ContinuousVoiceState.PAUSED || _voiceState.value == ContinuousVoiceState.STANDBY) {
            return
        }

        activeTurnJob?.cancel()
        _voiceState.value = ContinuousVoiceState.SPEAKING
        // Ensure speech recognition is stopped before interruption monitor starts
        speechRecognitionEngine.stopListening()
        startInterruptionMonitor()

        ttsEngine.speak(
            text = text,
            onStart = {
                _voiceState.value = ContinuousVoiceState.SPEAKING
            },
            onDone = {
                Log.d(TAG, "TTS complete, automatically returning to LISTENING state.")
                stopInterruptionMonitor()
                onCompletion()
                vad.reset()
                // Seamless turn-taking: automatically resume listening after playback
                if (_voiceState.value == ContinuousVoiceState.SPEAKING) {
                    _voiceState.value = ContinuousVoiceState.LISTENING
                    startListeningForTurn()
                }
            },
            onError = { err ->
                Log.e(TAG, "TTS error: $err, returning to LISTENING")
                stopInterruptionMonitor()
                vad.reset()
                if (_voiceState.value == ContinuousVoiceState.SPEAKING) {
                    _voiceState.value = ContinuousVoiceState.LISTENING
                    startListeningForTurn()
                }
            }
        )
    }

    private fun startInterruptionMonitor() {
        stopInterruptionMonitor()
        // During speaking, SpeechRecognizer is stopped. We monitor microphone PCM energy
        // solely during playback to detect genuine user interruption.
        interruptionMonitorJob = scope.launch(Dispatchers.IO) {
            try {
                val sampleRate = 16000
                val channelConfig = android.media.AudioFormat.CHANNEL_IN_MONO
                val audioFormat = android.media.AudioFormat.ENCODING_PCM_16BIT
                val minBufSize = android.media.AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
                if (minBufSize <= 0) return@launch

                val audioRecord = android.media.AudioRecord(
                    android.media.MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                    sampleRate,
                    channelConfig,
                    audioFormat,
                    minBufSize * 2
                )

                if (audioRecord.state == android.media.AudioRecord.STATE_INITIALIZED) {
                    try {
                        audioRecord.startRecording()
                        val buffer = ShortArray(minBufSize)
                        while (isActive && _voiceState.value == ContinuousVoiceState.SPEAKING) {
                            val read = audioRecord.read(buffer, 0, buffer.size)
                            if (read > 0) {
                                var sumSquares = 0.0
                                for (i in 0 until read) {
                                    val sample = buffer[i].toDouble()
                                    sumSquares += sample * sample
                                }
                                val rms = Math.sqrt(sumSquares / read)
                                val rmsDb = if (rms > 0.0) (20.0 * Math.log10(rms / 100.0)).toFloat() else -100f
                                onDecibelRmsChanged(rmsDb)
                            }
                        }
                    } finally {
                        try {
                            audioRecord.stop()
                        } catch (e: Exception) {
                            Log.w(TAG, "AudioRecord stop exception: ${e.message}")
                        }
                        audioRecord.release()
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Interruption audio monitor exception: ${e.message}")
            }
        }
    }

    private fun stopInterruptionMonitor() {
        interruptionMonitorJob?.cancel()
        interruptionMonitorJob = null
    }

    fun pauseSession() {
        ttsEngine.stop()
        stopInterruptionMonitor()
        speechRecognitionEngine.stopListening()
        activeTurnJob?.cancel()
        activeTurnJob = null
        vad.reset()
        _voiceState.value = ContinuousVoiceState.PAUSED
        Log.d(TAG, "Paused continuous voice session - microphone completely stopped")
    }

    fun stopSession() {
        ttsEngine.stop()
        stopInterruptionMonitor()
        speechRecognitionEngine.stopListening()
        activeTurnJob?.cancel()
        activeTurnJob = null
        vad.reset()
        bluetoothHeadsetManager.stopHeadsetAudioRoute()
        _voiceState.value = ContinuousVoiceState.STANDBY
        Log.d(TAG, "Stopped continuous voice session")
    }

    fun release() {
        stopSession()
        bluetoothHeadsetManager.release()
        ttsEngine.release()
    }
}
