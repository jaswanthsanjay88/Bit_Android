package com.bit.ui.components

import android.annotation.SuppressLint
import androidx.compose.animation.animateColorAsState
import com.bit.ui.theme.Motion
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.bit.global.Standards
import com.bit.ui.theme.Glass
import com.bit.ui.icons.TnIcons

// ==================== Text Components ====================


/**
 * Caption text - small secondary info text
 */
@Composable
fun CaptionText(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Glass.TextMuted
) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = color,
        modifier = modifier
    )
}

// ==================== Switch Components ====================

/**
 * Standard row with label and ActionSwitch on the right.
 * Optional icon on the left, optional description below the title.
 */
@SuppressLint("ModifierParameter")
@Composable
fun SwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    description: String? = null,
    icon: ImageVector? = null,
    iconRes: Int? = null,
    enabled: Boolean = true,
    titleColor: Color? = null,
    hasBorder: Boolean = true
) {
    val rowContent = @Composable {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    horizontal = if (hasBorder) Standards.CardPadding else 0.dp,
                    vertical = Standards.SpacingSm
                ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Standards.SpacingSm)
        ) {
            // Optional icon
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(Standards.IconMd),
                    tint = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else if (iconRes != null) {
                Icon(
                    painter = painterResource(iconRes),
                    contentDescription = null,
                    modifier = Modifier.size(Standards.IconMd),
                    tint = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Title + description
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = titleColor ?: if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (description != null) {
                    Text(
                        text = description,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (enabled) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                    )
                }
            }

            // Switch
            ActionSwitch(
                checked = checked,
                onCheckedChange = onCheckedChange,
                enabled = enabled
            )
        }
    }

    if (hasBorder) {
        Surface(
            modifier = modifier
                .fillMaxWidth()
                .border(1.dp, Glass.BorderSubtle, RoundedCornerShape(Standards.CardSmallCornerRadius)),
            color = Glass.Surface,
            shape = RoundedCornerShape(Standards.CardSmallCornerRadius)
        ) {
            rowContent()
        }
    } else {
        Box(modifier = modifier) {
            rowContent()
        }
    }
}

/**
 * A beautiful, borderless version of SwitchRow for nesting inside GlassSectionCard.
 */
@Composable
fun SettingsSwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    description: String? = null,
    icon: ImageVector? = null,
    iconRes: Int? = null,
    enabled: Boolean = true,
    titleColor: Color? = null
) {
    SwitchRow(
        title = title,
        checked = checked,
        onCheckedChange = onCheckedChange,
        modifier = modifier,
        description = description,
        icon = icon,
        iconRes = iconRes,
        enabled = enabled,
        titleColor = titleColor,
        hasBorder = false
    )
}

/**
 * Clickable settings row for nesting inside GlassSectionCard.
 */
@Composable
fun SettingsClickableRow(
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    description: String? = null,
    icon: ImageVector? = null,
    iconRes: Int? = null,
    enabled: Boolean = true,
    titleColor: Color? = null,
    trailing: @Composable (RowScope.() -> Unit)? = null
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = Standards.SpacingSm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Standards.SpacingSm)
    ) {
        // Optional icon
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(Standards.IconMd),
                tint = if (enabled) MaterialTheme.colorScheme.onSurface else Glass.TextMuted
            )
        } else if (iconRes != null) {
            Icon(
                painter = painterResource(iconRes),
                contentDescription = null,
                modifier = Modifier.size(Standards.IconMd),
                tint = if (enabled) MaterialTheme.colorScheme.onSurface else Glass.TextMuted
            )
        }

        // Title + description
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = titleColor ?: if (enabled) Glass.TextPrimary else Glass.TextMuted
            )
            if (description != null) {
                Text(
                    text = description,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (enabled) Glass.TextSecondary else Glass.TextMuted
                )
            }
        }

        if (trailing != null) {
            trailing()
        } else {
            Icon(
                imageVector = TnIcons.ChevronRight,
                contentDescription = null,
                tint = Glass.TextSecondary,
                modifier = Modifier.size(Standards.IconSm)
            )
        }
    }
}

// ==================== Card Components ====================

/**
 * Standard card with optional icon, title, description, and custom content slot.
 */
