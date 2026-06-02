package com.bit.ui.screen.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.bit.global.Standards
import com.bit.plugins.PluginManager
import com.bit.service.ModelDownloadService
import com.bit.ui.components.ActionButton
import com.bit.ui.components.GlassCard
import com.bit.ui.components.GlassDivider
import com.bit.ui.components.GlassSectionCard
import com.bit.ui.components.SettingsClickableRow
import com.bit.ui.icons.TnIcons
import com.bit.ui.theme.Glass
import com.bit.viewmodel.SettingsViewModel
import com.bit.stt.SherpaSTTEngine

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun SettingsScreen(
    onNavigateBack: () -> Unit,
    onModelEditor: () -> Unit = {},
    onAiMemoryClick: () -> Unit = {},
    onEmbeddingSetupClick: () -> Unit = {},
    onDiagnosticsClick: () -> Unit = {},
    viewModel: SettingsViewModel = viewModel()
) {
    // Intercept back actions robustly to prevent app exit
    BackHandler {
        onNavigateBack()
    }

    val context = androidx.compose.ui.platform.LocalContext.current
    val hasSttModel = remember(context) { SherpaSTTEngine.hasModelFiles(context) }

    // Dynamic Profile Info
    val profileName by viewModel.profileName.collectAsStateWithLifecycle()
    val profileEmail by viewModel.profileEmail.collectAsStateWithLifecycle()
    val profilePhone by viewModel.profilePhone.collectAsStateWithLifecycle()

    // Dialog Edit States
    var showEditNameDialog by remember { mutableStateOf(false) }
    var showEditEmailDialog by remember { mutableStateOf(false) }
    var showEditPhoneDialog by remember { mutableStateOf(false) }
    var editNameInput by remember { mutableStateOf("") }
    var editEmailInput by remember { mutableStateOf("") }
    var editPhoneInput by remember { mutableStateOf("") }

    LaunchedEffect(showEditNameDialog) {
        if (showEditNameDialog) editNameInput = profileName
    }
    LaunchedEffect(showEditEmailDialog) {
        if (showEditEmailDialog) editEmailInput = profileEmail
    }
    LaunchedEffect(showEditPhoneDialog) {
        if (showEditPhoneDialog) editPhoneInput = profilePhone
    }

    // App settings
    val streamingEnabled by viewModel.streamingEnabled.collectAsStateWithLifecycle()
    val chatMemoryEnabled by viewModel.chatMemoryEnabled.collectAsStateWithLifecycle()
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
        topBar = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Standards.SpacingMd, vertical = Standards.SpacingSm)
                    .statusBarsPadding(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                ActionButton(
                    onClickListener = onNavigateBack,
                    icon = TnIcons.ArrowLeft,
                    contentDescription = "Back",
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = Glass.Surface,
                        contentColor = Glass.TextPrimary
                    )
                )
            }
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(horizontal = Standards.SpacingMd),
            verticalArrangement = Arrangement.spacedBy(Standards.SpacingSm)
        ) {
            // ── Dynamic Profile Section (ChatGPT Style) ──
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = Standards.SpacingMd),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box(
                        modifier = Modifier.size(92.dp),
                        contentAlignment = Alignment.BottomEnd
                    ) {
                        // Initials Avatar with custom blue/silver gradient
                        val initials = profileName.ifEmpty { "User" }.split(" ")
                            .mapNotNull { it.firstOrNull()?.toString() }
                            .joinToString("")
                            .take(2)
                            .uppercase()

                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(
                                    brush = Brush.radialGradient(
                                        colors = listOf(
                                            Glass.AccentPrimary,
                                            Glass.AccentSecondary
                                         )
                                    ),
                                    shape = CircleShape
                                )
                                .clickable { showEditNameDialog = true },
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = initials,
                                style = MaterialTheme.typography.headlineMedium,
                                fontWeight = FontWeight.Bold,
                                color = Glass.TextOnAccent
                            )
                        }

                        // Edit Badge
                        Box(
                            modifier = Modifier
                                .size(28.dp)
                                .background(Glass.SurfaceElevated, CircleShape)
                                .border(1.dp, Glass.Border, CircleShape)
                                .clickable { showEditNameDialog = true },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = TnIcons.Edit,
                                contentDescription = "Edit Profile",
                                modifier = Modifier.size(13.dp),
                                tint = Glass.TextPrimary
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(Standards.SpacingSm))

                    // Dynamic User Name
                    Text(
                        text = profileName.ifEmpty { "User" },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = if (profileName.isEmpty()) Glass.TextMuted else Glass.TextPrimary,
                        modifier = Modifier.clickable { showEditNameDialog = true }
                    )
                }
            }

            // ── Dynamic Account Section (ChatGPT Style) ──
            item {
                Text(
                    text = "Account",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = Glass.TextSecondary,
                    modifier = Modifier.padding(start = Standards.SpacingXs, bottom = Standards.SpacingXxs)
                )
                GlassCard(
                    modifier = Modifier.fillMaxWidth(),
                    backgroundColor = Glass.Surface,
                    borderColor = Glass.BorderSubtle,
                    cornerRadius = Standards.CardCornerRadius,
                    contentPadding = PaddingValues(horizontal = Standards.CardPadding, vertical = Standards.SpacingXs)
                ) {
                    Column {
                        SettingsClickableRow(
                            title = "Email",
                            description = profileEmail.ifEmpty { "Not set" },
                            icon = TnIcons.User,
                            onClick = { showEditEmailDialog = true }
                        )
                        GlassDivider()
                        SettingsClickableRow(
                            title = "Phone number",
                            description = profilePhone.ifEmpty { "Not set" },
                            icon = TnIcons.Settings,
                            onClick = { showEditPhoneDialog = true }
                        )
                    }
                }
            }

            // ── General AI Plugins ──
            generalSettingsSection(
                toolCallingEnabled = toolCallingEnabled,
                toolCallingBypassEnabled = toolCallingBypassEnabled,
                hasToolCallingModel = hasToolCallingModel,
                toolCallingDownloadState = toolCallingDownloadState,
                viewModel = viewModel
            )

            // ── LLM Engine Settings ──
            llmSettingsSection(
                streamingEnabled = streamingEnabled,
                chatMemoryEnabled = chatMemoryEnabled,
                speedModeEnabled = speedModeEnabled,
                viewModel = viewModel
            )

            // ── Chat Experience ──
            chatSettingsSection(
                codeHighlightEnabled = codeHighlightEnabled,
                viewModel = viewModel
            )

            // ── Hardware Tuning ──
            hardwareTuningSection(
                hardwareTuningEnabled = hardwareTuningEnabled,
                performanceMode = performanceMode,
                hardwareProfile = hardwareProfile,
                viewModel = viewModel
            )

            // ── Model Configurations ──
            modelConfigurationSection(
                hardwareTuningEnabled = hardwareTuningEnabled,
                installedModels = installedModels,
                onModelEditor = onModelEditor,
                onEmbeddingSetup = onEmbeddingSetupClick
            )

            // ── AI Memory ──
            aiMemorySection(
                aiMemoryEnabled = aiMemoryEnabled,
                onAiMemoryClick = onAiMemoryClick,
                viewModel = viewModel
            )

            // ── TTS Text-to-Speech ──
            ttsSettingsSection(
                installedTtsModelId = installedTtsModelId,
                ttsDownloadStates = ttsDownloadStates,
                ttsModelLoaded = ttsModelLoaded,
                loadTTSOnStart = loadTTSOnStart,
                ttsSettings = ttsSettings,
                voices = voices,
                viewModel = viewModel
            )

            // ── STT Speech-to-Text ──
            sttSettingsSection(
                hasSttModel = hasSttModel,
                sttThreads = sttThreads,
                sttLanguage = sttLanguage,
                viewModel = viewModel
            )

            // ── Image Generation ──
            imageGenerationSection(
                imageBlurEnabled = imageBlurEnabled,
                viewModel = viewModel
            )

            // ── HuggingFace Access Token ──
            huggingFaceTokenSection(
                tokenState = hfTokenState,
                testResult = hfTestResult,
                onSaveToken = viewModel::saveHfToken,
                onClearToken = viewModel::clearHfToken,
                onTestConnection = viewModel::testHfConnection
            )

            // ── Data Management Card ──
            item {
                Spacer(Modifier.height(Standards.SpacingSm))
                Text(
                    text = "Data Management",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = Glass.TextSecondary,
                    modifier = Modifier.padding(start = Standards.SpacingXs, bottom = Standards.SpacingXxs)
                )
                GlassSectionCard(
                    title = "System Storage",
                    icon = TnIcons.Refresh,
                    description = "Securely manage, backup, or reset your local data footprints"
                ) {
                    DataManagementSection(viewModel = viewModel)
                }
            }

            // ── Diagnostics ──
            item {
                Spacer(Modifier.height(Standards.SpacingSm))
                Text(
                    text = "System Diagnostics",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = Glass.TextSecondary,
                    modifier = Modifier.padding(start = Standards.SpacingXs, bottom = Standards.SpacingXxs)
                )
                GlassCard(
                    onClick = onDiagnosticsClick,
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

            // ── About ──
            aboutSection(appVersion = viewModel.appVersion)

            item { Spacer(Modifier.height(Standards.SpacingXl)) }
        }
    }

    // ── Dialogs to Edit Profile dynamically ──
    if (showEditNameDialog) {
        ProfileEditDialog(
            title = "Edit Full Name",
            label = "Full Name",
            initialValue = editNameInput,
            onDismiss = { showEditNameDialog = false },
            onSave = { viewModel.updateProfileName(it) }
        )
    }

    if (showEditEmailDialog) {
        ProfileEditDialog(
            title = "Edit Email Address",
            label = "Email Address",
            initialValue = editEmailInput,
            onDismiss = { showEditEmailDialog = false },
            onSave = { viewModel.updateProfileEmail(it) }
        )
    }

    if (showEditPhoneDialog) {
        ProfileEditDialog(
            title = "Edit Phone Number",
            label = "Phone Number",
            initialValue = editPhoneInput,
            onDismiss = { showEditPhoneDialog = false },
            onSave = { viewModel.updateProfilePhone(it) }
        )
    }
}

@Composable
private fun ProfileEditDialog(
    title: String,
    label: String,
    initialValue: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit
) {
    var textInput by remember { mutableStateOf(initialValue) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, fontWeight = FontWeight.SemiBold) },
        text = {
            OutlinedTextField(
                value = textInput,
                onValueChange = { textInput = it },
                label = { Text(label) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = Glass.TextPrimary,
                    unfocusedTextColor = Glass.TextPrimary,
                    focusedBorderColor = Glass.BorderActive,
                    unfocusedBorderColor = Glass.Border
                )
            )
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (textInput.isNotBlank()) {
                        onSave(textInput.trim())
                    }
                    onDismiss()
                }
            ) {
                Text("Save", color = Glass.AccentPrimary)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = Glass.TextSecondary)
            }
        },
        containerColor = Glass.SurfaceElevated,
        textContentColor = Glass.TextPrimary,
        titleContentColor = Glass.TextPrimary,
        shape = RoundedCornerShape(Standards.RadiusLg)
    )
}
