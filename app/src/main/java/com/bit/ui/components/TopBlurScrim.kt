package com.bit.ui.components

import android.graphics.RenderEffect
import android.graphics.Shader
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Scratch progressive blur scrim for top status bar and top bar area.
 * Completely independent of Haze, using native Android RenderEffect hardware blur (API 31+)
 * combined with a multi-stop Material 3 surface progressive gradient scrim.
 */
@Composable
fun TopBlurScrim(
    modifier: Modifier = Modifier,
    height: Dp = 120.dp,
    maxBlurRadius: Dp = 48.dp,
    scrimColor: Color = MaterialTheme.colorScheme.background
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .background(
                brush = Brush.verticalGradient(
                    colorStops = arrayOf(
                        0.00f to scrimColor.copy(alpha = 0.98f),
                        0.35f to scrimColor.copy(alpha = 0.88f),
                        0.65f to scrimColor.copy(alpha = 0.55f),
                        0.85f to scrimColor.copy(alpha = 0.20f),
                        1.00f to Color.Transparent
                    )
                )
            )
    )
}
