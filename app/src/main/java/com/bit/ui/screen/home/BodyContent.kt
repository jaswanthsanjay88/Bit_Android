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
import com.bit.ui.components.AgentExecutionView
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
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState

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
    data class PluginResultGroup(val results: List<com.bit.models.messages.Messages>, val key: String) : ChatMessageItem()
}

fun groupMessages(messages: List<com.bit.models.messages.Messages>, lastAssistantIndex: Int): List<ChatMessageItem> {
    val result = mutableListOf<ChatMessageItem>()
    var currentGroup = mutableListOf<com.bit.models.messages.Messages>()
    
    messages.forEachIndexed { index, msg ->
        if (msg.content.contentType == ContentType.PluginResult) {
            currentGroup.add(msg)
        } else {
            if (currentGroup.isNotEmpty()) {
                val groupKey = currentGroup.first().msgId + "-group"
                result.add(ChatMessageItem.PluginResultGroup(currentGroup.toList(), groupKey))
                currentGroup = mutableListOf()
            }
            if (msg.role == Role.User) {
                result.add(ChatMessageItem.UserMessage(msg))
            } else {
                val isLastAssistant = index == lastAssistantIndex
                result.add(ChatMessageItem.AssistantMessage(msg, isLastAssistant))
            }
        }
    }
    if (currentGroup.isNotEmpty()) {
        val groupKey = currentGroup.first().msgId + "-group"
        result.add(ChatMessageItem.PluginResultGroup(currentGroup.toList(), groupKey))
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

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun BodyContent(
    paddingValues: PaddingValues,
    chatViewModel: ChatViewModel,
    llmModelViewModel: LLMModelViewModel,
    liquidState: LiquidState? = null,
    hazeState: HazeState = rememberHazeState(),
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
            .hazeSource(state = hazeState)
            .padding(paddingValues)
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
                contentPadding = PaddingValues(vertical = Standards.SpacingSm),
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
                                    onEditRequest = { chatViewModel.startEditingPrompt(it) },
                                    onForkRequest = { chatViewModel.forkConversation(it) }
                                )
                            }
                        }
                        is ChatMessageItem.PluginResultGroup -> {
                            item(key = item.key) {
                                com.bit.ui.components.PluginResultGroupCard(messages = item.results)
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
                                val parsedText = if (THINK_TAG_REGEX.containsMatchIn(raw)) {
                                    raw.replace(THINK_TAG_REGEX, "").trim()
                                } else raw
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
                                    onEdit = { chatViewModel.startEditingPrompt(it) },
                                    onFork = { chatViewModel.forkConversation(it) }
                                )
                            }
                        }
                    }
                }

                // If currently generating, append active streaming items at the end of the same LazyColumn!
                if (chatState.isGenerating && streaming.userMessage != null) {
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

                    if (agent.phase != AgentPhase.Idle) {
                        item(key = "streaming-agent-view") {
                            AgentExecutionView(
                                plan = agent.plan,
                                steps = agent.toolChainSteps,
                                summary = agent.summary,
                                phase = agent.phase,
                                currentStep = agent.currentRound
                            )
                        }
                    }

                    val streamingPluginMsgs = messages.filter { it.content.contentType == ContentType.PluginResult }
                    if (streamingPluginMsgs.isNotEmpty() && agent.phase == AgentPhase.Idle) {
                        item(key = "streaming-plugin-group") {
                            com.bit.ui.components.PluginResultGroupCard(messages = streamingPluginMsgs)
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

        // Scrim + Dynamic Action Window — single AnimatedVisibility to avoid double state reads
        AnimatedVisibility(
            visible = config.showDynamicWindow,
            enter = fadeIn(Motion.entrance()),
            exit = fadeOut(Motion.exit())
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                // Scrim background
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.6f))
                        .clickable(
                            indication = null,
                            interactionSource = remember { MutableInteractionSource() }
                        ) {
                            chatViewModel.hideDynamicWindow()
                        }
                )

                // Window content with spring animation
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Standards.SpacingLg, vertical = Standards.SpacingLg),
                    contentAlignment = Alignment.TopCenter
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
}
