package com.bit.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bit.global.Standards
import com.bit.models.messages.Messages
import com.bit.models.messages.ToolChainStepData
import com.bit.ui.icons.TnIcons
import com.bit.ui.theme.Glass
import org.json.JSONObject

data class TraceStep(
    val toolName: String,
    val pluginName: String,
    val result: String,
    val success: Boolean,
    val executionTimeMs: Long,
    val inputParams: String = ""
)

data class CleanToolResult(
    val stdout: String = "",
    val stderr: String = "",
    val exitCode: Int? = null,
    val path: String = "",
    val message: String = "",
    val status: String = "",
    val raw: String = ""
)

fun ToolChainStepData.toTraceStep() = TraceStep(
    toolName = toolName,
    pluginName = pluginName,
    result = result,
    success = success,
    executionTimeMs = executionTimeMs,
    inputParams = args
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

fun parseToolResult(raw: String): CleanToolResult {
    if (raw.isBlank()) return CleanToolResult()
    return try {
        val json = JSONObject(raw)
        val stdout = json.optString("stdout", "")
        val stderr = json.optString("stderr", "")
        val output = json.optString("output", "")
        val content = json.optString("content", "")
        val path = json.optString("path", "")
        val message = json.optString("message", "")
        val status = json.optString("status", "")
        val exitCode = if (json.has("exitCode")) json.optInt("exitCode") else null

        val cleanStderr = filterInternalProotNoise(stderr)
        val cleanStdout = stdout.ifBlank { output }.ifBlank { content }

        CleanToolResult(
            stdout = cleanStdout,
            stderr = cleanStderr,
            exitCode = exitCode,
            path = path,
            message = message,
            status = status,
            raw = raw
        )
    } catch (e: Exception) {
        CleanToolResult(stdout = raw, raw = raw)
    }
}

fun filterInternalProotNoise(stderr: String): String {
    if (stderr.isBlank()) return ""
    val lines = stderr.lines()
    val cleanLines = lines.filterNot { line ->
        line.contains("=== proot sanity check ===") ||
        line.contains("=== rootfs diagnostic ===") ||
        line.contains("=== proot shell-only test ===") ||
        line.contains("=== verbose proot output") ||
        line.contains("Tried proot launch modes:") ||
        line.contains("Android still failed while proot was entering") ||
        line.startsWith("bash ELF interpreter:") ||
        line.trim().startsWith("/lib") ||
        line.trim().startsWith("/usr/lib") ||
        line.contains("PROOT_LOADER") ||
        line.contains("libproot_exec.so") ||
        line.contains("libproot_loader.so")
    }
    return cleanLines.joinToString("\n").trim()
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
    val timeStr = if (totalTimeMs > 0) " (${String.format(java.util.Locale.US, "%.1f", totalTimeMs / 1000f)}s)" else ""

    val totalSteps = steps.size
    val label = if (totalSteps > 0) {
        "Executed $totalSteps tool${if (totalSteps != 1) "s" else ""}$timeStr"
    } else {
        "Tool execution process"
    }

    val icon = TnIcons.BrainCircuit

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 2.dp)
            .animateContentSize(
                animationSpec = tween(
                    durationMillis = com.bit.ui.theme.MotionDuration.stateChange,
                    easing = com.bit.ui.theme.MotionEasing.standard
                )
            )
    ) {
        // Main Header Row (Containerless Inline Toggle)
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
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Normal,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f)
            )
            if (isLive && currentRound > 0) {
                Spacer(Modifier.width(6.dp))
                Text(
                    text = "(Round $currentRound/$maxRounds)",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.75f)
                )
            }
            Spacer(Modifier.width(4.dp))
            Icon(
                imageVector = if (isExpanded) TnIcons.ChevronDown else TnIcons.ChevronRight,
                contentDescription = if (isExpanded) "Collapse" else "Expand",
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f),
                modifier = Modifier.size(15.dp)
            )
        }

        if (isExpanded) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp, bottom = 4.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (plan != null) {
                    PlanDagTodoListSection(planText = plan)
                }

                steps.forEachIndexed { idx, step ->
                    ReasoningStepCard(step = step, index = idx + 1)
                }

                if (isLive) {
                    ReasoningLoadingRow()
                }
            }
        }
    }
}

