package com.bit.ui.components

import androidx.compose.animation.AnimatedVisibility
import com.bit.ui.theme.Motion
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.bit.models.messages.Messages
import com.bit.models.plugins.PluginExecutionMetrics
import com.bit.models.plugins.PluginResultData
import com.bit.plugins.PluginManager
import org.json.JSONObject
import com.bit.ui.icons.TnIcons
import com.bit.global.Standards

@Composable
fun PluginResultCard(
    message: Messages,
    modifier: Modifier = Modifier
) {
    val pluginData = message.content.pluginResultData ?: return
    val metrics = message.pluginMetrics

    var isExpanded by remember { mutableStateOf(false) }
    var showDetailDialog by remember { mutableStateOf(false) }

    if (showDetailDialog) {
        PluginResultDetailDialog(
            pluginData = pluginData,
            metrics = metrics,
            onDismiss = { showDetailDialog = false }
        )
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = Standards.SpacingMd)
    ) {
        // Summary Row
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { isExpanded = !isExpanded },
            shape = RoundedCornerShape(Standards.RadiusMd),
            color = if (pluginData.success) {
                MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.3f)
            } else {
                MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f)
            },
            tonalElevation = 1.dp
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = Standards.SpacingSm),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(Standards.SpacingMd),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = TnIcons.Tool,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = if (pluginData.success) {
                            MaterialTheme.colorScheme.tertiary
                        } else {
                            MaterialTheme.colorScheme.error
                        }
                    )

                    Text(
                        text = pluginData.pluginName,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )

                    Text(
                        text = "•",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                    )

                    Text(
                        text = pluginData.toolName,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    // Status Badge
                    Icon(
                        imageVector = if (pluginData.success) {
                            TnIcons.CircleCheck
                        } else {
                            TnIcons.AlertTriangle
                        },
                        contentDescription = if (pluginData.success) "Success" else "Failed",
                        modifier = Modifier.size(14.dp),
                        tint = if (pluginData.success) {
                            MaterialTheme.colorScheme.tertiary
                        } else {
                            MaterialTheme.colorScheme.error
                        }
                    )
                }

                ExpandCollapseIcon(isExpanded = isExpanded, size = 18.dp)
            }
        }

        // Detailed Results
        AnimatedVisibility(
            visible = isExpanded,
            enter = Motion.Enter,
            exit = Motion.Exit
        ) {
            Surface(
                onClick = { showDetailDialog = true },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp),
                shape = RoundedCornerShape(Standards.RadiusMd),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f)
            ) {
                Column(
                    modifier = Modifier.padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    // Metrics
                    metrics?.let { m ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "Execution Time:",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = "${m.executionTimeMs}ms",
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }

                    // Input Parameters (if expanded)
                    if (pluginData.inputParams.isNotEmpty()) {
                        Text(
                            text = "Input:",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Surface(
                            shape = RoundedCornerShape(Standards.RadiusSm),
                            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.5f)
                        ) {
                            Text(
                                text = formatJsonForDisplay(pluginData.inputParams),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(Standards.SpacingSm)
                            )
                        }
                    }

                    // Output using CacheToolUI
                    if (pluginData.success) {
                        Text(
                            text = "Result:",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )

                        val plugin = PluginManager.getPlugin(pluginData.pluginName)
                        // Parse JSON outside composable scope
                        val resultJson = remember(pluginData.resultData) {
                            try {
                                JSONObject(pluginData.resultData)
                            } catch (e: Exception) {
                                null
                            }
                        }

                        if (plugin != null && resultJson != null) {
                            plugin.CacheToolUI(data = resultJson)
                        } else {
                            // Fallback to text display if plugin not available or JSON parsing failed
                            Surface(
                                shape = RoundedCornerShape(Standards.RadiusSm),
                                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.5f)
                            ) {
                                Text(
                                    text = pluginData.resultData,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(Standards.SpacingSm)
                                )
                            }
                        }
                    } else {
                        // Show error
                        Text(
                            text = "Error:",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.error
                        )
                        Surface(
                            shape = RoundedCornerShape(Standards.RadiusSm),
                            color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f)
                        ) {
                            Text(
                                text = pluginData.resultData,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                modifier = Modifier.padding(Standards.SpacingSm)
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun formatJsonForDisplay(json: String): String {
    return try {
        val jsonObject = JSONObject(json)
        jsonObject.keys().asSequence().joinToString("\n") { key ->
            "$key: ${jsonObject.get(key)}"
        }
    } catch (_: Exception) {
        json
    }
}

@Composable
private fun PluginResultDetailDialog(
    pluginData: PluginResultData,
    metrics: PluginExecutionMetrics?,
    onDismiss: () -> Unit
) {
    ToolDetailDialog(
        title = "${pluginData.pluginName} · ${pluginData.toolName}",
        onDismiss = onDismiss
    ) {
        DetailKeyValue("Status", if (pluginData.success) "Success" else "Failed")
        metrics?.let {
            DetailKeyValue("Execution Time", "${it.executionTimeMs}ms")
        }

        if (pluginData.inputParams.isNotBlank()) {
            DetailSection(label = "Input Parameters", content = formatJsonForDisplay(pluginData.inputParams))
        }

        DetailSection(
            label = if (pluginData.success) "Result" else "Error",
            content = pluginData.resultData
        )
    }
}

@Composable
fun PluginResultGroupCard(
    messages: List<Messages>,
    modifier: Modifier = Modifier
) {
    if (messages.isEmpty()) return

    var isExpanded by remember { mutableStateOf(false) }

    val totalSteps = messages.size
    val hasSearch = messages.any { 
        val toolName = it.content.pluginResultData?.toolName ?: ""
        toolName.contains("search") || toolName.contains("fetch") 
    }
    
    val label = if (hasSearch) "Searched the web" else "Used tools"
    val title = "$label · $totalSteps steps"
    val icon = if (hasSearch) TnIcons.World else TnIcons.Tool

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = Standards.SpacingMd, vertical = 4.dp)
    ) {
        // Main Collapsed / Header Card
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { isExpanded = !isExpanded },
            shape = RoundedCornerShape(Standards.RadiusMd),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)),
            tonalElevation = 1.dp
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(Standards.SpacingMd),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )

                    Text(
                        text = title,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }

                ExpandCollapseIcon(isExpanded = isExpanded, size = 18.dp)
            }
        }

        // Expanded Container holding all individual steps
        androidx.compose.animation.AnimatedVisibility(
            visible = isExpanded,
            enter = com.bit.ui.theme.Motion.Enter,
            exit = com.bit.ui.theme.Motion.Exit
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp),
                shape = RoundedCornerShape(Standards.RadiusMd),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.15f),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
            ) {
                Column {
                    messages.forEachIndexed { index, msg ->
                        PluginResultRow(message = msg)
                        if (index < messages.lastIndex) {
                            androidx.compose.material3.HorizontalDivider(
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f),
                                thickness = 0.8.dp
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun PluginResultRow(
    message: Messages,
    modifier: Modifier = Modifier
) {
    val pluginData = message.content.pluginResultData ?: return
    val metrics = message.pluginMetrics
    var isRowExpanded by remember { mutableStateOf(false) }
    var showDetailDialog by remember { mutableStateOf(false) }

    if (showDetailDialog) {
        PluginResultDetailDialog(
            pluginData = pluginData,
            metrics = metrics,
            onDismiss = { showDetailDialog = false }
        )
    }

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { isRowExpanded = !isRowExpanded }
                .padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(Standards.SpacingSm),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = TnIcons.Tool,
                    contentDescription = null,
                    modifier = Modifier.size(13.dp),
                    tint = if (pluginData.success) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.error
                )
                Text(
                    text = "${pluginData.pluginName} · ${pluginData.toolName}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Icon(
                    imageVector = if (pluginData.success) TnIcons.CircleCheck else TnIcons.AlertTriangle,
                    contentDescription = null,
                    modifier = Modifier.size(12.dp),
                    tint = if (pluginData.success) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.error
                )
            }
            ExpandCollapseIcon(isExpanded = isRowExpanded, size = 16.dp)
        }

        androidx.compose.animation.AnimatedVisibility(visible = isRowExpanded) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.3f))
                    .clickable { showDetailDialog = true }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                // Execution Time
                metrics?.let { m ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "Execution Time:",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = "${m.executionTimeMs}ms",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
                // Input Params
                if (pluginData.inputParams.isNotEmpty()) {
                    Text(
                        text = "Input: " + formatJsonForDisplay(pluginData.inputParams),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                // Output snippet
                Text(
                    text = "Result: " + if (pluginData.success) {
                        pluginData.resultData.take(200) + if (pluginData.resultData.length > 200) "..." else ""
                    } else pluginData.resultData,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (pluginData.success) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error
                )
            }
        }
    }
}
