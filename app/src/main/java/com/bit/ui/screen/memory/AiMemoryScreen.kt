package com.bit.ui.screen.memory

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bit.di.AppContainer
import com.bit.global.Standards
import com.bit.models.table_schema.AiMemory
import com.bit.models.table_schema.MemoryCategory
import com.bit.ui.components.GlassCard
import com.bit.ui.icons.TnIcons
import com.bit.ui.theme.Glass
import com.bit.ui.theme.LocalBitHaptics
import com.bit.worker.MemoryExtractor
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiMemoryScreen(
    onNavigateBack: () -> Unit
) {
    val memoryRepo = remember { AppContainer.getMemoryRepo() }
    val memoryExtractor = remember { MemoryExtractor(memoryRepo) }
    val allMemories by memoryRepo.getAll().collectAsStateWithLifecycle(initialValue = emptyList())
    val scope = rememberCoroutineScope()
    val haptics = LocalBitHaptics.current

    var searchQuery by remember { mutableStateOf("") }
    var selectedCategory by remember { mutableStateOf<MemoryCategory?>(null) }
    var showClearAllDialog by remember { mutableStateOf(false) }
    var showClearStaleDialog by remember { mutableStateOf(false) }

    val filteredMemories = remember(allMemories, searchQuery, selectedCategory) {
        allMemories.filter { memory ->
            val matchesSearch = searchQuery.isBlank() ||
                    memory.fact.contains(searchQuery, ignoreCase = true)
            val matchesCategory = selectedCategory == null ||
                    memory.category == selectedCategory
            matchesSearch && matchesCategory
        }.distinctBy { it.id }
    }

    val staleCount = remember(allMemories) {
        allMemories.count { memoryExtractor.isStale(it) }
    }

    Scaffold(
        containerColor = Color.Black,
        topBar = {
            CenterAlignedTopAppBar(
                modifier = Modifier.background(Color.Black),
                title = {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = "AI Long-Term Memory",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                        Text(
                            text = "${allMemories.size} facts remembered",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                navigationIcon = {
                    FilledTonalIconButton(
                        onClick = {
                            haptics.selection()
                            onNavigateBack()
                        },
                        colors = IconButtonDefaults.filledTonalIconButtonColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                        ),
                        modifier = Modifier.padding(start = Standards.SpacingSm)
                    ) {
                        Icon(
                            imageVector = TnIcons.ArrowLeft,
                            contentDescription = "Back",
                            tint = Color.White
                        )
                    }
                },
                actions = {
                    if (staleCount > 0) {
                        FilledTonalIconButton(
                            onClick = {
                                haptics.selection()
                                showClearStaleDialog = true
                            },
                            colors = IconButtonDefaults.filledTonalIconButtonColors(
                                containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f)
                            ),
                            modifier = Modifier.padding(end = Standards.SpacingSm)
                        ) {
                            Icon(
                                imageVector = TnIcons.Trash,
                                contentDescription = "Clear Stale",
                                tint = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = Color.Black
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(Color.Black)
        ) {
            // Material 3 Expressive Search Bar Pill
            Surface(
                color = Color.Black,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Standards.SpacingMd, vertical = Standards.SpacingXs)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .background(
                            color = MaterialTheme.colorScheme.surfaceContainerHigh,
                            shape = RoundedCornerShape(24.dp)
                        )
                        .border(
                            width = 1.dp,
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                            shape = RoundedCornerShape(24.dp)
                        )
                        .padding(horizontal = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = TnIcons.Search,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp)
                    )

                    Spacer(modifier = Modifier.width(10.dp))

                    BasicTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyMedium.copy(
                            color = Color.White
                        ),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        modifier = Modifier
                            .weight(1f)
                            .padding(vertical = 4.dp),
                        decorationBox = { innerTextField ->
                            Box(contentAlignment = Alignment.CenterStart) {
                                if (searchQuery.isEmpty()) {
                                    Text(
                                        text = "Search remembered facts & notes...",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                                    )
                                }
                                innerTextField()
                            }
                        }
                    )

                    if (searchQuery.isNotEmpty()) {
                        IconButton(
                            onClick = {
                                haptics.selection()
                                searchQuery = ""
                            },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                imageVector = TnIcons.X,
                                contentDescription = "Clear",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }
            }

            // Material 3 Filter Chips Row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = Standards.SpacingMd, vertical = Standards.SpacingXs),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = selectedCategory == null,
                    onClick = {
                        haptics.selection()
                        selectedCategory = null
                    },
                    label = { Text("All (${allMemories.size})") },
                    shape = RoundedCornerShape(12.dp),
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.primary,
                        selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        labelColor = Glass.TextSecondary
                    ),
                    border = FilterChipDefaults.filterChipBorder(
                        enabled = true,
                        selected = selectedCategory == null,
                        borderColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f),
                        selectedBorderColor = MaterialTheme.colorScheme.primary
                    )
                )

                MemoryCategory.entries.forEach { category ->
                    val count = allMemories.count { it.category == category }
                    FilterChip(
                        selected = selectedCategory == category,
                        onClick = {
                            haptics.selection()
                            selectedCategory = if (selectedCategory == category) null else category
                        },
                        label = { Text("${categoryLabel(category)} ($count)") },
                        shape = RoundedCornerShape(12.dp),
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primary,
                            selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                            labelColor = Glass.TextSecondary
                        ),
                        border = FilterChipDefaults.filterChipBorder(
                            enabled = true,
                            selected = selectedCategory == category,
                            borderColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f),
                            selectedBorderColor = MaterialTheme.colorScheme.primary
                        )
                    )
                }
            }

            // Content List or Empty State
            if (filteredMemories.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(Standards.SpacingXxl),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.surfaceContainerHigh,
                            modifier = Modifier.size(64.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = if (allMemories.isEmpty()) TnIcons.Sparkles else TnIcons.SearchOff,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(32.dp)
                                )
                            }
                        }
                        Text(
                            text = if (allMemories.isEmpty()) "No Memories Formed Yet" else "No Matching Memories",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = Glass.TextPrimary
                        )
                        Text(
                            text = if (allMemories.isEmpty())
                                "Chat with BIT to automatically extract and retain facts about your preferences, work, and interests."
                            else
                                "No memories found for \"$searchQuery\". Try adjusting your search query or filter.",
                            style = MaterialTheme.typography.bodySmall,
                            color = Glass.TextSecondary,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            modifier = Modifier.padding(horizontal = 24.dp)
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .imePadding(),
                    contentPadding = PaddingValues(horizontal = Standards.SpacingMd, vertical = Standards.SpacingSm),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(filteredMemories, key = { it.id }) { memory ->
                        MemoryItem(
                            memory = memory,
                            isStale = memoryExtractor.isStale(memory),
                            strength = memoryExtractor.computeStrength(memory),
                            onDelete = {
                                haptics.selection()
                                scope.launch { memoryRepo.delete(memory) }
                            }
                        )
                    }

                    if (allMemories.size > 2) {
                        item {
                            Spacer(modifier = Modifier.height(Standards.SpacingSm))
                            OutlinedButton(
                                onClick = {
                                    haptics.selection()
                                    showClearAllDialog = true
                                },
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(Standards.CardCornerRadius),
                                colors = ButtonDefaults.outlinedButtonColors(
                                    contentColor = MaterialTheme.colorScheme.error
                                ),
                                border = androidx.compose.foundation.BorderStroke(
                                    1.dp,
                                    MaterialTheme.colorScheme.error.copy(alpha = 0.5f)
                                )
                            ) {
                                Icon(
                                    imageVector = TnIcons.Trash,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Clear All Memories")
                            }
                        }
                    }

                    item { Spacer(modifier = Modifier.height(Standards.SpacingXl)) }
                }
            }
        }
    }

    // Clear all dialog
    if (showClearAllDialog) {
        AlertDialog(
            onDismissRequest = { showClearAllDialog = false },
            title = { Text("Clear All Memories?") },
            text = { Text("This will permanently delete all ${allMemories.size} memories. The AI will reset its knowledge about you.") },
            confirmButton = {
                TextButton(onClick = {
                    haptics.selection()
                    scope.launch {
                        memoryRepo.deleteAll()
                        showClearAllDialog = false
                    }
                }) {
                    Text("Clear All", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearAllDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Clear stale dialog
    if (showClearStaleDialog) {
        AlertDialog(
            onDismissRequest = { showClearStaleDialog = false },
            title = { Text("Clear Stale Memories?") },
            text = { Text("This will remove $staleCount outdated memories that haven't been accessed recently.") },
            confirmButton = {
                TextButton(onClick = {
                    haptics.selection()
                    scope.launch {
                        memoryExtractor.clearStaleMemories()
                        showClearStaleDialog = false
                    }
                }) {
                    Text("Clear Stale", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearStaleDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
private fun MemoryItem(
    memory: AiMemory,
    isStale: Boolean,
    strength: Float,
    onDelete: () -> Unit
) {
    val dateFormat = remember { SimpleDateFormat("MMM d, yyyy", Locale.getDefault()) }

    GlassCard(
        onClick = {},
        modifier = Modifier.fillMaxWidth(),
        backgroundColor = if (isStale) Glass.Surface.copy(alpha = 0.5f) else Glass.Surface,
        borderColor = Glass.BorderSubtle,
        cornerRadius = Standards.CardCornerRadius,
        contentPadding = PaddingValues(Standards.CardPadding)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .animateContentSize()
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Category Icon surface badge
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = categoryColor(memory.category).copy(alpha = 0.15f),
                    modifier = Modifier.size(36.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = categoryIcon(memory.category),
                            contentDescription = null,
                            tint = categoryColor(memory.category),
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }

                // Fact text
                Text(
                    text = memory.fact,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                    color = if (isStale) Glass.TextSecondary else Glass.TextPrimary
                )

                // Delete button
                IconButton(
                    onClick = onDelete,
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        imageVector = TnIcons.Trash,
                        contentDescription = "Delete",
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Footer metadata
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Category pill
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                    ) {
                        Text(
                            text = categoryLabel(memory.category),
                            style = MaterialTheme.typography.labelSmall,
                            color = categoryColor(memory.category),
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }

                    if (isStale) {
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.4f)
                        ) {
                            Text(
                                text = "Stale",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                }

                // Strength percentage & date
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = when {
                            strength >= 0.7f -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
                            strength >= 0.4f -> MaterialTheme.colorScheme.surfaceContainerHigh
                            else -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f)
                        }
                    ) {
                        Text(
                            text = "${(strength * 100).roundToInt()}% strength",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Medium,
                            color = when {
                                strength >= 0.7f -> MaterialTheme.colorScheme.primary
                                strength >= 0.4f -> MaterialTheme.colorScheme.onSurfaceVariant
                                else -> MaterialTheme.colorScheme.error
                            },
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }

                    Text(
                        text = dateFormat.format(Date(memory.updatedAt)),
                        style = MaterialTheme.typography.labelSmall,
                        color = Glass.TextSecondary
                    )
                }
            }
        }
    }
}

private fun categoryLabel(category: MemoryCategory): String {
    return when (category) {
        MemoryCategory.PERSONAL -> "Personal"
        MemoryCategory.PREFERENCE -> "Preference"
        MemoryCategory.WORK -> "Work"
        MemoryCategory.INTEREST -> "Interest"
        MemoryCategory.GENERAL -> "General"
    }
}

@Composable
private fun categoryColor(category: MemoryCategory): Color {
    return when (category) {
        MemoryCategory.PERSONAL -> MaterialTheme.colorScheme.primary
        MemoryCategory.PREFERENCE -> Color(0xFFE91E63)
        MemoryCategory.WORK -> Color(0xFF2196F3)
        MemoryCategory.INTEREST -> Color(0xFFFF9800)
        MemoryCategory.GENERAL -> MaterialTheme.colorScheme.secondary
    }
}

private fun categoryIcon(category: MemoryCategory): androidx.compose.ui.graphics.vector.ImageVector {
    return when (category) {
        MemoryCategory.PERSONAL -> TnIcons.User
        MemoryCategory.PREFERENCE -> TnIcons.Heart
        MemoryCategory.WORK -> TnIcons.Package
        MemoryCategory.INTEREST -> TnIcons.Sparkles
        MemoryCategory.GENERAL -> TnIcons.Folder
    }
}
