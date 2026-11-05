package com.personal.personalai.presentation.avatar

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.personal.personalai.domain.avatar.AvatarState
import com.personal.personalai.domain.avatar.Viseme

/**
 * 2.2D Realistic Avatar View for Lumio Alpha 1.0.
 *
 * Implements a lightweight single-portrait 2.2D canvas rendering with:
 * - 854x480 aspect ratio (16:9).
 * - Real-time speech-driven viseme mouth shapes (AA, OH, EE, MM, etc.).
 * - Eye blinks and natural subtle head breathing tilts.
 * - Hardware baseline efficiency (targeting 24 FPS with near-zero memory footprint).
 */
@Composable
fun AvatarView(
    avatarState: AvatarState,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(16f / 9f)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center
    ) {
        val outlineColor = MaterialTheme.colorScheme.outline
        val skinColor = Color(0xFFFFDAB9)
        val eyeColor = Color(0xFF2B2B2B)
        val mouthColor = Color(0xFFC04050)

        Canvas(modifier = Modifier.matchParentSize()) {
            val centerX = size.width / 2f
            val centerY = size.height / 2f + (avatarState.headTiltY * 20f)

            // Head (2.2D oval portrait)
            val headWidth = size.width * 0.28f
            val headHeight = size.height * 0.65f
            drawOval(
                color = skinColor,
                topLeft = Offset(centerX - headWidth / 2f, centerY - headHeight / 2f),
                size = Size(headWidth, headHeight)
            )
            drawOval(
                color = outlineColor.copy(alpha = 0.4f),
                topLeft = Offset(centerX - headWidth / 2f, centerY - headHeight / 2f),
                size = Size(headWidth, headHeight),
                style = Stroke(width = 2.dp.toPx())
            )

            // Eyes with dynamic blinking
            val eyeSpacing = headWidth * 0.28f
            val eyeLevelY = centerY - headHeight * 0.12f
            val eyeWidth = headWidth * 0.16f
            val openEyeHeight = headHeight * 0.08f
            val currentEyeHeight = openEyeHeight * (1f - avatarState.blinkProgress).coerceAtLeast(0.05f)

            // Left Eye
            drawOval(
                color = eyeColor,
                topLeft = Offset(centerX - eyeSpacing - eyeWidth / 2f, eyeLevelY - currentEyeHeight / 2f),
                size = Size(eyeWidth, currentEyeHeight)
            )

            // Right Eye
            drawOval(
                color = eyeColor,
                topLeft = Offset(centerX + eyeSpacing - eyeWidth / 2f, eyeLevelY - currentEyeHeight / 2f),
                size = Size(eyeWidth, currentEyeHeight)
            )

            // 2.2D Mouth blendshape deformation based on Viseme & speech energy
            val mouthCenterY = centerY + headHeight * 0.22f
            val baseMouthWidth = headWidth * 0.32f
            val openAmt = avatarState.mouthOpenAmount

            val (mouthW, mouthH) = when (avatarState.currentViseme) {
                Viseme.SILENCE, Viseme.MM -> Pair(baseMouthWidth * 0.85f, 4.dp.toPx())
                Viseme.AA -> Pair(baseMouthWidth * 0.95f, (24.dp * openAmt).toPx().coerceAtLeast(6.dp.toPx()))
                Viseme.OH, Viseme.OU -> Pair(baseMouthWidth * 0.65f, (22.dp * openAmt).toPx().coerceAtLeast(8.dp.toPx()))
                Viseme.EE, Viseme.IH -> Pair(baseMouthWidth * 1.2f, (12.dp * openAmt).toPx().coerceAtLeast(5.dp.toPx()))
                Viseme.FF -> Pair(baseMouthWidth * 0.8f, 6.dp.toPx())
            }

            drawOval(
                color = mouthColor,
                topLeft = Offset(centerX - mouthW / 2f, mouthCenterY - mouthH / 2f),
                size = Size(mouthW, mouthH)
            )
        }
    }
}
