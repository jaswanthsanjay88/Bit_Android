package com.bit.ui.screen.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
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
import com.bit.ui.theme.LocalBitHaptics
import com.bit.util.SkillExportImport
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
    skillManager: SkillManager
) {
    SkillsScreen(skillManager = skillManager)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SkillsScreen(
    skillManager: SkillManager
) {
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    val bitHaptics = LocalBitHaptics.current

    val skills by skillManager.skills.collectAsStateWithLifecycle()
    var localOrder by remember(skills) { mutableStateOf(skills) }

    var selectedSkillForEdit by remember { mutableStateOf<Skill?>(null) }
    var showCreateSheet by remember { mutableStateOf(false) }

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

    // First-time tool notice dialog
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
                    "Agent Skills will only be actively triggered when using instruction-tuned tool models (e.g. Qwen 2.5, Llama 3.1/3.2, Claude, GPT-4o, DeepSeek-V3). Base completion models cannot execute structured tool routines.",
                    style = MaterialTheme.typography.bodyMedium,
                    lineHeight = 20.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        bitHaptics.pop()
                        showFirstTimeSkillsDialog = false
                        prefs.edit().putBoolean("has_seen_skills_notice", true).apply()
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
            .background(Color.Black),
        contentPadding = PaddingValues(horizontal = Standards.SpacingMd, vertical = Standards.SpacingSm),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // ── HERO INFO BANNER ──
        item(key = "hero_banner") {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
            ) {
                Row(
                    modifier = Modifier.padding(18.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.primaryContainer,
                        modifier = Modifier.size(44.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Rounded.AutoAwesome,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                    }

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Agent Skills (Claude SKILL.md)",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "Modular instruction skills and prompt capabilities. Long-press drag handle to reorder, swipe left to delete.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        // ── TOP ACTION BAR ──
        item(key = "action_bar") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "CONFIGURED SKILLS (${localOrder.size})",
                    style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 1.sp),
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = {
                            bitHaptics.pop()
                            importLauncher.launch(arrayOf("*/*", "text/markdown", "application/json"))
                        },
                        shape = RoundedCornerShape(12.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Icon(Icons.Rounded.FileUpload, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Import SKILL.md", style = MaterialTheme.typography.labelMedium)
                    }

                    FilledTonalButton(
                        onClick = {
                            bitHaptics.pop()
                            showCreateSheet = true
                        },
                        shape = RoundedCornerShape(12.dp),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
                    ) {
                        Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("New Skill", style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }

        // ── EMPTY STATE ──
        if (localOrder.isEmpty()) {
            item(key = "empty_state") {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
                ) {
                    Box(modifier = Modifier.padding(32.dp).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Text(
                            text = "No skills configured. Tap \"New Skill\" or \"Import SKILL.md\" to add one.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        } else {
            // ── REORDERABLE SKILL CARDS ──
            items(localOrder, key = { it.id }) { skill ->
                val index = localOrder.indexOf(skill)
                val position = when {
                    localOrder.size == 1 -> ItemPosition.ONLY
                    index == 0 -> ItemPosition.FIRST
                    index == localOrder.lastIndex -> ItemPosition.LAST
                    else -> ItemPosition.MIDDLE
                }

                ReorderableItem(state = reorderableState, key = skill.id) { isDragging ->
                    val elevation by animateDpAsState(if (isDragging) 8.dp else 0.dp, label = "elevation")
                    val scale by animateFloatAsState(if (isDragging) 1.02f else 1f, label = "scale")
                    val alpha by animateFloatAsState(if (isDragging) 0.92f else 1f, label = "alpha")

                    PhysicsSwipeToDelete(
                        onDelete = {
                            bitHaptics.thud()
                            skillManager.removeSkill(skill.id)
                            Toast.makeText(context, "Deleted \"${skill.name}\"", Toast.LENGTH_SHORT).show()
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
                        SkillCard(
                            skill = skill,
                            shape = shape,
                            dragHandle = {
                                Icon(
                                    Icons.Rounded.DragIndicator,
                                    contentDescription = "Reorder ${skill.name}",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                    modifier = Modifier
                                        .size(48.dp)
                                        .padding(8.dp)
                                        .longPressDraggableHandle(
                                            onDragStarted = {
                                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                            },
                                            onDragStopped = {
                                                skillManager.setOrderedSkills(localOrder)
                                            }
                                        )
                                )
                            },
                            onToggle = { isEnabled ->
                                bitHaptics.selection()
                                skillManager.toggleSkill(skill.id, isEnabled)
                            },
                            onClick = {
                                bitHaptics.pop()
                                selectedSkillForEdit = skill
                            },
                            onExportMd = {
                                val md = SkillExportImport.exportToSkillMd(skill)
                                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                cm.setPrimaryClip(ClipData.newPlainText("SKILL.md", md))
                                Toast.makeText(context, "Copied SKILL.md to clipboard", Toast.LENGTH_SHORT).show()
                            }
                        )
                    }
                }
            }
        }
    }

    // ── EDIT / CREATE MODAL SHEET ──
    val activeSkill = selectedSkillForEdit
    if (activeSkill != null) {
        SkillEditorSheet(
            skill = activeSkill,
            isNew = false,
            onDismiss = { selectedSkillForEdit = null },
            onSave = { updated ->
                skillManager.updateSkill(updated)
                selectedSkillForEdit = null
                Toast.makeText(context, "Saved \"${updated.name}\"", Toast.LENGTH_SHORT).show()
            }
        )
    }

    if (showCreateSheet) {
        SkillEditorSheet(
            skill = Skill(name = "", description = "", instructions = "", icon = "code"),
            isNew = true,
            onDismiss = { showCreateSheet = false },
            onSave = { newSkill ->
                skillManager.addSkill(newSkill)
                showCreateSheet = false
                Toast.makeText(context, "Created \"${newSkill.name}\"", Toast.LENGTH_SHORT).show()
            }
        )
    }
}

@Composable
fun SkillCard(
    skill: Skill,
    shape: androidx.compose.ui.graphics.Shape,
    dragHandle: @Composable () -> Unit = {},
    onToggle: (Boolean) -> Unit,
    onClick: () -> Unit,
    onExportMd: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = shape,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Drag handle — isolated 48dp touch area
            dragHandle()

            // Skill Icon
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.7f),
                modifier = Modifier.size(42.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = getSkillIcon(skill.icon),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }

            // Text Info — Clickable region for editing
            Column(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(onClick = onClick)
                    .padding(vertical = 4.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = skill.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (skill.isBuiltIn) {
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                        ) {
                            Text(
                                text = "BUILT-IN",
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                            )
                        }
                    }
                }

                if (skill.description.isNotBlank()) {
                    Text(
                        text = skill.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            // Actions (Export & Toggle)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                IconButton(onClick = onExportMd, modifier = Modifier.size(36.dp)) {
                    Icon(
                        Icons.Rounded.ContentCopy,
                        contentDescription = "Copy SKILL.md",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                }

                Switch(
                    checked = skill.enabled,
                    onCheckedChange = onToggle
                )
            }
        }
    }
}

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
    var icon by remember { mutableStateOf(skill.icon ?: "sparkles") }
    var enabled by remember { mutableStateOf(skill.enabled) }

    val iconOptions = listOf(
        "sparkles" to Icons.Rounded.AutoAwesome,
        "search" to Icons.Rounded.Search,
        "storage" to Icons.Rounded.Storage,
        "terminal" to Icons.Rounded.Terminal,
        "calculate" to Icons.Rounded.Calculate,
        "code" to Icons.Rounded.Code,
        "security" to Icons.Rounded.Security,
        "translate" to Icons.Rounded.Translate,
        "brush" to Icons.Rounded.Brush,
        "psychology" to Icons.Rounded.Psychology
    )

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.85f)
                .padding(horizontal = 24.dp, vertical = 8.dp)
                .verticalScroll(rememberScrollState())
                .imePadding(),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (isNew) "Create Agent Skill" else "Edit Agent Skill",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Enabled", style = MaterialTheme.typography.labelMedium)
                    Spacer(Modifier.width(8.dp))
                    Switch(checked = enabled, onCheckedChange = { enabled = it })
                }
            }

            // Name
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Skill Name") },
                placeholder = { Text("e.g. Code Reviewer, Python Expert") },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth()
            )

            // Description / Trigger
            OutlinedTextField(
                value = description,
                onValueChange = { description = it },
                label = { Text("Description & Trigger Criteria") },
                placeholder = { Text("When and why the AI should apply this skill...") },
                maxLines = 3,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth()
            )

            // Icon Chips
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "Skill Icon",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(iconOptions) { (key, imageVector) ->
                        val isSelected = icon == key
                        Surface(
                            shape = CircleShape,
                            color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh,
                            modifier = Modifier
                                .size(40.dp)
                                .clickable { icon = key }
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = imageVector,
                                    contentDescription = key,
                                    tint = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    }
                }
            }

            // Markdown Instructions Body
            OutlinedTextField(
                value = instructions,
                onValueChange = { instructions = it },
                label = { Text("Instructions Body (Claude SKILL.md markdown)") },
                placeholder = { Text("Enter the system prompt instructions, rules, and behavioral guidelines...") },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 160.dp, max = 320.dp),
                textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace, fontSize = 13.sp),
                shape = RoundedCornerShape(14.dp)
            )

            // Save button
            Button(
                onClick = {
                    if (name.isNotBlank()) {
                        onSave(
                            skill.copy(
                                name = name.trim(),
                                description = description.trim(),
                                instructions = instructions.trim(),
                                icon = icon,
                                enabled = enabled
                            )
                        )
                    }
                },
                enabled = name.isNotBlank(),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Rounded.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(if (isNew) "Create Skill" else "Save Changes")
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

fun getSkillIcon(iconKey: String?): ImageVector {
    return when (iconKey?.lowercase()) {
        "search" -> Icons.Rounded.Search
        "storage" -> Icons.Rounded.Storage
        "terminal" -> Icons.Rounded.Terminal
        "calculate" -> Icons.Rounded.Calculate
        "code" -> Icons.Rounded.Code
        "security" -> Icons.Rounded.Security
        "translate" -> Icons.Rounded.Translate
        "brush" -> Icons.Rounded.Brush
        "psychology" -> Icons.Rounded.Psychology
        else -> Icons.Rounded.AutoAwesome
    }
}
