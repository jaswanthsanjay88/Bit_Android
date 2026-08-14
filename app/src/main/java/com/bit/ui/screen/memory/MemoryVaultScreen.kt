package com.bit.ui.screen.memory

import androidx.activity.ComponentActivity
import androidx.compose.ui.platform.LocalContext
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bit.activity.RagActivity
import com.bit.models.table_schema.MemoryNote
import com.bit.ui.icons.TnIcons
import com.bit.viewmodel.MemoryVaultViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MemoryVaultScreen(
    onBackClick: () -> Unit,
    onNoteClick: (noteId: String?, defaultType: String) -> Unit,
    onNotesListClick: () -> Unit = {},
    onAiMemoryListClick: () -> Unit = {},
    onBackupSettingsClick: () -> Unit = {},
    viewModel: MemoryVaultViewModel = hiltViewModel(LocalContext.current as ComponentActivity)
) {
    val context = LocalContext.current
    val notes by viewModel.filteredNotes.collectAsStateWithLifecycle()
    val allNotes by viewModel.notes.collectAsStateWithLifecycle()
    val searchQuery by viewModel.searchQuery.collectAsStateWithLifecycle()
    val selectedCategory by viewModel.selectedCategory.collectAsStateWithLifecycle()

    var isGlobalMemoryEnabled by remember { mutableStateOf(true) }
    var isSidebarOpen by remember { mutableStateOf(false) }

    val myNotesCount = remember(allNotes) { allNotes.count { it.noteType == "note" || it.folder == "notes" } }
    val aiMemoryCount = remember(allNotes) { allNotes.count { it.noteType == "fact" || it.noteType == "ai_fact" || it.folder == "ai_memory" } }
    val docsCount = remember(allNotes) { allNotes.count { it.noteType == "document" || it.folder == "documents" } }

    val hasSeenImportPrompt by viewModel.hasSeenMemoryImportPrompt.collectAsStateWithLifecycle()
    var showImportDialog by remember { mutableStateOf(false) }

    val docPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: android.net.Uri? ->
        uri?.let {
            viewModel.importDocumentFromUri(it) { success ->
                if (success) {
                    android.widget.Toast.makeText(context, "Document added to Vault", android.widget.Toast.LENGTH_SHORT).show()
                } else {
                    android.widget.Toast.makeText(context, "Failed to parse document", android.widget.Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
    
    LaunchedEffect(hasSeenImportPrompt) {
        if (!hasSeenImportPrompt) {
            showImportDialog = true
        }
    }

    if (showImportDialog) {
        val importViewModel = hiltViewModel<com.bit.viewmodel.MemoryImportViewModel>(LocalContext.current as ComponentActivity)
        
        LaunchedEffect(Unit) {
            importViewModel.reset()
        }
        
        val importStep by importViewModel.currentStep.collectAsStateWithLifecycle()
        LaunchedEffect(importStep) {
            if (importStep == com.bit.viewmodel.ImportStep.SUCCESS) {
                viewModel.refreshNotesFromDisk()
            }
        }

        MemoryImportSheets(
            viewModel = importViewModel,
            onDismiss = {
                showImportDialog = false
                viewModel.markMemoryImportPromptSeen()
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Memory vault",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(TnIcons.ArrowLeft, contentDescription = "Back", tint = MaterialTheme.colorScheme.onSurface)
                    }
                },
                actions = {
                    // Backup Pill Button
                    OutlinedButton(
                        onClick = { onBackupSettingsClick() },
                        shape = RoundedCornerShape(20.dp),
                        modifier = Modifier.height(36.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp)
                    ) {
                        Icon(TnIcons.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Backup", style = MaterialTheme.typography.labelMedium)
                    }

                    Spacer(Modifier.width(8.dp))

                    // Global Memory Toggle Switch
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier.padding(end = 4.dp)
                    ) {
                        Text(
                            text = "Memory",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Switch(
                            checked = isGlobalMemoryEnabled,
                            onCheckedChange = { isGlobalMemoryEnabled = it }
                        )
                    }

                    var showMenu by remember { mutableStateOf(false) }
                    Box {
                        IconButton(onClick = { showMenu = true }) {
                            Icon(TnIcons.DotsVertical, contentDescription = "More options", tint = MaterialTheme.colorScheme.onSurface)
                        }
                        DropdownMenu(
                            expanded = showMenu,
                            onDismissRequest = { showMenu = false },
                            modifier = Modifier.background(MaterialTheme.colorScheme.surfaceContainer)
                        ) {
                            DropdownMenuItem(
                                text = { Text("Import from another AI", color = MaterialTheme.colorScheme.onSurface) },
                                onClick = {
                                    showMenu = false
                                    showImportDialog = true
                                }
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { paddingValues ->
        // Main Vault Body Area
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
                // Memory Off subtle inline notice (spec §5)
                if (!isGlobalMemoryEnabled) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHighest,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = "Memory is off. AI will not read from or write to vault content during chat.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
                        )
                    }
                }

                // Search Input (Search notes, memories, docs)
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { viewModel.setSearchQuery(it) },
                    placeholder = { Text("Search notes, memories, docs", color = MaterialTheme.colorScheme.outline) },
                    leadingIcon = { Icon(TnIcons.Search, contentDescription = null, tint = MaterialTheme.colorScheme.outline) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp)),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent
                    ),
                    singleLine = true
                )

                // Top Peer Cards (My notes & AI memory)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // My Notes Card
                    Card(
                        modifier = Modifier
                            .weight(1f)
                            .clickable { viewModel.setSelectedCategory(if (selectedCategory == "notes") "all" else "notes") },
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = if (selectedCategory == "notes") MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow
                        )
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(TnIcons.Edit, contentDescription = null, tint = if (selectedCategory == "notes") MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(24.dp))
                            Text("My notes", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = if (selectedCategory == "notes") MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface)
                            Text("$myNotesCount files", style = MaterialTheme.typography.bodySmall, color = if (selectedCategory == "notes") MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f) else MaterialTheme.colorScheme.outline)
                        }
                    }

                    // AI Memory Card
                    Card(
                        modifier = Modifier
                            .weight(1f)
                            .clickable { viewModel.setSelectedCategory(if (selectedCategory == "facts") "all" else "facts") },
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = if (selectedCategory == "facts") MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow
                        )
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(TnIcons.Brain, contentDescription = null, tint = if (selectedCategory == "facts") MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(24.dp))
                            Text("AI memory", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = if (selectedCategory == "facts") MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface)
                            Text("$aiMemoryCount facts", style = MaterialTheme.typography.bodySmall, color = if (selectedCategory == "facts") MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f) else MaterialTheme.colorScheme.outline)
                        }
                    }
                }

                // Wide Documents Card
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { viewModel.setSelectedCategory(if (selectedCategory == "documents") "all" else "documents") },
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = if (selectedCategory == "documents") MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow
                    )
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(TnIcons.Folder, contentDescription = null, tint = if (selectedCategory == "documents") MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(24.dp))
                            Column {
                                Text("Documents", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = if (selectedCategory == "documents") MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface)
                                Text("$docsCount files stored in vault", style = MaterialTheme.typography.bodySmall, color = if (selectedCategory == "documents") MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f) else MaterialTheme.colorScheme.outline)
                            }
                        }

                        Button(
                            onClick = { docPickerLauncher.launch("*/*") },
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (selectedCategory == "documents") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHighest,
                                contentColor = if (selectedCategory == "documents") MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
                            ),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Icon(TnIcons.Plus, contentDescription = "Add Document", modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Add", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }

                // Recent Section Header + New Note/Doc Button
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (selectedCategory == "all") "Recent" else selectedCategory.replaceFirstChar { it.uppercase() },
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )

                    if (selectedCategory == "documents") {
                        Button(
                            onClick = { docPickerLauncher.launch("*/*") },
                            shape = RoundedCornerShape(20.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary)
                        ) {
                            Icon(TnIcons.Plus, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Import doc", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                        }
                    } else {
                        Button(
                            onClick = { onNoteClick(null, if (selectedCategory == "facts") "fact" else "note") },
                            shape = RoundedCornerShape(20.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest, contentColor = MaterialTheme.colorScheme.onSurface)
                        ) {
                            Icon(TnIcons.Plus, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("+ New note", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                        }
                    }
                }

                // Recent List Items with Provenance Type Icons
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    contentPadding = PaddingValues(bottom = 24.dp)
                ) {
                    items(notes, key = { it.id }) { note ->
                        RecentItemCard(
                            note = note,
                            onClick = { onNoteClick(note.id, note.noteType) }
                        )
                    }
                }
            }
        }
    }

@Composable
private fun RecentItemCard(
    note: MemoryNote,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() },
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Provenance Type Icon
            val icon = when (note.noteType) {
                "task" -> TnIcons.Check
                "ai_fact" -> TnIcons.Brain
                "document" -> TnIcons.Folder
                else -> TnIcons.Edit
            }

            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(22.dp)
            )

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = note.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                val subtitle = when (note.noteType) {
                    "task" -> "Task · ${note.status}"
                    "ai_fact" -> "AI saved · from chat"
                    "document" -> "Document · attached file"
                    else -> "Note · ${note.folder}"
                }

                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline
                )
            }
        }
    }
}


