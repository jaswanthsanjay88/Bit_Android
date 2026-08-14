package com.bit.ui.components

import android.graphics.PathMeasure
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.asComposePath
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.sin

private fun buildPillPerimeterPath(
    width: Float,
    height: Float,
    strokePx: Float,
    cornerRadiusPx: Float
): Path {
    val halfStroke = strokePx / 2f
    val r = cornerRadiusPx.coerceAtMost((height - strokePx) / 2f)

    val left = halfStroke
    val top = halfStroke
    val right = width - halfStroke
    val bottom = height - halfStroke

    return Path().apply {
        moveTo(width / 2f, top)
        lineTo(right - r, top)
        arcTo(
            rect = androidx.compose.ui.geometry.Rect(right - 2 * r, top, right, top + 2 * r),
            startAngleDegrees = -90f,
            sweepAngleDegrees = 90f,
            forceMoveTo = false
        )
        lineTo(right, bottom - r)
        arcTo(
            rect = androidx.compose.ui.geometry.Rect(right - 2 * r, bottom - 2 * r, right, bottom),
            startAngleDegrees = 0f,
            sweepAngleDegrees = 90f,
            forceMoveTo = false
        )
        lineTo(left + r, bottom)
        arcTo(
            rect = androidx.compose.ui.geometry.Rect(left, bottom - 2 * r, left + 2 * r, bottom),
            startAngleDegrees = 90f,
            sweepAngleDegrees = 90f,
            forceMoveTo = false
        )
        lineTo(left, top + r)
        arcTo(
            rect = androidx.compose.ui.geometry.Rect(left, top, left + 2 * r, top + 2 * r),
            startAngleDegrees = 180f,
            sweepAngleDegrees = 90f,
            forceMoveTo = false
        )
        close()
    }
}

/**
 * Material 3 animated perimeter border progress modifier.
 * Traces a single continuous stroke around the rounded-rect boundary of a pill/container based on [progress] (0f..1f).
 * If [progress] is null, an indeterminate traveling arc is drawn around the perimeter.
 */
fun Modifier.pillBorderProgress(
    progress: Float?,
    strokeWidth: Dp = 2.dp,
    activeColor: Color,
    trackColor: Color = Color.Transparent,
    cornerRadius: Dp = 24.dp
): Modifier = this.then(
    Modifier.drawWithContent {
        drawContent()

        val width = size.width
        val height = size.height
        if (width <= 0f || height <= 0f) return@drawWithContent

        val strokePx = strokeWidth.toPx()
        val radiusPx = cornerRadius.toPx().coerceAtMost(height / 2f)

        val fullPath = buildPillPerimeterPath(width, height, strokePx, radiusPx)

        if (trackColor != Color.Transparent) {
            drawPath(
                path = fullPath,
                color = trackColor,
                style = Stroke(width = strokePx, cap = StrokeCap.Round)
            )
        }

        val androidPath = fullPath.asAndroidPath()
        val pathMeasure = PathMeasure(androidPath, true)
        val totalLength = pathMeasure.length
        if (totalLength <= 0f) return@drawWithContent

        if (progress != null) {
            val clampedProgress = progress.coerceIn(0f, 1f)
            if (clampedProgress > 0f) {
                val dstAndroidPath = android.graphics.Path()
                val stopDist = totalLength * clampedProgress
                pathMeasure.getSegment(0f, stopDist, dstAndroidPath, true)

                drawPath(
                    path = dstAndroidPath.asComposePath(),
                    color = activeColor,
                    style = Stroke(width = strokePx, cap = StrokeCap.Round)
                )
            }
        }
    }
)

/**
 * Composable pill wrapper with animated border progress and indeterminate rotation support.
 */
