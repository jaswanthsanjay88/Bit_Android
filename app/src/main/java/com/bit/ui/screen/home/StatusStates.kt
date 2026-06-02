package com.bit.ui.screen.home

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bit.models.state.AppState
import com.bit.viewmodel.ChatViewModel
import com.bit.viewmodel.StreamingState
import com.bit.ui.icons.TnIcons
import com.bit.ui.theme.Glass
import com.bit.global.Standards

// ── Status States ──

private val WHITESPACE_REGEX = "\\s+".toRegex()

@Composable
internal fun WelcomeContent() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                Glass.AccentPrimarySurface,
                RoundedCornerShape(Standards.RadiusMd)
            )
            .border(1.dp, Glass.BorderSubtle, RoundedCornerShape(Standards.RadiusMd))
            .padding(Standards.SpacingMd),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = TnIcons.User,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = Glass.AccentPrimary
        )
        Column {
            Text(
                text = "Welcome",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = Glass.TextPrimary
            )
            Text(
                text = "Load a model to begin",
                style = MaterialTheme.typography.labelSmall,
                color = Glass.TextSecondary
            )
        }
    }
}

@Composable
internal fun NoModelLoadedContent() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                Glass.SurfaceSubtle,
                RoundedCornerShape(Standards.RadiusMd)
            )
            .border(1.dp, Glass.BorderSubtle, RoundedCornerShape(Standards.RadiusMd))
            .padding(Standards.SpacingMd),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = TnIcons.Photo,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = Glass.TextMuted
        )
        Column {
            Text(
                text = "No Model Loaded",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = Glass.TextPrimary
            )
            Text(
                text = "Switch to Models tab to load one",
                style = MaterialTheme.typography.labelSmall,
                color = Glass.TextSecondary
            )
        }
    }
}

@Composable
internal fun ModelLoadedContent(state: AppState.ModelLoaded) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                Glass.StatusSuccessSurface,
                RoundedCornerShape(Standards.RadiusMd)
            )
            .border(1.dp, Glass.BorderSubtle, RoundedCornerShape(Standards.RadiusMd))
            .padding(10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(Standards.SpacingSm),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.weight(1f)
        ) {
            Icon(
                imageVector = TnIcons.CircleCheck,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = Glass.StatusSuccess
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Model Ready",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = Glass.StatusSuccess
                )
                Text(
                    text = state.modelName,
                    style = MaterialTheme.typography.bodySmall,
                    color = Glass.TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
internal fun LoadingModelContent(state: AppState.LoadingModel) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                Glass.AccentSecondarySurface,
                RoundedCornerShape(Standards.RadiusMd)
            )
            .border(1.dp, Glass.BorderSubtle, RoundedCornerShape(Standards.RadiusMd))
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(Standards.SpacingSm),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                CircularProgressIndicator(
                    progress = { state.progress },
                    modifier = Modifier.size(18.dp),
                    color = Glass.AccentSecondary,
                    strokeWidth = 2.dp,
                    trackColor = Glass.AccentSecondary.copy(alpha = 0.2f)
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Loading Model",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = Glass.AccentSecondary
                    )
                    Text(
                        text = state.modelName,
                        style = MaterialTheme.typography.bodySmall,
                        color = Glass.TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Text(
                text = "${(state.progress * 100).toInt()}%",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = Glass.AccentSecondary
            )
        }
        LinearProgressIndicator(
            progress = { state.progress },
            modifier = Modifier
                .fillMaxWidth()
                .height(3.dp)
                .clip(RoundedCornerShape(2.dp)),
            color = Glass.AccentSecondary,
            trackColor = Glass.AccentSecondary.copy(alpha = 0.2f)
        )
    }
}

