package com.bit.ui.screen.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.bit.global.Standards
import com.bit.models.enums.ProviderType
import com.bit.models.plugins.PluginInfo
import com.bit.ui.components.ActionSwitch
import com.bit.ui.components.ActionTextButton
import com.bit.ui.components.CaptionText
import com.bit.ui.components.GlassDivider
import com.bit.ui.components.PasswordTextField
import com.bit.ui.components.StatusBadge
import com.bit.ui.icons.TnIcons
import com.bit.ui.theme.Glass
import com.bit.ui.theme.Motion
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import com.bit.ui.theme.BitColors



// ── QuickLookChipRow ────────────────────────────────────────────────────────────

@Composable
internal fun QuickLookChipRow(
    loadedRagCount: Int,
    isMemoryEnabled: Boolean,
    isWebSearchEnabled: Boolean = false,
    isRagEnabled: Boolean = true,
    activePluginName: String? = null,
    onRagChipClick: () -> Unit,
    onToolChipClick: () -> Unit,
    onMemoryChipClick: () -> Unit,
    onWebSearchChipClick: () -> Unit = {}
) {
    val hasAnyActive = (loadedRagCount > 0 && isRagEnabled) || isMemoryEnabled || isWebSearchEnabled || activePluginName != null

    AnimatedVisibility(visible = hasAnyActive) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Standards.SpacingXs, vertical = Standards.SpacingXxs)
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(Standards.ChipSpacing)
        ) {
            if (loadedRagCount > 0 && isRagEnabled) {
                StatusChip(
                    label = "$loadedRagCount RAG",
                    color = Glass.AccentTertiary,
                    onClick = onRagChipClick
                )
            }
            if (isWebSearchEnabled) {
                StatusChip(
                    label = "Web Search",
                    color = Glass.AccentSecondary,
                    onClick = onWebSearchChipClick
                )
            }
            if (activePluginName != null) {
                StatusChip(
                    label = activePluginName,
                    color = Glass.AccentPrimary,
                    onClick = onToolChipClick
                )
            }
            if (isMemoryEnabled) {
                StatusChip(
                    label = "Memory",
                    color = Glass.AccentWarm,
                    onClick = onMemoryChipClick
                )
            }
        }
    }
}

// ── StatusChip ──────────────────────────────────────────────────────────────────

@Composable
internal fun StatusChip(
    label: String,
    color: Color,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        color = color.copy(alpha = 0.12f),
        shape = RoundedCornerShape(Standards.ChipCornerRadius),
        modifier = Modifier
            .height(Standards.ChipHeight)
            .border(1.dp, color.copy(alpha = 0.25f), RoundedCornerShape(Standards.ChipCornerRadius))
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = Standards.ChipHorizontalPadding)
        ) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .background(color, CircleShape)
            )
            Spacer(Modifier.width(Standards.SpacingXs))
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = color,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

// ── ReloadModelDialog ───────────────────────────────────────────────────────────

@Composable
internal fun ReloadModelDialog(
    modelName: String,
    modelType: ProviderType,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    val typeLabel = when (modelType) {
        ProviderType.GGUF -> "Text"
        ProviderType.DIFFUSION -> "Image"
        ProviderType.TTS -> "TTS"
        ProviderType.STT -> "STT"
        ProviderType.VLM -> "VLM"
        ProviderType.API -> "API"
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Glass.SurfaceElevated,
        icon = {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .background(Glass.AccentPrimarySurface, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    TnIcons.Cpu, null,
                    tint = Glass.AccentPrimary,
                    modifier = Modifier.size(24.dp)
                )
            }
        },
        title = {
            Text("Load Previous Model?", fontWeight = FontWeight.SemiBold, color = Glass.TextPrimary)
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Standards.SpacingSm)) {
                Text(
                    "You previously had a model loaded. Would you like to load it again?",
                    style = MaterialTheme.typography.bodySmall,
                    color = Glass.TextSecondary
                )
                Text(
                    modelName,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = Glass.TextPrimary
                )
                Text(
                    typeLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = Glass.TextMuted
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("Load", color = Glass.AccentPrimary, fontWeight = FontWeight.SemiBold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Skip", color = Glass.TextSecondary)
            }
        },
        shape = RoundedCornerShape(Standards.RadiusXl)
    )
}

// ── FloatingTtsPlayer ───────────────────────────────────────────────────────────

@Composable
internal fun FloatingTtsPlayer(
    isPlaying: Boolean,
    isSynthesizing: Boolean,
    onPlayPauseToggle: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    // Dynamic timer
    var seconds by remember { mutableIntStateOf(0) }
    LaunchedEffect(isPlaying) {
        if (isPlaying) {
            while (true) {
                delay(1000)
                seconds++
            }
        }
    }

    val timeText = remember(seconds) {
        val m = (seconds / 60).toString().padStart(2, '0')
        val s = (seconds % 60).toString().padStart(2, '0')
        "$m:$s"
    }

    // Playback state colors and icons
    val iconVector = if (isPlaying) TnIcons.PlayerPause else TnIcons.PlayerPlay

    Surface(
        modifier = modifier
            .padding(horizontal = Standards.SpacingLg)
            .fillMaxWidth()
            .height(54.dp)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f), RoundedCornerShape(27.dp)),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = RoundedCornerShape(27.dp),
        tonalElevation = 6.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Left: Play/Pause button
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(CircleShape)
                    .background(Color.White)
                    .clickable { onPlayPauseToggle() },
                contentAlignment = Alignment.Center
            ) {
                if (isSynthesizing) {
                    androidx.compose.material3.CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = Color.Black
                    )
                } else {
                    Icon(
                        imageVector = iconVector,
                        contentDescription = "Play or Pause",
                        tint = Color.Black,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }

            Spacer(Modifier.width(10.dp))

            // Time indicator
            Text(
                text = timeText,
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium
                ),
                color = Color.White
            )

            Spacer(Modifier.width(4.dp))

            // Spacer divider line or dots
            Text(text = " • ", color = Color(0x66FFFFFF), fontSize = 12.sp)

            Spacer(Modifier.width(4.dp))

            // Middle: Pulsing dancing waveform
            Row(
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val durations = remember { listOf(600, 750, 500, 800, 650, 700, 550, 720, 620, 810) }
                repeat(18) { index ->
                    val transition = rememberInfiniteTransition(label = "waveform")
                    val baseHeight = remember { 3.dp + (index % 4 * 3).dp + (index % 3 * 3).dp }
                    val animatedHeight by if (isPlaying) {
                        transition.animateFloat(
                            initialValue = 0.2f,
                            targetValue = 1.3f,
                            animationSpec = infiniteRepeatable(
                                animation = tween(durations[index % durations.size], easing = FastOutSlowInEasing),
                                repeatMode = RepeatMode.Reverse
                            ),
                            label = "bar$index"
                        )
                    } else {
                        remember { androidx.compose.runtime.mutableStateOf(1f) }
                    }

                    Box(
                        modifier = Modifier
                            .width(2.dp)
                            .height(baseHeight * animatedHeight)
                            .background(Color.White, RoundedCornerShape(1.dp))
                    )
                }
            }

            Spacer(Modifier.width(10.dp))

            // Right: Close button
            Icon(
                imageVector = TnIcons.X,
                contentDescription = "Close Player",
                tint = Color(0x8EFFFFFF),
                modifier = Modifier
                    .size(20.dp)
                    .clip(CircleShape)
                    .clickable { onClose() }
                    .padding(2.dp)
            )
        }
    }
}
