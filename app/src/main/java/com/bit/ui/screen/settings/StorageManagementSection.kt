package com.bit.ui.screen.settings

import android.text.format.DateFormat
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.MenuBook
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bit.global.Standards
import com.bit.global.formatBytes
import com.bit.repo.AppStorageRepository
import com.bit.repo.AppStorageSnapshot
import com.bit.repo.StorageCategoryUsage
import com.bit.repo.StorageFileItem
import com.bit.ui.icons.TnIcons
import com.bit.ui.theme.Glass
import com.bit.ui.theme.LocalBitHaptics
import com.bit.ui.theme.Motion
import com.bit.ui.theme.bouncyClick
import com.bit.viewmodel.SettingsViewModel
import kotlinx.coroutines.launch
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StorageManagementSection(viewModel: SettingsViewModel) {
    val snapshot by viewModel.storageSnapshot.collectAsStateWithLifecycle()
    val haptics = LocalBitHaptics.current
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        viewModel.refreshStorage()
    }

    var inspectingCategory by remember { mutableStateOf<StorageCategoryUsage?>(null) }
    var inspectingFiles by remember { mutableStateOf<List<StorageFileItem>>(emptyList()) }
    var isLoadingFiles by remember { mutableStateOf(false) }
    var fileSearchQuery by remember { mutableStateOf("") }

    var pendingDeleteFile by remember { mutableStateOf<StorageFileItem?>(null) }
    var actionStatusMessage by remember { mutableStateOf<String?>(null) }

    fun openInspector(category: StorageCategoryUsage) {
        inspectingCategory = category
        fileSearchQuery = ""
        isLoadingFiles = true
        scope.launch {
            inspectingFiles = viewModel.listStorageCategoryFiles(category.id)
            isLoadingFiles = false
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(Standards.SpacingLg)) {

        // 1. Material 3 Expressive Hero Storage Card
        M3StorageHeroCard(
            snapshot = snapshot,
            onRefresh = {
                haptics.action()
                viewModel.refreshStorage()
            }
        )

        // 2. Action Status Feedback Banner
        AnimatedVisibility(
            visible = actionStatusMessage != null,
            enter = Motion.Enter,
            exit = Motion.Exit
        ) {
            actionStatusMessage?.let { msg ->
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f),
                    shape = RoundedCornerShape(Standards.CardCornerRadius),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.25f))
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = Standards.SpacingMd, vertical = Standards.SpacingSm),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Standards.SpacingSm)
                    ) {
                        Icon(
                            Icons.Rounded.CheckCircle,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                        Text(
                            text = msg,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(
                            onClick = {
                                haptics.pop()
                                actionStatusMessage = null
                            },
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(TnIcons.X, contentDescription = "Dismiss", modifier = Modifier.size(16.dp))
                        }
                    }
                }
            }
        }

        // 3. Quick Maintenance Action Buttons (M3 Tonal Pills)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Standards.SpacingSm)
        ) {
            // Clean Cache Button
            FilledTonalButton(
                onClick = {
                    haptics.action()
                    viewModel.clearTempCache { freed ->
                        actionStatusMessage = if (freed > 0) "Reclaimed ${formatBytes(freed)} temporary cache" else "Cache is already optimal"
                    }
                },
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.filledTonalButtonColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
                ),
                contentPadding = PaddingValues(horizontal = Standards.SpacingMd, vertical = 12.dp)
            ) {
                Icon(
                    Icons.Rounded.CleaningServices,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "Clean Cache",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }

            // Optimize Database Button
            FilledTonalButton(
                onClick = {
                    haptics.action()
                    viewModel.vacuumDatabase { success ->
                        actionStatusMessage = if (success) "SQLite database vacuumed and defragmented" else "Database optimization failed"
                    }
                },
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.filledTonalButtonColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
                ),
                contentPadding = PaddingValues(horizontal = Standards.SpacingMd, vertical = 12.dp)
            ) {
                Icon(
                    Icons.Rounded.Speed,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "Optimize DB",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }

        // 4. Category Breakdown List
        Card(
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow
            ),
            border = BorderStroke(1.dp, Glass.BorderSubtle),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Standards.SpacingLg, vertical = Standards.SpacingMd),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Storage Categories",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "${snapshot.categories.size} domains",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))

                snapshot.categories.forEachIndexed { index, category ->
                    M3StorageCategoryRow(
                        category = category,
                        onClick = {
                            haptics.selection()
                            openInspector(category)
                        }
                    )
                    if (index < snapshot.categories.size - 1) {
                        HorizontalDivider(
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f),
                            modifier = Modifier.padding(horizontal = Standards.SpacingMd)
                        )
                    }
                }
            }
        }
    }

    // 5. File Inspector BottomSheet
    inspectingCategory?.let { category ->
        ModalBottomSheet(
            onDismissRequest = { inspectingCategory = null },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = Glass.SurfaceElevated,
            scrimColor = Glass.Scrim,
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Standards.SpacingLg, vertical = Standards.SpacingSm)
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Standards.SpacingSm)
                    ) {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = getCategoryColor(category.id).copy(alpha = 0.2f),
                            modifier = Modifier.size(40.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = getCategoryIcon(category.id),
                                    contentDescription = null,
                                    tint = getCategoryColor(category.id),
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                        }
                        Column {
                            Text(
                                text = category.label,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "${inspectingFiles.size} files · Total: ${formatBytes(inspectingFiles.sumOf { it.sizeBytes })}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    IconButton(onClick = {
                        haptics.pop()
                        inspectingCategory = null
                    }) {
                        Icon(TnIcons.X, contentDescription = "Close", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }

                Spacer(modifier = Modifier.height(Standards.SpacingMd))

                // Search Bar within inspector
                if (inspectingFiles.size > 3) {
                    OutlinedTextField(
                        value = fileSearchQuery,
                        onValueChange = { fileSearchQuery = it },
                        placeholder = { Text("Filter files in category…", style = MaterialTheme.typography.bodyMedium) },
                        leadingIcon = { Icon(TnIcons.Search, contentDescription = null, modifier = Modifier.size(18.dp)) },
                        trailingIcon = {
                            if (fileSearchQuery.isNotEmpty()) {
                                IconButton(onClick = { fileSearchQuery = "" }) {
                                    Icon(TnIcons.X, contentDescription = "Clear", modifier = Modifier.size(16.dp))
                                }
                            }
                        },
                        singleLine = true,
                        shape = RoundedCornerShape(14.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = MaterialTheme.colorScheme.primary,
                            unfocusedBorderColor = Glass.BorderSubtle,
                            focusedContainerColor = Glass.SurfaceSubtle,
                            unfocusedContainerColor = Glass.SurfaceSubtle
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = Standards.SpacingSm)
                    )
                }

                val displayedFiles = remember(inspectingFiles, fileSearchQuery) {
                    if (fileSearchQuery.isBlank()) inspectingFiles
                    else inspectingFiles.filter { it.displayName.contains(fileSearchQuery, ignoreCase = true) }
                }

                if (isLoadingFiles) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(180.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(32.dp), color = MaterialTheme.colorScheme.primary)
                    }
                } else if (displayedFiles.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(140.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = if (fileSearchQuery.isBlank()) "No files found in this category" else "No matching files",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Glass.TextMuted
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 440.dp),
                        verticalArrangement = Arrangement.spacedBy(Standards.SpacingXs)
                    ) {
                        items(displayedFiles, key = { it.path }) { fileItem ->
                            M3StorageFileRowItem(
                                item = fileItem,
                                onDelete = {
                                    haptics.action()
                                    pendingDeleteFile = fileItem
                                }
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(Standards.SpacingLg))
            }
        }
    }

    // 6. Delete Confirmation Dialog
    pendingDeleteFile?.let { file ->
        AlertDialog(
            onDismissRequest = { pendingDeleteFile = null },
            icon = {
                Icon(
                    Icons.Rounded.DeleteOutline,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(28.dp)
                )
            },
            title = {
                Text(
                    text = "Delete File?",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Text(
                    text = "Are you sure you want to permanently delete \"${file.displayName}\" (${formatBytes(file.sizeBytes)})? This action cannot be undone.",
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        val pathToDelete = file.path
                        val cat = inspectingCategory
                        pendingDeleteFile = null
                        haptics.thud()
                        viewModel.deleteStorageFile(pathToDelete) { success ->
                            if (success && cat != null) {
                                scope.launch {
                                    inspectingFiles = viewModel.listStorageCategoryFiles(cat.id)
                                }
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Delete Permanently")
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    haptics.pop()
                    pendingDeleteFile = null
                }) {
                    Text("Cancel")
                }
            },
            containerColor = Glass.SurfaceElevated,
            shape = RoundedCornerShape(24.dp)
        )
    }
}

// ── M3StorageHeroCard ──

@Composable
private fun M3StorageHeroCard(
    snapshot: AppStorageSnapshot,
    onRefresh: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
        ),
        border = BorderStroke(1.dp, Glass.BorderSubtle)
    ) {
        Column(
            modifier = Modifier.padding(Standards.CardPadding),
            verticalArrangement = Arrangement.spacedBy(Standards.SpacingMd)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Standards.SpacingSm)
                ) {
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.primaryContainer,
                        modifier = Modifier.size(42.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                Icons.Rounded.Storage,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    }
                    Column {
                        Text(
                            text = "BIT Storage Footprint",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = if (snapshot.freeDeviceBytes > 0) "${formatBytes(snapshot.freeDeviceBytes)} Available on Device" else "Local On-Device Footprint",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                FilledTonalIconButton(
                    onClick = onRefresh,
                    colors = IconButtonDefaults.filledTonalIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                    ),
                    modifier = Modifier.size(38.dp)
                ) {
                    if (snapshot.isScanning) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(
                            Icons.Rounded.Refresh,
                            contentDescription = "Refresh",
                            tint = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }

            // Total Space Display
            Row(
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = formatBytes(snapshot.totalAppBytes),
                    style = MaterialTheme.typography.displaySmall.copy(fontWeight = FontWeight.Bold, fontSize = 32.sp),
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "total app footprint",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 6.dp)
                )
            }

            // Segmented Proportional Storage Bar
            M3SegmentedStorageBar(
                categories = snapshot.categories,
                totalBytes = snapshot.totalAppBytes
            )

            // Category Mini Legend Chips
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                snapshot.categories.take(4).forEach { cat ->
                    if (cat.bytes > 0) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(getCategoryColor(cat.id))
                            )
                            Text(
                                text = cat.label.split(" ").first(),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}

// ── M3SegmentedStorageBar ──

@Composable
private fun M3SegmentedStorageBar(
    categories: List<StorageCategoryUsage>,
    totalBytes: Long
) {
    val total = if (totalBytes > 0) totalBytes.toFloat() else 1f

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(14.dp)
            .clip(RoundedCornerShape(7.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
        horizontalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        if (categories.isEmpty() || totalBytes == 0L) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Glass.BorderSubtle)
            )
        } else {
            categories.forEach { category ->
                if (category.bytes > 0) {
                    val weight = (category.bytes.toFloat() / total).coerceAtLeast(0.02f)
                    Box(
                        modifier = Modifier
                            .weight(weight)
                            .fillMaxHeight()
                            .background(getCategoryColor(category.id))
                    )
                }
            }
        }
    }
}

