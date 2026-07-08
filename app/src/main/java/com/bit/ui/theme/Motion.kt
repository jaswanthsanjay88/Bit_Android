package com.bit.ui.theme

import android.view.HapticFeedbackConstants
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.scale
import androidx.compose.ui.platform.LocalView

// Centralized animation tokens — use these instead of inline spring()/tween() calls.
object Motion {

    // Material 3 Emphasized easing curves
    val EmphasizedEasing = CubicBezierEasing(0.2f, 0.0f, 0f, 1.0f)
    val EmphasizedDecelerate = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1.0f)
    val EmphasizedAccelerate = CubicBezierEasing(0.3f, 0.0f, 0.8f, 0.15f)

    // Durations — M3 spec tokens
    const val DurationShort = 200   // small state changes (icon toggle)
    const val DurationMedium = 300  // standard transitions (card expand)
    const val DurationLong = 500    // large/complex transitions (screen change)

    // Interactive press/toggle feedback — snappy with slight bounce
    fun <T> interactive(): FiniteAnimationSpec<T> = spring(
        dampingRatio = Spring.DampingRatioMediumBouncy,
        stiffness = Spring.StiffnessMedium
    )

    // Content appear/disappear, expand/collapse
    fun <T> content(): FiniteAnimationSpec<T> = spring(
        dampingRatio = Spring.DampingRatioNoBouncy,
        stiffness = Spring.StiffnessMedium
    )

    // State changes — color, alpha, size
    fun <T> state(): FiniteAnimationSpec<T> = tween(
        durationMillis = DurationShort,
        easing = EmphasizedEasing
    )

    // Page/modal entrance — Emphasized deceleration
    fun <T> entrance(): FiniteAnimationSpec<T> = tween(
        durationMillis = DurationMedium,
        easing = EmphasizedDecelerate
    )

    // Exit — Emphasized acceleration
    fun <T> exit(): FiniteAnimationSpec<T> = tween(
        durationMillis = DurationShort,
        easing = EmphasizedAccelerate
    )

    // Standard enter transition for AnimatedVisibility
    val Enter: EnterTransition = fadeIn(tween(DurationMedium, easing = EmphasizedDecelerate)) +
        expandVertically(tween(DurationMedium, easing = EmphasizedDecelerate), expandFrom = Alignment.Top)

    // Standard exit transition for AnimatedVisibility
    val Exit: ExitTransition = fadeOut(tween(DurationShort, easing = EmphasizedAccelerate)) +
        shrinkVertically(tween(DurationShort, easing = EmphasizedAccelerate), shrinkTowards = Alignment.Top)
}

/**
 * MD3 Expressive bouncy wobble animation for buttons.
 * Scales down slightly when pressed and adds haptic feedback.
 */
fun Modifier.bouncyClick(
    scaleDown: Float = 0.90f,
    enabled: Boolean = true,
    onClick: () -> Unit
): Modifier = composed {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    
    val scale by animateFloatAsState(
        targetValue = if (isPressed && enabled) scaleDown else 1f,
        animationSpec = Motion.interactive(),
        label = "bouncyClickScale"
    )

    val haptics = com.bit.ui.theme.LocalBitHaptics.current

    this
        .scale(scale)
        .clickable(
            interactionSource = interactionSource,
            indication = LocalIndication.current,
            enabled = enabled,
            onClick = {
                haptics.selection()
                onClick()
            }
        )
}
