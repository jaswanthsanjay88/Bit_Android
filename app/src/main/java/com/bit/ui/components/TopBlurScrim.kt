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
    height: Dp = 140.dp,
    maxBlurRadius: Dp = 28.dp,
    scrimColor: Color = MaterialTheme.colorScheme.background
) {
    val density = LocalDensity.current
    val maxBlurPx = with(density) { maxBlurRadius.toPx() }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && maxBlurPx > 0f) {
            // Layer 1: Native hardware blur with progressive alpha mask
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        renderEffect = RenderEffect
                            .createBlurEffect(
                                maxBlurPx,
                                maxBlurPx,
                                Shader.TileMode.CLAMP
                            )
                            .asComposeRenderEffect()
                        compositingStrategy = CompositingStrategy.Offscreen
                    }
                    .drawWithContent {
                        drawContent()
                        drawRect(
                            brush = Brush.verticalGradient(
                                colorStops = arrayOf(
                                    0.0f to Color.Black,
                                    0.45f to Color.Black.copy(alpha = 0.85f),
                                    0.75f to Color.Black.copy(alpha = 0.40f),
                                    1.0f to Color.Transparent
                                )
                            ),
                            blendMode = BlendMode.DstIn
                        )
                    }
            )

            // Layer 2: Secondary soft blur pass for smooth progressive falloff
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        renderEffect = RenderEffect
                            .createBlurEffect(
                                maxBlurPx * 0.4f,
                                maxBlurPx * 0.4f,
                                Shader.TileMode.CLAMP
                            )
                            .asComposeRenderEffect()
                        compositingStrategy = CompositingStrategy.Offscreen
                    }
                    .drawWithContent {
                        drawContent()
                        drawRect(
                            brush = Brush.verticalGradient(
                                colorStops = arrayOf(
                                    0.0f to Color.Black.copy(alpha = 0.5f),
                                    0.5f to Color.Black.copy(alpha = 0.7f),
                                    0.85f to Color.Black.copy(alpha = 0.2f),
                                    1.0f to Color.Transparent
                                )
                            ),
                            blendMode = BlendMode.DstIn
                        )
                    }
            )
        }

        // Layer 3: Material 3 Tonal Scrim Gradient (Smooth fade into content background)
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    brush = Brush.verticalGradient(
                        colorStops = arrayOf(
                            0.00f to scrimColor.copy(alpha = 0.94f),
                            0.30f to scrimColor.copy(alpha = 0.85f),
                            0.60f to scrimColor.copy(alpha = 0.55f),
                            0.85f to scrimColor.copy(alpha = 0.20f),
                            1.00f to Color.Transparent
                        )
                    )
                )
        )
    }
}
