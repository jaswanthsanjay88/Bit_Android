package com.bit.ui.screen.home

import androidx.compose.animation.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.animation.core.*
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.text.font.FontWeight
import com.bit.ui.components.MarkdownText
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalTextToolbar
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bit.models.ModelType
import com.bit.models.messages.ContentType
import com.bit.models.messages.MessageContent
import com.bit.models.messages.Messages
import com.bit.models.messages.Role
import com.bit.models.table_schema.Model
import com.bit.ui.components.lazyMarkdownItems
import com.bit.ui.components.ReasoningTraceCard
import com.bit.ui.components.toTraceStep
import com.bit.ui.components.CustomTextToolbar
import com.bit.ui.components.TextToolbarState
import com.bit.ui.components.CustomTextSelectionPopup
import com.bit.ui.components.EditMessageDialog
import com.bit.ui.components.MessageActionBottomSheet
import com.bit.ui.theme.Motion
import com.bit.viewmodel.ChatViewModel
import com.bit.viewmodel.LLMModelViewModel
import com.bit.global.Standards
import com.bit.viewmodel.StreamingState
import com.bit.viewmodel.ChatUiState
import com.bit.viewmodel.AgentState
import com.bit.viewmodel.AgentPhase
import com.bit.viewmodel.RagState
import com.bit.viewmodel.ChatConfigState
import io.github.fletchmckee.liquid.LiquidState
import io.github.fletchmckee.liquid.liquefiable

// ── Pre-compiled tags ──
private val THINK_TAGS = listOf(
    "<think>" to "</think>",
    "[THINK]" to "[/THINK]",
    "<reasoning>" to "</reasoning>",
    "<|channel>thought" to "<|channel>"
)

data class ParsedMessage(
    val thinkingContent: String?,
    val actualContent: String,
    val isThinkingInProgress: Boolean = false
)

sealed class ChatMessageItem {
    data class UserMessage(val message: com.bit.models.messages.Messages) : ChatMessageItem()
    data class AssistantMessage(val message: com.bit.models.messages.Messages, val isLastAssistant: Boolean) : ChatMessageItem()
}

fun groupMessages(messages: List<com.bit.models.messages.Messages>, lastAssistantIndex: Int): List<ChatMessageItem> {
    val result = mutableListOf<ChatMessageItem>()
    
    messages.forEachIndexed { index, msg ->
        if (msg.content.contentType == ContentType.PluginResult) {
            // Skip PluginResult, tool steps are handled inside AssistantMessage
        } else {
            if (msg.role == Role.User) {
                result.add(ChatMessageItem.UserMessage(msg))
            } else {
                val isLastAssistant = index == lastAssistantIndex
                result.add(ChatMessageItem.AssistantMessage(msg, isLastAssistant))
            }
        }
    }
    return result
}

@Composable
fun GeneratingIndicator(
    modifier: Modifier = Modifier,
    thinkingEnabled: Boolean = false
) {
    val infiniteTransition = rememberInfiniteTransition(label = "generatingIndicator")
    val alpha1 by infiniteTransition.animateFloat(
        initialValue = 0.2f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(600, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "dot1"
    )
    val alpha2 by infiniteTransition.animateFloat(
        initialValue = 0.2f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(600, delayMillis = 200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "dot2"
    )
    val alpha3 by infiniteTransition.animateFloat(
        initialValue = 0.2f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(600, delayMillis = 400, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "dot3"
    )

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = Standards.SpacingMd, vertical = Standards.SpacingSm),
        horizontalArrangement = Arrangement.Start,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = com.bit.ui.icons.TnIcons.Sparkles,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f)
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = if (thinkingEnabled) "Thinking" else "Generating",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            fontWeight = FontWeight.Medium
        )
        Spacer(Modifier.width(4.dp))
        Row(
            horizontalArrangement = Arrangement.spacedBy(3.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(Modifier.size(4.dp).background(MaterialTheme.colorScheme.primary.copy(alpha = alpha1), CircleShape))
            Box(Modifier.size(4.dp).background(MaterialTheme.colorScheme.primary.copy(alpha = alpha2), CircleShape))
            Box(Modifier.size(4.dp).background(MaterialTheme.colorScheme.primary.copy(alpha = alpha3), CircleShape))
        }
    }
}