@Composable
internal fun GeneratingTextContent(state: AppState.GeneratingText, chatViewModel: ChatViewModel) {
    val streaming by chatViewModel.streamingState.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(Standards.SpacingSm),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                val infiniteTransition = rememberInfiniteTransition(label = "generating")
                val rotation by infiniteTransition.animateFloat(
                    initialValue = 0f,
                    targetValue = 360f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(1500, easing = LinearEasing),
                        repeatMode = RepeatMode.Restart
                    ),
                    label = "rotation"
                )
                Icon(
                    imageVector = TnIcons.Wrench,
                    contentDescription = null,
                    modifier = Modifier
                        .size(20.dp)
                        .rotate(rotation),
                    tint = Glass.AccentPrimary
                )
                Column {
                    Text(
                        text = "Generating",
                        style = MaterialTheme.typography.labelSmall,
                        color = Glass.TextSecondary
                    )
                    Text(
                        text = state.modelName,
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold,
                        color = Glass.TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            if (streaming.assistantMessage.isNotEmpty()) {
                Text(
                    text = "${streaming.assistantMessage.split(WHITESPACE_REGEX).size}",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = Glass.AccentPrimary
                )
            }
        }
        // ── Context usage bar ──
        val contextUsage by chatViewModel.contextUsagePercent.collectAsStateWithLifecycle()
        if (contextUsage > 0f) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Standards.SpacingSm),
                verticalAlignment = Alignment.CenterVertically
            ) {
                LinearProgressIndicator(
                    progress = { contextUsage },
                    modifier = Modifier
                        .weight(1f)
                        .height(3.dp)
                        .clip(RoundedCornerShape(2.dp)),
                    color = if (contextUsage > 0.85f) Glass.StatusError
                            else Glass.AccentTertiary,
                    trackColor = Glass.SurfaceSubtle
                )
                Text(
                    text = "${(contextUsage * 100).toInt()}%",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (contextUsage > 0.85f) Glass.StatusError
                            else Glass.TextSecondary
                )
            }
        }

        LinearProgressIndicator(
            modifier = Modifier
                .fillMaxWidth()
                .height(2.dp)
                .clip(RoundedCornerShape(1.dp)),
            color = Glass.AccentPrimary
        )
    }
}

@Composable
internal fun GeneratingImageContent(state: AppState.GeneratingImage, chatViewModel: ChatViewModel) {
    val streaming by chatViewModel.streamingState.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(Standards.SpacingSm),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                val infiniteTransition = rememberInfiniteTransition(label = "generating_image")
                val scale by infiniteTransition.animateFloat(
                    initialValue = 1f,
                    targetValue = 1.1f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(800, easing = FastOutSlowInEasing),
                        repeatMode = RepeatMode.Reverse
                    ),
                    label = "scale"
                )
                Icon(
                    imageVector = TnIcons.Wrench,
                    contentDescription = null,
                    modifier = Modifier
                        .size(20.dp)
                        .scale(scale),
                    tint = Glass.AccentTertiary
                )
                Column {
                    Text(
                        text = streaming.imageStep.ifEmpty { "Creating" },
                        style = MaterialTheme.typography.labelSmall,
                        color = Glass.TextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = state.modelName,
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold,
                        color = Glass.TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Text(
                text = "${(streaming.imageProgress * 100).toInt()}%",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = Glass.AccentTertiary
            )
        }
        LinearProgressIndicator(
            progress = { streaming.imageProgress },
            modifier = Modifier
                .fillMaxWidth()
                .height(3.dp)
                .clip(RoundedCornerShape(2.dp)),
            color = Glass.AccentTertiary,
            trackColor = Glass.AccentTertiary.copy(alpha = 0.2f)
        )
        streaming.image?.let { bitmap ->
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = "Preview",
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(Standards.RadiusMd))
            )
        }
    }
}

@Composable
internal fun GeneratingAudioContent() {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        AudioWaveAnimation(Glass.AccentPrimary)
        Text(
            text = "Generating Audio",
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.SemiBold,
            color = Glass.TextPrimary
        )
        LinearProgressIndicator(
            modifier = Modifier
                .fillMaxWidth()
                .height(2.dp)
                .clip(RoundedCornerShape(1.dp)),
            color = Glass.AccentPrimary
        )
    }
}

@Composable
internal fun ExecutingPluginContent(state: AppState.ExecutingPlugin) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                Glass.AccentTertiarySurface,
                RoundedCornerShape(Standards.RadiusMd)
            )
            .border(1.dp, Glass.BorderSubtle, RoundedCornerShape(Standards.RadiusMd))
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(Standards.SpacingSm),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                val infiniteTransition = rememberInfiniteTransition(label = "executing_plugin")
                val rotation by infiniteTransition.animateFloat(
                    initialValue = 0f,
                    targetValue = 360f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(1500, easing = LinearEasing),
                        repeatMode = RepeatMode.Restart
                    ),
                    label = "rotation"
                )
                Icon(
                    imageVector = TnIcons.Wrench,
                    contentDescription = null,
                    modifier = Modifier
                        .size(18.dp)
                        .rotate(rotation),
                    tint = Glass.AccentTertiary
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Executing Tool",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = Glass.AccentTertiary
                    )
                    Text(
                        text = state.toolName,
                        style = MaterialTheme.typography.bodySmall,
                        color = Glass.TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            CompactBadge(state.pluginName, Glass.AccentTertiary)
        }
        LinearProgressIndicator(
            modifier = Modifier
                .fillMaxWidth()
                .height(2.dp)
                .clip(RoundedCornerShape(1.dp)),
            color = Glass.AccentTertiary,
            trackColor = Glass.AccentTertiary.copy(alpha = 0.2f)
        )
    }
}

