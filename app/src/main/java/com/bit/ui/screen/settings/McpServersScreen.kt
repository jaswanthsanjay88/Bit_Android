package com.bit.ui.screen.settings

import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bit.global.Standards
import com.bit.mcp.McpManager
import com.bit.mcp.McpServerConfig
import com.bit.mcp.McpStatus
import com.bit.mcp.McpToolConfig
import com.bit.mcp.parseMcpServersFromJson
import com.bit.ui.components.ItemPosition
import com.bit.ui.components.PhysicsSwipeToDelete
import com.bit.ui.theme.LocalBitHaptics
import kotlinx.coroutines.launch
import org.json.JSONObject
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState
import java.io.BufferedReader
import java.io.InputStreamReader

fun LazyListScope.mcpServersSection(
    mcpManager: McpManager
) {
    item {
        McpServersContent(mcpManager = mcpManager)
    }
}

@Composable
fun McpServersContent(
    mcpManager: McpManager
) {
    McpServersScreen(mcpManager = mcpManager)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun McpServersScreen(
    mcpManager: McpManager
) {
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    val bitHaptics = LocalBitHaptics.current
    val scope = rememberCoroutineScope()

    val servers by mcpManager.servers.collectAsStateWithLifecycle()
    var localOrder by remember(servers) { mutableStateOf(servers) }

    var showAddDialog by remember { mutableStateOf(false) }
    var showBulkImportDialog by remember { mutableStateOf(false) }
    var isSyncingAll by remember { mutableStateOf(false) }

    val lazyListState = rememberLazyListState()
    val reorderableState = rememberReorderableLazyListState(lazyListState) { from, to ->
        val fromIdx = localOrder.indexOfFirst { it.id == from.key }
        val toIdx = localOrder.indexOfFirst { it.id == to.key }
        if (fromIdx != -1 && toIdx != -1) {
            localOrder = localOrder.toMutableList().apply {
                add(toIdx, removeAt(fromIdx))
            }
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        }
    }

    // File picker for JSON config
    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            try {
                val stream = context.contentResolver.openInputStream(uri)
                if (stream != null) {
                    val content = BufferedReader(InputStreamReader(stream)).readText()
                    val parsed = parseMcpServersFromJson(content)
                    if (parsed.isNotEmpty()) {
                        bitHaptics.success()
                        mcpManager.addServers(parsed)
                        Toast.makeText(context, "Imported ${parsed.size} MCP servers", Toast.LENGTH_SHORT).show()
                    } else {
                        bitHaptics.reject()
                        Toast.makeText(context, "No valid MCP server configuration found in file", Toast.LENGTH_LONG).show()
                    }
                }
            } catch (e: Exception) {
                bitHaptics.reject()
                Toast.makeText(context, "Failed to read file: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    // First-time tool notice dialog
    val prefs = remember { context.getSharedPreferences("bit_ui_prefs", Context.MODE_PRIVATE) }
    var showFirstTimeDialog by remember {
        mutableStateOf(!prefs.getBoolean("has_seen_mcp_notice", false))
    }

    if (showFirstTimeDialog) {
        AlertDialog(
            onDismissRequest = {
                showFirstTimeDialog = false
                prefs.edit().putBoolean("has_seen_mcp_notice", true).apply()
            },
            icon = {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier.size(52.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.Rounded.SmartToy,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.size(28.dp)
                        )
                    }
                }
            },
            title = {
                Text(
                    "Tool-Capable Model Required",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Text(
                    "Model Context Protocol (MCP) servers and tools require instruction-tuned tool calling models (e.g. Qwen 2.5, Llama 3.1/3.2, Claude 3.5, GPT-4o, DeepSeek-V3). Base completion models cannot generate structured tool calls.",
                    style = MaterialTheme.typography.bodyMedium,
                    lineHeight = 20.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        bitHaptics.pop()
                        showFirstTimeDialog = false
                        prefs.edit().putBoolean("has_seen_mcp_notice", true).apply()
                    },
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("Got It")
                }
            },
            shape = RoundedCornerShape(24.dp)
        )
    }

    LazyColumn(
        state = lazyListState,
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentPadding = PaddingValues(horizontal = Standards.SpacingMd, vertical = Standards.SpacingSm),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // ── TOP ACTION BAR ──
        item(key = "action_bar") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "MCP SERVERS",
                        style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 1.sp),
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f)
                    ) {
                        Text(
                            text = "${localOrder.size}",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (localOrder.isNotEmpty()) {
                        IconButton(
                            onClick = {
                                bitHaptics.pop()
                                scope.launch {
                                    isSyncingAll = true
                                    mcpManager.syncAll()
                                    isSyncingAll = false
                                    Toast.makeText(context, "All MCP servers synchronized", Toast.LENGTH_SHORT).show()
                                }
                            },
                            modifier = Modifier.size(36.dp)
                        ) {
                            if (isSyncingAll) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            } else {
                                Icon(
                                    Icons.Rounded.Sync,
                                    contentDescription = "Sync All",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    }

                    // Bulk Import JSON Button
                    IconButton(
                        onClick = {
                            bitHaptics.pop()
                            showBulkImportDialog = true
                        },
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            Icons.Rounded.FileUpload,
                            contentDescription = "Bulk Import",
                            tint = MaterialTheme.colorScheme.secondary,
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    FilledTonalButton(
                        onClick = {
                            bitHaptics.pop()
                            showAddDialog = true
                        },
                        shape = RoundedCornerShape(12.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Add Server", style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }

        // ── EMPTY STATE OR REORDERABLE MCP SERVER CARDS ──
        if (localOrder.isEmpty()) {
            item(key = "empty_state") {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(24.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f),
                            modifier = Modifier.size(56.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    Icons.Rounded.Terminal,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                                    modifier = Modifier.size(28.dp)
                                )
                            }
                        }
                        Text(
                            text = "No MCP Servers Added",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "Connect local or remote MCP servers (Streamable HTTP, SSE, Context7) or import a Claude desktop config.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            lineHeight = 18.sp
                        )
                        Spacer(Modifier.height(4.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                onClick = {
                                    bitHaptics.pop()
                                    showBulkImportDialog = true
                                },
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Icon(Icons.Rounded.FileUpload, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Bulk Import JSON")
                            }
                            Button(
                                onClick = {
                                    bitHaptics.pop()
                                    showAddDialog = true
                                },
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Add Server")
                            }
                        }
                    }
                }
            }
        } else {
            items(localOrder, key = { it.id }) { srv ->
                val index = localOrder.indexOf(srv)
                val position = when {
                    localOrder.size == 1 -> ItemPosition.ONLY
                    index == 0 -> ItemPosition.FIRST
                    index == localOrder.lastIndex -> ItemPosition.LAST
                    else -> ItemPosition.MIDDLE
                }

                ReorderableItem(state = reorderableState, key = srv.id) { isDragging ->
                    val elevation by animateDpAsState(if (isDragging) 8.dp else 0.dp, label = "elevation")
                    val scale by animateFloatAsState(if (isDragging) 1.02f else 1f, label = "scale")
                    val alpha by animateFloatAsState(if (isDragging) 0.92f else 1f, label = "alpha")

                    PhysicsSwipeToDelete(
                        onDelete = {
                            bitHaptics.thud()
                            mcpManager.removeServer(srv.id)
                            Toast.makeText(context, "Deleted \"${srv.name}\"", Toast.LENGTH_SHORT).show()
                        },
                        position = position,
                        modifier = Modifier
                            .graphicsLayer {
                                scaleX = scale
                                scaleY = scale
                                shadowElevation = elevation.toPx()
                                this.alpha = alpha
                            }
                    ) { shape ->
                        McpServerCard(
                            server = srv,
                            shape = shape,
                            dragHandle = {
                                Icon(
                                    Icons.Rounded.DragIndicator,
                                    contentDescription = "Reorder ${srv.name}",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                    modifier = Modifier
                                        .size(48.dp)
                                        .padding(8.dp)
                                        .longPressDraggableHandle(
                                            onDragStarted = {
                                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                            },
                                            onDragStopped = {
                                                mcpManager.setOrderedServers(localOrder)
                                            }
                                        )
                                )
                            },
                            onToggle = { isEnabled ->
                                bitHaptics.selection()
                                mcpManager.toggleServer(srv.id, isEnabled)
                            },
                            onSync = {
                                bitHaptics.pop()
                                scope.launch {
                                    val res = mcpManager.syncServer(srv.id)
                                    if (res.isSuccess) {
                                        Toast.makeText(context, "Synced ${res.getOrNull()?.size ?: 0} tools", Toast.LENGTH_SHORT).show()
                                    } else {
                                        Toast.makeText(context, "Sync failed: ${res.exceptionOrNull()?.message}", Toast.LENGTH_LONG).show()
                                    }
                                }
                            },
                            onToggleTool = { toolName, isEnabled ->
                                bitHaptics.selection()
                                mcpManager.toggleTool(srv.id, toolName, isEnabled)
                            }
                        )
                    }
                }
            }
        }
    }

    // ── ADD SERVER BOTTOM SHEET ──
    if (showAddDialog) {
        AddMcpServerSheet(
            onDismiss = { showAddDialog = false },
            onAdd = { name, url, headers ->
                mcpManager.addServer(name, url, headers)
                showAddDialog = false
                Toast.makeText(context, "Added & Introspecting Tools...", Toast.LENGTH_SHORT).show()
            }
        )
    }

    // ── BULK IMPORT JSON BOTTOM SHEET ──
    if (showBulkImportDialog) {
        McpBulkImportSheet(
            onDismiss = { showBulkImportDialog = false },
            onPickFile = {
                filePickerLauncher.launch(arrayOf("application/json", "text/*", "*/*"))
                showBulkImportDialog = false
            },
            onImport = { newConfigs ->
                mcpManager.addServers(newConfigs)
                showBulkImportDialog = false
                Toast.makeText(context, "Imported ${newConfigs.size} MCP servers", Toast.LENGTH_SHORT).show()
            }
        )
    }
}

@Composable
fun McpServerCard(
    server: McpServerConfig,
    shape: androidx.compose.ui.graphics.Shape,
    dragHandle: @Composable () -> Unit = {},
    onToggle: (Boolean) -> Unit,
    onSync: () -> Unit,
    onToggleTool: (String, Boolean) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val arrowRotation by animateFloatAsState(targetValue = if (expanded) 180f else 0f, label = "arrow")

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = shape,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Header Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Isolated drag handle
                    dragHandle()

                    Surface(
                        shape = CircleShape,
                        color = when (server.status) {
                            is McpStatus.Connected -> MaterialTheme.colorScheme.primaryContainer
                            is McpStatus.Connecting -> MaterialTheme.colorScheme.tertiaryContainer
                            is McpStatus.Error -> MaterialTheme.colorScheme.errorContainer
                            is McpStatus.Idle -> MaterialTheme.colorScheme.surfaceContainerHigh
                        },
                        modifier = Modifier.size(40.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            when (server.status) {
                                is McpStatus.Connected -> Icon(
                                    Icons.Rounded.CheckCircle,
                                    contentDescription = "Connected",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(22.dp)
                                )
                                is McpStatus.Connecting -> CircularProgressIndicator(
                                    modifier = Modifier.size(20.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.tertiary
                                )
                                is McpStatus.Error -> Icon(
                                    Icons.Rounded.ErrorOutline,
                                    contentDescription = "Error",
                                    tint = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(22.dp)
                                )
                                is McpStatus.Idle -> Icon(
                                    Icons.Rounded.CloudQueue,
                                    contentDescription = "Idle",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                        }
                    }

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = server.name,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = server.url,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    IconButton(
                        onClick = onSync,
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            Icons.Rounded.Refresh,
                            contentDescription = "Sync",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp)
                        )
                    }

                    Switch(
                        checked = server.isEnabled,
                        onCheckedChange = onToggle
                    )
                }
            }

            // Error info if present
            if (server.status is McpStatus.Error) {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(10.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Rounded.Warning,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = server.status.message,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            // Tools Expander Bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .clickable { expanded = !expanded }
                    .padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        Icons.Rounded.Handyman,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = "${server.tools.size} Dynamic Tools Introspected",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.secondary
                    )
                }

                Icon(
                    Icons.Rounded.KeyboardArrowDown,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .size(20.dp)
                        .rotate(arrowRotation)
                )
            }

            // Dynamic Tools List
            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (server.tools.isEmpty()) {
                        Text(
                            text = "No tools discovered yet. Tap the refresh icon to query tools/list.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 8.dp)
                        )
                    } else {
                        server.tools.forEach { tool ->
                            ToolItemRow(
                                tool = tool,
                                onToggle = { onToggleTool(tool.name, it) }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ToolItemRow(
    tool: McpToolConfig,
    onToggle: (Boolean) -> Unit
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.5f),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = tool.name,
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                if (tool.description.isNotBlank()) {
                    Text(
                        text = tool.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Switch(
                checked = tool.isEnabled,
                onCheckedChange = onToggle,
                modifier = Modifier.padding(start = 8.dp)
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddMcpServerSheet(
    onDismiss: () -> Unit,
    onAdd: (String, String, Map<String, String>) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var headersText by remember { mutableStateOf("") }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = "Add MCP Server",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )

            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Server Name") },
                placeholder = { Text("e.g. Context7 Docs / Local Dev MCP") },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth()
            )

            OutlinedTextField(
                value = url,
                onValueChange = { url = it },
                label = { Text("Server Endpoint URL") },
                placeholder = { Text("https://mcp.context7.ai/mcp or http://10.73.4.8:3000") },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth()
            )

            OutlinedTextField(
                value = headersText,
                onValueChange = { headersText = it },
                label = { Text("Auth Token or Custom Headers (Optional)") },
                placeholder = { Text("Paste token (e.g. github_pat_...) or JSON headers") },
                maxLines = 3,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth()
            )

            Button(
                onClick = {
                    if (name.isNotBlank() && url.isNotBlank()) {
                        val parsedHeaders = mutableMapOf<String, String>()
                        val trimmedHeaders = headersText.trim()
                        if (trimmedHeaders.isNotBlank()) {
                            if (trimmedHeaders.startsWith("{") && trimmedHeaders.endsWith("}")) {
                                try {
                                    val json = JSONObject(trimmedHeaders)
                                    val keys = json.keys()
                                    while (keys.hasNext()) {
                                        val k = keys.next()
                                        parsedHeaders[k] = json.getString(k)
                                    }
                                } catch (_: Exception) {
                                    parsedHeaders["Authorization"] = if (trimmedHeaders.startsWith("Bearer ", ignoreCase = true)) trimmedHeaders else "Bearer $trimmedHeaders"
                                }
                            } else {
                                // Raw token or key-value format
                                if (trimmedHeaders.contains(":") && !trimmedHeaders.startsWith("http")) {
                                    val parts = trimmedHeaders.split(":", limit = 2)
                                    parsedHeaders[parts[0].trim()] = parts[1].trim()
                                } else {
                                    val authVal = if (trimmedHeaders.startsWith("Bearer ", ignoreCase = true)) trimmedHeaders else "Bearer $trimmedHeaders"
                                    parsedHeaders["Authorization"] = authVal
                                }
                            }
                        }
                        onAdd(name.trim(), url.trim(), parsedHeaders)
                    }
                },
                enabled = name.isNotBlank() && url.isNotBlank(),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Add & Introspect Tools")
            }

            Spacer(Modifier.height(16.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun McpBulkImportSheet(
    onDismiss: () -> Unit,
    onPickFile: () -> Unit,
    onImport: (List<McpServerConfig>) -> Unit
) {
    var jsonText by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 16.dp)
                .imePadding(),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "Bulk Import MCP Servers",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "Paste Claude desktop config or JSON array",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                OutlinedButton(
                    onClick = onPickFile,
                    shape = RoundedCornerShape(12.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Icon(Icons.Rounded.FolderOpen, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Select File")
                }
            }

            OutlinedTextField(
                value = jsonText,
                onValueChange = {
                    jsonText = it
                    errorMessage = null
                },
                placeholder = {
                    Text(
                        "{\n  \"mcpServers\": {\n    \"context7\": {\n      \"url\": \"https://mcp.context7.ai/mcp\"\n    }\n  }\n}",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp
                    )
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 160.dp, max = 260.dp),
                isError = errorMessage != null,
                supportingText = errorMessage?.let { msg -> { Text(msg, color = MaterialTheme.colorScheme.error) } },
                shape = RoundedCornerShape(14.dp)
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End)
            ) {
                TextButton(onClick = onDismiss) {
                    Text("Cancel")
                }

                Button(
                    onClick = {
                        val parsed = parseMcpServersFromJson(jsonText.trim())
                        if (parsed.isEmpty()) {
                            errorMessage = "No valid MCP server definitions found. Ensure JSON contains {\"mcpServers\": {...}} or an array of {name, url} objects."
                        } else {
                            onImport(parsed)
                        }
                    },
                    enabled = jsonText.isNotBlank(),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Rounded.FileUpload, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Import All")
                }
            }

            Spacer(Modifier.height(16.dp))
        }
    }
}
