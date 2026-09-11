package com.bit.activity

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Clear
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bit.global.formatDateOnly
import com.bit.neuron_example.EdgeType
import com.bit.neuron_example.GraphStats
import com.bit.neuron_example.NeuronNode
import com.bit.ui.components.ActionButton
import com.bit.ui.components.ActionTextButton
import com.bit.ui.components.NeuronGraphCanvas
import com.bit.ui.components.PasswordTextField
import com.bit.ui.icons.TnIcons
import com.bit.ui.screen.rag.SecureRagCreationScreen
import com.bit.ui.theme.LocalBitHaptics
import com.bit.ui.theme.NeuroVerseTheme
import com.bit.viewmodel.KnowledgeCategory
import com.bit.viewmodel.KnowledgeItemType
import com.bit.viewmodel.KnowledgeSourceItem
import com.bit.viewmodel.RagViewModel
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import java.io.File

@AndroidEntryPoint
class RagActivity : ComponentActivity() {

    companion object {
        const val EXTRA_INITIAL_TAB = "initial_tab"
        const val TAB_SOURCES = 0
        const val TAB_GRAPH = 1
        const val TAB_CHUNKS = 2
        const val TAB_STATS = 3
        const val TAB_CREATE = 4
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val initialTab = intent.getIntExtra(EXTRA_INITIAL_TAB, TAB_SOURCES)

        setContent {
            NeuroVerseTheme {
                RagScreen(
                    initialTab = initialTab,
                    onClose = { finish() }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun RagScreen(
    initialTab: Int = RagActivity.TAB_SOURCES,
    ragViewModel: RagViewModel = hiltViewModel(),
    onClose: () -> Unit
) {
    val context = LocalContext.current
    val haptics = LocalBitHaptics.current
    val scope = rememberCoroutineScope()

    var selectedTab by remember { mutableIntStateOf(initialTab) }
    var selectedCategory by remember { mutableStateOf(KnowledgeCategory.ALL) }
    var searchQuery by remember { mutableStateOf("") }
    var selectedSourceFilter by remember { mutableStateOf<String?>(null) }
    var selectedNode by remember { mutableStateOf<NeuronNode?>(null) }
    var itemToDelete by remember { mutableStateOf<KnowledgeSourceItem?>(null) }

    // Password Dialog State for Encrypted RAGs
    var showPasswordDialog by remember { mutableStateOf(false) }
    var pendingPasswordRagId by remember { mutableStateOf<String?>(null) }

    // Observe ViewModel flows
    val unifiedSources by ragViewModel.unifiedSources.collectAsStateWithLifecycle()
    val graphNodes by ragViewModel.graphNodes.collectAsStateWithLifecycle()
    val graphStats by ragViewModel.graphStats.collectAsStateWithLifecycle()
    val isLoading by ragViewModel.isLoading.collectAsStateWithLifecycle()
    val error by ragViewModel.error.collectAsStateWithLifecycle()

    // Multi-format SAF document & package picker
    val knowledgeFilePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: Exception) {}
            ragViewModel.importKnowledgeFile(uri)
            Toast.makeText(context, "Importing knowledge document...", Toast.LENGTH_SHORT).show()
        }
    }

    val supportedMimeTypes = remember {
        arrayOf(
            "application/pdf",
            "text/plain",
            "text/markdown",
            "text/csv",
            "text/html",
            "application/json",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "application/octet-stream",
            "application/x-neuron",
            "*/*"
        )
    }

    // Auto-initialize embedding engine when Create tab is selected
    LaunchedEffect(selectedTab) {
        if (selectedTab == RagActivity.TAB_CREATE && !ragViewModel.isEmbeddingReady) {
            ragViewModel.initializeEmbeddingFromFiles()
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = "Knowledge & Neural Graph",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        val activeCount = unifiedSources.count { it.isEnabled }
                        Text(
                            text = when (selectedTab) {
                                RagActivity.TAB_SOURCES -> "$activeCount active / ${unifiedSources.size} sources"
                                RagActivity.TAB_GRAPH -> "${graphNodes.size} neurons • ${graphNodes.sumOf { it.edges.size }} synapses"
                                RagActivity.TAB_CHUNKS -> "${graphNodes.size} chunks extracted"
                                RagActivity.TAB_STATS -> "Architecture & Metrics"
                                else -> "Secure Package Creation"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                },
                navigationIcon = {
                    ActionTextButton(
                        onClickListener = {
                            haptics.pop()
                            onClose()
                        },
                        icon = TnIcons.ChevronLeft,
                        text = "Back",
                        contentDescription = "Back",
                        shape = RoundedCornerShape(12.dp)
                    )
                },
                actions = {
                    // Add Document / Package Button
                    ActionButton(
                        onClickListener = {
                            haptics.pop()
                            knowledgeFilePicker.launch(supportedMimeTypes)
                        },
                        icon = TnIcons.Plus,
                        contentDescription = "Add Document or Package",
                        shape = RoundedCornerShape(12.dp)
                    )
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // ── Primary Navigation Tabs (Zero Jumping) ──
            SecondaryTabRow(
                selectedTabIndex = selectedTab,
                containerColor = MaterialTheme.colorScheme.background,
                contentColor = MaterialTheme.colorScheme.primary
            ) {
                Tab(
                    selected = selectedTab == RagActivity.TAB_SOURCES,
                    onClick = {
                        haptics.selection()
                        selectedTab = RagActivity.TAB_SOURCES
                    },
                    text = { Text("Sources (${unifiedSources.size})") }
                )
                Tab(
                    selected = selectedTab == RagActivity.TAB_GRAPH,
                    onClick = {
                        haptics.selection()
                        selectedTab = RagActivity.TAB_GRAPH
                    },
                    text = { Text("Graph") }
                )
                Tab(
                    selected = selectedTab == RagActivity.TAB_CHUNKS,
                    onClick = {
                        haptics.selection()
                        selectedTab = RagActivity.TAB_CHUNKS
                    },
                    text = { Text("Chunks") }
                )
                Tab(
                    selected = selectedTab == RagActivity.TAB_STATS,
                    onClick = {
                        haptics.selection()
                        selectedTab = RagActivity.TAB_STATS
                    },
                    text = { Text("Stats") }
                )
                Tab(
                    selected = selectedTab == RagActivity.TAB_CREATE,
                    onClick = {
                        haptics.selection()
                        selectedTab = RagActivity.TAB_CREATE
                    },
                    text = { Text("Create") }
                )
            }

            // Loading indicator
            AnimatedVisibility(visible = isLoading) {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.primary
                )
            }

            // Error banner
            error?.let { errorMsg ->
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    color = MaterialTheme.colorScheme.errorContainer,
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = errorMsg,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(onClick = { ragViewModel.clearError() }) {
                            Icon(TnIcons.X, contentDescription = "Dismiss", tint = MaterialTheme.colorScheme.onErrorContainer)
                        }
                    }
                }
            }

            // ── Main Tab Content ──
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                when (selectedTab) {
                    RagActivity.TAB_SOURCES -> {
                        SourcesTabContent(
                            sources = unifiedSources,
                            selectedCategory = selectedCategory,
                            onSelectCategory = { selectedCategory = it },
                            searchQuery = searchQuery,
                            onSearchQueryChange = { searchQuery = it },
                            onToggleEnabled = { source, isEnabled ->
                                if (source.isEncrypted && isEnabled) {
                                    pendingPasswordRagId = source.id
                                    showPasswordDialog = true
                                } else {
                                    ragViewModel.toggleSourceEnabled(source, isEnabled)
                                }
                            },
                            onViewInGraph = { source ->
                                selectedSourceFilter = source.id
                                selectedTab = RagActivity.TAB_GRAPH
                            },
                            onViewChunks = { source ->
                                selectedSourceFilter = source.id
                                selectedTab = RagActivity.TAB_CHUNKS
                            },
                            onDelete = { source ->
                                itemToDelete = source
                            },
                            onAddClick = {
                                knowledgeFilePicker.launch(supportedMimeTypes)
                            }
                        )
                    }

                    RagActivity.TAB_GRAPH -> {
                        val filteredNodes = remember(graphNodes, selectedSourceFilter, searchQuery) {
                            var list = graphNodes
                            if (!selectedSourceFilter.isNullOrBlank()) {
                                list = list.filter { it.metadata.sourceId == selectedSourceFilter }
                            }
                            list
                        }

                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            // Subgraph filter chips
                            SourceFilterChipRow(
                                sources = unifiedSources,
                                selectedSourceId = selectedSourceFilter,
                                onSelectSource = { selectedSourceFilter = it }
                            )

                            // Search bar for canvas highlighting
                            OutlinedTextField(
                                value = searchQuery,
                                onValueChange = { searchQuery = it },
                                placeholder = { Text("Highlight nodes in graph...", color = MaterialTheme.colorScheme.outline) },
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

                            if (filteredNodes.isEmpty()) {
                                Box(
                                    modifier = Modifier.fillMaxSize(),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Column(
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        verticalArrangement = Arrangement.spacedBy(12.dp)
                                    ) {
                                        Icon(TnIcons.Brain, contentDescription = null, modifier = Modifier.size(56.dp), tint = MaterialTheme.colorScheme.outline)
                                        Text(
                                            text = if (selectedSourceFilter != null) "No nodes found for this source" else "No knowledge nodes in graph",
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        Button(onClick = { knowledgeFilePicker.launch(supportedMimeTypes) }) {
                                            Icon(TnIcons.Plus, contentDescription = null, modifier = Modifier.size(16.dp))
                                            Spacer(Modifier.width(6.dp))
                                            Text("Upload Document to Graph")
                                        }
                                    }
                                }
                            } else {
                                NeuronGraphCanvas(
                                    nodes = filteredNodes,
                                    selectedNode = selectedNode,
                                    onNodeSelected = { selectedNode = it },
                                    searchQuery = searchQuery,
                                    modifier = Modifier.fillMaxSize()
                                )
                            }
                        }
                    }

                    RagActivity.TAB_CHUNKS -> {
                        val filteredChunks = remember(graphNodes, selectedSourceFilter, searchQuery) {
                            var list = graphNodes
                            if (!selectedSourceFilter.isNullOrBlank()) {
                                list = list.filter { it.metadata.sourceId == selectedSourceFilter }
                            }
                            if (searchQuery.isNotBlank()) {
                                list = list.filter {
                                    it.content.contains(searchQuery, ignoreCase = true) ||
                                            it.metadata.sourceName.contains(searchQuery, ignoreCase = true) ||
                                            it.id.contains(searchQuery, ignoreCase = true)
                                }
                            }
                            list
                        }

                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            // Subgraph filter chips
                            SourceFilterChipRow(
                                sources = unifiedSources,
                                selectedSourceId = selectedSourceFilter,
                                onSelectSource = { selectedSourceFilter = it }
                            )

                            // Search bar
                            OutlinedTextField(
                                value = searchQuery,
                                onValueChange = { searchQuery = it },
                                placeholder = { Text("Search extracted chunks...", color = MaterialTheme.colorScheme.outline) },
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

                            if (filteredChunks.isEmpty()) {
                                Box(
                                    modifier = Modifier.fillMaxSize(),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = "No document chunks found",
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
                                    items(filteredChunks, key = { it.id }) { node ->
                                        DocumentChunkCard(
                                            node = node,
                                            isSelected = selectedNode?.id == node.id,
                                            onClick = { selectedNode = node }
                                        )
                                    }
                                }
                            }
                        }
                    }

                    RagActivity.TAB_STATS -> {
                        StatisticsTabContent(
                            graphStats = graphStats,
                            graphNodes = graphNodes,
                            sources = unifiedSources,
                            embeddingStatus = ragViewModel.embeddingStatus.collectAsStateWithLifecycle().value
                        )
                    }

                    RagActivity.TAB_CREATE -> {
                        SecureRagCreationScreen(
                            ragViewModel = ragViewModel,
                            padding = PaddingValues(0.dp),
                            onRagCreated = {
                                selectedTab = RagActivity.TAB_SOURCES
                            }
                        )
                    }
                }
            }
        }
    }

    // ── Node Detail Modal Sheet ──
    selectedNode?.let { node ->
        ModalBottomSheet(
            onDismissRequest = { selectedNode = null },
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
            shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
        ) {
            NodeDetailSheetContent(
                node = node,
                allNodes = graphNodes,
                onClose = { selectedNode = null },
                onSelectConnectedNode = { targetId ->
                    val target = graphNodes.find { it.id == targetId }
                    if (target != null) selectedNode = target
                }
            )
        }
    }

    // ── Delete Confirmation Dialog ──
    itemToDelete?.let { item ->
        AlertDialog(
            onDismissRequest = { itemToDelete = null },
            title = { Text("Delete Knowledge Source?") },
            text = {
                Text(
                    text = "Are you sure you want to delete '${item.name}'? This will remove all ${item.chunkCount} neural nodes and synapses from the knowledge graph and delete the file from the vault.",
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        ragViewModel.deleteSource(item)
                        itemToDelete = null
                        Toast.makeText(context, "Deleted '${item.name}'", Toast.LENGTH_SHORT).show()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { itemToDelete = null }) {
                    Text("Cancel")
                }
            }
        )
    }

    // ── Password Dialog for Encrypted Packages ──
    if (showPasswordDialog && pendingPasswordRagId != null) {
        PasswordDialog(
            onDismiss = {
                showPasswordDialog = false
                pendingPasswordRagId = null
            },
            onConfirm = { password ->
                pendingPasswordRagId?.let { id ->
                    ragViewModel.loadRag(id, password)
                }
                showPasswordDialog = false
                pendingPasswordRagId = null
            }
        )
    }
}

// ── Tab 0: Sources Content ──

@Composable
private fun SourcesTabContent(
    sources: List<KnowledgeSourceItem>,
    selectedCategory: KnowledgeCategory,
    onSelectCategory: (KnowledgeCategory) -> Unit,
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    onToggleEnabled: (KnowledgeSourceItem, Boolean) -> Unit,
    onViewInGraph: (KnowledgeSourceItem) -> Unit,
    onViewChunks: (KnowledgeSourceItem) -> Unit,
    onDelete: (KnowledgeSourceItem) -> Unit,
    onAddClick: () -> Unit
) {
    val haptics = LocalBitHaptics.current

    val filteredSources = remember(sources, selectedCategory, searchQuery) {
        sources.filter { item ->
            val matchesCategory = when (selectedCategory) {
                KnowledgeCategory.ALL -> true
                else -> item.category == selectedCategory
            }
            val matchesSearch = searchQuery.isBlank() ||
                    item.name.contains(searchQuery, ignoreCase = true) ||
                    item.description.contains(searchQuery, ignoreCase = true)
            matchesCategory && matchesSearch
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // ── Category Chips ──
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            KnowledgeCategory.values().forEach { cat ->
                val count = when (cat) {
                    KnowledgeCategory.ALL -> sources.size
                    else -> sources.count { it.category == cat }
                }
                FilterChip(
                    selected = selectedCategory == cat,
                    onClick = {
                        haptics.selection()
                        onSelectCategory(cat)
                    },
                    label = { Text("${cat.label} ($count)") },
                    shape = RoundedCornerShape(12.dp)
                )
            }
        }

        // ── Search Bar ──
        OutlinedTextField(
            value = searchQuery,
            onValueChange = onSearchQueryChange,
            placeholder = { Text("Search sources, documents, notes...", color = MaterialTheme.colorScheme.outline) },
            leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
            trailingIcon = {
                if (searchQuery.isNotEmpty()) {
                    IconButton(onClick = { onSearchQueryChange("") }) {
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

        // ── List of Knowledge Sources ──
        if (filteredSources.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Icon(TnIcons.Folder, contentDescription = null, modifier = Modifier.size(56.dp), tint = MaterialTheme.colorScheme.outline)
                    Text(
                        text = if (searchQuery.isNotBlank()) "No matching sources found" else "No knowledge documents added yet",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Button(onClick = onAddClick) {
                        Icon(TnIcons.Plus, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Add Document (PDF, Word, Text)")
                    }
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(bottom = 24.dp)
            ) {
                items(filteredSources, key = { it.id }) { source ->
                    KnowledgeSourceCard(
                        source = source,
                        onToggleEnabled = { onToggleEnabled(source, it) },
                        onViewInGraph = { onViewInGraph(source) },
                        onViewChunks = { onViewChunks(source) },
                        onDelete = { onDelete(source) }
                    )
                }
            }
        }
    }
}

// ── Knowledge Source Card ──

@Composable
private fun KnowledgeSourceCard(
    source: KnowledgeSourceItem,
    onToggleEnabled: (Boolean) -> Unit,
    onViewInGraph: () -> Unit,
    onViewChunks: () -> Unit,
    onDelete: () -> Unit
) {
    val haptics = LocalBitHaptics.current

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (source.isEnabled) MaterialTheme.colorScheme.surfaceContainerLow
            else MaterialTheme.colorScheme.surfaceContainerLowest
        ),
        border = BorderStroke(
            1.dp,
            if (source.isEnabled) MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)
            else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f)
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Header Row: Format Icon + Title + Switch (Zero jumping!)
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
                        color = when (source.type) {
                            KnowledgeItemType.PDF -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.6f)
                            KnowledgeItemType.DOCX -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f)
                            KnowledgeItemType.NEURON_PACKAGE -> MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.6f)
                            else -> MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.6f)
                        },
                        modifier = Modifier.size(36.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = when (source.type) {
                                    KnowledgeItemType.PDF -> TnIcons.FileText
                                    KnowledgeItemType.DOCX -> TnIcons.FileText
                                    KnowledgeItemType.NEURON_PACKAGE -> TnIcons.BrainCircuit
                                    else -> TnIcons.FileText
                                },
                                contentDescription = null,
                                modifier = Modifier.size(20.dp),
                                tint = when (source.type) {
                                    KnowledgeItemType.PDF -> MaterialTheme.colorScheme.error
                                    KnowledgeItemType.DOCX -> MaterialTheme.colorScheme.primary
                                    KnowledgeItemType.NEURON_PACKAGE -> MaterialTheme.colorScheme.tertiary
                                    else -> MaterialTheme.colorScheme.secondary
                                }
                            )
                        }
                    }

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = source.name,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "${source.category.label} • ${source.chunkCount} chunks • ${source.getFormattedSize()}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                // Inline Active Switch
                Switch(
                    checked = source.isEnabled,
                    onCheckedChange = {
                        haptics.selection()
                        onToggleEnabled(it)
                    },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = MaterialTheme.colorScheme.primary,
                        checkedTrackColor = MaterialTheme.colorScheme.primaryContainer
                    )
                )
            }

            if (source.description.isNotBlank()) {
                Text(
                    text = source.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f))

            // Action Buttons Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilledTonalButton(
                        onClick = {
                            haptics.pop()
                            onViewInGraph()
                        },
                        shape = RoundedCornerShape(10.dp),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                        modifier = Modifier.height(32.dp)
                    ) {
                        Icon(TnIcons.Brain, contentDescription = null, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("View Graph", style = MaterialTheme.typography.labelSmall)
                    }

                    OutlinedButton(
                        onClick = {
                            haptics.pop()
                            onViewChunks()
                        },
                        shape = RoundedCornerShape(10.dp),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                        modifier = Modifier.height(32.dp)
                    ) {
                        Icon(TnIcons.FileText, contentDescription = null, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Chunks", style = MaterialTheme.typography.labelSmall)
                    }
                }

                IconButton(
                    onClick = {
                        haptics.pop()
                        onDelete()
                    },
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        imageVector = TnIcons.Trash,
                        contentDescription = "Delete",
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }
    }
}

