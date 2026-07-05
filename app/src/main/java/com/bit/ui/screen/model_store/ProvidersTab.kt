package com.bit.ui.screen.model_store

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bit.api.ProviderConfig
import com.bit.api.StreamEvent
import com.bit.api.LlmProviderResolver
import com.bit.api.ChatMessage
import com.bit.api.Participant
import com.bit.api.MessageStatus
import com.bit.models.enums.ProviderType
import com.bit.models.table_schema.Model
import com.bit.models.table_schema.ModelConfig
import com.bit.viewmodel.ModelStoreViewModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.Locale
import com.bit.ui.icons.TnIcons

@Composable
internal fun ProvidersTab(
    viewModel: ModelStoreViewModel,
    llmModelViewModel: com.bit.viewmodel.LLMModelViewModel
) {
    var selectedProvider by remember { mutableStateOf<String?>(null) }

    AnimatedContent(
        targetState = selectedProvider,
        transitionSpec = {
            if (targetState != null) {
                slideInHorizontally { it } + fadeIn() togetherWith slideOutHorizontally { -it } + fadeOut()
            } else {
                slideInHorizontally { -it } + fadeIn() togetherWith slideOutHorizontally { it } + fadeOut()
            }
        },
        label = "provider_navigation"
    ) { provider ->
        if (provider != null) {
            ProviderDetailView(
                providerName = provider,
                viewModel = viewModel,
                llmModelViewModel = llmModelViewModel,
                onBack = { selectedProvider = null }
            )
        } else {
            ProviderListView(
                viewModel = viewModel,
                onSelectProvider = { selectedProvider = it }
            )
        }
    }
}

