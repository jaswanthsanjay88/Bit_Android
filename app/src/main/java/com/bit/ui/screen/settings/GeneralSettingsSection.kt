package com.bit.ui.screen.settings

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.bit.global.HardwareProfile
import com.bit.global.PerformanceMode
import com.bit.global.Standards
import com.bit.ui.theme.Glass
import com.bit.ui.components.GlassCard
import com.bit.models.table_schema.Model
import com.bit.service.ModelDownloadService
import com.bit.ui.components.ActionTextButton
import com.bit.ui.components.ActionToggleGroup
import com.bit.ui.components.BodyLabel
import com.bit.ui.components.StandardCard
import com.bit.ui.components.SettingsSwitchRow
import com.bit.ui.components.SettingsClickableRow
import com.bit.ui.components.GlassSectionCard
import com.bit.ui.components.GlassDivider
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
    item { Spacer(Modifier.height(Standards.SpacingSm)) }
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
    item { Spacer(Modifier.height(Standards.SpacingSm)) }
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

// ── Chat Settings Section ──

internal fun LazyListScope.chatSettingsSection(
    codeHighlightEnabled: Boolean,
    viewModel: SettingsViewModel
) {
    item { Spacer(Modifier.height(Standards.SpacingSm)) }
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
    item { Spacer(Modifier.height(Standards.SpacingSm)) }
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
    onModelEditor: () -> Unit,
    onEmbeddingSetup: () -> Unit
) {
    item { Spacer(Modifier.height(Standards.SpacingSm)) }
    item {
        GlassSectionCard(
            title = "Model Configurations",
            icon = TnIcons.Sparkles,
            description = "View active LLM formats, install new modules, and setup RAG embedding vectors",
            trailing = {
                ActionTextButton(
                    onClickListener = onModelEditor,
                    icon = TnIcons.Sparkles,
                    text = "Configure",
                    shape = RoundedCornerShape(Standards.CardSmallCornerRadius),
                    enabled = !hardwareTuningEnabled
                )
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
                        SettingsClickableRow(
                            title = model.modelName,
                            description = model.providerType.name,
                            onClick = if (!hardwareTuningEnabled) onModelEditor else ({})
                        )
                        if (idx < installedModels.size - 1) {
                            GlassDivider()
                        }
                    }
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
    item { Spacer(Modifier.height(Standards.SpacingSm)) }
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
    item { Spacer(Modifier.height(Standards.SpacingSm)) }
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

internal fun LazyListScope.aboutSection(appVersion: String) {
    item { Spacer(Modifier.height(Standards.SpacingSm)) }
    item {
        GlassSectionCard(
            title = "About",
            icon = TnIcons.InfoCircle,
            description = "BIT · On-device AI — LLM, Image Generation, TTS"
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = Standards.SpacingXs),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "Application Version",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = Glass.TextPrimary
                )
                Text(
                    text = "Version $appVersion",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    color = Glass.TextSecondary
                )
            }
        }
    }
}
