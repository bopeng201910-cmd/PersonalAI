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

    override fun startListening(
        onResult: (String) -> Unit,
        onError: (String) -> Unit,
        onRmsChanged: ((Float) -> Unit)?
    ) {
        mainScope.launch {
            try {
                // Explicit on-device speech recognizer binding (Android 16 / API 36 baseline)
                if (speechRecognizer == null) {
                    if (SpeechRecognizer.isOnDeviceRecognitionAvailable(context)) {
                        speechRecognizer = SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
                    } else {
                        Log.e(TAG, "On-device speech recognition is NOT available on this device!")
                        onError("On-device speech recognition model is not installed. Cloud fallback is blocked.")
                        return@launch
                    }
                }

                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.SIMPLIFIED_CHINESE.toString())
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
