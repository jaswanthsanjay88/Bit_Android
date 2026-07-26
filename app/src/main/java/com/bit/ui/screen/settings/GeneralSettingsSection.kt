package com.bit.ui.screen.settings

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import org.json.JSONObject

import com.bit.global.HardwareProfile
import com.bit.global.PerformanceMode
import com.bit.global.Standards
import com.bit.ui.theme.Glass
import com.bit.ui.components.GlassCard
import com.bit.models.table_schema.Model
import com.bit.service.ModelDownloadService
import com.bit.ui.components.ActionTextButton
import com.bit.ui.components.ActionToggleGroup
import com.bit.ui.components.StandardCard
import com.bit.ui.components.SettingsSwitchRow
import com.bit.ui.components.SettingsClickableRow
import com.bit.ui.components.GlassSectionCard
import com.bit.ui.components.GlassDivider
import com.bit.ui.components.providerIcon
import com.bit.ui.icons.TnIcons
import com.bit.viewmodel.SettingsViewModel

// ── General Settings Section ──

internal fun LazyListScope.generalSettingsSection(
    toolCallingEnabled: Boolean,
    toolCallingBypassEnabled: Boolean,
    hasToolCallingModel: Boolean,
    toolCallingDownloadState: ModelDownloadService.DownloadState?,
    viewModel: SettingsViewModel
) {
    item {
        val canEnableToolCalling = hasToolCallingModel || toolCallingBypassEnabled
        GlassSectionCard(
            title = "AI Plugins & Tools",
            icon = TnIcons.Settings,
            description = "Configure intelligence layers, action tools, and local execution pipelines"
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(Standards.SpacingSm)) {
                SettingsSwitchRow(
                    title = "Tool Calling",
                    description = when {
                        toolCallingBypassEnabled -> "Bypass enabled — tool calling available for all models"
                        hasToolCallingModel -> "Any model with a chat template can use tools"
                        else -> "Install a GGUF model to enable tool calling"
                    },
                    checked = toolCallingEnabled && canEnableToolCalling,
                    onCheckedChange = { viewModel.setToolCallingEnabled(it) },
                    enabled = canEnableToolCalling
                )

                if (!hasToolCallingModel) {
                    GlassDivider()
                    ModelDownloadCard(
                        title = "Recommended Tool Calling Model",
                        description = "Ruvltra Claude Code 0.5B · ~400 MB\nCompact model optimized for tool calling",
                        downloadState = toolCallingDownloadState,
                        onDownload = { viewModel.downloadToolCallingModel() }
                    )
                }

                GlassDivider()
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(Standards.SpacingXs)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Standards.SpacingSm)
                    ) {
                        Icon(
                            TnIcons.AlertTriangle,
                            contentDescription = null,
                            tint = Glass.StatusError,
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = "Bypass Model Check",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = Glass.StatusError
                        )
                    }
                    Text(
                        text = "Force tool calling on models without a chat template. May cause errors or unexpected output.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Glass.TextSecondary
                    )
                    SettingsSwitchRow(
                        title = "Enable Bypass",
                        description = if (toolCallingBypassEnabled) "Tool calling forced for all models" else "Only models with chat templates can use tools",
                        checked = toolCallingBypassEnabled,
                        onCheckedChange = { viewModel.setToolCallingBypassEnabled(it) },
                        titleColor = Glass.StatusError
                    )
                }
            }
        }
    }
}

// ── LLM Settings Section ──

internal fun LazyListScope.llmSettingsSection(
    streamingEnabled: Boolean,
    chatMemoryEnabled: Boolean,
    speedModeEnabled: Boolean,
    viewModel: SettingsViewModel
) {
    item {
        GlassSectionCard(
            title = "LLM Engine",
            icon = TnIcons.Brain,
            description = "Fine-tune streaming behaviors, reload checks, and local context caches"
        ) {
            Column {
                SettingsSwitchRow(
                    title = "Streaming Response",
                    description = "Stream tokens as they generate in real-time",
                    checked = streamingEnabled,
                    onCheckedChange = { viewModel.setStreamingEnabled(it) }
                )
                GlassDivider()
                SettingsSwitchRow(
                    title = "Chat Memory",
                    description = "Remember previous messages in conversation (faster without)",
                    checked = chatMemoryEnabled,
                    onCheckedChange = { viewModel.setChatMemoryEnabled(it) }
                )
                GlassDivider()
                SettingsSwitchRow(
                    title = "Speed Mode (Speculative Decoding)",
                    description = "Accelerates generation speed up to 2x using parallel N-gram sequence prediction.",
                    checked = speedModeEnabled,
                    onCheckedChange = { viewModel.setSpeedModeEnabled(it) }
                )
            }
        }
    }
}

