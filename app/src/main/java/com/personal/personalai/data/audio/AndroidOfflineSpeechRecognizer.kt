package com.personal.personalai.data.audio

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import com.personal.personalai.domain.audio.SpeechRecognitionEngine
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AndroidOfflineSpeechRecognizer @Inject constructor(
    @ApplicationContext private val context: Context
) : SpeechRecognitionEngine {

    companion object {
        private const val TAG = "OfflineSpeechRecognizer"
    }

    private var speechRecognizer: SpeechRecognizer? = null
    private val listening = AtomicBoolean(false)
    private val mainScope = CoroutineScope(Dispatchers.Main)
    private var currentLanguageTag: String = "zh-CN"

    fun setLanguage(languageTag: String): Boolean {
        return when (languageTag.lowercase()) {
            "en", "en-us", "en_us" -> {
                currentLanguageTag = "en-US"
                Log.d(TAG, "Set offline speech recognition language to: $currentLanguageTag")
                true
            }
            "zh", "zh-cn", "zh_cn", "cmn-hans-cn" -> {
                currentLanguageTag = "zh-CN"
                Log.d(TAG, "Set offline speech recognition language to: $currentLanguageTag")
                true
            }
            else -> {
                Log.w(TAG, "Requested language '$languageTag' is not verified or supported for offline recognition")
                false
            }
        }
    }

    override fun startListening(
        onResult: (String) -> Unit,
        onError: (String) -> Unit,
        onRmsChanged: ((Float) -> Unit)?
    ) {
        mainScope.launch {
            try {
                // Explicit on-device speech recognizer binding (Android 16 / API 36 baseline)
                if (!SpeechRecognizer.isOnDeviceRecognitionAvailable(context)) {
                    Log.e(TAG, "On-device speech recognition is NOT available on this device.")
                    onError("LOCAL_ASR_UNAVAILABLE: On-device speech recognition is not supported on this device. Cloud fallback is blocked.")
                    return@launch
                }

                if (speechRecognizer == null) {
                    speechRecognizer = SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
                }

                val targetLocale = when (currentLanguageTag.lowercase()) {
                    "en-us" -> Locale.US
                    "zh-cn" -> Locale.SIMPLIFIED_CHINESE
                    else -> {
                        onError("LOCAL_ASR_UNAVAILABLE: Offline recognition for '$currentLanguageTag' is not installed or verified. Cloud fallback is blocked.")
                        return@launch
                    }
                }

                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, targetLocale.toLanguageTag())
                    putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                }

                speechRecognizer?.setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) {
                        listening.set(true)
                    }

                    override fun onBeginningOfSpeech() {}
                    override fun onRmsChanged(rmsdB: Float) {
                        onRmsChanged?.invoke(rmsdB)
                    }
                    override fun onBufferReceived(buffer: ByteArray?) {}
                    override fun onEndOfSpeech() {
                        listening.set(false)
                    }

                    override fun onError(error: Int) {
                        listening.set(false)
                        Log.w(TAG, "SpeechRecognizer error: $error")
                        onError("Speech recognition error code: $error")
                    }

                    override fun onResults(results: Bundle?) {
                        listening.set(false)
                        val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        val recognizedText = matches?.firstOrNull() ?: ""
                        if (recognizedText.isNotBlank()) {
                            onResult(recognizedText)
                        } else {
                            onError("No speech recognized")
                        }
                    }

                    override fun onPartialResults(partialResults: Bundle?) {}
                    override fun onEvent(eventType: Int, params: Bundle?) {}
                })

                speechRecognizer?.startListening(intent)
                listening.set(true)
                Log.d(TAG, "Started speech recognizer in offline mode")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start speech recognizer: ${e.message}", e)
                listening.set(false)
                onError(e.message ?: "Failed to start speech recognition")
            }
        }
    }

    override fun stopListening() {
        mainScope.launch {
            try {
                speechRecognizer?.stopListening()
                listening.set(false)
            } catch (e: Exception) {
                Log.w(TAG, "Error stopping speech recognizer: ${e.message}")
            }
        }
    }

    override fun isListening(): Boolean = listening.get()
}
