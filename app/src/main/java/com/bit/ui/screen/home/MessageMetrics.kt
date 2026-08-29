package com.bit.ui.screen.home

import androidx.compose.animation.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.bit.models.engine_schema.DecodingMetrics
import com.bit.models.messages.ImageGenerationMetrics
import com.bit.models.messages.MemoryMetrics
import com.bit.ui.theme.Motion

// ── MetricsDisplay ──

@Composable
internal fun MetricsDisplay(metrics: DecodingMetrics, memoryMetrics: MemoryMetrics? = null) {
    var isExpanded by remember { mutableStateOf(false) }

    val formattedSpeed = remember(metrics.tokensPerSecond) {
        if (metrics.tokensPerSecond > 0f) "%.1f".format(metrics.tokensPerSecond) else null
    }
    val formattedTime = remember(metrics.totalTimeMs) {
        if (metrics.totalTimeMs > 0f) "%.1f".format(metrics.totalTimeMs / 1000f) else null
    }
    val totalTokens = remember(metrics) {
        if (metrics.totalTokens > 0) metrics.totalTokens else (metrics.tokensEvaluated + metrics.tokensPredicted + metrics.reasoningTokens)
    }

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
                ) { isExpanded = !isExpanded }
                .padding(vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            val mainTokenText = if (metrics.tokensPredicted > 0) {
                "${metrics.tokensPredicted} tokens"
            } else if (totalTokens > 0) {
                "$totalTokens tokens"
            } else null

            if (formattedSpeed != null) {
                Text(
                    text = "$formattedSpeed t/s",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f)
                )
            }
            if (mainTokenText != null) {
                Text(
                    text = if (formattedSpeed != null) "•  $mainTokenText" else mainTokenText,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f)
                )
            }
            if (formattedTime != null) {
                Text(
                    text = "•  ${formattedTime}s",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f)
                )
            }
        }

        AnimatedVisibility(
            visible = isExpanded,
            enter = Motion.Enter,
            exit = Motion.Exit
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 2.dp, top = 2.dp, bottom = 4.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                if (metrics.tokensEvaluated > 0) {
                    val cachedSuffix = if (metrics.cachedTokens > 0) " (${metrics.cachedTokens} cached)" else ""
                    Text(
                        text = "Prompt: ${metrics.tokensEvaluated} tokens$cachedSuffix",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                    )
                }
                if (metrics.reasoningTokens > 0) {
                    Text(
                        text = "Reasoning: ${metrics.reasoningTokens} tokens",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                    )
                }
                if (metrics.tokensPredicted > 0) {
                    Text(
                        text = "Response: ${metrics.tokensPredicted} tokens",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                    )
                }
                if (totalTokens > 0 && (metrics.tokensEvaluated > 0 || metrics.reasoningTokens > 0)) {
                    Text(
                        text = "Total Processed: $totalTokens tokens",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                    )
                }
                if (metrics.timeToFirstTokenMs > 0f) {
                    Text(
                        text = "TTFT: ${"%.0f".format(metrics.timeToFirstTokenMs)} ms",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                    )
                }
                memoryMetrics?.let { mem ->
                    if (mem.peakMemoryMB > 0) {
                        Text(
                            text = "Peak Memory: ${mem.peakMemoryMB} MB",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                        )
                    }
                }
            }
        }
    }
}

// ── MemoryMetricsDisplay ──

@Composable
internal fun MemoryMetricsDisplay(metrics: MemoryMetrics) {
    var isExpanded by remember { mutableStateOf(false) }

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
                ) { isExpanded = !isExpanded }
                .padding(vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = "Memory: ${metrics.peakMemoryMB} MB peak",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
            )
            if (metrics.memoryUsagePercent > 0) {
                Text(
                    text = "•  ${"%.1f".format(metrics.memoryUsagePercent)}%",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                )
            }
        }

        AnimatedVisibility(
            visible = isExpanded,
            enter = Motion.Enter,
            exit = Motion.Exit
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 2.dp, top = 2.dp, bottom = 4.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                if (metrics.modelSizeMB > 0) {
                    Text(
                        text = "Model Size: ${metrics.modelSizeMB} MB",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)
                    )
                }
                if (metrics.contextSizeMB > 0) {
                    Text(
                        text = "Context Size: ${metrics.contextSizeMB} MB",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)
                    )
                }
            }
        }
    }
}

// ── ImageMetricsDisplay ──

@Composable
internal fun ImageMetricsDisplay(metrics: ImageGenerationMetrics) {
    val formattedTime = remember(metrics.generationTimeMs) {
        "%.1f".format(metrics.generationTimeMs / 1000f)
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 2.dp)
    ) {
        Text(
            text = "Generated in ${formattedTime}s",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
        )
    }
}