@Composable
private fun ReasoningStepCard(step: TraceStep, index: Int) {
    var stepExpanded by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val haptics = com.bit.ui.theme.LocalBitHaptics.current

    val (cmdOrQuery, rawArgsDisplay) = remember(step.inputParams) {
        try {
            val json = JSONObject(step.inputParams)
            val cmd = json.optString("command").takeIf { it.isNotBlank() }
                ?: json.optString("query").takeIf { it.isNotBlank() }
                ?: json.optString("path").takeIf { it.isNotBlank() }
                ?: json.optString("url").takeIf { it.isNotBlank() }
                ?: ""
            cmd to json.toString(2)
        } catch (e: Exception) {
            step.inputParams to step.inputParams
        }
    }

    val toolDisplayName = when (step.toolName.lowercase()) {
        "workspace_shell" -> "Workspace Shell"
        "workspace_read_file" -> "Read File"
        "workspace_write_file" -> "Write File"
        "workspace_edit_file" -> "Edit File"
        "web_search", "search" -> "Web Search"
        "read_url", "web_fetch" -> "Web Fetch"
        "calculator", "math" -> "Calculator"
        else -> step.toolName.replace('_', ' ').replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
    }

    val parsedResult = remember(step.result) { parseToolResult(step.result) }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = androidx.compose.foundation.BorderStroke(
            0.8.dp,
            if (step.success) MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f)
            else MaterialTheme.colorScheme.error.copy(alpha = 0.35f)
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp)
        ) {
            // Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        haptics.selection()
                        stepExpanded = !stepExpanded
                    }
                    .padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Status icon
                Icon(
                    imageVector = if (step.success) TnIcons.Check else TnIcons.AlertCircle,
                    contentDescription = null,
                    tint = if (step.success) Color(0xFF10B981) else MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(16.dp)
                )

                Spacer(Modifier.width(8.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = toolDisplayName,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        if (cmdOrQuery.isNotBlank()) {
                            Text(
                                text = "— $cmdOrQuery",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }

                if (step.executionTimeMs > 0) {
                    Text(
                        text = "${String.format(java.util.Locale.US, "%.1f", step.executionTimeMs / 1000f)}s",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    )
                }

                Spacer(Modifier.width(6.dp))

                Icon(
                    imageVector = if (stepExpanded) TnIcons.ChevronUp else TnIcons.ChevronDown,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(14.dp)
                )
            }

            // Expanded Output Area (Claude Code / Antigravity Terminal View)
            if (stepExpanded) {
                Spacer(Modifier.height(8.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.15f))
                Spacer(Modifier.height(6.dp))

                // Input Parameters / Command
                if (cmdOrQuery.isNotBlank() || step.inputParams.isNotBlank()) {
                    Text(
                        text = "INPUT / COMMAND",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.8f),
                        letterSpacing = 0.5.sp
                    )
                    Spacer(Modifier.height(3.dp))
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(6.dp),
                        color = Color(0xFF0F172A)
                    ) {
                        SelectionContainer {
                            Text(
                                text = if (cmdOrQuery.isNotBlank()) "$ $cmdOrQuery" else rawArgsDisplay,
                                style = MaterialTheme.typography.bodySmall.copy(
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 12.sp,
                                    lineHeight = 16.sp
                                ),
                                color = Color(0xFFE2E8F0),
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                            )
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                }

                // Output / Stdout / Stderr
                Text(
                    text = "OUTPUT",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    letterSpacing = 0.5.sp
                )
                Spacer(Modifier.height(3.dp))

                val outputText = buildString {
                    if (parsedResult.stdout.isNotBlank()) {
                        append(parsedResult.stdout.trim())
                    }
                    if (parsedResult.stderr.isNotBlank()) {
                        if (isNotEmpty()) append("\n")
                        append("STDERR:\n").append(parsedResult.stderr.trim())
                    }
                    if (isEmpty() && parsedResult.message.isNotBlank()) {
                        append(parsedResult.message.trim())
                    }
                    if (isEmpty() && parsedResult.path.isNotBlank()) {
                        append("File path: ").append(parsedResult.path)
                    }
                    if (isEmpty()) {
                        append(if (step.success) "(Command completed with exit code 0 and no output)" else "(Command failed with no output)")
                    }
                }

                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(6.dp),
                    color = Color(0xFF0F172A),
                    border = androidx.compose.foundation.BorderStroke(
                        0.5.dp,
                        if (parsedResult.stderr.isNotBlank() || !step.success) MaterialTheme.colorScheme.error.copy(alpha = 0.4f)
                        else Color(0xFF334155)
                    )
                ) {
                    Column(modifier = Modifier.padding(8.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            parsedResult.exitCode?.let { code ->
                                Surface(
                                    shape = RoundedCornerShape(4.dp),
                                    color = if (code == 0) Color(0xFF10B981).copy(alpha = 0.15f) else Color(0xFFEF4444).copy(alpha = 0.15f)
                                ) {
                                    Text(
                                        text = "exit $code",
                                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                                        color = if (code == 0) Color(0xFF34D399) else Color(0xFFF87171),
                                        modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                                    )
                                }
                            }
                            Spacer(Modifier.weight(1f))
                            Icon(
                                imageVector = TnIcons.Copy,
                                contentDescription = "Copy output",
                                tint = Color(0xFF94A3B8),
                                modifier = Modifier
                                    .size(14.dp)
                                    .clickable {
                                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                        clipboard.setPrimaryClip(ClipData.newPlainText("Tool Output", outputText))
                                        Toast.makeText(context, "Output copied", Toast.LENGTH_SHORT).show()
                                    }
                            )
                        }
                        Spacer(Modifier.height(4.dp))
                        SelectionContainer {
                            Text(
                                text = outputText,
                                style = MaterialTheme.typography.bodySmall.copy(
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 11.5.sp,
                                    lineHeight = 16.sp
                                ),
                                color = if (parsedResult.stderr.isNotBlank() && parsedResult.stdout.isBlank()) Color(0xFFFCA5A5) else Color(0xFFE2E8F0)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun PulsingDotLoader(
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.onSurfaceVariant,
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
            .padding(vertical = 4.dp, horizontal = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(Standards.SpacingSm),
        verticalAlignment = Alignment.CenterVertically
    ) {
        PulsingDotLoader(
            modifier = Modifier.padding(end = 4.dp),
            color = MaterialTheme.colorScheme.primary
        )
        Text(
            text = "Executing tool...",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.Medium
        )
    }
}

private data class DagTodoItem(
    val status: String,
    val text: String,
    val toolTag: String? = null
)

@Composable
fun PlanDagTodoListSection(planText: String, modifier: Modifier = Modifier) {
    val items = remember(planText) {
        planText.lines().mapNotNull { line ->
            val trimmed = line.trim()
            if (!trimmed.startsWith("- [")) return@mapNotNull null
            val status = when {
                trimmed.startsWith("- [x]", ignoreCase = true) -> "DONE"
                trimmed.startsWith("- [⏳]") || trimmed.startsWith("- [running]", ignoreCase = true) -> "RUNNING"
                trimmed.startsWith("- [!]") || trimmed.startsWith("- [error]", ignoreCase = true) -> "ERROR"
                else -> "PENDING"
            }
            val content = trimmed.substringAfter("] ").trim()
            val toolTag = if (content.contains("`")) {
                content.substringAfter("`").substringBefore("`")
            } else null
            val cleanText = content.replace("`$toolTag`", "").replace("**", "").trim()
            DagTodoItem(status, cleanText, toolTag)
        }
    }

    if (items.isEmpty()) {
        Text(
            text = planText,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp)
        )
        return
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Icon(
                imageVector = TnIcons.BrainCircuit,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(13.dp)
            )
            Text(
                text = "Task Execution Plan (DAG)",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
        }

        items.forEachIndexed { _, item ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)
            ) {
                when (item.status) {
                    "DONE" -> {
                        Box(
                            modifier = Modifier
                                .size(16.dp)
                                .clip(CircleShape)
                                .background(Color(0xFF4CAF50).copy(alpha = 0.15f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "✓",
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF4CAF50)
                            )
                        }
                    }
                    "RUNNING" -> {
                        CircularProgressIndicator(
                            modifier = Modifier.size(14.dp),
                            strokeWidth = 1.5.dp,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    "ERROR" -> {
                        Box(
                            modifier = Modifier
                                .size(16.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.error.copy(alpha = 0.15f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "!",
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                    else -> {
                        Box(
                            modifier = Modifier
                                .size(14.dp)
                                .clip(CircleShape)
                                .border(1.dp, MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f), CircleShape)
                        )
                    }
                }

                Spacer(Modifier.width(8.dp))

                Text(
                    text = item.text,
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.5.sp),
                    color = if (item.status == "DONE") {
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                    fontWeight = if (item.status == "RUNNING") FontWeight.SemiBold else FontWeight.Normal,
                    modifier = Modifier.weight(1f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )

                if (item.toolTag != null) {
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = item.toolTag,
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp, fontFamily = FontFamily.Monospace),
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.8f),
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.08f))
                            .padding(horizontal = 4.dp, vertical = 2.dp)
                    )
                }
            }
        }
    }
}
