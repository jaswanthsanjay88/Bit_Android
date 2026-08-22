package com.bit.ui.screen.settings

import android.app.Activity
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import com.bit.ui.theme.Motion
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.bit.ui.theme.Glass
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bit.global.Standards
import com.bit.global.formatBackupTimestamp
import com.bit.global.formatBytes
import com.bit.sync.WebDavBackupItem
import com.bit.sync.WebDavConfig
import com.bit.sync.WebDavSyncState
import com.bit.ui.components.PasswordTextField
import com.bit.ui.components.SwitchRow
import com.bit.ui.icons.TnIcons
import com.bit.viewmodel.SettingsViewModel
import com.bit.worker.SystemBackupManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalMaterial3Api::class)
@Composable
fun DataManagementSection(viewModel: SettingsViewModel) {
    val context = LocalContext.current
    val haptics = com.bit.ui.theme.LocalBitHaptics.current

    val backupProgress by viewModel.backupProgress.collectAsStateWithLifecycle()
    val backupOptions by viewModel.backupOptions.collectAsStateWithLifecycle()
    val backupSizeEstimate by viewModel.backupSizeEstimate.collectAsStateWithLifecycle()

    val webDavConfig by viewModel.webDavConfig.collectAsStateWithLifecycle()
    val webDavSyncState by viewModel.webDavSyncState.collectAsStateWithLifecycle()
    val webDavBackups by viewModel.webDavBackups.collectAsStateWithLifecycle()

    var selectedTab by remember { mutableStateOf(0) } // 0 = Cloud (WebDAV), 1 = Local Storage

    // Dialog states
    var showBackupDialog by remember { mutableStateOf(false) }
    var showRestoreDialog by remember { mutableStateOf(false) }
    var showWebDavBackupDialog by remember { mutableStateOf(false) }
    var restoreWebDavTarget by remember { mutableStateOf<WebDavBackupItem?>(null) }
    var showDeleteDialog by remember { mutableStateOf(false) }

    var backupPassword by remember { mutableStateOf("") }
    var backupPasswordConfirm by remember { mutableStateOf("") }
    var restorePassword by remember { mutableStateOf("") }
    var deleteConfirmText by remember { mutableStateOf("") }

    // SAF launchers
    val backupLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri ->
        if (uri != null && backupPassword.isNotEmpty()) {
            viewModel.createBackup(uri, backupPassword)
            backupPassword = ""
            backupPasswordConfirm = ""
        }
    }

    val restoreLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null && restorePassword.isNotEmpty()) {
            viewModel.restoreBackup(uri, restorePassword)
            restorePassword = ""
        }
    }

    // Auto-dismiss progress after completion, or restart after restore
    LaunchedEffect(backupProgress) {
        if (backupProgress is SystemBackupManager.BackupProgress.Complete) {
            if (showRestoreDialog || restoreWebDavTarget != null) {
                kotlinx.coroutines.delay(500)
                showRestoreDialog = false
                restoreWebDavTarget = null
                val activity = context as? Activity
                activity?.let {
                    val intent = it.packageManager.getLaunchIntentForPackage(it.packageName)
                        ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                    it.finishAffinity()
                    if (intent != null) it.startActivity(intent)
                    Runtime.getRuntime().exit(0)
                }
            } else {
                kotlinx.coroutines.delay(2000)
                viewModel.clearBackupProgress()
            }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(Standards.SpacingMd)) {
        // Tab Selector (Segmented Button Row)
        SingleChoiceSegmentedButtonRow(
            modifier = Modifier.fillMaxWidth()
        ) {
            SegmentedButton(
                selected = selectedTab == 0,
                onClick = {
                    haptics.pop()
                    selectedTab = 0
                    if (webDavConfig.isConfigured) {
                        viewModel.listWebDavBackups()
                    }
                },
                shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                icon = {
                    Icon(
                        TnIcons.CloudUpload,
                        contentDescription = null,
                        modifier = Modifier.size(SegmentedButtonDefaults.IconSize)
                    )
                }
            ) {
                Text("Cloud (WebDAV)", fontWeight = FontWeight.SemiBold)
            }

            SegmentedButton(
                selected = selectedTab == 1,
                onClick = {
                    haptics.pop()
                    selectedTab = 1
                },
                shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                icon = {
                    Icon(
                        TnIcons.Folder,
                        contentDescription = null,
                        modifier = Modifier.size(SegmentedButtonDefaults.IconSize)
                    )
                }
            ) {
                Text("Local Storage", fontWeight = FontWeight.SemiBold)
            }
        }

        // Global Progress indicator
        AnimatedVisibility(
            visible = (backupProgress != null && backupProgress !is SystemBackupManager.BackupProgress.Complete && backupProgress !is SystemBackupManager.BackupProgress.Error) ||
                    webDavSyncState is WebDavSyncState.Loading,
            enter = Motion.Enter,
            exit = Motion.Exit
        ) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f),
                shape = RoundedCornerShape(Standards.CardCornerRadius),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.3f))
            ) {
                Row(
                    modifier = Modifier.padding(Standards.CardPadding),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Standards.SpacingSm)
                ) {
                    LoadingIndicator(modifier = Modifier.size(20.dp))
                    Text(
                        text = when (val s = webDavSyncState) {
                            is WebDavSyncState.Loading -> s.message
                            else -> when (val p = backupProgress) {
                                is SystemBackupManager.BackupProgress.Starting -> "Preparing backup..."
                                is SystemBackupManager.BackupProgress.Collecting -> p.component
                                is SystemBackupManager.BackupProgress.Processing -> "${if (p.stage.isNotEmpty()) "${p.stage} " else ""}${(p.progress * 100).toInt()}%"
                                else -> "Processing..."
                            }
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }

        // WebDAV Sync State Toast / Badge
        when (val s = webDavSyncState) {
            is WebDavSyncState.Success -> {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.12f),
                    shape = RoundedCornerShape(Standards.CardCornerRadius),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.tertiary.copy(alpha = 0.3f))
                ) {
                    Text(
                        s.message,
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.tertiary,
                        modifier = Modifier.padding(Standards.CardPadding)
                    )
                }
            }
            is WebDavSyncState.Error -> {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f),
                    shape = RoundedCornerShape(Standards.CardCornerRadius),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.3f))
                ) {
                    Text(
                        s.errorMessage,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(Standards.CardPadding)
                    )
                }
            }
            else -> {}
        }

        // TAB 1: WEBDAV CLOUD SYNC
        if (selectedTab == 0) {
            WebDavCloudSection(
                config = webDavConfig,
                syncState = webDavSyncState,
                backups = webDavBackups,
                onSaveConfig = viewModel::updateWebDavConfig,
                onTestConnection = { viewModel.testWebDavConnection() },
                onBackupClick = {
                    haptics.pop()
                    showWebDavBackupDialog = true
                },
                onRestoreItem = { item ->
                    haptics.pop()
                    restoreWebDavTarget = item
                },
                onDeleteItem = { item ->
                    haptics.thud()
                    viewModel.deleteWebDavBackup(item)
                }
            )
        }

        // TAB 2: LOCAL STORAGE
        if (selectedTab == 1) {
            LocalStorageSection(
                onBackupClick = {
                    haptics.pop()
                    showBackupDialog = true
                },
                onRestoreClick = {
                    haptics.pop()
                    showRestoreDialog = true
                },
                onDeleteAllClick = {
                    haptics.thud()
                    showDeleteDialog = true
                }
            )
        }
    }

    // ── Local Backup Dialog ──
    if (showBackupDialog) {
        BackupDialog(
            backupPassword = backupPassword,
            onPasswordChange = { backupPassword = it },
            backupPasswordConfirm = backupPasswordConfirm,
            onPasswordConfirmChange = { backupPasswordConfirm = it },
            options = backupOptions,
            onOptionsChange = viewModel::updateBackupOptions,
            sizeEstimate = backupSizeEstimate,
            onDismiss = {
                showBackupDialog = false
                backupPassword = ""
                backupPasswordConfirm = ""
            },
            onConfirm = {
                showBackupDialog = false
                val filename = "BIT_backup_${formatBackupTimestamp()}.bitbackup"
                backupLauncher.launch(filename)
            }
        )
    }

    // ── WebDAV Backup Dialog ──
    if (showWebDavBackupDialog) {
        WebDavBackupPasswordDialog(
            backupPassword = backupPassword,
            onPasswordChange = { backupPassword = it },
            backupPasswordConfirm = backupPasswordConfirm,
            onPasswordConfirmChange = { backupPasswordConfirm = it },
            options = backupOptions,
            onOptionsChange = viewModel::updateBackupOptions,
            onDismiss = {
                showWebDavBackupDialog = false
                backupPassword = ""
                backupPasswordConfirm = ""
            },
            onConfirm = {
                showWebDavBackupDialog = false
                val pass = backupPassword
                backupPassword = ""
                backupPasswordConfirm = ""
                viewModel.backupToWebDav(pass)
            }
        )
    }

    // ── Local Restore Dialog ──
    if (showRestoreDialog) {
        RestoreDialog(
            restorePassword = restorePassword,
            onPasswordChange = { restorePassword = it },
            onDismiss = {
                showRestoreDialog = false
                restorePassword = ""
            },
            onConfirm = {
                restoreLauncher.launch(arrayOf("*/*"))
            }
        )
    }

    // ── WebDAV Restore Confirmation Dialog ──
    if (restoreWebDavTarget != null) {
        val target = restoreWebDavTarget!!
        WebDavRestorePasswordDialog(
            item = target,
            restorePassword = restorePassword,
            onPasswordChange = { restorePassword = it },
            onDismiss = {
                restoreWebDavTarget = null
                restorePassword = ""
            },
            onConfirm = {
                val pass = restorePassword
                restorePassword = ""
                viewModel.restoreFromWebDav(target, pass)
            }
        )
    }

    // ── Delete All Dialog ──
    if (showDeleteDialog) {
        DeleteAllDialog(
            confirmText = deleteConfirmText,
            onConfirmTextChange = { deleteConfirmText = it },
            onDismiss = {
                showDeleteDialog = false
                deleteConfirmText = ""
            },
            onConfirm = {
                showDeleteDialog = false
                deleteConfirmText = ""
                viewModel.deleteAllData()
            }
        )
    }
}

