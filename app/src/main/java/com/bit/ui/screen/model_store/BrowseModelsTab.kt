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
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
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
import com.bit.ui.components.ActionButton
import com.bit.ui.components.CaptionText
import com.bit.ui.components.GlassCard
import com.bit.ui.theme.Glass
import com.bit.ui.theme.Motion
import com.bit.viewmodel.ModelStoreViewModel
import com.bit.viewmodel.RepoGroupInfo
import com.bit.ui.icons.TnIcons

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
                    LoadingIndicator(color = Glass.AccentPrimary)
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
                            tint = Glass.StatusError
                        )
                        Text(
                            text = "Error loading models",
                            style = MaterialTheme.typography.titleMedium,
                            color = Glass.StatusError
                        )
                        Text(
                            text = error,
                            style = MaterialTheme.typography.bodySmall,
                            color = Glass.TextSecondary
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
                            tint = Glass.TextMuted
                        )
                        Text(
                            text = "No models found",
                            style = MaterialTheme.typography.bodyLarge,
                            color = Glass.TextSecondary
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
                            onCancelDownload = onCancelDownload
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
                    val ramGb = remember { getTotalSystemRamGb(context) }
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
                        color = Glass.AccentPrimary,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(vertical = Standards.SpacingSm, horizontal = Standards.SpacingXs)
                    )
                }

                if (groupedRepos.isEmpty()) {
                    item {
                        Text(
                            text = "No matching local repositories found",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Glass.TextMuted,
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
                        color = Glass.AccentPrimary,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(vertical = Standards.SpacingSm, horizontal = Standards.SpacingXs)
                    )
                }

                if (explorerResults.isEmpty() && !isExplorerLoading) {
                    item {
                        Text(
                            text = explorerError ?: "No online Hugging Face repositories found for \"$searchQuery\"",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Glass.TextMuted,
                            modifier = Modifier.padding(vertical = Standards.SpacingMd, horizontal = Standards.SpacingXs)
                        )
                    }
                }

                items(
                    items = explorerResults,
                    key = { it.id }
                ) { explorerRepo ->
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                viewModel.addExplorerRepository(explorerRepo)
                                viewModel.selectRepository(explorerRepo.id)
                            }
                            .padding(vertical = Standards.SpacingSm)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = Standards.SpacingXs),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(Standards.SpacingSm)
                        ) {
                            ModelTypeBadge(com.bit.models.data.ModelType.GGUF)

                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = explorerRepo.id.substringAfter("/"),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = Glass.TextPrimary,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(Standards.SpacingXs),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    CaptionText(text = explorerRepo.author, color = Glass.TextSecondary)
                                    CaptionText(text = "·", color = Glass.TextMuted)
                                    CaptionText(
                                        text = "${if (explorerRepo.downloads >= 1000000) "${explorerRepo.downloads / 1000000}M" else if (explorerRepo.downloads >= 1000) "${explorerRepo.downloads / 1000}k" else explorerRepo.downloads} DLs",
                                        color = Glass.TextSecondary
                                    )
                                    CaptionText(text = "·", color = Glass.TextMuted)
                                    Row(
                                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(
                                            imageVector = TnIcons.Heart,
                                            contentDescription = "Likes",
                                            tint = Glass.TextSecondary,
                                            modifier = Modifier.size(10.dp)
                                        )
                                        CaptionText(text = explorerRepo.likes.toString(), color = Glass.TextSecondary)
                                    }

                                    if (explorerRepo.gated) {
                                        CaptionText(text = "·", color = Glass.TextMuted)
                                        Row(
                                            horizontalArrangement = Arrangement.spacedBy(2.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Icon(
                                                imageVector = TnIcons.Lock,
                                                contentDescription = "Gated",
                                                tint = Glass.StatusWarning,
                                                modifier = Modifier.size(10.dp)
                                            )
                                            CaptionText(text = "Gated", color = Glass.StatusWarning)
                                        }
                                    }
                                }
                            }

                            Icon(
                                imageVector = TnIcons.Plus,
                                contentDescription = "Add and View",
                                modifier = Modifier.size(16.dp),
                                tint = Glass.AccentPrimary
                            )
                        }
                        Spacer(modifier = Modifier.height(Standards.SpacingSm))
                        HorizontalDivider(
                            color = Glass.BorderSubtle.copy(alpha = 0.5f),
                            thickness = 0.8.dp
                        )
                    }
                }
            }
        }

        if (isLoading || isExplorerLoading) {
            LoadingIndicator(
                modifier = Modifier.align(Alignment.Center),
                color = Glass.AccentPrimary
            )
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
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = Standards.SpacingSm)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = Standards.SpacingXs),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Standards.SpacingSm)
        ) {
            ModelTypeBadge(info.modelType)

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = info.displayName,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Glass.TextPrimary,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(Standards.SpacingXs),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (info.author.isNotEmpty()) {
                        CaptionText(text = info.author, color = Glass.TextSecondary)
                        CaptionText(text = "·", color = Glass.TextMuted)
                    }
                    CaptionText(
                        text = "${info.modelCount} ${if (info.modelCount == 1) "model" else "models"}",
                        color = Glass.TextSecondary
                    )
                    if (hasActiveDownload) {
                        CaptionText(text = "·", color = Glass.TextMuted)
                        LoadingIndicator(
                            modifier = Modifier.size(10.dp),
                            color = Glass.AccentPrimary
                        )
                    }
                }
            }

            Icon(
                imageVector = TnIcons.ChevronRight,
                contentDescription = "View models",
                modifier = Modifier.size(16.dp),
                tint = Glass.TextMuted
            )
        }
        Spacer(modifier = Modifier.height(Standards.SpacingSm))
        HorizontalDivider(
            color = Glass.BorderSubtle.copy(alpha = 0.5f),
            thickness = 0.8.dp
        )
    }
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
    onCancelDownload: (String) -> Unit
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
            ActionButton(
                onClickListener = { viewModel.selectRepository(null) },
                icon = TnIcons.ArrowLeft,
                contentDescription = "Back to repos"
            )
            repoInfo?.let { info ->
                ModelTypeBadge(info.modelType)
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = info.displayName,
                        style = MaterialTheme.typography.titleSmall,
                        color = Glass.TextPrimary,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (info.author.isNotEmpty()) {
                        CaptionText(text = info.author, color = Glass.TextSecondary)
                    }
                }
                CaptionText(text = "${info.modelCount} models", color = Glass.TextSecondary)
            }
        }

        HorizontalDivider(
            color = Glass.BorderSubtle
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
                        onCancelDownload = { onCancelDownload(model.id) }
                    )
                }
            }

            if (isLoading) {
                LoadingIndicator(
                    modifier = Modifier.align(Alignment.Center),
                    color = Glass.AccentPrimary
                )
            }
        }
    }
}