// ── System Prompt Section ──

internal fun LazyListScope.systemPromptSection(
    globalSystemPrompt: String,
    globalPrependPrompt: String,
    globalPostpendPrompt: String,
    viewModel: SettingsViewModel
) {
    item {
        var selectedTabIndex by remember { androidx.compose.runtime.mutableIntStateOf(0) }

        GlassSectionCard(
            title = "Global Prompts",
            icon = TnIcons.DeviceFloppy, // Just a placeholder icon, maybe something better later
            description = "Manage prompts that are applied across all conversations"
        ) {
            Column {
                androidx.compose.material3.TabRow(
                    selectedTabIndex = selectedTabIndex,
                    containerColor = Color.Transparent,
                    contentColor = Color.White,
                    indicator = { tabPositions ->
                        androidx.compose.material3.TabRowDefaults.Indicator(
                            Modifier.tabIndicatorOffset(tabPositions[selectedTabIndex]),
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                ) {
                    androidx.compose.material3.Tab(
                        selected = selectedTabIndex == 0,
                        onClick = { selectedTabIndex = 0 },
                        text = { Text("System") }
                    )
                    androidx.compose.material3.Tab(
                        selected = selectedTabIndex == 1,
                        onClick = { selectedTabIndex = 1 },
                        text = { Text("Prepend") }
                    )
                    androidx.compose.material3.Tab(
                        selected = selectedTabIndex == 2,
                        onClick = { selectedTabIndex = 2 },
                        text = { Text("Postpend") }
                    )
                }

                val currentValue = when (selectedTabIndex) {
                    0 -> globalSystemPrompt
                    1 -> globalPrependPrompt
                    2 -> globalPostpendPrompt
                    else -> ""
                }

                val onValueChange: (String) -> Unit = {
                    when (selectedTabIndex) {
                        0 -> viewModel.setGlobalSystemPrompt(it)
                        1 -> viewModel.setGlobalPrependPrompt(it)
                        2 -> viewModel.setGlobalPostpendPrompt(it)
                    }
                }

                androidx.compose.material3.OutlinedTextField(
                    value = currentValue,
                    onValueChange = onValueChange,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(Standards.SpacingSm),
                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = Color.White),
                    minLines = 3,
                    maxLines = 10,
                    colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent
                    )
                )
            }
        }
    }
}

// ── Chat Settings Section ──

internal fun LazyListScope.chatSettingsSection(
    codeHighlightEnabled: Boolean,
    viewModel: SettingsViewModel
) {
    item {
        GlassSectionCard(
            title = "Chat Experience",
            icon = TnIcons.Adjustments,
            description = "Configure syntax rendering, markdown parsing, and code scrolling presets"
        ) {
            SettingsSwitchRow(
                title = "Code Syntax Highlighting",
                description = "Colorize code blocks based on language (disable for faster scrolling)",
                checked = codeHighlightEnabled,
                onCheckedChange = { viewModel.setCodeHighlightEnabled(it) }
            )
        }
    }
}

// ── Hardware Tuning Section ──