@Composable
fun PillBorderProgressContainer(
    progress: Float?,
    modifier: Modifier = Modifier,
    strokeWidth: Dp = 2.dp,
    activeColor: Color = MaterialTheme.colorScheme.primary,
    trackColor: Color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f),
    cornerRadius: Dp = 24.dp,
    content: @Composable () -> Unit
) {
    val animatedProgress by animateFloatAsState(
        targetValue = progress ?: 0f,
        animationSpec = tween(durationMillis = 350, easing = LinearEasing),
        label = "PillBorderProgressAnim"
    )

    val infiniteTransition = rememberInfiniteTransition(label = "IndeterminateBorderAnim")
    val indeterminatePhase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1500, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "IndeterminatePhase"
    )

    Box(
        modifier = modifier.drawWithContent {
            drawContent()

            val width = size.width
            val height = size.height
            if (width <= 0f || height <= 0f) return@drawWithContent

            val strokePx = strokeWidth.toPx()
            val radiusPx = cornerRadius.toPx().coerceAtMost(height / 2f)

            val fullPath = buildPillPerimeterPath(width, height, strokePx, radiusPx)

            if (trackColor != Color.Transparent) {
                drawPath(
                    path = fullPath,
                    color = trackColor,
                    style = Stroke(width = strokePx, cap = StrokeCap.Round)
                )
            }

            val androidPath = fullPath.asAndroidPath()
            val pathMeasure = PathMeasure(androidPath, true)
            val totalLength = pathMeasure.length
            if (totalLength <= 0f) return@drawWithContent

            val dstAndroidPath = android.graphics.Path()

            if (progress != null) {
                val clamped = animatedProgress.coerceIn(0f, 1f)
                if (clamped > 0f) {
                    val stopDist = totalLength * clamped
                    pathMeasure.getSegment(0f, stopDist, dstAndroidPath, true)
                    drawPath(
                        path = dstAndroidPath.asComposePath(),
                        color = activeColor,
                        style = Stroke(width = strokePx, cap = StrokeCap.Round)
                    )
                }
            } else {
                // Indeterminate comet loop around perimeter
                val segmentLen = totalLength * 0.25f
                val startDist = (totalLength * indeterminatePhase) % totalLength
                val endDist = startDist + segmentLen

                if (endDist <= totalLength) {
                    pathMeasure.getSegment(startDist, endDist, dstAndroidPath, true)
                } else {
                    pathMeasure.getSegment(startDist, totalLength, dstAndroidPath, true)
                    pathMeasure.getSegment(0f, endDist - totalLength, dstAndroidPath, true)
                }

                drawPath(
                    path = dstAndroidPath.asComposePath(),
                    color = activeColor,
                    style = Stroke(width = strokePx, cap = StrokeCap.Round)
                )
            }
        }
    ) {
        content()
    }
}

/**
 * Material 3 Expressive Wavy Linear Progress Indicator.
 * Renders a sinusoidal active wave track that transitions to a flat trailing track.
 */
@Composable
fun M3WavyLinearProgressIndicator(
    progress: Float,
    modifier: Modifier = Modifier,
    activeColor: Color = MaterialTheme.colorScheme.primary,
    trackColor: Color = MaterialTheme.colorScheme.surfaceContainerHighest,
    strokeWidth: Dp = 3.5.dp,
    waveAmplitude: Dp = 2.dp,
    waveLength: Dp = 16.dp,
    isIndeterminate: Boolean = false
) {
    val animatedProgress by animateFloatAsState(
        targetValue = progress.coerceIn(0f, 1f),
        animationSpec = tween(durationMillis = 300, easing = LinearEasing),
        label = "WavyProgressAnim"
    )

    val infiniteTransition = rememberInfiniteTransition(label = "WavyPhaseAnim")
    val phase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 2f * PI.toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "WavyPhase"
    )

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(strokeWidth * 2 + waveAmplitude * 2)
    ) {
        val width = size.width
        val height = size.height
        val centerY = height / 2f
        val strokePx = strokeWidth.toPx()
        val ampPx = waveAmplitude.toPx()
        val waveLenPx = waveLength.toPx()

        if (width <= 0f) return@Canvas

        // 1. Draw background track (flat line)
        drawLine(
            color = trackColor,
            start = androidx.compose.ui.geometry.Offset(0f, centerY),
            end = androidx.compose.ui.geometry.Offset(width, centerY),
            strokeWidth = strokePx,
            cap = StrokeCap.Round
        )

        // 2. Draw active wavy path
        val activeWidth = if (isIndeterminate) width else width * animatedProgress
        if (activeWidth > 0f) {
            val wavePath = Path()
            var x = 0f
            val step = 2f // px step for smooth sine curve

            wavePath.moveTo(0f, centerY + ampPx * sin((phase).toDouble()).toFloat())

            while (x <= activeWidth) {
                val y = centerY + ampPx * sin(((x / waveLenPx) * 2 * PI + phase).toDouble()).toFloat()
                wavePath.lineTo(x, y)
                x += step
            }

            drawPath(
                path = wavePath,
                color = activeColor,
                style = Stroke(width = strokePx, cap = StrokeCap.Round)
            )
        }
    }
}
