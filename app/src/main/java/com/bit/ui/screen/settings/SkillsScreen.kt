package com.bit.ui.screen.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
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
import com.bit.models.Skill
import com.bit.skills.SkillManager
import com.bit.ui.components.ItemPosition
import com.bit.ui.components.PhysicsSwipeToDelete
import com.bit.ui.icons.TnIcons
import com.bit.ui.theme.LocalBitHaptics
import com.bit.util.SkillExportImport
import kotlinx.coroutines.launch
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

fun LazyListScope.skillsSection(
    skillManager: SkillManager
) {
    item {
        SkillsContent(skillManager = skillManager)
    }
}

@Composable
fun SkillsContent(
    skillManager: SkillManager,
    onUseSkillInChat: ((String) -> Unit)? = null
) {
    SkillsScreen(skillManager = skillManager, onUseSkillInChat = onUseSkillInChat)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SkillsScreen(
    skillManager: SkillManager,
    onUseSkillInChat: ((String) -> Unit)? = null
) {
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    val bitHaptics = LocalBitHaptics.current

    val skills by skillManager.skills.collectAsStateWithLifecycle()
    var localOrder by remember(skills) { mutableStateOf(skills) }

    val coroutineScope = rememberCoroutineScope()
    var selectedSkillForDetail by remember { mutableStateOf<Skill?>(null) }
    var selectedSkillForEdit by remember { mutableStateOf<Skill?>(null) }
    var showCreateSheet by remember { mutableStateOf(false) }
    var showImportUrlDialog by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var selectedFilterIndex by remember { mutableIntStateOf(0) } // 0: All, 1: Active, 2: Built-in, 3: Custom

    val filters = listOf("All", "Active", "Built-in", "Custom")

    val filteredSkills = remember(localOrder, searchQuery, selectedFilterIndex) {
        localOrder.filter { skill ->
            val matchesSearch = searchQuery.isBlank() ||
                    skill.name.contains(searchQuery, ignoreCase = true) ||
                    skill.description.contains(searchQuery, ignoreCase = true) ||
                    skill.instructions.contains(searchQuery, ignoreCase = true) ||
                    skillManager.getSkillSlug(skill).contains(searchQuery, ignoreCase = true)

            val matchesFilter = when (selectedFilterIndex) {
                1 -> skill.enabled
                2 -> skill.isBuiltIn
                3 -> !skill.isBuiltIn
                else -> true
            }

            matchesSearch && matchesFilter
        }
    }

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

    // File import launcher for SKILL.md / JSON
    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            val result = SkillExportImport.importFromUri(context, uri)
            when (result) {
                is SkillExportImport.ImportResult.Success -> {
                    bitHaptics.success()
                    skillManager.addSkill(result.skill)
                    Toast.makeText(context, "Imported \"${result.skill.name}\" (${result.format})", Toast.LENGTH_SHORT).show()
                }
                is SkillExportImport.ImportResult.Error -> {
                    bitHaptics.thud()
                    Toast.makeText(context, "Import failed: ${result.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    if (showImportUrlDialog) {
        ImportSkillUrlDialog(
            onDismiss = { showImportUrlDialog = false },
            onConfirm = { url ->
                coroutineScope.launch {
                    val result = SkillExportImport.importFromUrl(url)
                    showImportUrlDialog = false
                    when (result) {
                        is SkillExportImport.ImportResult.Success -> {
                            bitHaptics.success()
                            skillManager.addSkill(result.skill)
                            Toast.makeText(context, "Imported \"${result.skill.name}\" from ${result.format}", Toast.LENGTH_SHORT).show()
                        }
                        is SkillExportImport.ImportResult.Error -> {
                            bitHaptics.thud()
                            Toast.makeText(context, "Import failed: ${result.message}", Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }
        )
    }

    // First-time notice dialog
    val prefs = remember { context.getSharedPreferences("bit_ui_prefs", Context.MODE_PRIVATE) }
    var showFirstTimeSkillsDialog by remember {
        mutableStateOf(!prefs.getBoolean("has_seen_skills_notice", false))
    }

    if (showFirstTimeSkillsDialog) {
        AlertDialog(
            onDismissRequest = {
                showFirstTimeSkillsDialog = false
                prefs.edit().putBoolean("has_seen_skills_notice", true).apply()
            },
            icon = {
                Icon(
                    Icons.Rounded.AutoAwesome,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(28.dp)
                )
            },
            title = {
                Text(
                    "Agent Skills Framework",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold
                )
            },
            text = {
                Text(
                    "Agent Skills inject specialized behavioral instructions, domain patterns, and capabilities (Anthropic SKILL.md standard) directly into the agent context.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        bitHaptics.pop()
                        showFirstTimeSkillsDialog = false
                        prefs.edit().putBoolean("has_seen_skills_notice", true).apply()
                    }
                ) {
                    Text("Got It")
                }
            }
        )
    }

    LazyColumn(
        state = lazyListState,
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // ── 1. HEADER / TOOLBAR CARD (MATCHES MCP & SETTINGS SCREEN) ──
        item(key = "header_toolbar") {
            Card(
                modifier = Modifier.fillMaxWidth(),
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
                                imageVector = Icons.Rounded.AutoAwesome,
                                contentDescription = "Skills",
                                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                text = "Agent Skills",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "${skills.count { it.enabled }} active • ${skills.size} total",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        IconButton(
                            onClick = {
                                bitHaptics.pop()
                                importLauncher.launch(arrayOf("*/*", "text/markdown", "application/json"))
                            }
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.FileUpload,
                                contentDescription = "Import file",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        IconButton(
                            onClick = {
                                bitHaptics.pop()
                                showImportUrlDialog = true
                            }
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.CloudDownload,
                                contentDescription = "Import URL",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        FilledTonalButton(
                            onClick = {
                                bitHaptics.pop()
                                showCreateSheet = true
                            },
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                            shape = RoundedCornerShape(Standards.RadiusMd),
                            modifier = Modifier.height(36.dp)
                        ) {
                            Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("New", style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }
        }

        // ── 2. SEARCH BAR & FILTER CHIPS ──
        item(key = "search_and_filters") {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                // Search bar matching SettingsScreen
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(44.dp)
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

                    Spacer(modifier = Modifier.width(8.dp))

                    BasicTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyMedium.copy(
                            color = MaterialTheme.colorScheme.onSurface
                        ),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        modifier = Modifier.weight(1f),
                        decorationBox = { innerTextField ->
                            Box(contentAlignment = Alignment.CenterStart) {
                                if (searchQuery.isEmpty()) {
                                    Text(
                                        text = "Search skills or /commands...",
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
                            onClick = { searchQuery = "" },
                            modifier = Modifier.size(28.dp)
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

                // Filter Chips
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    filters.forEachIndexed { index, filter ->
                        val isSelected = selectedFilterIndex == index
                        FilterChip(
                            selected = isSelected,
                            onClick = {
                                bitHaptics.selection()
                                selectedFilterIndex = index
                            },
                            label = {
                                Text(
                                    filter,
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal
                                )
                            },
                            shape = RoundedCornerShape(Standards.RadiusMd),
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                containerColor = MaterialTheme.colorScheme.surfaceContainer,
                                labelColor = MaterialTheme.colorScheme.onSurfaceVariant
                            ),
                            border = FilterChipDefaults.filterChipBorder(
                                enabled = true,
                                selected = isSelected,
                                borderColor = if (isSelected) Color.Transparent else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
                            )
                        )
                    }
                }
            }
        }

        // ── 3. SKILL CARDS ──
        if (filteredSkills.isEmpty()) {
            item(key = "empty_state") {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 40.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Icon(
                            imageVector = TnIcons.Sparkles,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.outlineVariant,
                            modifier = Modifier.size(48.dp)
                        )
                        Text(
                            text = if (searchQuery.isNotEmpty()) "No matching skills" else "No skills configured",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = if (searchQuery.isNotEmpty()) "Try a different search term" else "Tap '+ New' to create an agent skill",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (searchQuery.isNotEmpty()) {
                            TextButton(onClick = { searchQuery = "" }) {
                                Text("Clear Search")
                            }
                        }
                    }
                }
            }
        } else {
            items(
                items = filteredSkills,
                key = { it.id }
            ) { skill ->
                ReorderableItem(reorderableState, key = skill.id) { isDragging ->
                    val slug = skillManager.getSkillSlug(skill)

                    if (!skill.isBuiltIn) {
                        PhysicsSwipeToDelete(
                            onDelete = {
                                bitHaptics.thud()
                                skillManager.removeSkill(skill.id)
                                Toast.makeText(context, "Deleted \"${skill.name}\"", Toast.LENGTH_SHORT).show()
                            }
                        ) { shape ->
                            SkillCard(
                                skill = skill,
                                slug = slug,
                                isDragging = isDragging,
                                shape = shape,
                                dragHandle = {
                                    IconButton(
                                        onClick = {},
                                        modifier = Modifier.draggableHandle()
                                    ) {
                                        Icon(
                                            imageVector = Icons.Rounded.DragHandle,
                                            contentDescription = "Reorder",
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }
                                },
                                onToggle = { enabled ->
                                    skillManager.toggleSkill(skill.id, enabled)
                                },
                                onClick = {
                                    bitHaptics.pop()
                                    selectedSkillForDetail = skill
                                }
                            )
                        }
                    } else {
                        SkillCard(
                            skill = skill,
                            slug = slug,
                            isDragging = isDragging,
                            dragHandle = {
                                IconButton(
                                    onClick = {},
                                    modifier = Modifier.draggableHandle()
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.DragHandle,
                                        contentDescription = "Reorder",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            },
                            onToggle = { enabled ->
                                skillManager.toggleSkill(skill.id, enabled)
                            },
                            onClick = {
                                bitHaptics.pop()
                                selectedSkillForDetail = skill
                            }
                        )
                    }
                }
            }
        }

        item(key = "bottom_spacer") {
            Spacer(modifier = Modifier.height(24.dp))
        }
    }

    // Detail Sheet
    selectedSkillForDetail?.let { skill ->
        SkillDetailSheet(
            skill = skill,
            slug = skillManager.getSkillSlug(skill),
            onDismiss = { selectedSkillForDetail = null },
            onToggle = { enabled ->
                skillManager.toggleSkill(skill.id, enabled)
                selectedSkillForDetail = skill.copy(enabled = enabled)
            },
            onEdit = {
                selectedSkillForDetail = null
                selectedSkillForEdit = skill
            },
            onDelete = {
                skillManager.removeSkill(skill.id)
                selectedSkillForDetail = null
                Toast.makeText(context, "Deleted \"${skill.name}\"", Toast.LENGTH_SHORT).show()
            },
            onExportMd = {
                val text = SkillExportImport.exportToSkillMd(skill)
                val sendIntent = android.content.Intent().apply {
                    action = android.content.Intent.ACTION_SEND
                    putExtra(android.content.Intent.EXTRA_TEXT, text)
                    type = "text/plain"
                }
                context.startActivity(android.content.Intent.createChooser(sendIntent, "Share ${skill.name} (SKILL.md)"))
            },
            onExportJson = {
                val json = SkillExportImport.exportToJson(skill)
                val sendIntent = android.content.Intent().apply {
                    action = android.content.Intent.ACTION_SEND
                    putExtra(android.content.Intent.EXTRA_TEXT, json)
                    type = "application/json"
                }
                context.startActivity(android.content.Intent.createChooser(sendIntent, "Share ${skill.name} (JSON)"))
            },
            onUseInChat = onUseSkillInChat
        )
    }

    // Create / Edit Sheet
    if (showCreateSheet) {
        SkillEditorSheet(
            skill = Skill(name = "", description = "", instructions = "", icon = "code"),
            isNew = true,
            onDismiss = { showCreateSheet = false },
            onSave = { newSkill ->
                skillManager.addSkill(newSkill)
                showCreateSheet = false
                bitHaptics.success()
                Toast.makeText(context, "Skill created", Toast.LENGTH_SHORT).show()
            }
        )
    }

    selectedSkillForEdit?.let { skill ->
        SkillEditorSheet(
            skill = skill,
            isNew = false,
            onDismiss = { selectedSkillForEdit = null },
            onSave = { updated ->
                skillManager.updateSkill(updated)
                selectedSkillForEdit = null
                bitHaptics.success()
                Toast.makeText(context, "Skill updated", Toast.LENGTH_SHORT).show()
            }
        )
    }
}

// ─────────────────────────────────────────────────────────────
// MINIMAL MATERIAL 3 SKILL CARD
// ─────────────────────────────────────────────────────────────
@Composable
fun SkillCard(
    skill: Skill,
    slug: String,
    isDragging: Boolean = false,
    shape: androidx.compose.ui.graphics.Shape = RoundedCornerShape(Standards.RadiusMd),
    dragHandle: @Composable () -> Unit = {},
    onToggle: (Boolean) -> Unit,
    onClick: () -> Unit
) {
    val bitHaptics = LocalBitHaptics.current

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = shape,
        colors = CardDefaults.cardColors(
            containerColor = if (skill.enabled) MaterialTheme.colorScheme.surfaceContainer
            else MaterialTheme.colorScheme.surfaceContainerLow
        ),
        border = BorderStroke(
            1.dp,
            if (skill.enabled) MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
            else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Drag handle
            dragHandle()

            // Icon container
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .clip(RoundedCornerShape(Standards.RadiusSm))
                    .background(
                        if (skill.enabled) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f)
                        else MaterialTheme.colorScheme.surfaceVariant
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = getSkillIcon(skill.icon),
                    contentDescription = null,
                    tint = if (skill.enabled) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp)
                )
            }

            // Text Info — Clickable region for details
            Column(
                modifier = Modifier
                    .weight(1f)
                    .clickable(onClick = onClick)
                    .padding(vertical = 2.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = skill.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )

                    // Command Slug Badge (e.g. /web-search)
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f)
                    ) {
                        Text(
                            text = "/$slug",
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                        )
                    }

                    if (skill.isBuiltIn) {
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant
                        ) {
                            Text(
                                text = "BUILT-IN",
                                style = MaterialTheme.typography.labelSmall,
                                fontSize = 8.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                            )
                        }
                    }
                }

                if (skill.description.isNotBlank()) {
                    Spacer(Modifier.height(3.dp))
                    Text(
                        text = skill.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            // Material 3 Switch
            Switch(
                checked = skill.enabled,
                onCheckedChange = {
                    bitHaptics.selection()
                    onToggle(it)
                },
                colors = SwitchDefaults.colors(
                    checkedThumbColor = MaterialTheme.colorScheme.primary,
                    checkedTrackColor = MaterialTheme.colorScheme.primaryContainer,
                    uncheckedThumbColor = MaterialTheme.colorScheme.outline,
                    uncheckedTrackColor = MaterialTheme.colorScheme.surfaceContainerHighest
                )
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────
// DETAIL MODAL BOTTOM SHEET
// ─────────────────────────────────────────────────────────────
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SkillDetailSheet(
    skill: Skill,
    slug: String,
    onDismiss: () -> Unit,
    onToggle: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onExportMd: () -> Unit,
    onExportJson: () -> Unit,
    onUseInChat: ((String) -> Unit)? = null
) {
    val context = LocalContext.current
    val bitHaptics = LocalBitHaptics.current

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(topStart = Standards.RadiusLg, topEnd = Standards.RadiusLg),
        tonalElevation = 2.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.85f)
                .padding(horizontal = 20.dp, vertical = 6.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Header Row: Icon + Name + Switch
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(Standards.RadiusSm))
                        .background(
                            if (skill.enabled) MaterialTheme.colorScheme.primaryContainer
                            else MaterialTheme.colorScheme.surfaceVariant
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = getSkillIcon(skill.icon),
                        contentDescription = null,
                        tint = if (skill.enabled) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(24.dp)
                    )
                }

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = skill.name,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f)
                        ) {
                            Text(
                                text = "/$slug",
                                style = MaterialTheme.typography.labelSmall,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                            )
                        }

                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant
                        ) {
                            Text(
                                text = if (skill.isBuiltIn) "BUILT-IN" else "CUSTOM",
                                style = MaterialTheme.typography.labelSmall,
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                            )
                        }
                    }
                }

                Switch(
                    checked = skill.enabled,
                    onCheckedChange = {
                        bitHaptics.selection()
                        onToggle(it)
                    }
                )
            }

            // Description
            if (skill.description.isNotBlank()) {
                Text(
                    text = skill.description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Instructions section
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "INSTRUCTIONS",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        letterSpacing = 1.sp
                    )

                    IconButton(
                        onClick = {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            clipboard.setPrimaryClip(ClipData.newPlainText("Skill Instructions", skill.instructions))
                            bitHaptics.pop()
                            Toast.makeText(context, "Instructions copied", Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.ContentCopy,
                            contentDescription = "Copy instructions",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }

                Surface(
                    shape = RoundedCornerShape(Standards.RadiusMd),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = skill.instructions.ifBlank { "No behavioral instructions defined." },
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(14.dp)
                    )
                }
            }

            // Action Buttons
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (onUseInChat != null) {
                    Button(
                        onClick = {
                            bitHaptics.pop()
                            onDismiss()
                            onUseInChat("/$slug ")
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(Standards.RadiusMd)
                    ) {
                        Icon(Icons.Rounded.Chat, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Use in Chat")
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = {
                            bitHaptics.pop()
                            onExportMd()
                        },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(Standards.RadiusMd)
                    ) {
                        Icon(Icons.Rounded.Share, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Share .md")
                    }

                    OutlinedButton(
                        onClick = {
                            bitHaptics.pop()
                            onExportJson()
                        },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(Standards.RadiusMd)
                    ) {
                        Icon(Icons.Rounded.Code, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Share JSON")
                    }
                }

                if (!skill.isBuiltIn) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        FilledTonalButton(
                            onClick = {
                                bitHaptics.pop()
                                onEdit()
                            },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(Standards.RadiusMd)
                        ) {
                            Icon(Icons.Rounded.Edit, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Edit")
                        }

                        OutlinedButton(
                            onClick = {
                                bitHaptics.thud()
                                onDelete()
                            },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(Standards.RadiusMd),
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = MaterialTheme.colorScheme.error
                            )
                        ) {
                            Icon(Icons.Rounded.Delete, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Delete")
                        }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
        }
    }
}

// ─────────────────────────────────────────────────────────────
// SKILL EDITOR BOTTOM SHEET (CREATE & EDIT)
// ─────────────────────────────────────────────────────────────
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SkillEditorSheet(
    skill: Skill,
    isNew: Boolean,
    onDismiss: () -> Unit,
    onSave: (Skill) -> Unit
) {
    var name by remember { mutableStateOf(skill.name) }
    var description by remember { mutableStateOf(skill.description) }
    var instructions by remember { mutableStateOf(skill.instructions) }
    var icon by remember { mutableStateOf(skill.icon ?: "terminal") }
    var enabled by remember { mutableStateOf(skill.enabled) }

    val iconOptions = listOf(
        "terminal" to Icons.Rounded.Terminal,
        "code" to Icons.Rounded.Code,
        "mcp" to TnIcons.Mcp,
        "search" to Icons.Rounded.Search,
        "storage" to Icons.Rounded.Storage,
        "security" to Icons.Rounded.Security,
        "auto" to Icons.Rounded.AutoAwesome,
        "translate" to Icons.Rounded.Translate,
        "brush" to Icons.Rounded.Brush,
        "psychology" to Icons.Rounded.Psychology
    )

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(topStart = Standards.RadiusLg, topEnd = Standards.RadiusLg),
        tonalElevation = 2.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.9f)
                .padding(horizontal = 20.dp, vertical = 6.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text(
                text = if (isNew) "Create Agent Skill" else "Edit Skill",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )

            // Name
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Skill Name") },
                placeholder = { Text("e.g. Code Reviewer") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(Standards.RadiusMd)
            )

            // Description
            OutlinedTextField(
                value = description,
                onValueChange = { description = it },
                label = { Text("Description") },
                placeholder = { Text("What does this skill help with?") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(Standards.RadiusMd)
            )

            // Icon Picker
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = "ICON",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    items(iconOptions) { (key, vector) ->
                        val isSelected = icon == key
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(RoundedCornerShape(Standards.RadiusSm))
                                .background(
                                    if (isSelected) MaterialTheme.colorScheme.primaryContainer
                                    else MaterialTheme.colorScheme.surfaceContainerHigh
                                )
                                .border(
                                    1.dp,
                                    if (isSelected) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                                    RoundedCornerShape(Standards.RadiusSm)
                                )
                                .clickable { icon = key },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = vector,
                                contentDescription = key,
                                tint = if (isSelected) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }
            }

            // Instructions
            OutlinedTextField(
                value = instructions,
                onValueChange = { instructions = it },
                label = { Text("Behavioral Instructions (Markdown)") },
                placeholder = { Text("Instructions injected into system prompt when this skill is invoked...") },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(200.dp),
                shape = RoundedCornerShape(Standards.RadiusMd)
            )

            // Enable switch
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Enable Skill Immediately",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Switch(
                    checked = enabled,
                    onCheckedChange = { enabled = it }
                )
            }

            // Save / Cancel Buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedButton(
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(Standards.RadiusMd)
                ) {
                    Text("Cancel")
                }

                Button(
                    onClick = {
                        if (name.isNotBlank()) {
                            onSave(
                                skill.copy(
                                    name = name.trim(),
                                    description = description.trim(),
                                    instructions = instructions.trim(),
                                    icon = icon,
                                    enabled = enabled,
                                    isBuiltIn = false
                                )
                            )
                        }
                    },
                    enabled = name.isNotBlank(),
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(Standards.RadiusMd)
                ) {
                    Text(if (isNew) "Create" else "Save")
                }
            }

            Spacer(Modifier.height(16.dp))
        }
    }
}

private fun getSkillIcon(icon: String?): ImageVector {
    return when (icon?.lowercase()) {
        "mcp" -> TnIcons.Mcp
        "search" -> Icons.Rounded.Search
        "storage" -> Icons.Rounded.Storage
        "terminal" -> Icons.Rounded.Terminal
        "code" -> Icons.Rounded.Code
        "security" -> Icons.Rounded.Security
        "translate" -> Icons.Rounded.Translate
        "brush" -> Icons.Rounded.Brush
        "psychology" -> Icons.Rounded.Psychology
        "auto" -> Icons.Rounded.AutoAwesome
        else -> Icons.Rounded.Terminal
    }
}

@Composable
private fun ImportSkillUrlDialog(
    onDismiss: () -> Unit,
    onConfirm: (url: String) -> Unit
) {
    var url by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = { if (!loading) onDismiss() },
        icon = {
            Icon(
                Icons.Rounded.CloudDownload,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(28.dp)
            )
        },
        title = {
            Text(
                "Import From URL / Git",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Paste a raw URL to a SKILL.md file or skill JSON definition:",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    placeholder = { Text("https://raw.githubusercontent.com/.../SKILL.md") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(Standards.RadiusMd)
                )
                if (loading) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (url.isNotBlank()) {
                        loading = true
                        onConfirm(url.trim())
                    }
                },
                enabled = url.isNotBlank() && !loading
            ) {
                Text("Import")
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                enabled = !loading
            ) {
                Text("Cancel")
            }
        }
    )
}
