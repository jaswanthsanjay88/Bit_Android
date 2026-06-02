package com.bit.ui.screen.home

import android.content.Intent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bit.activity.RagActivity
import com.bit.global.Standards
import com.bit.models.ModelType
import com.bit.models.table_schema.Model
import com.bit.ui.components.ActionButton
import com.bit.ui.components.ActionProgressButton
import com.bit.ui.components.ActionToggleButton
import com.bit.ui.components.GlassChip
import com.bit.ui.components.GlassDivider
import com.bit.ui.components.MemoryOverlayBottomSheet
import com.bit.ui.components.ModeToggleSwitch
import com.bit.ui.components.ModelListItem
import com.bit.ui.components.ModelList
import com.bit.ui.components.PluginOverlayBottomSheet
import com.bit.ui.icons.TnIcons
import com.bit.ui.theme.Glass
import com.bit.ui.theme.Motion
import com.bit.viewmodel.ChatViewModel
import com.bit.viewmodel.ChatUiState
import com.bit.viewmodel.ChatConfigState
import com.bit.viewmodel.LLMModelViewModel
import com.bit.viewmodel.MemoryViewModel
import com.bit.viewmodel.PluginViewModel
import com.bit.viewmodel.RagViewModel
import io.github.fletchmckee.liquid.LiquidState
import io.github.fletchmckee.liquid.liquid
import kotlinx.coroutines.launch

