package com.bit.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeProgressive
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect

/**
 * Dynamic Frosted-Glass Scrim overlay that blurs content scrolling underneath the top toolbar.
 * Uses Haze to sample real-time content underneath the TopBar, applying a vertical opacity dissolve
 * and a hairline bottom divider for a soft edge.
 */
@Composable
fun TopBlurScrim(
    hazeState: HazeState,
    modifier: Modifier = Modifier,
    height: Dp = 100.dp,
    blurRadius: Dp = 20.dp,
    tintColor: Color = MaterialTheme.colorScheme.background,
    tintAlpha: Float = 0.6f
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
    ) {
        // Real-time frosted glass blur sampler with progressive vertical gradient
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
                .hazeEffect(state = hazeState) {
                    style = HazeStyle(
                        backgroundColor = tintColor,
                        tint = HazeTint(tintColor.copy(alpha = tintAlpha)),
                        blurRadius = blurRadius
                    )
                    progressive = HazeProgressive.verticalGradient(
                        startIntensity = 1.0f,
                        endIntensity = 0.0f
                    )
                }
                .drawWithContent {
                    drawContent()
                    drawRect(
                        brush = Brush.verticalGradient(
                            colors = listOf(Color.Black, Color.Transparent)
                        ),
                        blendMode = BlendMode.DstIn
                    )
                }
        )

        // Hairline bottom border demarcating the frosted glass edge from scrolling messages
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(0.5.dp)
                .align(Alignment.BottomCenter)
                .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
        )
    }
}
