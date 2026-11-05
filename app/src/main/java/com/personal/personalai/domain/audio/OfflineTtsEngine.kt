package com.personal.personalai.domain.audio

interface OfflineTtsEngine {
    fun speak(text: String, onStart: () -> Unit = {}, onDone: () -> Unit = {}, onError: (String) -> Unit = {})
    fun stop()
    fun isSpeaking(): Boolean
    fun release()
}
