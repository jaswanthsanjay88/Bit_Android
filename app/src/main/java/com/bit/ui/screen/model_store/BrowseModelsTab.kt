package com.bit.ui.screen.model_store

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.gestures.ScrollableDefaults
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bit.models.data.HuggingFaceModel
import com.bit.service.ModelDownloadService
import com.bit.global.Standards
import com.bit.global.HardwareScanner
import com.bit.ui.components.ActionButton
import com.bit.ui.components.CaptionText
import com.bit.ui.theme.Motion
import com.bit.viewmodel.ModelStoreViewModel
import com.bit.viewmodel.RepoGroupInfo
import com.bit.ui.icons.TnIcons
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.shape.CircleShape
import com.bit.ui.components.GlassCard
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.IconButtonDefaults

// ── ModelsTab (Curated flat list) ──

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun ModelsTab(
    models: List<HuggingFaceModel>,
    isLoading: Boolean,
    error: String?,
    downloadStates: Map<String, ModelDownloadService.DownloadState>,
    installedModelIds: Set<String>,
    viewModel: ModelStoreViewModel,
    onDownload: (HuggingFaceModel) -> Unit,
    onCancelDownload: (String) -> Unit,
    onPauseDownload: (String) -> Unit = {},
    onResumeDownload: (String, String) -> Unit = { _, _ -> },
    onRetry: () -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {
        ModelFiltersSection(viewModel = viewModel)

        when {
            isLoading && models.isEmpty() -> {
                Box(
                    modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                }
            }

            error != null && models.isEmpty() -> {
                Box(
                    modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(Standards.SpacingLg)
                    ) {
                        Icon(
                            imageVector = TnIcons.AlertTriangle,
                            contentDescription = null,
                            modifier = Modifier.size(48.dp),
                            tint = MaterialTheme.colorScheme.error
                        )
                        Text(
                            text = "Error loading models",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.error
                        )
                        Text(
                            text = error,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Button(onClick = onRetry) {
                            Text("Retry")
                        }
                    }
                }
            }

            models.isEmpty() -> {
                Box(
                    modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(Standards.SpacingMd)
                    ) {
                        Icon(
                            imageVector = TnIcons.SearchOff,
                            contentDescription = null,
                            modifier = Modifier.size(48.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                        )
                        Text(
                            text = "No models found",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            else -> {
                // Flat curated model list — no repo navigation
                CuratedModelList(
                    models = models,
                    isLoading = isLoading,
                    downloadStates = downloadStates,
                    installedModelIds = installedModelIds,
                    onDownload = onDownload,
                    onCancelDownload = onCancelDownload,
                    onPauseDownload = onPauseDownload,
                    onResumeDownload = onResumeDownload
                )
            }
        }
    }
}

// ── CuratedModelList (flat, no repo grouping) ──

@Composable
internal fun CuratedModelList(
    models: List<HuggingFaceModel>,
    isLoading: Boolean,
    downloadStates: Map<String, ModelDownloadService.DownloadState>,
    installedModelIds: Set<String>,
    onDownload: (HuggingFaceModel) -> Unit,
    onCancelDownload: (String) -> Unit,
    onPauseDownload: (String) -> Unit = {},
    onResumeDownload: (String, String) -> Unit = { _, _ -> }
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val ramGb = remember { HardwareScanner.getTotalSystemRamGb(context) }

    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .then(if (isLoading) Modifier.blur(4.dp) else Modifier),
            contentPadding = PaddingValues(horizontal = Standards.SpacingMd, vertical = Standards.SpacingSm),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            flingBehavior = ScrollableDefaults.flingBehavior()
        ) {
            // Recommended model card
            item {
                RecommendedModelCard(
                    systemRamGb = ramGb,
                    models = models,
                    installedModelIds = installedModelIds,
                    downloadStates = downloadStates,
                    onDownload = onDownload,
                    onCancelDownload = onCancelDownload,
                    onPauseDownload = onPauseDownload,
                    onResumeDownload = onResumeDownload
                )
            }

            items(
                items = models,
                key = { model -> model.id }
            ) { model ->
                ModelCard(
                    model = model,
                    isInstalled = installedModelIds.contains(model.id),
                    downloadState = downloadStates[model.id],
                    onDownload = { onDownload(model) },
                    onCancelDownload = { onCancelDownload(model.id) },
                    onPauseDownload = { onPauseDownload(model.id) },
                    onResumeDownload = { onResumeDownload(model.id, model.name) }
                )
            }
        }

        if (isLoading) {
            CircularProgressIndicator(
                modifier = Modifier.align(Alignment.Center),
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}

// ── Shared RepoListItem (kept for Advanced tab) ──

@Composable
internal fun RepoListItem(
    title: String,
    modelType: com.bit.models.data.ModelType,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: @Composable () -> Unit,
    rightContent: @Composable () -> Unit
) {
    GlassCard(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Standards.SpacingSm)
        ) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                modifier = Modifier.size(40.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = when (modelType) {
                            com.bit.models.data.ModelType.GGUF -> TnIcons.Sparkles
                            com.bit.models.data.ModelType.VLM -> TnIcons.Sparkles
                            com.bit.models.data.ModelType.SD -> TnIcons.Photo
                            com.bit.models.data.ModelType.TTS -> TnIcons.Volume
                            com.bit.models.data.ModelType.STT -> TnIcons.Microphone
                            com.bit.models.data.ModelType.EMBEDDING -> TnIcons.FileText
                        },
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }

            Column(modifier = Modifier.weight(1f)) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onBackground,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    ModelTypeBadge(modelType)
                }
                Spacer(modifier = Modifier.height(4.dp))
                subtitle()
            }

            rightContent()
        }
    }
}

