package com.personal.personalai.data.avatar

import com.personal.personalai.domain.avatar.AvatarState
import com.personal.personalai.domain.avatar.Viseme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Real-time Speech-Driven Lip Synchronization Engine for Lumio Alpha 1.0.
 *
 * Runs a deterministic 24 FPS render loop (~41.6ms ticks) generating viseme mouth openings,
 * subtle eye blinks, and natural breathing idle movements based on speech playback timing.
 */
@Singleton
class SpeechLipSyncEngine @Inject constructor() {

    companion object {
        const val FRAME_DURATION_MS = 41L // ~24 FPS (1000 / 24 = 41.6ms)
        const val TARGET_WIDTH = 854
        const val TARGET_HEIGHT = 480
    }

    private val scope = CoroutineScope(Dispatchers.Default)
    private var animationJob: Job? = null

    private val _avatarState = MutableStateFlow(
        AvatarState(
            renderWidth = TARGET_WIDTH,
            renderHeight = TARGET_HEIGHT,
            targetFps = 24
        )
    )
    val avatarState: StateFlow<AvatarState> = _avatarState.asStateFlow()

    fun onSpeechStarted() {
        _avatarState.update { it.copy(isSpeaking = true) }
        startAnimationLoop()
    }

    fun onSpeechCompleted() {
        _avatarState.update {
            it.copy(
                isSpeaking = false,
                currentViseme = Viseme.SILENCE,
                mouthOpenAmount = 0f
            )
        }
    }

    fun updateSpeechEnergy(amplitude: Float) {
        // Map audio energy level into viseme mouth shapes
        val openAmount = amplitude.coerceIn(0f, 1f)
        val viseme = when {
            openAmount < 0.1f -> Viseme.SILENCE
            openAmount < 0.35f -> Viseme.MM
            openAmount < 0.6f -> Viseme.EE
            openAmount < 0.85f -> Viseme.OH
            else -> Viseme.AA
        }

        _avatarState.update {
            it.copy(
                currentViseme = viseme,
                mouthOpenAmount = openAmount
            )
        }
    }

    private fun startAnimationLoop() {
        if (animationJob?.isActive == true) return

        animationJob = scope.launch {
            var frameCount = 0L
            while (isActive) {
                frameCount++

                // Natural subtle breathing cycle (~3 seconds per cycle at 24 FPS)
                val breathCycle = (frameCount % 72).toFloat() / 72f
                val headTiltY = Math.sin(breathCycle * 2.0 * Math.PI).toFloat() * 0.04f

                // Periodic eye blink every ~120 frames (~5 seconds), blink lasts ~4 frames (~160ms)
                val blinkPhase = (frameCount % 120)
                val blinkProgress = if (blinkPhase in 0..4) {
                    Math.sin((blinkPhase.toFloat() / 4f) * Math.PI).toFloat()
                } else {
                    0f
                }

                _avatarState.update { current ->
                    current.copy(
                        blinkProgress = blinkProgress,
                        headTiltY = headTiltY
                    )
                }

                delay(FRAME_DURATION_MS)
            }
        }
    }

    fun release() {
        animationJob?.cancel()
        animationJob = null
    }
}
