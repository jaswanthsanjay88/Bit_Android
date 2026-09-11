package com.bit.ui.screen.subagent

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bit.agent.harness.engine.SubagentSessionBus
import com.bit.global.Standards
import com.bit.ui.icons.TnIcons
import com.bit.ui.theme.LocalBitHaptics
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/** Navigate-to-subagent-session function provided app-wide (see MainActivity). */
val LocalSubagentNav = androidx.compose.runtime.compositionLocalOf<(String) -> Unit> { {} }

/**
 * Full-screen drill-down for a running (or finished) subagent session.
 *
 * Shows: the mission prompt the MAIN AGENT gave this subagent, its live thinking/tool
 * activity, and the final result. User messaging is intentionally BLOCKED — only the
 * main agent drives subagents — with an explicit "Return to Main Agent" action.
 */
@Composable
fun SubagentSessionScreen(
    subagentId: String,
    onBack: () -> Unit
) {
    val sessions by SubagentSessionBus.sessions.collectAsStateWithLifecycle()
    val session = sessions[subagentId]
    val haptics = LocalBitHaptics.current
    val listState = rememberLazyListState()

    // Follow live activity while running
    LaunchedEffect(session?.activity?.size, session?.isRunning) {
        if (session?.isRunning == true && session.activity.isNotEmpty()) {
            runCatching { listState.animateScrollToItem(session.activity.size) }
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Standards.SpacingMd, vertical = Standards.SpacingSm)
                        .padding(top = 24.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Standards.SpacingSm)
                ) {
                    IconButton(onClick = {
                        haptics.selection()
                        onBack()
                    }) {
                        Icon(imageVector = TnIcons.ChevronLeft, contentDescription = "Back")
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = session?.role ?: "Subagent Session",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            val status = session?.status ?: "UNKNOWN"
                            val statusColor = when (status) {
                                "RUNNING" -> MaterialTheme.colorScheme.primary
                                "COMPLETED" -> androidx.compose.ui.graphics.Color(0xFF4CAF50)
                                "WARNING" -> androidx.compose.ui.graphics.Color(0xFFFFA000)
                                else -> MaterialTheme.colorScheme.error
                            }
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(statusColor)
                            )
                            Text(
                                text = if (status == "RUNNING" && session != null)
                                    "Working · round ${session.currentRound}/${session.maxSteps}"
                                else status,
                                style = MaterialTheme.typography.labelMedium,
                                color = statusColor,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }
            }
        },
        bottomBar = {
            // Blocked composer: subagents are driven by the main agent only.
            Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(Standards.SpacingMd)
                        .padding(bottom = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = TnIcons.AlertCircle,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = "Only the main agent can send prompts here — you can't message this subagent.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f)
                        )
                    }
                    Button(
                        onClick = {
                            haptics.selection()
                            onBack()
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("← Return to Main Agent")
                    }
                }
            }
        }
    ) { padding ->
        if (session == null) {
            Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text(
                    "This subagent session has ended or was not found.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            return@Scaffold
        }

        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(Standards.SpacingMd),
            verticalArrangement = Arrangement.spacedBy(Standards.SpacingSm)
        ) {
            // ── Mission: the prompt the main agent gave this subagent ──
            item(key = "mission") {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(Standards.RadiusMd),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                ) {
                    Column(modifier = Modifier.fillMaxWidth().padding(Standards.SpacingMd)) {
                        Text(
                            text = "MISSION · from main agent",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = session.missionPrompt,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.9f)
                        )
                    }
                }
            }

            // ── Live activity ──
            item(key = "activity-header") {
                Text(
                    text = "Thinking & Activity",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = Standards.SpacingXs)
                )
            }
            if (session.activity.isEmpty()) {
                item(key = "activity-empty") {
                    Text(
                        text = if (session.isRunning) "Starting up…" else "No recorded activity.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    )
                }
            } else {
                items(session.activity, key = { "${it.timestampMs}_${it.text.hashCode()}" }) { line ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "R${line.round}",
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.8f)
                        )
                        Text(
                            text = line.text,
                            style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f)
                        )
                    }
                }
            }

            // ── Result ──
            if (!session.isRunning && !session.result.isNullOrBlank()) {
                item(key = "result") {
                    Spacer(modifier = Modifier.height(Standards.SpacingSm))
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(Standards.RadiusMd),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f)
                    ) {
                        Column(modifier = Modifier.fillMaxWidth().padding(Standards.SpacingMd)) {
                            Text(
                                text = "RESULT",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.tertiary
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = session.result,
                                style = MaterialTheme.typography.bodySmall.copy(
                                    fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 15.sp
                                ),
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f),
                                maxLines = 40,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }

            item(key = "bottom-space") { Spacer(modifier = Modifier.height(Standards.SpacingLg)) }
        }
    }
}
