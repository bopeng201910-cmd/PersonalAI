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
                val result = tts?.setLanguage(Locale.SIMPLIFIED_CHINESE)
                if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                    Log.w(TAG, "Mandarin simplified data missing, falling back to Chinese general")
                    tts?.setLanguage(Locale.CHINESE)
                }
                isInitialized.set(true)
                Log.d(TAG, "Offline Mandarin TTS initialized successfully")
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
            onError("TTS engine not initialized")
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
