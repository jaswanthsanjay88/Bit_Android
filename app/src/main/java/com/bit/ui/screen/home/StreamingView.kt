package com.bit.ui.screen.home

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bit.models.messages.ContentType
import com.bit.models.messages.MessageContent
import com.bit.models.messages.Messages
import com.bit.models.messages.Role
import com.bit.ui.components.ReasoningTraceCard
import com.bit.ui.components.toTraceStep
import com.bit.ui.icons.TnIcons
import com.bit.viewmodel.AgentPhase
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import com.bit.global.Standards
import androidx.compose.ui.text.style.TextAlign

// ── StreamingView ──

@Composable
internal fun StreamingView(
    userMessage: String,
    assistantMessage: String,
    streamingImage: Bitmap?,
    imageProgress: Float,
    imageStep: String,
    isImageGeneration: Boolean,
    ragResults: List<com.bit.viewmodel.RagQueryDisplayResult> = emptyList(),
    appState: com.bit.models.state.AppState,
    messages: List<Messages> = emptyList(),
    toolChainSteps: List<com.bit.models.messages.ToolChainStepData> = emptyList(),
    currentToolChainRound: Int = 0,
    agentPhase: AgentPhase = AgentPhase.Idle,
    agentPlan: String? = null,
    agentSummary: String? = null,
    thinkingEnabled: Boolean = false
) {
    val scrollState = rememberScrollState()

    // Track whether user has manually scrolled up (disables auto-scroll)
    var userScrolledUp by remember { mutableStateOf(false) }
    val isAtBottom = remember {
        derivedStateOf {
            val maxScroll = scrollState.maxValue
            maxScroll == 0 || scrollState.value >= maxScroll - 100
        }
    }

    // Detect user scroll gestures - if user scrolls away from bottom, pause auto-scroll
    LaunchedEffect(scrollState.isScrollInProgress) {
        if (scrollState.isScrollInProgress && !isAtBottom.value) {
            userScrolledUp = true
        }
    }

    // Reset userScrolledUp when user scrolls back to bottom
    LaunchedEffect(isAtBottom.value) {
        if (isAtBottom.value) {
            userScrolledUp = false
        }
    }

    @OptIn(FlowPreview::class)
    LaunchedEffect(Unit) {
        snapshotFlow {
            // Combine all scroll-triggering values
            Triple(assistantMessage.length, messages.size, toolChainSteps.size)
        }
        .debounce(150)
        .collect {
            if (!userScrolledUp) {
                scrollState.animateScrollTo(scrollState.maxValue)
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(Standards.SpacingSm),
        verticalArrangement = Arrangement.spacedBy(Standards.SpacingSm)
    ) {
        UserMessageBubble(
            message = Messages(
                role = Role.User,
                content = MessageContent(
                    contentType = ContentType.Text,
                    content = userMessage
                )
            )
        )

        // Show RAG context if available
        if (ragResults.isNotEmpty()) {
            RagResultsDisplay(results = ragResults)
        }

        // Show unified reasoning trace or plugin results
        val hasReasoningTrace = toolChainSteps.isNotEmpty() || agentPlan != null || agentSummary != null
        val pluginMsgs = remember(messages) {
            messages.filter { it.content.contentType == ContentType.PluginResult }
        }

        if (hasReasoningTrace) {
            val traceSteps = toolChainSteps.map { it.toTraceStep() }
            ReasoningTraceCard(
                steps = traceSteps,
                plan = agentPlan,
                summary = agentSummary,
                isLive = agentPhase != AgentPhase.Complete && agentPhase != AgentPhase.Idle,
                currentRound = currentToolChainRound,
                maxRounds = 5
            )
        } else if (pluginMsgs.isNotEmpty()) {
            val traceSteps = pluginMsgs.mapNotNull { it.toTraceStep() }
            ReasoningTraceCard(
                steps = traceSteps,
                isLive = false
            )
        }

        when {
            isImageGeneration -> {
                ImageGenerationStreamingBubble(
                    streamingImage = streamingImage,
                    progress = imageProgress,
                    step = imageStep
                )
            }
            // Show streaming text when in simple flow or during plan/summary generation
            agentPhase == AgentPhase.Idle || agentPhase == AgentPhase.Complete -> {
                if (assistantMessage.isNotEmpty()) {
                    AssistantStreamingBubble(text = assistantMessage, thinkingEnabled = thinkingEnabled)
                }
            }
        }

        Spacer(modifier = Modifier.height(Standards.SpacingLg))
    }
}

// ── EmptyMessagesState ──

@Composable
fun rememberGreeting(): String {
    return remember {
        val calendar = java.util.Calendar.getInstance()
        val hour = calendar.get(java.util.Calendar.HOUR_OF_DAY)
        val greetings = when (hour) {
            in 5..11 -> listOf(
                "Hey, good morning! What are we working on today?",
                "Good morning! Ready to start something great?",
                "Hey there, good morning. Let's make today productive.",
                "Morning! How can I help you kick off your day?",
                "Good morning! Hope you're having a wonderful start."
            )
            in 12..16 -> listOf(
                "Hey, good afternoon! What's on your mind?",
                "Good afternoon! How can I assist you today?",
                "Hey there, good afternoon. Hope your day is going well.",
                "Good afternoon! Let's get some things done.",
                "Hey! Hope you're having a productive afternoon."
            )
            in 17..20 -> listOf(
                "Hey, good evening! How was your day?",
                "Good evening! What can I help you wrap up today?",
                "Hey there, good evening. Hope you're having a relaxing night.",
                "Good evening! Let's solve some problems together.",
                "Hey! How can I support you this evening?"
            )
            else -> listOf(
                "Hey, burning the midnight oil? How can I help?",
                "Late night coding session? Let me assist you.",
                "Hey there! Still awake? Let's get to work.",
                "Quiet night? Let's build something awesome.",
                "Working late? I'm here to help you out."
            )
        }
        greetings.random()
    }
}

@Composable
internal fun EmptyMessagesState() {
    val colorScheme = MaterialTheme.colorScheme
    val typography = MaterialTheme.typography
    val greeting = rememberGreeting()

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = greeting,
            style = typography.headlineSmall.copy(
                fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                fontSize = 21.sp,
                lineHeight = 30.sp
            ),
            color = colorScheme.onSurface,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 36.dp)
        )
    }
}