// ── Subgraph Filter Chip Row ──

@Composable
private fun SourceFilterChipRow(
    sources: List<KnowledgeSourceItem>,
    selectedSourceId: String?,
    onSelectSource: (String?) -> Unit
) {
    val haptics = LocalBitHaptics.current

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        FilterChip(
            selected = selectedSourceId == null,
            onClick = {
                haptics.selection()
                onSelectSource(null)
            },
            label = { Text("All Knowledge (${sources.size})") },
            shape = RoundedCornerShape(12.dp)
        )

        sources.forEach { src ->
            FilterChip(
                selected = selectedSourceId == src.id,
                onClick = {
                    haptics.selection()
                    onSelectSource(if (selectedSourceId == src.id) null else src.id)
                },
                label = {
                    Text(
                        text = src.name.take(20),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                shape = RoundedCornerShape(12.dp)
            )
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
    val context = LocalContext.current

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
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.weight(1f)
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
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = node.metadata.chunkTitle.ifBlank { "Chunk #${node.metadata.position + 1}" },
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (node.metadata.sourceName.isNotBlank()) {
                            Text(
                                text = "${node.metadata.sourceName} • #${node.metadata.position + 1}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.outline,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
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

            if (node.metadata.entities.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    node.metadata.entities.take(4).forEach { entity ->
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = Color(0xFF06B6D4).copy(alpha = 0.12f),
                            border = BorderStroke(1.dp, Color(0xFF06B6D4).copy(alpha = 0.35f))
                        ) {
                            Text(
                                text = entity,
                                style = MaterialTheme.typography.labelSmall,
                                color = Color(0xFF0891B2),
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                    if (node.metadata.entities.size > 4) {
                        Text(
                            text = "+${node.metadata.entities.size - 4} more",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline,
                            modifier = Modifier.align(Alignment.CenterVertically)
                        )
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "ID: ${node.id.take(10)}...",
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.outline
                )

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(
                        onClick = {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            clipboard.setPrimaryClip(ClipData.newPlainText("Chunk", node.content))
                            Toast.makeText(context, "Copied chunk", Toast.LENGTH_SHORT).show()
                        },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                        modifier = Modifier.height(28.dp)
                    ) {
                        Icon(Icons.Rounded.ContentCopy, contentDescription = null, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Copy", style = MaterialTheme.typography.labelSmall)
                    }

                    Text(
                        text = "Inspect ➔",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.align(Alignment.CenterVertically)
                    )
                }
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
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = node.metadata.chunkTitle.ifBlank { node.metadata.sourceName.ifBlank { "Neural Node" } },
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "${node.metadata.sourceName.ifBlank { "Document" }} • Chunk #${node.metadata.position + 1}",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = "ID: ${node.id.take(16)}...",
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    IconButton(
                        onClick = {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            clipboard.setPrimaryClip(ClipData.newPlainText("Node ID", node.id))
                            Toast.makeText(context, "Copied Node ID", Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.size(20.dp)
                    ) {
                        Icon(
                            TnIcons.Copy,
                            contentDescription = "Copy ID",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(12.dp)
                        )
                    }
                }
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
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            TnIcons.FileText,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(14.dp)
                        )
                        Text(
                            text = "CHUNK CONTENT",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    Text(
                        text = "${node.content.length} chars • ~${(node.content.length / 4).coerceAtLeast(1)} tokens",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    )
                }

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
                    Icon(TnIcons.Copy, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Copy Text")
                }
            }
        }

        // Extracted Entities & Conceptual Tags
        if (node.metadata.entities.isNotEmpty()) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                border = BorderStroke(1.dp, Color(0xFF06B6D4).copy(alpha = 0.35f))
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            TnIcons.Tag,
                            contentDescription = null,
                            tint = Color(0xFF0891B2),
                            modifier = Modifier.size(14.dp)
                        )
                        Text(
                            text = "EXTRACTED ENTITIES & CONCEPTS",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF0891B2)
                        )
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        node.metadata.entities.forEach { entity ->
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = Color(0xFF06B6D4).copy(alpha = 0.12f),
                                border = BorderStroke(1.dp, Color(0xFF06B6D4).copy(alpha = 0.4f))
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Icon(
                                        TnIcons.Tag,
                                        contentDescription = null,
                                        tint = Color(0xFF0891B2),
                                        modifier = Modifier.size(11.dp)
                                    )
                                    Text(
                                        text = entity,
                                        style = MaterialTheme.typography.bodySmall,
                                        fontWeight = FontWeight.Medium,
                                        color = Color(0xFF0891B2)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // Synaptic Connections
        if (node.edges.isNotEmpty()) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(
                    TnIcons.BrainCircuit,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(15.dp)
                )
                Text(
                    text = "SYNAPTIC CONNECTIONS (${node.edges.size})",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                node.edges.forEach { edge ->
                    val targetNode = allNodes.find { it.id == edge.targetId }
                    val isCrossDoc = targetNode != null && targetNode.metadata.sourceId.isNotBlank() && targetNode.metadata.sourceId != node.metadata.sourceId
                    val (badgeContainer, badgeContent, edgeTypeLabel) = when (edge.type) {
                        EdgeType.ENTITY -> Triple(Color(0xFF06B6D4).copy(alpha = 0.18f), Color(0xFF0891B2), "Entity Bridge")
                        EdgeType.SEMANTIC -> Triple(MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.onPrimaryContainer, "Semantic")
                        EdgeType.SEQUENTIAL -> Triple(MaterialTheme.colorScheme.surfaceContainerHighest, MaterialTheme.colorScheme.onSurfaceVariant, "Sequential")
                        EdgeType.EXPLICIT -> Triple(Color(0xFF10B981).copy(alpha = 0.18f), Color(0xFF059669), "Explicit")
                    }
                    val edgeIcon = when (edge.type) {
                        EdgeType.ENTITY -> TnIcons.Tag
                        EdgeType.SEMANTIC -> TnIcons.Sparkles
                        EdgeType.SEQUENTIAL -> TnIcons.ArrowRight
                        EdgeType.EXPLICIT -> TnIcons.Link
                    }

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
                                val targetTitle = targetNode?.metadata?.chunkTitle?.ifBlank {
                                    targetNode.metadata.sourceName.ifBlank { "Node ${edge.targetId.take(8)}" }
                                } ?: "Node ${edge.targetId.take(8)}"

                                Text(
                                    text = targetTitle,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )

                                if (isCrossDoc && targetNode != null) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        Icon(
                                            TnIcons.GitBranch,
                                            contentDescription = null,
                                            tint = Color(0xFF0891B2),
                                            modifier = Modifier.size(12.dp)
                                        )
                                        Text(
                                            text = "Cross-Doc: ${targetNode.metadata.sourceName} • ${targetNode.content.take(50)}",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = Color(0xFF0891B2),
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                } else {
                                    Text(
                                        text = targetNode?.content?.take(80) ?: "Target node content",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }

                            Spacer(Modifier.width(8.dp))

                            Badge(
                                containerColor = badgeContainer,
                                contentColor = badgeContent
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                                ) {
                                    Icon(
                                        edgeIcon,
                                        contentDescription = null,
                                        tint = badgeContent,
                                        modifier = Modifier.size(11.dp)
                                    )
                                    val pct = (edge.weight * 100).toInt()
                                    Text(if (edge.type == EdgeType.SEQUENTIAL) "Flow" else "$edgeTypeLabel • $pct%")
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// ── Tab 3: Statistics Tab Content ──

@Composable
private fun StatisticsTabContent(
    graphStats: GraphStats?,
    graphNodes: List<NeuronNode>,
    sources: List<KnowledgeSourceItem>,
    embeddingStatus: String
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
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
                        text = "Unified Knowledge Architecture",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )

                    StatRow("Embedding Engine", embeddingStatus)
                    StatRow("Vector Dimensions", "384D (all-MiniLM-L6-v2)")
                    StatRow("Total Neurons (Chunks)", "${graphNodes.size}")
                    StatRow("Total Synaptic Connections", "${graphNodes.sumOf { it.edges.size }}")
                    val entitySynapses = graphStats?.entityEdgeCount ?: (graphNodes.flatMap { it.edges }.count { it.type == EdgeType.ENTITY } / 2)
                    StatRow("Entity Synapses", "$entitySynapses")
                    val crossDocBridges = graphStats?.crossDocumentEdgeCount ?: (graphNodes.sumOf { node ->
                        node.edges.count { edge ->
                            val target = graphNodes.find { it.id == edge.targetId }
                            target != null && target.metadata.sourceId.isNotBlank() && target.metadata.sourceId != node.metadata.sourceId
                        }
                    } / 2)
                    StatRow("Cross-Document Bridges", "$crossDocBridges")
                    StatRow("Active Knowledge Sources", "${sources.count { it.isEnabled }} / ${sources.size}")
                    StatRow("Documents in Vault", "${sources.count { it.category == KnowledgeCategory.DOCUMENTS }}")
                    StatRow("Notes & Facts", "${sources.count { it.category == KnowledgeCategory.NOTES || it.category == KnowledgeCategory.AI_MEMORY }}")
                    StatRow("RAG Packages", "${sources.count { it.category == KnowledgeCategory.PACKAGES }}")
                    val avgSynapses = if (graphNodes.isNotEmpty()) {
                        String.format("%.2f", graphNodes.sumOf { it.edges.size }.toFloat() / graphNodes.size)
                    } else "0.00"
                    StatRow("Synaptic Density", "$avgSynapses edges / node")
                    val memoryEst = (graphNodes.size * 384 * 4L) / 1024
                    StatRow("Vector Memory Footprint", "~$memoryEst KB")
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
fun PasswordDialog(
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
                    "This RAG package is encrypted. Please enter the password to unlock.",
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