@Composable
internal fun AudioWaveAnimation(color: androidx.compose.ui.graphics.Color) {
    val infiniteTransition = rememberInfiniteTransition(label = "audio_wave")
    Row(
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.height(20.dp)
    ) {
        repeat(5) { index ->
            val height by infiniteTransition.animateFloat(
                initialValue = 0.3f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(500, delayMillis = index * 80, easing = FastOutSlowInEasing),
                    repeatMode = RepeatMode.Reverse
                ),
                label = "wave_$index"
            )
            Box(
                modifier = Modifier
                    .width(2.dp)
                    .fillMaxHeight(height)
                    .clip(RoundedCornerShape(1.dp))
                    .background(color)
            )
        }
    }
}

@Composable
internal fun PluginExecutionCompleteContent(state: AppState.PluginExecutionComplete) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                if (state.success) Glass.StatusSuccessSurface
                else Glass.StatusErrorSurface,
                RoundedCornerShape(Standards.RadiusMd)
            )
            .border(
                1.dp,
                if (state.success) Glass.BorderSubtle else Glass.StatusError.copy(alpha = 0.3f),
                RoundedCornerShape(Standards.RadiusMd)
            )
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(Standards.SpacingSm),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                Icon(
                    imageVector = if (state.success) TnIcons.CircleCheck else TnIcons.AlertTriangle,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = if (state.success) Glass.StatusSuccess
                           else Glass.StatusError
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (state.success) "Tool Completed" else "Tool Failed",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = if (state.success) Glass.StatusSuccess
                               else Glass.StatusError
                    )
                    Text(
                        text = state.toolName,
                        style = MaterialTheme.typography.bodySmall,
                        color = Glass.TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Text(
                text = "${state.executionTimeMs}ms",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = if (state.success) Glass.StatusSuccess
                       else Glass.StatusError
            )
        }

        if (!state.success && state.errorMessage != null) {
            HorizontalDivider(
                color = Glass.StatusError.copy(alpha = 0.2f),
                thickness = 0.5.dp
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.Top
            ) {
                Icon(
                    imageVector = TnIcons.AlertTriangle,
                    contentDescription = null,
                    modifier = Modifier.size(12.dp),
                    tint = Glass.StatusError.copy(alpha = 0.7f)
                )
                Text(
                    text = state.errorMessage,
                    style = MaterialTheme.typography.labelSmall,
                    color = Glass.StatusError,
                    modifier = Modifier.weight(1f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        if (state.success) {
            CompactBadge(state.pluginName, Glass.StatusSuccess)
        }
    }
}

@Composable
internal fun ErrorContent(state: AppState.Error) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                Glass.StatusErrorSurface,
                RoundedCornerShape(Standards.RadiusMd)
            )
            .border(1.dp, Glass.StatusError.copy(alpha = 0.3f), RoundedCornerShape(Standards.RadiusMd))
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Standards.SpacingSm),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = TnIcons.AlertTriangle,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = Glass.StatusError
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Error",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = Glass.StatusError
                )
                state.modelName?.let { model ->
                    Text(
                        text = model,
                        style = MaterialTheme.typography.bodySmall,
                        color = Glass.TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }

        HorizontalDivider(
            color = Glass.StatusError.copy(alpha = 0.2f),
            thickness = 0.5.dp
        )

        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.Top
        ) {
            Icon(
                imageVector = TnIcons.AlertTriangle,
                contentDescription = null,
                modifier = Modifier.size(12.dp),
                tint = Glass.StatusError.copy(alpha = 0.7f)
            )
            Text(
                text = state.message,
                style = MaterialTheme.typography.labelSmall,
                color = Glass.StatusError,
                modifier = Modifier.weight(1f),
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
