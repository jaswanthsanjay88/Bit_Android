package com.bit.update

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.bit.ui.theme.BitColors
import kotlinx.coroutines.delay

private enum class UpdateStage { PROMPT, DOWNLOADING, READY_TO_INSTALL, ERROR }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UpdateBottomSheet(
    update: UpdateInfo,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val downloader = remember { UpdateDownloader(context) }
    val sheetState = rememberModalBottomSheetState()

    var stage by remember { mutableStateOf(UpdateStage.PROMPT) }
    var downloadId by remember { mutableStateOf(-1L) }
    var progress by remember { mutableFloatStateOf(0f) }
    var receiverRef by remember { mutableStateOf<android.content.BroadcastReceiver?>(null) }

    // Clean up receiver on sheet dispose
    DisposableEffect(Unit) {
        onDispose {
            receiverRef?.let {
                try {
                    context.unregisterReceiver(it)
                } catch (e: Exception) {
                    // Ignore
                }
            }
        }
    }

    // Poll download progress while downloading
    LaunchedEffect(stage) {
        if (stage == UpdateStage.DOWNLOADING) {
            while (stage == UpdateStage.DOWNLOADING) {
                downloader.getDownloadProgress(downloadId)?.let { progress = it }
                if (progress >= 1f) {
                    stage = UpdateStage.READY_TO_INSTALL
                }
                delay(300)
            }
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = BitColors.Surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column {
                    Text(
                        text = "Update available",
                        style = MaterialTheme.typography.labelLarge,
                        color = BitColors.TextPrimary
                    )
                    Text(
                        text = "${update.title} (${update.version})",
                        style = MaterialTheme.typography.headlineSmall,
                        color = BitColors.TextPrimary
                    )
                }
            }

            Spacer(Modifier.height(8.dp))
            Text(
                text = "Currently on ${update.currentVersion} - ${formatSize(update.sizeBytes)}",
                style = MaterialTheme.typography.bodyMedium,
                color = BitColors.TextSecondary
            )

            Spacer(Modifier.height(20.dp))

            // Changelog — scrollable if long, capped height so sheet doesn't take the whole screen
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(180.dp)
                    .background(
                        BitColors.SurfaceAlt,
                        RoundedCornerShape(16.dp)
                    )
                    .padding(16.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    text = update.changelog.ifBlank { "No changelog provided for this release." },
                    style = MaterialTheme.typography.bodyMedium,
                    color = BitColors.TextPrimary
                )
            }

            Spacer(Modifier.height(24.dp))

            when (stage) {
                UpdateStage.PROMPT -> {
                    Button(
                        onClick = {
                            if (!downloader.canInstallUnknownApps()) {
                                downloader.requestInstallPermission()
                                return@Button
                            }
                            downloadId = downloader.startDownload(update)
                            receiverRef = downloader.registerInstallOnComplete(downloadId) {
                                stage = UpdateStage.READY_TO_INSTALL
                                progress = 1.0f
                            }
                            stage = UpdateStage.DOWNLOADING
                        },
                        modifier = Modifier.fillMaxWidth().height(52.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = BitColors.Inverse,
                            contentColor = BitColors.OnInverse
                        ),
                        shape = RoundedCornerShape(28.dp)
                    ) {
                        Text("Update now")
                    }
                    Spacer(Modifier.height(8.dp))
                    TextButton(
                        onClick = onDismiss,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.textButtonColors(
                            contentColor = BitColors.TextSecondary
                        )
                    ) {
                        Text("Later")
                    }
                }

                UpdateStage.DOWNLOADING -> {
                    Text(
                        text = "Downloading... ${(progress * 100).toInt()}%",
                        style = MaterialTheme.typography.bodyMedium,
                        color = BitColors.TextPrimary
                    )
                    Spacer(Modifier.height(8.dp))
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(RoundedCornerShape(3.dp)),
                        color = BitColors.Inverse,
                        trackColor = BitColors.SurfaceAlt
                    )
                }

                UpdateStage.READY_TO_INSTALL -> {
                    LaunchedEffect(Unit) {
                        downloader.installDownloadedApk()
                    }
                    Button(
                        onClick = { downloader.installDownloadedApk() },
                        modifier = Modifier.fillMaxWidth().height(52.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = BitColors.Inverse,
                            contentColor = BitColors.OnInverse
                        ),
                        shape = RoundedCornerShape(28.dp)
                    ) {
                        Text("Install now")
                    }
                }

                UpdateStage.ERROR -> {
                    Text(
                        text = "Something went wrong downloading the update. Try again from the release page.",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }
    }
}

private fun formatSize(bytes: Long): String {
    val mb = bytes / (1024.0 * 1024.0)
    return "%.1f MB".format(mb)
}
