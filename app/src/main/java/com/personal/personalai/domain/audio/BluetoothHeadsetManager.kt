package com.personal.personalai.domain.audio

import kotlinx.coroutines.flow.StateFlow

interface BluetoothHeadsetManager {
    val isHeadsetConnected: StateFlow<Boolean>
    val isScoActive: StateFlow<Boolean>

    fun startHeadsetAudioRoute()
    fun stopHeadsetAudioRoute()
    fun release()
}
