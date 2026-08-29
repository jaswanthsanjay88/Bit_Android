package com.bit.ui.screen.workspace

import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bit.global.Standards
import com.bit.models.table_schema.WorkspaceEntity
import com.bit.repo.WorkspaceRepository
import com.bit.ui.components.ItemPosition
import com.bit.ui.components.PhysicsSwipeToDelete
import com.bit.ui.icons.TnIcons
import com.bit.ui.theme.LocalBitHaptics
import com.bit.ui.theme.bouncyClick
import com.bit.viewmodel.WorkspaceDetailViewModel
import kotlinx.coroutines.launch
import me.rerere.workspace.RootfsInstallStage
import androidx.activity.compose.BackHandler
import me.rerere.workspace.WorkspaceFileEntry
import me.rerere.workspace.WorkspaceShellStatus
import me.rerere.workspace.WorkspaceStorageArea

@Composable
fun WorkspaceDetailPage(
    workspaceId: String,
    onBack: () -> Unit,
    onOpenTerminal: (WorkspaceEntity, String?) -> Unit,
    onEditingFileChanged: (Boolean) -> Unit = {},
    viewModel: WorkspaceDetailViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val bitHaptics = LocalBitHaptics.current
    val scope = rememberCoroutineScope()

    LaunchedEffect(workspaceId) {
        viewModel.loadWorkspace(workspaceId)
    }

    val workspace by viewModel.workspace.collectAsStateWithLifecycle()
    val files by viewModel.files.collectAsStateWithLifecycle()
    val currentPath by viewModel.currentPath.collectAsStateWithLifecycle()
    val currentArea by viewModel.currentArea.collectAsStateWithLifecycle()
    val installProgress by viewModel.installProgress.collectAsStateWithLifecycle()
    val installError by viewModel.installError.collectAsStateWithLifecycle()
    val isInstalling by viewModel.isInstalling.collectAsStateWithLifecycle()
    val availableDistros by viewModel.availableDistros.collectAsStateWithLifecycle()
    val installedDistro by viewModel.installedDistro.collectAsStateWithLifecycle()

    var selectedTab by remember { mutableIntStateOf(0) }
    var editingFile by remember { mutableStateOf<WorkspaceFileEntry?>(null) }

    LaunchedEffect(editingFile) {
        onEditingFileChanged(editingFile != null)
    }

    BackHandler(enabled = editingFile != null) {
        editingFile = null
        onEditingFileChanged(false)
    }

    var showCreateFileDialog by remember { mutableStateOf(false) }
    var showCustomUrlDialog by remember { mutableStateOf(false) }
    var showProcessesSheet by remember { mutableStateOf(false) }
    var pendingDeleteFile by remember { mutableStateOf<WorkspaceFileEntry?>(null) }

    val allProcesses by com.bit.workspace.WorkspaceProcessManager.processes.collectAsStateWithLifecycle()
    val activeProcessesCount = remember(allProcesses, workspaceId) {
        allProcesses.count { it.workspaceId == workspaceId && it.status == com.bit.workspace.WorkspaceProcessStatus.RUNNING }
    }

    // SAF Import Launcher
    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            val fileName = queryFileName(context, uri) ?: "imported_file"
            viewModel.importFile(fileName, uri) { result ->
                result.onSuccess {
                    Toast.makeText(context, "Imported $fileName", Toast.LENGTH_SHORT).show()
                }.onFailure {
                    Toast.makeText(context, "Import failed: ${it.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    // SAF Export Launcher
    var pendingExportEntry by remember { mutableStateOf<WorkspaceFileEntry?>(null) }
    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("*/*")
    ) { uri: Uri? ->
        val entry = pendingExportEntry
        if (uri != null && entry != null) {
            viewModel.exportFile(entry, uri) { result ->
                result.onSuccess {
                    Toast.makeText(context, "Exported ${entry.name}", Toast.LENGTH_SHORT).show()
                }.onFailure {
                    Toast.makeText(context, "Export failed: ${it.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
        pendingExportEntry = null
    }

    val ws = workspace
    if (ws == null) {
        Box(
            modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
            contentAlignment = Alignment.Center
        ) {
            CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
        }
        return
    }

    val currentEditing = editingFile
    if (currentEditing != null) {
        WorkspaceFileEditorPage(
            workspaceId = workspaceId,
            area = currentArea,
            path = currentEditing.path,
            onBack = {
                editingFile = null
                onEditingFileChanged(false)
            },
            viewModel = viewModel
        )
        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = Standards.SpacingMd, vertical = Standards.SpacingSm)
    ) {
        // ── TOP HEADER (Container Summary & Terminal / Processes Action) ──
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = ws.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "PRoot Sandbox Container",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            FilledTonalButton(
                onClick = {
                    bitHaptics.pop()
                    showProcessesSheet = true
                },
                shape = RoundedCornerShape(12.dp),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
            ) {
                if (activeProcessesCount > 0) {
                    Box(
                        modifier = Modifier
                            .size(7.dp)
                            .background(Color(0xFF22C55E), CircleShape)
                    )
                    Spacer(Modifier.width(6.dp))
                }
                Icon(TnIcons.Terminal, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text(
                    text = if (activeProcessesCount > 0) "Processes ($activeProcessesCount)" else "Processes",
                    style = MaterialTheme.typography.labelMedium
                )
            }
        }

        Spacer(Modifier.height(10.dp))

        // ── TABS ──
        PrimaryTabRow(
            selectedTabIndex = selectedTab,
            containerColor = MaterialTheme.colorScheme.background,
            contentColor = MaterialTheme.colorScheme.primary,
            divider = { HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f)) }
        ) {
            Tab(
                selected = selectedTab == 0,
                onClick = {
                    bitHaptics.selection()
                    selectedTab = 0
                },
                text = { Text("Environment & Rootfs", style = MaterialTheme.typography.labelMedium) },
                icon = { Icon(Icons.Rounded.Settings, contentDescription = null, modifier = Modifier.size(18.dp)) },
            )
            Tab(
                selected = selectedTab == 1,
                onClick = {
                    bitHaptics.selection()
                    selectedTab = 1
                },
                text = { Text("Files (${files.size})", style = MaterialTheme.typography.labelMedium) },
                icon = { Icon(TnIcons.Folder, contentDescription = null, modifier = Modifier.size(18.dp)) },
            )
        }

        Spacer(Modifier.height(12.dp))

        AnimatedContent(
            targetState = selectedTab,
            transitionSpec = {
                if (targetState > initialState) {
                    (slideInHorizontally(
                        animationSpec = androidx.compose.animation.core.tween(300, easing = com.bit.ui.theme.Motion.EmphasizedDecelerate),
                        initialOffsetX = { (it * 0.20f).toInt() }
                    ) + fadeIn(androidx.compose.animation.core.tween(200, easing = com.bit.ui.theme.Motion.EmphasizedDecelerate))) togetherWith
                    (slideOutHorizontally(
                        animationSpec = androidx.compose.animation.core.tween(250, easing = com.bit.ui.theme.Motion.EmphasizedAccelerate),
                        targetOffsetX = { -(it * 0.12f).toInt() }
                    ) + fadeOut(androidx.compose.animation.core.tween(150, easing = com.bit.ui.theme.Motion.EmphasizedAccelerate)))
                } else {
                    (slideInHorizontally(
                        animationSpec = androidx.compose.animation.core.tween(300, easing = com.bit.ui.theme.Motion.EmphasizedDecelerate),
                        initialOffsetX = { -(it * 0.20f).toInt() }
                    ) + fadeIn(androidx.compose.animation.core.tween(200, easing = com.bit.ui.theme.Motion.EmphasizedDecelerate))) togetherWith
                    (slideOutHorizontally(
                        animationSpec = androidx.compose.animation.core.tween(250, easing = com.bit.ui.theme.Motion.EmphasizedAccelerate),
                        targetOffsetX = { (it * 0.12f).toInt() }
                    ) + fadeOut(androidx.compose.animation.core.tween(150, easing = com.bit.ui.theme.Motion.EmphasizedAccelerate)))
                }
            },
            label = "workspace_tab_content"
        ) { tab ->
            when (tab) {
                0 -> {
                    EnvironmentTabContent(
                        workspace = ws,
                        installedDistro = installedDistro,
                        availableDistros = availableDistros,
                        isInstalling = isInstalling,
                        installProgress = installProgress,
                        installError = installError,
                        onInstallDistro = { distro ->
                            bitHaptics.pop()
                            viewModel.installRootfs(distro.downloadUrl)
                        },
                        onCustomUrl = {
                            bitHaptics.pop()
                            showCustomUrlDialog = true
                        },
                        onToolApprovalChange = { tool, needsApproval ->
                            bitHaptics.selection()
                            viewModel.setToolApproval(tool, needsApproval)
                        },
                        onOpenTerminal = { onOpenTerminal(ws, null) },
                    )
                }
                1 -> {
                    FilesTabContent(
                        currentPath = currentPath,
                        currentArea = currentArea,
                        files = files,
                        onAreaChange = { viewModel.switchArea(it) },
                        onOpenDir = {
                            bitHaptics.selection()
                            viewModel.openDirectory(it)
                        },
                        onGoUp = {
                            bitHaptics.selection()
                            viewModel.goUp()
                        },
                        onRefresh = {
                            bitHaptics.pop()
                            viewModel.refresh()
                        },
                        onNewFile = {
                            bitHaptics.pop()
                            showCreateFileDialog = true
                        },
                        onImport = {
                            bitHaptics.pop()
                            importLauncher.launch(arrayOf("*/*"))
                        },
                        onOpenFile = { entry ->
                            bitHaptics.selection()
                            when (entry.detectFileType()) {
                                WorkspaceFileType.TEXT -> {
                                    editingFile = entry
                                }
                                WorkspaceFileType.IMAGE -> {
                                    Toast.makeText(context, "Image file: ${entry.name}", Toast.LENGTH_SHORT).show()
                                }
                                WorkspaceFileType.OTHER -> {
                                    editingFile = entry
                                }
                            }
                        },
                        onExportFile = { entry ->
                            bitHaptics.pop()
                            pendingExportEntry = entry
                            exportLauncher.launch(entry.name)
                        },
                        onDeleteFile = {
                            bitHaptics.pop()
                            pendingDeleteFile = it
                        },
                    )
                }
            }
        }
    }

    // Delete File/Folder Confirmation Dialog
    if (pendingDeleteFile != null) {
        val target = pendingDeleteFile!!
        AlertDialog(
            onDismissRequest = { pendingDeleteFile = null },
            icon = {
                Icon(
                    Icons.Rounded.Delete,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(28.dp)
                )
            },
            title = {
                Text(if (target.isDirectory) "Delete Folder?" else "Delete File?")
            },
            text = {
                Text(
                    if (target.isDirectory)
                        "Are you sure you want to permanently delete folder \"${target.name}\" and all containing files? This cannot be undone."
                    else
                        "Are you sure you want to permanently delete \"${target.name}\"? This cannot be undone."
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        bitHaptics.thud()
                        viewModel.deleteFile(target)
                        Toast.makeText(context, "Deleted \"${target.name}\"", Toast.LENGTH_SHORT).show()
                        pendingDeleteFile = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { pendingDeleteFile = null },
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("Cancel")
                }
            },
            shape = RoundedCornerShape(24.dp)
        )
    }

    // Custom URL Dialog
    if (showCustomUrlDialog) {
        var customUrl by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showCustomUrlDialog = false },
            title = { Text("Install Custom Rootfs") },
            text = {
                Column {
                    Text(
                        text = "Enter direct HTTP/HTTPS link to a .tar.xz or .tar.gz rootfs archive.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedTextField(
                        value = customUrl,
                        onValueChange = { customUrl = it },
                        label = { Text("Rootfs URL") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (customUrl.isNotBlank()) {
                            showCustomUrlDialog = false
                            viewModel.installRootfs(customUrl.trim())
                        }
                    },
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text("Install")
                }
            },
            dismissButton = {
                TextButton(onClick = { showCustomUrlDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Create File Dialog
    if (showCreateFileDialog) {
        var newFileName by remember { mutableStateOf("") }
        var initialContent by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showCreateFileDialog = false },
            title = { Text("New Workspace File") },
            text = {
                Column {
                    OutlinedTextField(
                        value = newFileName,
                        onValueChange = { newFileName = it },
                        label = { Text("File name (e.g. main.py, test.sh)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = initialContent,
                        onValueChange = { initialContent = it },
                        label = { Text("Content (Optional)") },
                        minLines = 3,
                        maxLines = 6,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (newFileName.isNotBlank()) {
                            val relPath = if (currentPath.isBlank()) newFileName.trim() else "$currentPath/${newFileName.trim()}"
                            scope.launch {
                                viewModel.writeText(relPath, initialContent)
                                showCreateFileDialog = false
                            }
                        }
                    },
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text("Create")
                }
            },
            dismissButton = {
                TextButton(onClick = { showCreateFileDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    if (showProcessesSheet) {
        WorkspaceProcessesSheet(
            workspace = ws,
            onDismiss = { showProcessesSheet = false },
            onOpenTerminal = { targetProcId ->
                showProcessesSheet = false
                onOpenTerminal(ws, targetProcId)
            }
        )
    }
}

@Composable
private fun EnvironmentTabContent(
    workspace: WorkspaceEntity,
    installedDistro: com.bit.repo.InstalledDistroInfo?,
    availableDistros: List<com.bit.repo.LinuxDistro>,
    isInstalling: Boolean,
    installProgress: me.rerere.workspace.RootfsInstallProgress?,
    installError: String?,
    onInstallDistro: (com.bit.repo.LinuxDistro) -> Unit,
    onCustomUrl: () -> Unit,
    onToolApprovalChange: (String, Boolean) -> Unit,
    onOpenTerminal: () -> Unit,
) {
    val bitHaptics = LocalBitHaptics.current
    var showDistroSwitcher by remember { mutableStateOf(false) }
    val isReady = workspace.shellStatus == WorkspaceShellStatus.READY.name && installedDistro != null

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        // ── 1. ACTIVE RUNNING ENVIRONMENT HERO CARD ──
        if (isReady) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(22.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                border = BorderStroke(
                    1.dp,
                    if (installedDistro.isUbuntu) Color(0x66E95420)
                    else if (installedDistro.isAlpine) Color(0x660D597F)
                    else if (installedDistro.isDebian) Color(0x66D70A53)
                    else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)
                )
            ) {
                Column(modifier = Modifier.padding(18.dp)) {
                    // Header Row: Brand + Status Pill
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            val distroIconRes = if (installedDistro.isUbuntu) com.bit.R.drawable.ic_distro_ubuntu
                            else if (installedDistro.isAlpine) com.bit.R.drawable.ic_distro_alpine
                            else if (installedDistro.isDebian) com.bit.R.drawable.ic_distro_debian
                            else com.bit.R.drawable.ic_distro_linux

                            Icon(
                                painter = androidx.compose.ui.res.painterResource(id = distroIconRes),
                                contentDescription = installedDistro.prettyName,
                                modifier = Modifier
                                    .size(38.dp)
                                    .clip(RoundedCornerShape(10.dp)),
                                tint = Color.Unspecified
                            )
                            Column {
                                Text(
                                    text = installedDistro.prettyName,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = "${installedDistro.arch} • PRoot Container",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        // Live status pill
                        Surface(
                            color = Color(0x2222C55E),
                            shape = RoundedCornerShape(100.dp),
                            border = BorderStroke(1.dp, Color(0x4422C55E))
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(7.dp)
                                        .background(Color(0xFF22C55E), CircleShape)
                                )
                                Text(
                                    text = "ACTIVE",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Color(0xFF4ADE80),
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }

                    Spacer(Modifier.height(14.dp))

                    // Spec Grid (Storage, Package Manager, Architecture)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.5f), RoundedCornerShape(14.dp))
                            .padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text("Package Manager", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(installedDistro.packageManager, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                        }
                        Column {
                            Text("Rootfs Disk Usage", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(installedDistro.sizeText, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                        }
                        Column {
                            Text("Architecture", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(installedDistro.arch, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                        }
                    }

                    Spacer(Modifier.height(14.dp))

                    // Action buttons
                    Button(
                        onClick = onOpenTerminal,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Icon(TnIcons.Terminal, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Launch Interactive Terminal")
                    }

                    Spacer(Modifier.height(8.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TextButton(
                            onClick = {
                                bitHaptics.selection()
                                showDistroSwitcher = !showDistroSwitcher
                            }
                        ) {
                            Icon(
                                if (showDistroSwitcher) TnIcons.ChevronUp else TnIcons.ChevronDown,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(if (showDistroSwitcher) "Hide OS Catalog" else "Reinstall / Switch Distribution")
                        }

                        TextButton(onClick = onCustomUrl) {
                            Text("Custom Rootfs URL", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }

        // ── 2. INSTALLING / PROGRESS CARD ──
        if (isInstalling) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(22.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.5f))
            ) {
                Column(modifier = Modifier.padding(18.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.5.dp,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = "Configuring Linux Environment",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }

                    Spacer(Modifier.height(14.dp))

                    val stageName = when (installProgress?.stage) {
                        RootfsInstallStage.DOWNLOADING -> "Downloading rootfs archive..."
                        RootfsInstallStage.EXTRACTING -> "Extracting filesystem (${installProgress.entriesExtracted} files)..."
                        RootfsInstallStage.CONFIGURING -> "Configuring sandbox & pre-installing Python 3..."
                        RootfsInstallStage.INSTALLED -> "Installation complete! Finalizing..."
                        null -> "Preparing environment..."
                    }

                    Text(
                        text = stageName,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    val total = installProgress?.totalBytes
                    val read = installProgress?.bytesRead ?: 0L
                    if (total != null && total > 0) {
                        val progress = (read.toFloat() / total.toFloat()).coerceIn(0f, 1f)
                        LinearProgressIndicator(
                            progress = { progress },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "${read / (1024 * 1024)} MB / ${total / (1024 * 1024)} MB (${(progress * 100).toInt()}%)",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                }
            }
        }

        // ── 3. DISTRIBUTION SELECTION SECTION ──
        if (!isInstalling && (!isReady || showDistroSwitcher)) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(22.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
            ) {
                Column(modifier = Modifier.padding(18.dp)) {
                    Text(
                        text = if (isReady) "Available Linux Distributions" else "Select Linux Distribution",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = "Choose an isolated on-device rootfs to run compilers, scripts, Python, C++, Node.js, and package managers.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        lineHeight = 18.sp
                    )

                    Spacer(modifier = Modifier.height(14.dp))

                    // Render each distribution card
                    availableDistros.forEach { distro ->
                        LinuxDistroItemCard(
                            distro = distro,
                            onInstall = { onInstallDistro(distro) }
                        )
                        Spacer(Modifier.height(10.dp))
                    }

                    // Custom URL button
                    OutlinedButton(
                        onClick = onCustomUrl,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    ) {
                        Icon(TnIcons.Link, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Custom Rootfs URL (.tar.xz / .tar.gz)")
                    }

                    installError?.let { err ->
                        Spacer(modifier = Modifier.height(10.dp))
                        Surface(
                            color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text(
                                text = "Error: $err",
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(12.dp)
                            )
                        }
                    }
                }
            }
        }

        // ── 4. TOOL APPROVALS & PERMISSIONS ──
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(22.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
        ) {
            Column(modifier = Modifier.padding(18.dp)) {
                Text(
                    text = "Agent Permissions & Approvals",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = "Require manual approval before AI executes specific actions in this workspace.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Spacer(modifier = Modifier.height(12.dp))

                val approvals = workspace.toolApprovalOverrides()
                ToolApprovalRow(
                    title = "Run Shell Commands (workspace_shell)",
                    checked = approvals["workspace_shell"] ?: true,
                    onCheckedChange = { onToolApprovalChange("workspace_shell", it) }
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f))
                ToolApprovalRow(
                    title = "Write Files (workspace_write_file)",
                    checked = approvals["workspace_write_file"] ?: false,
                    onCheckedChange = { onToolApprovalChange("workspace_write_file", it) }
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f))
                ToolApprovalRow(
                    title = "Edit Files (workspace_edit_file)",
                    checked = approvals["workspace_edit_file"] ?: false,
                    onCheckedChange = { onToolApprovalChange("workspace_edit_file", it) }
                )
            }
        }
    }
}

@Composable
private fun LinuxDistroItemCard(
    distro: com.bit.repo.LinuxDistro,
    onInstall: () -> Unit
) {
    val brandColor = when (distro.id) {
        "ubuntu" -> Color(0xFFE95420)
        "alpine" -> Color(0xFF0D597F)
        "debian" -> Color(0xFFD70A53)
        else -> MaterialTheme.colorScheme.primary
    }

    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.6f),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, if (distro.isDownloaded) Color(0x5522C55E) else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            // Header Row: Distro Icon + Title + Status Pill
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    val itemIconRes = when (distro.id) {
                        "ubuntu" -> com.bit.R.drawable.ic_distro_ubuntu
                        "alpine" -> com.bit.R.drawable.ic_distro_alpine
                        "debian" -> com.bit.R.drawable.ic_distro_debian
                        else -> com.bit.R.drawable.ic_distro_linux
                    }

                    Icon(
                        painter = androidx.compose.ui.res.painterResource(id = itemIconRes),
                        contentDescription = distro.name,
                        modifier = Modifier
                            .size(34.dp)
                            .clip(RoundedCornerShape(8.dp)),
                        tint = Color.Unspecified
                    )

                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                text = distro.name,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Surface(
                                color = MaterialTheme.colorScheme.surfaceVariant,
                                shape = RoundedCornerShape(4.dp)
                            ) {
                                Text(
                                    text = distro.version,
                                    modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        Text(
                            text = distro.tag,
                            style = MaterialTheme.typography.labelSmall,
                            color = brandColor,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }

                // Download Status Pill
                if (distro.isDownloaded) {
                    Surface(
                        color = Color(0x2222C55E),
                        shape = RoundedCornerShape(100.dp),
                        border = BorderStroke(1.dp, Color(0x4422C55E))
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(
                                TnIcons.Check,
                                contentDescription = null,
                                tint = Color(0xFF4ADE80),
                                modifier = Modifier.size(12.dp)
                            )
                            Text(
                                text = "DOWNLOADED",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color(0xFF4ADE80),
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                } else {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        shape = RoundedCornerShape(100.dp)
                    ) {
                        Text(
                            text = distro.sizeText,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            Spacer(Modifier.height(8.dp))

            Text(
                text = distro.description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                lineHeight = 16.sp
            )

            Spacer(Modifier.height(12.dp))

            // Action button
            if (distro.isDownloaded) {
                Button(
                    onClick = onInstall,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF166534))
                ) {
                    Icon(TnIcons.CircleCheck, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Instant Install (Ready on device)", color = Color.White, fontWeight = FontWeight.SemiBold)
                }
            } else {
                FilledTonalButton(
                    onClick = onInstall,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(TnIcons.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Download & Install (${distro.sizeText})")
                }
            }
        }
    }
}

@Composable
private fun ToolApprovalRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
        )
    }
}

@Composable
private fun FilesTabContent(
    currentPath: String,
    currentArea: WorkspaceStorageArea,
    files: List<WorkspaceFileEntry>,
    onAreaChange: (WorkspaceStorageArea) -> Unit,
    onOpenDir: (String) -> Unit,
    onGoUp: () -> Unit,
    onRefresh: () -> Unit,
    onNewFile: () -> Unit,
    onImport: () -> Unit,
    onOpenFile: (WorkspaceFileEntry) -> Unit,
    onExportFile: (WorkspaceFileEntry) -> Unit,
    onDeleteFile: (WorkspaceFileEntry) -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        // Storage Area Toggle Chips
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilterChip(
                selected = currentArea == WorkspaceStorageArea.FILES,
                onClick = { onAreaChange(WorkspaceStorageArea.FILES) },
                label = { Text("Project (/workspace)") },
                leadingIcon = { Icon(TnIcons.Folder, contentDescription = null, modifier = Modifier.size(16.dp)) },
                shape = RoundedCornerShape(10.dp),
            )
            FilterChip(
                selected = currentArea == WorkspaceStorageArea.LINUX,
                onClick = { onAreaChange(WorkspaceStorageArea.LINUX) },
                label = { Text("Linux System (/)") },
                leadingIcon = { Icon(TnIcons.Terminal, contentDescription = null, modifier = Modifier.size(16.dp)) },
                shape = RoundedCornerShape(10.dp),
            )
        }

        Spacer(Modifier.height(8.dp))

        // Path and Navigation Bar Card
        val basePath = if (currentArea == WorkspaceStorageArea.FILES) "/workspace" else ""
        val displayPath = if (currentPath.isBlank()) (if (basePath.isBlank()) "/" else basePath) else "$basePath/$currentPath"

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(
                    onClick = onGoUp,
                    enabled = currentPath.isNotBlank(),
                ) {
                    Icon(Icons.Rounded.ArrowUpward, contentDescription = "Up")
                }

                Text(
                    text = displayPath,
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )

                if (currentArea == WorkspaceStorageArea.FILES) {
                    IconButton(onClick = onNewFile) {
                        Icon(Icons.Rounded.Add, contentDescription = "New File")
                    }
                    IconButton(onClick = onImport) {
                        Icon(Icons.Rounded.FileUpload, contentDescription = "Import")
                    }
                }
                IconButton(onClick = onRefresh) {
                    Icon(Icons.Rounded.Refresh, contentDescription = "Refresh")
                }
            }
        }

        Spacer(Modifier.height(10.dp))

        if (files.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.padding(24.dp)
                ) {
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f),
                        modifier = Modifier.size(52.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                if (currentArea == WorkspaceStorageArea.FILES) TnIcons.Folder else TnIcons.Terminal,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(26.dp),
                            )
                        }
                    }
                    Text(
                        text = if (currentArea == WorkspaceStorageArea.FILES) "No files in /workspace yet" else "Linux Rootfs is not populated",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = if (currentArea == WorkspaceStorageArea.FILES)
                            "Create Python/C/Bash scripts with '+ New File' or import existing project files."
                        else
                            "Install Alpine or Ubuntu from the Environment tab to browse system files.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        lineHeight = 18.sp
                    )
                    if (currentArea == WorkspaceStorageArea.FILES) {
                        Spacer(Modifier.height(4.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                onClick = onImport,
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Icon(Icons.Rounded.FileUpload, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Import File")
                            }
                            Button(
                                onClick = onNewFile,
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("New File")
                            }
                        }
                    }
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                itemsIndexed(files, key = { _, it -> it.path }) { index, entry ->
                    val position = when {
                        files.size == 1 -> ItemPosition.ONLY
                        index == 0 -> ItemPosition.FIRST
                        index == files.lastIndex -> ItemPosition.LAST
                        else -> ItemPosition.MIDDLE
                    }

                    PhysicsSwipeToDelete(
                        onDelete = {
                            onDeleteFile(entry)
                        },
                        position = position,
                        modifier = Modifier.animateItem()
                    ) { cardShape ->
                        FileEntryRow(
                            entry = entry,
                            shape = cardShape,
                            onClick = {
                                if (entry.isDirectory) {
                                    onOpenDir(entry.path)
                                } else {
                                    onOpenFile(entry)
                                }
                            },
                            onExport = { onExportFile(entry) },
                            onDelete = { onDeleteFile(entry) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun FileEntryRow(
    entry: WorkspaceFileEntry,
    shape: androidx.compose.ui.graphics.Shape = RoundedCornerShape(14.dp),
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    onExport: () -> Unit,
    onDelete: () -> Unit,
) {
    var showMenu by remember { mutableStateOf(false) }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .clickable(onClick = onClick),
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = if (entry.isDirectory) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f) else MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier.size(38.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = if (entry.isDirectory) TnIcons.Folder else TnIcons.Code,
                        contentDescription = null,
                        tint = if (entry.isDirectory) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = entry.name,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (entry.isDirectory) FontWeight.SemiBold else FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (!entry.isDirectory) {
                    Text(
                        text = formatFileSize(entry.sizeBytes),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Box {
                IconButton(onClick = { showMenu = true }, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Rounded.MoreVert, contentDescription = "More", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                DropdownMenu(
                    expanded = showMenu,
                    onDismissRequest = { showMenu = false },
                ) {
                    if (!entry.isDirectory) {
                        DropdownMenuItem(
                            text = { Text("Export via SAF") },
                            leadingIcon = { Icon(Icons.Rounded.FileDownload, contentDescription = null) },
                            onClick = {
                                showMenu = false
                                onExport()
                            }
                        )
                    }
                    DropdownMenuItem(
                        text = { Text("Delete", color = MaterialTheme.colorScheme.error) },
                        leadingIcon = { Icon(Icons.Rounded.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
                        onClick = {
                            showMenu = false
                            onDelete()
                        }
                    )
                }
            }
        }
    }
}

private fun formatFileSize(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val exp = (Math.log(bytes.toDouble()) / Math.log(1024.0)).toInt()
    val pre = "KMGTPE"[exp - 1]
    return String.format("%.1f %sB", bytes / Math.pow(1024.0, exp.toDouble()), pre)
}

private fun queryFileName(context: Context, uri: Uri): String? {
    val cursor = context.contentResolver.query(uri, null, null, null, null) ?: return null
    return cursor.use {
        if (it.moveToFirst()) {
            val nameIndex = it.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (nameIndex >= 0) it.getString(nameIndex) else null
        } else null
    }
}
