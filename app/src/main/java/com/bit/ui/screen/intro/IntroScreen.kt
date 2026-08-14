package com.bit.ui.screen.intro

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import com.bit.ui.theme.BitColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// ---------------------------------------------------------------------------
// MOTION TOKENS — one easing curve used everywhere, this is what makes the
// whole reveal feel like ONE designed system instead of separate toys.
// ---------------------------------------------------------------------------
val EmphasizedEasing = CubicBezierEasing(0.2f, 0.0f, 0f, 1.0f)
val EmphasizedDecelerate = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1.0f)

// ---------------------------------------------------------------------------
// LOGO GEOMETRY — bar + top block + bottom block, no outer frame (see prior
// note: thin frame strokes disappear at small icon sizes).
// Tune the fractional constants against your real PNG until they line up.
// ---------------------------------------------------------------------------
private data class LogoShape(val path: Path, val bounds: Rect, val revealFromTop: Boolean)

private fun buildLogoShapes(canvasSize: Size): List<LogoShape> {
    val w = canvasSize.width
    val h = canvasSize.height

    val barLeft = w * 0.28f
    val barWidth = w * 0.12f
    val barTop = h * 0.22f
    val barBottom = h * 0.78f

    val blockLeft = w * 0.47f
    val blockRight = w * 0.72f
    val blockTop = h * 0.22f
    val blockMidGapTop = h * 0.485f
    val blockMidGapBottom = h * 0.515f
    val blockBottom = h * 0.78f

    val barRect = Rect(barLeft, barTop, barLeft + barWidth, barBottom)
    val topBlockRect = Rect(blockLeft, blockTop, blockRight, blockMidGapTop)
    val bottomBlockRect = Rect(blockLeft, blockMidGapBottom, blockRight, blockBottom)

    return listOf(
        LogoShape(Path().apply { addRect(barRect) }, barRect, revealFromTop = false), // bar rises bottom->top
        LogoShape(Path().apply { addRect(topBlockRect) }, topBlockRect, revealFromTop = true),
        LogoShape(Path().apply { addRect(bottomBlockRect) }, bottomBlockRect, revealFromTop = true)
    )
}

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun IntroScreen(
    innerPadding: PaddingValues,
    sharedTransitionScope: SharedTransitionScope,
    animatedVisibilityScope: AnimatedVisibilityScope,
    targetDestination: String?,
    onFinish: (String) -> Unit = {}
) {
    var animationFinished by remember { mutableStateOf(false) }

    // Navigate only when BOTH the animation has finished AND the target destination is loaded/resolved
    LaunchedEffect(targetDestination, animationFinished) {
        if (animationFinished && targetDestination != null) {
            onFinish(targetDestination)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(BitColors.Background)
            .padding(innerPadding),
        contentAlignment = Alignment.Center
    ) {
        with(sharedTransitionScope) {
            Box(
                modifier = Modifier
                    .size(120.dp)
                    .sharedElement(
                        sharedTransitionScope.rememberSharedContentState(key = "bit_mark"),
                        animatedVisibilityScope = animatedVisibilityScope
                    )
            ) {
                ElementLogoReveal(
                    modifier = Modifier.fillMaxSize(),
                    onFinished = {
                        animationFinished = true
                    }
                )
            }
        }
    }
}

// Each shape is revealed via a clip-wipe (the shape is always full size and
// in its final position — a mask simply slides across it), not a scale-pop.
// All three shapes use the SAME easing curve and SAME duration; only their
// START TIME is staggered. That single-easing-curve rule is what keeps this
// from looking like three unrelated animations bolted together.
@Composable
fun ElementLogoReveal(
    modifier: Modifier = Modifier,
    onFinished: () -> Unit = {}
) {
    // one progress value per shape, all driven by the same tween spec
    val barReveal = remember { Animatable(0f) }
    val topBlockReveal = remember { Animatable(0f) }
    val bottomBlockReveal = remember { Animatable(0f) }

    // subtle outline glow that traces alongside the reveal, fades once solid
    val glowAlpha = remember { Animatable(0.35f) }

    // final settle — barely-there scale pulse, not a bounce
    val settleScale = remember { Animatable(1f) }

    val revealDuration = 280 // crisp and snappy
    val stagger = 50 // fast fluid stagger

    LaunchedEffect(Unit) {
        delay(40) // minimal hold

        launch { barReveal.animateTo(1f, tween(revealDuration, easing = EmphasizedEasing)) }
        delay(stagger.toLong())
        launch { topBlockReveal.animateTo(1f, tween(revealDuration, easing = EmphasizedEasing)) }
        delay(stagger.toLong())
        launch { bottomBlockReveal.animateTo(1f, tween(revealDuration, easing = EmphasizedEasing)) }

        // wait for the reveal to finish
        delay(revealDuration.toLong())

        // glow recedes quickly
        launch { glowAlpha.animateTo(0f, tween(150, easing = EmphasizedEasing)) }

        // snappy settle
        settleScale.animateTo(1.015f, tween(80, easing = EmphasizedEasing))
        settleScale.animateTo(1f, tween(100, easing = EmphasizedDecelerate))

        delay(40)
        onFinished()
    }

    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { scaleX = settleScale.value; scaleY = settleScale.value }
        ) {
            val shapes = buildLogoShapes(size)
            val progresses = listOf(barReveal.value, topBlockReveal.value, bottomBlockReveal.value)

            shapes.forEachIndexed { index, shape ->
                val progress = progresses[index]
                if (progress <= 0f) return@forEachIndexed

                val b = shape.bounds
                // clip rect grows along the vertical axis toward the shape's full bounds —
                // top blocks reveal downward (like ink filling), the bar reveals upward
                // (like it's rising from the ground) — small directional variety without
                // breaking the single-easing-curve rule, since it's still driven by the
                // exact same progress/timing, just a different clip axis.
                val clipRect = if (shape.revealFromTop) {
                    Rect(b.left, b.top, b.right, b.top + (b.height * progress))
                } else {
                    Rect(b.left, b.bottom - (b.height * progress), b.right, b.bottom)
                }

                clipRect(clipRect.left, clipRect.top, clipRect.right, clipRect.bottom) {
                    // soft glow trace along the leading edge — thin, not overpowering
                    drawRect(
                        color = Color.White.copy(alpha = glowAlpha.value),
                        topLeft = Offset(b.left - 2f, b.top - 2f),
                        size = Size(b.width + 4f, b.height + 4f),
                        blendMode = BlendMode.Plus
                    )
                    // the actual solid fill
                    drawPath(path = shape.path, color = Color.White, style = Fill)
                }
            }
        }
    }
}

@Composable
fun AnimatedLogo(
    modifier: Modifier = Modifier,
    color: Color = Color.White
) {
    Canvas(modifier = modifier) {
        val shapes = buildLogoShapes(size)
        shapes.forEach { shape ->
            drawPath(path = shape.path, color = color, style = Fill)
        }
    }
}