internal fun LazyListScope.hardwareTuningSection(
    hardwareTuningEnabled: Boolean,
    performanceMode: PerformanceMode,
    hardwareProfile: HardwareProfile?,
    viewModel: SettingsViewModel
) {
    item {
        GlassSectionCard(
            title = "Hardware Tuning",
            icon = TnIcons.Cpu,
            description = "Calibrate CPU threads, prime cores, and performance presets to your device"
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(Standards.SpacingSm)) {
                SettingsSwitchRow(
                    title = "Hardware-Based Tuning",
                    description = "Automatically optimize model parameters based on your device's hardware. Disable to set parameters manually.",
                    checked = hardwareTuningEnabled,
                    onCheckedChange = { viewModel.setHardwareTuningEnabled(it) }
                )

                GlassDivider()

                // Performance presets
                Column {
                    Text(
                        text = "Performance Mode",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        color = if (hardwareTuningEnabled) Glass.TextPrimary else Glass.TextSecondary.copy(alpha = 0.6f)
                    )
                    Spacer(Modifier.height(Standards.SpacingXs))
                    ActionToggleGroup(
                        items = PerformanceMode.entries.toList(),
                        selectedItem = performanceMode,
                        onItemSelected = { viewModel.setPerformanceMode(it) },
                        itemLabel = { mode ->
                            when (mode) {
                                PerformanceMode.PERFORMANCE -> "Performance"
                                PerformanceMode.BALANCED -> "Balanced"
                                PerformanceMode.POWER_SAVING -> "Power Saver"
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = hardwareTuningEnabled
                    )
                    Spacer(Modifier.height(Standards.SpacingXs))
                    Text(
                        text = if (!hardwareTuningEnabled) {
                            "Enable hardware tuning to use performance presets"
                        } else when (performanceMode) {
                            PerformanceMode.PERFORMANCE -> "Uses all fast cores. Best speed, higher battery use."
                            PerformanceMode.BALANCED -> "Uses performance cores only. Good speed and battery balance."
                            PerformanceMode.POWER_SAVING -> "Minimal threads and memory. Best battery life."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = Glass.TextSecondary
                    )
                }

                // Topology Info Card
                hardwareProfile?.let { profile ->
                    GlassDivider()
                    val topo = profile.cpuTopology
                    val coreInfo = if (topo.scanSucceeded) {
                        buildString {
                            if (topo.primeCoreCount > 0) append("${topo.primeCoreCount}P")
                            if (topo.performanceCoreCount > 0) {
                                if (isNotEmpty()) append("+")
                                append("${topo.performanceCoreCount}P")
                            }
                            if (topo.efficiencyCoreCount > 0) {
                                if (isNotEmpty()) append("+")
                                append("${topo.efficiencyCoreCount}E")
                            }
                            append(" cores")
                        }
                    } else {
                        "${profile.cpuCores} cores"
                    }

                    StandardCard(
                        title = "${profile.totalRamMB} MB RAM · $coreInfo · ${profile.cpuArch}",
                        description = profile.deviceModel
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End
                        ) {
                            ActionTextButton(
                                onClickListener = { viewModel.rescanHardware() },
                                icon = TnIcons.Refresh,
                                text = "Rescan",
                                shape = RoundedCornerShape(Standards.CardSmallCornerRadius)
                            )
                        }
                    }
                }
            }
        }
    }
}

// ── Model Configuration Section ──

internal fun LazyListScope.modelConfigurationSection(
    hardwareTuningEnabled: Boolean,
    installedModels: List<Model>,
    onModelSelected: (Model) -> Unit,
    onEmbeddingSetup: () -> Unit,
    onModelEditor: () -> Unit
) {
    item {
        GlassSectionCard(
            title = "Model Configurations",
            icon = TnIcons.Sparkles,
            description = "View active LLM formats, install new modules, and setup RAG embedding vectors",
            trailing = {
                // If there's an active model, selecting it from the gear could be a nice touch, but list is fine.
            }
        ) {
            Column {
                if (hardwareTuningEnabled) {
                    Text(
                        text = "Model parameters are managed by the performance engine. Disable hardware tuning to edit manually.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Glass.TextSecondary,
                        modifier = Modifier.padding(bottom = Standards.SpacingSm)
                    )
                    GlassDivider()
                }

                if (installedModels.isEmpty()) {
                    SettingsClickableRow(
                        title = "No models installed",
                        description = "Download models from the store",
                        onClick = {}
                    )
                } else {
                    installedModels.forEachIndexed { idx, model ->
                        val iconResId = providerIcon(model.providerType.name)
                        SettingsClickableRow(
                            title = model.modelName,
                            description = model.providerType.name,
                            iconRes = if (iconResId != 0) iconResId else null,
                            onClick = {
                                onModelSelected(model)
                            }
                        )
                        GlassDivider()
                    }
                    SettingsClickableRow(
                        title = "Advanced Model Config Editor",
                        description = "View raw model configs and edit parameters",
                        icon = TnIcons.Settings,
                        onClick = onModelEditor
                    )
                }

                GlassDivider()

                SettingsClickableRow(
                    title = "Embedding Model Setup",
                    description = "Select or download the embedding model used for RAG",
                    onClick = onEmbeddingSetup
                )
            }
        }
    }
}

// ── AI Memory Section ──

internal fun LazyListScope.aiMemorySection(
    aiMemoryEnabled: Boolean,
    onAiMemoryClick: () -> Unit,
    viewModel: SettingsViewModel
) {
    item {
        GlassSectionCard(
            title = "AI Memory",
            icon = TnIcons.User,
            description = "Manage facts, contexts, and personal notes retained across sessions"
        ) {
            Column {
                SettingsSwitchRow(
                    title = "Enable AI Memory",
                    description = "Remember facts about you across conversations",
                    checked = aiMemoryEnabled,
                    onCheckedChange = { viewModel.setAiMemoryEnabled(it) }
                )
                GlassDivider()
                SettingsClickableRow(
                    title = "View Memories",
                    description = "See, search, and manage what the AI remembers about you",
                    onClick = onAiMemoryClick
                )
            }
        }
    }
}

// ── Image Generation Section ──

internal fun LazyListScope.imageGenerationSection(
    imageBlurEnabled: Boolean,
    viewModel: SettingsViewModel
) {
    item {
        GlassSectionCard(
            title = "Image Generation",
            icon = TnIcons.Photo,
            description = "Configure privacy and render presets for generated visuals"
        ) {
            SettingsSwitchRow(
                title = "Blur Generated Images",
                description = "Blur images by default, tap to reveal",
                checked = imageBlurEnabled,
                onCheckedChange = { viewModel.setImageBlurEnabled(it) }
            )
        }
    }
}

// ── About Section ──

internal fun LazyListScope.aboutSection(appVersion: String, onTriggerCredits: () -> Unit) {
    // ── Hero Block Card ──
    item {
        val haptics = com.bit.ui.theme.LocalBitHaptics.current
        
        GlassCard(
            onClick = {},
            modifier = Modifier.fillMaxWidth(),
            backgroundColor = Glass.Surface,
            borderColor = Glass.BorderSubtle,
            cornerRadius = Standards.CardCornerRadius,
            contentPadding = PaddingValues(Standards.CardPadding)
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(Standards.SpacingMd)
            ) {
                // App Logo Container
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    modifier = Modifier.size(56.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            painter = androidx.compose.ui.res.painterResource(id = com.bit.R.drawable.ic_logo),
                            contentDescription = "App logo",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(32.dp)
                        )
                    }
                }
                
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "BIT AI",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = Glass.TextPrimary
                    )
                    Text(
                        text = "Offline On-Device AI Assistant",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Glass.TextSecondary
                    )
                }

                // Divider
                androidx.compose.material3.HorizontalDivider(
                    color = MaterialTheme.colorScheme.outlineVariant,
                    thickness = 1.dp
                )

                // Version and Developer rows using M3 ListItem wrapped in secret easter egg hold trigger
                HoldToRevealTrigger(
                    onRevealed = { onTriggerCredits() },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    androidx.compose.material3.ListItem(
                        headlineContent = { Text("Application Version") },
                        trailingContent = { Text("Version $appVersion", fontWeight = FontWeight.Bold) },
                        colors = androidx.compose.material3.ListItemDefaults.colors(
                            containerColor = androidx.compose.ui.graphics.Color.Transparent,
                            headlineColor = Glass.TextPrimary,
                            supportingColor = Glass.TextSecondary,
                            trailingIconColor = Glass.TextSecondary
                        )
                    )
                }
                
                androidx.compose.material3.ListItem(
                    headlineContent = { Text("Developer") },
                    trailingContent = { Text("Jaswanth Sanjay Nekkanti") },
                    colors = androidx.compose.material3.ListItemDefaults.colors(
                        containerColor = androidx.compose.ui.graphics.Color.Transparent,
                        headlineColor = Glass.TextPrimary,
                        supportingColor = Glass.TextSecondary,
                        trailingIconColor = Glass.TextSecondary
                    )
                )
            }
        }
    }

    // ── Bio Paragraph (Plain Surface Text) ──
    item {
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "I am Nekkanti Jaswanth Sanjay, an AI Engineer, full-stack developer, UI/UX designer, Machine Learning engineer and technology builder focused on creating intelligent digital products. I independently design, develop, and deploy complete software solutions, combining artificial intelligence, mobile development, backend engineering, and user experience design.\n\nAs a solo developer, I take ownership of the entire product lifecycle—from ideation and system architecture to interface design, implementation, testing, and deployment. My work emphasizes innovation, automation, and solving real-world problems through scalable technology.",
            style = MaterialTheme.typography.bodyMedium,
            color = Glass.TextSecondary,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Standards.SpacingSm)
        )
    }

    // ── Links Section Header ──
    item {
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "Links",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = Standards.SpacingSm, vertical = 4.dp)
        )
    }

    // ── Link Cards ──
    item {
        val context = androidx.compose.ui.platform.LocalContext.current
        val haptics = com.bit.ui.theme.LocalBitHaptics.current
        
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            AboutLinkRow(
                title = "Official Website",
                description = "bit.jaswanthsanjay.me",
                icon = TnIcons.Sparkles,
                onClick = {
                    haptics.selection()
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://bit.jaswanthsanjay.me")))
                }
            )

            AboutLinkRow(
                title = "Developer Website",
                description = "jaswanthsanjay.me",
                icon = TnIcons.User,
                onClick = {
                    haptics.selection()
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://jaswanthsanjay.me")))
                }
            )

            AboutLinkRow(
                title = "LinkedIn Profile",
                description = "linkedin.com/in/jaswanthsanjay",
                icon = androidx.compose.ui.graphics.vector.ImageVector.vectorResource(id = com.bit.R.drawable.ic_linkedin),
                onClick = {
                    haptics.selection()
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://linkedin.com/in/jaswanthsanjay")))
                }
            )

            AboutLinkRow(
                title = "GitHub Repository",
                description = "Browse source code and contribute",
                icon = androidx.compose.ui.graphics.vector.ImageVector.vectorResource(id = com.bit.R.drawable.ic_github),
                onClick = {
                    haptics.selection()
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/jaswanthsanjay88/BIT_Android")))
                }
            )

            AboutLinkRow(
                title = "Report Issues",
                description = "Submit bugs or feature requests",
                icon = TnIcons.AlertCircle,
                onClick = {
                    haptics.selection()
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/jaswanthsanjay88/BIT_Android/issues")))
                }
            )

            AboutLinkRow(
                title = "Buy Me a Coffee",
                description = "Support independent open-source AI development",
                icon = TnIcons.Coffee,
                onClick = {
                    haptics.selection()
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://buymeacoffee.com/jaswanthsanjay")))
                }
            )
        }
    }

    // ── Rate BIT & Submit Review Section Header ──
    item {
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "Rate BIT & Submit Review",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = Standards.SpacingSm, vertical = 4.dp)
        )
    }

    // ── Rating & Review Form Card ──
    item {
        val context = androidx.compose.ui.platform.LocalContext.current
        val haptics = com.bit.ui.theme.LocalBitHaptics.current
        val scope = androidx.compose.runtime.rememberCoroutineScope()

        var userName by remember { mutableStateOf("") }
        var userRole by remember { mutableStateOf("") }
        var rating by remember { mutableStateOf(5) }
        var reviewComment by remember { mutableStateOf("") }

        var isSubmitting by remember { mutableStateOf(false) }
        var isSuccess by remember { mutableStateOf(false) }
        var errorMessage by remember { mutableStateOf<String?>(null) }

        GlassCard(
            onClick = {},
            modifier = Modifier.fillMaxWidth(),
            backgroundColor = Glass.Surface,
            borderColor = Glass.BorderSubtle,
            cornerRadius = Standards.CardCornerRadius,
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 20.dp)
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(Standards.SpacingMd)
            ) {
                if (isSuccess) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(Standards.SpacingMd)
                    ) {
                        Icon(
                            imageVector = TnIcons.Heart,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(48.dp)
                        )
                        Text(
                            text = "Thank you for your feedback!",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = Glass.TextPrimary,
                            textAlign = TextAlign.Center
                        )
                        Text(
                            text = "Your review has been uploaded to bit.jaswanthsanjay.me and will be featured live on our website!",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Glass.TextSecondary,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(Standards.SpacingXs))
                        ActionTextButton(
                            onClickListener = {
                                haptics.selection()
                                isSuccess = false
                                reviewComment = ""
                            },
                            text = "Submit Another Review",
                            shape = RoundedCornerShape(Standards.CardSmallCornerRadius)
                        )
                    }
                } else {
                    // Header & Intro
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = "Share Your Review on the Web",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = Glass.TextPrimary
                        )
                        Text(
                            text = "Your review & avatar will be published live to bit.jaswanthsanjay.me",
                            style = MaterialTheme.typography.bodySmall,
                            color = Glass.TextSecondary
                        )
                    }

                    // Avatar Preview & Name / Role inputs
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Standards.SpacingSm)
                    ) {
                        val avatarUrl = "https://api.dicebear.com/10.x/notionists/svg?seed=" +
                            URLEncoder.encode(userName.ifBlank { "BitUser" }, "UTF-8")

                        // Notionists Avatar Badge
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.primaryContainer,
                            modifier = Modifier.size(52.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                AsyncImage(
                                    model = avatarUrl,
                                    contentDescription = "Notionist Avatar Preview",
                                    modifier = Modifier.size(44.dp)
                                )
                            }
                        }

                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            androidx.compose.material3.OutlinedTextField(
                                value = userName,
                                onValueChange = { userName = it },
                                label = { Text("Your Name") },
                                placeholder = { Text("e.g. Alex R.") },
                                singleLine = true,
                                textStyle = MaterialTheme.typography.bodyMedium.copy(color = Color.White),
                                modifier = Modifier.fillMaxWidth(),
                                colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                                    unfocusedBorderColor = Glass.BorderSubtle,
                                    focusedContainerColor = Color.Transparent,
                                    unfocusedContainerColor = Color.Transparent
                                )
                            )
                            androidx.compose.material3.OutlinedTextField(
                                value = userRole,
                                onValueChange = { userRole = it },
                                label = { Text("Occupation / Role (Optional)") },
                                placeholder = { Text("e.g. Entrepreneur, Designer") },
                                singleLine = true,
                                textStyle = MaterialTheme.typography.bodyMedium.copy(color = Color.White),
                                modifier = Modifier.fillMaxWidth(),
                                colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                                    unfocusedBorderColor = Glass.BorderSubtle,
                                    focusedContainerColor = Color.Transparent,
                                    unfocusedContainerColor = Color.Transparent
                                )
                            )
                        }
                    }

                    // 5-Star Rating Selector
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = when (rating) {
                                5 -> "★★★★★ Outstanding!"
                                4 -> "★★★★☆ Great App"
                                3 -> "★★★☆☆ Good"
                                2 -> "★★☆☆☆ Okay"
                                else -> "★☆☆☆☆ Needs Improvement"
                            },
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            for (i in 1..5) {
                                IconButton(
                                    onClick = {
                                        haptics.selection()
                                        rating = i
                                    },
                                    modifier = Modifier.size(44.dp)
                                ) {
                                    Icon(
                                        imageVector = TnIcons.Star,
                                        contentDescription = "Star $i",
                                        tint = if (i <= rating) MaterialTheme.colorScheme.primary else Glass.TextSecondary.copy(alpha = 0.3f),
                                        modifier = Modifier.size(32.dp)
                                    )
                                }
                            }
                        }
                    }

                    // Review Text Field
                    androidx.compose.material3.OutlinedTextField(
                        value = reviewComment,
                        onValueChange = { reviewComment = it },
                        label = { Text("Write your review") },
                        placeholder = { Text("What do you think of BIT AI?") },
                        minLines = 3,
                        maxLines = 5,
                        textStyle = MaterialTheme.typography.bodyMedium.copy(color = Color.White),
                        modifier = Modifier.fillMaxWidth(),
                        colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = MaterialTheme.colorScheme.primary,
                            unfocusedBorderColor = Glass.BorderSubtle,
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent
                        )
                    )

                    if (errorMessage != null) {
                        Text(
                            text = errorMessage!!,
                            style = MaterialTheme.typography.bodySmall,
                            color = Glass.StatusError
                        )
                    }

                    // Submit Button
                    ActionTextButton(
                        onClickListener = {
                            if (userName.isBlank()) {
                                errorMessage = "Please enter your name"
                                return@ActionTextButton
                            }
                            if (reviewComment.isBlank()) {
                                errorMessage = "Please write a review comment"
                                return@ActionTextButton
                            }
                            errorMessage = null
                            haptics.selection()
                            isSubmitting = true

                            scope.launch(Dispatchers.IO) {
                                try {
                                    val url = URL("https://api.jaswanthsanjay.me/api/rating")
                                    val conn = url.openConnection() as HttpURLConnection
                                    conn.requestMethod = "POST"
                                    conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                                    conn.doOutput = true
                                    conn.connectTimeout = 10000
                                    conn.readTimeout = 10000

                                    val seed = URLEncoder.encode(userName.trim(), "UTF-8")
                                    val avatarUrl = "https://api.dicebear.com/10.x/notionists/svg?seed=$seed"

                                    val jsonPayload = JSONObject().apply {
                                        put("name", userName.trim())
                                        put("role", userRole.ifBlank { "User" }.trim())
                                        put("rating", rating)
                                        put("comment", reviewComment.trim())
                                        put("review", reviewComment.trim())
                                        put("feedback", reviewComment.trim())
                                        put("avatar", avatarUrl)
                                        put("appVersion", appVersion)
                                    }

                                    conn.outputStream.use { os ->
                                        os.write(jsonPayload.toString().toByteArray(Charsets.UTF_8))
                                    }

                                    val responseCode = conn.responseCode
                                    if (responseCode in 200..299) {
                                        withContext(Dispatchers.Main) {
                                            isSubmitting = false
                                            isSuccess = true
                                        }
                                    } else {
                                        withContext(Dispatchers.Main) {
                                            isSubmitting = false
                                            errorMessage = "Server error ($responseCode). Please try again."
                                        }
                                    }
                                } catch (e: Exception) {
                                    withContext(Dispatchers.Main) {
                                        isSubmitting = false
                                        errorMessage = "Failed to upload: ${e.localizedMessage ?: "Network error"}"
                                    }
                                }
                            }
                        },
                        text = if (isSubmitting) "Publishing to Website..." else "Submit Review to Website",
                        icon = if (!isSubmitting) TnIcons.Sparkles else null,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(Standards.CardSmallCornerRadius)
                    )
                }
            }
        }
    }
}

@Composable
private fun AboutLinkRow(
    title: String,
    description: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit
) {
    GlassCard(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        backgroundColor = Glass.Surface,
        borderColor = Glass.BorderSubtle,
        cornerRadius = Standards.CardCornerRadius,
        contentPadding = PaddingValues(Standards.SpacingMd)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Standards.SpacingSm)
        ) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.secondaryContainer,
                modifier = Modifier.size(40.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                }
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = Glass.TextPrimary
                )
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = Glass.TextSecondary
                )
            }
            Icon(
                imageVector = TnIcons.ChevronRight,
                contentDescription = null,
                tint = Glass.TextSecondary,
                modifier = Modifier.size(Standards.IconSm)
            )
        }
    }
}

