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
import android.util.Base64
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
    onLongClick: ((Messages) -> Unit)? = null
) {
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
                .padding(horizontal = Standards.SpacingSm, vertical = 2.dp)
        ) {
            val interactionSource = remember { MutableInteractionSource() }
            val bubbleShape = RoundedCornerShape(20.dp)
            val bubbleColor = MaterialTheme.colorScheme.surfaceContainerHigh
            
            Box(
                modifier = Modifier
                    .widthIn(min = 40.dp, max = 340.dp)
                    .clip(bubbleShape)
                    .background(bubbleColor)
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f), bubbleShape)
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

        }
    }
}

// ── AssistantStreamingBubble ──

@Composable
internal fun AssistantStreamingBubble(
    message: Messages? = null,
    text: String,
    thinkingEnabled: Boolean = false,
    onLongClick: ((Messages) -> Unit)? = null
) {
    var revealedLen by remember { mutableIntStateOf(0) }
    val latestText by rememberUpdatedState(text)
    val haptics = com.bit.ui.theme.LocalBitHaptics.current

    LaunchedEffect(Unit) {
        var isFirstChunk = true
        var lastHapticLen = 0
        while (true) {
            val target = latestText.length
            if (revealedLen < target) {
                if (isFirstChunk) {
                    haptics.generationStart()
                    isFirstChunk = false
                }
                
                val behind = target - revealedLen
                val step = when {
                    behind > 100 -> behind / 2
                    behind > 40 -> behind / 3
                    behind > 15 -> 8
                    behind > 5 -> 4
                    else -> 2
                }
                revealedLen = minOf(revealedLen + step, target)
                
                // Pleasant rhythmic haptic feedback every ~20 characters
                if (revealedLen - lastHapticLen >= 20) {
                    haptics.generationTick()
                    lastHapticLen = revealedLen
                }
                delay(16) // ~60 FPS smooth catch-up
            } else {
                delay(100) // idle — waiting for tokens, check less often
            }
        }
    }

    val displayed = if (revealedLen < text.length) text.substring(0, revealedLen) else text

    if (displayed.isEmpty()) {
        GeneratingIndicator(thinkingEnabled = thinkingEnabled)
    } else {
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
            if (parsedMessage.thinkingContent != null || parsedMessage.isThinkingInProgress) {
                ThinkingBlock(
                    thinkingText = parsedMessage.thinkingContent ?: "",
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
}

// ── ImageMessageBubble ──

/**
 * Detect if a decoded bitmap is solid black (or nearly solid black, < 15 brightness per channel).
 * This occurs when remote Stable Diffusion safety checkers zero-out an image on flagged prompts,
 * or when VAE NaN outputs cause blank frames.
 */
private fun isBitmapAllBlack(bitmap: android.graphics.Bitmap): Boolean {
    val width = bitmap.width
    val height = bitmap.height
    if (width <= 0 || height <= 0) return false

    val stepX = (width / 32).coerceAtLeast(1)
    val stepY = (height / 32).coerceAtLeast(1)

    for (x in 0 until width step stepX) {
        for (y in 0 until height step stepY) {
            val pixel = bitmap.getPixel(x, y)
            val r = (pixel shr 16) and 0xFF
            val g = (pixel shr 8) and 0xFF
            val b = pixel and 0xFF
            if (r > 15 || g > 15 || b > 15) {
                return false
            }
        }
    }
    return true
}

@Composable
internal fun ImageMessageBubble(message: Messages, imageBlurEnabled: Boolean = false) {
    var isImageRevealed by remember(imageBlurEnabled) { mutableStateOf(!imageBlurEnabled) }
    var showFullscreenDialog by remember { mutableStateOf(false) }

    Column(
        verticalArrangement = Arrangement.spacedBy(Standards.SpacingSm),
        modifier = Modifier.padding(Standards.SpacingMd)
    ) {
        message.content.imagePrompt?.let { prompt ->
            val cleanDisplayPrompt = prompt.replace(Regex("^(?i)(generate\\s+image|create\\s+image|/image|/draw|/paint)[:\\s]*"), "").trim()
            Text(
                text = "Prompt: $cleanDisplayPrompt",
                style = MaterialTheme.typography.bodySmall,
                color = Glass.TextSecondary,
                modifier = Modifier.padding(horizontal = Standards.SpacingXs)
            )
        }

        message.content.imageData?.let { rawBase64 ->
            val bitmap = remember(rawBase64) {
                try {
                    val cleanB64 = if (rawBase64.contains(",")) {
                        rawBase64.substringAfter(",")
                    } else {
                        rawBase64
                    }.replace("\\s".toRegex(), "")
                    val imageBytes = Base64.decode(cleanB64, Base64.DEFAULT)
                    BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
                } catch (e: Exception) {
                    null
                }
            }

            if (bitmap != null) {
                val isAllBlack = remember(bitmap) { isBitmapAllBlack(bitmap) }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f)
                        .clip(RoundedCornerShape(Standards.RadiusLg))
                        .clickable {
                            if (!isImageRevealed) {
                                isImageRevealed = true
                            } else {
                                showFullscreenDialog = true
                            }
                        },
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
                            bitmap = bitmap.asImageBitmap(),
                            contentDescription = message.content.content,
                            modifier = Modifier
                                .fillMaxSize()
                                .then(
                                    if (!isImageRevealed) Modifier.blur(radius = 60.dp)
                                    else Modifier
                                ),
                            contentScale = ContentScale.Crop
                        )
                    }

                    // Soft frosted glass veil when image is blurred (never pitch black!)
                    if (!isImageRevealed) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(Color.Black.copy(alpha = 0.45f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Surface(
                                shape = CircleShape,
                                color = Glass.SurfaceElevated.copy(alpha = 0.85f),
                                border = androidx.compose.foundation.BorderStroke(1.dp, Glass.BorderActive)
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = TnIcons.Eye,
                                        contentDescription = "Reveal image",
                                        modifier = Modifier.size(18.dp),
                                        tint = Glass.TextPrimary
                                    )
                                    Text(
                                        text = "Tap to reveal",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = Glass.TextPrimary,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }
                            }
                        }
                    }
                }

                // If remote API/diffusers returned an all-black image due to safety filter or VAE NaN
                if (isAllBlack) {
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = Standards.SpacingXs),
                        shape = RoundedCornerShape(Standards.RadiusMd),
                        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.35f),
                        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.5f))
                    ) {
                        Row(
                            modifier = Modifier.padding(Standards.SpacingMd),
                            horizontalArrangement = Arrangement.spacedBy(Standards.SpacingSm),
                            verticalAlignment = Alignment.Top
                        ) {
                            Icon(
                                imageVector = TnIcons.AlertTriangle,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(20.dp).padding(top = 2.dp)
                            )
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(
                                    text = "Blank / Black Image Detected",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.error,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    text = "The image model returned a completely black image. This occurs when the safety filter flags sensitive keywords in the prompt (e.g. minors, beach, swimwear) or due to model precision limits. Try rephrasing your prompt.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Glass.TextSecondary
                                )
                            }
                        }
                    }
                }

                // Fullscreen Lightbox Dialog
                if (showFullscreenDialog) {
                    androidx.compose.ui.window.Dialog(
                        onDismissRequest = { showFullscreenDialog = false },
                        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(Color.Black.copy(alpha = 0.92f))
                                .clickable { showFullscreenDialog = false },
                            contentAlignment = Alignment.Center
                        ) {
                            Image(
                                bitmap = bitmap.asImageBitmap(),
                                contentDescription = message.content.content,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(Standards.SpacingMd)
                                    .clip(RoundedCornerShape(Standards.RadiusLg)),
                                contentScale = ContentScale.Fit
                            )

                            // Close button
                            IconButton(
                                onClick = { showFullscreenDialog = false },
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .padding(24.dp)
                                    .background(Glass.SurfaceElevated.copy(alpha = 0.7f), CircleShape)
                            ) {
                                Icon(
                                    imageVector = TnIcons.X,
                                    contentDescription = "Close",
                                    tint = Glass.TextPrimary
                                )
                            }
                        }
                    }
                }
            } else {
                // Bitmap decode error fallback
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(16f / 9f),
                    shape = RoundedCornerShape(Standards.RadiusLg),
                    color = Glass.Surface,
                    border = androidx.compose.foundation.BorderStroke(1.dp, Glass.BorderSubtle)
                ) {
                    Column(
                        modifier = Modifier.fillMaxSize().padding(Standards.SpacingMd),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = TnIcons.AlertTriangle,
                            contentDescription = "Image decode failed",
                            tint = Glass.TextMuted,
                            modifier = Modifier.size(32.dp)
                        )
                        Spacer(Modifier.height(Standards.SpacingSm))
                        Text(
                            text = "Unable to load image preview",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Glass.TextSecondary
                        )
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
    var isExpanded by remember { mutableStateOf(isStreaming) }
    val haptics = com.bit.ui.theme.LocalBitHaptics.current

    LaunchedEffect(isStreaming) {
        if (isStreaming) {
            isExpanded = true
        }
    }

    // Live elapsed timer tracking
    val startTime = remember { System.currentTimeMillis() }
    var elapsedMs by remember { mutableLongStateOf(0L) }

    LaunchedEffect(isStreaming) {
        if (isStreaming) {
            while (true) {
                elapsedMs = System.currentTimeMillis() - startTime
                kotlinx.coroutines.delay(100)
            }
        }
    }

    val elapsedSeconds = (elapsedMs / 1000f)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 2.dp)
    ) {
        Row(
            modifier = Modifier
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) {
                    haptics.selection()
                    isExpanded = !isExpanded
                }
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = when {
                    isStreaming && elapsedSeconds > 0.5f -> "Thinking (${String.format(java.util.Locale.US, "%.1fs", elapsedSeconds)})…"
                    isStreaming -> "Thinking…"
                    elapsedSeconds > 0.5f -> "Thought for ${String.format(java.util.Locale.US, "%.1fs", elapsedSeconds)}"
                    else -> "Thoughts"
                },
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Normal,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f)
            )
            Spacer(Modifier.width(4.dp))
            Icon(
                imageVector = if (isExpanded) TnIcons.ChevronDown else TnIcons.ChevronRight,
                contentDescription = if (isExpanded) "Collapse" else "Expand",
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f),
                modifier = Modifier.size(15.dp)
            )
        }

        AnimatedVisibility(
            visible = isExpanded,
            enter = Motion.Enter,
            exit = Motion.Exit
        ) {
            Text(
                text = thinkingText,
                style = MaterialTheme.typography.bodySmall.copy(
                    lineHeight = 20.sp
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                modifier = Modifier.padding(start = 2.dp, top = 2.dp, bottom = 6.dp)
            )
        }
    }
}
