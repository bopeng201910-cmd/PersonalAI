package com.personal.personalai.data.audio

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import com.personal.personalai.domain.audio.OfflineTtsEngine
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class OfflineMandarinTtsEngine @Inject constructor(
    @ApplicationContext private val context: Context
) : OfflineTtsEngine {

    companion object {
        private const val TAG = "OfflineMandarinTts"
    }

    private var tts: TextToSpeech? = null
    private val isInitialized = AtomicBoolean(false)
    private val speaking = AtomicBoolean(false)

    init {
        initializeTts()
    }

    private fun initializeTts() {
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val engine = tts
                val result = engine?.setLanguage(Locale.SIMPLIFIED_CHINESE)
                if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                    Log.w(TAG, "Mandarin simplified data missing, falling back to Chinese general")
                    engine?.setLanguage(Locale.CHINESE)
                }

                // Explicit verification that selected voice does not require network connection (Android 16 baseline)
                var validOfflineVoiceFound = false
                val currentVoice = engine?.voice
                if (currentVoice != null && !currentVoice.isNetworkConnectionRequired) {
                    validOfflineVoiceFound = true
                } else {
                    Log.w(TAG, "Default voice requires network or is null. Searching for offline voice...")
                    val offlineVoice = engine?.voices?.firstOrNull {
                        (it.locale == Locale.SIMPLIFIED_CHINESE || it.locale == Locale.CHINESE) &&
                        !it.isNetworkConnectionRequired
                    }
                    if (offlineVoice != null) {
                        engine?.voice = offlineVoice
                        validOfflineVoiceFound = true
                        Log.d(TAG, "Selected offline Mandarin voice: ${offlineVoice.name}")
                    } else {
                        Log.e(TAG, "No strictly offline Mandarin voice found in TTS engine! Failing closed.")
                    }
                }

                if (validOfflineVoiceFound) {
                    isInitialized.set(true)
                    Log.d(TAG, "Offline Mandarin TTS initialized successfully with verified offline voice")
                } else {
                    isInitialized.set(false)
                    Log.e(TAG, "TTS initialization failed closed: no verified offline voice available")
                }
            } else {
                Log.e(TAG, "TTS Initialization failed: $status")
            }
        }
    }

    override fun speak(
        text: String,
        onStart: () -> Unit,
        onDone: () -> Unit,
        onError: (String) -> Unit
    ) {
        val engine = tts
        if (engine == null || !isInitialized.get()) {
            onError("LOCAL_TTS_UNAVAILABLE: No verified offline Mandarin voice is installed on this device.")
            return
        }

        // Re-verify immediately prior to playback that voice has not flipped to cloud-required
        val voice = engine.voice
        if (voice != null && voice.isNetworkConnectionRequired) {
            onError("LOCAL_TTS_UNAVAILABLE: Selected voice requires network connection. Cloud TTS is blocked.")
            return
        }

        val utteranceId = UUID.randomUUID().toString()
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                speaking.set(true)
                onStart()
            }

            override fun onDone(utteranceId: String?) {
                speaking.set(false)
                onDone()
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                speaking.set(false)
                onError("TTS playback error")
            }

            override fun onError(utteranceId: String?, errorCode: Int) {
                speaking.set(false)
                onError("TTS error code $errorCode")
            }
        })

        speaking.set(true)
        val result = engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
        if (result != TextToSpeech.SUCCESS) {
            speaking.set(false)
            onError("Failed to queue speech: $result")
        }
    }

    override fun stop() {
        tts?.stop()
        speaking.set(false)
    }

    override fun isSpeaking(): Boolean = speaking.get() || (tts?.isSpeaking == true)

    override fun release() {
        stop()
        tts?.shutdown()
        tts = null
        isInitialized.set(false)
    }
}
