package com.bit.ui.components

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bit.agent.harness.model.ObservationStatus
import com.bit.agent.harness.state.AgentHarnessState
import com.bit.agent.harness.state.TaskPlan
import com.bit.agent.harness.state.TaskStep
import com.bit.global.Standards
import com.bit.ui.icons.TnIcons
import com.bit.ui.theme.LocalBitHaptics
import java.util.Locale

/**
 * Containerless Thinking / Reasoning accordion view for the Agent Harness.
 * Mimics native LLM thought processes and multi-step planning traces.
 */
@Composable
fun AgentHarnessThinkingView(
    state: AgentHarnessState,
    plan: TaskPlan? = null,
    onStepClick: ((TaskStep) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    if (state is AgentHarnessState.Idle) return

    val isLive = state is AgentHarnessState.Decomposing ||
            state is AgentHarnessState.Executing ||
            state is AgentHarnessState.GateChecking ||
            state is AgentHarnessState.SelfCorrecting ||
            state is AgentHarnessState.AwaitingApproval ||
            state is AgentHarnessState.SubagentRunning

    var isExpanded by remember(isLive) { mutableStateOf(isLive) }
    val haptics = LocalBitHaptics.current

    val headerLabel = remember(state, plan) {
        when (state) {
            is AgentHarnessState.Decomposing -> "Planning & Decomposing Task..."
            is AgentHarnessState.Executing -> "Executing Plan · Step ${state.stepIndex} of ${state.totalSteps}"
            is AgentHarnessState.AwaitingApproval -> "Awaiting Approval · Step '${state.activeStep.id}' (${state.toolName})"
            is AgentHarnessState.SubagentRunning -> "Subagent [${state.subagentTask.role}] Executing · Step ${state.parentStepIndex} of ${state.totalParentSteps}"
            is AgentHarnessState.GateChecking -> "Verifying Step Outcome..."
            is AgentHarnessState.SelfCorrecting -> "Self-Correcting · Step '${state.failedStep.id}' (Attempt ${state.retryCount}/${state.maxRetries})"
            is AgentHarnessState.Completed -> {
                val sec = String.format(Locale.US, "%.1f", state.executionTimeMs / 1000f)
                "Thought Process (${plan?.steps?.size ?: state.totalTurns} steps · ${sec}s)"
            }
            is AgentHarnessState.Failed -> "Plan Execution Halted (${state.reason})"
            AgentHarnessState.Idle -> "Agent Idle"
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .animateContentSize(
                animationSpec = tween(
                    durationMillis = com.bit.ui.theme.MotionDuration.stateChange,
                    easing = com.bit.ui.theme.MotionEasing.standard
                )
            )
    ) {
        // Containerless Header Row
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
            // Live Shimmer/Pulse or Static Brain Icon
            if (isLive) {
                val infiniteTransition = rememberInfiniteTransition(label = "harnessPulse")
                val alpha by infiniteTransition.animateFloat(
                    initialValue = 0.35f,
                    targetValue = 1.0f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(800, easing = LinearEasing),
                        repeatMode = RepeatMode.Reverse
                    ),
                    label = "pulseAlpha"
                )
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = alpha))
                )
                Spacer(Modifier.width(8.dp))
            }

            Text(
                text = headerLabel,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Normal,
                color = if (state is AgentHarnessState.Failed) {
                    MaterialTheme.colorScheme.error.copy(alpha = 0.85f)
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.70f)
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false)
            )

            Spacer(Modifier.width(4.dp))

            Icon(
                imageVector = if (isExpanded) TnIcons.ChevronDown else TnIcons.ChevronRight,
                contentDescription = if (isExpanded) "Collapse" else "Expand",
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f),
                modifier = Modifier.size(15.dp)
            )
        }

        // Expandable Thought / DAG Step Timeline
        if (isExpanded) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp, bottom = 4.dp, start = 4.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Render Plan Steps
                val steps = plan?.steps ?: emptyList()
                if (steps.isNotEmpty()) {
                    steps.forEachIndexed { index, step ->
                        HarnessStepRow(
                            step = step,
                            index = index + 1,
                            totalSteps = steps.size,
                            onClick = { onStepClick?.invoke(step) }
                        )
                    }
                }

                // If currently decomposing with no plan yet
                if (state is AgentHarnessState.Decomposing && steps.isEmpty()) {
                    Row(
                        modifier = Modifier.padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(14.dp),
                            strokeWidth = 1.5.dp,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = "Analyzing task and decomposing into executable steps...",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                        )
                    }
                }
            }
        }
    }
}

/**
 * Human-in-the-loop approval card shown when a harness step requires user consent.
 * Renders the pending tool name, description and argument preview with Approve / Deny actions.
 */