fun parseThinkingTags(raw: String): ParsedMessage {
    if (raw.isEmpty()) return ParsedMessage(null, "", false)
    if (!raw.contains('<') && !raw.contains('[') && !raw.contains("channel")) {
        return ParsedMessage(null, raw, false)
    }

    val content = java.lang.StringBuilder()
    val thinking = java.lang.StringBuilder()
    var isThinkingInProgress = false
    var i = 0

    while (i < raw.length) {
        var minIdx = -1
        var selectedOpenTag = ""
        for ((open, _) in THINK_TAGS) {
            val idx = raw.indexOf(open, i, ignoreCase = true)
            if (idx >= 0 && (minIdx < 0 || idx < minIdx)) {
                minIdx = idx
                selectedOpenTag = open
            }
        }

        if (minIdx < 0) {
            content.append(raw, i, raw.length)
            break
        }

        content.append(raw, i, minIdx)
        val closeTag = THINK_TAGS.first { it.first == selectedOpenTag }.second
        val bodyStart = minIdx + selectedOpenTag.length
        val end = raw.indexOf(closeTag, bodyStart, ignoreCase = true)

        if (end < 0) {
            val chunk = raw.substring(bodyStart).trim()
            if (chunk.isNotEmpty() && !thinking.toString().contains(chunk)) {
                if (thinking.isNotEmpty()) thinking.append("\n\n")
                thinking.append(chunk)
            }
            isThinkingInProgress = true
            i = raw.length
        } else {
            val chunk = raw.substring(bodyStart, end).trim()
            if (chunk.isNotEmpty() && !thinking.toString().contains(chunk)) {
                if (thinking.isNotEmpty()) thinking.append("\n\n")
                thinking.append(chunk)
            }
            i = end + closeTag.length
        }
    }

    val thinkingStr = thinking.toString().trim()
    val contentStr = content.toString().trim()

    // Handle orphaned closing tag at the very beginning (e.g., if the text starts with </think>)
    val orphanClose = contentStr.indexOf("</think>", ignoreCase = true)
    if (orphanClose != -1 && orphanClose < 10 && thinkingStr.isEmpty()) {
        val actualContent = contentStr.substring(orphanClose + 8).trim()
        val orphanThink = contentStr.substring(0, orphanClose).trim()
        return ParsedMessage(
            thinkingContent = orphanThink.ifEmpty { null },
            actualContent = actualContent,
            isThinkingInProgress = false
        )
    }

    return ParsedMessage(
        thinkingContent = thinkingStr.ifEmpty { null },
        actualContent = contentStr,
        isThinkingInProgress = isThinkingInProgress
    )
}