@Composable
private fun ProviderListView(
    viewModel: ModelStoreViewModel,
    onSelectProvider: (String) -> Unit
) {
    val installedModels by viewModel.installedModels.collectAsState()
    val providers = listOf(
        "Google Gemini",
        "OpenAI",
        "Anthropic Claude",
        "DeepSeek",
        "Ollama",
        "OpenRouter",
        "Custom API"
    )

    fun isConfigured(provider: String): Boolean {
        val cleanName = provider.lowercase(Locale.US).replace(" ", "-")
        return installedModels.any { it.providerType == ProviderType.API && it.id.startsWith("api-$cleanName") }
    }

    fun getActiveModelName(provider: String): String? {
        val cleanName = provider.lowercase(Locale.US).replace(" ", "-")
        val model = installedModels.firstOrNull { it.providerType == ProviderType.API && it.id.startsWith("api-$cleanName") && it.isActive }
        return model?.modelName
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = "Cloud & Remote Providers",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.Bold
        )

        providers.forEach { provider ->
            val configured = isConfigured(provider)
            val activeModel = getActiveModelName(provider)

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onSelectProvider(provider) },
                colors = CardDefaults.cardColors(
                    containerColor = if (activeModel != null) {
                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                    }
                ),
                border = if (activeModel != null) {
                    androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.primary)
                } else null
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Icon(
                            imageVector = when (provider) {
                                "Google Gemini" -> TnIcons.Sparkles
                                "Ollama" -> TnIcons.Terminal
                                else -> TnIcons.Upload
                            },
                            contentDescription = null,
                            tint = if (configured) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                        )

                        Column {
                            Text(
                                text = provider,
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Bold
                            )
                            if (activeModel != null) {
                                Text(
                                    text = "Active: $activeModel",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            } else if (configured) {
                                Text(
                                    text = "Configured (Inactive)",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            } else {
                                Text(
                                    text = "Not Configured",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                                )
                            }
                        }
                    }

                    Icon(
                        imageVector = TnIcons.ChevronRight,
                        contentDescription = "Configure",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun ProviderDetailView(
    providerName: String,
    viewModel: ModelStoreViewModel,
    llmModelViewModel: com.bit.viewmodel.LLMModelViewModel,
    onBack: () -> Unit
) {
    val coroutineScope = rememberCoroutineScope()
    val installedModels by viewModel.installedModels.collectAsState()
    val cleanName = providerName.lowercase(Locale.US).replace(" ", "-")
    val modelId = "api-$cleanName"

    val existingModel = installedModels.firstOrNull { it.id == modelId }

    var isEnabled by remember { mutableStateOf(existingModel != null) }
    var isActiveModel by remember { mutableStateOf(existingModel?.isActive ?: false) }

    var endpointUrl by remember {
        mutableStateOf(
            when (providerName) {
                "Google Gemini" -> "https://generativelanguage.googleapis.com/v1beta"
                "OpenAI" -> "https://api.openai.com/v1"
                "Anthropic Claude" -> "https://api.anthropic.com/v1"
                "Nvidia NIM" -> "https://integrate.api.nvidia.com/v1"
                "DeepSeek" -> "https://api.deepseek.com/v1"
                "OpenRouter" -> "https://openrouter.ai/api/v1"
                "Ollama" -> "http://10.0.2.2:11434"
                else -> ""
            }
        )
    }

    var modelName by remember {
        mutableStateOf(
            when (providerName) {
                "Google Gemini" -> "gemini-1.5-flash"
                "OpenAI" -> "gpt-4o-mini"
                "Anthropic Claude" -> "claude-3-5-haiku-20241022"
                "DeepSeek" -> "deepseek-chat"
                "OpenRouter" -> "meta-llama/llama-3-8b-instruct:free"
                "Ollama" -> "llama3"
                else -> ""
            }
        )
    }

    var apiKey by remember { mutableStateOf("") }
    var testStatus by remember { mutableStateOf<String?>(null) }
    var isTesting by remember { mutableStateOf(false) }

    LaunchedEffect(existingModel) {
        existingModel?.let { model ->
            val config = viewModel.getModelConfig(model.id)
            config?.let { cfg ->
                val json = JSONObject(cfg.modelLoadingParams ?: "{}")
                endpointUrl = json.optString("endpoint", endpointUrl)
                modelName = json.optString("model", modelName)
                apiKey = json.optString("authHeader", "")
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            IconButton(onClick = onBack) {
                Icon(TnIcons.ArrowLeft, contentDescription = "Back")
            }
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "$providerName Settings",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
        }

        OutlinedTextField(
            value = endpointUrl,
            onValueChange = { endpointUrl = it },
            label = { Text("Base Endpoint URL") },
            modifier = Modifier.fillMaxWidth()
        )

        OutlinedTextField(
            value = modelName,
            onValueChange = { modelName = it },
            label = { Text("Model ID") },
            modifier = Modifier.fillMaxWidth()
        )

        OutlinedTextField(
            value = apiKey,
            onValueChange = { apiKey = it },
            label = { Text("API Key / Access Token") },
            modifier = Modifier.fillMaxWidth()
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("Enable Provider")
            Switch(
                checked = isEnabled,
                onCheckedChange = { isEnabled = it }
            )
        }

        if (isEnabled) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("Set as Active Model")
                Switch(
                    checked = isActiveModel,
                    onCheckedChange = { isActiveModel = it }
                )
            }
        }

        Button(
            onClick = {
                isTesting = true
                testStatus = "Connecting..."
                coroutineScope.launch {
                    try {
                        val provider = LlmProviderResolver.resolveProvider(endpointUrl, modelName)
                        val config = ProviderConfig(
                            apiKey = apiKey,
                            modelId = modelName,
                            maxContextWindow = 1,
                            thinkingEnabled = false,
                            baseUrl = LlmProviderResolver.cleanBaseUrl(endpointUrl)
                        )
                        val testPrompt = listOf(
                            ChatMessage(
                                text = "Hello",
                                participant = Participant.USER,
                                status = MessageStatus.SUCCESS
                            )
                        )
                        var receivedChunk = false
                        provider.generateResponse(testPrompt, config).collect { event ->
                            if (event is StreamEvent.TextChunk) {
                                receivedChunk = true
                            } else if (event is StreamEvent.Error) {
                                throw Exception(event.message)
                            }
                        }
                        testStatus = if (receivedChunk) "Success! Connection OK." else "No response received."
                    } catch (e: Exception) {
                        testStatus = "Failed: ${e.localizedMessage ?: e.message}"
                    } finally {
                        isTesting = false
                    }
                }
            },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary),
            enabled = !isTesting
        ) {
            if (isTesting) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), color = MaterialTheme.colorScheme.onSecondary)
            } else {
                Text("Test Connection")
            }
        }

        testStatus?.let { status ->
            Text(
                text = status,
                color = if (status.startsWith("Success")) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall
            )
        }

        Button(
            onClick = {
                coroutineScope.launch {
                    val db = com.bit.di.AppContainer.getModelRepository()
                    if (isEnabled) {
                        val model = Model(
                            id = modelId,
                            modelName = "$modelName ($providerName)",
                            modelPath = endpointUrl.trim(),
                            pathType = com.bit.models.enums.PathType.FILE,
                            providerType = ProviderType.API,
                            fileSize = null,
                            isActive = isActiveModel
                        )
                        db.insertModel(model)

                        val loadingJson = JSONObject().apply {
                            put("endpoint", endpointUrl.trim())
                            put("model", modelName.trim())
                            put("stream", true)
                            put("authHeader", apiKey.trim())
                        }.toString()

                        val config = ModelConfig(
                            modelId = modelId,
                            modelLoadingParams = loadingJson,
                            modelInferenceParams = "{}"
                        )
                        db.insertConfig(config)

                        if (isActiveModel) {
                            llmModelViewModel.loadModel(model)
                        }
                    } else {
                        db.getModelById(modelId)?.let { db.deleteModel(it) }
                        db.getConfigByModelId(modelId)?.let { db.deleteConfig(it) }
                    }

                    // Reload models in Store VM list
                    viewModel.loadModels()
                    onBack()
                }
            },
            modifier = Modifier.fillMaxWidth(),
            enabled = !isTesting
        ) {
            Text("Save Settings")
        }
    }
}
