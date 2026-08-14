package com.bit.ui.screen.model_store

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.bit.models.data.HuggingFaceModel
import com.bit.models.data.ModelType
import com.bit.service.ModelDownloadService
import com.bit.ui.components.ActionButton
import com.bit.ui.components.ActionProgressButton
import com.bit.ui.components.M3WavyLinearProgressIndicator
import com.bit.ui.theme.Motion
import com.bit.ui.theme.MonoWarning
import com.bit.ui.icons.TnIcons
import com.bit.global.Standards
import com.bit.global.HardwareScanner

// ── ModelTypeBadge ──

@Composable
internal fun ModelTypeBadge(modelType: ModelType) {
    val (label, color) = when (modelType) {
        ModelType.GGUF -> "LLM" to MaterialTheme.colorScheme.primary
        ModelType.VLM -> "Vision" to MaterialTheme.colorScheme.primary
        ModelType.SD -> "Image" to MaterialTheme.colorScheme.secondary
        ModelType.TTS -> "TTS" to MaterialTheme.colorScheme.tertiary
        ModelType.STT -> "STT" to MaterialTheme.colorScheme.tertiary
        ModelType.EMBEDDING -> "Embedding" to MaterialTheme.colorScheme.tertiary
    }
    val shape = RoundedCornerShape(Standards.SpacingXs)
    Text(
        text = label,
        style = MaterialTheme.typography.labelSmall,
        color = color,
        fontWeight = FontWeight.Bold,
        modifier = Modifier
            .background(color.copy(alpha = 0.12f), shape)
            .border(1.dp, color.copy(alpha = 0.2f), shape)
            .padding(horizontal = 6.dp, vertical = Standards.SpacingXxs)
    )
}

// ── ModelCard ──

