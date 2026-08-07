package com.bit.ui.components

import android.view.HapticFeedbackConstants
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.bit.global.Standards
import com.bit.models.messages.ContentType
import com.bit.models.messages.Messages
import com.bit.models.messages.ToolChainStepData
import com.bit.ui.icons.TnIcons
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.border

data class TraceStep(
    val toolName: String,
    val pluginName: String,
    val result: String,
    val success: Boolean,
    val executionTimeMs: Long,
    val inputParams: String = ""
)

fun ToolChainStepData.toTraceStep() = TraceStep(
    toolName = toolName,
    pluginName = pluginName,
    result = result,
    success = success,
    executionTimeMs = executionTimeMs
)

fun Messages.toTraceStep(): TraceStep? {
    val data = content.pluginResultData ?: return null
    return TraceStep(
        toolName = data.toolName,
        pluginName = data.pluginName,
        result = data.resultData,
        success = data.success,
        executionTimeMs = pluginMetrics?.executionTimeMs ?: 0L,
        inputParams = data.inputParams
    )
}

@Composable
fun ReasoningTraceCard(
    steps: List<TraceStep>,
    plan: String? = null,
    summary: String? = null,
    isLive: Boolean = false,
    currentRound: Int = 0,
    maxRounds: Int = 5,
    onStepClick: ((TraceStep) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    if (steps.isEmpty() && plan == null && summary == null && !isLive) return

    var isExpanded by remember { mutableStateOf(false) }
    val haptics = com.bit.ui.theme.LocalBitHaptics.current

    val rotation by animateFloatAsState(
        targetValue = if (isExpanded) 180f else 0f,
        animationSpec = com.bit.ui.theme.Motion.content(),
        label = "chevronRotation"
    )

    val totalTimeMs = steps.sumOf { it.executionTimeMs }
    val timeStr = if (totalTimeMs > 0) " for ${String.format(java.util.Locale.US, "%.1f", totalTimeMs / 1000f)}s" else ""
    
    val totalSteps = steps.size
    val label = if (totalSteps > 0) {
        "Thought$timeStr, called $totalSteps tool${if (totalSteps != 1) "s" else ""}"
    } else {
        "Thought process"
    }

    val icon = TnIcons.BrainCircuit

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = Standards.SpacingMd, vertical = 4.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(Color.White.copy(alpha = 0.05f))
            .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(16.dp))
            .clickable {
                haptics.selection()
                isExpanded = !isExpanded
            }
            .animateContentSize(animationSpec = com.bit.ui.theme.Motion.content())
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp)
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            if (isLive && currentRound > 0) {
                Spacer(Modifier.width(4.dp))
                Text(
                    text = "(Round $currentRound/$maxRounds)",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.width(8.dp))
            Icon(
                imageVector = TnIcons.ChevronDown,
                contentDescription = if (isExpanded) "Collapse" else "Expand",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .size(16.dp)
                    .rotate(rotation)
            )
        }

        if (isExpanded) {
            HorizontalDivider(color = Color.White.copy(alpha = 0.08f))
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(Standards.SpacingXs)
            ) {
                // Plan / Reasoning
                if (plan != null) {
                    Text(
                        text = plan,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 8.dp)
                    )
                }
                
                // Steps
                steps.forEachIndexed { _, step ->
                    ReasoningStepRow(step = step, onClick = { onStepClick?.invoke(step) })
                }

                // Loading next step if live
                if (isLive) {
                    ReasoningLoadingRow()
                }
            }
        }
    }
}


@Composable
private fun ReasoningStepRow(step: TraceStep, onClick: () -> Unit) {
    val queryStr = remember(step.inputParams) {
        try {
            val json = org.json.JSONObject(step.inputParams)
            json.optString("query").takeIf { it.isNotBlank() } ?: json.optString("url").takeIf { it.isNotBlank() } ?: ""
        } catch (e: Exception) { "" }
    }
    val actionDesc = when (step.toolName.lowercase()) {
        "web_search", "search" -> "Searched the web"
        "read_url", "fetch" -> "Read a webpage"
        "calculator", "math" -> "Calculated"
        else -> "Used ${step.toolName.replace('_', ' ')}"
    }
    
    val displayText = buildString {
        append(if (step.success) "✓ " else "✗ ")
        append(actionDesc)
        if (queryStr.isNotBlank()) append(" — \"$queryStr\"")
        if (step.executionTimeMs > 0) append(" (${String.format(java.util.Locale.US, "%.1f", step.executionTimeMs / 1000f)}s)")
    }

    Text(
        text = displayText,
        style = MaterialTheme.typography.bodySmall,
        color = if (step.success) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(horizontal = 8.dp, vertical = 2.dp)
    )
}



@Composable
fun PulsingDotLoader(
    modifier: Modifier = Modifier,
    color: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurfaceVariant,
    dotSize: androidx.compose.ui.unit.Dp = 6.dp,
    spacing: androidx.compose.ui.unit.Dp = 4.dp
) {
    val infiniteTransition = rememberInfiniteTransition(label = "dotsTransition")
    
    val alpha1 by infiniteTransition.animateFloat(
        initialValue = 0.2f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = keyframes {
                durationMillis = 600
                0.2f at 0
                1f at 150
                0.2f at 300
                0.2f at 600
            },
            repeatMode = RepeatMode.Restart,
            initialStartOffset = StartOffset(0)
        ),
        label = "dotAlpha1"
    )
    val alpha2 by infiniteTransition.animateFloat(
        initialValue = 0.2f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = keyframes {
                durationMillis = 600
                0.2f at 0
                1f at 150
                0.2f at 300
                0.2f at 600
            },
            repeatMode = RepeatMode.Restart,
            initialStartOffset = StartOffset(150)
        ),
        label = "dotAlpha2"
    )
    val alpha3 by infiniteTransition.animateFloat(
        initialValue = 0.2f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = keyframes {
                durationMillis = 600
                0.2f at 0
                1f at 150
                0.2f at 300
                0.2f at 600
            },
            repeatMode = RepeatMode.Restart,
            initialStartOffset = StartOffset(300)
        ),
        label = "dotAlpha3"
    )

    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(spacing),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val dotModifier = Modifier
            .size(dotSize)
            .background(color, shape = CircleShape)
        
        Box(dotModifier.then(Modifier.alpha(alpha1)))
        Box(dotModifier.then(Modifier.alpha(alpha2)))
        Box(dotModifier.then(Modifier.alpha(alpha3)))
    }
}

@Composable
private fun ReasoningLoadingRow() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(Standards.SpacingSm),
        verticalAlignment = Alignment.CenterVertically
    ) {
        PulsingDotLoader(
            modifier = Modifier.padding(end = 4.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = "Thinking...",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
