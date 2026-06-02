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
import com.bit.ui.components.AgentExecutionView
import com.bit.ui.components.PluginResultCard
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

        // Agent execution view (Plan → Execute → Summarize)
        if (agentPhase != AgentPhase.Idle) {
            AgentExecutionView(
                plan = agentPlan,
                steps = toolChainSteps,
                summary = agentSummary,
                phase = agentPhase,
                currentStep = currentToolChainRound
            )
        }

        // Show tool results from plugin execution (only when NOT in agent mode,
        // since AgentExecutionView already displays step results)
        if (agentPhase == AgentPhase.Idle) {
            messages.filter { it.content.contentType == ContentType.PluginResult }.forEach { msg ->
                PluginResultCard(message = msg)
            }
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
internal fun EmptyMessagesState() {
    val colorScheme = MaterialTheme.colorScheme
    val typography = MaterialTheme.typography

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(horizontal = 32.dp)
        ) {
            Icon(
                imageVector = TnIcons.Sparkles,
                contentDescription = null,
                modifier = Modifier.size(56.dp),
                tint = colorScheme.primary.copy(0.45f)
            )
            Spacer(Modifier.height(16.dp))
            Text(
                "What can I help with?",
                style = typography.headlineSmall.copy(
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                ),
                color = colorScheme.onSurface,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "Select a local GGUF model and start typing. Everything runs entirely offline on your device.",
                style = typography.bodyMedium,
                color = colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                textAlign = TextAlign.Center
            )
        }
    }
}

