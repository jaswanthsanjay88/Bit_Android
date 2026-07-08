package com.bit.ui.screen.intro

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.graphicsLayer
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
    // Reveal entry animation (subtle scale and cinematic alpha fade-in)
    val revealProgress = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        revealProgress.animateTo(
            targetValue = 1f,
            animationSpec = tween(1200, easing = FastOutSlowInEasing)
        )
    }

    val revealAlpha = revealProgress.value
    val revealScale = 0.95f + (revealProgress.value * 0.05f)

    val startTime = remember { System.currentTimeMillis() }
    LaunchedEffect(targetDestination) {
        val target = targetDestination ?: return@LaunchedEffect
        val elapsed = System.currentTimeMillis() - startTime
        val remaining = (1600L - elapsed).coerceAtLeast(0L)
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
        with(sharedTransitionScope) {
            val invertMatrix = floatArrayOf(
                -1f, 0f, 0f, 0f, 255f,
                0f, -1f, 0f, 0f, 255f,
                0f, 0f, -1f, 0f, 255f,
                0f, 0f, 0f, 1f, 0f
            )

            androidx.compose.foundation.Image(
                painter = androidx.compose.ui.res.painterResource(id = com.bit.R.drawable.ic_logo),
                contentDescription = "BIT Logo",
                colorFilter = androidx.compose.ui.graphics.ColorFilter.colorMatrix(androidx.compose.ui.graphics.ColorMatrix(invertMatrix)),
                modifier = Modifier
                    .size(120.dp) // Make it slightly larger so it looks good
                    .sharedElement(
                        sharedTransitionScope.rememberSharedContentState(key = "bit_mark"),
                        animatedVisibilityScope = animatedVisibilityScope
                    )
                    .graphicsLayer {
                        alpha = revealAlpha
                        scaleX = revealScale
                        scaleY = revealScale
                    }
            )
        }
    }
}
