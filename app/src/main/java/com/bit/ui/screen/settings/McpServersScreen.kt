package com.bit.ui.screen.settings

import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bit.global.Standards
import com.bit.mcp.McpCommonOptions
import com.bit.mcp.McpManager
import com.bit.mcp.McpOAuthState
import com.bit.mcp.McpServerConfig
import com.bit.mcp.McpStatus
import com.bit.mcp.McpTool
import com.bit.mcp.headers
import com.bit.mcp.isEnabled
import com.bit.mcp.name
import com.bit.mcp.parseMcpServersFromJson
import com.bit.mcp.tools
import com.bit.mcp.url
import com.bit.ui.components.ItemPosition
import com.bit.ui.components.PhysicsSwipeToDelete
import com.bit.ui.icons.TnIcons
import com.bit.ui.theme.LocalBitHaptics
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
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
    val serverStatusMap by mcpManager.syncingStatus.collectAsStateWithLifecycle()
    var localOrder by remember(servers) { mutableStateOf(servers.distinctBy { it.id }) }

    var showAddDialog by remember { mutableStateOf(false) }
    var editingServer by remember { mutableStateOf<McpServerConfig?>(null) }
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
        }
    }

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            try {
                context.contentResolver.openInputStream(it)?.use { stream ->
                    val text = BufferedReader(InputStreamReader(stream)).readText()
                    val parsed = parseMcpServersFromJson(text)
                    if (parsed.isNotEmpty()) {
                        mcpManager.addServers(parsed)
                        Toast.makeText(context, "Imported ${parsed.size} MCP servers", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(context, "No valid MCP servers found in file", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                Toast.makeText(context, "Failed to import: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
    ) {
        // Top Toolbar
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            shape = RoundedCornerShape(Standards.RadiusLg),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
            )
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primaryContainer),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = TnIcons.McpServer,
                            contentDescription = "MCP Logo",
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            text = "Model Context Protocol",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = "${servers.count { it.isEnabled }} active • ${servers.sumOf { srv -> srv.tools.count { it.enable } }} tools",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        onClick = {
                            if (!isSyncingAll) {
                                isSyncingAll = true
                                scope.launch {
                                    mcpManager.syncAll()
                                    isSyncingAll = false
                                    Toast.makeText(context, "All MCP servers synced", Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                    ) {
                        if (isSyncingAll) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(
                                imageVector = Icons.Rounded.Refresh,
                                contentDescription = "Sync All",
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }

                    IconButton(onClick = { showBulkImportDialog = true }) {
                        Icon(
                            imageVector = Icons.Rounded.UploadFile,
                            contentDescription = "Import JSON",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    FilledTonalButton(
                        onClick = { showAddDialog = true },
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Icon(imageVector = Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(text = "Add", style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }

        if (servers.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(24.dp)
                ) {
                    Icon(
                        imageVector = TnIcons.Mcp,
                        contentDescription = null,
                        modifier = Modifier.size(64.dp),
                        tint = MaterialTheme.colorScheme.outlineVariant
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "No MCP Servers Configured",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "Add Streamable HTTP or SSE remote tool endpoints or import Claude Desktop config.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Button(onClick = { showAddDialog = true }) {
                        Icon(imageVector = Icons.Rounded.Add, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Add First Server")
                    }
                }
            }
        } else {
            LazyColumn(
                state = lazyListState,
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(bottom = 96.dp)
            ) {
                items(localOrder, key = { it.id }) { server ->
                    ReorderableItem(reorderableState, key = server.id) { isDragging ->
                        val status = serverStatusMap[server.id] ?: McpStatus.Idle
                        PhysicsSwipeToDelete(
                            onDelete = {
                                mcpManager.removeServer(server.id)
                                Toast.makeText(context, "Removed '${server.name}'", Toast.LENGTH_SHORT).show()
                            },
                            position = ItemPosition.ONLY
                        ) {
                            McpServerCard(
                                server = server,
                                status = status,
                                isDragging = isDragging,
                                onToggleServer = { mcpManager.toggleServer(server.id, it) },
                                onToggleTool = { toolName, enabled -> mcpManager.toggleTool(server.id, toolName, enabled) },
                                onToggleToolApproval = { toolName, needsApproval ->
                                    mcpManager.toggleToolNeedsApproval(server.id, toolName, needsApproval)
                                },
                                onSyncServer = {
                                    scope.launch {
                                        val res = mcpManager.syncServer(server.id)
                                        if (res.isSuccess) {
                                            Toast.makeText(context, "Discovered ${res.getOrNull()?.size ?: 0} tools", Toast.LENGTH_SHORT).show()
                                        } else {
                                            Toast.makeText(context, "Sync failed: ${res.exceptionOrNull()?.message}", Toast.LENGTH_LONG).show()
                                        }
                                    }
                                },
                                onStartOAuth = {
                                    mcpManager.startAuthorization(server, context)
                                },
                                onClearOAuth = {
                                    scope.launch {
                                        mcpManager.clearAuthorization(server)
                                        Toast.makeText(context, "OAuth credentials cleared", Toast.LENGTH_SHORT).show()
                                    }
                                },
                                onEditServer = { editingServer = server },
                                onDeleteServer = { mcpManager.removeServer(server.id) }
                            )
                        }
                    }
                }
            }
        }
    }

    if (showAddDialog) {
        McpServerEditDialog(
            server = null,
            onDismiss = { showAddDialog = false },
            onSave = { name, url, isStreamable, headers, oauth ->
                mcpManager.addServer(name, url, isStreamable, headers, oauth)
                showAddDialog = false
            }
        )
    }

    editingServer?.let { srv ->
        McpServerEditDialog(
            server = srv,
            onDismiss = { editingServer = null },
            onSave = { name, url, isStreamable, headers, oauth ->
                val updated = if (isStreamable) {
                    McpServerConfig.StreamableHTTPServer(
                        id = srv.id,
                        commonOptions = srv.commonOptions.copy(name = name, headers = headers, oauth = oauth),
                        url = url
                    )
                } else {
                    McpServerConfig.SseTransportServer(
                        id = srv.id,
                        commonOptions = srv.commonOptions.copy(name = name, headers = headers, oauth = oauth),
                        url = url
                    )
                }
                mcpManager.updateServer(updated)
                editingServer = null
            }
        )
    }

    if (showBulkImportDialog) {
        McpBulkImportDialog(
            onDismiss = { showBulkImportDialog = false },
            onOpenFilePicker = { filePickerLauncher.launch("application/json") },
            onImportJsonText = { jsonText ->
                val parsed = parseMcpServersFromJson(jsonText)
                if (parsed.isNotEmpty()) {
                    mcpManager.addServers(parsed)
                    Toast.makeText(context, "Imported ${parsed.size} MCP servers", Toast.LENGTH_SHORT).show()
                    showBulkImportDialog = false
                } else {
                    Toast.makeText(context, "No valid MCP servers found", Toast.LENGTH_SHORT).show()
                }
            }
        )
    }
}

@Composable
fun McpServerCard(
    server: McpServerConfig,
    status: McpStatus,
    isDragging: Boolean,
    onToggleServer: (Boolean) -> Unit,
    onToggleTool: (String, Boolean) -> Unit,
    onToggleToolApproval: (String, Boolean) -> Unit,
    onSyncServer: () -> Unit,
    onStartOAuth: () -> Unit,
    onClearOAuth: () -> Unit,
    onEditServer: () -> Unit,
    onDeleteServer: () -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize(),
        shape = RoundedCornerShape(Standards.RadiusLg),
        colors = CardDefaults.cardColors(
            containerColor = if (isDragging) MaterialTheme.colorScheme.surfaceContainerHighest else MaterialTheme.colorScheme.surfaceContainer
        ),
        border = BorderStroke(
            1.dp,
            if (server.isEnabled) MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f) else Color.Transparent
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp)
        ) {
            // Header Row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(
                            if (server.isEnabled) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f)
                            else MaterialTheme.colorScheme.surfaceVariant
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = TnIcons.Mcp,
                        contentDescription = null,
                        tint = if (server.isEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp)
                    )
                }

                Spacer(modifier = Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = server.name.ifBlank { "Untitled Server" },
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        McpStatusBadge(status = status)
                    }

                    Text(
                        text = server.url.ifBlank { "No URL" },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        fontFamily = FontFamily.Monospace
                    )
                }

                Switch(
                    checked = server.isEnabled,
                    onCheckedChange = onToggleServer,
                    modifier = Modifier.padding(start = 8.dp)
                )
            }

            // Expandable Details
            AnimatedVisibility(
                visible = expanded,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 14.dp)
                ) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                    Spacer(modifier = Modifier.height(10.dp))

                    // Transport type badge & headers
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Transport: ${if (server is McpServerConfig.StreamableHTTPServer) "Streamable HTTP" else "Server-Sent Events (SSE)"}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = "${server.headers.size} custom headers",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    // OAuth 2.1 Section
                    val oauth = server.commonOptions.oauth
                    if (oauth != null && oauth.enabled || status is McpStatus.NeedsAuthorization) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                            ),
                            shape = RoundedCornerShape(Standards.RadiusMd)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "OAuth 2.1 Authorization",
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Text(
                                        text = if (oauth?.isAuthorized == true) "Authorized • Bearer Token Active" else "Needs Authorization",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = if (oauth?.isAuthorized == true) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                                    )
                                }

                                if (oauth?.isAuthorized == true) {
                                    TextButton(onClick = onClearOAuth) {
                                        Text("Disconnect", color = MaterialTheme.colorScheme.error)
                                    }
                                } else {
                                    Button(onClick = onStartOAuth) {
                                        Text("Connect")
                                    }
                                }
                            }
                        }
                    }

                    // Discovered Tools List
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "Discovered Tools (${server.tools.size})",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(modifier = Modifier.height(6.dp))

                    if (server.tools.isEmpty()) {
                        Text(
                            text = "No tools discovered yet. Tap 'Sync Server' to introspect tools/list.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            server.tools.forEach { tool ->
                                McpToolItem(
                                    tool = tool,
                                    onToggleEnable = { onToggleTool(tool.name, it) },
                                    onToggleApproval = { onToggleToolApproval(tool.name, it) }
                                )
                            }
                        }
                    }

                    // Action buttons
                    Spacer(modifier = Modifier.height(14.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TextButton(onClick = onSyncServer) {
                            Icon(imageVector = Icons.Rounded.Sync, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Sync Server")
                        }
                        Spacer(modifier = Modifier.width(6.dp))
                        TextButton(onClick = onEditServer) {
                            Icon(imageVector = Icons.Rounded.Edit, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Edit")
                        }
                        Spacer(modifier = Modifier.width(6.dp))
                        TextButton(
                            onClick = onDeleteServer,
                            colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                        ) {
                            Icon(imageVector = Icons.Rounded.Delete, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Delete")
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun McpToolItem(
    tool: McpTool,
    onToggleEnable: (Boolean) -> Unit,
    onToggleApproval: (Boolean) -> Unit
) {
    var showSchema by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(Standards.RadiusMd),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = tool.name,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        fontFamily = FontFamily.Monospace
                    )
                    if (!tool.description.isNullOrBlank()) {
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
                    checked = tool.enable,
                    onCheckedChange = onToggleEnable
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.clickable { onToggleApproval(!tool.needsApproval) }
                ) {
                    Checkbox(
                        checked = tool.needsApproval,
                        onCheckedChange = onToggleApproval,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Require Approval",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                TextButton(
                    onClick = { showSchema = !showSchema },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = if (showSchema) "Hide Schema" else "View Schema",
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }

            AnimatedVisibility(visible = showSchema) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerLowest)
                        .padding(8.dp)
                ) {
                    Text(
                        text = tool.inputSchemaJson,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }
    }
}

@Composable
fun McpStatusBadge(status: McpStatus) {
    val (color, text) = when (status) {
        is McpStatus.Connected -> MaterialTheme.colorScheme.primary to "Connected"
        is McpStatus.Connecting -> MaterialTheme.colorScheme.tertiary to "Connecting"
        is McpStatus.Reconnecting -> MaterialTheme.colorScheme.tertiary to "Reconnecting"
        is McpStatus.NeedsAuthorization -> MaterialTheme.colorScheme.error to "Needs OAuth"
        is McpStatus.Authorizing -> MaterialTheme.colorScheme.primary to "Authorizing"
        is McpStatus.Error -> MaterialTheme.colorScheme.error to "Error"
        is McpStatus.Stopped -> MaterialTheme.colorScheme.outline to "Stopped"
        is McpStatus.Idle -> MaterialTheme.colorScheme.outline to "Idle"
    }

    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(color.copy(alpha = 0.15f))
            .padding(horizontal = 8.dp, vertical = 2.dp)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = color,
            fontWeight = FontWeight.Medium
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun McpServerEditDialog(
    server: McpServerConfig?,
    onDismiss: () -> Unit,
    onSave: (name: String, url: String, isStreamable: Boolean, headers: List<Pair<String, String>>, oauth: McpOAuthState?) -> Unit
) {
    var name by remember(server) { mutableStateOf(server?.name.orEmpty()) }
    var url by remember(server) { mutableStateOf(server?.url.orEmpty()) }
    var isStreamable by remember(server) { mutableStateOf(server is McpServerConfig.StreamableHTTPServer || server == null) }
    var enableOAuth by remember(server) { mutableStateOf(server?.commonOptions?.oauth?.enabled == true) }
    var customHeaders by remember(server) {
        mutableStateOf(
            server?.commonOptions?.headers?.joinToString("\n") { "${it.first}: ${it.second}" }.orEmpty()
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(if (server == null) "Add MCP Server" else "Edit MCP Server")
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Server Name") },
                    placeholder = { Text("e.g. GitHub Copilot MCP") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text("Server URL / Endpoint") },
                    placeholder = { Text("https://api.githubcopilot.com/mcp") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Transport Protocol", style = MaterialTheme.typography.bodyMedium)
                    SingleChoiceSegmentedButtonRow {
                        SegmentedButton(
                            selected = isStreamable,
                            onClick = { isStreamable = true },
                            shape = SegmentedButtonDefaults.itemShape(0, 2)
                        ) {
                            Text("HTTP")
                        }
                        SegmentedButton(
                            selected = !isStreamable,
                            onClick = { isStreamable = false },
                            shape = SegmentedButtonDefaults.itemShape(1, 2)
                        ) {
                            Text("SSE")
                        }
                    }
                }

                OutlinedTextField(
                    value = customHeaders,
                    onValueChange = { customHeaders = it },
                    label = { Text("Custom Headers (Key: Value per line)") },
                    placeholder = { Text("Authorization: Bearer ghp_your_token") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2,
                    maxLines = 4
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text("OAuth 2.1 Authentication", style = MaterialTheme.typography.bodyMedium)
                        Text("Auto-discover PRM / AS endpoints", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(checked = enableOAuth, onCheckedChange = { enableOAuth = it })
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val parsedHeaders = customHeaders.lines()
                        .map { it.trim() }
                        .filter { it.isNotBlank() }
                        .mapNotNull { line ->
                            when {
                                line.contains(":") -> {
                                    val parts = line.split(":", limit = 2)
                                    parts[0].trim() to parts[1].trim()
                                }
                                line.contains("=") -> {
                                    val parts = line.split("=", limit = 2)
                                    parts[0].trim() to parts[1].trim()
                                }
                                line.startsWith("Bearer ", ignoreCase = true) -> {
                                    "Authorization" to line.trim()
                                }
                                line.startsWith("token ", ignoreCase = true) -> {
                                    "Authorization" to "Bearer " + line.substring(6).trim()
                                }
                                line.startsWith("ghp_") || line.startsWith("github_pat_") -> {
                                    "Authorization" to "Bearer $line"
                                }
                                else -> "Authorization" to "Bearer $line"
                            }
                        }
                    val oauthState = if (enableOAuth) {
                        server?.commonOptions?.oauth ?: McpOAuthState(enabled = true)
                    } else null
                    onSave(name.trim(), url.trim(), isStreamable, parsedHeaders, oauthState)
                },
                enabled = name.isNotBlank() && url.isNotBlank()
            ) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@Composable
fun McpBulkImportDialog(
    onDismiss: () -> Unit,
    onOpenFilePicker: () -> Unit,
    onImportJsonText: (String) -> Unit
) {
    var inputContent by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    AlertDialog(
        onDismissRequest = { if (!loading) onDismiss() },
        title = {
            Text("Import MCP Server Configuration", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "Select a .json file, paste MCP configuration JSON, or enter a configuration URL:",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Button(
                    onClick = onOpenFilePicker,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    enabled = !loading
                ) {
                    Icon(imageVector = Icons.Rounded.FolderOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Select JSON File")
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                OutlinedTextField(
                    value = inputContent,
                    onValueChange = { inputContent = it },
                    label = { Text("MCP JSON or URL") },
                    placeholder = { Text("https://... or {\n  \"mcpServers\": {\n    ...\n  }\n}") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(140.dp),
                    enabled = !loading,
                    shape = RoundedCornerShape(12.dp),
                    textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                )

                if (loading) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Text("Downloading configuration...", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val trimmed = inputContent.trim()
                    if (trimmed.startsWith("http://", ignoreCase = true) || trimmed.startsWith("https://", ignoreCase = true)) {
                        loading = true
                        scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                            try {
                                val url = java.net.URL(trimmed)
                                val conn = url.openConnection() as java.net.HttpURLConnection
                                conn.connectTimeout = 10_000
                                conn.readTimeout = 15_000
                                val text = conn.inputStream.bufferedReader().use { it.readText() }
                                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                                    loading = false
                                    onImportJsonText(text)
                                }
                            } catch (e: Exception) {
                                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                                    loading = false
                                    onImportJsonText("")
                                }
                            }
                        }
                    } else {
                        onImportJsonText(trimmed)
                    }
                },
                enabled = inputContent.isNotBlank() && !loading,
                shape = RoundedCornerShape(12.dp)
            ) {
                Text("Import")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !loading) {
                Text("Cancel")
            }
        },
        shape = RoundedCornerShape(20.dp)
    )
}
