package com.bit.ui.screen.workspace

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.animation.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bit.models.table_schema.WorkspaceEntity
import com.bit.ui.components.ItemPosition
import com.bit.ui.components.PhysicsSwipeToDelete
import com.bit.ui.icons.TnIcons
import com.bit.ui.theme.LocalBitHaptics
import com.bit.workspace.WorkspaceProcess
import com.bit.workspace.WorkspaceProcessManager
import com.bit.workspace.WorkspaceProcessStatus
import com.bit.workspace.WorkspaceProcessType

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkspaceProcessesSheet(
    workspace: WorkspaceEntity,
    onDismiss: () -> Unit,
    onOpenTerminal: (String?) -> Unit
) {
    val context = LocalContext.current
    val bitHaptics = LocalBitHaptics.current
    val allProcesses by WorkspaceProcessManager.processes.collectAsStateWithLifecycle()

    val workspaceProcesses = remember(allProcesses, workspace.id) {
        allProcesses.filter { it.workspaceId == workspace.id }
    }

    val activeCount = remember(workspaceProcesses) {
        workspaceProcesses.count { it.status == WorkspaceProcessStatus.RUNNING }
    }
    val aiTasksCount = remember(workspaceProcesses) {
        workspaceProcesses.count { it.type == WorkspaceProcessType.AI_AGENT_COMMAND }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        tonalElevation = 2.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.85f)
                .padding(horizontal = 20.dp, vertical = 8.dp)
        ) {
            // ── TOP HEADER ──
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.primaryContainer,
                        modifier = Modifier.size(40.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                TnIcons.Terminal,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    }

                    Column {
                        Text(
                            text = "Terminal & Processes",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "${workspace.name} • PRoot Container",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (workspaceProcesses.any { it.status != WorkspaceProcessStatus.RUNNING }) {
                        IconButton(
                            onClick = {
                                bitHaptics.pop()
                                WorkspaceProcessManager.clearFinished(workspace.id)
                            },
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(
                                Icons.Rounded.DeleteSweep,
                                contentDescription = "Clear finished",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }

                    FilledTonalButton(
                        onClick = {
                            bitHaptics.pop()
                            val newHolder = WorkspaceTerminalSessionPool.getOrCreateSession(
                                context = context,
                                workspaceId = workspace.id,
                                workspaceRoot = workspace.root,
                                forceNew = true
                            )
                            onDismiss()
                            onOpenTerminal(newHolder?.processId)
                        },
                        shape = RoundedCornerShape(12.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("New Terminal", style = MaterialTheme.typography.labelMedium)
                    }
                }
            }

            Spacer(Modifier.height(14.dp))

            // ── KPI STATS ROW ──
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    modifier = Modifier.weight(1f)
                ) {
                    Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
                        Text("ACTIVE", style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp), color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                        Text("$activeCount", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                    }
                }

                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f),
                    modifier = Modifier.weight(1f)
                ) {
                    Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
                        Text("AI TASKS", style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp), color = MaterialTheme.colorScheme.secondary, fontWeight = FontWeight.Bold)
                        Text("$aiTasksCount", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.secondary)
                    }
                }

                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    modifier = Modifier.weight(1f)
                ) {
                    Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
                        Text("TOTAL", style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp), color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.SemiBold)
                        Text("${workspaceProcesses.size}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                    }
                }
            }

            Spacer(Modifier.height(14.dp))

            // ── PROCESS LIST ──
            if (workspaceProcesses.isEmpty()) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
                ) {
                    Column(
                        modifier = Modifier
                            .padding(28.dp)
                            .fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.surfaceContainerHigh,
                            modifier = Modifier.size(52.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = TnIcons.Terminal,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(26.dp)
                                )
                            }
                        }
                        Text(
                            text = "No Active Processes",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "No terminal sessions or AI tasks are currently running. Tap \"New Terminal\" to open a bash shell or ask AI to execute scripts.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    itemsIndexed(workspaceProcesses, key = { _, proc -> proc.id }) { index, proc ->
                        val position = when {
                            workspaceProcesses.size == 1 -> ItemPosition.ONLY
                            index == 0 -> ItemPosition.FIRST
                            index == workspaceProcesses.lastIndex -> ItemPosition.LAST
                            else -> ItemPosition.MIDDLE
                        }

                        PhysicsSwipeToDelete(
                            onDelete = {
                                bitHaptics.thud()
                                WorkspaceTerminalSessionPool.deleteSession(proc.id)
                                Toast.makeText(context, "Deleted \"${proc.title}\"", Toast.LENGTH_SHORT).show()
                            },
                            position = position,
                            modifier = Modifier.animateItem()
                        ) { cardShape ->
                            WorkspaceProcessCard(
                                process = proc,
                                shape = cardShape,
                                onOpenTerminal = {
                                    onDismiss()
                                    onOpenTerminal(proc.id)
                                },
                                onKill = {
                                    bitHaptics.thud()
                                    WorkspaceTerminalSessionPool.killSession(proc.id)
                                },
                                onDelete = {
                                    bitHaptics.thud()
                                    WorkspaceTerminalSessionPool.deleteSession(proc.id)
                                    Toast.makeText(context, "Deleted \"${proc.title}\"", Toast.LENGTH_SHORT).show()
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun WorkspaceProcessCard(
    process: WorkspaceProcess,
    shape: androidx.compose.ui.graphics.Shape = RoundedCornerShape(18.dp),
    onOpenTerminal: () -> Unit,
    onKill: () -> Unit,
    onDelete: () -> Unit
) {
    var isExpanded by remember { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current

    val isInteractive = process.type == WorkspaceProcessType.INTERACTIVE_TERMINAL
    val isRunning = process.status == WorkspaceProcessStatus.RUNNING

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (isInteractive && isRunning) Modifier.clickable { onOpenTerminal() }
                else Modifier
            ),
        shape = shape,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        border = BorderStroke(
            1.dp,
            if (isRunning) MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)
            else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
        )
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            // Header Row: Icon + Title & Type Pill + Status
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = if (isInteractive) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.tertiaryContainer,
                        modifier = Modifier.size(36.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = if (isInteractive) TnIcons.Terminal else Icons.Rounded.Build,
                                contentDescription = null,
                                tint = if (isInteractive) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onTertiaryContainer,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }

                    Column(modifier = Modifier.weight(1f)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = process.title,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false)
                            )

                            // Type badge
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = if (isInteractive) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f)
                                else MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.6f),
                                modifier = Modifier.wrapContentWidth()
                            ) {
                                Text(
                                    text = if (isInteractive) "INTERACTIVE" else "AI AGENT",
                                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                                    fontWeight = FontWeight.Bold,
                                    color = if (isInteractive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.tertiary,
                                    maxLines = 1,
                                    softWrap = false,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }

                        Text(
                            text = "Duration: ${process.durationText}",
                            style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                // Status Badge
                Surface(
                    shape = RoundedCornerShape(100.dp),
                    color = when (process.status) {
                        WorkspaceProcessStatus.RUNNING -> Color(0x2222C55E)
                        WorkspaceProcessStatus.COMPLETED -> Color(0x2222C55E)
                        WorkspaceProcessStatus.FAILED -> Color(0x22EF4444)
                        WorkspaceProcessStatus.KILLED -> Color(0x226B7280)
                    },
                    border = BorderStroke(
                        1.dp,
                        when (process.status) {
                            WorkspaceProcessStatus.RUNNING -> Color(0x5522C55E)
                            WorkspaceProcessStatus.COMPLETED -> Color(0x4422C55E)
                            WorkspaceProcessStatus.FAILED -> Color(0x55EF4444)
                            WorkspaceProcessStatus.KILLED -> Color(0x446B7280)
                        }
                    )
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        if (isRunning) {
                            Box(
                                modifier = Modifier
                                    .size(6.dp)
                                    .background(Color(0xFF22C55E), CircleShape)
                            )
                        }
                        Text(
                            text = when (process.status) {
                                WorkspaceProcessStatus.RUNNING -> "RUNNING"
                                WorkspaceProcessStatus.COMPLETED -> "EXIT 0"
                                WorkspaceProcessStatus.FAILED -> "EXIT ${process.exitCode ?: 1}"
                                WorkspaceProcessStatus.KILLED -> "STOPPED"
                            },
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            softWrap = false,
                            color = when (process.status) {
                                WorkspaceProcessStatus.RUNNING -> Color(0xFF4ADE80)
                                WorkspaceProcessStatus.COMPLETED -> Color(0xFF4ADE80)
                                WorkspaceProcessStatus.FAILED -> Color(0xFFF87171)
                                WorkspaceProcessStatus.KILLED -> Color(0xFF9CA3AF)
                            }
                        )
                    }
                }
            }

            Spacer(Modifier.height(10.dp))

            // Command Box
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.7f),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = process.command,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )

                    IconButton(
                        onClick = {
                            clipboard.setText(AnnotatedString(process.command))
                            Toast.makeText(context, "Command copied", Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.size(24.dp)
                    ) {
                        Icon(
                            Icons.Rounded.ContentCopy,
                            contentDescription = "Copy command",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(14.dp)
                        )
                    }
                }
            }

            // Output preview (if AI task)
            if (!isInteractive && process.output.isNotBlank()) {
                Spacer(Modifier.height(8.dp))

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { isExpanded = !isExpanded }
                        .padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (isExpanded) "Hide Output Log" else "View Output Log (${process.output.lines().size} lines)",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold
                    )
                    Icon(
                        imageVector = if (isExpanded) Icons.Rounded.KeyboardArrowUp else Icons.Rounded.KeyboardArrowDown,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp)
                    )
                }

                AnimatedVisibility(visible = isExpanded) {
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = Color.Black,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp)
                    ) {
                        Text(
                            text = process.output,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            color = Color(0xFF81C784),
                            modifier = Modifier
                                .padding(10.dp)
                                .heightIn(max = 180.dp)
                                .verticalScroll(rememberScrollState())
                                .horizontalScroll(rememberScrollState())
                        )
                    }
                }
            }

            Spacer(Modifier.height(10.dp))

            // Action Buttons Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Delete button on every process card
                IconButton(
                    onClick = onDelete,
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        Icons.Rounded.Delete,
                        contentDescription = "Delete process",
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(18.dp)
                    )
                }

                Spacer(Modifier.width(6.dp))

                if (isInteractive) {
                    if (isRunning) {
                        OutlinedButton(
                            onClick = onKill,
                            shape = RoundedCornerShape(10.dp),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
                        ) {
                            Icon(Icons.Rounded.Stop, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Stop", style = MaterialTheme.typography.labelMedium)
                        }
                        Spacer(Modifier.width(8.dp))
                    }

                    Button(
                        onClick = onOpenTerminal,
                        shape = RoundedCornerShape(10.dp),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
                    ) {
                        Icon(TnIcons.Terminal, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(if (isRunning) "Open Terminal" else "Restart Terminal", style = MaterialTheme.typography.labelMedium)
                    }
                } else if (isRunning) {
                    OutlinedButton(
                        onClick = onKill,
                        shape = RoundedCornerShape(10.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
                    ) {
                        Icon(Icons.Rounded.Stop, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Stop Task", style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }
    }
}