// ── BottomBar ───────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun BottomBar(
    chatViewModel: ChatViewModel = hiltViewModel(),
    llmModelViewModel: LLMModelViewModel = hiltViewModel(),
    ragViewModel: RagViewModel = hiltViewModel(),
    pluginViewModel: PluginViewModel = hiltViewModel(),
    memoryViewModel: MemoryViewModel = hiltViewModel(),
    toolCallingEnabled: Boolean = true,
    onModelSelectedNavigate: (Model) -> Unit = {},
    liquidState: LiquidState? = null
) {
    val context = LocalContext.current
    var value by remember { mutableStateOf("") }
    val installedModels by llmModelViewModel.installedModels.collectAsStateWithLifecycle(emptyList())
    val currentModelID by llmModelViewModel.currentModelID.collectAsStateWithLifecycle()
    val chatState by chatViewModel.chatUiState.collectAsStateWithLifecycle()
    val config by chatViewModel.chatConfigState.collectAsStateWithLifecycle()
    val promptEditState by chatViewModel.promptEditState.collectAsStateWithLifecycle()
    val isTextModelLoaded by chatViewModel.isTextModelLoaded.collectAsStateWithLifecycle()
    val isImageModelLoaded by chatViewModel.isImageModelLoaded.collectAsStateWithLifecycle()
    val huggingFaceToken by chatViewModel.huggingFaceToken.collectAsStateWithLifecycle()

    // RAG State
    val loadedRags by ragViewModel.loadedRags.collectAsStateWithLifecycle()
    val isRagEnabledForChat by ragViewModel.isRagEnabledForChat.collectAsStateWithLifecycle()
    val lastRagResults by ragViewModel.lastRagResults.collectAsStateWithLifecycle()

    // Plugin State
    val showPluginOverlay by pluginViewModel.showPluginOverlay.collectAsStateWithLifecycle()
    val registeredPlugins by pluginViewModel.registeredPlugins.collectAsStateWithLifecycle()
    val enabledPluginNames by pluginViewModel.enabledPluginNames.collectAsStateWithLifecycle()
    val expandedPluginIds by pluginViewModel.expandedPluginIds.collectAsStateWithLifecycle()
    val multiTurnEnabled by pluginViewModel.multiTurnEnabled.collectAsStateWithLifecycle()
    val toolCallingConfig by pluginViewModel.toolCallingConfig.collectAsStateWithLifecycle()
    val isToolCallingModelLoaded by pluginViewModel.isToolCallingModelLoaded.collectAsStateWithLifecycle()

    // Memory State
    val showMemoryOverlay by memoryViewModel.showMemoryOverlay.collectAsStateWithLifecycle()
    val isMemoryEnabled by memoryViewModel.isMemoryEnabled.collectAsStateWithLifecycle()
    val memoryResults by memoryViewModel.memoryResults.collectAsStateWithLifecycle()
    val vaultStats by memoryViewModel.vaultStats.collectAsStateWithLifecycle()
    val memoryEntryCount by memoryViewModel.memoryEntryCount.collectAsStateWithLifecycle()

    // Web Search & non-WebSearch plugins
    val isWebSearchEnabled by pluginViewModel.isWebSearchEnabled.collectAsStateWithLifecycle()
    val nonWebSearchPlugins by pluginViewModel.nonWebSearchPlugins.collectAsStateWithLifecycle()

    // Coroutine scope for RAG queries
    val scope = rememberCoroutineScope()



    LaunchedEffect(promptEditState?.messageId) {
        promptEditState?.let { state ->
            value = state.initialText
        }
    }

    // Plugin Overlay (excludes Web Search — it has its own toggle)
    PluginOverlayBottomSheet(
        show = showPluginOverlay,
        plugins = nonWebSearchPlugins,
        enabledPluginNames = enabledPluginNames,
        expandedPluginIds = expandedPluginIds,
        multiTurnEnabled = multiTurnEnabled,
        toolCallingConfig = toolCallingConfig,
        onDismiss = { pluginViewModel.hidePluginOverlay() },
        onPluginToggle = { name, enabled ->
            pluginViewModel.togglePluginEnabled(name, enabled)
        },
        onPluginExpand = { name ->
            pluginViewModel.togglePluginExpanded(name)
        },
        onMultiTurnToggle = { pluginViewModel.setMultiTurnEnabled(it) },
        onMaxRoundsChange = { pluginViewModel.setMaxRounds(it) }
    )

    // Memory Overlay
    MemoryOverlayBottomSheet(
        show = showMemoryOverlay,
        isMemoryEnabled = isMemoryEnabled,
        vaultStats = vaultStats,
        memoryResults = memoryResults,
        memoryEntryCount = memoryEntryCount,
        onDismiss = { memoryViewModel.dismissMemoryOverlay() },
        onMemoryEnabledChange = { memoryViewModel.setMemoryEnabled(it) },
        onRefreshStats = { memoryViewModel.refreshStats() }
    )

    Column(
        modifier = Modifier.then(if (liquidState != null) Modifier.liquid(liquidState) else Modifier)
    ) {
        AnimatedVisibility(config.showModelList) {
            if (installedModels.isEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(Standards.SpacingSm)
                        .background(Glass.StatusErrorSurface, shape = RoundedCornerShape(Standards.RadiusMd))
                        .border(1.dp, Glass.StatusError.copy(alpha = 0.3f), RoundedCornerShape(Standards.RadiusMd))
                        .padding(horizontal = Standards.SpacingMd, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Standards.SpacingSm)
                ) {
                    Icon(
                        imageVector = TnIcons.AlertTriangle,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = Glass.StatusError
                    )
                    Text(
                        "No models installed. Download one from the store or load a local GGUF file.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Glass.TextPrimary
                    )
                }
            } else {
                ModelList(
                    installedModels = installedModels,
                    currentModelID = currentModelID,
                    onClickListener = { selectedModel ->
                        if (currentModelID == selectedModel.id) {
                            llmModelViewModel.unloadModel()
                            chatViewModel.hideModelList()
                        } else {
                            llmModelViewModel.loadModel(selectedModel)
                            onModelSelectedNavigate(selectedModel)
                            chatViewModel.hideModelList()
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(Standards.SpacingSm)
                        .background(Glass.Surface, shape = RoundedCornerShape(Standards.RadiusMd))
                        .border(1.dp, Glass.BorderSubtle, RoundedCornerShape(Standards.RadiusMd)),
                    maxHeight = 200.dp
                )
            }
        }

        // ── Main bottom bar container (Transparent floating gradient scrim) ──
        val bottomScrim = androidx.compose.ui.graphics.Brush.verticalGradient(
            colors = listOf(
                Color.Transparent,
                Color(0xCC000000), // 80% black
                Color(0xFF000000)  // Solid black
            )
        )
        Box(
            Modifier
                .fillMaxWidth()
                .background(brush = bottomScrim)
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .imePadding()
                    .padding(horizontal = Standards.SpacingMd)
                    .padding(top = Standards.SpacingSm, bottom = Standards.SpacingMd)
            ) {

                // ── Feature Toggle Chips — always visible, horizontally scrollable ──
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(bottom = Standards.SpacingSm),
                    horizontalArrangement = Arrangement.spacedBy(Standards.ChipSpacing)
                ) {
                    // Web Search chip
                    if (toolCallingEnabled) {
                        GlassChip(
                            text = "Web",
                            icon = TnIcons.World,
                            isActive = isWebSearchEnabled,
                            activeColor = Glass.AccentSecondary,
                            onClick = { pluginViewModel.toggleWebSearch(!isWebSearchEnabled) }
                        )
                    }

                    // RAG chip
                    GlassChip(
                        text = if (loadedRags.isNotEmpty()) "${loadedRags.size} RAG" else "RAG",
                        icon = TnIcons.Database,
                        isActive = isRagEnabledForChat && loadedRags.isNotEmpty(),
                        activeColor = Glass.AccentTertiary,
                        onClick = {
                            if (loadedRags.isEmpty()) {
                                context.startActivity(Intent(context, RagActivity::class.java))
                            } else {
                                ragViewModel.toggleRagForChat(!isRagEnabledForChat)
                            }
                        }
                    )

                    // Plugins chip
                    if (toolCallingEnabled && nonWebSearchPlugins.isNotEmpty()) {
                        val activePluginCount = enabledPluginNames.count { it != "Web Search" }
                        GlassChip(
                            text = if (activePluginCount > 0) "$activePluginCount Plugins" else "Plugins",
                            icon = TnIcons.Wrench,
                            isActive = activePluginCount > 0,
                            activeColor = Glass.AccentPrimary,
                            onClick = { pluginViewModel.showPluginOverlay() }
                        )
                    }

                    // Memory chip
                    GlassChip(
                        text = "Memory",
                        icon = TnIcons.Brain,
                        isActive = isMemoryEnabled,
                        activeColor = Glass.AccentWarm,
                        onClick = { memoryViewModel.toggleMemoryOverlay() }
                    )

                    // Thinking mode chip
                    if (isTextModelLoaded) {
                        GlassChip(
                            text = "Thinking",
                            icon = TnIcons.BulbFilled,
                            isActive = chatState.thinkingEnabled,
                            activeColor = Glass.AccentWarm,
                            onClick = { chatViewModel.setThinkingMode(!chatState.thinkingEnabled) }
                        )
                    }
                }

                // ── Edit prompt banner ──
                AnimatedVisibility(visible = promptEditState != null) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = Standards.SpacingSm)
                            .background(Glass.Surface, shape = RoundedCornerShape(Standards.RadiusMd))
                            .border(1.dp, Glass.BorderSubtle, RoundedCornerShape(Standards.RadiusMd))
                            .padding(horizontal = Standards.SpacingSm, vertical = Standards.SpacingXs),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(Standards.SpacingXs),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(
                                imageVector = TnIcons.Edit,
                                contentDescription = null,
                                tint = Glass.AccentPrimary,
                                modifier = Modifier.size(Standards.IconSm)
                            )
                            Text(
                                text = "Editing prompt",
                                style = MaterialTheme.typography.labelMedium,
                                color = Glass.TextSecondary
                            )
                        }

                        ActionButton(
                            onClickListener = {
                                chatViewModel.cancelPromptEdit()
                                value = ""
                            },
                            icon = TnIcons.X,
                            contentDescription = "Cancel edit",
                            modifier = Modifier.size(36.dp),
                            colors = IconButtonDefaults.filledIconButtonColors(
                                containerColor = Glass.SurfaceSubtle,
                                contentColor = Glass.TextPrimary
                            )
                        )
                    }
                }

                // ── Sleek ChatGPT Style Input Pill ──
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Standards.SpacingSm)
                ) {
                    // Left circular '+' button (for model selector toggle)
                    ActionButton(
                        onClickListener = {
                            if (config.showModelList) {
                                chatViewModel.hideModelList()
                            } else {
                                chatViewModel.showModelList()
                            }
                        },
                        icon = TnIcons.Plus,
                        contentDescription = "Select Models",
                        modifier = Modifier.size(44.dp),
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = Color(0x22FFFFFF), // 13% transparent white circle
                            contentColor = Glass.AccentPrimary
                        )
                    )

                    // Capsule Input Bar
                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .background(
                                color = Color(0xFF1A1A1A), // Dark carbon matching screenshot
                                shape = RoundedCornerShape(24.dp)
                            )
                            .heightIn(min = 46.dp)
                            .padding(horizontal = 6.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = value,
                            onValueChange = { value = it },
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = 38.dp, max = 150.dp)
                                .padding(horizontal = 4.dp),
                            placeholder = {
                                Text(
                                    text = if (promptEditState != null) {
                                        "Edit your prompt…"
                                    } else {
                                        "Ask me anything" // Sleek minimal placeholder
                                    },
                                    color = Glass.TextMuted
                                )
                            },
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedContainerColor = Color.Transparent,
                                unfocusedContainerColor = Color.Transparent,
                                disabledContainerColor = Color.Transparent,
                                focusedBorderColor = Color.Transparent,
                                unfocusedBorderColor = Color.Transparent,
                                cursorColor = Glass.AccentPrimary,
                                focusedTextColor = Glass.TextPrimary,
                                unfocusedTextColor = Glass.TextPrimary
                            ),
                            singleLine = false,
                            maxLines = 5
                        )

                        // Waveform Mode or Send circular button
                        if (chatState.isGenerating) {
                            // Generating stop button - White circle with Black stop square
                            ActionButton(
                                onClickListener = { chatViewModel.stop() },
                                icon = TnIcons.PlayerStop,
                                contentDescription = "Stop generation",
                                modifier = Modifier.size(36.dp),
                                colors = IconButtonDefaults.filledIconButtonColors(
                                    containerColor = Color.White,
                                    contentColor = Color.Black
                                )
                            )
                        } else {
                            val hasText = value.isNotBlank()
                            if (hasText) {
                                // Has text: White circle with Black Up Arrow (matches ChatGPT screenshot!)
                                ActionButton(
                                    onClickListener = {
                                        if (value.isNotBlank()) {
                                            val trimmedValue = value.trim()
                                            // Auto-detect image requests
                                            val isImageTrigger = trimmedValue.startsWith("/image", ignoreCase = true) ||
                                                    trimmedValue.startsWith("/draw", ignoreCase = true) ||
                                                    trimmedValue.startsWith("/paint", ignoreCase = true) ||
                                                    trimmedValue.startsWith("generate image", ignoreCase = true) ||
                                                    trimmedValue.startsWith("create image", ignoreCase = true)

                                            if (isImageTrigger && isImageModelLoaded) {
                                                val cleanPrompt = when {
                                                    trimmedValue.startsWith("/image", ignoreCase = true) -> trimmedValue.removePrefix("/image").trim()
                                                    trimmedValue.startsWith("/draw", ignoreCase = true) -> trimmedValue.removePrefix("/draw").trim()
                                                    trimmedValue.startsWith("/paint", ignoreCase = true) -> trimmedValue.removePrefix("/paint").trim()
                                                    trimmedValue.startsWith("generate image", ignoreCase = true) -> trimmedValue.removePrefix("generate image").trim()
                                                    trimmedValue.startsWith("create image", ignoreCase = true) -> trimmedValue.removePrefix("create image").trim()
                                                    else -> trimmedValue
                                                }.removePrefix(":").removePrefix(" ").trim()

                                                chatViewModel.sendImageRequest(cleanPrompt)
                                                value = ""
                                            } else {
                                                if (promptEditState != null) {
                                                    chatViewModel.applyPromptEdit(value)
                                                    value = ""
                                                    return@ActionButton
                                                }

                                                val hasRags = loadedRags.isNotEmpty() && isRagEnabledForChat

                                                if (hasRags) {
                                                    val userQuery = value
                                                    value = ""
                                                    scope.launch {
                                                        val ragContext = ragViewModel.queryAndStoreResults(userQuery)
                                                        chatViewModel.setRagContext(
                                                            ragContext.ifBlank { null },
                                                            ragViewModel.lastRagResults.value
                                                        )
                                                        chatViewModel.sendTextMessage(userQuery)
                                                    }
                                                } else {
                                                    chatViewModel.clearRagContext()
                                                    chatViewModel.sendTextMessage(value)
                                                    value = ""
                                                }
                                            }
                                        }
                                    },
                                    icon = TnIcons.ArrowUp, // Matches ChatGPT Up Arrow send button
                                    contentDescription = "Send message",
                                    modifier = Modifier.size(36.dp),
                                    colors = IconButtonDefaults.filledIconButtonColors(
                                        containerColor = Color.White,
                                        contentColor = Color.Black
                                    )
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
