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

    private val vad: VoiceActivityDetector = LocalVoiceActivityDetector(silenceThresholdMs = 900L)

    private var activeTurnJob: Job? = null
    private var interruptionAudioRecordJob: Job? = null
    var onSpeechSubmitted: ((String) -> Unit)? = null
    var onUserInterruption: (() -> Unit)? = null

    init {
        updateVadThresholdForMode(_conversationMode.value)
    }

    fun setConversationMode(mode: ConversationMode) {
        _conversationMode.value = mode
        updateVadThresholdForMode(mode)
    }

    private fun updateVadThresholdForMode(mode: ConversationMode) {
        val thresholdMs = when (mode) {
            ConversationMode.NATURAL_CONVERSATION -> 900L
            ConversationMode.ACTIVE_INTERACTION -> 750L
            ConversationMode.FULL_LISTENING -> 1500L
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
                    _voiceState.value = ContinuousVoiceState.LISTENING
                    startListeningForTurn()
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
                // Convert rmsdB to approximate amplitude range (0 - 10000)
                val amp = (rmsdB * 500f).toInt().coerceAtLeast(0)
                onAudioFrame(amp)
            }
        )
    }


    /**
     * Process real-time microphone energy frame.
     * Evaluates VAD endpointing when LISTENING, and user interruption when SPEAKING.
     */
    fun onAudioFrame(amplitude: Int) {
        val currentState = _voiceState.value
        val isAiSpeaking = (currentState == ContinuousVoiceState.SPEAKING)

        // 1. Interruption check: User speaking while AI is playing TTS
        if (isAiSpeaking && amplitude > 3000) {
            Log.i(TAG, "User interruption detected! Halting TTS immediately.")
            stopInterruptionMonitor()
            ttsEngine.stop()
            activeTurnJob?.cancel()
            onUserInterruption?.invoke()
            vad.reset()
            _voiceState.value = ContinuousVoiceState.LISTENING
            startListeningForTurn()
            return
        }

        // 2. Continuous listening speech endpoint detection
        if (currentState == ContinuousVoiceState.LISTENING) {
            val endpointDetected = vad.processFrame(amplitude, isAiSpeaking = false)
            if (endpointDetected && speechRecognitionEngine.isListening()) {
                Log.d(TAG, "VAD speech endpoint reached! Stopping recognition to trigger results.")
                speechRecognitionEngine.stopListening()
            }
        }
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
                Log.e(TAG, "TTS error: $err, falling back to LISTENING")
                stopInterruptionMonitor()
                vad.reset()
                if (_voiceState.value == ContinuousVoiceState.SPEAKING) {
                    _voiceState.value = ContinuousVoiceState.LISTENING
                }
            }
        )
    }

    private fun startInterruptionMonitor() {
        stopInterruptionMonitor()
        // During speaking, launch coroutine monitoring microphone energy to trigger onAudioFrame
        interruptionAudioRecordJob = scope.launch(Dispatchers.IO) {
            try {
                // Monitor microphone amplitude using AudioRecord if permission granted
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
                                var maxAmp = 0
                                for (i in 0 until read) {
                                    val absVal = java.lang.Math.abs(buffer[i].toInt())
                                    if (absVal > maxAmp) maxAmp = absVal
                                }
                                onAudioFrame(maxAmp)
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
        interruptionAudioRecordJob?.cancel()
        interruptionAudioRecordJob = null
    }

    fun pauseSession() {
        ttsEngine.stop()
        stopInterruptionMonitor()
        speechRecognitionEngine.stopListening()
        activeTurnJob?.cancel()
        vad.reset()
        _voiceState.value = ContinuousVoiceState.PAUSED
        Log.d(TAG, "Paused continuous voice session")
    }

    fun stopSession() {
        ttsEngine.stop()
        stopInterruptionMonitor()
        speechRecognitionEngine.stopListening()
        activeTurnJob?.cancel()
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