@Composable
fun AgentApprovalCard(
    toolName: String,
    description: String,
    toolArguments: String,
    onApprove: () -> Unit,
    onDeny: () -> Unit,
    modifier: Modifier = Modifier
) {
    val haptics = LocalBitHaptics.current

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = Standards.SpacingMd, vertical = Standards.SpacingXs),
        shape = RoundedCornerShape(Standards.RadiusMd),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Standards.SpacingMd),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Filled.CheckCircle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp)
                )
                Text(
                    text = "Approval Required",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }

            Text(
                text = description.ifBlank { "Agent wants to execute a restricted action." },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f)
            )

            Text(
                text = "Tool · $toolName",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary
            )

            if (toolArguments.isNotBlank() && toolArguments != "{}") {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(6.dp))
                        .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.6f))
                        .padding(6.dp)
                ) {
                    Text(
                        text = toolArguments.take(600),
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 10.sp,
                            lineHeight = 13.sp
                        ),
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f),
                        maxLines = 4,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedButton(
                    onClick = {
                        haptics.reject()
                        onDeny()
                    }
                ) {
                    Text("Deny")
                }
                Button(
                    onClick = {
                        haptics.success()
                        onApprove()
                    }
                ) {
                    Text("Approve")
                }
            }
        }
    }
}

/**
 * Human-in-the-loop question card for the ask_user tool.
 * The harness is suspended until the user types an answer (or skips).
 */
@Composable
fun AgentQuestionCard(
    question: String,
    onAnswer: (String) -> Unit,
    onSkip: () -> Unit,
    modifier: Modifier = Modifier
) {
    val haptics = LocalBitHaptics.current
    var answerText by remember(question) { mutableStateOf("") }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = Standards.SpacingMd, vertical = Standards.SpacingXs),
        shape = RoundedCornerShape(Standards.RadiusMd),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.tertiary.copy(alpha = 0.45f)
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Standards.SpacingMd),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Filled.Error,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier.size(18.dp)
                )
                Text(
                    text = "Agent needs your input",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }

            Text(
                text = question,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.9f)
            )

            OutlinedTextField(
                value = answerText,
                onValueChange = { answerText = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("Type your answer...") },
                singleLine = false,
                maxLines = 3,
                textStyle = MaterialTheme.typography.bodySmall
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(
                    onClick = {
                        haptics.cancel()
                        onSkip()
                    }
                ) {
                    Text("Skip")
                }
                Button(
                    enabled = answerText.isNotBlank(),
                    onClick = {
                        haptics.success()
                        onAnswer(answerText.trim())
                    }
                ) {
                    Text("Send")
                }
            }
        }
    }
}

@Composable
private fun HarnessStepRow(
    step: TaskStep,
    index: Int,
    totalSteps: Int,
    onClick: () -> Unit
) {
    var stepExpanded by remember { mutableStateOf(false) }
    val haptics = LocalBitHaptics.current

    val (iconTint, statusBadge) = when {
        step.status == com.bit.agent.harness.state.StepStatus.PASSED ->
            Color(0xFF4CAF50) to "PASSED"
        step.status == com.bit.agent.harness.state.StepStatus.RUNNING ->
            MaterialTheme.colorScheme.primary to "RUNNING"
        step.status == com.bit.agent.harness.state.StepStatus.FAILED ->
            MaterialTheme.colorScheme.error to "FAILED"
        step.retryCount > 0 ->
            Color(0xFFFFA000) to "RETRYING (${step.retryCount})"
        else ->
            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f) to "PENDING"
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f))
            .clickable {
                haptics.selection()
                stepExpanded = !stepExpanded
                onClick()
            }
            .padding(horizontal = 10.dp, vertical = 8.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            // Step Number / Status Indicator
            Box(
                modifier = Modifier
                    .size(18.dp)
                    .clip(CircleShape)
                    .background(iconTint.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                if (step.status == com.bit.agent.harness.state.StepStatus.PASSED) {
                    Icon(
                        imageVector = Icons.Filled.CheckCircle,
                        contentDescription = null,
                        tint = iconTint,
                        modifier = Modifier.size(12.dp)
                    )
                } else if (step.status == com.bit.agent.harness.state.StepStatus.RUNNING) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(10.dp),
                        strokeWidth = 1.5.dp,
                        color = iconTint
                    )
                } else {
                    Text(
                        text = "$index",
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                        fontWeight = FontWeight.Bold,
                        color = iconTint
                    )
                }
            }

            Spacer(Modifier.width(8.dp))

            // Step Description & Tool Name
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = step.description,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "${step.toolName} · ${step.expectedOutcome}",
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            // Status Badge
            Text(
                text = statusBadge,
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                fontWeight = FontWeight.SemiBold,
                color = iconTint,
                modifier = Modifier.padding(horizontal = 4.dp)
            )

            Icon(
                imageVector = if (stepExpanded) TnIcons.ChevronDown else TnIcons.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                modifier = Modifier.size(12.dp)
            )
        }

        // Expandable Step Observation / Recovery Details
        if (stepExpanded) {
            val obs = step.observation
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp, start = 26.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                if (obs != null) {
                    Text(
                        text = "Outcome: ${obs.summary}",
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    if (!obs.payload.isNullOrBlank()) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(6.dp))
                                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.6f))
                                .padding(6.dp)
                        ) {
                            Text(
                                text = obs.payload,
                                style = MaterialTheme.typography.bodySmall.copy(
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 10.sp,
                                    lineHeight = 13.sp
                                ),
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f),
                                maxLines = 8,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    if (!obs.recoveryHint.isNullOrBlank()) {
                        Text(
                            text = "Recovery Guidance: ${obs.recoveryHint}",
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                            color = MaterialTheme.colorScheme.error.copy(alpha = 0.85f)
                        )
                    }
                } else {
                    Text(
                        text = "Tool arguments: ${step.toolArguments}",
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 10.sp
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    )
                }
            }
        }
    }
}