// ======================== WEBDAV COMPONENT ========================

@Composable
private fun WebDavCloudSection(
    config: WebDavConfig,
    syncState: WebDavSyncState,
    backups: List<WebDavBackupItem>,
    onSaveConfig: (WebDavConfig) -> Unit,
    onTestConnection: () -> Unit,
    onBackupClick: () -> Unit,
    onRestoreItem: (WebDavBackupItem) -> Unit,
    onDeleteItem: (WebDavBackupItem) -> Unit
) {
    var url by remember(config.url) { mutableStateOf(config.url) }
    var username by remember(config.username) { mutableStateOf(config.username) }
    var password by remember(config.password) { mutableStateOf(config.password) }
    var path by remember(config.path) { mutableStateOf(config.path) }
    var isPasswordVisible by remember { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(Standards.SpacingSm)) {
        // Configuration Card
        Card(
            shape = RoundedCornerShape(Standards.CardCornerRadius),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
            border = BorderStroke(1.dp, Glass.BorderSubtle)
        ) {
            Column(
                modifier = Modifier.padding(Standards.CardPadding),
                verticalArrangement = Arrangement.spacedBy(Standards.SpacingSm)
            ) {
                Text(
                    "WebDAV Connection",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    "Sync backups with Nextcloud, ownCloud, Synology, Koofr, or any WebDAV server.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Glass.TextSecondary
                )

                OutlinedTextField(
                    value = url,
                    onValueChange = {
                        url = it
                        onSaveConfig(config.copy(url = it.trim()))
                    },
                    label = { Text("Server URL") },
                    placeholder = { Text("https://cloud.example.com/remote.php/dav/files/user/") },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(Standards.SpacingSm)
                ) {
                    OutlinedTextField(
                        value = username,
                        onValueChange = {
                            username = it
                            onSaveConfig(config.copy(username = it.trim()))
                        },
                        label = { Text("Username") },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.weight(1f)
                    )

                    OutlinedTextField(
                        value = password,
                        onValueChange = {
                            password = it
                            onSaveConfig(config.copy(password = it.trim()))
                        },
                        label = { Text("Password / App Token") },
                        singleLine = true,
                        visualTransformation = if (isPasswordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                        trailingIcon = {
                            IconButton(onClick = { isPasswordVisible = !isPasswordVisible }) {
                                Icon(
                                    if (isPasswordVisible) TnIcons.EyeOff else TnIcons.Eye,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        },
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.weight(1f)
                    )
                }

                OutlinedTextField(
                    value = path,
                    onValueChange = {
                        path = it
                        onSaveConfig(config.copy(path = it.trim()))
                    },
                    label = { Text("Remote Backup Folder") },
                    placeholder = { Text("BIT_Backups") },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(Standards.SpacingSm)
                ) {
                    OutlinedButton(
                        onClick = onTestConnection,
                        enabled = config.isConfigured && syncState !is WebDavSyncState.Loading,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(TnIcons.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Test Connection")
                    }

                    Button(
                        onClick = onBackupClick,
                        enabled = config.isConfigured && syncState !is WebDavSyncState.Loading,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(TnIcons.CloudUpload, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Backup Now")
                    }
                }
            }
        }

        // Remote Backups List
        Card(
            shape = RoundedCornerShape(Standards.CardCornerRadius),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
            border = BorderStroke(1.dp, Glass.BorderSubtle)
        ) {
            Column(
                modifier = Modifier.padding(Standards.CardPadding),
                verticalArrangement = Arrangement.spacedBy(Standards.SpacingSm)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "Cloud Backups (${backups.size})",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    IconButton(
                        onClick = onTestConnection,
                        enabled = config.isConfigured,
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(TnIcons.Refresh, contentDescription = "Refresh", modifier = Modifier.size(16.dp))
                    }
                }

                if (backups.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 16.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            if (config.isConfigured) "No backups found on WebDAV server" else "Configure WebDAV above to view cloud backups",
                            style = MaterialTheme.typography.bodySmall,
                            color = Glass.TextSecondary
                        )
                    }
                } else {
                    val sdf = remember { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()) }
                    backups.forEach { item ->
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.surfaceContainer,
                            border = BorderStroke(1.dp, Glass.BorderSubtle),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(Standards.SpacingSm)
                            ) {
                                Icon(
                                    TnIcons.CloudDownload,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(24.dp)
                                )

                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        item.displayName,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        maxLines = 1
                                    )
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        if (item.sizeBytes > 0) {
                                            Text(
                                                formatBytes(item.sizeBytes),
                                                style = MaterialTheme.typography.labelSmall,
                                                color = Glass.TextSecondary
                                            )
                                        }
                                        Text(
                                            sdf.format(Date(item.lastModifiedEpochMs)),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = Glass.TextSecondary
                                        )
                                    }
                                }

                                // Restore button
                                IconButton(
                                    onClick = { onRestoreItem(item) },
                                    modifier = Modifier.size(32.dp)
                                ) {
                                    Icon(
                                        TnIcons.CloudDownload,
                                        contentDescription = "Restore",
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }

                                // Delete button
                                IconButton(
                                    onClick = { onDeleteItem(item) },
                                    modifier = Modifier.size(32.dp)
                                ) {
                                    Icon(
                                        TnIcons.TrashX,
                                        contentDescription = "Delete",
                                        tint = Color(0xFFFF5252),
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// ======================== LOCAL STORAGE COMPONENT ========================

@Composable
private fun LocalStorageSection(
    onBackupClick: () -> Unit,
    onRestoreClick: () -> Unit,
    onDeleteAllClick: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(Standards.SpacingSm)) {
        // --- Local Export Card ---
        Surface(
            onClick = onBackupClick,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(Standards.CardCornerRadius),
            color = Glass.Surface,
            border = BorderStroke(1.dp, Glass.BorderSubtle)
        ) {
            Row(
                modifier = Modifier.padding(Standards.CardPadding),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Standards.SpacingSm)
            ) {
                Icon(
                    TnIcons.Folder, null,
                    modifier = Modifier.size(Standards.IconLg),
                    tint = Glass.AccentPrimary
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "Export Local Backup",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = Glass.TextPrimary
                    )
                    Text(
                        "Save encrypted snapshot (.bitbackup) to device storage",
                        style = MaterialTheme.typography.bodySmall,
                        color = Glass.TextSecondary
                    )
                }
            }
        }

        // --- Local Restore Card ---
        Surface(
            onClick = onRestoreClick,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(Standards.CardCornerRadius),
            color = Glass.Surface,
            border = BorderStroke(1.dp, Glass.BorderSubtle)
        ) {
            Row(
                modifier = Modifier.padding(Standards.CardPadding),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Standards.SpacingSm)
            ) {
                Icon(
                    TnIcons.CloudDownload, null,
                    modifier = Modifier.size(Standards.IconLg),
                    tint = Glass.AccentSecondary
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "Restore from File",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = Glass.TextPrimary
                    )
                    Text(
                        "Import and decrypt .bitbackup file from Downloads / Storage",
                        style = MaterialTheme.typography.bodySmall,
                        color = Glass.TextSecondary
                    )
                }
            }
        }

        // --- Red Delete All Card ---
        Surface(
            onClick = onDeleteAllClick,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(Standards.CardCornerRadius),
            color = Color(0x1AFF4D4D),
            border = BorderStroke(1.dp, Color(0x33FF4D4D))
        ) {
            Row(
                modifier = Modifier.padding(Standards.CardPadding),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Standards.SpacingSm)
            ) {
                Icon(
                    TnIcons.TrashX, null,
                    modifier = Modifier.size(Standards.IconLg),
                    tint = Color(0xFFFF4D4D)
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "Delete All App Data",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = Color(0xFFFF4D4D)
                    )
                    Text(
                        "Permanently purge all chats, memory vault, and settings",
                        style = MaterialTheme.typography.bodySmall,
                        color = Glass.TextSecondary
                    )
                }
            }
        }
    }
}

