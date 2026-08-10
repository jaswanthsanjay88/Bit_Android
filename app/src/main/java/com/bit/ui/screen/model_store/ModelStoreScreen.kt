package com.bit.ui.screen.model_store

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.bit.global.Standards
import com.bit.ui.components.ActionButton
import com.bit.ui.theme.Motion
import com.bit.viewmodel.ModelStoreViewModel
import com.bit.ui.icons.TnIcons

import androidx.compose.material3.Icon
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width

enum class StoreTab {
    MODELS, INSTALLED, PROVIDERS, ADVANCED
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelStoreScreen(
    onNavigateBack: () -> Unit,
    llmModelViewModel: com.bit.viewmodel.LLMModelViewModel,
    viewModel: ModelStoreViewModel = hiltViewModel()
) {
    val selectedTab by viewModel.selectedTab.collectAsStateWithLifecycle()
    val models by viewModel.filteredModels.collectAsStateWithLifecycle()
    val isLoading by viewModel.isLoading.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val downloadStates by viewModel.downloadStates.collectAsStateWithLifecycle()
    val installedModels by viewModel.installedModels.collectAsStateWithLifecycle()
    val deviceInfo by viewModel.deviceInfo.collectAsStateWithLifecycle()
    val deleteInProgress by viewModel.deleteInProgress.collectAsStateWithLifecycle()

    var searchQuery by remember { mutableStateOf("") }
    var showSearch by remember { mutableStateOf(false) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            Column {
                if (showSearch) {
                    SearchAppBar(searchQuery = searchQuery, onSearchQueryChange = {
                        searchQuery = it
                        viewModel.filterModels(it)
                        viewModel.setExplorerQuery(it)
                        viewModel.searchExplorerRepositories()
                    }, onCloseSearch = {
                        showSearch = false
                        searchQuery = ""
                        viewModel.filterModels("")
                        viewModel.setExplorerQuery("")
                    })
                } else {
                    TopAppBar(
                        title = {
                            Text(
                                "Model Store",
                                color = MaterialTheme.colorScheme.onBackground,
                                fontWeight = FontWeight.SemiBold
                            )
                        },
                        navigationIcon = {
                            FilledTonalIconButton(
                                onClick = onNavigateBack,
                                colors = IconButtonDefaults.filledTonalIconButtonColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                                )
                            ) {
                                Icon(
                                    imageVector = TnIcons.ArrowLeft,
                                    contentDescription = "Back",
                                    tint = MaterialTheme.colorScheme.onSurface
                                )
                            }
                        },
                        actions = {
                            if (selectedTab == StoreTab.MODELS) {
                                Row {
                                    FilledTonalIconButton(
                                        onClick = { viewModel.refreshModels() },
                                        colors = IconButtonDefaults.filledTonalIconButtonColors(
                                            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                                        )
                                    ) {
                                        Icon(
                                            imageVector = TnIcons.Refresh,
                                            contentDescription = "Refresh",
                                            tint = MaterialTheme.colorScheme.onSurface
                                        )
                                    }
                                    Spacer(modifier = Modifier.width(8.dp))
                                    FilledTonalIconButton(
                                        onClick = { showSearch = true },
                                        colors = IconButtonDefaults.filledTonalIconButtonColors(
                                            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                                        )
                                    ) {
                                        Icon(
                                            imageVector = TnIcons.Search,
                                            contentDescription = "Search",
                                            tint = MaterialTheme.colorScheme.onSurface
                                        )
                                    }
                                }
                            }
                        },
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = MaterialTheme.colorScheme.background
                        )
                    )
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
        }) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // Material 3 SecondaryTabRow
            SecondaryTabRow(
                selectedTabIndex = selectedTab.ordinal,
                containerColor = MaterialTheme.colorScheme.background,
                contentColor = MaterialTheme.colorScheme.primary
            ) {
                Tab(
                    selected = selectedTab == StoreTab.MODELS,
                    onClick = { viewModel.selectTab(StoreTab.MODELS) },
                    text = { Text("Store", fontWeight = if (selectedTab == StoreTab.MODELS) FontWeight.Bold else FontWeight.Normal) }
                )
                Tab(
                    selected = selectedTab == StoreTab.INSTALLED,
                    onClick = { viewModel.selectTab(StoreTab.INSTALLED) },
                    text = { Text("Installed", fontWeight = if (selectedTab == StoreTab.INSTALLED) FontWeight.Bold else FontWeight.Normal) }
                )
                Tab(
                    selected = selectedTab == StoreTab.PROVIDERS,
                    onClick = { viewModel.selectTab(StoreTab.PROVIDERS) },
                    text = { Text("Providers", fontWeight = if (selectedTab == StoreTab.PROVIDERS) FontWeight.Bold else FontWeight.Normal) }
                )
                Tab(
                    selected = selectedTab == StoreTab.ADVANCED,
                    onClick = { viewModel.selectTab(StoreTab.ADVANCED) },
                    text = { Text("Advanced", fontWeight = if (selectedTab == StoreTab.ADVANCED) FontWeight.Bold else FontWeight.Normal) }
                )
            }

            // Tab Content
            AnimatedContent(
                targetState = selectedTab, transitionSpec = {
                    fadeIn(Motion.state()) togetherWith fadeOut(Motion.state())
                }, label = "tab_content",
                modifier = Modifier.weight(1f)
            ) { tab ->
                when (tab) {
                    StoreTab.MODELS -> ModelsTab(
                        models = models,
                        isLoading = isLoading,
                        error = error,
                        downloadStates = downloadStates,
                        installedModelIds = installedModels.map { it.id }.toSet(),
                        viewModel = viewModel,
                        onDownload = { viewModel.downloadModel(it) },
                        onCancelDownload = { modelId -> viewModel.cancelDownload(modelId) },
                        onPauseDownload = { modelId -> viewModel.pauseDownload(modelId) },
                        onResumeDownload = { modelId, modelName -> viewModel.resumeDownload(modelId, modelName) },
                        onRetry = { viewModel.refreshModels() })

                    StoreTab.INSTALLED -> InstalledModelsTab(
                        models = installedModels,
                        deleteInProgress = deleteInProgress,
                        onDelete = { viewModel.deleteModel(it) },
                        viewModel = viewModel,
                        llmModelViewModel = llmModelViewModel
                    )

                    StoreTab.PROVIDERS -> ProvidersTab(
                        viewModel = viewModel,
                        llmModelViewModel = llmModelViewModel
                    )

                    StoreTab.ADVANCED -> AdvancedTab(
                        deviceInfo = deviceInfo, viewModel = viewModel
                    )
                }
            }
        }
    }
}
