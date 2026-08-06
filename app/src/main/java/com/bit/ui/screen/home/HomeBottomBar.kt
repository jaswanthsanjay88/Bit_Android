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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.runtime.rememberUpdatedState
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
import android.net.Uri
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.foundation.Image
import androidx.compose.foundation.Canvas
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.style.TextOverflow
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import android.Manifest
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
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
import kotlinx.coroutines.delay

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
    onLiveVoiceClick: () -> Unit = {},
    onSettingsClick: () -> Unit = {},
    liquidState: LiquidState? = null
) {
    val context = LocalContext.current
    var showSttDownloadDialog by remember { mutableStateOf(false) }
    var value by remember { mutableStateOf("") }
    val isSttRecording by chatViewModel.isSttRecording.collectAsStateWithLifecycle()
    val isSttTranscribing by chatViewModel.isSttTranscribing.collectAsStateWithLifecycle()
    val sttAmplitude by chatViewModel.sttAmplitude.collectAsStateWithLifecycle()

    val micPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            chatViewModel.startSttRecording()
        }
    }
    val installedModels by llmModelViewModel.installedModels.collectAsStateWithLifecycle(emptyList())
    val currentModelID by llmModelViewModel.currentModelID.collectAsStateWithLifecycle()
    val chatState by chatViewModel.chatUiState.collectAsStateWithLifecycle()
    val config by chatViewModel.chatConfigState.collectAsStateWithLifecycle()
    val promptEditState by chatViewModel.promptEditState.collectAsStateWithLifecycle()
    val isTextModelLoaded by chatViewModel.isTextModelLoaded.collectAsStateWithLifecycle()
    val isImageModelLoaded by chatViewModel.isImageModelLoaded.collectAsStateWithLifecycle()
    val isVlmLoaded by chatViewModel.isVlmLoaded.collectAsStateWithLifecycle()
    val huggingFaceToken by chatViewModel.huggingFaceToken.collectAsStateWithLifecycle()

    var showAttachmentSheet by remember { mutableStateOf(false) }
    var attachedImages by remember { mutableStateOf<List<Uri>>(emptyList()) }
    var attachedFiles by remember { mutableStateOf<List<Uri>>(emptyList()) }

    val galleryLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            attachedImages = attachedImages + it
            showAttachmentSheet = false
        }
    }

    val isRagProcessing by chatViewModel.isRagProcessing.collectAsStateWithLifecycle()
    
    val fileLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            attachedFiles = attachedFiles + it
            chatViewModel.attachDocument(it)
            showAttachmentSheet = false
        }
    }

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
    
    val haptics = com.bit.ui.theme.LocalBitHaptics.current



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

    // Add Attachment Sheet
    AddAttachmentBottomSheet(
        show = showAttachmentSheet,
        onDismiss = { showAttachmentSheet = false },
        onModelClick = {
            if (config.showModelList) chatViewModel.hideModelList() else chatViewModel.showModelList()
            showAttachmentSheet = false
        },
        onGalleryClick = {
            galleryLauncher.launch("image/*")
            showAttachmentSheet = false
        },
        onFilesClick = {
            fileLauncher.launch("*/*")
            showAttachmentSheet = false
        },
        toolCallingEnabled = toolCallingEnabled,
        isWebSearchEnabled = isWebSearchEnabled,
        onWebSearchToggle = { pluginViewModel.toggleWebSearch(it) },
        isMemoryEnabled = isMemoryEnabled,
        onMemoryClick = {
            memoryViewModel.toggleMemoryOverlay()
            showAttachmentSheet = false
        },
        isRagEnabled = isRagEnabledForChat,
        onRagClick = {
            ragViewModel.toggleRagForChat(!isRagEnabledForChat)
            showAttachmentSheet = false
        },
        activePluginCount = enabledPluginNames.count { it != "Web Search" },
        onPluginClick = {
            pluginViewModel.showPluginOverlay()
            showAttachmentSheet = false
        },
        isThinkingEnabled = chatState.thinkingEnabled,
        onThinkingToggle = { chatViewModel.setThinkingMode(it) }
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

                // ── M3 Capsule Input Pill ──
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            color = Color(0xFF1E1E1E), // M3 filled container dark carbon
                            shape = RoundedCornerShape(28.dp)
                        )
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (isSttRecording || isSttTranscribing) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            // 1. Cancel button
                            ActionButton(
                                onClickListener = { chatViewModel.cancelSttRecording() },
                                icon = TnIcons.X,
                                contentDescription = "Cancel recording",
                                modifier = Modifier.size(32.dp),
                                colors = IconButtonDefaults.filledIconButtonColors(
                                    containerColor = Color(0x1AFFFFFF),
                                    contentColor = Color.White
                                )
                            )

                            // 2. Equalizer / Status
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.weight(1f).padding(horizontal = 12.dp)
                            ) {
                                if (isSttTranscribing) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(16.dp),
                                        strokeWidth = 2.dp,
                                        color = Color.White
                                    )
                                    Text(
                                        text = "Transcribing…",
                                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                                        color = Color.White,
                                        maxLines = 1
                                    )
                                } else {
                                    Text(
                                        text = "Listening",
                                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                                        color = Color.White,
                                        maxLines = 1
                                    )
                                    EqualizerBars(
                                        amplitude = sttAmplitude,
                                        modifier = Modifier.weight(1f).height(24.dp),
                                        activeColor = Color.White
                                    )
                                }
                            }

                            // 3. Stop/Check button
                            ActionButton(
                                onClickListener = {
                                    chatViewModel.stopSttRecording { transcribedText ->
                                        value = (value + " " + transcribedText).trim()
                                    }
                                },
                                icon = TnIcons.Check,
                                contentDescription = "Stop and transcribe",
                                modifier = Modifier.size(32.dp),
                                colors = IconButtonDefaults.filledIconButtonColors(
                                    containerColor = Color.White,
                                    contentColor = Color.Black
                                ),
                                enabled = !isSttTranscribing
                            )
                        }
                    } else {
                        // Plus (+) button inside the pill
                        FilledTonalIconButton(
                            onClick = { showAttachmentSheet = true },
                            modifier = Modifier.size(36.dp),
                            colors = IconButtonDefaults.filledTonalIconButtonColors(
                                containerColor = Color(0x11FFFFFF),
                                contentColor = Color.White
                            )
                        ) {
                            Icon(
                                imageVector = TnIcons.Plus,
                                contentDescription = "Add attachment",
                                modifier = Modifier.size(20.dp)
                            )
                        }

                        Spacer(modifier = Modifier.width(4.dp))

                        // Column for attachments and TextField
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .padding(vertical = 2.dp)
                        ) {
                            if (attachedImages.isNotEmpty() || attachedFiles.isNotEmpty()) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .horizontalScroll(rememberScrollState())
                                        .padding(horizontal = 4.dp, vertical = 4.dp),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    attachedImages.forEach { uri ->
                                        Box(
                                            modifier = Modifier
                                                .size(56.dp)
                                                .clip(RoundedCornerShape(8.dp))
                                                .background(Color(0x33FFFFFF))
                                        ) {
                                            val resolver = context.contentResolver
                                            val bitmap = remember(uri) {
                                                try {
                                                    resolver.openInputStream(uri)?.use { stream ->
                                                        android.graphics.BitmapFactory.decodeStream(stream)
                                                    }
                                                } catch (e: Exception) {
                                                    null
                                                }
                                            }
                                            if (bitmap != null) {
                                                Image(
                                                    bitmap = bitmap.asImageBitmap(),
                                                    contentDescription = "Attachment",
                                                    contentScale = ContentScale.Crop,
                                                    modifier = Modifier.fillMaxSize()
                                                )
                                            }
                                            Icon(
                                                imageVector = TnIcons.X,
                                                contentDescription = "Remove",
                                                modifier = Modifier
                                                    .size(16.dp)
                                                    .align(Alignment.TopEnd)
                                                    .clickable { attachedImages = attachedImages - uri }
                                                    .background(Color(0xCC000000), CircleShape)
                                                    .padding(2.dp),
                                                tint = Color.White
                                            )
                                        }
                                    }
                                    attachedFiles.forEach { uri ->
                                        val fileName = remember(uri) {
                                            val cursor = context.contentResolver.query(uri, null, null, null, null)
                                            cursor?.use {
                                                val nameIndex = it.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                                                if (nameIndex != -1 && it.moveToFirst()) it.getString(nameIndex) else "File"
                                            } ?: "File"
                                        }
                                        Row(
                                            modifier = Modifier
                                                .height(32.dp)
                                                .clip(RoundedCornerShape(8.dp))
                                                .background(Color(0x22FFFFFF))
                                                .border(0.5.dp, Color(0x44FFFFFF), RoundedCornerShape(8.dp))
                                                .padding(horizontal = 8.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                                        ) {
                                            Icon(
                                                imageVector = TnIcons.FileText,
                                                contentDescription = null,
                                                modifier = Modifier.size(16.dp),
                                                tint = Glass.AccentSecondary
                                            )
                                            Text(
                                                text = fileName,
                                                style = MaterialTheme.typography.labelSmall,
                                                color = Glass.TextPrimary,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                                modifier = Modifier.widthIn(max = 100.dp)
                                            )
                                            if (isRagProcessing) {
                                                CircularProgressIndicator(
                                                    modifier = Modifier.size(14.dp),
                                                    strokeWidth = 2.dp,
                                                    color = Glass.AccentPrimary
                                                )
                                            } else {
                                                Icon(
                                                    imageVector = TnIcons.X,
                                                    contentDescription = "Remove",
                                                    modifier = Modifier
                                                        .size(14.dp)
                                                        .clickable { 
                                                            attachedFiles = attachedFiles - uri
                                                            chatViewModel.clearAttachedDocument()
                                                        },
                                                    tint = Glass.TextSecondary
                                                )
                                            }
                                        }
                                    }
                                }
                            }

                            OutlinedTextField(
                                value = value,
                                onValueChange = { value = it },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 38.dp, max = 150.dp)
                                    .padding(horizontal = 4.dp),
                                placeholder = {
                                    Text(
                                        text = if (promptEditState != null) "Edit your prompt…" else "Ask me anything",
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
                        }

                        Spacer(modifier = Modifier.width(4.dp))

                        // Trailing Buttons: Send or Mic + Live Mode (Animated swap)
                        val canSend = value.isNotBlank() || attachedImages.isNotEmpty() || attachedFiles.isNotEmpty()
                        
                        AnimatedContent(
                            targetState = Pair(canSend, chatState.isGenerating),
                            transitionSpec = {
                                fadeIn(animationSpec = tween(250, easing = FastOutSlowInEasing)) togetherWith
                                fadeOut(animationSpec = tween(250, easing = FastOutSlowInEasing))
                            },
                            label = "trailing_buttons_motion"
                        ) { (hasInput, isGenerating) ->
                            if (isGenerating) {
                                FilledTonalIconButton(
                                    onClick = { chatViewModel.stop() },
                                    modifier = Modifier.size(36.dp),
                                    colors = IconButtonDefaults.filledTonalIconButtonColors(
                                        containerColor = MaterialTheme.colorScheme.errorContainer,
                                        contentColor = MaterialTheme.colorScheme.onErrorContainer
                                    )
                                ) {
                                    Icon(
                                        imageVector = TnIcons.PlayerStop,
                                        contentDescription = "Stop generation",
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            } else if (hasInput) {
                                FilledTonalIconButton(
                                    onClick = {
                                        haptics.action()
                                        val trimmedValue = value.trim()
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
                                            val finalPrompt = trimmedValue

                                            val imageBytesList = attachedImages.mapNotNull { uri ->
                                                try {
                                                    context.contentResolver.openInputStream(uri)?.use { stream ->
                                                        stream.readBytes()
                                                    }
                                                } catch (e: Exception) {
                                                    null
                                                }
                                            }

                                            if (imageBytesList.isNotEmpty()) {
                                                chatViewModel.clearRagContext()
                                                chatViewModel.sendChatWithImages(finalPrompt, imageBytesList)
                                                value = ""
                                                attachedImages = emptyList()
                                                attachedFiles = emptyList()
                                            } else {
                                                if (promptEditState != null) {
                                                    chatViewModel.applyPromptEdit(finalPrompt)
                                                    value = ""
                                                    attachedImages = emptyList()
                                                    attachedFiles = emptyList()
                                                } else {
                                                    val hasRags = loadedRags.isNotEmpty() && isRagEnabledForChat
                                                    if (hasRags) {
                                                        value = ""
                                                        attachedImages = emptyList()
                                                        attachedFiles = emptyList()
                                                        scope.launch {
                                                            val ragContext = ragViewModel.queryAndStoreResults(finalPrompt)
                                                            chatViewModel.setRagContext(
                                                                ragContext.ifBlank { null },
                                                                ragViewModel.lastRagResults.value
                                                            )
                                                            chatViewModel.sendTextMessage(finalPrompt)
                                                        }
                                                    } else {
                                                        chatViewModel.clearRagContext()
                                                        chatViewModel.sendTextMessage(finalPrompt)
                                                        value = ""
                                                        attachedImages = emptyList()
                                                        attachedFiles = emptyList()
                                                    }
                                                }
                                            }
                                        }
                                    },
                                    modifier = Modifier.size(36.dp),
                                    colors = IconButtonDefaults.filledTonalIconButtonColors(
                                        containerColor = Color.White,
                                        contentColor = Color.Black
                                    )
                                ) {
                                    Icon(
                                        imageVector = TnIcons.ArrowUp,
                                        contentDescription = "Send message",
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            } else {
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    // Voice typing STT button
                                    FilledTonalIconButton(
                                        onClick = {
                                            val hasPermission = ContextCompat.checkSelfPermission(
                                                context,
                                                Manifest.permission.RECORD_AUDIO
                                            ) == PackageManager.PERMISSION_GRANTED
                                            if (hasPermission) {
                                                if (!com.bit.stt.SherpaSTTEngine.hasModelFiles(context)) {
                                                    showSttDownloadDialog = true
                                                } else {
                                                    chatViewModel.startSttRecording()
                                                }
                                            } else {
                                                micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                            }
                                        },
                                        modifier = Modifier.size(36.dp),
                                        colors = IconButtonDefaults.filledTonalIconButtonColors(
                                            containerColor = Color(0x1AFFFFFF),
                                            contentColor = Color.White
                                        )
                                    ) {
                                        Icon(
                                            imageVector = TnIcons.Microphone,
                                            contentDescription = "Voice typing",
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }

                                    // Live Voice Mode button
                                    FilledTonalIconButton(
                                        onClick = {
                                            val hasPermission = ContextCompat.checkSelfPermission(
                                                context,
                                                Manifest.permission.RECORD_AUDIO
                                            ) == PackageManager.PERMISSION_GRANTED
                                            if (hasPermission) {
                                                if (!com.bit.stt.SherpaSTTEngine.hasModelFiles(context)) {
                                                    showSttDownloadDialog = true
                                                } else {
                                                    onLiveVoiceClick()
                                                }
                                            } else {
                                                micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                            }
                                        },
                                        modifier = Modifier.size(36.dp),
                                        colors = IconButtonDefaults.filledTonalIconButtonColors(
                                            containerColor = Color.White,
                                            contentColor = Color.Black
                                        )
                                    ) {
                                        Icon(
                                            imageVector = TnIcons.LiveWaveform,
                                            contentDescription = "Live Voice Mode",
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                if (showSttDownloadDialog) {
                    androidx.compose.material3.AlertDialog(
                        onDismissRequest = { showSttDownloadDialog = false },
                        title = { Text("Speech Model Required") },
                        text = { Text("To use voice features, please download the speech model first. You can find it under Speech Settings.") },
                        confirmButton = {
                            androidx.compose.material3.TextButton(
                                onClick = {
                                    showSttDownloadDialog = false
                                    onSettingsClick()
                                }
                            ) {
                                Text("Go to Settings")
                            }
                        },
                        dismissButton = {
                            androidx.compose.material3.TextButton(
                                onClick = { showSttDownloadDialog = false }
                            ) {
                                Text("Cancel")
                            }
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun EqualizerBars(
    amplitude: Float,
    modifier: Modifier = Modifier,
    activeColor: Color = Color.White,
    barCount: Int = 20,
    barWindowMs: Long = 80L
) {
    val bars = remember { mutableStateOf(FloatArray(barCount)) }
    val currentAmplitude by rememberUpdatedState(amplitude)
    LaunchedEffect(Unit) {
        while (true) {
            val current = bars.value
            val next = FloatArray(barCount)
            for (i in 0 until barCount - 1) {
                next[i] = current[i + 1]
            }
            next[barCount - 1] = currentAmplitude.coerceIn(0f, 1f)
            bars.value = next
            delay(barWindowMs)
        }
    }

    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp, Alignment.CenterHorizontally),
    ) {
        val currentBars = bars.value
        for (i in currentBars.indices) {
            val raw = currentBars[i]
            val target = (0.1f + raw * 0.9f).coerceIn(0.1f, 1f)
            val animated by animateFloatAsState(
                targetValue = target,
                animationSpec = tween(durationMillis = 100),
                label = "bar_$i",
            )
            Spacer(
                modifier = Modifier
                    .width(3.dp)
                    .fillMaxHeight(animated)
                    .background(
                        color = activeColor.copy(alpha = 0.3f + animated * 0.7f),
                        shape = RoundedCornerShape(1.5.dp),
                    ),
            )
        }
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun AddAttachmentBottomSheet(
    show: Boolean,
    onDismiss: () -> Unit,
    onModelClick: () -> Unit,
    onGalleryClick: () -> Unit,
    onFilesClick: () -> Unit,
    toolCallingEnabled: Boolean,
    isWebSearchEnabled: Boolean,
    onWebSearchToggle: (Boolean) -> Unit,
    isMemoryEnabled: Boolean,
    onMemoryClick: () -> Unit,
    isRagEnabled: Boolean,
    onRagClick: () -> Unit,
    activePluginCount: Int,
    onPluginClick: () -> Unit,
    isThinkingEnabled: Boolean,
    onThinkingToggle: (Boolean) -> Unit
) {
    if (show) {
        androidx.compose.material3.ModalBottomSheet(
            onDismissRequest = onDismiss,
            containerColor = androidx.compose.material3.MaterialTheme.colorScheme.surfaceContainerHigh
        ) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp).padding(bottom = 32.dp)) {
                // Header row: X + centered title
                Box(Modifier.fillMaxWidth()) {
                    androidx.compose.material3.IconButton(onClick = onDismiss, modifier = Modifier.align(Alignment.CenterStart)) {
                        Icon(TnIcons.X, null, tint = androidx.compose.material3.MaterialTheme.colorScheme.onSurface)
                    }
                    Text("Add to chat", modifier = Modifier.align(Alignment.Center), style = androidx.compose.material3.MaterialTheme.typography.titleMedium, color = androidx.compose.material3.MaterialTheme.colorScheme.onSurface)
                }

                Spacer(Modifier.height(20.dp))

                // Zone 1: instant-action grid
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    GridActionButton(icon = TnIcons.Photo, label = "Photos", onClick = onGalleryClick)
                    GridActionButton(icon = TnIcons.Folder, label = "Files", onClick = onFilesClick)
                    GridActionButton(icon = TnIcons.BrainCircuit, label = "Models", onClick = onModelClick)
                }

                Spacer(Modifier.height(16.dp))
                androidx.compose.material3.HorizontalDivider(color = androidx.compose.material3.MaterialTheme.colorScheme.outlineVariant)
                Spacer(Modifier.height(8.dp))

                // Zone 2: persistent toggles
                if (toolCallingEnabled) {
                    ToggleRow(icon = TnIcons.World, title = "Web search", checked = isWebSearchEnabled, onCheckedChange = onWebSearchToggle)
                }
                ToggleRow(icon = TnIcons.Database, title = "Connectors", subtitle = if (isRagEnabled) "On" else "Off", onClick = onRagClick)
                if (toolCallingEnabled) {
                    ToggleRow(icon = TnIcons.Wrench, title = "Tool access", subtitle = if (activePluginCount > 0) "$activePluginCount active" else "Auto", onClick = onPluginClick)
                }
                ToggleRow(icon = TnIcons.Brain, title = "Memory", subtitle = if (isMemoryEnabled) "On" else "Off", onClick = onMemoryClick)
                ToggleRow(icon = TnIcons.BulbFilled, title = "Reasoning", checked = isThinkingEnabled, onCheckedChange = onThinkingToggle)
            }
        }
    }
}

@Composable
private fun GridActionButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        androidx.compose.material3.Surface(
            onClick = onClick,
            shape = androidx.compose.foundation.shape.CircleShape,
            color = androidx.compose.material3.MaterialTheme.colorScheme.surfaceContainerHighest,
            modifier = Modifier.size(48.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(imageVector = icon, contentDescription = label, modifier = Modifier.size(24.dp), tint = androidx.compose.material3.MaterialTheme.colorScheme.onSurface)
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(text = label, style = androidx.compose.material3.MaterialTheme.typography.labelMedium, color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ToggleRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    checked: Boolean? = null,
    onCheckedChange: ((Boolean) -> Unit)? = null,
    subtitle: String? = null,
    onClick: (() -> Unit)? = null
) {
    androidx.compose.material3.ListItem(
        modifier = if (onClick != null) Modifier.clickable { onClick() } else Modifier,
        leadingContent = {
            Icon(imageVector = icon, contentDescription = title, tint = androidx.compose.material3.MaterialTheme.colorScheme.onSurface)
        },
        headlineContent = {
            Text(text = title, style = androidx.compose.material3.MaterialTheme.typography.bodyLarge, color = androidx.compose.material3.MaterialTheme.colorScheme.onSurface)
        },
        trailingContent = {
            if (checked != null && onCheckedChange != null) {
                androidx.compose.material3.Switch(checked = checked, onCheckedChange = onCheckedChange)
            } else if (subtitle != null) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(text = subtitle, style = androidx.compose.material3.MaterialTheme.typography.bodyMedium, color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant)
                    Icon(imageVector = TnIcons.ChevronRight, contentDescription = null, tint = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
                }
            }
        },
        colors = androidx.compose.material3.ListItemDefaults.colors(
            containerColor = Color.Transparent
        )
    )
}
