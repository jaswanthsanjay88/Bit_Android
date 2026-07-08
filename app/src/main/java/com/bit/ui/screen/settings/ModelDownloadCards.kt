package com.bit.ui.screen.settings

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.bit.global.Standards
import com.bit.global.formatDecimalBytes
import com.bit.service.ModelDownloadService
import com.bit.ui.components.CaptionText
import com.bit.ui.components.StandardCard
import com.bit.ui.icons.TnIcons
import com.bit.ui.theme.Glass
import com.bit.ui.theme.Motion

// ── Reusable Download Card ──

@Composable
internal fun ModelDownloadCard(
    title: String,
    description: String,
    downloadState: ModelDownloadService.DownloadState?,
    onDownload: () -> Unit,
    successText: String = "Downloaded",
    isInstalled: Boolean = false,
    onActivate: (() -> Unit)? = null
) {
    StandardCard(title = title) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .animateContentSize(Motion.content()),
            verticalArrangement = Arrangement.spacedBy(Standards.SpacingSm)
        ) {
            description.split(" · ").forEach { line ->
                CaptionText(text = line)
            }
 
            when {
                isInstalled -> {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CaptionText(
                            text = successText,
                            color = if (onActivate == null) Glass.StatusSuccess else Glass.TextSecondary
                        )
                        if (onActivate != null) {
                            FilledTonalButton(
                                onClick = onActivate,
                                modifier = Modifier.height(32.dp),
                                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = Standards.SpacingMd)
                            ) {
                                Text("Activate", style = MaterialTheme.typography.labelSmall)
                            }
                        } else {
                            Icon(
                                imageVector = TnIcons.Check,
                                contentDescription = "Active",
                                tint = Glass.StatusSuccess,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }

                downloadState is ModelDownloadService.DownloadState.Downloading -> {
                    val progress = downloadState.progress
                    val downloadedStr = formatDecimalBytes(downloadState.downloadedBytes)
                    val totalStr = formatDecimalBytes(downloadState.totalBytes)
                    val speedStr = if (downloadState.speedBytesPerSec > 0) {
                        "${formatDecimalBytes(downloadState.speedBytesPerSec)}/s"
                    } else ""
                    val etaStr = if (downloadState.etaSeconds >= 0) {
                        val mins = downloadState.etaSeconds / 60
                        val secs = downloadState.etaSeconds % 60
                        if (mins > 0) "ETA: ${mins}m ${secs}s" else "ETA: ${secs}s"
                    } else ""

                    Column(verticalArrangement = Arrangement.spacedBy(Standards.SpacingXs)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            LinearProgressIndicator(
                                progress = { progress },
                                modifier = Modifier
                                    .weight(1f)
                                    .height(6.dp)
                                    .clip(RoundedCornerShape(3.dp)),
                                color = Glass.AccentPrimary,
                                trackColor = Glass.SurfaceSubtle
                            )
                            Spacer(Modifier.width(Standards.SpacingMd))
                            Text(
                                "${(progress * 100).toInt()}%",
                                style = MaterialTheme.typography.labelMedium.copy(
                                    fontWeight = FontWeight.SemiBold
                                ),
                                color = Glass.TextPrimary
                            )
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            CaptionText(text = "$downloadedStr / $totalStr")
                            if (speedStr.isNotEmpty() || etaStr.isNotEmpty()) {
                                CaptionText(
                                    text = listOfNotNull(speedStr.takeIf { it.isNotEmpty() }, etaStr.takeIf { it.isNotEmpty() })
                                        .joinToString(" · ")
                                )
                            }
                        }
                    }
                }

                downloadState is ModelDownloadService.DownloadState.Extracting -> {
                    val file = downloadState.currentFile
                    val count = downloadState.extractedCount
                    val total = downloadState.totalFiles

                    Column(verticalArrangement = Arrangement.spacedBy(Standards.SpacingXs)) {
                        LinearProgressIndicator(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(6.dp)
                                .clip(RoundedCornerShape(3.dp)),
                            color = Glass.AccentPrimary,
                            trackColor = Glass.SurfaceSubtle
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            CaptionText(
                                text = if (file.isNotEmpty()) "Extracting: $file" else "Extracting archive...",
                                modifier = Modifier.weight(1f),
                                color = Glass.TextSecondary
                            )
                            if (total > 0) {
                                CaptionText(text = "$count / $total")
                            } else if (count > 0) {
                                CaptionText(text = "$count files")
                            }
                        }
                    }
                }

                downloadState is ModelDownloadService.DownloadState.Processing -> {
                    Column(verticalArrangement = Arrangement.spacedBy(Standards.SpacingXs)) {
                        LinearProgressIndicator(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(6.dp)
                                .clip(RoundedCornerShape(3.dp)),
                            color = Glass.AccentPrimary,
                            trackColor = Glass.SurfaceSubtle
                        )
                        CaptionText(text = "Processing and finalizing model...")
                    }
                }

                downloadState is ModelDownloadService.DownloadState.Success -> {
                    CaptionText(text = successText, color = Glass.StatusSuccess)
                }

                downloadState is ModelDownloadService.DownloadState.Error -> {
                    Text(
                        text = downloadState.message,
                        style = MaterialTheme.typography.bodySmall,
                        color = Glass.StatusError
                    )
                    FilledTonalButton(onClick = onDownload) {
                        Text("Retry")
                    }
                }

                else -> {
                    FilledTonalButton(onClick = onDownload) {
                        Icon(
                            TnIcons.Download,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(Standards.SpacingSm))
                        Text("Download")
                    }
                }
            }
        }
    }
}