// ======================== DIALOGS ========================

@Composable
private fun WebDavBackupPasswordDialog(
    backupPassword: String,
    onPasswordChange: (String) -> Unit,
    backupPasswordConfirm: String,
    onPasswordConfirmChange: (String) -> Unit,
    options: SystemBackupManager.BackupOptions,
    onOptionsChange: (SystemBackupManager.BackupOptions) -> Unit,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    val passwordsMatch = backupPassword.isNotEmpty() && backupPassword == backupPasswordConfirm
    val isPasswordStrong = backupPassword.length >= 6

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("WebDAV Cloud Backup", fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Standards.SpacingSm)) {
                Text(
                    "Set an encryption password for your remote cloud backup. You will need this password to restore.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Glass.TextSecondary
                )

                PasswordTextField(
                    value = backupPassword,
                    onValueChange = onPasswordChange,
                    label = "Encryption Password"
                )

                PasswordTextField(
                    value = backupPasswordConfirm,
                    onValueChange = onPasswordConfirmChange,
                    label = "Confirm Password",
                    isError = backupPasswordConfirm.isNotEmpty() && !passwordsMatch
                )

                if (backupPasswordConfirm.isNotEmpty() && !passwordsMatch) {
                    Text("Passwords do not match", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }

                SwitchRow(
                    title = "Include RAG Files",
                    description = "Back up knowledge base documents",
                    checked = options.includeRagFiles,
                    onCheckedChange = { onOptionsChange(options.copy(includeRagFiles = it)) }
                )
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                enabled = passwordsMatch && isPasswordStrong
            ) {
                Text("Upload to Cloud")
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
private fun WebDavRestorePasswordDialog(
    item: WebDavBackupItem,
    restorePassword: String,
    onPasswordChange: (String) -> Unit,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Restore Cloud Backup", fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Standards.SpacingSm)) {
                Text(
                    "Restoring: ${item.displayName}",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    "Enter the encryption password used when this backup was created. Restoring will overwrite existing chats and settings.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Glass.TextSecondary
                )

                PasswordTextField(
                    value = restorePassword,
                    onValueChange = onPasswordChange,
                    label = "Backup Password"
                )
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                enabled = restorePassword.isNotEmpty(),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
            ) {
                Text("Download & Restore")
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
private fun BackupDialog(
    backupPassword: String,
    onPasswordChange: (String) -> Unit,
    backupPasswordConfirm: String,
    onPasswordConfirmChange: (String) -> Unit,
    options: SystemBackupManager.BackupOptions,
    onOptionsChange: (SystemBackupManager.BackupOptions) -> Unit,
    sizeEstimate: SystemBackupManager.BackupSizeEstimate?,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    val passwordsMatch = backupPassword.isNotEmpty() && backupPassword == backupPasswordConfirm
    val isPasswordStrong = backupPassword.length >= 6

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Export Local Backup", fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Standards.SpacingSm)) {
                Text(
                    "Create an encrypted backup archive (.bitbackup) protected with AES-256.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Glass.TextSecondary
                )

                PasswordTextField(
                    value = backupPassword,
                    onValueChange = onPasswordChange,
                    label = "Encryption Password"
                )

                PasswordTextField(
                    value = backupPasswordConfirm,
                    onValueChange = onPasswordConfirmChange,
                    label = "Confirm Password",
                    isError = backupPasswordConfirm.isNotEmpty() && !passwordsMatch
                )

                if (backupPasswordConfirm.isNotEmpty() && !passwordsMatch) {
                    Text("Passwords do not match", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }

                SwitchRow(
                    title = "Include RAG Files",
                    description = "Back up knowledge base documents",
                    checked = options.includeRagFiles,
                    onCheckedChange = { onOptionsChange(options.copy(includeRagFiles = it)) }
                )

                if (sizeEstimate != null) {
                    Text(
                        "Estimated size: ${formatBytes(sizeEstimate.totalSize)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = Glass.TextSecondary
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                enabled = passwordsMatch && isPasswordStrong
            ) {
                Text("Export File")
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
private fun RestoreDialog(
    restorePassword: String,
    onPasswordChange: (String) -> Unit,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Restore Local Backup", fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Standards.SpacingSm)) {
                Text(
                    "Select a .bitbackup file and enter its encryption password. Restoring will replace existing local data.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Glass.TextSecondary
                )

                PasswordTextField(
                    value = restorePassword,
                    onValueChange = onPasswordChange,
                    label = "Backup Password"
                )
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                enabled = restorePassword.isNotEmpty()
            ) {
                Text("Select File & Restore")
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
private fun DeleteAllDialog(
    confirmText: String,
    onConfirmTextChange: (String) -> Unit,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    val isConfirmed = confirmText.trim().equals("DELETE", ignoreCase = true)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Delete All App Data?", fontWeight = FontWeight.Bold, color = Color(0xFFFF4D4D)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Standards.SpacingSm)) {
                Text(
                    "This action cannot be undone. All chats, episodic memory vault entries, RAG documents, and custom settings will be permanently erased.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Glass.TextSecondary
                )
                Text(
                    "Type DELETE to confirm:",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                OutlinedTextField(
                    value = confirmText,
                    onValueChange = onConfirmTextChange,
                    placeholder = { Text("DELETE") },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                enabled = isConfirmed,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF4D4D))
            ) {
                Text("Erase Everything")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
