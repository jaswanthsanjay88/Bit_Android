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

// ── Pre-compiled regex (avoid allocation in composition) ──

internal val THINK_TAG_REGEX = Regex(
    "<think>(.*?)</think>|\\[THINK](.*?)\\[/THINK]|<reasoning>(.*?)</reasoning>|<\\|channel>thought(.*?)(?:<channel\\|>|<\\|channel\\|>)",
    RegexOption.DOT_MATCHES_ALL
)
private val THINK_OPEN_TAGS = listOf("<|channel>thought", "<think>", "[THINK]", "<reasoning>")

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
    modifier: Modifier = Modifier
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
            text = "Thinking",
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

fun parseThinkingTags(content: String): ParsedMessage {
    val orphanClose = content.indexOf("</think>", ignoreCase = true)
    if (orphanClose != -1) {
        val hasOpenTag = THINK_OPEN_TAGS.any {
            val idx = content.indexOf(it, ignoreCase = true)
            idx != -1 && idx < orphanClose
        }
        if (!hasOpenTag) {
            val thinkingContent = content.substring(0, orphanClose).trim()
            val actualContent = content.substring(orphanClose + 8).trim()
            return ParsedMessage(
                thinkingContent = thinkingContent.ifEmpty { null },
                actualContent = actualContent
            )
        }
    }

    val openTag = THINK_OPEN_TAGS.firstOrNull { content.contains(it, ignoreCase = true) }
        ?: return ParsedMessage(null, content.trim())

    val thinkingMatch = THINK_TAG_REGEX.find(content)
    if (thinkingMatch != null) {
        val thinkingContent = thinkingMatch.groupValues.drop(1).firstOrNull { it.isNotEmpty() }?.trim() ?: ""
        val actualContent = content.replace(THINK_TAG_REGEX, "").trim()
        return ParsedMessage(
            thinkingContent = thinkingContent.ifEmpty { null },
            actualContent = actualContent
        )
    }

    val openIdx = content.indexOf(openTag, ignoreCase = true)
    val thinkingContent = content.substring(openIdx + openTag.length).trim()
    val beforeThink = content.substring(0, openIdx).trim()
    return ParsedMessage(
        thinkingContent = thinkingContent.ifEmpty { null },
        actualContent = beforeThink,
        isThinkingInProgress = true
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
    
    var textToolbarState by remember { mutableStateOf(TextToolbarState()) }
    val customTextToolbar = remember { CustomTextToolbar { textToolbarState = it } }
    var selectedMessageForActionSheet by remember { mutableStateOf<Messages?>(null) }

    LaunchedEffect(chatState.isGenerating) {
        if (wasGenerating && !chatState.isGenerating) {
            haptics.generationEnd()
        }
        wasGenerating = chatState.isGenerating
    }

    LaunchedEffect(messages.size, chatState.isGenerating, streaming.assistantMessage.length) {
        if (!chatState.isGenerating && messages.isNotEmpty()) {
            kotlinx.coroutines.delay(100)
        }
        val itemCount = listState.layoutInfo.totalItemsCount
        if (itemCount > 0) {
            try {
                listState.animateScrollToItem(itemCount - 1)
            } catch (e: Exception) {
            }
        }
    }

    CompositionLocalProvider(
        LocalTextToolbar provides customTextToolbar
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .then(if (liquidState != null) Modifier.liquefiable(liquidState) else Modifier)
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
                        if (msg.role == Role.User) {
                            UserMessageBubble(
                                message = msg,
                                onLongClick = { selectedMessageForActionSheet = msg }
                            )
                        } else {
                            val isLast = msg == messages.last()
                            Column {
                                com.bit.ui.screen.home.AssistantMessageHeader(message = msg, onTraceStepClick = { selectedTraceStep = it })
                                Box(modifier = Modifier.fillMaxWidth().padding(horizontal = Standards.SpacingMd)) {
                                    androidx.compose.foundation.text.selection.SelectionContainer {
                                        MarkdownText(
                                            text = msg.content.content,
                                            modifier = Modifier.fillMaxWidth()
                                        )
                                    }
                                }
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

                    if (chatState.isGenerating) {
                        val isImageGen = chatState.generationType == ModelType.IMAGE_GENERATION
                        if (isImageGen) {
                            item(key = "streaming-image-response") {
                                // Assume ImageGenerationStreamingBubble is there or skip if not in scope
                            }
                        } else {
                            if (streaming.assistantMessage.isNotEmpty()) {
                                item(key = "streaming-assistant-response") {
                                    AssistantStreamingBubble(
                                        text = streaming.assistantMessage,
                                        thinkingEnabled = chatState.thinkingEnabled
                                    )
                                }
                            } else {
                                item(key = "generating-indicator") {
                                    GeneratingIndicator()
                                }
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
                    
                    val resultText = selectedTraceStep!!.result ?: "No output returned."
                    
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

        CustomTextSelectionPopup(
            state = textToolbarState,
            onDismiss = { textToolbarState = TextToolbarState() }
        )

        selectedMessageForActionSheet?.let { msg ->
            MessageActionBottomSheet(
                message = msg,
                show = true,
                onDismiss = { selectedMessageForActionSheet = null },
                onEditRequest = if (msg.role == Role.User) { { m: com.bit.models.messages.Messages -> chatViewModel.startEditingPrompt(m) } } else null,
                onSaveToMemory = if (msg.role == Role.User) { { c: String -> chatViewModel.saveMessageToMemoryVault(c) } } else null
            )
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
}
