package com.bit.ui.screen.settings

import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import android.widget.Toast
import com.bit.global.Standards
import com.bit.plugins.PluginManager
import com.bit.service.ModelDownloadService
import com.bit.ui.components.ActionButton
import com.bit.ui.components.GlassCard
import com.bit.ui.components.GlassSectionCard
import com.bit.ui.components.GlassDivider
import com.bit.ui.icons.TnIcons
import com.bit.ui.theme.Glass
import com.bit.viewmodel.SettingsViewModel
import com.bit.stt.SherpaSTTEngine
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import com.bit.viewmodel.ModelConfigEditorViewModel
import com.bit.ui.screen.model_config.ConfigEditorPanel

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun SettingsScreen(
    onNavigateBack: () -> Unit,
    onModelEditor: () -> Unit = {},
    onAiMemoryClick: () -> Unit = {},
    onEmbeddingSetupClick: () -> Unit = {},
    onDiagnosticsClick: () -> Unit = {},
    viewModel: SettingsViewModel = viewModel(),
    configEditorViewModel: ModelConfigEditorViewModel = hiltViewModel()
) {
    var selectedCategory by remember { mutableStateOf<String?>(null) }
    var editorModel by remember { mutableStateOf<com.bit.models.table_schema.Model?>(null) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var showCredits by remember { mutableStateOf(false) }

    // Intercept back actions to go back to category list first
    BackHandler {
        if (selectedCategory != null) {
            selectedCategory = null
        } else {
            onNavigateBack()
        }
    }

    val context = androidx.compose.ui.platform.LocalContext.current
    val hasSttModel = remember(context) { SherpaSTTEngine.hasModelFiles(context) }
    val haptics = com.bit.ui.theme.LocalBitHaptics.current

    // App settings
    val streamingEnabled by viewModel.streamingEnabled.collectAsStateWithLifecycle()
    val chatMemoryEnabled by viewModel.chatMemoryEnabled.collectAsStateWithLifecycle()
    val globalSystemPrompt by viewModel.globalSystemPrompt.collectAsStateWithLifecycle()
    val globalPrependPrompt by viewModel.globalPrependPrompt.collectAsStateWithLifecycle()
    val globalPostpendPrompt by viewModel.globalPostpendPrompt.collectAsStateWithLifecycle()
    val toolCallingEnabled by viewModel.toolCallingEnabled.collectAsStateWithLifecycle()
    val toolCallingBypassEnabled by viewModel.toolCallingBypassEnabled.collectAsStateWithLifecycle()
    val imageBlurEnabled by viewModel.imageBlurEnabled.collectAsStateWithLifecycle()
    val loadTTSOnStart by viewModel.loadTTSOnStart.collectAsStateWithLifecycle()
    val codeHighlightEnabled by viewModel.codeHighlightEnabled.collectAsStateWithLifecycle()
    val aiMemoryEnabled by viewModel.aiMemoryEnabled.collectAsStateWithLifecycle()
    val speedModeEnabled by viewModel.speedModeEnabled.collectAsStateWithLifecycle()
    val hardwareTuningEnabled by viewModel.hardwareTuningEnabled.collectAsStateWithLifecycle()
    val hardwareProfile by viewModel.hardwareProfile.collectAsStateWithLifecycle()
    val performanceMode by viewModel.performanceMode.collectAsStateWithLifecycle()
    // Installed models
    val installedModels by viewModel.installedModels.collectAsStateWithLifecycle(initialValue = emptyList())

    // Tool calling model state
    val hasToolCallingModel by viewModel.hasToolCallingModel.collectAsStateWithLifecycle()
    val toolCallingDownloadStates by viewModel.toolCallingModelDownloadState.collectAsStateWithLifecycle()
    val toolCallingDownloadState = toolCallingDownloadStates[PluginManager.TOOL_CALLING_MODEL_ID]

    // TTS settings
    val ttsSettings by viewModel.ttsSettings.collectAsStateWithLifecycle()
    val ttsModelLoaded by viewModel.ttsModelLoaded.collectAsStateWithLifecycle()
    val ttsVoices by viewModel.ttsAvailableVoices.collectAsStateWithLifecycle()
    val ttsDownloadStates by viewModel.ttsDownloadStates.collectAsStateWithLifecycle()
    val installedTtsModelId by viewModel.installedTtsModelId.collectAsStateWithLifecycle(initialValue = null)
    val installedTtsModelIds by viewModel.installedTtsModelIds.collectAsStateWithLifecycle(initialValue = emptyList())

    // STT Settings State
    val sttThreads by viewModel.sttThreads.collectAsStateWithLifecycle()
    val sttLanguage by viewModel.sttLanguage.collectAsStateWithLifecycle()

    // Auto-load TTS after download succeeds
    LaunchedEffect(ttsDownloadStates) {
        val anySuccess = ttsDownloadStates.values.any { it is ModelDownloadService.DownloadState.Success }
        if (anySuccess) {
            viewModel.loadTtsAfterDownload()
        }
    }

    // HuggingFace Token
    val hfTokenState by viewModel.hfTokenState.collectAsStateWithLifecycle()
    val hfTestResult by viewModel.hfTestResult.collectAsStateWithLifecycle()

    val voices = ttsVoices.ifEmpty { DEFAULT_VOICES }

    Scaffold(
        containerColor = Color.Black,
        topBar = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color.Black)
                    .padding(horizontal = Standards.SpacingMd, vertical = Standards.SpacingSm)
                    .statusBarsPadding(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = {
                        haptics.selection()
                        if (selectedCategory != null) {
                            selectedCategory = null
                        } else {
                            onNavigateBack()
                        }
                    }
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = Color.White
                    )
                }
                Spacer(modifier = Modifier.width(Standards.SpacingMd))
                Text(
                    text = when (selectedCategory) {
                        "services" -> "Services & Models"
                        "chat" -> "Responses & Chat"
                        "hardware" -> "Hardware Tuning"
                        "intelligence" -> "Intelligence & Tools"
                        "voice" -> "Voice Settings"
                        "storage" -> "Storage & Diagnostics"
                        "about" -> "About BIT"
                        else -> "Settings"
                    },
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
            }
        }
    ) { padding ->
        AnimatedContent(
            targetState = selectedCategory,
            transitionSpec = {
                if (targetState != null) {
                    slideInHorizontally { it } + fadeIn() togetherWith slideOutHorizontally { -it } + fadeOut()
                } else {
                    slideInHorizontally { -it } + fadeIn() togetherWith slideOutHorizontally { it } + fadeOut()
                }
            },
            modifier = Modifier.padding(padding).background(Color.Black),
            label = "settings_navigation"
        ) { category ->
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black),
                contentPadding = PaddingValues(horizontal = Standards.SpacingMd, vertical = Standards.SpacingSm),
                verticalArrangement = Arrangement.spacedBy(Standards.SpacingSm)
            ) {
                when (category) {
                    "services" -> {
                        // ── Services & Models ──
                        modelConfigurationSection(
                            hardwareTuningEnabled = hardwareTuningEnabled,
                            installedModels = installedModels,
                            onModelSelected = { model -> 
                                configEditorViewModel.selectModel(model)
                                editorModel = model 
                            },
                            onEmbeddingSetup = onEmbeddingSetupClick,
                            onModelEditor = onModelEditor
                        )
                        huggingFaceTokenSection(
                            tokenState = hfTokenState,
                            testResult = hfTestResult,
                            onSaveToken = viewModel::saveHfToken,
                            onClearToken = viewModel::clearHfToken,
                            onTestConnection = viewModel::testHfConnection
                        )
                    }
                    "chat" -> {
                        // ── Responses & Chat Experience ──
                        llmSettingsSection(
                            streamingEnabled = streamingEnabled,
                            chatMemoryEnabled = chatMemoryEnabled,
                            speedModeEnabled = speedModeEnabled,
                            viewModel = viewModel
                        )
                        systemPromptSection(
                            globalSystemPrompt = globalSystemPrompt,
                            globalPrependPrompt = globalPrependPrompt,
                            globalPostpendPrompt = globalPostpendPrompt,
                            viewModel = viewModel
                        )
                        chatSettingsSection(
                            codeHighlightEnabled = codeHighlightEnabled,
                            viewModel = viewModel
                        )
                    }
                    "hardware" -> {
                        // ── Hardware Tuning ──
                        hardwareTuningSection(
                            hardwareTuningEnabled = hardwareTuningEnabled,
                            performanceMode = performanceMode,
                            hardwareProfile = hardwareProfile,
                            viewModel = viewModel
                        )
                    }
                    "intelligence" -> {
                        // ── Intelligence & Tools ──
                        generalSettingsSection(
                            toolCallingEnabled = toolCallingEnabled,
                            toolCallingBypassEnabled = toolCallingBypassEnabled,
                            hasToolCallingModel = hasToolCallingModel,
                            toolCallingDownloadState = toolCallingDownloadState,
                            viewModel = viewModel
                        )
                        aiMemorySection(
                            aiMemoryEnabled = aiMemoryEnabled,
                            onAiMemoryClick = onAiMemoryClick,
                            viewModel = viewModel
                        )
                    }
                    "voice" -> {
                        // ── Voice Settings (TTS & STT) ──
                        ttsSettingsSection(
                            installedTtsModelId = installedTtsModelId,
                            installedTtsModelIds = installedTtsModelIds,
                            ttsDownloadStates = ttsDownloadStates,
                            ttsModelLoaded = ttsModelLoaded,
                            loadTTSOnStart = loadTTSOnStart,
                            ttsSettings = ttsSettings,
                            voices = voices,
                            viewModel = viewModel
                        )
                        sttSettingsSection(
                            hasSttModel = hasSttModel,
                            sttThreads = sttThreads,
                            sttLanguage = sttLanguage,
                            sttDownloadStates = ttsDownloadStates,
                            viewModel = viewModel
                        )
                    }
                    "storage" -> {
                        // ── Storage & Diagnostics ──
                        item {
                            GlassSectionCard(
                                title = "System Storage",
                                icon = TnIcons.Refresh,
                                description = "Securely manage, backup, or reset your local data footprints"
                            ) {
                                DataManagementSection(viewModel = viewModel)
                            }
                        }

                        item {
                            GlassCard(
                                onClick = {
                                    haptics.selection()
                                    onDiagnosticsClick()
                                },
                                modifier = Modifier.fillMaxWidth(),
                                backgroundColor = Glass.Surface,
                                borderColor = Glass.BorderSubtle,
                                cornerRadius = Standards.CardCornerRadius,
                                contentPadding = PaddingValues(Standards.CardPadding)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(Standards.SpacingSm)
                                ) {
                                    Icon(
                                        imageVector = TnIcons.Terminal,
                                        contentDescription = null,
                                        modifier = Modifier.size(Standards.IconLg),
                                        tint = Glass.AccentSecondary
                                    )
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = "System Diagnostics",
                                            style = MaterialTheme.typography.titleSmall,
                                            fontWeight = FontWeight.SemiBold,
                                            color = Glass.TextPrimary
                                        )
                                        Text(
                                            text = "View logs, native audits, and crash reports",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = Glass.TextSecondary
                                        )
                                    }
                                    Icon(
                                        imageVector = TnIcons.ChevronRight,
                                        contentDescription = null,
                                        tint = Glass.TextSecondary
                                    )
                                }
                            }
                        }
                    }
                    "about" -> {
                        // ── About ──
                        aboutSection(
                            appVersion = viewModel.appVersion,
                            onTriggerCredits = { showCredits = true }
                        )
                    }
                    else -> {
                        // ── Main Dashboard list (Cleaned up Material Design 3 style) ──
                        item {
                            SettingsGroup(title = "Models") {
                                SettingsItem(
                                    title = "Services & Models",
                                    description = "Manage installed LLMs and RAG embeddings",
                                    icon = TnIcons.Sparkles,
                                    onClick = {
                                        selectedCategory = "services"
                                    }
                                )
                            }
                        }

                        item {
                            SettingsGroup(title = "General Settings") {
                                SettingsItem(
                                    title = "Responses & Chat",
                                    description = "System prompts, language models, and chat experience",
                                    icon = Icons.AutoMirrored.Filled.Chat,
                                    onClick = {
                                        selectedCategory = "chat"
                                    }
                                )
                                androidx.compose.material3.HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f), thickness = 1.dp)
                                SettingsItem(
                                    title = "Intelligence & Tools",
                                    description = "Tool calling capabilities and AI plugins",
                                    icon = Icons.Default.Psychology,
                                    onClick = {
                                        selectedCategory = "intelligence"
                                    }
                                )
                                androidx.compose.material3.HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f), thickness = 1.dp)
                                SettingsItem(
                                    title = "Voice Processing",
                                    description = "Text-to-Speech (TTS) and Speech-to-Text (STT) models",
                                    icon = Icons.Default.RecordVoiceOver,
                                    onClick = {
                                        selectedCategory = "voice"
                                    }
                                )
                            }
                        }

                        item {
                            SettingsGroup(title = "Device & Storage") {
                                SettingsItem(
                                    title = "Hardware Tuning",
                                    description = "CPU threads, compute cores, and performance modes",
                                    icon = Icons.Default.Memory,
                                    onClick = {
                                        selectedCategory = "hardware"
                                    }
                                )
                                androidx.compose.material3.HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f), thickness = 1.dp)
                                SettingsItem(
                                    title = "Storage & Diagnostics",
                                    description = "Manage memory, diagnostics, and app data",
                                    icon = Icons.Default.Storage,
                                    onClick = {
                                        selectedCategory = "storage"
                                    }
                                )
                            }
                        }

                        item {
                            SettingsGroup(title = "About") {
                                SettingsItem(
                                    title = "About BIT",
                                    description = "App version, developer info, and ratings",
                                    icon = Icons.Default.Info,
                                    onClick = {
                                        selectedCategory = "about"
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (editorModel != null) {
        ModalBottomSheet(
            onDismissRequest = {
                editorModel = null
            },
            sheetState = sheetState,
            containerColor = MaterialTheme.colorScheme.surface,
            modifier = Modifier.fillMaxHeight(0.85f)
        ) {
            ConfigEditorPanel(
                model = editorModel!!,
                viewModel = configEditorViewModel,
                modifier = Modifier.fillMaxSize()
            )
        }
    }

    if (showCredits) {
        EndCreditsOverlay(
            audioResIds = listOf(
                com.bit.R.raw.credits_song_1,
                com.bit.R.raw.credits_song_2,
                com.bit.R.raw.credits_song_3,
                com.bit.R.raw.credits_song_4
            ),
            lines = bitCreditLines,
            onDismiss = { showCredits = false }
        )
    }
}

@Composable
fun SettingsGroup(
    title: String,
    content: @Composable () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 16.dp)
    ) {
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = title.uppercase(),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.2.sp
        )
        // Grouped MD3 card using solid GlassCard
        GlassCard(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(0.dp)
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                content()
            }
        }
    }
}

@Composable
fun SettingsItem(
    title: String,
    description: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit
) {
    val haptics = com.bit.ui.theme.LocalBitHaptics.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable {
                haptics.selection()
                onClick()
            }
            .padding(horizontal = 16.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Tonal Icon Container (rounded circle)
        androidx.compose.material3.Surface(
            shape = androidx.compose.foundation.shape.CircleShape,
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
            modifier = Modifier.size(40.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }
        
        Spacer(modifier = Modifier.width(16.dp))
        
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Medium,
                color = Color.White
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        
        Icon(
            imageVector = TnIcons.ChevronRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
            modifier = Modifier.size(20.dp)
        )
    }
}
