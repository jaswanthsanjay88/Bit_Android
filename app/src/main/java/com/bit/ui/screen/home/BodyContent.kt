package com.bit.ui.screen.home

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
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
import com.bit.ui.theme.Motion
import com.bit.viewmodel.ChatViewModel
import com.bit.viewmodel.LLMModelViewModel
import com.bit.global.Standards
import com.bit.viewmodel.StreamingState
import com.bit.viewmodel.ChatUiState
import com.bit.viewmodel.AgentState
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

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty() && !chatState.isGenerating) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .then(if (liquidState != null) Modifier.liquefiable(liquidState) else Modifier)
            .padding(paddingValues)
    ) {
        if (messages.isEmpty() && !chatState.isGenerating) {
            EmptyMessagesState()
        } else {
            if (chatState.isGenerating && streaming.userMessage != null) {
                StreamingView(
                    userMessage = streaming.userMessage!!,
                    assistantMessage = streaming.assistantMessage,
                    streamingImage = streaming.image,
                    imageProgress = streaming.imageProgress,
                    imageStep = streaming.imageStep,
                    isImageGeneration = chatState.generationType == ModelType.IMAGE_GENERATION,
                    ragResults = rag.results,
                    appState = appState,
                    messages = messages,
                    toolChainSteps = agent.toolChainSteps,
                    currentToolChainRound = agent.currentRound,
                    agentPhase = agent.phase,
                    agentPlan = agent.plan,
                    agentSummary = agent.summary,
                    thinkingEnabled = chatState.thinkingEnabled
                )
            } else {
                val deduped = remember(messages.size) { messages.distinctBy { it.msgId } }
                val lastAssistantIndex = remember(deduped.size) { deduped.indexOfLast { it.role == Role.Assistant } }
                val lastUserMessageId = remember(deduped.size) { deduped.lastOrNull { it.role == Role.User }?.msgId }

                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(vertical = Standards.SpacingSm),
                    verticalArrangement = Arrangement.spacedBy(Standards.SpacingXs)
                ) {

                    deduped.forEachIndexed { index, message ->
                        when (message.role) {
                            Role.User -> {
                                item(key = "${message.msgId}-user") {
                                    UserMessageBubble(
                                        message = message,
                                        editable = !chatState.isGenerating,
                                        onEditRequest = { chatViewModel.startEditingPrompt(it) },
                                        onForkRequest = { chatViewModel.forkConversation(it) }
                                    )
                                }
                            }
                            else -> {
                                val isLastAssistant = index == lastAssistantIndex
                                // Header: RAG, tool chain, thinking, image/plugin
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
                    item {
                        Spacer(modifier = Modifier.height(Standards.SpacingLg))
                    }
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
