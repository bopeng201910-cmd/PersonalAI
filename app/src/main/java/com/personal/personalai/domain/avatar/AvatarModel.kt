package com.personal.personalai.domain.avatar

/**
 * Phonetic mouth visemes for speech-driven 2.2D lip synchronization.
 */
enum class Viseme {
    SILENCE, // Closed/resting mouth
    AA,      // Open mouth (e.g. 'a', 'ah')
    OH,      // Rounded mouth (e.g. 'o', 'oh')
    EE,      // Wide spread mouth (e.g. 'e', 'ee')
    IH,      // Narrow open mouth (e.g. 'i', 'ih')
    OU,      // Pursed mouth (e.g. 'u', 'oo')
    FF,      // Lip-teeth contact (e.g. 'f', 'v')
    MM       // Pressed lips (e.g. 'm', 'b', 'p')
}

/**
 * Authoritative 2.2D realistic avatar state for Lumio Alpha 1.0.
 *
 * Baseline constraints:
 * - Single-portrait 2.2D mesh/blendshape deformation.
 * - Target resolution: 854 x 480 (16:9 widescreen or portrait container).
 * - Target frame rate: 24 FPS (~41.6ms per frame).
 * - Hardware baseline: 12GB RAM Android device.
 * - Decoupled completely from user long-term memory and conversation archive.
 */
data class AvatarState(
    val avatarId: String = "default_avatar",
    val photoUri: String? = null,    // Local user-imported photo URI
    val isSpeaking: Boolean = false,
    val currentViseme: Viseme = Viseme.SILENCE,
    val mouthOpenAmount: Float = 0f, // 0.0 to 1.0
    val blinkProgress: Float = 0f,   // 0.0 (open) to 1.0 (closed)
    val headTiltX: Float = 0f,       // -1.0 to 1.0 (subtle natural idle breathing)
    val headTiltY: Float = 0f,       // -1.0 to 1.0
    val targetFps: Int = 24,
    val renderWidth: Int = 854,
    val renderHeight: Int = 480
)
