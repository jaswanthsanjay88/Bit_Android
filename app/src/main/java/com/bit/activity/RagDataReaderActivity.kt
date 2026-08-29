package com.bit.activity

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
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
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bit.engine.EmbeddingEngine
import com.bit.neuron_example.GraphSettings
import com.bit.neuron_example.NeuronGraph
import com.bit.neuron_example.NeuronNode
import com.bit.neuron_example.SourceType
import com.bit.ui.components.NeuronGraphCanvas
import com.bit.ui.components.PasswordTextField
import com.bit.ui.icons.TnIcons
import com.bit.ui.theme.LocalBitHaptics
import com.bit.ui.theme.NeuroVerseTheme
import com.neuronpacket.NeuronPacketManager
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

@AndroidEntryPoint
class RagDataReaderActivity : ComponentActivity() {

    @Inject
    lateinit var embeddingEngine: EmbeddingEngine

    companion object {
        const val EXTRA_RAG_FILE_PATH = "rag_file_path"
        const val EXTRA_RAG_NAME = "rag_name"
        const val EXTRA_IS_ENCRYPTED = "is_encrypted"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val filePath = intent.getStringExtra(EXTRA_RAG_FILE_PATH)
        val password = intent.getStringExtra("rag_password")
        val ragName = intent.getStringExtra(EXTRA_RAG_NAME) ?: "Knowledge Document"
        val isEncrypted = intent.getBooleanExtra(EXTRA_IS_ENCRYPTED, false)

        setContent {
            NeuroVerseTheme {
                if (filePath != null) {
                    RagDataReaderScreen(
                        filePath = filePath,
                        password = password,
                        ragName = ragName,
                        isEncrypted = isEncrypted,
                        embeddingEngine = embeddingEngine,
                        onBackClick = { finish() }
                    )
                } else {
                    ErrorScreen(
                        message = "No RAG knowledge document path provided",
                        onBackClick = { finish() }
                    )
                }
            }
        }
    }
}

