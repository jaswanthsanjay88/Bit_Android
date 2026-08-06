package com.bit.ui.screen.home

import android.graphics.BitmapFactory
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.bit.models.messages.Messages
import com.bit.ui.components.ExpandCollapseIcon
import com.bit.ui.components.GlassCard
import com.bit.ui.components.MarkdownText
import com.bit.ui.icons.TnIcons
import com.bit.ui.theme.Glass
import com.bit.ui.theme.Motion
import kotlinx.coroutines.delay
import java.util.Base64
import com.bit.global.Standards
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.unit.sp
import com.bit.ui.components.InlineColors
import com.bit.ui.components.buildInlineFormatted

// ── UserMessageBubble ──

@Composable
internal fun UserMessageBubble(
    message: Messages,
    editable: Boolean = false,
    onEditRequest: ((Messages) -> Unit)? = null,
    onSaveToMemory: ((String) -> Unit)? = null
) {
    var menuExpanded by remember(message.msgId) { mutableStateOf(false) }
    val imageBitmap = remember(message.content.imageData) {
        message.content.imageData?.let { base64 ->
            try {
                val bytes = android.util.Base64.decode(base64, android.util.Base64.DEFAULT)
                val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                bitmap?.asImageBitmap()
            } catch (e: Exception) {
                null
            }
        }
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End
    ) {
        Box(
            modifier = Modifier
                .padding(horizontal = Standards.SpacingSm, vertical = 5.dp)
        ) {
            val interactionSource = remember { MutableInteractionSource() }
            val bubbleShape = RoundedCornerShape(20.dp, 20.dp, 4.dp, 20.dp)
            val bubbleGradient = androidx.compose.ui.graphics.Brush.verticalGradient(
                colors = listOf(
                    Color(0x3DFFFFFF), // 24% white glass (lighter at top)
                    Color(0x24FFFFFF)  // 14% white glass (darker at bottom)
                )
            )
            
            Box(
                modifier = Modifier
                    .widthIn(max = 280.dp)
                    .clip(bubbleShape)
                    .background(bubbleGradient)
                    .border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.35f), bubbleShape)
                    .combinedClickable(
                        interactionSource = interactionSource,
                        indication = null,
                        onClick = {},
                        onLongClick = {
                            menuExpanded = true
                        }
                    )
            ) {
                Column {
                    imageBitmap?.let { bmp ->
                        Image(
                            bitmap = bmp,
                            contentDescription = "User attached image",
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 200.dp)
                                .clip(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)),
                            contentScale = ContentScale.Crop
                        )
                    }
                    SelectionContainer {
                        androidx.compose.material3.ProvideTextStyle(
                            MaterialTheme.typography.bodyLarge.copy(
                                fontWeight = FontWeight.Medium
                            )
                        ) {
                            MarkdownText(
                                text = message.content.content,
                                modifier = Modifier.padding(
                                    horizontal = 16.dp,
                                    vertical = 10.dp
                                )
                            )
                        }
                    }
                }
            }

            DropdownMenu(
                expanded = menuExpanded,
                onDismissRequest = { menuExpanded = false }
            ) {
                if (editable && onEditRequest != null) {
                    DropdownMenuItem(
                        text = { Text("Edit prompt") },
                        leadingIcon = {
                            Icon(
                                imageVector = TnIcons.Edit,
                                contentDescription = null
                            )
                        },
                        onClick = {
                            menuExpanded = false
                            onEditRequest.invoke(message)
                        }
                    )
                }
                if (onSaveToMemory != null) {
                    DropdownMenuItem(
                        text = { Text("Save to Memory Vault") },
                        leadingIcon = {
                            Icon(
                                imageVector = TnIcons.Brain,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                        },
                        onClick = {
                            menuExpanded = false
                            onSaveToMemory.invoke(message.content.content)
                        }
                    )
                }
            }
        }
    }
}

// ── AssistantStreamingBubble ──

@Composable
internal fun AssistantStreamingBubble(text: String, thinkingEnabled: Boolean = false) {
    var revealedLen by remember { mutableIntStateOf(0) }
    val latestText by rememberUpdatedState(text)
    val haptics = com.bit.ui.theme.LocalBitHaptics.current

    LaunchedEffect(Unit) {
        var isFirstChunk = true
        while (true) {
            val target = latestText.length
            if (revealedLen < target) {
                if (isFirstChunk) {
                    haptics.generationStart()
                    isFirstChunk = false
                }
                
                val behind = target - revealedLen
                val step = when {
                    behind > 20 -> 4   // far behind: catch up faster
                    behind > 8 -> 3
                    else -> 2          // normal: gentle reveal
                }
                revealedLen = minOf(revealedLen + step, target)
                haptics.generationTick()
                delay(33) // ~30 FPS — actively revealing
            } else {
                delay(100) // idle — waiting for tokens, check less often
            }
        }
    }

    val displayed = if (revealedLen < text.length) text.substring(0, revealedLen) else text
    val parsedMessage = remember(displayed) { parseThinkingTags(displayed) }

    val streamingState = remember { com.bit.ui.components.markdown.StreamingMarkdownRenderState() }

    LaunchedEffect(parsedMessage.actualContent, revealedLen < text.length) {
        streamingState.offer(parsedMessage.actualContent, isStreaming = revealedLen < text.length)
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = Standards.SpacingSm),
        verticalArrangement = Arrangement.spacedBy(Standards.SpacingSm)
    ) {
        if (parsedMessage.thinkingContent != null) {
            ThinkingBlock(
                thinkingText = parsedMessage.thinkingContent,
                isStreaming = parsedMessage.isThinkingInProgress
            )
        }

        if (parsedMessage.actualContent.isNotEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Standards.SpacingMd)
            ) {
                com.bit.ui.components.markdown.IncrementalStreamingMarkdownView(
                    state = streamingState,
                    modifier = Modifier.fillMaxWidth()
                ) { blockText ->
                    MarkdownText(text = blockText)
                }
            }
        }
    }
}

