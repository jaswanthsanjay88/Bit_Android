package com.bit.ui.screen.intro

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.dp
import com.bit.ui.theme.BitColors
import kotlinx.coroutines.delay

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun IntroScreen(
    innerPadding: PaddingValues,
    sharedTransitionScope: SharedTransitionScope,
    animatedVisibilityScope: AnimatedVisibilityScope,
    targetDestination: String?,
    onFinish: (String) -> Unit = {}
) {
    val infiniteTransition = rememberInfiniteTransition(label = "splash_ring")
    
    // Pulse ring animation: scale 1.0 -> 1.15, alpha 15% -> 8%
    val scale by infiniteTransition.animateFloat(
        initialValue = 1.0f,
        targetValue = 1.2f,
        animationSpec = infiniteRepeatable(
            animation = tween(1800, easing = EaseInOutSine),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse_scale"
    )
    
    val ringAlpha by infiniteTransition.animateFloat(
        initialValue = 0.15f,
        targetValue = 0.05f,
        animationSpec = infiniteRepeatable(
            animation = tween(1800, easing = EaseInOutSine),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse_alpha"
    )

    // Sweeping shine animation: sweeps X offset across the logo
    val shimmerTranslateX by infiniteTransition.animateFloat(
        initialValue = -150f,
        targetValue = 250f,
        animationSpec = infiniteRepeatable(
            animation = tween(1500, delayMillis = 200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "shimmer_x"
    )

    // Reveal entry animation (scale and alpha fade-in)
    val revealProgress = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        revealProgress.animateTo(
            targetValue = 1f,
            animationSpec = tween(1000, easing = FastOutSlowInEasing)
        )
    }

    val revealAlpha = revealProgress.value
    val revealScale = 0.75f + (revealProgress.value * 0.25f)

    val startTime = remember { System.currentTimeMillis() }
    LaunchedEffect(targetDestination) {
        val target = targetDestination ?: return@LaunchedEffect
        val elapsed = System.currentTimeMillis() - startTime
        val remaining = (1600L - elapsed).coerceAtLeast(0L) // Slightly longer to appreciate the shimmer
        delay(remaining)
        onFinish(target)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(BitColors.Background)
            .padding(innerPadding),
        contentAlignment = Alignment.Center
    ) {
        // Faint animated pulse ring behind the mark
        Box(
            modifier = Modifier
                .size(160.dp)
                .scale(scale)
                .alpha(ringAlpha)
                .border(1.dp, Color.White, CircleShape)
        )

        with(sharedTransitionScope) {
            androidx.compose.foundation.Image(
                painter = androidx.compose.ui.res.painterResource(id = com.bit.R.drawable.ic_logo),
                contentDescription = "BIT Logo",
                modifier = Modifier
                    .size(80.dp)
                    .sharedElement(
                        sharedTransitionScope.rememberSharedContentState(key = "bit_mark"),
                        animatedVisibilityScope = animatedVisibilityScope
                    )
                    .graphicsLayer {
                        // Apply reveal scale and alpha
                        alpha = revealAlpha
                        scaleX = revealScale
                        scaleY = revealScale
                    }
                    .drawWithContent {
                        drawContent() // Render the logo vector
                        
                        // Render sweeping shine/reflection effect
                        val width = size.width
                        val height = size.height
                        val xOffset = shimmerTranslateX * width / 100f
                        
                        drawRect(
                            brush = Brush.linearGradient(
                                colors = listOf(
                                    Color.Transparent,
                                    Color.White.copy(alpha = 0.15f),
                                    Color.White.copy(alpha = 0.65f),
                                    Color.White.copy(alpha = 0.15f),
                                    Color.Transparent
                                ),
                                start = Offset(xOffset - 40f, 0f),
                                end = Offset(xOffset + 40f, height)
                            ),
                            blendMode = BlendMode.SrcAtop
                        )
                    }
            )
        }
    }
}
