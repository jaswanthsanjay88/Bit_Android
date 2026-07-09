package com.bit.ui.screen.setup

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bit.global.Standards
import com.bit.service.ModelDownloadService
import com.bit.ui.components.PasswordTextField
import com.bit.ui.icons.TnIcons
import com.bit.viewmodel.SetupOption
import com.bit.viewmodel.SetupViewModel
import com.bit.worker.SystemBackupManager
import com.bit.models.data.HuggingFaceModel
import com.bit.ui.theme.BitColors
import com.bit.ui.theme.Motion
import kotlinx.coroutines.delay

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun SetupScreen(
    sharedTransitionScope: SharedTransitionScope,
    animatedVisibilityScope: AnimatedVisibilityScope,
    onSetupComplete: () -> Unit
) {
    val viewModel: SetupViewModel = viewModel()
    val selectedOption by viewModel.selectedOption.collectAsStateWithLifecycle()
    val downloadStates by viewModel.downloadStates.collectAsStateWithLifecycle()
    val setupComplete by viewModel.setupComplete.collectAsStateWithLifecycle()
    val downloadError by viewModel.downloadError.collectAsStateWithLifecycle()
    val primaryModelId by viewModel.primaryModelId.collectAsStateWithLifecycle()
    val recommendedTextModel by viewModel.recommendedTextModel.collectAsStateWithLifecycle()

    var currentSlide by remember { mutableIntStateOf(0) }
    var selectLocalOption by remember { mutableStateOf(true) }

    LaunchedEffect(setupComplete) {
        if (setupComplete) {
            delay(400)
            onSetupComplete()
        }
    }

    val isDownloading = selectedOption != null && selectedOption != SetupOption.POWER_MODE

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(BitColors.Background)
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 24.dp)
            .padding(bottom = 24.dp)
    ) {
        AnimatedContent(
            targetState = isDownloading,
            transitionSpec = {
                fadeIn(tween(280)) togetherWith fadeOut(tween(180))
            },
            label = "setupPhase"
        ) { downloading ->
            if (downloading) {
                DownloadProgressContent(
                    viewModel = viewModel,
                    selectedOption = selectedOption,
                    downloadStates = downloadStates,
                    downloadError = downloadError,
                    primaryModelId = primaryModelId
                )
            } else {
                when (currentSlide) {
                    0 -> WelcomeSlide(
                        onNext = { currentSlide = 1 }
                    )
                    1 -> SetupChoiceSlide(
                        selectLocal = selectLocalOption,
                        onSelectLocal = { selectLocalOption = true },
                        onSelectRemote = { selectLocalOption = false },
                        onNext = { currentSlide = 2 },
                        onBack = { currentSlide = 0 }
                    )
                    2 -> {
                        if (selectLocalOption) {
                            ModelPickerContent(
                                viewModel = viewModel,
                                sharedTransitionScope = sharedTransitionScope,
                                animatedVisibilityScope = animatedVisibilityScope,
                                recommendedTextModel = recommendedTextModel,
                                onBack = { currentSlide = 1 }
                            )
                        } else {
                            RemoteApiConfigContent(
                                viewModel = viewModel,
                                onBack = { currentSlide = 1 }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun WelcomeSlide(
    onNext: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.SpaceBetween,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(64.dp))

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.weight(1f)
        ) {
            Surface(
                color = BitColors.SurfaceAlt,
                shape = RoundedCornerShape(24.dp),
                modifier = Modifier.size(96.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = TnIcons.Lock,
                        contentDescription = null,
                        tint = BitColors.TextPrimary,
                        modifier = Modifier.size(48.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "Welcome to BIT",
                style = androidx.compose.ui.text.TextStyle(
                    color = BitColors.TextPrimary,
                    fontSize = 32.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = (-1).sp
                ),
                textAlign = TextAlign.Center
            )

            Text(
                text = "Your private, secure on-device AI assistant.\nChoose how you want to connect to AI models.",
                style = androidx.compose.ui.text.TextStyle(
                    color = BitColors.TextSecondary,
                    fontSize = 15.sp,
                    lineHeight = 22.sp
                ),
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 16.dp)
            )
        }

        Button(
            onClick = onNext,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            shape = RoundedCornerShape(28.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = BitColors.Inverse,
                contentColor = BitColors.OnInverse
            )
        ) {
            Text(
                text = "Set Up BIT",
                style = androidx.compose.ui.text.TextStyle(
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
                )
            )
        }
    }
}

@Composable
private fun SetupChoiceSlide(
    selectLocal: Boolean,
    onSelectLocal: () -> Unit,
    onSelectRemote: () -> Unit,
    onNext: () -> Unit,
    onBack: () -> Unit
) {
    val haptics = com.bit.ui.theme.LocalBitHaptics.current

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(
                onClick = onBack,
                modifier = Modifier.align(Alignment.Start)
            ) {
                Text("Back", color = BitColors.TextTertiary)
            }
            Text(
                text = "Choose AI Provider Type",
                style = androidx.compose.ui.text.TextStyle(
                    color = BitColors.TextPrimary,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = (-0.5).sp
                )
            )
            Text(
                text = "Decide whether to run local models on-device or connect to high-speed cloud APIs.",
                style = androidx.compose.ui.text.TextStyle(
                    color = BitColors.TextSecondary,
                    fontSize = 14.sp,
                    lineHeight = 20.sp
                )
            )
        }

        Column(
            modifier = Modifier.weight(1f).padding(vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(20.dp))
                    .background(if (selectLocal) BitColors.SurfaceAlt else BitColors.Surface)
                    .border(
                        width = if (selectLocal) 1.5.dp else 1.dp,
                        color = if (selectLocal) BitColors.TextPrimary else BitColors.Border,
                        shape = RoundedCornerShape(20.dp)
                    )
                    .clickable {
                        haptics.selection()
                        onSelectLocal()
                    }
            ) {
                Row(
                    modifier = Modifier.padding(20.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = TnIcons.Lock,
                        contentDescription = null,
                        tint = if (selectLocal) BitColors.TextPrimary else BitColors.TextSecondary,
                        modifier = Modifier.size(36.dp)
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Local Offline AI (Recommended)",
                            fontWeight = FontWeight.Bold,
                            color = BitColors.TextPrimary,
                            fontSize = 16.sp
                        )
                        Text(
                            text = "Runs models directly on your device. 100% private, no internet required, zero data collection.",
                            color = BitColors.TextSecondary,
                            fontSize = 13.sp,
                            lineHeight = 18.sp
                        )
                    }
                }
            }

            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(20.dp))
                    .background(if (!selectLocal) BitColors.SurfaceAlt else BitColors.Surface)
                    .border(
                        width = if (!selectLocal) 1.5.dp else 1.dp,
                        color = if (!selectLocal) BitColors.TextPrimary else BitColors.Border,
                        shape = RoundedCornerShape(20.dp)
                    )
                    .clickable {
                        haptics.selection()
                        onSelectRemote()
                    }
            ) {
                Row(
                    modifier = Modifier.padding(20.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = TnIcons.ArrowUp,
                        contentDescription = null,
                        tint = if (!selectLocal) BitColors.TextPrimary else BitColors.TextSecondary,
                        modifier = Modifier.size(36.dp)
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Cloud APIs (Bring Your Own Key)",
                            fontWeight = FontWeight.Bold,
                            color = BitColors.TextPrimary,
                            fontSize = 16.sp
                        )
                        Text(
                            text = "Connect Google Gemini, OpenAI, Anthropic Claude, DeepSeek, or other endpoints for advanced cloud speed.",
                            color = BitColors.TextSecondary,
                            fontSize = 13.sp,
                            lineHeight = 18.sp
                        )
                    }
                }
            }
        }

        Button(
            onClick = onNext,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            shape = RoundedCornerShape(28.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = BitColors.Inverse,
                contentColor = BitColors.OnInverse
            )
        ) {
            Text(
                text = "Continue",
                style = androidx.compose.ui.text.TextStyle(
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
                )
            )
        }
    }
}

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun ModelPickerContent(
    viewModel: SetupViewModel,
    sharedTransitionScope: SharedTransitionScope,
    animatedVisibilityScope: AnimatedVisibilityScope,
    recommendedTextModel: HuggingFaceModel,
    onBack: () -> Unit
) {
    val haptics = com.bit.ui.theme.LocalBitHaptics.current
    var localSelectedOption by remember { mutableStateOf<SetupOption?>(null) }
    
    val context = LocalContext.current
    val ramGb = remember {
        try {
            val activityManager = context.getSystemService(android.content.Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            val memoryInfo = android.app.ActivityManager.MemoryInfo()
            activityManager.getMemoryInfo(memoryInfo)
            memoryInfo.totalMem / (1024.0 * 1024.0 * 1024.0)
        } catch (e: Exception) { 8.0 }
    }
    
    val ramGuidance = when {
        ramGb < 4.0 -> "Low memory detected (${String.format("%.1f", ramGb)}GB RAM). 1B models recommended."
        ramGb < 8.0 -> "Standard memory detected (${String.format("%.1f", ramGb)}GB RAM). Optimized for 1B or 3B models."
        else -> "Premium memory detected (${String.format("%.1f", ramGb)}GB RAM). Highly recommended to use 3B models."
    }

    data class PickerItem(
        val option: SetupOption,
        val title: String,
        val size: String,
        val description: String
    )

    val items = remember(recommendedTextModel) {
        val list = mutableListOf<PickerItem>()
        list.add(PickerItem(
            SetupOption.TEXT,
            "Llama-3.2 1B Instruct",
            "640 MB",
            "Highly responsive, optimized for low memory usage."
        ))
        
        if (recommendedTextModel.id != "unsloth-llama-3_2-1b-instruct-q4_k_m") {
            list.add(PickerItem(
                SetupOption.TEXT_RECOMMENDED,
                recommendedTextModel.name,
                recommendedTextModel.approximateSize,
                "Stronger reasoning capacity. Optimal choice for this device."
            ))
        }
        


        list.add(PickerItem(
            SetupOption.POWER_MODE,
            "Skip & Go to Chat",
            "0 MB",
            "Minimal startup. Bring your own models or use API keys."
        ))
        list
    }

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.Start
        ) {
            TextButton(
                onClick = onBack,
                modifier = Modifier.align(Alignment.Start)
            ) {
                Text("Back", color = BitColors.TextTertiary)
            }
            Text(
                text = "Choose offline model",
                style = androidx.compose.ui.text.TextStyle(
                    color = BitColors.TextPrimary,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = (-0.5).sp
                )
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = ramGuidance,
                style = androidx.compose.ui.text.TextStyle(
                    color = BitColors.TextSecondary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Normal,
                    lineHeight = 18.sp
                )
            )
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .padding(vertical = 16.dp)
                .fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items.forEach { item ->
                val isSelected = localSelectedOption == item.option
                
                val strokeWidth = if (isSelected) 1.5.dp else 1.dp
                val strokeColor = if (isSelected) BitColors.TextPrimary else BitColors.Border
                val cardBgColor = if (isSelected) BitColors.SurfaceAlt else BitColors.Surface

                val cardModifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(20.dp))
                    .background(cardBgColor)
                    .border(strokeWidth, strokeColor, RoundedCornerShape(20.dp))
                    .clickable {
                        haptics.selection()
                        localSelectedOption = item.option
                    }

                val finalModifier = if (isSelected) {
                    with(sharedTransitionScope) {
                        cardModifier.sharedBounds(
                            sharedTransitionScope.rememberSharedContentState(key = "chat_header"),
                            animatedVisibilityScope = animatedVisibilityScope
                        )
                    }
                } else {
                    cardModifier
                }

                Row(
                    modifier = finalModifier.padding(20.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = item.title,
                                style = androidx.compose.ui.text.TextStyle(
                                    color = BitColors.TextPrimary,
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            )
                            
                            Box(
                                modifier = Modifier
                                    .border(1.dp, BitColors.TextTertiary, RoundedCornerShape(100.dp))
                                    .padding(horizontal = 8.dp, vertical = 2.dp)
                            ) {
                                Text(
                                    text = item.size,
                                    style = androidx.compose.ui.text.TextStyle(
                                        color = BitColors.TextTertiary,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Medium
                                    )
                                )
                            }
                        }
                        
                        Spacer(modifier = Modifier.height(4.dp))
                        
                        Text(
                            text = item.description,
                            style = androidx.compose.ui.text.TextStyle(
                                color = BitColors.TextSecondary,
                                fontSize = 13.sp,
                                lineHeight = 18.sp
                            )
                        )
                    }
                }
            }
        }

        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            RestoreFromBackupSection(viewModel = viewModel)

            val isEnabled = localSelectedOption != null
            val buttonAlpha = if (isEnabled) 1.0f else 0.3f
            
            Button(
                onClick = {
                    localSelectedOption?.let {
                        viewModel.selectOption(it)
                    }
                },
                enabled = isEnabled,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .alpha(buttonAlpha),
                shape = RoundedCornerShape(28.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = BitColors.Inverse,
                    contentColor = BitColors.OnInverse,
                    disabledContainerColor = BitColors.Inverse,
                    disabledContentColor = BitColors.OnInverse
                )
            ) {
                Text(
                    text = if (localSelectedOption == SetupOption.POWER_MODE) "Go to Chat" else "Start Chatting",
                    style = androidx.compose.ui.text.TextStyle(
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold
                    )
                )
            }
        }
    }
}