@SuppressLint("ModifierParameter")
@Composable
fun StandardCard(
    modifier: Modifier = Modifier,
    title: String? = null,
    description: String? = null,
    icon: ImageVector? = null,
    iconRes: Int? = null,
    iconTint: Color = Glass.AccentPrimary,
    containerColor: Color = Glass.Surface,
    onClick: (() -> Unit)? = null,
    trailing: @Composable (RowScope.() -> Unit)? = null,
    content: @Composable (() -> Unit)? = null
) {
    val clickModifier = if (onClick != null) {
        Modifier.clickable(onClick = onClick)
    } else {
        Modifier
    }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .border(1.dp, Glass.BorderSubtle, RoundedCornerShape(Standards.CardCornerRadius))
            .then(clickModifier),
        color = containerColor,
        shape = RoundedCornerShape(Standards.CardCornerRadius),
        tonalElevation = 0.dp
    ) {
        Column(
            modifier = Modifier.padding(Standards.CardPadding),
            verticalArrangement = Arrangement.spacedBy(Standards.SpacingSm)
        ) {
            if (title != null || icon != null || iconRes != null || trailing != null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Standards.SpacingSm)
                ) {
                    if (icon != null) {
                        Icon(
                            imageVector = icon,
                            contentDescription = null,
                            modifier = Modifier.size(Standards.IconMd),
                            tint = iconTint
                        )
                    } else if (iconRes != null) {
                        Icon(
                            painter = painterResource(iconRes),
                            contentDescription = null,
                            modifier = Modifier.size(Standards.IconMd),
                            tint = iconTint
                        )
                    }

                    Column(modifier = Modifier.weight(1f)) {
                        if (title != null) {
                            Text(
                                text = title,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = Glass.TextPrimary
                            )
                        }
                        if (description != null) {
                            Text(
                                text = description,
                                style = MaterialTheme.typography.labelSmall,
                                color = Glass.TextSecondary,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    if (trailing != null) {
                        trailing()
                    }
                }
            }

            if (content != null) {
                content()
            }
        }
    }
}

/**
 * Compact info card - used for status indicators, small info panels
 */
@SuppressLint("ModifierParameter")
@Composable
fun InfoCard(
    title: String,
    value: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    iconRes: Int? = null,
    containerColor: Color = Glass.AccentPrimarySurface,
    contentColor: Color = Glass.AccentPrimary
) {
    Surface(
        modifier = modifier
            .border(1.dp, Glass.BorderSubtle, RoundedCornerShape(Standards.CardSmallCornerRadius)),
        color = containerColor,
        shape = RoundedCornerShape(Standards.CardSmallCornerRadius)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = Standards.SpacingSm, vertical = Standards.SpacingXs),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Standards.SpacingXs)
        ) {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(Standards.IconSm),
                    tint = contentColor
                )
            } else if (iconRes != null) {
                Icon(
                    painter = painterResource(iconRes),
                    contentDescription = null,
                    modifier = Modifier.size(Standards.IconSm),
                    tint = contentColor
                )
            }
            Text(
                text = title,
                style = MaterialTheme.typography.labelSmall,
                color = Glass.TextSecondary
            )
            Text(
                text = value,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = contentColor
            )
        }
    }
}

// ==================== Badge Components ====================

/**
 * Small colored badge/chip for tags, labels, and status indicators.
 */
@SuppressLint("ModifierParameter")
@Composable
fun InfoBadge(
    text: String,
    modifier: Modifier = Modifier,
    containerColor: Color = Glass.AccentPrimarySurface,
    contentColor: Color = Glass.AccentPrimary
) {
    Surface(
        modifier = modifier
            .height(Standards.BadgeHeight)
            .border(1.dp, Glass.BorderSubtle, RoundedCornerShape(Standards.SpacingXs)),
        color = containerColor,
        shape = RoundedCornerShape(Standards.SpacingXs)
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.padding(horizontal = Standards.SpacingSm)
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                color = contentColor,
                maxLines = 1
            )
        }
    }
}

/**
 * Status badge with dot indicator and outer glow ring when active
 */
@SuppressLint("ModifierParameter")
@Composable
fun StatusBadge(
    text: String,
    isActive: Boolean,
    modifier: Modifier = Modifier,
    activeColor: Color = Glass.StatusSuccess,
    inactiveColor: Color = Glass.TextMuted
) {
    val dotColor by animateColorAsState(
        targetValue = if (isActive) activeColor else inactiveColor,
        animationSpec = Motion.state(),
        label = "statusDot"
    )

    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Standards.SpacingXs)
    ) {
        // Dot with outer glow ring when active
        Box(contentAlignment = Alignment.Center) {
            if (isActive) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .background(activeColor.copy(alpha = 0.2f), CircleShape)
                )
            }
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .background(dotColor, CircleShape)
            )
        }
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Medium,
            color = if (isActive) activeColor else inactiveColor
        )
    }
}

// ==================== Section Components ====================

/**
 * Section header with title and optional action on the right
 */
@SuppressLint("ModifierParameter")
@Composable
fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    action: @Composable (() -> Unit)? = null
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(Standards.SectionHeaderHeight),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = Glass.AccentPrimary
        )
        if (action != null) {
            action()
        }
    }
}

/**
 * Divider-like section separator — glassmorphic gradient fade-in/fade-out
 */
@SuppressLint("ModifierParameter")
@Composable
fun SectionDivider(
    modifier: Modifier = Modifier,
    label: String? = null
) {
    if (label != null) {
        Row(
            modifier = modifier
                .fillMaxWidth()
                .padding(vertical = Standards.SpacingSm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Standards.SpacingSm)
        ) {
            GlassDivider(modifier = Modifier.weight(1f))
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = Glass.TextMuted
            )
            GlassDivider(modifier = Modifier.weight(1f))
        }
    } else {
        GlassDivider(
            modifier = modifier.padding(vertical = Standards.SpacingSm)
        )
    }
}
