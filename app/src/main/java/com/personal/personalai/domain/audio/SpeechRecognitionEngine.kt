package com.personal.personalai.domain.audio

interface SpeechRecognitionEngine {
    fun startListening(
        onResult: (String) -> Unit,
        onError: (String) -> Unit,
        onRmsChanged: ((Float) -> Unit)? = null
    )
    fun stopListening()
    fun isListening(): Boolean
}