@Composable
private fun RemoteApiConfigContent(
    viewModel: SetupViewModel,
    onBack: () -> Unit
) {
    val providers = listOf("Google Gemini", "OpenAI", "Anthropic Claude", "Nvidia NIM", "DeepSeek", "Custom")
    var expanded by remember { mutableStateOf(false) }
    var selectedProvider by remember { mutableStateOf(providers[0]) }

    var endpointUrl by remember { mutableStateOf("https://generativelanguage.googleapis.com/v1beta") }
    var modelName by remember { mutableStateOf("gemini-1.5-flash") }
    var apiKey by remember { mutableStateOf("") }

    val availableModels by viewModel.availableRemoteModels.collectAsStateWithLifecycle()
    val isFetchingModels by viewModel.isFetchingRemoteModels.collectAsStateWithLifecycle()
    val fetchError by viewModel.remoteFetchError.collectAsStateWithLifecycle()
    var modelDropdownExpanded by remember { mutableStateOf(false) }

    fun onProviderChange(p: String) {
        selectedProvider = p
        viewModel.clearRemoteModels()
        when (p) {
            "Google Gemini" -> {
                endpointUrl = "https://generativelanguage.googleapis.com/v1beta"
                modelName = "gemini-1.5-flash"
            }
            "OpenAI" -> {
                endpointUrl = "https://api.openai.com/v1"
                modelName = "gpt-4o-mini"
            }
            "Anthropic Claude" -> {
                endpointUrl = "https://api.anthropic.com/v1"
                modelName = "claude-3-5-haiku-20241022"
            }
            "Nvidia NIM" -> {
                endpointUrl = "https://integrate.api.nvidia.com/v1"
                modelName = "meta/llama-3.1-8b-instruct"
            }
            "DeepSeek" -> {
                endpointUrl = "https://api.deepseek.com/v1"
                modelName = "deepseek-chat"
            }
            "Custom" -> {
                endpointUrl = ""
                modelName = ""
            }
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            TextButton(
                onClick = onBack,
                modifier = Modifier.align(Alignment.Start)
            ) {
                Text("Back", color = BitColors.TextTertiary)
            }
            Text(
                text = "Configure API Connection",
                style = androidx.compose.ui.text.TextStyle(
                    color = BitColors.TextPrimary,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = (-0.5).sp
                )
            )
            Text(
                text = "Register details for your preferred API endpoints. Prefilled default params are set for convenience.",
                style = androidx.compose.ui.text.TextStyle(
                    color = BitColors.TextSecondary,
                    fontSize = 13.sp,
                    lineHeight = 18.sp
                )
            )

            Box(modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(
                    onClick = { expanded = true },
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = BitColors.TextPrimary),
                    border = BorderStroke(1.dp, BitColors.Border)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(selectedProvider, style = androidx.compose.ui.text.TextStyle(fontSize = 15.sp))
                        Text("▼", fontSize = 11.sp, color = BitColors.TextSecondary)
                    }
                }
                DropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false },
                    modifier = Modifier.fillMaxWidth(0.9f).background(BitColors.Surface)
                ) {
                    providers.forEach { provider ->
                        DropdownMenuItem(
                            text = { Text(provider, color = BitColors.TextPrimary) },
                            onClick = {
                                onProviderChange(provider)
                                expanded = false
                            }
                        )
                    }
                }
            }

            OutlinedTextField(
                value = endpointUrl,
                onValueChange = { endpointUrl = it },
                label = { Text("Base Endpoint URL") },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = BitColors.TextPrimary,
                    unfocusedBorderColor = BitColors.Border,
                    focusedLabelColor = BitColors.TextPrimary,
                    unfocusedLabelColor = BitColors.TextSecondary,
                    focusedTextColor = BitColors.TextPrimary,
                    unfocusedTextColor = BitColors.TextPrimary
                )
            )

            PasswordTextField(
                value = apiKey,
                onValueChange = { apiKey = it },
                label = "API Key / Access Token",
                modifier = Modifier.fillMaxWidth()
            )

            Button(
                onClick = {
                    viewModel.fetchAvailableRemoteModels(selectedProvider, endpointUrl, apiKey)
                },
                modifier = Modifier.fillMaxWidth().height(48.dp),
                shape = RoundedCornerShape(14.dp),
                enabled = !isFetchingModels && endpointUrl.isNotBlank() && apiKey.isNotBlank(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = BitColors.SurfaceAlt,
                    contentColor = BitColors.TextPrimary
                )
            ) {
                if (isFetchingModels) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), color = BitColors.TextPrimary)
                } else {
                    Text("Fetch Available Models", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                }
            }

            fetchError?.let { err ->
                Text(err, color = Color.Red, style = androidx.compose.ui.text.TextStyle(fontSize = 12.sp))
            }

            Box(modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = modelName,
                    onValueChange = { modelName = it },
                    label = { Text("API Model ID") },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    trailingIcon = {
                        if (availableModels.isNotEmpty()) {
                            IconButton(onClick = { modelDropdownExpanded = true }) {
                                Text("▼", fontSize = 11.sp, color = BitColors.TextSecondary)
                            }
                        }
                    },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = BitColors.TextPrimary,
                        unfocusedBorderColor = BitColors.Border,
                        focusedLabelColor = BitColors.TextPrimary,
                        unfocusedLabelColor = BitColors.TextSecondary,
                        focusedTextColor = BitColors.TextPrimary,
                        unfocusedTextColor = BitColors.TextPrimary
                    )
                )
                if (availableModels.isNotEmpty()) {
                    DropdownMenu(
                        expanded = modelDropdownExpanded,
                        onDismissRequest = { modelDropdownExpanded = false },
                        modifier = Modifier.fillMaxWidth(0.9f).background(BitColors.Surface)
                    ) {
                        availableModels.forEach { model ->
                            DropdownMenuItem(
                                text = { Text(model, color = BitColors.TextPrimary) },
                                onClick = {
                                    modelName = model
                                    modelDropdownExpanded = false
                                }
                            )
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        val isEnabled = endpointUrl.isNotBlank() && modelName.isNotBlank() && apiKey.isNotBlank()
        val buttonAlpha = if (isEnabled) 1.0f else 0.3f

        Button(
            onClick = {
                if (isEnabled) {
                    viewModel.configureRemoteApi(
                        provider = selectedProvider,
                        baseUrl = endpointUrl,
                        modelName = modelName,
                        apiKey = apiKey
                    )
                }
            },
            enabled = isEnabled,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
                .alpha(buttonAlpha),
            shape = RoundedCornerShape(28.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = BitColors.Inverse,
                contentColor = BitColors.OnInverse,
                disabledContainerColor = BitColors.Inverse,
                disabledContentColor = BitColors.OnInverse
            )
        ) {
            Text(
                text = "Connect & Complete Setup",
                style = androidx.compose.ui.text.TextStyle(
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold
                )
            )
        }
    }
}

@Composable
private fun DownloadProgressContent(
    viewModel: SetupViewModel,
    selectedOption: SetupOption?,
    downloadStates: Map<String, ModelDownloadService.DownloadState>,
    downloadError: String?,
    primaryModelId: String?
) {
    val haptics = com.bit.ui.theme.LocalBitHaptics.current
    val downloadState = primaryModelId?.let { downloadStates[it] }

    val progress = when (downloadState) {
        is ModelDownloadService.DownloadState.Downloading -> downloadState.progress
        is ModelDownloadService.DownloadState.Extracting -> -1f
        is ModelDownloadService.DownloadState.Processing -> -1f
        is ModelDownloadService.DownloadState.Success -> 1f
        else -> 0f
    }

    val speed = if (downloadState is ModelDownloadService.DownloadState.Downloading) {
        val speedBytes = downloadState.speedBytesPerSec
        val kb = speedBytes / 1024f
        val mb = kb / 1024f
        if (mb >= 1f) String.format("%.1f MB/s", mb) else String.format("%.1f KB/s", kb)
    } else null

    val eta = if (downloadState is ModelDownloadService.DownloadState.Downloading) {
        val etaSecs = downloadState.etaSeconds
        if (etaSecs < 0) "Calculating..."
        else {
            val mins = etaSecs / 60
            val secs = etaSecs % 60
            if (mins > 0) "${mins}m ${secs}s remaining" else "${secs}s remaining"
        }
    } else null

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(BitColors.Surface, RoundedCornerShape(20.dp))
                .border(1.dp, BitColors.Border, RoundedCornerShape(20.dp))
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            Text(
                text = "Downloading Weights",
                style = androidx.compose.ui.text.TextStyle(
                    color = BitColors.TextPrimary,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold
                )
            )

            Text(
                text = "BIT runs entirely on-device. You can close or minimize the app; we will notify you when it is ready.",
                style = androidx.compose.ui.text.TextStyle(
                    color = BitColors.TextSecondary,
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                    textAlign = TextAlign.Center
                )
            )

            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (progress >= 0f) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(6.dp)
                                .clip(RoundedCornerShape(3.dp))
                                .background(BitColors.Border)
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxHeight()
                                    .fillMaxWidth(progress.coerceIn(0f, 1f))
                                    .background(BitColors.TextPrimary)
                            )
                        }
                        
                        Text(
                            text = "${(progress * 100).toInt()}%",
                            style = androidx.compose.ui.text.TextStyle(
                                color = BitColors.TextPrimary,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        )
                    }
                } else {
                    LinearProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(RoundedCornerShape(3.dp)),
                        color = BitColors.TextPrimary,
                        trackColor = BitColors.Border
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    speed?.let {
                        Text(
                            text = it,
                            style = androidx.compose.ui.text.TextStyle(
                                color = BitColors.TextTertiary,
                                fontSize = 12.sp
                            )
                        )
                    }
                    eta?.let {
                        Text(
                            text = it,
                            style = androidx.compose.ui.text.TextStyle(
                                color = BitColors.TextTertiary,
                                fontSize = 12.sp
                            )
                        )
                    }
                }
            }

            TextButton(
                onClick = {
                    haptics.selection()
                    viewModel.cancelDownload()
                }
            ) {
                Text(
                    text = "Cancel",
                    style = androidx.compose.ui.text.TextStyle(
                        color = BitColors.TextTertiary,
                        fontWeight = FontWeight.SemiBold
                    )
                )
            }
        }

        downloadError?.let { error ->
            Spacer(modifier = Modifier.height(16.dp))
            Surface(
                color = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = error,
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center
                    )
                    Button(
                        onClick = { viewModel.retryDownload() },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error,
                            contentColor = MaterialTheme.colorScheme.onError
                        )
                    ) {
                        Text("Retry")
                    }
                }
            }
        }
    }
}