// ── M3StorageCategoryRow ──

@Composable
private fun M3StorageCategoryRow(
    category: StorageCategoryUsage,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(horizontal = Standards.SpacingLg, vertical = Standards.SpacingMd),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Standards.SpacingMd)
    ) {
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = getCategoryColor(category.id).copy(alpha = 0.18f),
            modifier = Modifier.size(44.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = getCategoryIcon(category.id),
                    contentDescription = null,
                    tint = getCategoryColor(category.id),
                    modifier = Modifier.size(24.dp)
                )
            }
        }

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = category.label,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = getCategorySubtitle(category.id, category.fileCount),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Standards.SpacingXs)
        ) {
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            ) {
                Text(
                    text = formatBytes(category.bytes),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }
            Icon(
                Icons.Rounded.ChevronRight,
                contentDescription = "Inspect",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

// ── M3StorageFileRowItem ──

@Composable
private fun M3StorageFileRowItem(
    item: StorageFileItem,
    onDelete: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(Standards.RadiusMd),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, Glass.BorderSubtle)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Standards.SpacingMd, vertical = Standards.SpacingSm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Standards.SpacingSm)
        ) {
            // Extension Pill
            val ext = item.displayName.substringAfterLast('.', "FILE").uppercase().take(4)
            Surface(
                shape = RoundedCornerShape(6.dp),
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
                modifier = Modifier.size(36.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        text = ext,
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.displayName,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "${formatBytes(item.sizeBytes)} · ${DateFormat.format("MMM dd, yyyy", Date(item.lastModified))}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (item.isDeletable) {
                IconButton(
                    onClick = onDelete,
                    modifier = Modifier.size(34.dp)
                ) {
                    Icon(
                        Icons.Rounded.DeleteOutline,
                        contentDescription = "Delete",
                        tint = MaterialTheme.colorScheme.error.copy(alpha = 0.85f),
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
    }
}

// ── Color & Icon Helpers ──

private fun getCategoryIcon(id: String): ImageVector {
    return when (id) {
        AppStorageRepository.CATEGORY_MODELS -> Icons.Rounded.SmartToy
        AppStorageRepository.CATEGORY_VOICE -> Icons.Rounded.RecordVoiceOver
        AppStorageRepository.CATEGORY_WORKSPACE -> Icons.Rounded.Terminal
        AppStorageRepository.CATEGORY_SKILLS -> Icons.Rounded.Build
        AppStorageRepository.CATEGORY_VAULT -> Icons.Rounded.Memory
        AppStorageRepository.CATEGORY_RAGS -> Icons.AutoMirrored.Rounded.MenuBook
        AppStorageRepository.CATEGORY_DATABASE -> Icons.Rounded.Dataset
        AppStorageRepository.CATEGORY_CACHE -> Icons.Rounded.DeleteSweep
        else -> Icons.Rounded.Folder
    }
}

private fun getCategoryColor(id: String): Color {
    return when (id) {
        AppStorageRepository.CATEGORY_MODELS -> Color(0xFF64B5F6)     // Soft Blue
        AppStorageRepository.CATEGORY_VOICE -> Color(0xFF81C784)      // Soft Green
        AppStorageRepository.CATEGORY_WORKSPACE -> Color(0xFF26A69A)  // Teal Green
        AppStorageRepository.CATEGORY_SKILLS -> Color(0xFFAB47BC)     // Orchid Purple
        AppStorageRepository.CATEGORY_VAULT -> Color(0xFFFFB74D)      // Soft Orange
        AppStorageRepository.CATEGORY_RAGS -> Color(0xFFBA68C8)       // Soft Purple
        AppStorageRepository.CATEGORY_DATABASE -> Color(0xFF4DB6AC)   // Teal
        AppStorageRepository.CATEGORY_CACHE -> Color(0xFFE57373)      // Coral Red
        else -> Color.Gray
    }
}

private fun getCategorySubtitle(id: String, count: Int): String {
    return when (id) {
        AppStorageRepository.CATEGORY_MODELS -> "GGUF, VLM & diffusion weights ($count files)"
        AppStorageRepository.CATEGORY_VOICE -> "Kokoro, Piper & Whisper engines ($count files)"
        AppStorageRepository.CATEGORY_WORKSPACE -> "Rootfs filesystems & sandboxes ($count files)"
        AppStorageRepository.CATEGORY_SKILLS -> "Instruction skills & tool definitions ($count files)"
        AppStorageRepository.CATEGORY_VAULT -> "Episodic memory & vector embeddings ($count files)"
        AppStorageRepository.CATEGORY_RAGS -> "Document chunks & vector indices ($count files)"
        AppStorageRepository.CATEGORY_DATABASE -> "Chat SQLite records & tool traces ($count files)"
        AppStorageRepository.CATEGORY_CACHE -> "Prompt caches, Coil & temp files ($count files)"
        else -> "$count files"
    }
}