private fun getTotalSystemRamGb(context: android.content.Context): Double {
    return try {
        val activityManager = context.getSystemService(android.content.Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        val memoryInfo = android.app.ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memoryInfo)
        memoryInfo.totalMem / (1024.0 * 1024.0 * 1024.0)
    } catch (e: Exception) {
        8.0
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

    GlassCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Standards.SpacingXs, vertical = Standards.SpacingSm),
        cornerRadius = Standards.RadiusLg,
        onClick = onClick
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Standards.SpacingMd),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Standards.SpacingSm)
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = TnIcons.Bolt,
                        contentDescription = null,
                        tint = Glass.AccentWarm,
                        modifier = Modifier.size(12.dp)
                    )
                    Text(
                        text = "RECOMMENDED FOR YOUR DEVICE",
                        style = MaterialTheme.typography.labelSmall,
                        color = Glass.AccentWarm,
                        fontWeight = FontWeight.Bold
                    )
                }
                Spacer(modifier = Modifier.height(Standards.SpacingXs))
                Text(
                    text = modelName,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = Glass.TextPrimary
                )
                Spacer(modifier = Modifier.height(2.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(Standards.SpacingSm),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CaptionText(text = sizeStr, color = Glass.TextSecondary)
                    CaptionText(text = "·", color = Glass.TextMuted)
                    CaptionText(text = ramStr, color = Glass.TextMuted)
                }
            }
            Icon(
                imageVector = TnIcons.ChevronRight,
                contentDescription = null,
                tint = Glass.TextMuted,
                modifier = Modifier.size(16.dp)
            )
        }
    }
}
