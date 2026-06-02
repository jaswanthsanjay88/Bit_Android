package com.bit.ui.screen.model_store

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.hilt.navigation.compose.hiltViewModel
import com.bit.global.Standards
import com.bit.ui.components.ActionButton
import com.bit.ui.components.GlassDivider
import com.bit.ui.theme.Glass
import com.bit.ui.theme.Motion
import com.bit.viewmodel.ModelStoreViewModel
import com.bit.ui.icons.TnIcons

// ── StoreTab ──

enum class StoreTab {
    MODELS, INSTALLED, SETTINGS
}

// ── GlassSegmentedTab ──

@Composable
private fun GlassSegmentedTab(
    text: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.95f else 1f,
        animationSpec = Motion.interactive(),
        label = "tabScale"
    )
    val backgroundColor by animateColorAsState(
        targetValue = if (isSelected) Glass.AccentPrimarySurface else Glass.SurfaceSubtle,
        animationSpec = Motion.state(),
        label = "tabBg"
    )
    val borderColor by animateColorAsState(
        targetValue = if (isSelected) Glass.AccentPrimary.copy(alpha = 0.4f) else Glass.BorderSubtle,
        animationSpec = Motion.state(),
        label = "tabBorder"
    )
    val textColor by animateColorAsState(
        targetValue = if (isSelected) Glass.AccentPrimary else Glass.TextSecondary,
        animationSpec = Motion.state(),
        label = "tabText"
    )

    val shape = RoundedCornerShape(Standards.RadiusMd)

    Box(
        modifier = modifier
            .scale(scale)
            .clip(shape)
            .background(backgroundColor, shape)
            .border(1.dp, borderColor, shape)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            )
            .padding(horizontal = Standards.SpacingMd, vertical = Standards.SpacingSm),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
            color = textColor
        )
    }
}

// ── ModelStoreScreen ──

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelStoreScreen(
    onNavigateBack: () -> Unit, viewModel: ModelStoreViewModel = hiltViewModel()
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
                                color = Glass.TextPrimary,
                                fontWeight = FontWeight.SemiBold
                            )
                        },
                        navigationIcon = {
                            ActionButton(
                                onClickListener = onNavigateBack,
                                icon = TnIcons.ArrowLeft,
                                contentDescription = "Back"
                            )
                        },
                        actions = {
                            if (selectedTab == StoreTab.MODELS) {
                                ActionButton(
                                    onClickListener = { viewModel.refreshModels() },
                                    icon = TnIcons.Refresh,
                                    contentDescription = "Refresh"
                                )
                                ActionButton(
                                    onClickListener = { showSearch = true },
                                    icon = TnIcons.Search,
                                    contentDescription = "Search"
                                )
                            }
                        },
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = MaterialTheme.colorScheme.background
                        )
                    )
                }
                // Glass border at the bottom of the top bar
                GlassDivider()
            }
        }) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // Glassmorphic Segmented Control
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Glass.SurfaceSubtle)
                    .border(
                        width = 1.dp,
                        color = Glass.BorderSubtle,
                        shape = RoundedCornerShape(0.dp)
                    )
                    .padding(
                        horizontal = Standards.SpacingMd,
                        vertical = Standards.SpacingSm
                    )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(Standards.RadiusMd))
                        .background(Glass.Surface, RoundedCornerShape(Standards.RadiusMd))
                        .border(1.dp, Glass.BorderSubtle, RoundedCornerShape(Standards.RadiusMd))
                        .padding(Standards.SpacingXxs),
                    horizontalArrangement = Arrangement.spacedBy(Standards.SpacingXxs)
                ) {
                    GlassSegmentedTab(
                        text = "Store",
                        isSelected = selectedTab == StoreTab.MODELS,
                        onClick = { viewModel.selectTab(StoreTab.MODELS) },
                        modifier = Modifier.weight(1f)
                    )
                    GlassSegmentedTab(
                        text = "Installed",
                        isSelected = selectedTab == StoreTab.INSTALLED,
                        onClick = { viewModel.selectTab(StoreTab.INSTALLED) },
                        modifier = Modifier.weight(1f)
                    )
                    GlassSegmentedTab(
                        text = "Settings",
                        isSelected = selectedTab == StoreTab.SETTINGS,
                        onClick = { viewModel.selectTab(StoreTab.SETTINGS) },
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            // Tab Content
            AnimatedContent(
                targetState = selectedTab, transitionSpec = {
                    fadeIn(Motion.state()) togetherWith fadeOut(Motion.state())
                }, label = "tab_content"
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
                        onRetry = { viewModel.loadModels() })

                    StoreTab.INSTALLED -> InstalledModelsTab(
                        models = installedModels,
                        deleteInProgress = deleteInProgress,
                        onDelete = { viewModel.deleteModel(it) },
                        viewModel = viewModel
                    )

                    StoreTab.SETTINGS -> SettingsTab(
                        deviceInfo = deviceInfo, viewModel = viewModel
                    )
                }
            }
        }
    }
}
