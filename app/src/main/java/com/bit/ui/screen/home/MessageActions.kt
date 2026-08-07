package com.bit.ui.screen.home

import android.content.ClipData
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import com.bit.ui.theme.Glass
import com.bit.models.messages.ContentType
import com.bit.models.messages.Messages
import com.bit.models.ui.ActionIcon
import com.bit.models.ui.ActionItem
import com.bit.ui.components.ReasoningTraceCard
import com.bit.ui.components.toTraceStep
import com.bit.ui.components.MultiActionButton
import com.bit.ui.icons.TnIcons
import com.bit.viewmodel.AgentPhase
import kotlinx.coroutines.launch
import com.bit.global.Standards

// ── AssistantMessageHeader ──

/** Header part of assistant message: RAG results, tool chain, thinking block, non-text content. */
@Composable
internal fun AssistantMessageHeader(message: Messages, imageBlurEnabled: Boolean = true, onTraceStepClick: ((com.bit.ui.components.TraceStep) -> Unit)? = null) {
    val hasRagResults = remember(message.ragResults) {
        message.ragResults?.isNotEmpty() == true
    }
    val hasToolChainSteps = remember(message.toolChainSteps) {
        message.toolChainSteps?.isNotEmpty() == true
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        if (hasRagResults) {
            message.ragResults?.let { results ->
                SavedRagResultsDisplay(results = results)
            }
        }

        val hasReasoningTrace = message.agentPlan != null || hasToolChainSteps
        if (hasReasoningTrace) {
            val traceSteps = (message.toolChainSteps ?: emptyList()).map { it.toTraceStep() }
            ReasoningTraceCard(
                steps = traceSteps,
                plan = message.agentPlan,
                summary = message.agentSummary,
                isLive = false,
                onStepClick = onTraceStepClick
            )
        }

        // Non-text content types
        when (message.content.contentType) {
            ContentType.Image -> ImageMessageBubble(message, imageBlurEnabled)
            ContentType.PluginResult -> {
                message.toTraceStep()?.let { step ->
                    ReasoningTraceCard(
                        steps = listOf(step),
                        isLive = false,
                        onStepClick = onTraceStepClick
                    )
                }
            }
            else -> {
                // Thinking block (markdown body is handled by lazyMarkdownItems)
                val parsed = remember(message.content.content) {
                    parseThinkingTags(message.content.content)
                }
                parsed.thinkingContent?.let { ThinkingBlock(it) }
            }
        }
    }
}

// ── AssistantMessageFooter ──

/** Footer part of assistant message: metrics + action row. */
@Composable
internal fun AssistantMessageFooter(
    message: Messages,
    ttsPlayingMsgId: String?,
    ttsIsPlaying: Boolean,
    ttsSynthesizing: Boolean,
    ttsModelLoaded: Boolean,
    onSpeak: (Messages) -> Unit,
    onStopTTS: () -> Unit,
    onRegenerate: (() -> Unit)?,
    isRegenerateEnabled: Boolean,
    onEdit: ((Messages) -> Unit)? = null
) {
    val showMetrics = remember(message.decodingMetrics) {
        message.decodingMetrics?.tokensPerSecond?.let { it > 0 } ?: false
    }
    val showImageMetrics = remember(message.imageMetrics) {
        message.imageMetrics != null
    }
    val showMemoryMetrics = remember(message.memoryMetrics) {
        message.memoryMetrics?.let { it.modelSizeMB > 0 || it.peakMemoryMB > 0 } ?: false
    }
    val isTextContent = message.content.contentType == ContentType.Text
    val isThisMessagePlaying = ttsPlayingMsgId == message.msgId && ttsIsPlaying
    val isThisMessageSynthesizing = ttsPlayingMsgId == message.msgId && ttsSynthesizing

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        if (showMetrics) {
            message.decodingMetrics?.let { metrics ->
                MetricsDisplay(metrics, message.memoryMetrics)
            }
        }
        if (showImageMetrics) {
            message.imageMetrics?.let { metrics ->
                ImageMetricsDisplay(metrics)
            }
        }
        if (showMemoryMetrics && !showMetrics) {
            message.memoryMetrics?.let { metrics ->
                MemoryMetricsDisplay(metrics)
            }
        }
        if (isTextContent && message.content.content.isNotEmpty()) {
            // Strip thinking tags for the text content passed to action row
            val textContent = remember(message.content.content) {
                if (THINK_TAG_REGEX.containsMatchIn(message.content.content)) {
                    message.content.content.replace(THINK_TAG_REGEX, "").trim()
                } else message.content.content
            }
            if (textContent.isNotEmpty()) {
                MessageActionRow(
                    message = message,
                    textContent = textContent,
                    isPlaying = isThisMessagePlaying,
                    isSynthesizing = isThisMessageSynthesizing,
                    ttsModelLoaded = ttsModelLoaded,
                    onSpeak = onSpeak,
                    onStopTTS = onStopTTS,
                    onRegenerate = onRegenerate,
                    isRegenerateEnabled = isRegenerateEnabled,
                    onEdit = onEdit
                )
            }
        }
    }
}

