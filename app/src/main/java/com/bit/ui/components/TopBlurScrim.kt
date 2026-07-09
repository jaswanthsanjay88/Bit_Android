package com.bit.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeProgressive
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeEffect

@Composable
fun TopBlurScrim(
    hazeState: HazeState,
    modifier: Modifier = Modifier,
    height: Dp = 160.dp,
    blurRadius: Dp = 26.dp,
    tintColor: Color = Color.Black,
    tintAlpha: Float = 0.55f
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
    ) {
        // Layer 1: The dynamic progressive blur
        Box(
            modifier = Modifier
                .fillMaxSize()
                .hazeEffect(state = hazeState) {
                    progressive = HazeProgressive.verticalGradient(
                        startIntensity = 1f,
                        endIntensity = 0f
                    )
                    this.blurRadius = blurRadius
                }
        )
        // Layer 2: Matching darkness overlay fade
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            tintColor.copy(alpha = tintAlpha),
                            tintColor.copy(alpha = 0f)
                        )
                    )
                )
        )
    }
}