// ── ImageMessageBubble ──

@Composable
internal fun ImageMessageBubble(message: Messages, imageBlurEnabled: Boolean = true) {
    var isImageRevealed by remember(imageBlurEnabled) { mutableStateOf(!imageBlurEnabled) }

    Column(
        verticalArrangement = Arrangement.spacedBy(Standards.SpacingSm),
        modifier = Modifier.padding(Standards.SpacingMd)
    ) {
        message.content.imagePrompt?.let { prompt ->
            Text(
                text = "Prompt: $prompt",
                style = MaterialTheme.typography.bodySmall,
                color = Glass.TextSecondary,
                modifier = Modifier.padding(horizontal = Standards.SpacingXs)
            )
        }

        message.content.imageData?.let { base64Image ->
            val bitmap = remember(base64Image) {
                try {
                    val imageBytes = Base64.getDecoder().decode(base64Image)
                    BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
                } catch (e: Exception) {
                    null
                }
            }

            bitmap?.let {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f)
                        .clip(RoundedCornerShape(Standards.RadiusLg))
                        .clickable { isImageRevealed = !isImageRevealed },
                    contentAlignment = Alignment.Center
                ) {
                    Surface(
                        modifier = Modifier
                            .fillMaxSize()
                            .border(1.dp, Glass.BorderSubtle, RoundedCornerShape(Standards.RadiusLg)),
                        shape = RoundedCornerShape(Standards.RadiusLg),
                        color = Glass.Surface
                    ) {
                        Image(
                            bitmap = it.asImageBitmap(),
                            contentDescription = message.content.content,
                            modifier = Modifier
                                .fillMaxSize()
                                .then(
                                    if (!isImageRevealed) Modifier.blur(radius = 70.dp)
                                    else Modifier
                                ),
                            contentScale = ContentScale.Crop
                        )
                    }

                    // Overlay when blurred
                    if (!isImageRevealed) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(Glass.Scrim),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(Standards.SpacingSm)
                            ) {
                                Icon(
                                    imageVector = TnIcons.Sparkles,
                                    contentDescription = "Reveal image",
                                    modifier = Modifier.size(32.dp),
                                    tint = Glass.AccentTertiary
                                )
                                Text(
                                    text = "Tap to reveal",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = Glass.TextPrimary,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }
                    }
                }
            }
        }

        message.content.imageSeed?.let { seed ->
            Text(
                text = "Seed: $seed",
                style = MaterialTheme.typography.labelSmall,
                color = Glass.TextMuted,
                modifier = Modifier.padding(horizontal = Standards.SpacingXs)
            )
        }
    }
}

// ── ThinkingBlock ──

@Composable
internal fun ThinkingBlock(
    thinkingText: String,
    isStreaming: Boolean = false
) {
    // Auto-expand while streaming, auto-collapse when done
    var userToggled by remember { mutableStateOf(false) }
    var userExpandState by remember { mutableStateOf(false) }

    val isExpanded = if (userToggled) userExpandState else isStreaming

    // Pulsing dot animation for streaming state
    val infiniteTransition = rememberInfiniteTransition(label = "thinkPulse")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.4f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "thinkPulseAlpha"
    )

    GlassCard(
        backgroundColor = Glass.SurfaceMedium,
        borderColor = Glass.BorderSubtle,
        cornerRadius = 10.dp,
        borderWidth = 0.8.dp,
        contentPadding = PaddingValues(0.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Standards.SpacingMd)
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        userToggled = true
                        userExpandState = !isExpanded
                    }
                    .padding(vertical = Standards.SpacingSm, horizontal = Standards.SpacingMd),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(Standards.SpacingSm),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = TnIcons.BulbFilled,
                        contentDescription = null,
                        modifier = Modifier
                            .size(16.dp)
                            .graphicsLayer { alpha = if (isStreaming) pulseAlpha else 1f },
                        tint = Glass.AccentWarm
                    )
                    Text(
                        text = if (isStreaming) "Thinking…" else "Thought",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = Glass.AccentWarm
                    )
                }

                ExpandCollapseIcon(isExpanded = isExpanded, size = 20.dp)
            }

            AnimatedVisibility(
                visible = isExpanded,
                enter = Motion.Enter,
                exit = Motion.Exit
            ) {
                Column {
                    HorizontalDivider(
                        color = Glass.Divider
                    )
                    Text(
                        text = thinkingText,
                        style = MaterialTheme.typography.bodySmall,
                        color = Glass.TextSecondary,
                        modifier = Modifier.padding(Standards.SpacingMd)
                    )
                }
            }
        }
    }
}
