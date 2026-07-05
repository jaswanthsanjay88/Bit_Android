package com.bit.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.backdrops.rememberCombinedBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.lens
import kotlin.math.roundToInt

@Composable
fun GlassSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    backdrop: Backdrop,
    modifier: Modifier = Modifier,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    trackColor: Color = Color(0xFF262626) // Monochrome default border/track color
) {
    val density = LocalDensity.current
    val thumbWidth = 56.dp
    val thumbHeight = 32.dp

    BoxWithConstraints(
        modifier = modifier
            .padding(horizontal = 24.dp)
            .fillMaxWidth()
            .height(48.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        val trackBackdrop = rememberLayerBackdrop()
        val maxOffsetPx = constraints.maxWidth - with(density) { thumbWidth.toPx() }
        
        // Calculate current position fraction
        val rangeLength = valueRange.endInclusive - valueRange.start
        val fraction = if (rangeLength > 0f) {
            ((value - valueRange.start) / rangeLength).coerceIn(0f, 1f)
        } else 0f

        val currentOffsetPx = fraction * maxOffsetPx

        // Track bar
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .layerBackdrop(trackBackdrop)
                .background(trackColor, CircleShape)
                .height(6.dp)
        )

        // Interactive thumb with lens refraction overlaying track & background
        Box(
            modifier = Modifier
                .offset { IntOffset(currentOffsetPx.roundToInt(), 0) }
                .draggable(
                    orientation = Orientation.Horizontal,
                    state = rememberDraggableState { delta ->
                        val newOffsetPx = (currentOffsetPx + delta).coerceIn(0f, maxOffsetPx)
                        val newValue = valueRange.start + (newOffsetPx / maxOffsetPx) * rangeLength
                        onValueChange(newValue)
                    }
                )
                .drawBackdrop(
                    // Combines background backdrop and the track backdrop simultaneously
                    backdrop = rememberCombinedBackdrop(backdrop, trackBackdrop),
                    shape = { CircleShape },
                    effects = {
                        lens(
                            refractionHeight = 12f.dp.toPx(),
                            refractionAmount = 16f.dp.toPx(),
                            chromaticAberration = true
                        )
                    }
                )
                .size(thumbWidth, thumbHeight)
        )
    }
}
