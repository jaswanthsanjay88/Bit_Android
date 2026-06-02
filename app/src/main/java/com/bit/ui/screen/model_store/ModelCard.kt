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
import com.bit.ui.components.GlassCard
import com.bit.ui.theme.Glass
import com.bit.ui.theme.Motion
import com.bit.ui.theme.MonoWarning
import com.bit.ui.icons.TnIcons
import com.bit.global.Standards
import com.bit.global.HardwareScanner

// ── ModelTypeBadge ──

@Composable
internal fun ModelTypeBadge(modelType: ModelType) {
    val (label, color) = when (modelType) {
        ModelType.GGUF -> "LLM" to Glass.AccentPrimary
        ModelType.SD -> "Image" to Glass.AccentWarm
        ModelType.TTS -> "TTS" to Glass.AccentSecondary
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
    onCancelDownload: () -> Unit
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
    val isExtracting = remember(downloadState) {
        downloadState is ModelDownloadService.DownloadState.Extracting
    }
    val isProcessing = remember(downloadState) {
        downloadState is ModelDownloadService.DownloadState.Processing
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
                    ModelTypeBadge(model.modelType)
                    Text(
                        text = model.name,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = Glass.TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                when {
                    isInstalled -> {
                        Icon(
                            imageVector = TnIcons.CircleCheck,
                            contentDescription = "Installed",
                            tint = Glass.AccentPrimary,
                            modifier = Modifier.size(24.dp)
                        )
                    }

                    isDownloading || isExtracting || isProcessing -> {
                        ActionProgressButton(
                            onClickListener = onCancelDownload,
                            icon = TnIcons.PlayerStop,
                            contentDescription = "Cancel Download"
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
                    color = Glass.AccentPrimary,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier
                        .background(
                            Glass.AccentPrimarySurface,
                            sizeChipShape
                        )
                        .border(1.dp, Glass.AccentPrimary.copy(alpha = 0.2f), sizeChipShape)
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
                        color = Glass.TextSecondary,
                        modifier = Modifier
                            .background(
                                Glass.SurfaceSubtle,
                                repoChipShape
                            )
                            .border(1.dp, Glass.BorderSubtle, repoChipShape)
                            .padding(horizontal = 6.dp, vertical = Standards.SpacingXxs)
                    )
                }

                // Key tags (max 2)
                model.tags.take(2).forEach { tag ->
                    val tagShape = RoundedCornerShape(Standards.SpacingXs)
                    Text(
                        text = tag,
                        style = MaterialTheme.typography.labelSmall,
                        color = Glass.TextMuted,
                        modifier = Modifier
                            .background(
                                Glass.SurfaceSubtle,
                                tagShape
                            )
                            .border(1.dp, Glass.BorderSubtle, tagShape)
                            .padding(horizontal = 5.dp, vertical = Standards.SpacingXxs)
                    )
                }
            }

            // Download progress (animated)
            AnimatedVisibility(
                visible = isDownloading || isExtracting || isProcessing,
                enter = Motion.Enter,
                exit = Motion.Exit
            ) {
                Column(modifier = Modifier.padding(top = Standards.SpacingSm)) {
                    val progress =
                        if (downloadState is ModelDownloadService.DownloadState.Downloading) {
                            downloadState.progress
                        } else 0f

                    val statusText = when {
                        isProcessing -> "Processing..."
                        isExtracting -> {
                            val es = downloadState as ModelDownloadService.DownloadState.Extracting
                            if (es.currentFile.isNotEmpty()) {
                                "Unzipping ${es.currentFile} (${es.extractedCount + 1}/${es.totalFiles})"
                            } else {
                                "Extracting..."
                            }
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
                        color = Glass.AccentPrimary
                    )

                    Spacer(modifier = Modifier.height(Standards.SpacingXs))

                    val isIndeterminate = isProcessing || (isExtracting && (downloadState as ModelDownloadService.DownloadState.Extracting).totalFiles <= 0)
                    val progressVal = when {
                        isDownloading -> progress
                        isExtracting -> {
                            val es = downloadState as ModelDownloadService.DownloadState.Extracting
                            if (es.totalFiles > 0) es.extractedCount.toFloat() / es.totalFiles else 0f
                        }
                        else -> 0f
                    }

                    if (isIndeterminate) {
                        LinearProgressIndicator(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(4.dp)
                                .clip(RoundedCornerShape(2.dp)),
                            color = Glass.AccentPrimary,
                            trackColor = Glass.SurfaceSubtle
                        )
                    } else {
                        LinearProgressIndicator(
                            progress = { progressVal },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(4.dp)
                                .clip(RoundedCornerShape(2.dp)),
                            color = Glass.AccentPrimary,
                            trackColor = Glass.SurfaceSubtle
                        )
                    }
                }
            }
        }
        Spacer(modifier = Modifier.height(Standards.SpacingSm))
        androidx.compose.material3.HorizontalDivider(
            color = Glass.BorderSubtle.copy(alpha = 0.5f),
            thickness = 0.8.dp
        )
    }
}
