package com.bit.ui.components

import android.annotation.SuppressLint
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import com.bit.ui.theme.Motion

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.bit.ui.theme.Glass

/**
 * Cute compact switch component with smooth animations and glassmorphic styling.
 */
@SuppressLint("ModifierParameter")
@Composable
fun CuteSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    width: Dp = 44.dp,
    height: Dp = 24.dp,
    thumbSize: Dp = 18.dp,
    checkedTrackColor: Color = Glass.AccentPrimary,
    uncheckedTrackColor: Color = Glass.SurfaceMedium,
    checkedThumbColor: Color = Glass.TextOnAccent,
    uncheckedThumbColor: Color = Glass.TextMuted,
    thumbIcon: ImageVector? = null,
    thumbIconChecked: ImageVector? = null
) {
    val interactionSource = remember { MutableInteractionSource() }
    
    val trackColor by animateColorAsState(
        targetValue = if (checked) checkedTrackColor else uncheckedTrackColor,
        animationSpec = Motion.state(),
        label = "trackColor"
    )

    val thumbColor by animateColorAsState(
        targetValue = if (checked) checkedThumbColor else uncheckedThumbColor,
        animationSpec = Motion.state(),
        label = "thumbColor"
    )

    val thumbOffset by animateDpAsState(
        targetValue = if (checked) width - thumbSize - 3.dp else 3.dp,
        animationSpec = Motion.interactive(),
        label = "thumbOffset"
    )

    val thumbScale by animateFloatAsState(
        targetValue = if (enabled) 1f else 0.9f,
        animationSpec = Motion.state(),
        label = "thumbScale"
    )

    // Animate glow border for checked state
    val glowBorder by animateColorAsState(
        targetValue = if (checked) Glass.AccentPrimary.copy(alpha = 0.3f) else Color.Transparent,
        animationSpec = Motion.state(),
        label = "thumbGlow"
    )

    Box(
        modifier = modifier
            .width(width)
            .height(height)
            .clip(RoundedCornerShape(height / 2))
            .background(trackColor)
            .border(
                width = 0.8.dp,
                color = if (checked) Glass.BorderActive else Glass.BorderSubtle,
                shape = RoundedCornerShape(height / 2)
            )
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                enabled = enabled,
                role = Role.Switch,
                onClick = { onCheckedChange(!checked) }
            ),
        contentAlignment = Alignment.CenterStart
    ) {
        Box(
            modifier = Modifier
                .offset(x = thumbOffset)
                .size(thumbSize)
                .scale(thumbScale)
                .background(thumbColor, CircleShape)
                .border(
                    width = if (checked) 1.5.dp else 1.5.dp,
                    color = if (checked) glowBorder else trackColor.copy(alpha = 0.2f),
                    shape = CircleShape
                ),
            contentAlignment = Alignment.Center
        ) {
            // Optional icon in thumb
            val currentIcon = if (checked) thumbIconChecked ?: thumbIcon else thumbIcon
            currentIcon?.let { icon ->
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(12.dp),
                    tint = trackColor
                )
            }
        }
    }
}
