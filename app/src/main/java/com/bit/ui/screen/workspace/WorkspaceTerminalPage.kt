package com.bit.ui.screen.workspace

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bit.models.table_schema.WorkspaceEntity
import com.bit.repo.WorkspaceRepository
import com.bit.ui.icons.TnIcons
import com.bit.ui.theme.LocalBitHaptics
import com.bit.viewmodel.WorkspaceDetailViewModel
import com.termux.terminal.TerminalSession
import com.termux.view.TerminalView
import me.rerere.workspace.RootfsInstallStage

@Composable
fun WorkspaceTerminalPage(
    workspace: WorkspaceEntity,
    targetProcessId: String? = null,
    onBack: () -> Unit,
    viewModel: WorkspaceDetailViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val bitHaptics = LocalBitHaptics.current

    LaunchedEffect(workspace.id) {
        viewModel.loadWorkspace(workspace.id)
    }

    val installProgress by viewModel.installProgress.collectAsStateWithLifecycle()
    val isInstalling by viewModel.isInstalling.collectAsStateWithLifecycle()
    val installError by viewModel.installError.collectAsStateWithLifecycle()
    val availableDistros by viewModel.availableDistros.collectAsStateWithLifecycle()

    val allProcesses by com.bit.workspace.WorkspaceProcessManager.processes.collectAsStateWithLifecycle()
    val interactiveProcesses = remember(allProcesses, workspace.id) {
        allProcesses.filter {
            it.workspaceId == workspace.id &&
            it.type == com.bit.workspace.WorkspaceProcessType.INTERACTIVE_TERMINAL &&
            it.status == com.bit.workspace.WorkspaceProcessStatus.RUNNING
        }
    }

    var currentProcessId by remember(targetProcessId) { mutableStateOf(targetProcessId) }
    var sessionFinished by remember { mutableStateOf(false) }

    // Obtain or create active session holder from pool
    val currentHolder = remember(currentProcessId, workspace.id, workspace.root) {
        WorkspaceTerminalSessionPool.getOrCreateSession(
            context = context,
            workspaceId = workspace.id,
            workspaceRoot = workspace.root,
            targetProcessId = currentProcessId,
            forceNew = false,
            onSessionFinished = { finishedId ->
                if (finishedId == currentProcessId) {
                    sessionFinished = true
                }
            }
        )
    }

    // Keep currentProcessId synced with active holder
    LaunchedEffect(currentHolder?.processId) {
        currentHolder?.let {
            if (currentProcessId != it.processId) {
                currentProcessId = it.processId
            }
        }
    }

    var ctrlActive by remember { mutableStateOf(false) }
    var altActive by remember { mutableStateOf(false) }

    val rootfsReady = remember(workspace.root, isInstalling) {
        workspaceRootfsReady(context, workspace.root)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        // ── TOP TERMINAL CONTROLS BAR ──
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceContainer)
                .padding(horizontal = 8.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                IconButton(
                    onClick = {
                        bitHaptics.pop()
                        onBack()
                    },
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        Icons.AutoMirrored.Rounded.ArrowBack,
                        contentDescription = "Back",
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(18.dp)
                    )
                }

                Surface(
                    shape = CircleShape,
                    color = if (rootfsReady && (currentHolder?.session?.isRunning == true)) Color(0xFF2E7D32) else Color(0xFFC62828),
                    modifier = Modifier.size(10.dp)
                ) {}
                Text(
                    text = "${workspace.name} (${currentHolder?.title ?: "bash"})",
                    style = MaterialTheme.typography.labelMedium,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.SemiBold
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                IconButton(
                    onClick = {
                        bitHaptics.pop()
                        currentHolder?.viewClient?.focusAndShowKeyboard()
                    },
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        Icons.Rounded.Keyboard,
                        contentDescription = "Keyboard",
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(18.dp)
                    )
                }

                IconButton(
                    onClick = {
                        bitHaptics.pop()
                        val newHolder = WorkspaceTerminalSessionPool.getOrCreateSession(
                            context = context,
                            workspaceId = workspace.id,
                            workspaceRoot = workspace.root,
                            forceNew = true,
                            onSessionFinished = { finishedId ->
                                if (finishedId == currentProcessId) {
                                    sessionFinished = true
                                }
                            }
                        )
                        if (newHolder != null) {
                            sessionFinished = false
                            currentProcessId = newHolder.processId
                        }
                    },
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        Icons.Rounded.Add,
                        contentDescription = "New Terminal",
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }

        // ── ACTIVE TERMINAL SESSIONS TABS ──
        if (interactiveProcesses.isNotEmpty()) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.6f),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    interactiveProcesses.forEach { proc ->
                        val isSelected = proc.id == (currentHolder?.processId ?: currentProcessId)
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHighest,
                            border = BorderStroke(1.dp, if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.5f) else Color.Transparent),
                            modifier = Modifier.clickable {
                                bitHaptics.pop()
                                sessionFinished = false
                                currentProcessId = proc.id
                            }
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Icon(
                                    TnIcons.Terminal,
                                    contentDescription = null,
                                    tint = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(14.dp)
                                )
                                Text(
                                    text = "Bash #${proc.sessionIndex}",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                    color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        }

        // ── ROOTFS INSTALL BANNER OR TERMINAL VIEW ──
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(Color.Black)
        ) {
            if (!rootfsReady) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f),
                        modifier = Modifier.size(64.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                TnIcons.Terminal,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(32.dp),
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    Text(
                        text = "Linux Rootfs Not Installed",
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "Install an on-device Linux environment to use this interactive terminal.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        lineHeight = 18.sp
                    )

                    Spacer(modifier = Modifier.height(20.dp))

                    if (isInstalling) {
                        Column(
                            modifier = Modifier.fillMaxWidth(0.9f),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            val stageName = when (installProgress?.stage) {
                                RootfsInstallStage.DOWNLOADING -> "Downloading rootfs archive..."
                                RootfsInstallStage.EXTRACTING -> "Extracting Linux rootfs (${installProgress?.entriesExtracted ?: 0} files)..."
                                RootfsInstallStage.CONFIGURING -> "Configuring sandbox & pre-installing Python 3..."
                                RootfsInstallStage.INSTALLED -> "Starting terminal session..."
                                null -> "Preparing environment..."
                            }
                            Text(
                                text = stageName,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.SemiBold
                            )
                            Spacer(Modifier.height(8.dp))
                            val total = installProgress?.totalBytes
                            val read = installProgress?.bytesRead ?: 0L
                            if (total != null && total > 0) {
                                val progress = (read.toFloat() / total.toFloat()).coerceIn(0f, 1f)
                                LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                            } else {
                                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                            }
                        }
                    } else {
                        Column(
                            modifier = Modifier.fillMaxWidth(0.95f),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            availableDistros.forEach { distro ->
                                Surface(
                                    color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.5f),
                                    shape = RoundedCornerShape(14.dp),
                                    border = BorderStroke(1.dp, if (distro.isDownloaded) Color(0x5522C55E) else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(12.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                                            modifier = Modifier.weight(1f)
                                        ) {
                                            val distroIcon = when (distro.id) {
                                                "ubuntu" -> com.bit.R.drawable.ic_distro_ubuntu
                                                "alpine" -> com.bit.R.drawable.ic_distro_alpine
                                                "debian" -> com.bit.R.drawable.ic_distro_debian
                                                else -> com.bit.R.drawable.ic_distro_linux
                                            }

                                            Icon(
                                                painter = androidx.compose.ui.res.painterResource(id = distroIcon),
                                                contentDescription = distro.name,
                                                modifier = Modifier
                                                    .size(34.dp)
                                                    .clip(RoundedCornerShape(8.dp)),
                                                tint = Color.Unspecified
                                            )
                                            Column {
                                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                                    Text(
                                                        text = distro.name,
                                                        style = MaterialTheme.typography.bodyMedium,
                                                        fontWeight = FontWeight.Bold,
                                                        color = Color.White
                                                    )
                                                    if (distro.isDownloaded) {
                                                        Surface(
                                                            color = Color(0x2222C55E),
                                                            shape = RoundedCornerShape(100.dp)
                                                        ) {
                                                            Text(
                                                                text = "DOWNLOADED",
                                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                                                style = MaterialTheme.typography.labelSmall,
                                                                color = Color(0xFF4ADE80),
                                                                fontWeight = FontWeight.Bold
                                                            )
                                                        }
                                                    }
                                                }
                                                Text(
                                                    text = "${distro.version} • ${distro.sizeText}",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                        }

                                        if (distro.isDownloaded) {
                                            Button(
                                                onClick = {
                                                    bitHaptics.pop()
                                                    viewModel.installRootfs(distro.downloadUrl)
                                                },
                                                shape = RoundedCornerShape(10.dp),
                                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF166534)),
                                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                                            ) {
                                                Text("Instant Install", fontWeight = FontWeight.SemiBold)
                                            }
                                        } else {
                                            FilledTonalButton(
                                                onClick = {
                                                    bitHaptics.pop()
                                                    viewModel.installRootfs(distro.downloadUrl)
                                                },
                                                shape = RoundedCornerShape(10.dp),
                                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                                            ) {
                                                Text("Install")
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    installError?.let { err ->
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = "Error: $err",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            } else {
                key(currentHolder?.processId) {
                    AndroidView(
                        modifier = Modifier.fillMaxSize(),
                        factory = { ctx ->
                            TerminalView(ctx, null).apply {
                                setTextSize(36)
                                isFocusable = true
                                isFocusableInTouchMode = true
                                currentHolder?.let { h ->
                                    setTerminalViewClient(h.viewClient)
                                    h.viewClient.terminalView = this
                                    h.sessionClient.terminalView = this
                                    attachSession(h.session)
                                    postDelayed({ h.viewClient.focusAndShowKeyboard() }, 300)
                                }
                            }
                        },
                        update = { view ->
                            currentHolder?.let { h ->
                                view.setTerminalViewClient(h.viewClient)
                                h.viewClient.terminalView = view
                                h.sessionClient.terminalView = view
                                if (view.currentSession != h.session) {
                                    view.attachSession(h.session)
                                }
                            }
                        }
                    )
                }

                if (sessionFinished || (currentHolder?.session?.isRunning == false)) {
                    Surface(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(16.dp),
                        shape = RoundedCornerShape(10.dp),
                        color = Color(0xEE222222),
                        border = BorderStroke(1.dp, Color(0xFF444444)),
                        contentColor = Color.White,
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Text(
                                text = "Session exited",
                                fontSize = 12.sp,
                                fontFamily = FontFamily.Monospace,
                            )
                            Button(
                                onClick = {
                                    bitHaptics.pop()
                                    sessionFinished = false
                                    val newHolder = WorkspaceTerminalSessionPool.getOrCreateSession(
                                        context = context,
                                        workspaceId = workspace.id,
                                        workspaceRoot = workspace.root,
                                        forceNew = true,
                                        onSessionFinished = { finishedId ->
                                            if (finishedId == currentProcessId) {
                                                sessionFinished = true
                                            }
                                        }
                                    )
                                    if (newHolder != null) {
                                        currentProcessId = newHolder.processId
                                    }
                                },
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Text("Restart")
                            }
                        }
                    }
                }
            }
        }

        // ── VIRTUAL KEY BAR ──
        Surface(
            color = Color(0xFF141414),
            contentColor = Color.White,
            border = BorderStroke(1.dp, Color(0xFF222222))
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 6.dp, vertical = 4.dp)
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Control Key
                FilterChip(
                    selected = ctrlActive,
                    onClick = {
                        ctrlActive = !ctrlActive
                        currentHolder?.viewClient?.controlDown = ctrlActive
                    },
                    label = { Text("CTRL", fontFamily = FontFamily.Monospace, fontSize = 11.sp) },
                    colors = FilterChipDefaults.filterChipColors(
                        containerColor = Color(0xFF262626),
                        labelColor = Color.White,
                        selectedContainerColor = MaterialTheme.colorScheme.primary,
                        selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
                    )
                )

                // Alt Key
                FilterChip(
                    selected = altActive,
                    onClick = {
                        altActive = !altActive
                        currentHolder?.viewClient?.altDown = altActive
                    },
                    label = { Text("ALT", fontFamily = FontFamily.Monospace, fontSize = 11.sp) },
                    colors = FilterChipDefaults.filterChipColors(
                        containerColor = Color(0xFF262626),
                        labelColor = Color.White,
                        selectedContainerColor = MaterialTheme.colorScheme.primary,
                        selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
                    )
                )

                TerminalKeyButton(label = "TAB") {
                    sendKey(currentHolder?.session, 9)
                }

                TerminalKeyButton(label = "ESC") {
                    sendKey(currentHolder?.session, 27)
                }

                TerminalKeyButton(label = "▲") {
                    sendSpecialKey(currentHolder?.session, "\u001b[A")
                }

                TerminalKeyButton(label = "▼") {
                    sendSpecialKey(currentHolder?.session, "\u001b[B")
                }

                TerminalKeyButton(label = "◀") {
                    sendSpecialKey(currentHolder?.session, "\u001b[D")
                }

                TerminalKeyButton(label = "▶") {
                    sendSpecialKey(currentHolder?.session, "\u001b[C")
                }

                TerminalKeyButton(label = "|") {
                    currentHolder?.session?.write("|")
                }

                TerminalKeyButton(label = "/") {
                    currentHolder?.session?.write("/")
                }

                TerminalKeyButton(label = "-") {
                    currentHolder?.session?.write("-")
                }

                TerminalKeyButton(label = "~") {
                    currentHolder?.session?.write("~")
                }

                TerminalKeyButton(label = "PASTE") {
                    currentHolder?.let { h -> h.sessionClient.onPasteTextFromClipboard(h.session) }
                }
            }
        }
    }
}

@Composable
private fun TerminalKeyButton(
    label: String,
    onClick: () -> Unit,
) {
    FilledTonalButton(
        onClick = onClick,
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
        shape = RoundedCornerShape(6.dp),
        colors = ButtonDefaults.filledTonalButtonColors(
            containerColor = Color(0xFF262626),
            contentColor = Color.White,
        )
    ) {
        Text(
            text = label,
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

private fun sendKey(session: TerminalSession?, code: Int) {
    session?.write(byteArrayOf(code.toByte()), 0, 1)
}

private fun sendSpecialKey(session: TerminalSession?, sequence: String) {
    val bytes = sequence.toByteArray()
    session?.write(bytes, 0, bytes.size)
}
