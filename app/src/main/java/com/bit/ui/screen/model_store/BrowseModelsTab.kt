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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
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

// ── ModelsTab ──

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
    val selectedRepo by viewModel.selectedRepository.collectAsStateWithLifecycle()

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
                AnimatedContent(
                    targetState = selectedRepo,
                    transitionSpec = {
                        fadeIn(Motion.state()) togetherWith
                                fadeOut(Motion.state())
                    },
                    label = "repo_nav"
                ) { repoKey ->
                    if (repoKey == null) {
                        // Repo card list view
                        RepoCardListView(
                            viewModel = viewModel,
                            isLoading = isLoading,
                            downloadStates = downloadStates
                        )
                    } else {
                        // Model detail view inside a repo
                        RepoDetailView(
                            repoKey = repoKey,
                            viewModel = viewModel,
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
    }

    // Handle back press to return from detail to repo list
    if (selectedRepo != null) {
        BackHandler {
            viewModel.selectRepository(null)
        }
    }
}

// ── RepoCardListView ──

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun RepoCardListView(
    viewModel: ModelStoreViewModel,
    isLoading: Boolean,
    downloadStates: Map<String, ModelDownloadService.DownloadState>
) {
    val groupedRepos = remember(viewModel.filteredModels.collectAsStateWithLifecycle().value) {
        viewModel.getGroupedRepos()
    }
    val searchQuery by viewModel.searchQuery.collectAsStateWithLifecycle()
    val explorerResults by viewModel.explorerResults.collectAsStateWithLifecycle()
    val isExplorerLoading by viewModel.isExplorerLoading.collectAsStateWithLifecycle()
    val explorerError by viewModel.explorerError.collectAsStateWithLifecycle()

    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .then(if (isLoading || isExplorerLoading) Modifier.blur(4.dp) else Modifier),
            contentPadding = PaddingValues(horizontal = Standards.SpacingMd, vertical = Standards.SpacingSm),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            flingBehavior = ScrollableDefaults.flingBehavior()
        ) {
            if (searchQuery.isBlank()) {
                item {
                    val context = androidx.compose.ui.platform.LocalContext.current
                    val ramGb = remember { HardwareScanner.getTotalSystemRamGb(context) }
                    RecommendedModelCard(systemRamGb = ramGb, onClick = {
                        val query = when {
                            ramGb < 4.0 -> "Qwen2.5-0.5B"
                            ramGb < 8.0 -> "Llama-3.2-3B"
                            ramGb < 12.0 -> "Llama-3-8B"
                            else -> "Qwen2.5-14B"
                        }
                        viewModel.filterModels(query)
                        viewModel.setExplorerQuery(query)
                        viewModel.searchExplorerRepositories()
                    })
                }
            }

            if (searchQuery.isNotBlank()) {
                item {
                    Text(
                        text = "Local Repositories",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(vertical = Standards.SpacingSm, horizontal = Standards.SpacingXs)
                    )
                }

                if (groupedRepos.isEmpty()) {
                    item {
                        Text(
                            text = "No matching local repositories found",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = Standards.SpacingMd, horizontal = Standards.SpacingXs)
                        )
                    }
                }
            }

            items(
                items = groupedRepos.entries.toList(),
                key = { it.key }
            ) { (repoKey, info) ->
                val repoModels = remember(groupedRepos, repoKey) { viewModel.getModelsForRepo(repoKey) }
                val hasActiveDownload = repoModels.any { model ->
                    val state = downloadStates[model.id]
                    state is ModelDownloadService.DownloadState.Downloading ||
                            state is ModelDownloadService.DownloadState.Extracting ||
                            state is ModelDownloadService.DownloadState.Processing
                }

                StoreRepoCard(
                    info = info,
                    hasActiveDownload = hasActiveDownload,
                    onClick = { viewModel.selectRepository(repoKey) }
                )
            }

            if (searchQuery.isNotBlank()) {
                item {
                    Spacer(modifier = Modifier.padding(top = Standards.SpacingLg))
                    Text(
                        text = "Explore Hugging Face",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(vertical = Standards.SpacingSm, horizontal = Standards.SpacingXs)
                    )
                }

                if (explorerResults.isEmpty() && !isExplorerLoading) {
                    item {
                        Text(
                            text = explorerError ?: "No online Hugging Face repositories found for \"$searchQuery\"",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = Standards.SpacingMd, horizontal = Standards.SpacingXs)
                        )
                    }
                }

                items(
                    items = explorerResults,
                    key = { it.id }
                ) { explorerRepo ->
                    RepoListItem(
                        title = explorerRepo.id.substringAfter("/"),
                        modelType = com.bit.models.data.ModelType.GGUF,
                        onClick = {
                            viewModel.addExplorerRepository(explorerRepo)
                        },
                        subtitle = {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(Standards.SpacingXs),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = TnIcons.Heart,
                                        contentDescription = "Likes",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(10.dp)
                                    )
                                    CaptionText(text = explorerRepo.likes.toString(), color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }

                                if (explorerRepo.gated) {
                                    CaptionText(text = "·", color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f))
                                    Row(
                                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(
                                            imageVector = TnIcons.Lock,
                                            contentDescription = "Gated",
                                            tint = MaterialTheme.colorScheme.error,
                                            modifier = Modifier.size(10.dp)
                                        )
                                        CaptionText(text = "Gated", color = MaterialTheme.colorScheme.error)
                                    }
                                }
                            }
                        },
                        rightContent = {
                            Icon(
                                imageVector = TnIcons.Plus,
                                contentDescription = "Add and View",
                                modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    )
                }
            }
        }

        if (isLoading || isExplorerLoading) {
            CircularProgressIndicator(
                modifier = Modifier.align(Alignment.Center),
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}

// ── Shared RepoListItem ──

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
                            com.bit.models.data.ModelType.SD -> TnIcons.Photo
                            com.bit.models.data.ModelType.TTS -> TnIcons.Volume
                            com.bit.models.data.ModelType.STT -> TnIcons.Microphone
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

// ── StoreRepoCard ──

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

// ── RepoDetailView ──

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun RepoDetailView(
    repoKey: String,
    viewModel: ModelStoreViewModel,
    isLoading: Boolean,
    downloadStates: Map<String, ModelDownloadService.DownloadState>,
    installedModelIds: Set<String>,
    onDownload: (HuggingFaceModel) -> Unit,
    onCancelDownload: (String) -> Unit,
    onPauseDownload: (String) -> Unit = {},
    onResumeDownload: (String, String) -> Unit = { _, _ -> }
) {
    val repoModels = remember(viewModel.filteredModels.collectAsStateWithLifecycle().value, repoKey) {
        viewModel.getModelsForRepo(repoKey)
    }
    val groupedRepos = remember(viewModel.filteredModels.collectAsStateWithLifecycle().value) {
        viewModel.getGroupedRepos()
    }
    val repoInfo = groupedRepos[repoKey]

    Column(modifier = Modifier.fillMaxSize()) {
        // Back header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Standards.SpacingSm, vertical = Standards.SpacingXs),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Standards.SpacingXs)
        ) {
            FilledTonalIconButton(
                onClick = { viewModel.selectRepository(null) },
                colors = IconButtonDefaults.filledTonalIconButtonColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                )
            ) {
                Icon(
                    imageVector = TnIcons.ArrowLeft,
                    contentDescription = "Back to repos",
                    tint = MaterialTheme.colorScheme.onSurface
                )
            }
            repoInfo?.let { info ->
                ModelTypeBadge(info.modelType)
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = info.displayName,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onBackground,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (info.author.isNotEmpty()) {
                        CaptionText(text = info.author, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                CaptionText(text = "${info.modelCount} models", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        HorizontalDivider(
            color = MaterialTheme.colorScheme.outlineVariant
        )

        Box(modifier = Modifier.fillMaxSize()) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .then(if (isLoading) Modifier.blur(4.dp) else Modifier),
                contentPadding = PaddingValues(horizontal = Standards.SpacingMd, vertical = Standards.SpacingSm),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                flingBehavior = ScrollableDefaults.flingBehavior()
            ) {
                items(
                    items = repoModels,
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
}


@Composable
internal fun RecommendedModelCard(systemRamGb: Double, onClick: () -> Unit) {
    val (modelName, sizeStr, ramStr) = when {
        systemRamGb < 4.0 -> Triple("Qwen 2.5 0.5B (Q4_K_M)", "~0.39 GB", "Fits under 4GB RAM")
        systemRamGb < 8.0 -> Triple("Llama 3.2 3B (Q4_K_M)", "~2.02 GB", "Optimized for 4-6GB RAM")
        systemRamGb < 12.0 -> Triple("Llama 3 8B (Q4_K_M)", "~4.78 GB", "Recommended for 8-12GB RAM")
        else -> Triple("Qwen 2.5 14B (Q4_K_M)", "~9.05 GB", "Best for 12GB+ premium RAM")
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = Standards.SpacingXs, vertical = Standards.SpacingSm),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        shape = RoundedCornerShape(20.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Standards.SpacingMd),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Standards.SpacingSm)
        ) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.secondaryContainer,
                modifier = Modifier.size(36.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = TnIcons.Bolt,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "RECOMMENDED FOR YOUR DEVICE",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.1.sp
                )
                Spacer(modifier = Modifier.height(Standards.SpacingXs))
                Text(
                    text = modelName,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(2.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(Standards.SpacingSm),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CaptionText(text = sizeStr, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    CaptionText(text = "·", color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f))
                    CaptionText(text = ramStr, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f))
                }
            }
            Icon(
                imageVector = TnIcons.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                modifier = Modifier.size(16.dp)
            )
        }
    }
}
