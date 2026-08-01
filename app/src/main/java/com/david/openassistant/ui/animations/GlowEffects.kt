package com.david.openassistant.ui.animations

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.unit.dp

fun Modifier.neuralGlow(
    color: Color,
    intensity: Float = 0.15f
): Modifier = composed {
    val infiniteTransition = rememberInfiniteTransition(label = "glow")
    val alpha by infiniteTransition.animateFloat(
        initialValue = intensity * 0.5f,
        targetValue = intensity,
        animationSpec = infiniteRepeatable(
            animation = tween(3000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "glow_alpha"
    )

    drawWithContent {
        drawContent()
        drawRect(
            brush = Brush.radialGradient(
                colors = listOf(color.copy(alpha = alpha), Color.Transparent),
                center = center,
                radius = size.maxDimension
            )
        )
    }
}

fun Modifier.scanningLine(
    color: Color = Color.Cyan,
    active: Boolean = true
): Modifier = composed {
    if (!active) return@composed this

    val infiniteTransition = rememberInfiniteTransition(label = "scanning")
    val yOffset by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(2000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "scanning_line"
    )

    drawWithContent {
        drawContent()
        clipRect {
            val lineY = size.height * yOffset
            drawRect(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        Color.Transparent,
                        color.copy(alpha = 0.3f),
                        color.copy(alpha = 0.1f),
                        Color.Transparent
                    ),
                    startY = lineY - 20.dp.toPx(),
                    endY = lineY + 2.dp.toPx()
                ),
                topLeft = androidx.compose.ui.geometry.Offset(0f, lineY - 20.dp.toPx()),
                size = androidx.compose.ui.geometry.Size(size.width, 22.dp.toPx())
            )
        }
    }
}