enum class RagViewTab(val label: String) {
    GRAPH("Neuron Graph"),
    CHUNKS("Document Chunks"),
    STATS("Statistics")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RagDataReaderScreen(
    filePath: String,
    password: String?,
    ragName: String,
    isEncrypted: Boolean,
    embeddingEngine: EmbeddingEngine,
    onBackClick: () -> Unit
) {
    val haptics = LocalBitHaptics.current
    val context = LocalContext.current

    var loadingState by remember { mutableStateOf<RagLoadingState>(RagLoadingState.Loading) }
    var graph by remember { mutableStateOf<NeuronGraph?>(null) }
    var nodes by remember { mutableStateOf<List<NeuronNode>>(emptyList()) }
    var selectedNode by remember { mutableStateOf<NeuronNode?>(null) }
    var searchQuery by remember { mutableStateOf("") }
    var showPasswordDialog by remember { mutableStateOf(false) }
    var enteredPassword by remember { mutableStateOf(password) }
    var currentTab by remember { mutableStateOf(RagViewTab.GRAPH) }

    val scope = rememberCoroutineScope()

    // Show password dialog if encrypted and no password provided
    LaunchedEffect(isEncrypted, enteredPassword) {
        if (isEncrypted && enteredPassword == null) {
            showPasswordDialog = true
            loadingState = RagLoadingState.Loading
        } else {
            scope.launch {
                loadingState = RagLoadingState.Loading
                try {
                    val loadedGraph = withContext(Dispatchers.IO) {
                        loadRagFile(filePath, enteredPassword, isEncrypted, embeddingEngine)
                    }

                    if (loadedGraph != null) {
                        graph = loadedGraph
                        nodes = loadedGraph.getAllNodes()
                        loadingState = RagLoadingState.Success
                    } else {
                        loadingState = RagLoadingState.Error("Failed to load RAG file. ${if (isEncrypted) "Check your password." else ""}")
                    }
                } catch (e: Exception) {
                    loadingState = RagLoadingState.Error(e.message ?: "Unknown error")
                }
            }
        }
    }

    if (showPasswordDialog) {
        PasswordInputDialog(
            onDismiss = {
                showPasswordDialog = false
                onBackClick()
            },
            onConfirm = { pwd ->
                enteredPassword = pwd
                showPasswordDialog = false
            }
        )
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = ragName,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "${nodes.size} neurons • ${nodes.sumOf { it.edges.size }} synapses",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = {
                        haptics.pop()
                        onBackClick()
                    }) {
                        Icon(
                            Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = "Back",
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        }
    ) { padding ->
        when (val state = loadingState) {
            is RagLoadingState.Loading -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                        Text("Reading neural graph...", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }

            is RagLoadingState.Error -> {
                ErrorScreen(
                    message = state.message,
                    onBackClick = onBackClick
                )
            }

            is RagLoadingState.Success -> {
                val filteredNodes = remember(nodes, searchQuery) {
                    if (searchQuery.isBlank()) nodes
                    else nodes.filter {
                        it.content.contains(searchQuery, ignoreCase = true) ||
                                it.id.contains(searchQuery, ignoreCase = true) ||
                                it.metadata.sourceName.contains(searchQuery, ignoreCase = true)
                    }
                }

                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    // ── Tab Bar Chips ──
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        RagViewTab.values().forEach { tab ->
                            FilterChip(
                                selected = currentTab == tab,
                                onClick = {
                                    haptics.selection()
                                    currentTab = tab
                                },
                                label = { Text(tab.label) },
                                shape = RoundedCornerShape(12.dp)
                            )
                        }
                    }

                    // ── Search Bar ──
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        placeholder = { Text("Search neural chunks & documents...", color = MaterialTheme.colorScheme.outline) },
                        leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                        trailingIcon = {
                            if (searchQuery.isNotEmpty()) {
                                IconButton(onClick = { searchQuery = "" }) {
                                    Icon(Icons.Rounded.Clear, contentDescription = "Clear")
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        shape = RoundedCornerShape(14.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow
                        )
                    )

                    // ── Tab Content ──
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                    ) {
                        when (currentTab) {
                            RagViewTab.GRAPH -> {
                                NeuronGraphCanvas(
                                    nodes = filteredNodes,
                                    selectedNode = selectedNode,
                                    onNodeSelected = { selectedNode = it },
                                    searchQuery = searchQuery,
                                    modifier = Modifier.fillMaxSize()
                                )
                            }

                            RagViewTab.CHUNKS -> {
                                if (filteredNodes.isEmpty()) {
                                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                        Text(
                                            "No document chunks found",
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                } else {
                                    LazyColumn(
                                        modifier = Modifier.fillMaxSize(),
                                        verticalArrangement = Arrangement.spacedBy(10.dp),
                                        contentPadding = PaddingValues(bottom = 24.dp)
                                    ) {
                                        items(filteredNodes, key = { it.id }) { node ->
                                            DocumentChunkCard(
                                                node = node,
                                                isSelected = selectedNode?.id == node.id,
                                                onClick = { selectedNode = node }
                                            )
                                        }
                                    }
                                }
                            }

                            RagViewTab.STATS -> {
                                graph?.let { g ->
                                    GraphStatsView(
                                        graph = g,
                                        nodes = nodes,
                                        ragName = ragName,
                                        filePath = filePath
                                    )
                                }
                            }
                        }
                    }
                }

                // ── Selected Node Detail Modal Sheet ──
                selectedNode?.let { node ->
                    ModalBottomSheet(
                        onDismissRequest = { selectedNode = null },
                        containerColor = MaterialTheme.colorScheme.surfaceContainer,
                        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
                    ) {
                        NodeDetailSheetContent(
                            node = node,
                            allNodes = nodes,
                            onClose = { selectedNode = null },
                            onSelectConnectedNode = { targetId ->
                                val target = nodes.find { it.id == targetId }
                                if (target != null) selectedNode = target
                            }
                        )
                    }
                }
            }
        }
    }
}

// ── Document Chunk Card ──

@Composable
private fun DocumentChunkCard(
    node: NeuronNode,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val haptics = LocalBitHaptics.current

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable {
                haptics.selection()
                onClick()
            },
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
            else MaterialTheme.colorScheme.surfaceContainerLow
        ),
        border = BorderStroke(
            1.dp,
            if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
            else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.primaryContainer,
                        modifier = Modifier.size(28.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = TnIcons.Brain,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                    Text(
                        text = node.metadata.sourceName.ifBlank { "Chunk ${node.id.take(8)}" },
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }

                if (node.edges.isNotEmpty()) {
                    Badge(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                    ) {
                        Text("${node.edges.size} synapses")
                    }
                }
            }

            Text(
                text = node.content,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "ID: ${node.id.take(12)}...",
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.outline
                )
                Text(
                    text = "Tap to inspect",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

// ── Node Detail Sheet Content ──

@Composable
private fun NodeDetailSheetContent(
    node: NeuronNode,
    allNodes: List<NeuronNode>,
    onClose: () -> Unit,
    onSelectConnectedNode: (String) -> Unit
) {
    val context = LocalContext.current
    val haptics = LocalBitHaptics.current

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = node.metadata.sourceName.ifBlank { "Neural Node" },
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "ID: ${node.id}",
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            IconButton(onClick = onClose) {
                Icon(Icons.Rounded.Close, contentDescription = "Close")
            }
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))

        // Content Area Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
        ) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "CHUNK CONTENT",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )

                Text(
                    text = node.content,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )

                Button(
                    onClick = {
                        haptics.pop()
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("Chunk", node.content))
                        Toast.makeText(context, "Copied chunk content", Toast.LENGTH_SHORT).show()
                    },
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.align(Alignment.End)
                ) {
                    Icon(Icons.Rounded.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Copy Text")
                }
            }
        }

        // Synaptic Connections
        if (node.edges.isNotEmpty()) {
            Text(
                text = "SYNAPTIC CONNECTIONS (${node.edges.size})",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )

            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 220.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items(node.edges) { edge ->
                    val targetNode = allNodes.find { it.id == edge.targetId }
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                haptics.selection()
                                onSelectConnectedNode(edge.targetId)
                            }
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = targetNode?.metadata?.sourceName?.ifBlank { "Node ${edge.targetId.take(8)}" } ?: "Node ${edge.targetId.take(8)}",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = targetNode?.content?.take(80) ?: "Target node content",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            Badge(
                                containerColor = MaterialTheme.colorScheme.primaryContainer,
                                contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                            ) {
                                Text("${(edge.weight * 100).toInt()}% sim")
                            }
                        }
                    }
                }
            }
        }
    }
}

