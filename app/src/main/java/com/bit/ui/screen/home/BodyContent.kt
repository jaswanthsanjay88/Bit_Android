package com.bit.ui.screen.home

import androidx.compose.animation.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.animation.core.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bit.models.ModelType
import com.bit.models.messages.ContentType
import com.bit.models.messages.Role
import com.bit.models.table_schema.Model
import com.bit.ui.components.lazyMarkdownItems
import com.bit.ui.components.ReasoningTraceCard
import com.bit.ui.components.toTraceStep
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
    // Handle orphaned </think> (Qwen3-style: thinking content without opening <think> tag)
    val orphanClose = content.indexOf("</think>", ignoreCase = true)
    if (orphanClose != -1) {
        val hasOpenTag = THINK_OPEN_TAGS.any {
            val idx = content.indexOf(it, ignoreCase = true)
            idx != -1 && idx < orphanClose
        }
        if (!hasOpenTag) {
            // Everything before </think> is thinking content, everything after is actual content
            val thinkingContent = content.substring(0, orphanClose).trim()
            val actualContent = content.substring(orphanClose + 8).trim()
            return ParsedMessage(
                thinkingContent = thinkingContent.ifEmpty { null },
                actualContent = actualContent
            )
        }
    }

    // Fast path: no think tags at all
    val openTag = THINK_OPEN_TAGS.firstOrNull { content.contains(it, ignoreCase = true) }
        ?: return ParsedMessage(null, content.trim())

    // Completed thinking: matched pair present
    val thinkingMatch = THINK_TAG_REGEX.find(content)
    if (thinkingMatch != null) {
        // Group 1 = <think>, Group 2 = [THINK], Group 3 = <reasoning>
        val thinkingContent = thinkingMatch.groupValues.drop(1).firstOrNull { it.isNotEmpty() }?.trim() ?: ""
        val actualContent = content.replace(THINK_TAG_REGEX, "").trim()
        return ParsedMessage(
            thinkingContent = thinkingContent.ifEmpty { null },
            actualContent = actualContent
        )
    }

    // In-progress thinking: open tag without close tag (streaming)
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
    val appState by com.bit.state.AppStateManager.appState.collectAsStateWithLifecycle()
    val ttsPlayingMsgId by chatViewModel.ttsPlayingMsgId.collectAsStateWithLifecycle()
    val ttsIsPlaying by chatViewModel.ttsIsPlaying.collectAsStateWithLifecycle()
    val ttsSynthesizing by chatViewModel.ttsSynthesizing.collectAsStateWithLifecycle()
    val ttsModelLoaded by chatViewModel.ttsModelLoaded.collectAsStateWithLifecycle()

    // Image blur setting — collected once, passed down to avoid per-message DataStore creation
    val context = androidx.compose.ui.platform.LocalContext.current
    val imageBlurEnabled by remember { com.bit.data.AppSettingsDataStore(context).imageBlurEnabled }
        .collectAsStateWithLifecycle(initialValue = true)

    val listState = rememberLazyListState()

    LaunchedEffect(messages.size, chatState.isGenerating, streaming.assistantMessage.length) {
        if (chatState.isGenerating) {
            val itemCount = listState.layoutInfo.totalItemsCount
            if (itemCount > 0) {
                listState.animateScrollToItem(itemCount - 1)
            }
        } else if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }

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
 
            val lastUserMessage = remember(deduped) { deduped.lastOrNull { it.role == Role.User } }
            val lastAssistantMessage = remember(deduped) { deduped.lastOrNull { it.role == Role.Assistant } }
            val hasUserMessageInList = remember(deduped, lastUserMessage, lastAssistantMessage) {
                if (lastUserMessage != null) {
                    if (lastAssistantMessage != null) {
                        deduped.indexOf(lastUserMessage) > deduped.indexOf(lastAssistantMessage)
                    } else {
                        true
                    }
                } else {
                    false
                }
            }

            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    top = 80.dp + with(androidx.compose.ui.platform.LocalDensity.current) {
                        WindowInsets.statusBars.getTop(this).toDp()
                    },
                    bottom = Standards.SpacingSm
                ),
                verticalArrangement = Arrangement.spacedBy(Standards.SpacingXs)
            ) {
                groupedItems.forEach { item ->
                    when (item) {
                        is ChatMessageItem.UserMessage -> {
                            val message = item.message
                            item(key = "${message.msgId}-user") {
                                UserMessageBubble(
                                    message = message,
                                    editable = !chatState.isGenerating,
                                    onEditRequest = { chatViewModel.startEditingPrompt(it) }
                                )
                            }
                        }

                        is ChatMessageItem.AssistantMessage -> {
                            val message = item.message
                            val isLastAssistant = item.isLastAssistant
                            
                            // Header: RAG, tool chain, thinking, image (excluding PluginResult since it's grouped)
                            item(key = "${message.msgId}-header") {
                                AssistantMessageHeader(message, imageBlurEnabled)
                            }
                            
                            // Markdown content — each element is a lazy item
                            if (message.content.contentType == ContentType.Text) {
                                val raw = message.content.content
                                val parsedText = parseThinkingTags(raw).actualContent
                                if (parsedText.isNotEmpty()) {
                                    lazyMarkdownItems(
                                        text = parsedText,
                                        keyPrefix = message.msgId,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = Standards.SpacingMd)
                                    )
                                }
                            }
                            
                            // Footer: metrics + action row
                            item(key = "${message.msgId}-footer") {
                                AssistantMessageFooter(
                                    message = message,
                                    ttsPlayingMsgId = ttsPlayingMsgId,
                                    ttsIsPlaying = ttsIsPlaying,
                                    ttsSynthesizing = ttsSynthesizing,
                                    ttsModelLoaded = ttsModelLoaded,
                                    onSpeak = { chatViewModel.speakMessage(it) },
                                    onStopTTS = { chatViewModel.stopTTS() },
                                    onRegenerate = if (isLastAssistant) {
                                        { chatViewModel.regenerateLastMessage() }
                                    } else null,
                                    isRegenerateEnabled = !chatState.isGenerating,
                                    onEdit = { chatViewModel.startEditingPrompt(it) }
                                )
                            }
                        }
                    }
                }

                // If currently generating, append active streaming items at the end of the same LazyColumn!
                if (chatState.isGenerating && streaming.userMessage != null && !hasUserMessageInList) {
                    item(key = "streaming-user-query") {
                        UserMessageBubble(
                            message = com.bit.models.messages.Messages(
                                role = Role.User,
                                content = com.bit.models.messages.MessageContent(
                                    contentType = ContentType.Text,
                                    content = streaming.userMessage!!
                                )
                            )
                        )
                    }

                    if (rag.results.isNotEmpty()) {
                        item(key = "streaming-rag-results") {
                            RagResultsDisplay(results = rag.results)
                        }
                    }

                    val hasReasoningTrace = agent.toolChainSteps.isNotEmpty() || agent.plan != null || agent.summary != null
                    val streamingPluginMsgs = messages.filter { it.content.contentType == ContentType.PluginResult }

                    if (hasReasoningTrace) {
                        item(key = "streaming-reasoning-trace") {
                            val traceSteps = agent.toolChainSteps.map { it.toTraceStep() }
                            ReasoningTraceCard(
                                steps = traceSteps,
                                plan = agent.plan,
                                summary = agent.summary,
                                isLive = agent.phase != AgentPhase.Complete && agent.phase != AgentPhase.Idle,
                                currentRound = agent.currentRound,
                                maxRounds = 5
                            )
                        }
                    } else if (streamingPluginMsgs.isNotEmpty()) {
                        item(key = "streaming-reasoning-trace") {
                            val traceSteps = streamingPluginMsgs.mapNotNull { it.toTraceStep() }
                            ReasoningTraceCard(
                                steps = traceSteps,
                                isLive = false
                            )
                        }
                    }

                    item(key = "streaming-assistant-response") {
                        val isImageGen = chatState.generationType == ModelType.IMAGE_GENERATION
                        when {
                            isImageGen -> {
                                ImageGenerationStreamingBubble(
                                    streamingImage = streaming.image,
                                    progress = streaming.imageProgress,
                                    step = streaming.imageStep
                                )
                            }
                            else -> {
                                if (streaming.assistantMessage.isNotEmpty()) {
                                    AssistantStreamingBubble(
                                        text = streaming.assistantMessage,
                                        thinkingEnabled = chatState.thinkingEnabled
                                    )
                                } else {
                                    GeneratingIndicator()
                                }
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
    }
}
