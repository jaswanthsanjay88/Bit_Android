package com.bit.ui.screen.guide

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.bit.ui.theme.BitColors
import kotlin.math.cos
import kotlin.math.sin

@Composable
fun OnboardingHero(
    slideIndex: Int,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(240.dp)
    ) {
        when (slideIndex) {
            0 -> WireframeSphere(modifier = Modifier.fillMaxSize())
            1 -> SecureWaveform(modifier = Modifier.fillMaxSize())
            2 -> MorphingModelChips(modifier = Modifier.fillMaxSize())
        }
    }
}

@Composable
fun WireframeSphere(modifier: Modifier = Modifier) {
    val infiniteTransition = rememberInfiniteTransition(label = "sphere")
    val rotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(8000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "rotation"
    )

    Canvas(modifier = modifier) {
        val centerX = size.width / 2f
        val centerY = size.height / 2f
        val radius = size.minDimension * 0.35f

        // Outer bounds circle
        drawCircle(
            color = BitColors.Border,
            radius = radius,
            center = Offset(centerX, centerY),
            style = Stroke(width = 1f)
        )

        // Draw longitudinal rings rotating
        for (i in 0 until 6) {
            val angleRad = Math.toRadians((rotation + i * 30).toDouble())
            val widthFactor = cos(angleRad).toFloat()
            val opacity = (0.15f + 0.7f * kotlin.math.abs(widthFactor)).coerceIn(0.15f, 0.8f)

            drawOval(
                color = BitColors.TextPrimary.copy(alpha = opacity),
                topLeft = Offset(centerX - radius * kotlin.math.abs(widthFactor), centerY - radius),
                size = Size(radius * 2f * kotlin.math.abs(widthFactor), radius * 2f),
                style = Stroke(width = 1.5f)
            )
        }

        // Draw latitude rings (horizontal bands)
        val latLines = 3
        for (i in -latLines..latLines) {
            if (i == 0) continue
            val yOffset = radius * (i.toFloat() / (latLines + 1))
            val currentRadius = radius * cos(kotlin.math.asin(i.toFloat() / (latLines + 1)))

            drawOval(
                color = BitColors.TextSecondary.copy(alpha = 0.25f),
                topLeft = Offset(centerX - currentRadius, centerY + yOffset - (currentRadius * 0.15f)),
                size = Size(currentRadius * 2f, currentRadius * 0.3f),
                style = Stroke(width = 1f)
            )
        }
    }
}

@Composable
fun SecureWaveform(modifier: Modifier = Modifier) {
    val infiniteTransition = rememberInfiniteTransition(label = "waveform")
    val count = 12
    val phases = List(count) { index ->
        infiniteTransition.animateFloat(
            initialValue = 0.1f,
            targetValue = 1.0f,
            animationSpec = infiniteRepeatable(
                animation = tween(
                    durationMillis = 600 + (index * 80) % 500,
                    easing = FastOutSlowInEasing
                ),
                repeatMode = RepeatMode.Reverse
            ),
            label = "bar_$index"
        )
    }

    Canvas(modifier = modifier) {
        val width = size.width
        val height = size.height
        val barWidth = 4.dp.toPx()
        val spacing = 8.dp.toPx()
        val totalWidth = count * barWidth + (count - 1) * spacing
        val startX = (width - totalWidth) / 2f

        for (i in 0 until count) {
            val phase = phases[i].value
            val maxBarHeight = height * 0.5f
            val minBarHeight = height * 0.08f
            // Symmetric shape (bell curve modifier based on distance from center)
            val centerDist = kotlin.math.abs(i - (count - 1) / 2f) / ((count - 1) / 2f)
            val bellFactor = (1f - centerDist * 0.5f).coerceIn(0.5f, 1f)
            val barHeight = minBarHeight + (maxBarHeight - minBarHeight) * phase * bellFactor

            val x = startX + i * (barWidth + spacing)
            val y = (height - barHeight) / 2f

            drawRoundRect(
                color = BitColors.TextPrimary.copy(alpha = 0.15f + 0.7f * phase),
                topLeft = Offset(x, y),
                size = Size(barWidth, barHeight),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(barWidth / 2f)
            )
        }
    }
}

@Composable
fun MorphingModelChips(modifier: Modifier = Modifier) {
    val infiniteTransition = rememberInfiniteTransition(label = "chips")
    val morphProgress by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(2500, easing = EaseInOutSine),
            repeatMode = RepeatMode.Reverse
        ),
        label = "morph"
    )

    Canvas(modifier = modifier) {
        val centerX = size.width / 2f
        val centerY = size.height / 2f
        val chipW = 110.dp.toPx()
        val chipH = 44.dp.toPx()

        // 3 stacked cards that slightly offset, scale, and cross-animate opacity
        for (i in 0 until 3) {
            // Calculate dynamic offset based on iteration and morphProgress
            val baseOffset = (i - 1) * 32.dp.toPx()
            val dynamicOffset = baseOffset * (0.4f + 0.6f * morphProgress)
            val skewX = (i - 1) * 12.dp.toPx() * morphProgress

            val scale = 0.9f + (i * 0.05f) + (0.05f * morphProgress)
            val opacity = when (i) {
                0 -> 0.25f + 0.15f * morphProgress
                1 -> 0.5f + 0.2f * (1f - morphProgress)
                else -> 0.85f - 0.15f * morphProgress
            }

            val strokeW = if (i == 2) 2.dp.toPx() else 1.dp.toPx()
            val color = if (i == 2) BitColors.TextPrimary else BitColors.TextSecondary

            val w = chipW * scale
            val h = chipH * scale
            val x = centerX - w / 2f + skewX
            val y = centerY - h / 2f + dynamicOffset

            drawRoundRect(
                color = color.copy(alpha = opacity),
                topLeft = Offset(x, y),
                size = Size(w, h),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(10.dp.toPx()),
                style = Stroke(width = strokeW)
            )

            // Inner chip detail placeholder line (representing architecture parameters)
            val lineLength = w * 0.4f
            val lineX = x + (w - lineLength) / 2f
            val lineY = y + h / 2f
            drawLine(
                color = color.copy(alpha = opacity * 0.5f),
                start = Offset(lineX, lineY),
                end = Offset(lineX + lineLength, lineY),
                strokeWidth = 1.dp.toPx()
            )
        }
    }
}