@Composable
private fun RestoreFromBackupSection(viewModel: SetupViewModel) {
    val context = LocalContext.current
    var showPasswordDialog by remember { mutableStateOf(false) }
    var backupUri by remember { mutableStateOf<android.net.Uri?>(null) }
    var password by remember { mutableStateOf("") }
    
    val restoreProgress by viewModel.restoreProgress.collectAsStateWithLifecycle()

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            backupUri = uri
            showPasswordDialog = true
        }
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        if (restoreProgress != null) {
            val progressVal = when (val progress = restoreProgress!!) {
                is SystemBackupManager.BackupProgress.Processing -> progress.progress
                is SystemBackupManager.BackupProgress.Complete -> 1f
                else -> -1f
            }
            val stageVal = when (val progress = restoreProgress!!) {
                is SystemBackupManager.BackupProgress.Starting -> "Starting"
                is SystemBackupManager.BackupProgress.Collecting -> "Collecting ${progress.component}"
                is SystemBackupManager.BackupProgress.Processing -> progress.stage
                is SystemBackupManager.BackupProgress.Complete -> "Complete"
                is SystemBackupManager.BackupProgress.Error -> "Error: ${progress.message}"
            }
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (progressVal >= 0f) {
                    LinearProgressIndicator(
                        progress = { progressVal },
                        modifier = Modifier.fillMaxWidth(),
                        color = BitColors.TextPrimary,
                        trackColor = BitColors.Border
                    )
                } else {
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth(),
                        color = BitColors.TextPrimary,
                        trackColor = BitColors.Border
                    )
                }
                Text(
                    text = "${stageVal}...",
                    color = BitColors.TextSecondary,
                    fontSize = 12.sp
                )
            }
        } else {
            Text(
                text = "Already have a backup?",
                color = BitColors.TextTertiary,
                fontSize = 13.sp
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Restore from backup file",
                color = BitColors.TextPrimary,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .clickable {
                        filePickerLauncher.launch(arrayOf("application/octet-stream", "*/*"))
                    }
                    .padding(4.dp)
            )
        }
    }

    if (showPasswordDialog && backupUri != null) {
        AlertDialog(
            onDismissRequest = { showPasswordDialog = false },
            title = { Text("Restore Backup", color = BitColors.TextPrimary) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        "Please enter the password that was used to encrypt this backup.",
                        color = BitColors.TextSecondary,
                        fontSize = 14.sp
                    )
                    PasswordTextField(
                        value = password,
                        onValueChange = { password = it },
                        label = "Password",
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showPasswordDialog = false
                        viewModel.restoreFromBackup(backupUri!!, password)
                    }
                ) {
                    Text("Restore", color = BitColors.TextPrimary, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showPasswordDialog = false }) {
                    Text("Cancel", color = BitColors.TextSecondary)
                }
            },
            containerColor = BitColors.Surface,
            shape = RoundedCornerShape(20.dp)
        )
    }
}