// ── Graph Stats View ──

@Composable
private fun GraphStatsView(
    graph: NeuronGraph,
    nodes: List<NeuronNode>,
    ragName: String,
    filePath: String
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(bottom = 24.dp)
    ) {
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = "Neural Graph Architecture",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )

                    StatRow("Embedding Model", graph.getEmbeddingModelName())
                    StatRow("Vector Dimensions", "${graph.getEmbeddingDimension()}D")
                    StatRow("Total Neurons", "${nodes.size}")
                    StatRow("Total Synaptic Edges", "${nodes.sumOf { it.edges.size }}")
                    StatRow("Document Sources", "${nodes.map { it.metadata.sourceName }.distinct().size}")
                    StatRow("Local File", File(filePath).name)
                }
            }
        }
    }
}

@Composable
private fun StatRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
private fun ErrorScreen(
    message: String,
    onBackClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Icon(
                TnIcons.AlertTriangle,
                contentDescription = null,
                modifier = Modifier.size(56.dp),
                tint = MaterialTheme.colorScheme.error
            )
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error
            )
            Button(onClick = onBackClick) {
                Text("Go Back")
            }
        }
    }
}

sealed class RagLoadingState {
    object Loading : RagLoadingState()
    object Success : RagLoadingState()
    data class Error(val message: String) : RagLoadingState()
}

@Composable
fun PasswordInputDialog(
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var password by remember { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Enter Password") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "This RAG is encrypted. Please enter the password to view its contents.",
                    style = MaterialTheme.typography.bodyMedium
                )
                PasswordTextField(
                    value = password,
                    onValueChange = { password = it },
                    modifier = Modifier.fillMaxWidth(),
                    showPasswordState = showPassword,
                    onToggleVisibility = { showPassword = !showPassword }
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { if (password.isNotBlank()) onConfirm(password) },
                enabled = password.isNotBlank()
            ) {
                Text("Unlock")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

private const val MAX_RAG_FILE_SIZE = 512L * 1024 * 1024 // 512 MB

suspend fun loadRagFile(
    filePath: String,
    password: String?,
    isEncrypted: Boolean,
    embeddingEngine: EmbeddingEngine
): NeuronGraph? = withContext(Dispatchers.IO) {
    try {
        val file = File(filePath)
        if (!file.exists() || file.length() > MAX_RAG_FILE_SIZE) {
            return@withContext null
        }

        val graph = NeuronGraph(embeddingEngine, GraphSettings.DEFAULT)

        if (isEncrypted && password != null) {
            val packetManager = NeuronPacketManager()
            packetManager.open(file)
            val authResult = packetManager.authenticate(password)
            if (authResult.isFailure) return@withContext null
            val payloadResult = packetManager.decryptPayload(authResult.getOrThrow())
            if (payloadResult.isFailure) return@withContext null
            graph.deserialize(payloadResult.getOrThrow())
            packetManager.close()
        } else {
            val payload = file.readBytes()
            graph.deserialize(payload)
        }

        graph
    } catch (e: Exception) {
        android.util.Log.e("RagDataReader", "Failed to load RAG: ${e.message}", e)
        null
    }
}
