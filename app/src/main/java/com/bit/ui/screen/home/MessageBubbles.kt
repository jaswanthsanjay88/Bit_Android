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
    onForkRequest: ((Messages) -> Unit)? = null
) {
    var menuExpanded by remember(message.msgId) { mutableStateOf(false) }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End
    ) {
        Box(
            modifier = Modifier
                .padding(horizontal = Standards.SpacingSm, vertical = 5.dp)
        ) {
            val interactionSource = remember { MutableInteractionSource() }
            val shape = RoundedCornerShape(Standards.RadiusLg)
            
            GlassCard(
                backgroundColor = Color(0x2BFFFFFF), // 17% white glass -> beautiful dark gray carbon glass over black background
                borderColor = Color(0x1AFFFFFF),
                cornerRadius = 20.dp,
                borderWidth = 0.8.dp,
                contentPadding = PaddingValues(0.dp),
                modifier = Modifier
                    .widthIn(max = 280.dp)
                    .combinedClickable(
                        interactionSource = interactionSource,
                        indication = null,
                        onClick = {},
                        onLongClick = {
                            if ((editable && onEditRequest != null) || onForkRequest != null) {
                                menuExpanded = true
                            }
                        }
                    )
            ) {
                SelectionContainer {
                    MarkdownText(
                        text = message.content.content,
                        modifier = Modifier.padding(
                            horizontal = 16.dp,
                            vertical = 10.dp
                        )
                    )
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
                if (onForkRequest != null) {
                    DropdownMenuItem(
                        text = { Text("Fork conversation") },
                        leadingIcon = {
                            Icon(
                                imageVector = TnIcons.Share,
                                contentDescription = null
                            )
                        },
                        onClick = {
                            menuExpanded = false
                            onForkRequest.invoke(message)
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
    // ── Typewriter effect ──
    // Smoothly reveals text 2-4 chars per tick instead of chunky batch updates
    var revealedLen by remember { mutableIntStateOf(0) }
    val latestText by rememberUpdatedState(text)

    LaunchedEffect(Unit) {
        while (true) {
            val target = latestText.length
            if (revealedLen < target) {
                val behind = target - revealedLen
                val step = when {
                    behind > 20 -> 4   // far behind: catch up faster
                    behind > 8 -> 3
                    else -> 2          // normal: gentle reveal
                }
                revealedLen = minOf(revealedLen + step, target)
                delay(33) // ~30 FPS — actively revealing
            } else {
                delay(100) // idle — waiting for tokens, check less often
            }
        }
    }

    val displayed = if (revealedLen < text.length) text.substring(0, revealedLen) else text

    // Only parse thinking tags when thinking mode is enabled — skip regex overhead otherwise
    val parsedMessage = if (thinkingEnabled) {
        remember(displayed) { parseThinkingTags(displayed) }
    } else {
        ParsedMessage(thinkingContent = null, actualContent = displayed)
    }

    // Pulsing cursor animation for typing effect
    val infiniteTransition = rememberInfiniteTransition(label = "cursorPulse")
    val cursorAlpha by infiniteTransition.animateFloat(
        initialValue = 0.2f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(400, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "cursorPulseAlpha"
    )

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
                    .padding(horizontal = Standards.SpacingMd) // Matches completed assistant message padding perfectly!
            ) {
                val scheme = MaterialTheme.colorScheme
                val inlineColors = remember(scheme) {
                    InlineColors(
                        codeBg = scheme.surfaceVariant.copy(alpha = 0.5f),
                        highlightBg = scheme.primary.copy(alpha = 0.3f),
                        mathColor = scheme.primary
                    )
                }

                val formattedText = remember(parsedMessage.actualContent, inlineColors, cursorAlpha) {
                    buildAnnotatedString {
                        append(buildInlineFormatted(parsedMessage.actualContent, inlineColors))
                        withStyle(SpanStyle(color = Glass.AccentPrimary.copy(alpha = cursorAlpha), fontWeight = FontWeight.Bold)) {
                            append(" ▊") // Sleek modern typing terminal vertical cursor block
                        }
                    }
                }

                Text(
                    text = formattedText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Glass.TextPrimary,
                    lineHeight = 20.sp,
                    modifier = Modifier.fillMaxWidth()
                )
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
