package com.bit.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.MenuBook
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bit.global.Standards
import com.bit.models.messages.Messages
import com.bit.models.messages.RagResultItem
import com.bit.models.messages.ToolChainStepData
import com.bit.ui.icons.TnIcons
import com.bit.ui.theme.Glass
import com.bit.ui.theme.LocalBitHaptics

/**
 * Displays stacked context source badges indicating which RAG documents,
 * memories, and agent tools were injected into or executed for this message.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContextStackIndicator(
    message: Messages,
    modifier: Modifier = Modifier
) {
    val ragCount = message.ragResults?.size ?: 0
    val toolCount = message.toolChainSteps?.size ?: 0
    val hasContext = ragCount > 0 || toolCount > 0

    if (!hasContext) return

    val haptics = LocalBitHaptics.current
    var showSheet by remember { mutableStateOf(false) }

    Row(
        modifier = modifier
            .padding(horizontal = Standards.SpacingMd, vertical = 4.dp)
            .clip(RoundedCornerShape(Standards.RadiusSm))
            .clickable {
                haptics.pop()
                showSheet = true
            },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        if (ragCount > 0) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = Glass.SurfaceMedium,
                border = BorderStroke(0.8.dp, Glass.BorderSubtle)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.MenuBook,
                        contentDescription = null,
                        tint = Glass.AccentTertiary,
                        modifier = Modifier.size(13.dp)
                    )
                    Text(
                        text = "$ragCount RAG ${if (ragCount == 1) "source" else "sources"}",
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                        fontWeight = FontWeight.Medium,
                        color = Glass.TextSecondary
                    )
                }
            }
        }

        if (toolCount > 0) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = Glass.SurfaceMedium,
                border = BorderStroke(0.8.dp, Glass.BorderSubtle)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Build,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(13.dp)
                    )
                    Text(
                        text = "$toolCount ${if (toolCount == 1) "tool" else "tools"} executed",
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                        fontWeight = FontWeight.Medium,
                        color = Glass.TextSecondary
                    )
                }
            }
        }
    }

    if (showSheet) {
        ModalBottomSheet(
            onDismissRequest = { showSheet = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = Glass.SurfaceElevated,
            scrimColor = Glass.Scrim
        ) {
            ContextSourcesSheet(
                message = message,
                onClose = { showSheet = false }
            )
        }
    }
}

@Composable
private fun ContextSourcesSheet(
    message: Messages,
    onClose: () -> Unit
) {
    val ragResults = message.ragResults ?: emptyList()
    val toolSteps = message.toolChainSteps ?: emptyList()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Standards.SpacingLg, vertical = Standards.SpacingSm)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Standards.SpacingSm)
            ) {
                Icon(
                    Icons.Rounded.Layers,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(22.dp)
                )
                Text(
                    text = "Context Sources & Tool Trace",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = Glass.TextPrimary
                )
            }
            IconButton(onClick = onClose) {
                Icon(TnIcons.X, contentDescription = "Close", tint = Glass.TextSecondary)
            }
        }

        Spacer(modifier = Modifier.height(Standards.SpacingMd))

        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 480.dp),
            verticalArrangement = Arrangement.spacedBy(Standards.SpacingSm)
        ) {
            if (ragResults.isNotEmpty()) {
                item {
                    Text(
                        text = "RAG KNOWLEDGE SOURCES (${ragResults.size})",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = Glass.TextMuted,
                        modifier = Modifier.padding(bottom = 4.dp)
                    )
                }
                items(ragResults) { ragItem ->
                    RagSourceCard(ragItem)
                }
            }

            if (toolSteps.isNotEmpty()) {
                item {
                    Text(
                        text = "AGENT TOOL EXECUTION TRACE (${toolSteps.size})",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = Glass.TextMuted,
                        modifier = Modifier.padding(top = Standards.SpacingMd, bottom = 4.dp)
                    )
                }
                items(toolSteps) { step ->
                    ToolStepCard(step)
                }
            }
        }

        Spacer(modifier = Modifier.height(Standards.SpacingLg))
    }
}

@Composable
private fun RagSourceCard(item: RagResultItem) {
    var isExpanded by remember { mutableStateOf(false) }

    GlassCard(
        backgroundColor = Glass.SurfaceMedium,
        borderColor = Glass.BorderSubtle,
        cornerRadius = Standards.RadiusMd,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .clickable { isExpanded = !isExpanded }
                .padding(Standards.SpacingSm)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Standards.SpacingSm),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        Icons.AutoMirrored.Rounded.MenuBook,
                        contentDescription = null,
                        tint = Glass.AccentSecondary,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = item.ragName,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = Glass.TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                ) {
                    Text(
                        text = "${(item.score * 100).toInt()}% match",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }

            AnimatedVisibility(visible = isExpanded) {
                Column(modifier = Modifier.padding(top = Standards.SpacingSm)) {
                    HorizontalDivider(color = Glass.Divider)
                    Spacer(modifier = Modifier.height(Standards.SpacingXs))
                    Text(
                        text = item.content,
                        style = MaterialTheme.typography.bodySmall,
                        color = Glass.TextSecondary
                    )
                }
            }
        }
    }
}

@Composable
private fun ToolStepCard(step: ToolChainStepData) {
    var isExpanded by remember { mutableStateOf(false) }

    GlassCard(
        backgroundColor = Glass.SurfaceMedium,
        borderColor = Glass.BorderSubtle,
        cornerRadius = Standards.RadiusMd,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .clickable { isExpanded = !isExpanded }
                .padding(Standards.SpacingSm)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Standards.SpacingSm)
                ) {
                    Icon(
                        if (step.success) Icons.Rounded.CheckCircle else Icons.Rounded.Error,
                        contentDescription = null,
                        tint = if (step.success) Glass.AccentSecondary else MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = step.toolName,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = Glass.TextPrimary
                    )
                }

                Text(
                    text = "${step.executionTimeMs}ms",
                    style = MaterialTheme.typography.labelSmall,
                    color = Glass.TextMuted
                )
            }

            AnimatedVisibility(visible = isExpanded) {
                Column(modifier = Modifier.padding(top = Standards.SpacingSm), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    HorizontalDivider(color = Glass.Divider)
                    if (step.args.isNotEmpty()) {
                        Text(
                            text = "Args: ${step.args}",
                            style = MaterialTheme.typography.labelSmall,
                            color = Glass.TextSecondary
                        )
                    }
                    if (step.result.isNotEmpty()) {
                        Text(
                            text = "Result: ${step.result}",
                            style = MaterialTheme.typography.labelSmall,
                            color = Glass.TextMuted
                        )
                    }
                }
            }
        }
    }
}