@OptIn(ExperimentalSharedTransitionApi::class, ExperimentalMaterial3Api::class)
@Composable
fun BodyContent(
    paddingValues: PaddingValues,
    chatViewModel: ChatViewModel,
    llmModelViewModel: LLMModelViewModel,
    liquidState: LiquidState? = null,
    onModelSelectedNavigate: (Model) -> Unit = {}
) {
    val messages = chatViewModel.messages
    val streaming by chatViewModel.streamingState.collectAsStateWithLifecycle()
    val chatState by chatViewModel.chatUiState.collectAsStateWithLifecycle()
    val agent by chatViewModel.agentState.collectAsStateWithLifecycle()
    val rag by chatViewModel.ragState.collectAsStateWithLifecycle()
    val config by chatViewModel.chatConfigState.collectAsStateWithLifecycle()
    val promptEditState by chatViewModel.promptEditState.collectAsStateWithLifecycle()
    val ttsPlayingMsgId by chatViewModel.ttsPlayingMsgId.collectAsStateWithLifecycle()
    val ttsIsPlaying by chatViewModel.ttsIsPlaying.collectAsStateWithLifecycle()
    val ttsSynthesizing by chatViewModel.ttsSynthesizing.collectAsStateWithLifecycle()
    val ttsModelLoaded by chatViewModel.ttsModelLoaded.collectAsStateWithLifecycle()

    val context = androidx.compose.ui.platform.LocalContext.current
    val imageBlurEnabled by remember { com.bit.data.AppSettingsDataStore(context).imageBlurEnabled }
        .collectAsStateWithLifecycle(initialValue = true)

    val listState = rememberLazyListState()
    val haptics = com.bit.ui.theme.LocalBitHaptics.current
    var wasGenerating by remember { mutableStateOf(chatState.isGenerating) }
    var selectedTraceStep by remember { mutableStateOf<com.bit.ui.components.TraceStep?>(null) }

    LaunchedEffect(chatState.isGenerating) {
        if (wasGenerating && !chatState.isGenerating) {
            haptics.generationEnd()
            val itemCount = listState.layoutInfo.totalItemsCount
            if (itemCount > 0) {
                try {
                    listState.animateScrollToItem(itemCount - 1)
                } catch (_: Exception) {}
            }
        }
        wasGenerating = chatState.isGenerating
    }

    LaunchedEffect(messages.size) {
        val itemCount = listState.layoutInfo.totalItemsCount
        if (itemCount > 0) {
            try {
                listState.animateScrollToItem(itemCount - 1)
            } catch (_: Exception) {}
        }
    }

    // Stable, non-shaking scroll tracking during active text generation
    LaunchedEffect(streaming.assistantMessage.length) {
        if (chatState.isGenerating && streaming.assistantMessage.isNotEmpty()) {
            val layoutInfo = listState.layoutInfo
            val totalItems = layoutInfo.totalItemsCount
            if (totalItems > 0) {
                val lastVisibleIndex = layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
                if (lastVisibleIndex >= totalItems - 3) {
                    try {
                        listState.scrollToItem(totalItems - 1)
                    } catch (_: Exception) {}
                }
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(
                bottom = paddingValues.calculateBottomPadding()
            )
    ) {
        if (messages.isEmpty() && !chatState.isGenerating) {
            EmptyMessagesState()
        } else {
            val deduped = remember(messages.size) { messages.distinctBy { it.msgId } }
            val lastAssistantIndex = remember(deduped.size) { deduped.indexOfLast { it.role == Role.Assistant } }
            val groupedItems = remember(deduped) { groupMessages(deduped, lastAssistantIndex) }

            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    top = paddingValues.calculateTopPadding() + Standards.SpacingXl, 
                    bottom = 120.dp
                ),
                verticalArrangement = Arrangement.spacedBy(Standards.SpacingLg)
            ) {
                items(
                    items = messages,
                    key = { it.msgId }
                ) { msg ->
                    Box(modifier = Modifier.animateItem()) {
                        if (msg.role == Role.User) {
                            UserMessageBubble(
                                message = msg
                            )
                        } else {
                                val isLast = msg == messages.last()
                                val parsedMessage = remember(msg.content.content) { parseThinkingTags(msg.content.content) }
                                Column(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    com.bit.ui.screen.home.AssistantMessageHeader(
                                        message = msg,
                                        imageBlurEnabled = imageBlurEnabled,
                                        onTraceStepClick = { selectedTraceStep = it }
                                    )
                                    
                                    if (parsedMessage.thinkingContent != null) {
                                        ThinkingBlock(
                                            thinkingText = parsedMessage.thinkingContent,
                                            isStreaming = false
                                        )
                                    }
                                    
                                    if (parsedMessage.actualContent.isNotEmpty()) {
                                        Box(modifier = Modifier.fillMaxWidth().padding(horizontal = Standards.SpacingMd)) {
                                            androidx.compose.foundation.text.selection.SelectionContainer {
                                                MarkdownText(
                                                    text = parsedMessage.actualContent,
                                                    modifier = Modifier.fillMaxWidth()
                                                )
                                            }
                                        }
                                    }
                                    com.bit.ui.components.ContextStackIndicator(message = msg)
                                    com.bit.ui.screen.home.AssistantMessageFooter(
                                        message = msg,
                                        ttsPlayingMsgId = ttsPlayingMsgId,
                                        ttsIsPlaying = ttsIsPlaying,
                                        ttsSynthesizing = ttsSynthesizing,
                                        ttsModelLoaded = ttsModelLoaded,
                                        onSpeak = { chatViewModel.speakMessage(it) },
                                        onStopTTS = { chatViewModel.stopTTS() }, // toggle same message to stop
                                        onRegenerate = if (isLast) { { chatViewModel.regenerateLastMessage() } } else null,
                                        isRegenerateEnabled = isLast
                                    )
                                }
                            }
                        }
                    }

                    if (chatState.isGenerating) {
                        val isImageGen = chatState.generationType == ModelType.IMAGE_GENERATION
                        if (isImageGen) {
                            item(key = "streaming-image-response") {
                                ImageGenerationStreamingBubble(
                                    streamingImage = streaming.image,
                                    progress = streaming.imageProgress,
                                    step = streaming.imageStep
                                )
                            }
                        } else {
                            item(key = "streaming-assistant-response") {
                                AssistantStreamingBubble(
                                    text = streaming.assistantMessage,
                                    thinkingEnabled = chatState.thinkingEnabled
                                )
                            }
                        }
                    }

                    if (chatState.error != null) {
                    item(key = "generation-error") {
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = Standards.SpacingMd, vertical = Standards.SpacingSm),
                            shape = androidx.compose.foundation.shape.RoundedCornerShape(Standards.RadiusMd),
                            color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.2f),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.3f))
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(Standards.SpacingMd),
                                horizontalArrangement = Arrangement.spacedBy(Standards.SpacingSm),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = com.bit.ui.icons.TnIcons.AlertCircle,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(20.dp)
                                )
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "Inference Error",
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.error
                                    )
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        text = chatState.error!!,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onErrorContainer
                                    )
                                }
                            }
                        }
                    }
                }

                item {
                    Spacer(modifier = Modifier.height(Standards.SpacingLg))
                }
            }
        }

        // Modal Bottom Sheet for model selection details
        if (config.showDynamicWindow) {
            ModalBottomSheet(
                onDismissRequest = { chatViewModel.hideDynamicWindow() },
                sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
                containerColor = MaterialTheme.colorScheme.surfaceContainer,
                dragHandle = { BottomSheetDefaults.DragHandle() }
            ) {
                val ragCount by com.bit.plugins.PluginManager.enabledPluginNames.collectAsStateWithLifecycle()
                val ttsLoaded by com.bit.tts.TTSManager.isModelLoaded.collectAsStateWithLifecycle()

                DynamicActionWindow(
                    chatViewModel = chatViewModel,
                    modelViewModel = llmModelViewModel,
                    enabledToolCount = ragCount.size,
                    ttsModelLoaded = ttsLoaded,
                    onModelSelectedNavigate = onModelSelectedNavigate
                )
            }
        }

        // Trace Step Details Bottom Sheet
        if (selectedTraceStep != null) {
            ModalBottomSheet(
                onDismissRequest = { selectedTraceStep = null },
                sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false),
                containerColor = MaterialTheme.colorScheme.surfaceContainer,
                dragHandle = { BottomSheetDefaults.DragHandle() }
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Standards.SpacingMd)
                        .padding(bottom = Standards.SpacingLg),
                    verticalArrangement = Arrangement.spacedBy(Standards.SpacingMd)
                ) {
                    Text(
                        text = selectedTraceStep!!.toolName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    
                    val resultText = selectedTraceStep!!.result.ifEmpty { "No output returned." }
                    
                    Surface(
                        modifier = Modifier.fillMaxWidth().heightIn(max = 400.dp),
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(Standards.RadiusMd),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                    ) {
                        LazyColumn(
                            modifier = Modifier.fillMaxWidth().padding(Standards.SpacingSm)
                        ) {
                            item {
                                Text(
                                    text = resultText,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        }

        promptEditState?.let { state ->
            EditMessageDialog(
                initialText = state.initialText,
                onConfirm = { newText ->
                    chatViewModel.applyPromptEdit(newText)
                },
                onDismiss = {
                    chatViewModel.cancelPromptEdit()
                }
            )
        }
    }
}