// ── SmallActionButton ──

@Composable
internal fun SmallActionButton(
    icon: ImageVector,
    onClick: () -> Unit,
    contentDescription: String = "Action button",
    modifier: Modifier = Modifier,
    tint: Color = Glass.TextMuted,
    enabled: Boolean = true
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val isHovered by interactionSource.collectIsHoveredAsState()
    val isActive = isPressed || isHovered

    val backgroundColor by androidx.compose.animation.animateColorAsState(
        targetValue = if (!enabled) {
            Color.Transparent
        } else if (isActive) {
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
        } else {
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f)
        },
        animationSpec = androidx.compose.animation.core.tween(durationMillis = 150)
    )

    val iconColor by androidx.compose.animation.animateColorAsState(
        targetValue = if (!enabled) {
            Glass.TextMuted.copy(alpha = 0.4f)
        } else if (isActive) {
            MaterialTheme.colorScheme.primary
        } else {
            tint
        },
        animationSpec = androidx.compose.animation.core.tween(durationMillis = 150)
    )

    Box(
        modifier = Modifier
            .size(32.dp)
            .clip(CircleShape)
            .background(backgroundColor)
            .clickable(
                interactionSource = interactionSource,
                indication = androidx.compose.material3.ripple(),
                enabled = enabled,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = iconColor,
            modifier = Modifier.size(16.dp)
        )
    }
}

// ── MessageActionRow ──

@Composable
internal fun MessageActionRow(
    message: Messages,
    textContent: String,
    isPlaying: Boolean,
    isSynthesizing: Boolean,
    ttsModelLoaded: Boolean,
    onSpeak: (Messages) -> Unit,
    onStopTTS: () -> Unit,
    onRegenerate: (() -> Unit)? = null,
    isRegenerateEnabled: Boolean = true,
    onEdit: ((Messages) -> Unit)? = null
) {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    var showCopied by remember { mutableStateOf(false) }

    LaunchedEffect(showCopied) {
        if (showCopied) {
            kotlinx.coroutines.delay(1500)
            showCopied = false
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 12.dp, top = 4.dp, bottom = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 1. Copy Action
        SmallActionButton(
            icon = if (showCopied) TnIcons.CircleCheck else TnIcons.Copy,
            onClick = {
                scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("message", textContent))) }
                showCopied = true
            },
            contentDescription = "Copy",
            tint = if (showCopied) Color.White else Glass.TextMuted
        )

        // 2. Speaker (TTS) Action
        SmallActionButton(
            icon = if (isPlaying) TnIcons.PlayerStop else TnIcons.Volume,
            onClick = {
                if (isPlaying || isSynthesizing) {
                    onStopTTS()
                } else {
                    onSpeak(message)
                }
            },
            contentDescription = "Speak",
            tint = if (isPlaying || isSynthesizing) Color.White else Glass.TextMuted
        )

        // 4. Regenerate Action (Replacing vertical dots)
        if (onRegenerate != null) {
            SmallActionButton(
                icon = TnIcons.Refresh,
                onClick = { if (isRegenerateEnabled) onRegenerate() },
                contentDescription = "Regenerate",
                tint = Glass.TextMuted,
                enabled = isRegenerateEnabled
            )
        }
    }
}
