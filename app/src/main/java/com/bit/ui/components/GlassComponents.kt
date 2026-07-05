package com.bit.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import com.bit.global.Standards
import com.bit.ui.theme.Glass
import com.bit.ui.theme.Motion

// ═══════════════════════════════════════════════════════════════════════════════
// Glassmorphic Component Library
// Reusable frosted-glass composables for premium enterprise UI
// ═══════════════════════════════════════════════════════════════════════════════

/**
 * Premium glassmorphic card with translucent background and subtle border.
 * Use for all elevated content containers, settings groups, and feature cards.
 */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    backgroundColor: Color = Glass.Surface,
    borderColor: Color = Glass.BorderSubtle,
    cornerRadius: Dp = Standards.CardCornerRadius,
    borderWidth: Dp = 1.dp,
    contentPadding: PaddingValues = PaddingValues(Standards.CardPadding),
    onClick: (() -> Unit)? = null,
    content: @Composable () -> Unit
) {
    val shape = RoundedCornerShape(cornerRadius)
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.98f else 1f,
        animationSpec = Motion.interactive(),
        label = "glassCardScale"
    )

    val glassBrush = Brush.linearGradient(
        colors = listOf(
            backgroundColor.copy(alpha = (backgroundColor.alpha * 1.4f).coerceAtMost(1f)),
            backgroundColor.copy(alpha = (backgroundColor.alpha * 0.6f).coerceAtMost(1f))
        )
    )

    val borderBrush = Brush.linearGradient(
        colors = listOf(
            borderColor.copy(alpha = (borderColor.alpha * 1.8f).coerceAtMost(1f)),
            borderColor.copy(alpha = (borderColor.alpha * 0.4f).coerceAtMost(1f))
        )
    )

    val baseModifier = modifier
        .scale(scale)
        .clip(shape)
        .background(glassBrush, shape)
        .border(borderWidth, borderBrush, shape)

    val finalModifier = if (onClick != null) {
        val haptic = LocalHapticFeedback.current
        baseModifier.clickable(
            interactionSource = interactionSource,
            indication = null,
            onClick = {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                onClick()
            }
        )
    } else {
        baseModifier
    }

    Box(
        modifier = finalModifier.padding(contentPadding)
    ) {
        content()
    }
}

/**
 * Glassmorphic card with icon header, title, and optional description.
 * Use for settings sections, feature groups, and info panels.
 */
@Composable
fun GlassSectionCard(
    modifier: Modifier = Modifier,
    title: String,
    icon: ImageVector? = null,
    iconTint: Color = Glass.AccentPrimary,
    description: String? = null,
    backgroundColor: Color = Glass.Surface,
    borderColor: Color = Glass.BorderSubtle,
    trailing: @Composable (RowScope.() -> Unit)? = null,
    content: @Composable () -> Unit
) {
    GlassCard(
        modifier = modifier.fillMaxWidth(),
        backgroundColor = backgroundColor,
        borderColor = borderColor
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(Standards.SpacingSm)
        ) {
            // Header row
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Standards.SpacingSm)
            ) {
                if (icon != null) {
                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .background(iconTint.copy(alpha = 0.12f), RoundedCornerShape(8.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = icon,
                            contentDescription = null,
                            modifier = Modifier.size(Standards.IconMd),
                            tint = iconTint
                        )
                    }
                }

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = Glass.TextPrimary
                    )
                    if (description != null) {
                        Text(
                            text = description,
                            style = MaterialTheme.typography.labelSmall,
                            color = Glass.TextMuted,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                if (trailing != null) {
                    trailing()
                }
            }

            // Content
            content()
        }
    }
}

/**
 * Glassmorphic pill-shaped chip with optional icon and active glow.
 * Use for feature toggles, status indicators, and filter tags.
 */
@Composable
fun GlassChip(
    text: String,
    modifier: Modifier = Modifier,
    isActive: Boolean = false,
    activeColor: Color = Glass.AccentPrimary,
    icon: ImageVector? = null,
    onClick: (() -> Unit)? = null
) {
    val backgroundColor by animateColorAsState(
        targetValue = if (isActive) activeColor.copy(alpha = 0.15f) else Glass.SurfaceSubtle,
        animationSpec = Motion.state(),
        label = "chipBg"
    )
    val borderColor by animateColorAsState(
        targetValue = if (isActive) activeColor.copy(alpha = 0.4f) else Glass.BorderSubtle,
        animationSpec = Motion.state(),
        label = "chipBorder"
    )
    val textColor by animateColorAsState(
        targetValue = if (isActive) activeColor else Glass.TextSecondary,
        animationSpec = Motion.state(),
        label = "chipText"
    )

    val shape = RoundedCornerShape(Standards.ChipCornerRadius)

    val baseModifier = modifier
        .height(Standards.ChipHeight)
        .clip(shape)
        .background(backgroundColor, shape)
        .border(1.dp, borderColor, shape)

    val finalModifier = if (onClick != null) {
        val haptic = LocalHapticFeedback.current
        baseModifier.clickable(onClick = {
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            onClick()
        })
    } else {
        baseModifier
    }

    val horizontalPadding = if (text.isEmpty()) 10.dp else Standards.ChipHorizontalPadding
    Row(
        modifier = finalModifier.padding(
            horizontal = horizontalPadding,
            vertical = Standards.SpacingXs
        ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Standards.SpacingXs)
    ) {
        if (isActive && text.isNotEmpty()) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .background(activeColor, CircleShape)
            )
        }

        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(Standards.ChipIconSize),
                tint = textColor
            )
        }

        if (text.isNotEmpty()) {
            Text(
                text = text,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Normal,
                color = textColor,
                maxLines = 1
            )
        }
    }
}

/**
 * Glassmorphic divider — subtle gradient line for section separation.
 */
@Composable
fun GlassDivider(
    modifier: Modifier = Modifier,
    color: Color = Glass.Divider
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(
                brush = Brush.horizontalGradient(
                    colors = listOf(
                        Color.Transparent,
                        color,
                        color,
                        Color.Transparent
                    )
                )
            )
    )
}