@Composable
fun ModelCard(
    model: HuggingFaceModel,
    isInstalled: Boolean,
    downloadState: ModelDownloadService.DownloadState?,
    onDownload: () -> Unit,
    onCancelDownload: () -> Unit,
    onPauseDownload: () -> Unit = {},
    onResumeDownload: () -> Unit = {}
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val systemRamGb = remember { HardwareScanner.getTotalSystemRamGb(context) }
    
    // Auto-detect if this model requires high RAM (>3GB size or SD generation models)
    val isHeavyModel = remember(model.approximateSize, model.modelType) {
        val sizeStr = model.approximateSize.lowercase()
        when {
            model.modelType == ModelType.SD -> true
            sizeStr.contains("gb") -> {
                val sizeVal = sizeStr.replace("gb", "").replace("~", "").trim().toDoubleOrNull() ?: 0.0
                sizeVal >= 3.0
            }
            else -> false
        }
    }

    val isDownloading = remember(downloadState) {
        downloadState is ModelDownloadService.DownloadState.Downloading
    }
    val isPaused = remember(downloadState) {
        downloadState is ModelDownloadService.DownloadState.Paused
    }
    val isExtracting = remember(downloadState) {
        downloadState is ModelDownloadService.DownloadState.Extracting
    }
    val isProcessing = remember(downloadState) {
        downloadState is ModelDownloadService.DownloadState.Processing
    }
    val isVerifying = remember(downloadState) {
        downloadState is ModelDownloadService.DownloadState.Verifying
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = Standards.SpacingSm)
    ) {
        Column {
            // Top: Type badge + Name + Action button
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val targetIconUrl = model.iconUrl ?: model.icon?.let { "https://unpkg.com/@lobehub/icons-static-png@1.95.0/dark/$it.png" }
                    if (!targetIconUrl.isNullOrBlank()) {
                        coil3.compose.AsyncImage(
                            model = targetIconUrl,
                            contentDescription = null,
                            modifier = Modifier
                                .size(22.dp)
                                .clip(RoundedCornerShape(4.dp))
                        )
                    }
                    ModelTypeBadge(model.modelType)
                    Text(
                        text = model.name,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onBackground,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                when {
                    isInstalled -> {
                        Icon(
                            imageVector = TnIcons.CircleCheck,
                            contentDescription = "Installed",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(24.dp)
                        )
                    }

                    isDownloading -> {
                        val currentProgress = (downloadState as? ModelDownloadService.DownloadState.Downloading)?.progress ?: 0f
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // Pause button
                            ActionButton(
                                onClickListener = onPauseDownload,
                                icon = TnIcons.PlayerPause,
                                contentDescription = "Pause Download"
                            )
                            // Cancel button with circular progress ring
                            ActionProgressButton(
                                onClickListener = onCancelDownload,
                                icon = TnIcons.PlayerStop,
                                contentDescription = "Cancel Download",
                                progress = currentProgress
                            )
                        }
                    }

                    isPaused -> {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // Resume button
                            ActionButton(
                                onClickListener = onResumeDownload,
                                icon = TnIcons.PlayerPlay,
                                contentDescription = "Resume Download"
                            )
                            // Cancel button
                            ActionButton(
                                onClickListener = onCancelDownload,
                                icon = TnIcons.PlayerStop,
                                contentDescription = "Cancel Download"
                            )
                        }
                    }

                    isExtracting || isProcessing || isVerifying -> {
                        val extractProgress = (downloadState as? ModelDownloadService.DownloadState.Extracting)?.let {
                            if (it.totalFiles > 0) it.extractedCount.toFloat() / it.totalFiles else null
                        }
                        ActionProgressButton(
                            onClickListener = onCancelDownload,
                            icon = TnIcons.PlayerStop,
                            contentDescription = "Cancel Download",
                            progress = extractProgress
                        )
                    }

                    else -> {
                        ActionButton(
                            onClickListener = onDownload,
                            icon = TnIcons.Download,
                            contentDescription = "Download Model"
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(Standards.SpacingXs))

            // Size + repo source + key tags in a compact row
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Size chip
                val sizeChipShape = RoundedCornerShape(Standards.SpacingXs)
                Text(
                    text = model.approximateSize,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier
                        .background(
                            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f),
                            sizeChipShape
                        )
                        .border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.2f), sizeChipShape)
                        .padding(horizontal = 6.dp, vertical = Standards.SpacingXxs)
                )

                if (isHeavyModel && systemRamGb < 6.0) {
                    val warningShape = RoundedCornerShape(Standards.SpacingXs)
                    val warningColor = MonoWarning
                    Row(
                        modifier = Modifier
                            .background(warningColor.copy(alpha = 0.12f), warningShape)
                            .border(1.dp, warningColor.copy(alpha = 0.2f), warningShape)
                            .padding(horizontal = 6.dp, vertical = Standards.SpacingXxs),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = TnIcons.AlertTriangle,
                            contentDescription = null,
                            tint = warningColor,
                            modifier = Modifier.size(12.dp)
                        )
                        Text(
                            text = "Low RAM Warning",
                            style = MaterialTheme.typography.labelSmall,
                            color = warningColor,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                // Repo source
                if (model.repositoryUrl.isNotEmpty()) {
                    val repoName = model.repositoryUrl.substringBefore("/")
                    val repoChipShape = RoundedCornerShape(Standards.SpacingXs)
                    Text(
                        text = repoName,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .background(
                                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                                repoChipShape
                            )
                            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, repoChipShape)
                            .padding(horizontal = 6.dp, vertical = Standards.SpacingXxs)
                    )
                }

                // Key tags (max 2)
                model.tags.take(2).forEach { tag ->
                    val tagShape = RoundedCornerShape(Standards.SpacingXs)
                    Text(
                        text = tag,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                        modifier = Modifier
                            .background(
                                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                                tagShape
                            )
                            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, tagShape)
                            .padding(horizontal = 5.dp, vertical = Standards.SpacingXxs)
                    )
                }
            }

            // Download progress (animated)
            AnimatedVisibility(
                visible = isDownloading || isPaused || isExtracting || isProcessing || isVerifying,
                enter = Motion.Enter,
                exit = Motion.Exit
            ) {
                Column(modifier = Modifier.padding(top = Standards.SpacingSm)) {
                    val progress = when (downloadState) {
                        is ModelDownloadService.DownloadState.Downloading -> downloadState.progress
                        is ModelDownloadService.DownloadState.Paused -> downloadState.progress
                        else -> 0f
                    }

                    val statusText = when {
                        isVerifying -> "Verifying checksum..."
                        isProcessing -> "Processing..."
                        isExtracting -> {
                            val es = downloadState as ModelDownloadService.DownloadState.Extracting
                            if (es.currentFile.isNotEmpty()) {
                                "Unzipping ${es.currentFile} (${es.extractedCount + 1}/${es.totalFiles})"
                            } else {
                                "Extracting..."
                            }
                        }
                        isPaused -> {
                            val ps = downloadState as ModelDownloadService.DownloadState.Paused
                            val downloadedMB = ps.downloadedBytes / 1_000_000
                            val totalMB = ps.totalBytes / 1_000_000
                            val pct = (ps.progress * 100).toInt()
                            "Paused · ${downloadedMB}/${totalMB}MB ($pct%)"
                        }
                        isDownloading -> {
                            val ds = downloadState as ModelDownloadService.DownloadState.Downloading
                            val downloadedMB = ds.downloadedBytes / 1_000_000
                            val totalMB = ds.totalBytes / 1_000_000
                            val pct = (progress * 100).toInt()
                            val speedText = if (ds.speedBytesPerSec > 0) {
                                val speedMB = ds.speedBytesPerSec / 1_000_000.0
                                " · %.1f MB/s".format(speedMB)
                            } else ""
                            val etaText = if (ds.etaSeconds > 0) {
                                val mins = ds.etaSeconds / 60
                                val secs = ds.etaSeconds % 60
                                if (mins > 0) " · ${mins}m ${secs}s left"
                                else " · ${secs}s left"
                            } else ""
                            "${downloadedMB}/${totalMB}MB ($pct%)$speedText$etaText"
                        }
                        else -> ""
                    }

                    Text(
                        text = statusText,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (isPaused) MonoWarning else MaterialTheme.colorScheme.primary
                    )

                    Spacer(modifier = Modifier.height(Standards.SpacingXs))

                    val isIndeterminate = isProcessing || isVerifying || (isExtracting && (downloadState as ModelDownloadService.DownloadState.Extracting).totalFiles <= 0)
                    val progressVal = when {
                        isDownloading -> progress
                        isExtracting -> {
                            val es = downloadState as ModelDownloadService.DownloadState.Extracting
                            if (es.totalFiles > 0) es.extractedCount.toFloat() / es.totalFiles else 0f
                        }
                        else -> 0f
                    }

                    M3WavyLinearProgressIndicator(
                        progress = progressVal,
                        isIndeterminate = isIndeterminate,
                        activeColor = if (isPaused) MonoWarning else MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(Standards.SpacingSm))
        androidx.compose.material3.HorizontalDivider(
            color = MaterialTheme.colorScheme.outlineVariant,
            thickness = 0.8.dp
        )
    }
}