// ── StoreRepoCard (kept for Advanced tab) ──

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun StoreRepoCard(
    info: RepoGroupInfo,
    hasActiveDownload: Boolean,
    onClick: () -> Unit
) {
    RepoListItem(
        title = info.displayName,
        modelType = info.modelType,
        onClick = onClick,
        subtitle = {
            Row(
                horizontalArrangement = Arrangement.spacedBy(Standards.SpacingXs),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (info.author.isNotEmpty()) {
                    CaptionText(text = info.author, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    CaptionText(text = "·", color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f))
                }
                CaptionText(
                    text = "${info.modelCount} ${if (info.modelCount == 1) "model" else "models"}",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (hasActiveDownload) {
                    CaptionText(text = "·", color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f))
                    CircularProgressIndicator(
                        modifier = Modifier.size(10.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        },
        rightContent = {
            Icon(
                imageVector = TnIcons.ChevronRight,
                contentDescription = "View models",
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    )
}

// ── RecommendedModelCard (updated for curated models) ──

@Composable
internal fun RecommendedModelCard(
    systemRamGb: Double,
    models: List<HuggingFaceModel>,
    installedModelIds: Set<String> = emptySet(),
    downloadStates: Map<String, ModelDownloadService.DownloadState> = emptyMap(),
    onDownload: (HuggingFaceModel) -> Unit,
    onCancelDownload: ((String) -> Unit)? = null,
    onPauseDownload: ((String) -> Unit)? = null,
    onResumeDownload: ((String, String) -> Unit)? = null
) {
    // Find the best matching model from the curated list based on RAM,
    // prioritizing models the user has NOT yet installed so they are not recommended
    // something they already have.
    val recommended = remember(models, systemRamGb, installedModelIds) {
        val ggufModels = models.filter { it.modelType == com.bit.models.data.ModelType.GGUF }
        val uninstalled = ggufModels.filter { it.id !in installedModelIds }
        val pool = if (uninstalled.isNotEmpty()) uninstalled else ggufModels
        pool
            .filter { it.minRamGb > 0 && it.minRamGb <= systemRamGb }
            .maxByOrNull { it.sizeBytes }
            ?: pool.minByOrNull { it.sizeBytes }
    }

    if (recommended == null) return

    val isInstalled = installedModelIds.contains(recommended.id)
    val dlState = downloadStates[recommended.id]
    val isDownloading = dlState is ModelDownloadService.DownloadState.Downloading
    val isPaused = dlState is ModelDownloadService.DownloadState.Paused
    val isExtracting = dlState is ModelDownloadService.DownloadState.Extracting ||
            dlState is ModelDownloadService.DownloadState.Processing ||
            dlState is ModelDownloadService.DownloadState.Verifying

    val ramStr = when {
        systemRamGb < 4.0 -> "Fits under 4GB RAM"
        systemRamGb < 8.0 -> "Optimized for 4-6GB RAM"
        systemRamGb < 12.0 -> "Recommended for 8-12GB RAM"
        else -> "Best for 12GB+ RAM"
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (!isInstalled && !isDownloading && !isExtracting) {
                    Modifier.clickable(onClick = { onDownload(recommended) })
                } else Modifier
            )
            .padding(horizontal = Standards.SpacingXs, vertical = Standards.SpacingSm),
        colors = CardDefaults.cardColors(
            containerColor = if (isInstalled) {
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f)
            } else {
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            }
        ),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            if (isInstalled) MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)
            else MaterialTheme.colorScheme.outlineVariant
        ),
        shape = RoundedCornerShape(20.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Standards.SpacingMd),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Standards.SpacingSm)
            ) {
                Surface(
                    shape = CircleShape,
                    color = if (isInstalled) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                            else MaterialTheme.colorScheme.secondaryContainer,
                    modifier = Modifier.size(36.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = if (isInstalled) TnIcons.CircleCheck else TnIcons.Bolt,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (isInstalled) "RECOMMENDED FOR YOUR DEVICE • INSTALLED"
                               else "RECOMMENDED FOR YOUR DEVICE",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.1.sp
                    )
                    Spacer(modifier = Modifier.height(Standards.SpacingXs))
                    Text(
                        text = recommended.name,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(Standards.SpacingSm),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CaptionText(
                            text = if (isInstalled) "Installed · ${recommended.approximateSize}"
                                   else recommended.approximateSize,
                            color = if (isInstalled) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        CaptionText(text = "·", color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f))
                        CaptionText(text = ramStr, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f))
                    }
                }

                when {
                    isInstalled -> {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                            modifier = Modifier.padding(start = 4.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Icon(
                                    imageVector = TnIcons.CircleCheck,
                                    contentDescription = "Installed",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(16.dp)
                                )
                                Text(
                                    text = "Ready",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }

                    isDownloading -> {
                        TextButton(
                            onClick = { onCancelDownload?.invoke(recommended.id) },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                        ) {
                            Text(
                                "Cancel",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.error,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }

                    isPaused -> {
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            TextButton(
                                onClick = { onResumeDownload?.invoke(recommended.id, recommended.name) },
                                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)
                            ) {
                                Text("Resume", style = MaterialTheme.typography.labelSmall)
                            }
                            TextButton(
                                onClick = { onCancelDownload?.invoke(recommended.id) },
                                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)
                            ) {
                                Text("Cancel", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }

                    isExtracting -> {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }

                    else -> {
                        Icon(
                            imageVector = TnIcons.Download,
                            contentDescription = "Download ${recommended.name}",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }

            // Download progress bar if currently downloading
            if (isDownloading) {
                val downloadingState = dlState as? ModelDownloadService.DownloadState.Downloading
                val progress = downloadingState?.progress ?: 0f
                val pct = (progress * 100).toInt()
                val speedText = if (downloadingState != null && downloadingState.speedBytesPerSec > 0) {
                    " · %.1f MB/s".format(downloadingState.speedBytesPerSec / 1_000_000.0)
                } else ""
                val etaText = if (downloadingState != null && downloadingState.etaSeconds > 0) {
                    val mins = downloadingState.etaSeconds / 60
                    val secs = downloadingState.etaSeconds % 60
                    if (mins > 0) " · ${mins}m ${secs}s left" else " · ${secs}s left"
                } else ""

                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(4.dp)
                            .clip(RoundedCornerShape(2.dp)),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "Downloading... $pct%$speedText",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (etaText.isNotEmpty()) {
                            Text(
                                text = etaText.trimStart(' ', '·'),
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
