package com.bit.viewmodel

import java.io.File
import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import android.widget.Toast
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bit.data.AppSettingsDataStore
import com.bit.di.AppContainer
import com.bit.engine.GenerationEvent
import com.bit.api.util.StreamingThinkTagParser
import com.bit.models.engine_schema.GgufEngineSchema
import com.bit.models.engine_schema.GgufInferenceParams
import com.bit.models.messages.ContentType
import com.bit.models.messages.ImageGenerationMetrics
import com.bit.models.messages.MessageContent
import com.bit.models.messages.Messages
import com.bit.models.messages.RagResultItem
import com.bit.models.messages.Role
import com.bit.models.messages.ToolChainStepData
import com.bit.models.enums.ProviderType
import com.bit.models.plugins.PluginExecutionMetrics
import com.bit.models.plugins.PluginResultData
import com.bit.network.RemoteInferenceClient
import com.bit.plugins.PluginManager
import com.bit.state.AppStateManager
import com.bit.worker.ChatManager
import com.bit.worker.ActiveModelSession
import com.bit.worker.DiffusionConfig
import com.bit.worker.DiffusionInferenceParams
import com.bit.models.ModelType
import com.bit.tts.TTSManager
import com.bit.tts.TTSSettings
import com.bit.worker.LlmModelWorker
import com.bit.models.engine_schema.DecodingMetrics
import com.dark.gguf_lib.toolcalling.ToolCall
import com.dark.gguf_lib.toolcalling.ToolCallingConfig
import com.bit.service.AudioCaptureService
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import com.bit.api.ProviderConfig
import com.bit.api.StreamEvent
import com.bit.api.LlmProviderResolver
import com.bit.api.ChatMessage
import com.bit.api.Participant
import com.bit.util.SearchResultFormatter

enum class AgentPhase { Idle, Planning, Executing, Summarizing, Complete }

data class PromptEditState(
    val messageId: String,
    val initialText: String
)

@HiltViewModel
class ChatViewModel @Inject constructor(
    @ApplicationContext context: Context,
    private val chatManager: ChatManager,
    private val globalRagOrchestrator: com.bit.worker.GlobalRagOrchestrator,
    private val aiMemoryWriter: com.bit.data.AiMemoryWriter
) : ViewModel() {

    private val appContext = context
    private val appSettings = AppSettingsDataStore(context)
    private val ttsDataStore = com.bit.tts.TTSDataStore(context)
    private val skillManager = com.bit.skills.SkillManager(context)
    // ControlVectorManager removed — will be re-added when new lib supports it

    // ── TTFT optimization: cache vault memory notes to avoid re-walking filesystem every message ──
    @Volatile private var cachedVaultNotes: List<Pair<String, String>>? = null
    @Volatile private var vaultCacheTimestamp = 0L
    private val VAULT_CACHE_TTL_MS = 30_000L // 30 seconds

    private fun getCachedVaultNotes(): List<Pair<String, String>> {
        val now = System.currentTimeMillis()
        val cached = cachedVaultNotes
        if (cached != null && now - vaultCacheTimestamp < VAULT_CACHE_TTL_MS) return cached

        val vaultRoot = com.bit.global.AppPaths.vaultRoot(appContext)
        val notes = try {
            vaultRoot.walkTopDown()
                .filter { it.isFile && it.extension.lowercase() == "md" }
                .mapNotNull { file ->
                    try {
                        val raw = file.readText()
                        if (raw.contains("is_ai_memory_enabled: false")) return@mapNotNull null
                        val bodyStart = raw.indexOf("---", 3)
                        if (bodyStart < 0) return@mapNotNull null
                        val body = raw.substring(bodyStart + 3).trimStart('\n')
                        val titleLine = raw.lines().find { it.trim().startsWith("title:") }
                        val title = titleLine?.substringAfter("title:")?.trim() ?: file.nameWithoutExtension
                        title to body
                    } catch (_: Exception) { null }
                }
                .toList()
        } catch (_: Exception) { emptyList() }

        cachedVaultNotes = notes
        vaultCacheTimestamp = now
        return notes
    }

    val streamingEnabled: StateFlow<Boolean> = appSettings.streamingEnabled
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    val chatMemoryEnabled: StateFlow<Boolean> = appSettings.chatMemoryEnabled
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    private val hfTokenManager = com.bit.data.HuggingFaceTokenManager(context)

    val huggingFaceToken: StateFlow<String?> = appSettings.huggingFaceToken
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    fun saveHuggingFaceToken(token: String?) {
        viewModelScope.launch {
            if (token != null) {
                hfTokenManager.saveToken(token)
            } else {
                hfTokenManager.clearToken()
            }
            appSettings.saveHuggingFaceToken(token)
        }
    }

    private val _messages = mutableStateListOf<Messages>()
    val messages: SnapshotStateList<Messages> = _messages

    private val _isGenerating = MutableStateFlow(false)
    val isGenerating: StateFlow<Boolean> = _isGenerating

    val isLiveVoiceModeActive = mutableStateOf(false)

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    // Speech to Text (STT) state and controllers
    private val audioCaptureService = AudioCaptureService(context)
    val isSttRecording: StateFlow<Boolean> = audioCaptureService.isRecordingState
    val sttAmplitude: StateFlow<Float> = audioCaptureService.audioLevel
    private val _isSttTranscribing = MutableStateFlow(false)
    val isSttTranscribing: StateFlow<Boolean> = _isSttTranscribing

    private var sttAudioData = java.io.ByteArrayOutputStream()
    private var sttCollectJob: Job? = null

    fun startSttRecording() {
        if (!audioCaptureService.hasRecordPermission()) {
            reportError("Microphone permission required")
            return
        }
        
        if (!com.bit.stt.SherpaSTTEngine.hasModelFiles(appContext)) {
            reportError("STT model not downloaded. Download CSukuangfj/sherpa-onnx-whisper-tiny.en in Settings.")
            return
        }
        
        sttAudioData.reset()
        sttCollectJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                audioCaptureService.startCapture().collect { chunk ->
                    sttAudioData.write(chunk)
                }
            } catch (e: Exception) {
                Log.e("ChatViewModel", "STT record collection error: ${e.message}")
            }
        }
    }

    fun cancelSttRecording() {
        sttCollectJob?.cancel()
        sttCollectJob = null
        audioCaptureService.stopCapture()
        sttAudioData.reset()
        _isSttTranscribing.value = false
    }

    fun stopSttRecording(onTranscribed: (String) -> Unit) {
        sttCollectJob?.cancel()
        sttCollectJob = null
        audioCaptureService.stopCapture()
        
        val audioBytes = sttAudioData.toByteArray()
        sttAudioData.reset()
        
        if (audioBytes.isEmpty()) return
        
        viewModelScope.launch {
            _isSttTranscribing.value = true
            _isGenerating.value = true
            try {
                if (!com.bit.stt.SherpaSTTEngine.hasModelFiles(appContext)) {
                    reportError("STT model not downloaded. Download CSukuangfj/sherpa-onnx-whisper-tiny.en in Settings.")
                    return@launch
                }
                val transcribedText = com.bit.stt.SherpaSTTEngine.transcribe(appContext, audioBytes)
                if (transcribedText.isNotBlank()) {
                    onTranscribed(transcribedText)
                } else {
                    reportError("No speech recognized")
                }
            } catch (e: Exception) {
                reportError(e.message ?: "Transcription failed")
            } finally {
                _isGenerating.value = false
                _isSttTranscribing.value = false
            }
        }
    }

    private val _currentChatId = MutableStateFlow<String?>(null)
    val currentChatId: StateFlow<String?> = _currentChatId

    private val isNewConversation: Boolean get() = _currentChatId.value == null

    // Streaming state
    private val _streamingUserMessage = MutableStateFlow<String?>(null)
    val streamingUserMessage: StateFlow<String?> = _streamingUserMessage

    private val _streamingAssistantMessage = MutableStateFlow("")
    val streamingAssistantMessage: StateFlow<String> = _streamingAssistantMessage

    // Image generation state
    private val _streamingImage = MutableStateFlow<Bitmap?>(null)
    val streamingImage: StateFlow<Bitmap?> = _streamingImage

    private val _imageGenerationProgress = MutableStateFlow(0f)
    val imageGenerationProgress: StateFlow<Float> = _imageGenerationProgress

    private val _imageGenerationStep = MutableStateFlow("")
    val imageGenerationStep: StateFlow<String> = _imageGenerationStep

    // Tool chain steps (used by both streaming UI and persistence)
    private val _toolChainSteps = MutableStateFlow<List<ToolChainStepData>>(emptyList())
    val toolChainSteps: StateFlow<List<ToolChainStepData>> = _toolChainSteps

    private val _currentToolChainRound = MutableStateFlow(0)
    val currentToolChainRound: StateFlow<Int> = _currentToolChainRound

    // Agent phase state (Plan → Execute → Summarize)
    private val _agentPhase = MutableStateFlow(AgentPhase.Idle)
    val agentPhase: StateFlow<AgentPhase> = _agentPhase.asStateFlow()

    private val _agentPlan = MutableStateFlow<String?>(null)
    val agentPlan: StateFlow<String?> = _agentPlan.asStateFlow()

    private val _agentSummary = MutableStateFlow<String?>(null)
    val agentSummary: StateFlow<String?> = _agentSummary.asStateFlow()

    // Track generation job for proper cancellation
    private var generationJob: Job? = null

    // Track current generation state
    private var currentUserMessage: Messages? = null
    private val _currentMetrics = MutableStateFlow<DecodingMetrics?>(null)
    val currentDecodingMetrics: StateFlow<DecodingMetrics?> = _currentMetrics.asStateFlow()
    private var currentMetrics: DecodingMetrics?
        get() = _currentMetrics.value
        set(value) { _currentMetrics.value = value }
    private var currentImageMetrics: ImageGenerationMetrics? = null
    private var currentGeneratedImage: Bitmap? = null
    private var imageGenerationStartTime: Long = 0

    // Track if user message was already added to prevent duplicates
    private val userMessageAdded = java.util.concurrent.atomic.AtomicBoolean(false)

    // Current model ID for per-message attribution
    private val currentModelId: String?
        get() = ActiveModelSession.currentModelId.value.ifBlank { LlmModelWorker.currentGgufModelId.value ?: "" }
            .takeIf { it.isNotBlank() }

    /** True when a text generation model is loaded. */
    private val isAnyTextModelLoaded: Boolean
        get() = LlmModelWorker.isGgufModelLoaded.value || ActiveModelSession.currentModelType.value == ProviderType.API

    // UI state
    private val _showDynamicWindow = MutableStateFlow(false)
    val showDynamicWindow: StateFlow<Boolean> = _showDynamicWindow

    private val _showModelList = MutableStateFlow(false)
    val showModelList: StateFlow<Boolean> = _showModelList

    private val _currentGenerationType = MutableStateFlow(ModelType.TEXT_GENERATION)
    val currentGenerationType: StateFlow<ModelType> = _currentGenerationType

    private val _promptEditState = MutableStateFlow<PromptEditState?>(null)
    val promptEditState: StateFlow<PromptEditState?> = _promptEditState.asStateFlow()

    // Thinking mode toggle — when enabled, adds /think to system prompt for supported models
    private val _thinkingModeEnabled = MutableStateFlow(false)
    val thinkingModeEnabled: StateFlow<Boolean> = _thinkingModeEnabled.asStateFlow()
    private val _modelSupportsThinking = MutableStateFlow(false)
    val modelSupportsThinking: StateFlow<Boolean> = _modelSupportsThinking.asStateFlow()

    fun toggleThinkingMode() {
        _thinkingModeEnabled.value = !_thinkingModeEnabled.value
    }

    fun setThinkingMode(enabled: Boolean) {
        _thinkingModeEnabled.value = enabled
    }

    // Model state
    val isTextModelLoaded: StateFlow<Boolean> = combine(
        LlmModelWorker.isGgufModelLoaded,
        ActiveModelSession.currentModelType
    ) { ggufLoaded, providerType ->
        ggufLoaded || providerType == ProviderType.API
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)
    val isImageModelLoaded = LlmModelWorker.isDiffusionModelLoaded
    val isVlmLoaded: StateFlow<Boolean> = combine(
        LlmModelWorker.isVlmLoaded,
        ActiveModelSession.currentModelType
    ) { localVlm, providerType ->
        localVlm || providerType == ProviderType.VLM || providerType == ProviderType.API
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    // TTS state
    val ttsPlayingMsgId = TTSManager.currentPlayingMsgId
    val ttsIsPlaying = TTSManager.isPlaying
    val ttsSynthesizing = TTSManager.isSynthesizing
    val ttsModelLoaded = TTSManager.isModelLoaded
    val ttsAvailableVoices = TTSManager.availableVoices

    // RAG state
    private val _currentRagContext = MutableStateFlow<String?>(null)
    val currentRagContext: StateFlow<String?> = _currentRagContext

    private val _currentRagResults = MutableStateFlow<List<RagQueryDisplayResult>>(emptyList())
    val currentRagResults: StateFlow<List<RagQueryDisplayResult>> = _currentRagResults

    // ── Context Usage ──

    private val _contextUsagePercent = MutableStateFlow(0f)
    val contextUsagePercent: StateFlow<Float> = _contextUsagePercent.asStateFlow()

    // ── Attached Chat Document RAG ──
    private val _attachedFileName = MutableStateFlow<String?>(null)
    val attachedFileName: StateFlow<String?> = _attachedFileName.asStateFlow()
    val isRagProcessing: StateFlow<Boolean> = globalRagOrchestrator.isProcessing

    fun attachDocument(uri: android.net.Uri) {
        viewModelScope.launch {
            globalRagOrchestrator.attachDocument(uri).onSuccess {
                _attachedFileName.value = "Document Added to Vault"
            }.onFailure { e ->
                reportError("Failed to attach document: ${e.message}")
            }
        }
    }

    fun clearAttachedDocument() {
        _attachedFileName.value = null
    }

    // ── Grouped State Flows (for optimized recomposition) ──

    val streamingState: StateFlow<StreamingState> = combine(
        _streamingUserMessage,
        _streamingAssistantMessage,
        _streamingImage,
        _imageGenerationProgress,
        _imageGenerationStep
    ) { userMsg, assistantMsg, image, progress, step ->
        StreamingState(userMsg, assistantMsg, image, progress, step)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), StreamingState())

    val chatUiState: StateFlow<ChatUiState> = combine(
        combine(_isGenerating, _currentChatId, _error) { gen, chatId, err -> Triple(gen, chatId, err) },
        combine(_currentGenerationType, _thinkingModeEnabled, _modelSupportsThinking) { type, think, supports -> Triple(type, think, supports) }
    ) { (gen, chatId, err), (type, think, supports) ->
        ChatUiState(
            isGenerating = gen,
            currentChatId = chatId,
            error = err,
            generationType = type,
            thinkingEnabled = think,
            modelSupportsThinking = supports
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ChatUiState())

    val agentState: StateFlow<AgentState> = combine(
        _agentPhase,
        _agentPlan,
        _agentSummary,
        _toolChainSteps,
        _currentToolChainRound
    ) { phase, plan, summary, steps, round ->
        AgentState(phase, plan, summary, steps, round)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AgentState())

    val ragState: StateFlow<RagState> = combine(
        _currentRagContext,
        _currentRagResults
    ) { context, results ->
        RagState(context, results)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), RagState())

    val chatConfigState: StateFlow<ChatConfigState> = combine(
        streamingEnabled,
        chatMemoryEnabled,
        _showDynamicWindow,
        _showModelList
    ) { streaming, memory, dynWindow, modelList ->
        ChatConfigState(streaming, memory, dynWindow, modelList)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ChatConfigState())

    // ==================== Auto-restore last chat ====================

    init {
        // Bidirectional synchronization of HuggingFace token on startup
        viewModelScope.launch {
            try {
                val token = appSettings.huggingFaceToken.first()
                val managerToken = hfTokenManager.getToken()
                if (token != managerToken) {
                    if (managerToken != null) {
                        appSettings.saveHuggingFaceToken(managerToken)
                    } else if (token != null) {
                        hfTokenManager.saveToken(token)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Startup HuggingFace token sync failed: ${e.message}")
            }
        }

        viewModelScope.launch {
            try {
                val lastChatId = appSettings.lastChatId.first()
                if (lastChatId != null) {
                    chatManager.getChatMessages(lastChatId).onSuccess { loadedMessages ->
                        if (loadedMessages.isNotEmpty()) {
                            _currentChatId.value = lastChatId
                            _messages.clear()
                            _messages.addAll(loadedMessages)
                            AppStateManager.setHasMessages(true)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.d(TAG, "Could not restore last chat: ${e.message}")
            }
        }

        // Persist chat ID whenever it changes
        viewModelScope.launch {
            try {
                _currentChatId.collect { chatId ->
                    appSettings.saveLastChatId(chatId)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Chat ID persistence failed: ${e.message}")
            }
        }

        // Check thinking support whenever text model loads/unloads or type changes
        viewModelScope.launch {
            kotlinx.coroutines.flow.combine(
                LlmModelWorker.isGgufModelLoaded,
                ActiveModelSession.currentModelType
            ) { isGgufLoaded, modelType ->
                Pair(isGgufLoaded, modelType)
            }.collect { (isGgufLoaded, modelType) ->
                if (modelType == ProviderType.API) {
                    _modelSupportsThinking.value = true
                } else if (isGgufLoaded) {
                    val supports = LlmModelWorker.supportsThinkingGguf()
                    _modelSupportsThinking.value = supports
                    // Do not auto-disable thinking mode; allow user to force enable it
                } else {
                    _modelSupportsThinking.value = false
                    _thinkingModeEnabled.value = false
                }
            }
        }
    }

    // ==================== RAG Controls ====================

    fun setRagContext(context: String?, results: List<RagQueryDisplayResult> = emptyList()) {
        _currentRagContext.value = context
        _currentRagResults.value = results
    }

    fun clearRagContext() {
        _currentRagContext.value = null
        _currentRagResults.value = emptyList()
    }

    /** Fetch the current GGUF model's inference config from DB (cached per call). */
    private suspend fun getGgufModelSchema(): GgufEngineSchema {
        val modelId = LlmModelWorker.currentGgufModelId.value ?: return GgufEngineSchema()
        val config = AppContainer.getModelRepository().getConfigByModelId(modelId) ?: return GgufEngineSchema()
        return GgufEngineSchema.fromJson(config.modelLoadingParams, config.modelInferenceParams)
    }

    private suspend fun getModelInferenceParams(): GgufInferenceParams =
        getGgufModelSchema().inferenceParams

    // ==================== Chat Management ====================

    fun startNewConversation() {
        // Cancel any in-flight generation before switching
        generationJob?.cancel()
        generationJob = null

        _currentChatId.value = null
        _messages.clear()
        _streamingUserMessage.value = null
        _streamingAssistantMessage.value = ""
        _streamingImage.value = null
        _imageGenerationProgress.value = 0f
        _imageGenerationStep.value = ""
        _isGenerating.value = false
        currentUserMessage = null
        currentGeneratedImage = null
        currentMetrics = null
        currentImageMetrics = null
        userMessageAdded.set(false)
        _error.value = null
        _toolChainSteps.value = emptyList()
        _currentToolChainRound.value = 0
        _agentPhase.value = AgentPhase.Idle
        _agentPlan.value = null
        _agentSummary.value = null
        _currentRagContext.value = null
        _currentRagResults.value = emptyList()
        _promptEditState.value = null
        AppStateManager.setHasMessages(false)
    }

    fun loadChat(chatId: String) {
        viewModelScope.launch {
            try {
                _currentChatId.value = chatId
                _promptEditState.value = null
                chatManager.getChatMessages(chatId).onSuccess { loadedMessages ->
                    _messages.clear()
                    _messages.addAll(loadedMessages)
                    AppStateManager.setHasMessages(loadedMessages.isNotEmpty())
                }.onFailure { e ->
                    reportError("Failed to load chat: ${e.message}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load chat: ${e.message}")
                reportError("Failed to load chat: ${e.message}")
            }
        }
    }

    // ==================== Model Selection ====================

    fun switchToTextGeneration() {
        if (!isAnyTextModelLoaded) {
            reportError("Text generation model not loaded")
            return
        }
        _currentGenerationType.value = ModelType.TEXT_GENERATION
    }

    fun switchToImageGeneration() {
        if (!LlmModelWorker.isDiffusionModelLoaded.value) {
            reportError("Image generation model not loaded")
            return
        }
        _currentGenerationType.value = ModelType.IMAGE_GENERATION
    }

    // ==================== Unified Text Generation Entry Point ====================

    fun sendChat(prompt: String) {
        if (!isAnyTextModelLoaded) {
            val hint = if (LlmModelWorker.isDiffusionModelLoaded.value)
                "You have an image model loaded — switch to image mode, or load a text model for chat"
            else
                "Please load a text generation model first"
            reportError(hint)
            return
        }
        if (_isGenerating.value) {
            stop()
        }

        _isGenerating.value = true
        _streamingUserMessage.value = prompt
        _streamingAssistantMessage.value = ""
        userMessageAdded.set(false)
        currentMetrics = null
        _error.value = null

        currentUserMessage = Messages(
            msgId = "",
            role = Role.User,
            content = MessageContent(contentType = ContentType.Text, content = prompt),
            modelId = currentModelId,
        )
        if (!userMessageAdded.get()) {
            _messages.add(currentUserMessage!!)
            userMessageAdded.set(true)
        }
        AppStateManager.setHasMessages(true)

        var job: Job? = null
        job = viewModelScope.launch {
            try {
                // Let Compose render the StreamingView before native engine saturates CPU
                kotlinx.coroutines.yield()

                val isNewChat = isNewConversation
                val activeProviderType = ActiveModelSession.currentModelType.value
                val hasTools = PluginManager.hasEnabledTools()
                        && (PluginManager.isToolCallingModelLoaded.value || activeProviderType == ProviderType.API)
                LlmModelWorker.setThinkingEnabledGguf(_thinkingModeEnabled.value && !hasTools)

                // ── TTFT optimization: fire API connection pre-warm immediately ──
                // While we're building the request, TCP+TLS handshake happens in parallel
                // Pre-warm the actual endpoint URL so the HTTP/2 connection is ready
                if (activeProviderType == ProviderType.API) {
                    try {
                        val remoteCfg = getRemoteInferenceConfig()
                        if (remoteCfg != null) {
                            val baseUrl = LlmProviderResolver.cleanBaseUrl(remoteCfg.endpoint).trimEnd('/')
                            // Pre-warm the actual chat/completions endpoint, not just the base URL
                            val endpointUrl = if (baseUrl.contains("/v1") || baseUrl.endsWith("/chat/completions")) {
                                "$baseUrl/chat/completions"
                            } else {
                                "$baseUrl/v1/chat/completions"
                            }
                            com.bit.network.HttpClient.preWarmConnection(endpointUrl)
                        }
                    } catch (_: Exception) { /* best-effort */ }
                }

                // ── TTFT optimization: parallelize pre-generation IO work ──
                // RAG query, maxTokens read, and chat creation all run concurrently
                val maxTokensDeferred = async(Dispatchers.IO) { getCurrentModelMaxTokens() }
                val ragDeferred = async(Dispatchers.IO) {
                    globalRagOrchestrator.queryGlobalKnowledge(prompt, topK = 5)
                }

                // Create chat in DB while other async work is running
                val chatId = if (isNewChat) {
                    var createdId: String? = null
                    chatManager.createNewChat().onSuccess { id ->
                        createdId = id
                        _currentChatId.value = id
                        // Trigger title generation in background immediately!
                        generateChatTitleAsync(id, prompt, "")
                    }.onFailure { e ->
                        reportError("Failed to create chat: ${e.message}")
                    }
                    createdId ?: return@launch
                } else {
                    _currentChatId.value ?: run {
                        reportError("No chat selected")
                        return@launch
                    }
                }

                chatManager.addUserMessage(chatId, prompt).onSuccess { userMsg ->
                    currentUserMessage = userMsg
                    // Update the optimistic message with the real DB message (having the correct msgId)
                    val idx = _messages.indexOfLast { it.role == Role.User && it.msgId == "" && it.content.content == prompt }
                    if (idx != -1) {
                        _messages[idx] = userMsg
                    }
                }.onFailure { e ->
                    reportError("Failed to save message: ${e.message}")
                    return@launch
                }

                // ── Await parallelized results ──
                val maxTokens = maxTokensDeferred.await()
                val attachedResult = ragDeferred.await()

                var ragContext = _currentRagContext.value
                if (attachedResult != null && attachedResult.results.isNotEmpty() && attachedResult.confidence != com.bit.neuron_example.RetrievalConfidence.LOW) {
                    Log.d(TAG, "Global RAG returned ${attachedResult.results.size} chunks with confidence ${attachedResult.confidence}")
                    val attachedContextStr = attachedResult.results.joinToString("\n\n") {
                        "<chunk>\n${it.node.content}\n</chunk>"
                    }
                    val instruction = "\n[SYSTEM INSTRUCTION: You are provided with retrieved document chunks above. Use them to answer the user's query if relevant.]"
                    ragContext = if (ragContext != null) ragContext + "\n\n" + attachedContextStr + instruction else attachedContextStr + instruction
                }

                executeUnifiedGeneration(prompt, ragContext, maxTokens, isNewChat)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Error in sendChat", e)
                reportError(e.message)
            } finally {
                if (generationJob == job) {
                    resetStreamingState()
                } else {
                    _isGenerating.value = false
                }
            }
        }
        generationJob = job
    }

    // Keep old name as alias for backward compatibility with callers
    fun sendTextMessage(prompt: String) = sendChat(prompt)

    private fun resizeImage(imageData: ByteArray, maxDimension: Int = 1024): ByteArray {
        val options = android.graphics.BitmapFactory.Options()
        options.inJustDecodeBounds = true
        android.graphics.BitmapFactory.decodeByteArray(imageData, 0, imageData.size, options)
        var scale = 1
        while (options.outWidth / scale / 2 >= maxDimension &&
               options.outHeight / scale / 2 >= maxDimension) {
            scale *= 2
        }
        val outOptions = android.graphics.BitmapFactory.Options()
        outOptions.inSampleSize = scale
        val bitmap = android.graphics.BitmapFactory.decodeByteArray(imageData, 0, imageData.size, outOptions) ?: return imageData
        val resized = if (bitmap.width > maxDimension || bitmap.height > maxDimension) {
            val ratio = Math.min(maxDimension.toFloat() / bitmap.width, maxDimension.toFloat() / bitmap.height)
            android.graphics.Bitmap.createScaledBitmap(bitmap, (bitmap.width * ratio).toInt(), (bitmap.height * ratio).toInt(), true)
        } else {
            bitmap
        }
        val outputStream = java.io.ByteArrayOutputStream()
        resized.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, outputStream)
        if (resized != bitmap) {
            resized.recycle()
        }
        bitmap.recycle()
        return outputStream.toByteArray()
    }

    /**
     * Send a message with images (VLM). Requires a VLM projector to be loaded.
     * @param prompt User's text prompt
     * @param imageData List of raw image file bytes (JPEG/PNG)
     */
    fun sendChatWithImages(prompt: String, imageData: List<ByteArray>) {
        val resizedImageData = imageData.map { resizeImage(it, 768) }

        if (!isAnyTextModelLoaded) {
            reportError("Please load a text model first")
            return
        }

        if (_isGenerating.value) {
            stop()
        }

        _isGenerating.value = true
        _streamingUserMessage.value = prompt
        _streamingAssistantMessage.value = ""
        userMessageAdded.set(false)
        currentMetrics = null
        _error.value = null

        val base64Image = if (resizedImageData.isNotEmpty()) {
            android.util.Base64.encodeToString(resizedImageData.first(), android.util.Base64.NO_WRAP)
        } else {
            null
        }
        currentUserMessage = Messages(
            msgId = java.util.UUID.randomUUID().toString(),
            role = Role.User,
            content = MessageContent(
                contentType = if (base64Image != null) ContentType.TextWithImage else ContentType.Text,
                content = prompt,
                imageData = base64Image
            ),
            modelId = currentModelId,
        )
        if (!userMessageAdded.get()) {
            _messages.add(currentUserMessage!!)
            userMessageAdded.set(true)
        }
        AppStateManager.setHasMessages(true)

        // Persist user message immediately for existing chats so it survives navigation
        if (!isNewConversation) {
            val chatId = _currentChatId.value
            if (chatId != null && currentUserMessage != null) {
                viewModelScope.launch(Dispatchers.IO) {
                    try {
                        chatManager.addMessage(chatId, currentUserMessage!!)
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to persist VLM user message: ${e.message}")
                    }
                }
            }
        }

        var job: Job? = null
        job = viewModelScope.launch {
            try {
                // Let Compose render the StreamingView before native engine saturates CPU
                kotlinx.coroutines.yield()

                val modelId = currentModelId ?: ""
                val modelDb = try { AppContainer.getModelRepository().getModelById(modelId) } catch (_: Exception) { null }
                val activeProviderType = ActiveModelSession.currentModelType.value

                // 1. If native VLM projector is already loaded, or using remote API with vision, we are ready
                var isVisionReady = LlmModelWorker.isVlmLoaded.value || activeProviderType == ProviderType.API || activeProviderType == ProviderType.VLM

                // 2. If not yet loaded, attempt to discover and load a colocated or installed projector
                if (!isVisionReady && activeProviderType != ProviderType.API) {
                    val candidateProj = try {
                        val mFile = modelDb?.modelPath?.let { File(it) }
                        (mFile?.let { com.bit.util.VlmPaths.colocatedMmproj(it) })
                            ?: com.bit.util.VlmPaths.findAnyProjector(com.bit.global.AppPaths.models(appContext))
                    } catch (_: Exception) { null }

                    if (candidateProj != null && candidateProj.exists() && candidateProj.length() > 1024L) {
                        val loaded = LlmModelWorker.loadVlmProjector(candidateProj.absolutePath)
                        if (loaded) {
                            ActiveModelSession.set(modelId, ProviderType.VLM)
                            isVisionReady = true
                        }
                    }
                }

                if (!isVisionReady) {
                    reportError("Please load a vision-capable model with its projector (mmproj) first")
                    return@launch
                }

                val isNewChat = isNewConversation
                if (activeProviderType == ProviderType.API) {
                    simpleRemoteFlowWithImages(prompt, base64Image, isNewChat)
                    return@launch
                }

                val maxTokens = getCurrentModelMaxTokens()

                // Insert image marker into prompt for VLM
                val marker = LlmModelWorker.getVlmDefaultMarker().ifBlank { "<__image__>" }
                val imageCount = resizedImageData.size.coerceAtLeast(1)
                val vlmPrompt = if (prompt.contains(marker) || prompt.contains("<__image__>") || prompt.contains("<__media__>")) {
                    prompt
                } else {
                    (1..imageCount).joinToString("") { "$marker\n" } + prompt
                }

                val messagesJsonList = mutableListOf<JSONObject>()
                val systemPrompt = getCurrentModelSystemPrompt(userQuery = prompt, hasTools = false)
                if (systemPrompt.isNotEmpty()) {
                    messagesJsonList.add(JSONObject().put("role", "system").put("content", systemPrompt))
                }
                
                // Add conversation history (excluding the current user message)
                if (chatMemoryEnabled.value) {
                    val currentMsgId = currentUserMessage?.msgId
                    _messages.forEach { msg ->
                        if (msg.msgId != currentMsgId) {
                            when (msg.role) {
                                Role.User -> messagesJsonList.add(JSONObject().put("role", "user").put("content", msg.content.content))
                                Role.Assistant -> {
                                    if (msg.content.contentType == ContentType.Text) {
                                        messagesJsonList.add(JSONObject().put("role", "assistant").put("content", msg.content.content))
                                    }
                                }
                                else -> {}
                            }
                        }
                    }
                }
                
                // Add current VLM user message with <__image__> marker
                messagesJsonList.add(JSONObject().put("role", "user").put("content", vlmPrompt))
                val jsonArray = JSONArray(messagesJsonList)

                AppStateManager.setGeneratingText()

                val resultBuilder = StringBuilder()
                val thinkingBuilder = StringBuilder()
                var lastEmitTime = 0L
                val thinkingActive = _thinkingModeEnabled.value
                val vlmThinkParser = StreamingThinkTagParser(assumeThinking = false)

                LlmModelWorker.vlmGenerateStreaming(
                    jsonArray.toString(), resizedImageData, maxTokens
                ).collect { event ->
                    when (event) {
                        is GenerationEvent.Token -> {
                            vlmThinkParser.feed(
                                content = event.text,
                                thinkingEnabled = thinkingActive,
                                onText = { text ->
                                    resultBuilder.append(text)
                                    val now = System.currentTimeMillis()
                                    if (now - lastEmitTime >= STREAMING_THROTTLE_MS) {
                                        val output = if (thinkingBuilder.isNotEmpty()) "<think>${thinkingBuilder}</think>${resultBuilder}" else resultBuilder.toString()
                                        _streamingAssistantMessage.value = output
                                        lastEmitTime = now
                                    }
                                },
                                onThought = { thought -> 
                                    thinkingBuilder.append(thought) 
                                    val now = System.currentTimeMillis()
                                    if (now - lastEmitTime >= STREAMING_THROTTLE_MS) {
                                        val output = if (thinkingBuilder.isNotEmpty()) "<think>${thinkingBuilder}</think>${resultBuilder}" else resultBuilder.toString()
                                        _streamingAssistantMessage.value = output
                                        lastEmitTime = now
                                    }
                                }
                            )
                        }
                        is GenerationEvent.Done -> {
                            vlmThinkParser.flush(
                                onText = { resultBuilder.append(it) },
                                onThought = { thinkingBuilder.append(it) }
                            )
                            val finalResponse = if (thinkingBuilder.isNotEmpty()) "<think>${thinkingBuilder}</think>${resultBuilder}" else resultBuilder.toString()
                            _streamingAssistantMessage.value = finalResponse
                        }
                        is GenerationEvent.Metrics -> { currentMetrics = event.metrics }
                        is GenerationEvent.Progress -> { /* progress tracked elsewhere */ }
                        is GenerationEvent.Error -> {
                            Log.e(TAG, "VLM generation error: ${event.message}")
                            throw Exception(event.message)
                        }
                        is GenerationEvent.ToolCall -> { /* VLM doesn't support tool calling */ }
                        is GenerationEvent.ToolResult -> {}
                        is GenerationEvent.ThinkingBlock -> {}
                        is GenerationEvent.PartialResponse -> {}
                    }
                }

                val finalResponse = if (thinkingBuilder.isNotEmpty()) "<think>${thinkingBuilder}</think>${resultBuilder.toString().trim()}" else resultBuilder.toString().trim()
                _streamingAssistantMessage.value = finalResponse

                if (isNewChat) {
                    createChatWithMessages(prompt, finalResponse, currentMetrics)
                } else {
                    val chatId = _currentChatId.value ?: return@launch
                    val pendingUserMsg = currentUserMessage
                    if (!userMessageAdded.get() && pendingUserMsg != null) {
                        _messages.add(pendingUserMsg)
                        userMessageAdded.set(true)
                    }
                    if (finalResponse.isNotBlank()) {
                        val assistantMessage = Messages(
                            role = Role.Assistant,
                            content = MessageContent(contentType = ContentType.Text, content = finalResponse),
                            modelId = currentModelId,
                            decodingMetrics = currentMetrics,
                        )
                        _messages.add(assistantMessage)
                        chatManager.addMessage(chatId, assistantMessage)
                        AppStateManager.setGenerationComplete()
                        AppStateManager.chatRefreshed()
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Error in sendChatWithImages", e)
                _isGenerating.value = false
                reportError(e.message)
            } finally {
                if (generationJob == job) {
                    resetStreamingState()
                } else {
                    // Another generation was started — ensure we don't leave
                    // the UI stuck in "generating" from this coroutine.
                    _isGenerating.value = false
                }
            }
        }
        generationJob = job
    }

    /**
     * Regenerate the last assistant response.
     * Removes the last assistant message and re-sends the last user prompt.
     */
    // Snapshot of old assistant message during regeneration — restored if stop() is
    // called before new content arrives (fixes issue #77: message disappears on cancel)
    private var regenerationSnapshot: Messages? = null

    fun regenerateLastMessage() {
        if (!isAnyTextModelLoaded) {
            reportError("Please load a text generation model first")
            return
        }
        if (_isGenerating.value) return

        val chatId = _currentChatId.value ?: return

        // Find the last user message to get the prompt
        val lastUserMsg = _messages.lastOrNull { it.role == Role.User }
        if (lastUserMsg == null) {
            reportError("No user message to regenerate from")
            return
        }

        // Snapshot the old assistant message — remove from UI but keep for rollback
        val lastAssistantMsg = _messages.lastOrNull { it.role == Role.Assistant }
        regenerationSnapshot = lastAssistantMsg
        if (lastAssistantMsg != null) {
            _messages.remove(lastAssistantMsg)
        }

        val prompt = lastUserMsg.content.content

        // Set up generation state without creating a new user message
        _isGenerating.value = true
        _streamingUserMessage.value = prompt
        _streamingAssistantMessage.value = ""
        currentUserMessage = lastUserMsg // needed for stop() rollback
        userMessageAdded.set(true) // already added — skip re-adding user message
        currentMetrics = null
        _error.value = null

        var job: Job? = null
        job = viewModelScope.launch {
            try {
                val maxTokens = getCurrentModelMaxTokens()
                val activeProviderType = ActiveModelSession.currentModelType.value
                val hasTools = PluginManager.hasEnabledTools()
                        && (PluginManager.isToolCallingModelLoaded.value || activeProviderType == ProviderType.API)
                LlmModelWorker.setThinkingEnabledGguf(_thinkingModeEnabled.value && !hasTools)
                val ragContext = _currentRagContext.value

                executeUnifiedGeneration(prompt, ragContext, maxTokens, isNewChat = false, isRegeneration = true)

                // Generation completed successfully — now delete old message from DB
                if (lastAssistantMsg != null) {
                    chatManager.deleteMessage(lastAssistantMsg.msgId)
                }
                regenerationSnapshot = null
            } catch (e: kotlinx.coroutines.CancellationException) {
                // stop() handles rollback via regenerationSnapshot
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Error in regenerateLastMessage", e)
                restoreRegenerationSnapshot()
                reportError(e.message)
            } finally {
                if (generationJob == job) {
                    resetStreamingState()
                }
            }
        }
        generationJob = job
    }

    private fun restoreRegenerationSnapshot() {
        val snapshot = regenerationSnapshot ?: return
        regenerationSnapshot = null
        _messages.add(snapshot)
    }

    private suspend fun getCurrentModelMaxTokens(): Int =
        getGgufModelSchema().inferenceParams.maxTokens

    // ==================== Unified Generation Flow (Agora Architecture) ====================

    private suspend fun executeUnifiedGeneration(
        prompt: String,
        ragContext: String?,
        maxTokens: Int,
        isNewChat: Boolean,
        isRegeneration: Boolean = false
    ) {
        AppStateManager.setGeneratingText()
        val appSettings = AppSettingsDataStore(appContext)
        // ── TTFT optimization: parallelize DataStore reads ──
        val (globalPrepend, globalPostpend) = withContext(Dispatchers.IO) {
            val p1 = async { appSettings.globalPrependPrompt.first() }
            val p2 = async { appSettings.globalPostpendPrompt.first() }
            Pair(p1.await(), p2.await())
        }
        
        val currentDateTime = java.util.Date()
        val sdf = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
        val dateSdf = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault())

        val runtimeValues = mapOf(
            "{${com.bit.data.PredefinedVariables.TIME}}" to sdf.format(currentDateTime),
            "{${com.bit.data.PredefinedVariables.DATE}}" to dateSdf.format(currentDateTime),
            "{${com.bit.data.PredefinedVariables.SENT_TIME}}" to sdf.format(currentDateTime),
            "{${com.bit.data.PredefinedVariables.SENT_DATE}}" to dateSdf.format(currentDateTime),
            "{${com.bit.data.PredefinedVariables.ACTIVE_MEMORY}}" to ""
        )

        val activeModelId = currentModelId ?: ""
        val isSmall = activeModelId.contains("350m", ignoreCase = true) ||
                activeModelId.contains("125m", ignoreCase = true) ||
                activeModelId.contains("160m", ignoreCase = true) ||
                activeModelId.contains("tiny", ignoreCase = true) ||
                activeModelId.contains("mini", ignoreCase = true) ||
                activeModelId.contains("1b", ignoreCase = true) ||
                activeModelId.contains("2b", ignoreCase = true) ||
                activeModelId.contains("3b", ignoreCase = true) ||
                activeModelId.contains("1.5b", ignoreCase = true) ||
                activeModelId.contains("1.7b", ignoreCase = true) ||
                activeModelId.contains("1.8b", ignoreCase = true) ||
                activeModelId.contains("qwen3", ignoreCase = true)

        var finalPrepend = if (isSmall) "" else globalPrepend
        var finalPostpend = if (isSmall) "" else globalPostpend
        for ((key, value) in runtimeValues) {
            finalPrepend = finalPrepend.replace(key, value)
            finalPostpend = finalPostpend.replace(key, value)
        }

        var finalPrompt = prompt
        if (finalPrepend.isNotBlank()) finalPrompt = "$finalPrepend\n$finalPrompt"
        if (finalPostpend.isNotBlank()) finalPrompt = "$finalPrompt\n$finalPostpend"
        
        val fullPrompt = ragContext?.let { "$it\n\n$finalPrompt" } ?: finalPrompt

        // Reset state
        _streamingAssistantMessage.value = ""
        _agentPlan.value = null
        _agentSummary.value = null
        _toolChainSteps.value = emptyList()
        _agentPhase.value = AgentPhase.Executing

        val activeProviderType = ActiveModelSession.currentModelType.value
        val isQwen3 = activeModelId.contains("qwen3", ignoreCase = true)
        val isTooTinyForTools = activeModelId.contains("350m", ignoreCase = true) ||
                activeModelId.contains("125m", ignoreCase = true) ||
                activeModelId.contains("160m", ignoreCase = true) ||
                activeModelId.contains("0.5b", ignoreCase = true) ||
                activeModelId.contains("0.6b", ignoreCase = true) ||
                activeModelId.contains("0.8b", ignoreCase = true) ||
                activeModelId.contains("1b", ignoreCase = true) ||
                activeModelId.contains("1.5b", ignoreCase = true) ||
                activeModelId.contains("1.8b", ignoreCase = true) ||
                activeModelId.contains("tiny", ignoreCase = true) ||
                activeModelId.contains("mini", ignoreCase = true)
        val hasTools = PluginManager.hasEnabledTools()
                && (PluginManager.isToolCallingModelLoaded.value || activeProviderType == ProviderType.API || isQwen3)
                && !isTooTinyForTools

        val steps = mutableListOf<ToolChainStepData>()
        val seenCalls = mutableSetOf<String>()
        var finalResponse = ""
        var round = 0
        val maxRounds = 5

        val enabledNames = PluginManager.getEnabledToolNames().map { normalizeToolName(it) }.toSet()

        while (round < maxRounds) {
            round++
            Log.d(TAG, "Unified tool loop: starting round $round")

            val currentRoundHasTools = hasTools && steps.isEmpty()

            // Build conversation messages for this turn
            val conversationMessages = buildConversationMessagesWithSteps(fullPrompt, steps, isRegeneration, currentRoundHasTools)

            // Generate response (streaming)
            AppStateManager.setGeneratingText()
            val result = if (activeProviderType == ProviderType.API) {
                generateRemoteUnified(conversationMessages, steps, currentRoundHasTools, maxTokens)
            } else {
                generateGgufUnified(conversationMessages, hasTools = currentRoundHasTools, maxTokens = maxTokens)
            }

            if (result.toolCalls.isEmpty()) {
                // No tool calls generated. This is the final text response!
                finalResponse = result.text
                break
            }

            // Process the tool calls
            var toolExecuted = false
            var hallucinatedText = ""
            for ((rawName, rawArgs) in result.toolCalls) {
                val callKey = "${rawName.lowercase()}:${rawArgs.hashCode()}"
                if (callKey in seenCalls) {
                    Log.w(TAG, "Duplicate tool call detected, skipping: $rawName")
                    continue
                }
                seenCalls.add(callKey)

                val parsed = extractToolCallFromArgs(rawName, rawArgs)
                if (parsed == null) {
                    Log.e(TAG, "Failed to parse tool call: $rawName")
                    continue
                }

                val (toolName, argsObj) = parsed
                val normalizedName = normalizeToolName(toolName)
                if (normalizedName.lowercase() !in enabledNames) {
                    Log.w(TAG, "Hallucinated tool name: $normalizedName")
                    val possibleText = argsObj.optString("text").takeIf { it.isNotBlank() }
                        ?: argsObj.optString("answer").takeIf { it.isNotBlank() }
                        ?: argsObj.optString("summary").takeIf { it.isNotBlank() }
                        ?: argsObj.optString("response").takeIf { it.isNotBlank() }
                        ?: argsObj.optString("content").takeIf { it.isNotBlank() }
                        ?: argsObj.optString("final_answer").takeIf { it.isNotBlank() }
                    
                    if (possibleText != null) {
                        hallucinatedText += (if (hallucinatedText.isEmpty()) "" else "\n") + possibleText
                    }
                    continue
                }

                // Show executing plugin in UI
                AppStateManager.setExecutingPlugin("", normalizedName)
                _agentPhase.value = AgentPhase.Executing

                // Execute tool
                val startTime = System.currentTimeMillis()
                val toolCall = ToolCall(name = normalizedName, arguments = argsObj)
                val toolResult = PluginManager.executeToolForMultiTurn(toolCall)
                val executionTime = System.currentTimeMillis() - startTime

                val isSuccess = !toolResult.isError
                AppStateManager.setPluginExecutionComplete(
                    pluginName = toolResult.pluginName,
                    toolName = normalizedName,
                    success = isSuccess,
                    executionTimeMs = executionTime,
                    errorMessage = if (isSuccess) null else toolResult.resultJson
                )

                // Add step data
                val step = ToolChainStepData(
                    round = steps.size + 1,
                    toolName = normalizedName,
                    pluginName = toolResult.pluginName,
                    args = rawArgs.take(2000),
                    result = toolResult.resultJson,
                    executionTimeMs = executionTime,
                    success = isSuccess
                )
                steps.add(step)
                _toolChainSteps.value = steps.toList()
                toolExecuted = true

                // Add PluginResult message to in-memory UI
                if (toolResult.rawData != null) {
                    val resultData = PluginResultData(
                        pluginName = toolResult.pluginName,
                        toolName = normalizedName,
                        inputParams = argsObj.toString(),
                        resultData = toolResult.resultJson,
                        success = isSuccess
                    )
                    val pluginMessage = Messages(
                        role = Role.Assistant,
                        content = MessageContent(
                            contentType = ContentType.PluginResult,
                            content = "Plugin '${toolResult.pluginName}' executed tool '$normalizedName'",
                            pluginResultData = resultData
                        ),
                        modelId = currentModelId,
                        pluginMetrics = PluginExecutionMetrics(
                            pluginName = toolResult.pluginName,
                            toolName = normalizedName,
                            executionTimeMs = executionTime,
                            success = isSuccess
                        )
                    )
                    val pendingUserMsg = currentUserMessage
                    if (!userMessageAdded.get() && pendingUserMsg != null) {
                        _messages.add(pendingUserMsg)
                        userMessageAdded.set(true)
                    }
                    _messages.add(pluginMessage)
                }
            }

            if (!toolExecuted) {
                // If we generated tool calls but none were executed, stop.
                finalResponse = if (hallucinatedText.isNotBlank()) hallucinatedText else result.text
                break
            }
        }

        // Clean final response and update state
        val cleanResponse = filterToolCallSyntax(finalResponse).trim()
        _streamingAssistantMessage.value = cleanResponse

        // Save to DB and finish
        _agentPhase.value = if (steps.isNotEmpty()) AgentPhase.Complete else AgentPhase.Idle

        if (steps.isNotEmpty()) {
            val finalSummary = cleanResponse.takeIf { it.isNotBlank() } ?: "Tool execution completed, but the model failed to generate a final answer. Please view the tool outputs in the trace panel below."
            _agentSummary.value = finalSummary
            persistAgentChat(prompt, isNewChat, "Determine if any tools are needed to answer the query.", steps, finalSummary)
        } else {
            // Simple flow path
            if (isNewChat) {
                createChatWithMessages(prompt, cleanResponse, currentMetrics)
            } else {
                val chatId = _currentChatId.value ?: return
                val pendingUserMsg = currentUserMessage
                if (!userMessageAdded.get() && pendingUserMsg != null) {
                    _messages.add(pendingUserMsg)
                    userMessageAdded.set(true)
                }

                val ragResultItems = _currentRagResults.value.takeIf { it.isNotEmpty() }?.map { result ->
                    RagResultItem(
                        ragName = result.ragName,
                        content = result.content,
                        score = result.score,
                        nodeId = result.nodeId
                    )
                }

                if (cleanResponse.isNotBlank()) {
                    val assistantMessage = Messages(
                        role = Role.Assistant,
                        content = MessageContent(contentType = ContentType.Text, content = cleanResponse),
                        modelId = currentModelId,
                        decodingMetrics = currentMetrics,
                        ragResults = ragResultItems
                    )
                    _messages.add(assistantMessage)
                    chatManager.addMessage(chatId, assistantMessage)
                }
                AppStateManager.setGenerationComplete()
                AppStateManager.chatRefreshed()
                resetStreamingState()
                viewModelScope.launch { autoSpeakIfEnabled(cleanResponse, _messages.lastOrNull { it.role == Role.Assistant }?.msgId) }
            }
        }
    }

    private suspend fun buildConversationMessagesWithSteps(
        userPrompt: String,
        steps: List<ToolChainStepData>,
        isRegeneration: Boolean,
        hasTools: Boolean = false
    ): List<JSONObject> {
        val result = mutableListOf<JSONObject>()
        val systemPrompt = getCurrentModelSystemPrompt(userQuery = userPrompt, hasTools = hasTools)
        if (systemPrompt.isNotEmpty()) {
            result.add(JSONObject().put("role", "system").put("content", systemPrompt))
        }

        // Add history up to now
        if (chatMemoryEnabled.value) {
            val historyMessages = if (isRegeneration && _messages.lastOrNull()?.role == Role.User) {
                _messages.dropLast(1)
            } else {
                _messages
            }
            historyMessages.forEach { msg ->
                when (msg.role) {
                    Role.User -> result.add(JSONObject().put("role", "user").put("content", msg.content.content))
                    Role.Assistant -> {
                        when (msg.content.contentType) {
                            ContentType.Text -> result.add(JSONObject().put("role", "assistant").put("content", msg.content.content))
                            else -> {}
                        }
                    }
                }
            }
        }

        // Add current user prompt
        result.add(JSONObject().put("role", "user").put("content", userPrompt))

        // Add current steps' tool calls and results
        for (step in steps) {
            result.add(JSONObject().put("role", "assistant").put("content",
                """{"name":"${step.toolName}","arguments":${step.args}}"""
            ))
            
            // Format the search result or tool result using SearchResultFormatter!
            val formattedResult = SearchResultFormatter.format(step.result, appContext)
            val contentText = if (step.toolName == "web_search") {
                "Tool '${step.toolName}' result: $formattedResult\n\n[Instruction: If the search results above do not clearly state a fact, state that you cannot find it. Do not invent or assume any details.]"
            } else {
                "Tool '${step.toolName}' result: $formattedResult"
            }
            
            result.add(JSONObject().put("role", "user").put("content", contentText))
        }

        return sanitizeRoleAlternation(result)
    }

    private suspend fun generateRemoteUnified(
        messages: List<JSONObject>,
        steps: List<ToolChainStepData>,
        hasTools: Boolean,
        maxTokens: Int
    ): GenerationResult {
        val remoteCfg = getRemoteInferenceConfig() ?: throw IllegalStateException("Remote API model is missing endpoint configuration")

        val provider = LlmProviderResolver.resolveProvider(remoteCfg.endpoint, remoteCfg.model)
        val apiKey = LlmProviderResolver.cleanApiKey(remoteCfg.authHeader)
        val baseUrl = LlmProviderResolver.cleanBaseUrl(remoteCfg.endpoint)

        val chatMessages = mutableListOf<ChatMessage>()
        
        // Add previous history
        for (obj in messages) {
            val role = obj.optString("role")
            val content = obj.optString("content")
            
            // Skip the unstructured tool text that buildConversationMessagesWithSteps added
            if (role == "assistant" && content.startsWith("{\"name\":")) continue
            if (role == "user" && content.startsWith("Tool '")) continue
            
            val participant = if (role.lowercase() == "user") com.bit.api.Participant.USER else com.bit.api.Participant.MODEL
            chatMessages.add(ChatMessage(text = content, participant = participant))
        }
        
        // Add proper tool calls and results
        for (step in steps) {
            val tc = com.bit.api.ToolCallData(
                toolName = step.toolName,
                arguments = step.args,
                result = step.result
            )
            val toolCallId = com.bit.api.util.buildToolCallId(step.toolName, step.args)
            val tcWithId = tc.copy(toolCallId = toolCallId)
            
            chatMessages.add(ChatMessage(
                id = com.bit.api.Constants.TOOL_MSG_PREFIX + java.util.UUID.randomUUID().toString(),
                text = "",
                participant = com.bit.api.Participant.MODEL,
                toolCall = tcWithId
            ))
            
            chatMessages.add(ChatMessage(
                id = com.bit.api.Constants.RESULT_MSG_PREFIX + java.util.UUID.randomUUID().toString(),
                text = "",
                participant = com.bit.api.Participant.USER,
                toolCall = tcWithId
            ))
        }

        val jsonSerializer = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
        val tools = if (hasTools) {
            PluginManager.getEnabledToolDefinitions().map { toolDef ->
                val toolJsonString = toolDef.build().toOpenAIFormat().toString()
                jsonSerializer.decodeFromString<com.bit.api.ToolDefinition>(toolJsonString)
            }
        } else null

        val config = ProviderConfig(
            apiKey = apiKey,
            modelId = remoteCfg.model,
            systemPrompt = null,
            baseUrl = baseUrl,
            tools = tools,
            thinkingEnabled = _thinkingModeEnabled.value,
            maxTokens = maxTokens
        )

        val startTimeMs = System.currentTimeMillis()
        var firstTokenTimeMs = 0L
        var tokenCount = 0

        val textBuilder = java.lang.StringBuilder()
        val thinkBuilder = java.lang.StringBuilder()
        val toolCalls = mutableListOf<Pair<String, String>>()
        var lastEmitTime = 0L

        provider.generateResponse(chatMessages, config).collect { event ->
            when (event) {
                is StreamEvent.TextChunk -> {
                    if (firstTokenTimeMs == 0L) {
                        firstTokenTimeMs = System.currentTimeMillis()
                    }
                    tokenCount++
                    textBuilder.append(event.text)
                    val now = System.currentTimeMillis()
                    if (now - lastEmitTime >= STREAMING_THROTTLE_MS) {
                        val output = if (thinkBuilder.isNotEmpty()) "<think>${thinkBuilder}</think>${textBuilder}" else textBuilder.toString()
                        _streamingAssistantMessage.value = output
                        lastEmitTime = now
                    }
                }
                is StreamEvent.ThoughtChunk -> {
                    if (firstTokenTimeMs == 0L) {
                        firstTokenTimeMs = System.currentTimeMillis()
                    }
                    tokenCount++
                    thinkBuilder.append(event.thought)
                    val now = System.currentTimeMillis()
                    if (now - lastEmitTime >= STREAMING_THROTTLE_MS) {
                        val output = if (thinkBuilder.isNotEmpty()) "<think>${thinkBuilder}</think>${textBuilder}" else textBuilder.toString()
                        _streamingAssistantMessage.value = output
                        lastEmitTime = now
                    }
                }
                is StreamEvent.UsageUpdate -> {
                    tokenCount = event.tokenCount
                }
                is StreamEvent.ToolCallRequest -> {
                    toolCalls.add(Pair(event.name, event.arguments))
                }
                is StreamEvent.ToolCallsRequest -> {
                    event.calls.forEach { call ->
                        toolCalls.add(Pair(call.name, call.arguments))
                    }
                }
                is StreamEvent.Error -> {
                    throw Exception(event.message)
                }
                else -> {}
            }
        }

        val text = if (thinkBuilder.isNotEmpty()) "<think>${thinkBuilder}</think>${textBuilder.toString().trim()}" else textBuilder.toString().trim()
        if (text.isNotEmpty()) {
            _streamingAssistantMessage.value = text
        }
        val finalToolCalls = mutableListOf<Pair<String, String>>()
        finalToolCalls.addAll(toolCalls)

        val totalTimeMs = (System.currentTimeMillis() - startTimeMs).toFloat()
        val timeToFirstToken = if (firstTokenTimeMs > 0L) (firstTokenTimeMs - startTimeMs).toFloat() else totalTimeMs
        val tokensPerSec = if (totalTimeMs > 0) (tokenCount / (totalTimeMs / 1000f)) else 0f
        
        currentMetrics = com.bit.models.engine_schema.DecodingMetrics(
            tokensPerSecond = tokensPerSec,
            timeToFirstTokenMs = timeToFirstToken,
            totalTimeMs = totalTimeMs,
            tokensPredicted = tokenCount
        )

        if (finalToolCalls.isEmpty() && text.isNotBlank()) {
            parseToolCallsFromText(text)?.let { parsed ->
                finalToolCalls.addAll(parsed)
            }
        }

        return GenerationResult(text = text, toolCalls = finalToolCalls)
    }

    private suspend fun generateGgufUnified(
        messages: List<JSONObject>,
        hasTools: Boolean,
        maxTokens: Int
    ): GenerationResult {
        if (hasTools) {
            PluginManager.restoreGrammar()
        } else {
            PluginManager.clearGrammar()
        }

        val jsonArray = JSONArray(messages)
        val textBuilder = java.lang.StringBuilder()
        val thinkBuilder = java.lang.StringBuilder()
        val toolCalls = mutableListOf<Pair<String, String>>()
        var lastEmitTime = 0L
        val thinkingActive = _thinkingModeEnabled.value
        val thinkParser = StreamingThinkTagParser(assumeThinking = false)

        LlmModelWorker.ggufGenerateMultiTurnStreaming(
            jsonArray.toString(), maxTokens
        ).collect { event ->
            when (event) {
                is GenerationEvent.Token -> {
                    thinkParser.feed(
                        content = event.text,
                        thinkingEnabled = thinkingActive,
                        onText = { text ->
                            textBuilder.append(text)
                            val now = System.currentTimeMillis()
                            if (now - lastEmitTime >= STREAMING_THROTTLE_MS) {
                                val output = if (thinkBuilder.isNotEmpty()) "<think>${thinkBuilder}</think>${textBuilder}" else textBuilder.toString()
                                _streamingAssistantMessage.value = output
                                lastEmitTime = now
                            }
                        },
                        onThought = { thought -> 
                            thinkBuilder.append(thought)
                            val now = System.currentTimeMillis()
                            if (now - lastEmitTime >= STREAMING_THROTTLE_MS) {
                                val output = if (thinkBuilder.isNotEmpty()) "<think>${thinkBuilder}</think>${textBuilder}" else textBuilder.toString()
                                _streamingAssistantMessage.value = output
                                lastEmitTime = now
                            }
                        }
                    )
                }
                is GenerationEvent.ToolCall -> {
                    toolCalls.add(Pair(event.name, event.args))
                }
                is GenerationEvent.Metrics -> {
                    currentMetrics = event.metrics
                }
                is GenerationEvent.Error -> {
                    throw Exception(event.message)
                }
                is GenerationEvent.Done -> {
                    thinkParser.flush(
                        onText = { textBuilder.append(it) },
                        onThought = { thinkBuilder.append(it) }
                    )
                    val output = if (thinkBuilder.isNotEmpty()) "<think>${thinkBuilder}</think>${textBuilder}" else textBuilder.toString()
                    _streamingAssistantMessage.value = output
                }
                else -> {}
            }
        }

        val text = if (thinkBuilder.isNotEmpty()) "<think>${thinkBuilder}</think>${textBuilder.toString().trim()}" else textBuilder.toString().trim()
        val finalToolCalls = mutableListOf<Pair<String, String>>()
        finalToolCalls.addAll(toolCalls)

        if (finalToolCalls.isEmpty() && text.isNotBlank()) {
            val enabledNames = PluginManager.getEnabledToolNames().map { normalizeToolName(it) }.toSet()
            parseToolCallsFromText(text)?.let { parsed ->
                val valid = parsed.filter { (name, _) ->
                    normalizeToolName(name) in enabledNames
                }
                finalToolCalls.addAll(valid)
            }
        }

        return GenerationResult(text = text, toolCalls = finalToolCalls)
    }

    private suspend fun persistAgentChat(
        prompt: String,
        isNewChat: Boolean,
        plan: String,
        steps: List<ToolChainStepData>,
        summary: String
    ) {
        val ragResultItems = _currentRagResults.value.takeIf { it.isNotEmpty() }?.map { result ->
            RagResultItem(
                ragName = result.ragName,
                content = result.content,
                score = result.score,
                nodeId = result.nodeId
            )
        }

        val chatId = _currentChatId.value ?: return

        // Add user message to in-memory list if not already added
        val pendingUserMsg = currentUserMessage
        if (!userMessageAdded.get() && pendingUserMsg != null) {
            _messages.add(pendingUserMsg)
            userMessageAdded.set(true)
        }

        val assistantMessage = Messages(
            role = Role.Assistant,
            content = MessageContent(contentType = ContentType.Text, content = summary),
            modelId = currentModelId,
            decodingMetrics = currentMetrics,
            ragResults = ragResultItems,
            toolChainSteps = steps,
            agentPlan = plan,
            agentSummary = summary
        )
        
        // Remove ephemeral plugin result messages from in-memory UI to prevent duplicates
        _messages.removeAll { it.content.contentType == ContentType.PluginResult }
        
        _messages.add(assistantMessage)
        chatManager.addMessage(chatId, assistantMessage)

        if (isNewChat) {
            // Reload to get proper IDs
            chatManager.getChatMessages(chatId).onSuccess { loadedMessages ->
                _messages.clear()
                _messages.addAll(loadedMessages)
            }
        }

        AppStateManager.setGenerationComplete()
        AppStateManager.chatRefreshed()
        val spokenMsgId = assistantMessage.msgId
        resetStreamingState()
        viewModelScope.launch { autoSpeakIfEnabled(summary, spokenMsgId) }
    }

    private data class RemoteInferenceConfig(
        val endpoint: String,
        val model: String,
        val stream: Boolean,
        val authHeader: String?
    )

    private suspend fun getRemoteInferenceConfig(): RemoteInferenceConfig? {
        val modelId = ActiveModelSession.currentModelId.value.ifBlank { return null }
        val config = AppContainer.getModelRepository().getConfigByModelId(modelId) ?: return null
        val loading = config.modelLoadingParams?.takeIf { it.isNotBlank() } ?: return null
        return try {
            val json = JSONObject(loading)
            val endpoint = json.optString("endpoint").trim()
            if (endpoint.isBlank()) return null

            val model = json.optString("model").takeIf { it.isNotBlank() } ?: modelId
            val stream = json.optBoolean("stream", false)
            val auth = json.optString("authHeader").takeIf { it.isNotBlank() }
                ?: json.optString("authorization").takeIf { it.isNotBlank() }

            RemoteInferenceConfig(
                endpoint = endpoint,
                model = model,
                stream = stream,
                authHeader = auth
            )
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun simpleRemoteFlowWithImages(
        prompt: String,
        base64Image: String?,
        isNewChat: Boolean
    ) {
        AppStateManager.setGeneratingText()
        val remoteCfg = getRemoteInferenceConfig()
        if (remoteCfg == null) {
            reportError("Remote API model is missing endpoint configuration")
            return
        }

        if (!remoteCfg.endpoint.startsWith("http")) {
            reportError("Invalid remote endpoint: ${remoteCfg.endpoint}. Please ensure it starts with http:// or https://")
            return
        }

        val remoteResult = withContext(Dispatchers.IO) {
            val messages = mutableListOf<RemoteInferenceClient.Message>()
            
            // Add system prompt with date/time
            val systemPrompt = getCurrentModelSystemPrompt()
            if (systemPrompt.isNotEmpty()) {
                messages.add(
                    RemoteInferenceClient.Message(
                        role = "system",
                        content = systemPrompt
                    )
                )
            }
            
            // Build conversation history from current messages (excluding pending)
            if (!isNewChat) {
                _messages.forEach { msg ->
                    val role = when (msg.role) {
                        Role.User -> "user"
                        Role.Assistant -> "assistant"
                    }
                    messages.add(
                        RemoteInferenceClient.Message(
                            role = role,
                            content = msg.content.content,
                            base64Image = msg.content.imageData
                        )
                    )
                }
            }
            
            // Add current user message
            messages.add(
                RemoteInferenceClient.Message(
                    role = "user",
                    content = prompt,
                    base64Image = base64Image
                )
            )
            
            // Use history-aware method for chat endpoints
            val normalizedEndpoint = remoteCfg.endpoint.trim().lowercase()
            val isChatEndpoint = normalizedEndpoint.contains("/api/chat") ||
                normalizedEndpoint.contains("/v1/chat/completions")
            
            if (isChatEndpoint && messages.size > 1) {
                RemoteInferenceClient.inferWithHistory(
                    endpoint = remoteCfg.endpoint,
                    model = remoteCfg.model,
                    messages = messages,
                    stream = remoteCfg.stream,
                    authHeader = remoteCfg.authHeader
                )
            } else {
                RemoteInferenceClient.inferWithHistory(
                    endpoint = remoteCfg.endpoint,
                    model = remoteCfg.model,
                    messages = listOf(RemoteInferenceClient.Message("user", prompt, base64Image)),
                    stream = remoteCfg.stream,
                    authHeader = remoteCfg.authHeader
                )
            }
        }

        val finalResponse = remoteResult.text.trim()
        _streamingAssistantMessage.value = finalResponse

        if (isNewChat) {
            createChatWithMessages(prompt, finalResponse, null)
            return
        }

        val chatId = _currentChatId.value ?: return
        val pendingUserMsg = currentUserMessage
        if (!userMessageAdded.get() && pendingUserMsg != null) {
            _messages.add(pendingUserMsg)
            userMessageAdded.set(true)
        }

        if (finalResponse.isNotBlank()) {
            val assistantMessage = Messages(
                role = Role.Assistant,
                content = MessageContent(contentType = ContentType.Text, content = finalResponse),
                modelId = currentModelId
            )
            _messages.add(assistantMessage)
            chatManager.addMessage(chatId, assistantMessage)
            AppStateManager.setGenerationComplete()
            AppStateManager.chatRefreshed()
            val spokenMsgId = assistantMessage.msgId
            resetStreamingState()
            viewModelScope.launch { autoSpeakIfEnabled(finalResponse, spokenMsgId) }
        } else {
            AppStateManager.setGenerationComplete()
            resetStreamingState()
        }
    }

    // ==================== LLM Generation Helpers ====================

    /**
     * Detect if text ends with a repeating pattern (common with small models).
     * Returns the index to trim to (keep one copy of the pattern), or -1 if no repetition.
     */
    private fun detectRepetitionTrimIndex(
        text: String,
        minPatternLen: Int = REPETITION_MIN_PATTERN_LEN,
        minRepeats: Int = REPETITION_MIN_REPEATS,
        maxCheckLen: Int = REPETITION_MAX_CHECK_LEN
    ): Int {
        if (text.length < minPatternLen * minRepeats) return -1

        val checkLen = minOf(text.length, maxCheckLen)
        val startOffset = text.length - checkLen
        val window = text.substring(startOffset)

        for (patternLen in minPatternLen until checkLen / minRepeats) {
            val pattern = window.substring(window.length - patternLen)
            var count = 1
            var pos = window.length - patternLen * 2

            while (pos >= 0) {
                if (window.regionMatches(pos, pattern, 0, patternLen)) {
                    count++
                    pos -= patternLen
                } else {
                    break
                }
            }

            if (count >= minRepeats && patternLen * count >= 120) {
                // Keep content up to end of first occurrence of the pattern
                val repeatStartInWindow = window.length - patternLen * count
                return startOffset + repeatStartInWindow + patternLen
            }
        }
        return -1
    }

    private data class GenerationResult(
        val text: String,
        val toolCalls: List<Pair<String, String>> = emptyList()
    )

    /** Generate text, streaming to UI. Collects any native ToolCall events. */
    private suspend fun generatePlainText(
        messages: List<JSONObject>,
        maxTokens: Int
    ): String {
        val result = generateWithToolCalls(messages, maxTokens)
        return result.text
    }

    private suspend fun generateWithToolCalls(
        messages: List<JSONObject>,
        maxTokens: Int
    ): GenerationResult {
        val activeProviderType = ActiveModelSession.currentModelType.value
        if (activeProviderType == ProviderType.API) {
            val remoteCfg = getRemoteInferenceConfig() ?: throw IllegalStateException("Remote API model is missing endpoint configuration")
            
            val provider = LlmProviderResolver.resolveProvider(remoteCfg.endpoint, remoteCfg.model)
            val apiKey = LlmProviderResolver.cleanApiKey(remoteCfg.authHeader)
            val baseUrl = LlmProviderResolver.cleanBaseUrl(remoteCfg.endpoint)
            
            var sysPrompt: String? = null
            val chatMessages = messages.mapNotNull { obj ->
                val role = obj.optString("role")
                val content = obj.optString("content")
                if (role.lowercase() == "system") {
                    sysPrompt = content
                    null
                } else {
                    val participant = if (role.lowercase() == "user") Participant.USER else Participant.MODEL
                    ChatMessage(text = content, participant = participant)
                }
            }
            
            val config = ProviderConfig(
                apiKey = apiKey,
                modelId = remoteCfg.model,
                systemPrompt = sysPrompt,
                baseUrl = baseUrl,
                thinkingEnabled = _thinkingModeEnabled.value,
                maxTokens = maxTokens
            )
            
            val resultBuilder = java.lang.StringBuilder()
            val nativeToolCalls = mutableListOf<Pair<String, String>>()
            
            try {
                provider.generateResponse(chatMessages, config).collect { event ->
                    when (event) {
                        is StreamEvent.TextChunk -> {
                            resultBuilder.append(event.text)
                            _streamingAssistantMessage.value = resultBuilder.toString()
                        }
                        is StreamEvent.ToolCallRequest -> {
                            nativeToolCalls.add(Pair(event.name, event.arguments))
                        }
                        is StreamEvent.ToolCallsRequest -> {
                            event.calls.forEach { call ->
                                nativeToolCalls.add(Pair(call.name, call.arguments))
                            }
                        }
                        is StreamEvent.Error -> {
                            throw IllegalStateException(event.message)
                        }
                        else -> {}
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error in streaming remote inference", e)
                throw e
            }
            
            val result = resultBuilder.toString().trim()
            _streamingAssistantMessage.value = result
            
            if (nativeToolCalls.isEmpty() && result.isNotBlank()) {
                val enabledNames = PluginManager.getEnabledToolNames().map { normalizeToolName(it) }.toSet()
                parseToolCallsFromText(result)?.let { parsed ->
                    val valid = parsed.filter { (name, _) ->
                        normalizeToolName(name) in enabledNames
                    }
                    if (valid.size < parsed.size) {
                        Log.w(TAG, "Filtered out ${parsed.size - valid.size} hallucinated tool calls from fallback parsing")
                    }
                    nativeToolCalls.addAll(valid)
                    if (valid.isNotEmpty()) {
                        Log.d(TAG, "Fallback parsed ${valid.size} tool calls from remote API response")
                    }
                }
            }
            return GenerationResult(text = result, toolCalls = nativeToolCalls)
        }

        val jsonArray = JSONArray(messages)
        val resultBuilder = StringBuilder()
        val thinkResultBuilder = StringBuilder()
        val utf8Buffer = Utf8TokenBuffer()
        val nativeToolCalls = mutableListOf<Pair<String, String>>()
        currentMetrics = null
        var lastEmitTime = 0L
        var lastRepCheckLen = 0
        var repetitionTrimIndex = -1
        val thinkingActive = _thinkingModeEnabled.value
        val agentThinkParser = StreamingThinkTagParser(assumeThinking = false)

        val generationFlow = LlmModelWorker.ggufGenerateMultiTurnStreaming(jsonArray.toString(), maxTokens)

        generationFlow.collect { event ->
            when (event) {
                is GenerationEvent.Token -> {
                    val validText = utf8Buffer.append(event.text)
                    if (validText.isNotEmpty()) {
                        agentThinkParser.feed(
                            content = validText,
                            thinkingEnabled = thinkingActive,
                            onText = { text ->
                                resultBuilder.append(text)
                                val now = System.currentTimeMillis()
                                if (now - lastEmitTime >= STREAMING_THROTTLE_MS) {
                                    _streamingAssistantMessage.value = resultBuilder.toString()
                                    lastEmitTime = now
                                }
                            },
                            onThought = { thought -> thinkResultBuilder.append(thought) }
                        )
                    }

                    // Periodically check for repetition loops
                    if (repetitionTrimIndex < 0 && resultBuilder.length - lastRepCheckLen >= REPETITION_CHECK_INTERVAL) {
                        lastRepCheckLen = resultBuilder.length
                        val trimIdx = detectRepetitionTrimIndex(resultBuilder.toString())
                        if (trimIdx >= 0) {
                            Log.w(TAG, "Repetition loop detected at ~$trimIdx chars, stopping generation")
                            repetitionTrimIndex = trimIdx
                            LlmModelWorker.ggufStopGeneration()
                        }
                    }
                }
                is GenerationEvent.Done -> {
                    // Flush any remaining buffered bytes
                    val remaining = utf8Buffer.flush()
                    if (remaining.isNotEmpty()) {
                        agentThinkParser.feed(
                            content = remaining,
                            thinkingEnabled = thinkingActive,
                            onText = { resultBuilder.append(it) },
                            onThought = { thinkResultBuilder.append(it) }
                        )
                    }
                    agentThinkParser.flush(
                        onText = { resultBuilder.append(it) },
                        onThought = { thinkResultBuilder.append(it) }
                    )
                    _streamingAssistantMessage.value = resultBuilder.toString()
                    // Update context usage after generation completes
                    _contextUsagePercent.value = LlmModelWorker.getContextUsageGguf()
                }
                is GenerationEvent.Metrics -> { currentMetrics = event.metrics }
                is GenerationEvent.Progress -> { /* progress tracked elsewhere */ }
                is GenerationEvent.Error -> {
                    Log.e(TAG, "Generation error: ${event.message}")
                    throw Exception(event.message)
                }
                is GenerationEvent.ToolCall -> {
                    nativeToolCalls.add(Pair(event.name, event.args))
                    Log.d(TAG, "Native tool call received: ${event.name}")
                }
                is GenerationEvent.ToolResult -> {}
                is GenerationEvent.ThinkingBlock -> {}
                is GenerationEvent.PartialResponse -> {}
            }
        }

        var result = resultBuilder.toString().trim()

        // Trim repetitive tail if detected during streaming
        if (repetitionTrimIndex in 1 until result.length) {
            Log.d(TAG, "Trimming repetitive output: keeping ${repetitionTrimIndex} of ${result.length} chars")
            result = result.substring(0, repetitionTrimIndex).trim()
            _streamingAssistantMessage.value = result
        }

        // Fallback: if no native ToolCall events, try text parsing
        if (nativeToolCalls.isEmpty() && result.isNotBlank()) {
            val enabledNames = PluginManager.getEnabledToolNames().map { normalizeToolName(it) }.toSet()
            parseToolCallsFromText(result)?.let { parsed ->
                val valid = parsed.filter { (name, _) ->
                    normalizeToolName(name) in enabledNames
                }
                if (valid.size < parsed.size) {
                    Log.w(TAG, "Filtered out ${parsed.size - valid.size} hallucinated tool calls from fallback parsing")
                }
                nativeToolCalls.addAll(valid)
                if (valid.isNotEmpty()) {
                    Log.d(TAG, "Fallback parsed ${valid.size} tool calls from generateWithToolCalls text")
                }
            }
        }

        return GenerationResult(text = result, toolCalls = nativeToolCalls)
    }

    /** Generate with grammar and collect all tool calls from a single generation. */
    private suspend fun generateAndCollectToolCalls(
        messages: List<JSONObject>,
        maxTokens: Int
    ): List<Pair<String, String>> {
        val activeProviderType = ActiveModelSession.currentModelType.value
        if (activeProviderType == ProviderType.API) {
            val remoteCfg = getRemoteInferenceConfig() ?: throw IllegalStateException("Remote API model is missing endpoint configuration")
            
            val provider = LlmProviderResolver.resolveProvider(remoteCfg.endpoint, remoteCfg.model)
            val apiKey = LlmProviderResolver.cleanApiKey(remoteCfg.authHeader)
            val baseUrl = LlmProviderResolver.cleanBaseUrl(remoteCfg.endpoint)
            
            val chatMessages = messages.map { obj ->
                val role = obj.optString("role")
                val content = obj.optString("content")
                val participant = if (role.lowercase() == "user") Participant.USER else Participant.MODEL
                ChatMessage(text = content, participant = participant)
            }
            
            val jsonSerializer = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
            val tools = PluginManager.getEnabledToolDefinitions().map { toolDef ->
                val toolJsonString = toolDef.build().toOpenAIFormat().toString()
                jsonSerializer.decodeFromString<com.bit.api.ToolDefinition>(toolJsonString)
            }
            
            val config = ProviderConfig(
                apiKey = apiKey,
                modelId = remoteCfg.model,
                systemPrompt = null,
                baseUrl = baseUrl,
                tools = tools.takeIf { it.isNotEmpty() },
                thinkingEnabled = _thinkingModeEnabled.value,
                maxTokens = maxTokens
            )
            
            val resultBuilder = java.lang.StringBuilder()
            val nativeToolCalls = mutableListOf<Pair<String, String>>()
            var lastEmitTime = 0L
            
            try {
                provider.generateResponse(chatMessages, config).collect { event ->
                    when (event) {
                        is StreamEvent.TextChunk -> {
                            resultBuilder.append(event.text)
                            val now = System.currentTimeMillis()
                            if (now - lastEmitTime >= STREAMING_THROTTLE_MS) {
                                _streamingAssistantMessage.value = resultBuilder.toString()
                                lastEmitTime = now
                            }
                        }
                        is StreamEvent.ToolCallRequest -> {
                            nativeToolCalls.add(Pair(event.name, event.arguments))
                        }
                        is StreamEvent.ToolCallsRequest -> {
                            event.calls.forEach { call ->
                                nativeToolCalls.add(Pair(call.name, call.arguments))
                            }
                        }
                        is StreamEvent.Error -> {
                            throw IllegalStateException(event.message)
                        }
                        else -> {}
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error in generateAndCollectToolCalls remote API", e)
                throw e
            }
            
            val result = resultBuilder.toString().trim()
            val toolCalls = mutableListOf<Pair<String, String>>()
            toolCalls.addAll(nativeToolCalls)
            
            if (toolCalls.isEmpty() && result.isNotBlank()) {
                val enabledNames = PluginManager.getEnabledToolNames().map { normalizeToolName(it) }.toSet()
                parseToolCallsFromText(result)?.let { parsed ->
                    val valid = parsed.filter { (name, _) ->
                        normalizeToolName(name) in enabledNames
                    }
                    if (valid.size < parsed.size) {
                        Log.w(TAG, "Filtered out ${parsed.size - valid.size} hallucinated tool calls from fallback parsing")
                    }
                    toolCalls.addAll(valid)
                    if (valid.isNotEmpty()) {
                        Log.d(TAG, "Fallback parsed ${valid.size} tool calls from remote API response")
                    }
                }
            }
            return toolCalls
        }

        val toolCalls = mutableListOf<Pair<String, String>>()
        val textBuilder = StringBuilder()
        val thinkTextBuilder = StringBuilder()
        val jsonArray = JSONArray(messages)
        val thinkingActive = _thinkingModeEnabled.value
        val tcThinkParser = StreamingThinkTagParser(assumeThinking = false)

        LlmModelWorker.ggufGenerateMultiTurnStreaming(
            jsonArray.toString(), maxTokens
        ).collect { event ->
            when (event) {
                is GenerationEvent.Token -> {
                    tcThinkParser.feed(
                        content = event.text,
                        thinkingEnabled = thinkingActive,
                        onText = { text ->
                            textBuilder.append(text)
                            _streamingAssistantMessage.value = textBuilder.toString()
                        },
                        onThought = { thought -> thinkTextBuilder.append(thought) }
                    )
                }
                is GenerationEvent.ToolCall -> {
                    toolCalls.add(Pair(event.name, event.args))
                    Log.d(TAG, "Collected tool call: ${event.name}")
                }
                is GenerationEvent.Done -> {
                    tcThinkParser.flush(
                        onText = { textBuilder.append(it) },
                        onThought = { thinkTextBuilder.append(it) }
                    )
                    _streamingAssistantMessage.value = textBuilder.toString()
                }
                is GenerationEvent.Metrics -> { currentMetrics = event.metrics }
                is GenerationEvent.Progress -> { /* progress tracked elsewhere */ }
                is GenerationEvent.Error -> {
                    Log.e(TAG, "Generation error during tool call collection: ${event.message}")
                    throw Exception(event.message)
                }
                is GenerationEvent.ToolResult -> {}
                is GenerationEvent.ThinkingBlock -> {}
                is GenerationEvent.PartialResponse -> {}
            }
        }

        // Fallback: parse text if no ToolCall events were received
        val text = textBuilder.toString()
        if (toolCalls.isEmpty() && text.isNotBlank()) {
            Log.d(TAG, "No ToolCall events, trying text parsing fallback")
            parseToolCallsFromText(text)?.let { parsed ->
                toolCalls.addAll(parsed)
                Log.d(TAG, "Fallback parsed ${parsed.size} tool calls from text")
            }
        }

        return toolCalls
    }

    /** Parse multiple tool calls from text output (handles various formats). */
    private fun parseToolCallsFromText(text: String): List<Pair<String, String>>? {
        val results = mutableListOf<Pair<String, String>>()

        // Try: single tool call via existing parser
        tryParseToolCallFromContent(text)?.let { (name, args) ->
            results.add(Pair(name, args))
        }

        // Try: JSON array with tool_calls containing multiple entries
        if (results.isEmpty()) {
            try {
                val json = JSONObject(text.trim())
                val toolCallsArray = json.optJSONArray("tool_calls")
                if (toolCallsArray != null) {
                    for (i in 0 until toolCallsArray.length()) {
                        val call = toolCallsArray.getJSONObject(i)
                        val name = call.getString("name")
                        val args = call.getJSONObject("arguments").toString()
                        results.add(Pair(name, JSONObject().apply {
                            put("tool_calls", JSONArray().apply {
                                put(JSONObject().apply {
                                    put("name", name)
                                    put("arguments", JSONObject(args))
                                })
                            })
                        }.toString()))
                    }
                }
            } catch (e: Exception) {
                Log.d(TAG, "Multi-tool JSON parse failed: ${e.message}")
            }
        }

        return results.takeIf { it.isNotEmpty() }
    }

    /**
     * Read the system prompt from the currently loaded model's config.
     * Returns empty string if no system prompt is configured.
     */
    private suspend fun getCurrentModelSystemPrompt(userQuery: String = "", hasTools: Boolean = false): String {
        if (isLiveVoiceModeActive.value) {
            return "You are in Live Voice Mode. Respond to the user in short, highly conversational, natural sentences. Do NOT use lists, headers, bullet points, code blocks, or markdown formatting of any kind. Keep your replies concise (1-3 sentences max) and easy to understand when spoken aloud. Avoid introductory phrases like 'Here is what I found:' or restating the question. Speak naturally, as if in a face-to-face chat."
        }
        val modelId = ActiveModelSession.currentModelId.value
        val modelConfig = AppContainer.getModelRepository().getConfigByModelId(modelId)
        
        var modelSpecificPrompt: String? = null
        if (modelConfig != null) {
            val inferenceJson = modelConfig.modelInferenceParams
            if (!inferenceJson.isNullOrBlank()) {
                try {
                    val json = org.json.JSONObject(inferenceJson)
                    modelSpecificPrompt = json.optString("systemPrompt").takeIf { it.isNotBlank() }
                } catch (e: Exception) {
                    // Ignore parsing error
                }
            }
        }
        
        val basePrompt = modelSpecificPrompt ?: appSettings.globalSystemPrompt.first()
        val currentDateTime = java.util.Date()
        val sdf = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
        val dateSdf = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault())

        // ── TTFT optimization: use cached vault notes instead of re-walking filesystem ──
        val enabledNotes = getCachedVaultNotes()

        val activeMemoryText = if (enabledNotes.isNotEmpty()) {
            enabledNotes.joinToString("\n\n") { (title, body) ->
                "### Note: $title\n$body"
            }
        } else {
            ""
        }

        val runtimeValues = mapOf(
            "{${com.bit.data.PredefinedVariables.TIME}}" to sdf.format(currentDateTime),
            "{${com.bit.data.PredefinedVariables.DATE}}" to dateSdf.format(currentDateTime),
            "{${com.bit.data.PredefinedVariables.SENT_TIME}}" to sdf.format(currentDateTime),
            "{${com.bit.data.PredefinedVariables.SENT_DATE}}" to dateSdf.format(currentDateTime),
            "{${com.bit.data.PredefinedVariables.ACTIVE_MEMORY}}" to activeMemoryText
        )

        // For string replacement on the user's base prompt
        var compiledPrompt = basePrompt
        for ((key, value) in runtimeValues) {
            compiledPrompt = compiledPrompt.replace(key, value)
        }

        val skillsPrompt = if (hasTools && PluginManager.hasEnabledTools()) {
            skillManager.getSkillCatalogPrompt()
        } else {
            skillManager.getActiveSkillsPrompt()
        }
        if (skillsPrompt.isNotBlank()) {
            compiledPrompt = if (compiledPrompt.isNotBlank()) {
                "$compiledPrompt\n\n$skillsPrompt"
            } else {
                skillsPrompt
            }
        }

        return buildString {
            if (compiledPrompt.isNotEmpty()) {
                append(compiledPrompt).append("\n\n")
            }
            
            if (hasTools && PluginManager.hasEnabledTools()) {
                val toolsJsonArray = org.json.JSONArray()
                PluginManager.getEnabledToolDefinitions().forEach { toolDef ->
                    toolsJsonArray.put(toolDef.build().toOpenAIFormat())
                }
                val isQwen3 = modelId.contains("qwen3", ignoreCase = true)
                if (isQwen3) {
                    append("<tools>\n")
                    append(toolsJsonArray.toString(2))
                    append("\n</tools>\n\n")
                } else {
                    append("Tool Schema Injection:\n")
                    append("You have access to a UNION of the following tools. You MUST use them if they are relevant to the user's request. To call a tool, wrap a JSON object in <tool_call> tags like this: <tool_call>{\"name\": \"tool_name\", \"arguments\": {\"arg1\": \"value1\"}}</tool_call>\n")
                    append("<temp_tool_neuron>\nCRITICAL INSTRUCTION: You must choose one tool from the union of available tools below if the user asks for real-time data, web searches, or specific actions.\n</temp_tool_neuron>\n")
                    append("Available tools:\n")
                    append(toolsJsonArray.toString(2))
                    append("\n\n")
                }
            }
        }
    }

    /**
     * Build conversation messages for both new and existing chats.
     * @param isRegeneration when true, excludes the last user message from history
     *        and re-appends userPrompt at the end.
     */
    private suspend fun buildConversationMessages(
        userPrompt: String,
        isRegeneration: Boolean = false
    ): List<JSONObject> {
        val result = mutableListOf<JSONObject>()
        val systemPrompt = getCurrentModelSystemPrompt(userQuery = userPrompt, hasTools = false)
        if (systemPrompt.isNotEmpty()) {
            result.add(JSONObject().put("role", "system").put("content", systemPrompt))
        }
        // Always include history for regeneration (model needs context), otherwise respect setting
        if (chatMemoryEnabled.value || isRegeneration) {
            val excludeMsgId = if (isRegeneration) {
                _messages.lastOrNull { it.role == Role.User }?.msgId
            } else null

            _messages.forEach { msg ->
                if (excludeMsgId != null && msg.msgId == excludeMsgId) return@forEach
                when (msg.role) {
                    Role.User -> result.add(
                        JSONObject().put("role", "user").put("content", msg.content.content)
                    )
                    Role.Assistant -> {
                        when (msg.content.contentType) {
                            ContentType.Text -> result.add(
                                JSONObject().put("role", "assistant").put("content", msg.content.content)
                            )
                            ContentType.PluginResult -> {
                                msg.content.pluginResultData?.let { data ->
                                    result.add(JSONObject().put("role", "assistant").put("content",
                                        "Tool '${data.toolName}' result: ${data.resultData.take(1000)}"
                                    ))
                                }
                            }
                            else -> {
                                if (msg.content.content.isNotBlank()) {
                                    result.add(JSONObject().put("role", "assistant").put("content", msg.content.content))
                                }
                            }
                        }
                    }
                }
            }
        }
        result.add(JSONObject().put("role", "user").put("content", userPrompt))
        return sanitizeRoleAlternation(result)
    }

    /** Ensure no two consecutive messages share the same role (required by llama.cpp chat templates). */
    private fun sanitizeRoleAlternation(messages: List<JSONObject>): List<JSONObject> {
        if (messages.size <= 1) return messages
        val result = mutableListOf(messages.first())
        for (i in 1 until messages.size) {
            val current = messages[i]
            val previous = result.last()
            if (current.getString("role") == previous.getString("role")
                && current.getString("role") != "system") {
                // Merge: append current content to previous
                val merged = previous.getString("content") + "\n" + current.getString("content")
                result[result.lastIndex] = JSONObject()
                    .put("role", previous.getString("role"))
                    .put("content", merged)
            } else {
                result.add(current)
            }
        }
        return result
    }

    // ==================== Tool Call Parsing Utilities ====================

    /**
     * Try to extract tool name and arguments from potentially malformed JSON.
     * Returns Pair(toolName, arguments JSONObject) or null if extraction fails.
     */
    private fun extractToolCallFromArgs(toolCallName: String, toolCallArgs: String): Pair<String, JSONObject>? {
        val trimmedArgs = toolCallArgs.trim()

        // Strategy 1: Parse as valid JSON (with or without envelope structure)
        try {
            val argsObject = JSONObject(trimmedArgs)
            
            // Case A: Standard tool_calls array (e.g. {"tool_calls": [{"name": "...", "arguments": {...}}]})
            val toolCallsArray = argsObject.optJSONArray("tool_calls")
            if (toolCallsArray != null && toolCallsArray.length() > 0) {
                val firstCall = toolCallsArray.getJSONObject(0)
                return Pair(firstCall.getString("name"), firstCall.getJSONObject("arguments"))
            }
            
            // Case B: Nested arguments object (e.g. {"name": "...", "arguments": {...}} or {"arguments": {...}})
            if (argsObject.has("arguments")) {
                val nestedArgs = argsObject.optJSONObject("arguments")
                if (nestedArgs != null) {
                    val name = if (argsObject.has("name")) argsObject.getString("name") else toolCallName
                    return Pair(name, nestedArgs)
                }
            }
            
            // Case C: Direct flat arguments object (e.g. {"query": "latest news", "num_results": 10})
            // Since it is a valid JSON object but lacks the envelope structure, it is already the flat arguments object itself.
            return Pair(toolCallName, argsObject)
        } catch (e: Exception) {
            Log.d(TAG, "Strategy 1 (full JSON) failed: ${e.message}")
        }

        // Strategy 2: Regex extract the first {"name":"...","arguments":{...}} from the text
        try {
            val nameArgRegex = Regex(
                """\{\s*"name"\s*:\s*"([^"]+)"\s*,\s*"arguments"\s*:\s*(\{[^}]*\})""",
                RegexOption.DOT_MATCHES_ALL
            )
            val match = nameArgRegex.find(trimmedArgs)
            if (match != null) {
                val name = match.groupValues[1]
                val argsStr = match.groupValues[2]
                return Pair(name, JSONObject(argsStr))
            }
        } catch (e: Exception) {
            Log.d(TAG, "Strategy 2 (regex name+args) failed: ${e.message}")
        }

        // Strategy 3: Extract arguments with nested braces (handles deeper JSON)
        try {
            val nameIdx = trimmedArgs.indexOf("\"name\"")
            val argsIdx = trimmedArgs.indexOf("\"arguments\"")
            if (nameIdx >= 0 && argsIdx >= 0) {
                val nameValRegex = Regex(""""name"\s*:\s*"([^"]+)"""")
                val nameMatch = nameValRegex.find(trimmedArgs)
                val name = nameMatch?.groupValues?.get(1) ?: toolCallName

                val argsStart = trimmedArgs.indexOf('{', argsIdx)
                if (argsStart >= 0) {
                    var depth = 0
                    var argsEnd = argsStart
                    for (i in argsStart until trimmedArgs.length) {
                        when (trimmedArgs[i]) {
                            '{' -> depth++
                            '}' -> {
                                depth--
                                if (depth == 0) {
                                    argsEnd = i
                                    break
                                }
                            }
                        }
                    }
                    val argsStr = trimmedArgs.substring(argsStart, argsEnd + 1)
                    return Pair(name, JSONObject(argsStr))
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "Strategy 3 (balanced braces) failed: ${e.message}")
        }

        // Strategy 4: Find the first balanced braces block { ... } in the text and parse as direct arguments
        try {
            val startIdx = trimmedArgs.indexOf('{')
            if (startIdx >= 0) {
                var depth = 0
                var endIdx = startIdx
                for (i in startIdx until trimmedArgs.length) {
                    when (trimmedArgs[i]) {
                        '{' -> depth++
                        '}' -> {
                            depth--
                            if (depth == 0) {
                                endIdx = i
                                break
                            }
                        }
                    }
                }
                if (depth == 0 && endIdx > startIdx) {
                    val candidate = trimmedArgs.substring(startIdx, endIdx + 1)
                    val parsedJson = JSONObject(candidate)
                    // If it contains the envelope keys, recursively parse it
                    if (parsedJson.has("tool_calls") || parsedJson.has("arguments")) {
                        val nested = extractToolCallFromArgs(toolCallName, candidate)
                        if (nested != null) return nested
                    }
                    return Pair(toolCallName, parsedJson)
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "Strategy 4 (fallback brace extract) failed: ${e.message}")
        }

        Log.e(TAG, "All JSON extraction strategies failed for: ${trimmedArgs.take(200)}")
        return null
    }

    /**
     * Try to parse a tool call from generated token content.
     * Handles Qwen XML format, JSON tool_calls array, and direct JSON objects.
     */
    private fun tryParseToolCallFromContent(content: String): Pair<String, String>? {
        try {
            // Format 1: Qwen <tool_call> XML tags
            val toolCallXmlRegex = Regex(
                "<tool_call>\\s*(\\{.*?\\})\\s*</tool_call>",
                RegexOption.DOT_MATCHES_ALL
            )
            val xmlMatch = toolCallXmlRegex.find(content)
            if (xmlMatch != null) {
                val jsonStr = xmlMatch.groupValues[1]
                val json = JSONObject(jsonStr)
                val name = json.getString("name")
                val argsJson = JSONObject().apply {
                    put("tool_calls", JSONArray().apply {
                        put(JSONObject().apply {
                            put("name", name)
                            put("arguments", json.getJSONObject("arguments"))
                        })
                    })
                }.toString()
                return Pair(name, argsJson)
            }

            // Format 2: JSON with tool_calls array
            val toolCallsJsonRegex = Regex(
                "\\{\\s*\"tool_calls\"\\s*:\\s*\\[.*?\\]\\s*\\}",
                RegexOption.DOT_MATCHES_ALL
            )
            val jsonMatch = toolCallsJsonRegex.find(content)
            if (jsonMatch != null) {
                val jsonStr = jsonMatch.value
                val json = JSONObject(jsonStr)
                val toolCallsArray = json.getJSONArray("tool_calls")
                if (toolCallsArray.length() > 0) {
                    val firstCall = toolCallsArray.getJSONObject(0)
                    val name = firstCall.getString("name")
                    return Pair(name, jsonStr)
                }
            }

            // Format 3: Direct JSON object with name and arguments
            val directJsonRegex = Regex(
                "\\{\\s*\"name\"\\s*:\\s*\"([^\"]+)\"\\s*,\\s*\"arguments\"\\s*:\\s*(\\{.*?\\})\\s*\\}",
                RegexOption.DOT_MATCHES_ALL
            )
            val directMatch = directJsonRegex.find(content)
            if (directMatch != null) {
                val name = directMatch.groupValues[1]
                val argsJson = JSONObject().apply {
                    put("tool_calls", JSONArray().apply {
                        put(JSONObject().apply {
                            put("name", name)
                            put("arguments", JSONObject(directMatch.groupValues[2]))
                        })
                    })
                }.toString()
                return Pair(name, argsJson)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse tool call from content: ${e.message}")
        }
        return null
    }

    /** Normalize tool name: "Web Scraping" → "web_scraping" */
    private fun normalizeToolName(toolName: String): String {
        return toolName.lowercase().replace(" ", "_").replace("-", "_")
    }

    /** Filter out tool call syntax and code blocks from generated text. */
    private fun filterToolCallSyntax(content: String): String {
        var filtered = content
        
        // Rescue "final_answer", "answer", "text" from hallucinated JSON blocks before aggressive stripping
        try {
            val jsonBlocks = Regex("```json\\s*(\\{[^`]*\\})\\s*```", RegexOption.DOT_MATCHES_ALL).findAll(content)
            for (match in jsonBlocks) {
                try {
                    val jsonObj = org.json.JSONObject(match.groupValues[1])
                    val rescued = jsonObj.optString("final_answer").takeIf { it.isNotBlank() }
                        ?: jsonObj.optString("answer").takeIf { it.isNotBlank() }
                        ?: jsonObj.optString("text").takeIf { it.isNotBlank() }
                    if (rescued != null) {
                        filtered = filtered.replace(match.value, rescued)
                    }
                } catch (e: Exception) {}
            }
            
            val nakedJsonBlocks = Regex("(\\{\\s*\"(?:final_answer|answer|text)\"\\s*:[^}]*\\})", RegexOption.DOT_MATCHES_ALL).findAll(filtered)
            for (match in nakedJsonBlocks) {
                try {
                    val jsonObj = org.json.JSONObject(match.value)
                    val rescued = jsonObj.optString("final_answer").takeIf { it.isNotBlank() }
                        ?: jsonObj.optString("answer").takeIf { it.isNotBlank() }
                        ?: jsonObj.optString("text").takeIf { it.isNotBlank() }
                    if (rescued != null) {
                        filtered = filtered.replace(match.value, rescued)
                    }
                } catch (e: Exception) {}
            }
        } catch (e: Exception) {}

        filtered = filtered.replace(Regex("<tool_call>\\s*\\{.*?\\}\\s*</tool_call>", RegexOption.DOT_MATCHES_ALL), "")
        filtered = filtered.replace(Regex("```json\\s*\\{[^`]*```", RegexOption.DOT_MATCHES_ALL), "")
        filtered = filtered.replace(Regex("```\\s*\\{[^`]*```", RegexOption.DOT_MATCHES_ALL), "")
        filtered = filtered.replace(Regex("\\{\\s*\"tool_calls\"\\s*:[^}]*\\}\\s*", RegexOption.DOT_MATCHES_ALL), "")
        filtered = filtered.replace(Regex("\\{\\s*\"name\"\\s*:\\s*\"[^\"]+\"\\s*,\\s*\"arguments\"\\s*:\\s*\\{.*?\\}\\s*\\}", RegexOption.DOT_MATCHES_ALL), "")
        filtered = filtered.trim()
        filtered = filtered.replace(Regex("\\n{3,}"), "\n\n")
        return filtered
    }

    // ==================== Image Generation ====================

    fun sendImageRequest(
        prompt: String,
        negativePrompt: String? = null,
        steps: Int? = null,
        cfgScale: Float? = null,
        seed: Long = -1L,
        width: Int? = null,
        height: Int? = null,
        scheduler: String? = null
    ) {
        if (!LlmModelWorker.isDiffusionModelLoaded.value) {
            reportError("Please load an image generation model first")
            return
        }

        if (_isGenerating.value) return
        _isGenerating.value = true
        _currentGenerationType.value = ModelType.IMAGE_GENERATION

        viewModelScope.launch {
            try {
                val modelId = LlmModelWorker.currentDiffusionModelId.value
                if (modelId == null) {
                    reportError("Model configuration not found")
                    resetStreamingState()
                    return@launch
                }

                val config = getModelConfig(modelId)
                val inferenceParams = if (config != null) {
                    DiffusionInferenceParams.fromJson(config.modelInferenceParams)
                } else {
                    DiffusionInferenceParams()
                }
                val diffusionConfig = if (config != null) {
                    DiffusionConfig.fromJson(config.modelLoadingParams)
                } else {
                    DiffusionConfig()
                }

                val finalNegativePrompt = negativePrompt ?: inferenceParams.negativePrompt
                val finalSteps = steps ?: inferenceParams.steps
                val finalCfgScale = cfgScale ?: inferenceParams.cfgScale
                val finalWidth = width ?: diffusionConfig.width
                val finalHeight = height ?: diffusionConfig.height
                val finalScheduler = scheduler ?: inferenceParams.scheduler

                _streamingUserMessage.value = prompt
                imageGenerationStartTime = System.currentTimeMillis()
                userMessageAdded.set(false)

                if (isNewConversation) {
                    currentUserMessage = Messages(
                        msgId = "",
                        role = Role.User,
                        content = MessageContent(contentType = ContentType.Text, content = "Generate image: $prompt"),
                        modelId = LlmModelWorker.currentDiffusionModelId.value,
                            )
                    if (!userMessageAdded.get()) {
                        _messages.add(currentUserMessage!!)
                        userMessageAdded.set(true)
                    }
                    AppStateManager.setHasMessages(true)
                    generateImageForNewChat(prompt, finalNegativePrompt, finalSteps, finalCfgScale, seed, finalWidth, finalHeight, finalScheduler, inferenceParams.showDiffusionProcess, inferenceParams.showDiffusionStride)
                } else {
                    val chatId = _currentChatId.value
                    if (chatId == null) {
                        reportError("No chat selected")
                        resetStreamingState()
                        return@launch
                    }
                    
                    currentUserMessage = Messages(
                        msgId = "",
                        role = Role.User,
                        content = MessageContent(contentType = ContentType.Text, content = "Generate image: $prompt"),
                        modelId = LlmModelWorker.currentDiffusionModelId.value,
                    )
                    if (!userMessageAdded.get()) {
                        _messages.add(currentUserMessage!!)
                        userMessageAdded.set(true)
                    }
                    AppStateManager.setHasMessages(true)

                    chatManager.addUserMessage(chatId, "Generate image: $prompt").onSuccess { userMessage ->
                        currentUserMessage = userMessage
                        // Update the optimistic message with the real DB message
                        val idx = _messages.indexOfLast { it.role == Role.User && it.msgId == "" && it.content.content == "Generate image: $prompt" }
                        if (idx != -1) {
                            _messages[idx] = userMessage
                        }
                        generateImage(chatId, userMessage, prompt, finalNegativePrompt, finalSteps, finalCfgScale, seed, finalWidth, finalHeight, finalScheduler, inferenceParams.showDiffusionProcess, inferenceParams.showDiffusionStride)
                    }.onFailure { e ->
                        reportError("Failed to save message: ${e.message}")
                        resetStreamingState()
                    }
                }
            } catch (e: Exception) {
                reportError(e.message)
                resetStreamingState()
            }
        }
    }

    suspend fun getModelConfig(modelId: String): com.bit.models.table_schema.ModelConfig? {
        return AppContainer.getModelRepository().getConfigByModelId(modelId)
    }


    private fun generateImageForNewChat(
        prompt: String, negativePrompt: String, steps: Int, cfgScale: Float,
        seed: Long, width: Int, height: Int, scheduler: String,
        showDiffusionProcess: Boolean = true, showDiffusionStride: Int = 1
    ) {
        generationJob = viewModelScope.launch {
            _error.value = null
            _streamingImage.value = null
            _imageGenerationProgress.value = 0f
            currentGeneratedImage = null
            _isGenerating.value = true
            AppStateManager.setGeneratingImage()

            try {
                LlmModelWorker.generateDiffusionImage(prompt, negativePrompt, steps, cfgScale, seed, width, height, scheduler, showDiffusionProcess = showDiffusionProcess, showDiffusionStride = showDiffusionStride).collect { event ->
                    when (event) {
                        is LlmModelWorker.DiffusionGenerationEvent.Progress -> {
                            _imageGenerationProgress.value = event.progress
                            _imageGenerationStep.value = "Step ${event.currentStep}/${event.totalSteps}"
                            event.intermediateImage?.let { _streamingImage.value = it }
                        }
                        is LlmModelWorker.DiffusionGenerationEvent.Complete -> {
                            _imageGenerationProgress.value = 1f
                            _streamingImage.value = event.image
                            currentGeneratedImage = event.image
                            val generationTime = System.currentTimeMillis() - imageGenerationStartTime
                            currentImageMetrics = ImageGenerationMetrics(steps = steps, cfgScale = cfgScale, seed = event.seed, width = event.width, height = event.height, scheduler = scheduler, generationTimeMs = generationTime)
                            _isGenerating.value = false
                            val imageBase64 = LlmModelWorker.bitmapToBase64(event.image)
                            createChatWithImageMessage("Generate image: $prompt", imageBase64, prompt, event.seed)
                        }
                        is LlmModelWorker.DiffusionGenerationEvent.Error -> {
                            handleImageGenerationError(prompt, event.message)
                        }
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                handleImageGenerationException(prompt, e)
            }
        }
    }

    private fun generateImage(
        chatId: String, userMessage: Messages, prompt: String, negativePrompt: String,
        steps: Int, cfgScale: Float, seed: Long, width: Int, height: Int, scheduler: String,
        showDiffusionProcess: Boolean = true, showDiffusionStride: Int = 1
    ) {
        generationJob = viewModelScope.launch {
            _error.value = null
            _streamingImage.value = null
            _imageGenerationProgress.value = 0f
            _isGenerating.value = true
            AppStateManager.setGeneratingImage()

            try {
                LlmModelWorker.generateDiffusionImage(prompt, negativePrompt, steps, cfgScale, seed, width, height, scheduler, showDiffusionProcess = showDiffusionProcess, showDiffusionStride = showDiffusionStride).collect { event ->
                    when (event) {
                        is LlmModelWorker.DiffusionGenerationEvent.Progress -> {
                            _imageGenerationProgress.value = event.progress
                            _imageGenerationStep.value = "Step ${event.currentStep}/${event.totalSteps}"
                            event.intermediateImage?.let { _streamingImage.value = it }
                        }
                        is LlmModelWorker.DiffusionGenerationEvent.Complete -> {
                            _imageGenerationProgress.value = 1f
                            _streamingImage.value = event.image
                            _isGenerating.value = false
                            val generationTime = System.currentTimeMillis() - imageGenerationStartTime
                            currentImageMetrics = ImageGenerationMetrics(steps = steps, cfgScale = cfgScale, seed = event.seed, width = event.width, height = event.height, scheduler = scheduler, generationTimeMs = generationTime)
                            if (!userMessageAdded.get()) { _messages.add(userMessage); userMessageAdded.set(true) }
                            val imageBase64 = LlmModelWorker.bitmapToBase64(event.image)
                            val imageMessage = Messages(
                                role = Role.Assistant,
                                content = MessageContent(contentType = ContentType.Image, content = "Generated image for: $prompt", imageData = imageBase64, imagePrompt = prompt, imageSeed = event.seed),
                                modelId = LlmModelWorker.currentDiffusionModelId.value,
                                            imageMetrics = currentImageMetrics
                            )
                            _messages.add(imageMessage)
                            chatManager.addImageMessage(chatId, imageBase64, prompt, event.seed, currentImageMetrics)
                            AppStateManager.setGenerationComplete()
                            AppStateManager.chatRefreshed()
                            resetStreamingState()
                        }
                        is LlmModelWorker.DiffusionGenerationEvent.Error -> {
                            handleImageGenerationErrorExisting(chatId, userMessage, prompt, event.message)
                        }
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                handleImageGenerationExceptionExisting(chatId, userMessage, prompt, e)
            }
        }
    }

    // ==================== Helper Functions ====================

    private suspend fun createChatWithMessages(
        userPrompt: String,
        assistantResponse: String,
        metrics: DecodingMetrics?,
        toolChainSteps: List<ToolChainStepData>? = null
    ) {
        val filteredResponse = filterToolCallSyntax(assistantResponse)
        val ragResultItems = _currentRagResults.value.takeIf { it.isNotEmpty() }?.map { result ->
            RagResultItem(ragName = result.ragName, content = result.content, score = result.score, nodeId = result.nodeId)
        }
        val pluginResults = _messages.filter { it.content.contentType == ContentType.PluginResult }

        chatManager.createNewChat().onSuccess { newChatId ->
            _currentChatId.value = newChatId
            val userMsg = currentUserMessage?.let {
                if (it.msgId.isEmpty()) it.copy(msgId = java.util.UUID.randomUUID().toString()) else it
            } ?: Messages(
                role = Role.User,
                content = MessageContent(contentType = ContentType.Text, content = userPrompt),
                modelId = currentModelId,
            )
            chatManager.addMessage(newChatId, userMsg).onSuccess {
                pluginResults.forEach { pluginMsg ->
                    chatManager.addMessage(newChatId, pluginMsg)
                }
                if (filteredResponse.isNotBlank()) {
                    val assistantMsg = Messages(
                        role = Role.Assistant,
                        content = MessageContent(contentType = ContentType.Text, content = filteredResponse),
                        modelId = currentModelId,
                            decodingMetrics = metrics,
                        ragResults = ragResultItems,
                        toolChainSteps = toolChainSteps
                    )
                    chatManager.addMessage(newChatId, assistantMsg)
                }
                chatManager.getChatMessages(newChatId).onSuccess { loadedMessages ->
                    _messages.clear()
                    _messages.addAll(loadedMessages)
                    AppStateManager.setGenerationComplete()
                    AppStateManager.chatRefreshed()
                    val spokenMsgId = loadedMessages.lastOrNull { it.role == Role.Assistant }?.msgId
                    resetStreamingState()
                    viewModelScope.launch { autoSpeakIfEnabled(filteredResponse, spokenMsgId) }
                    generateChatTitleAsync(newChatId, userPrompt, filteredResponse)
                }.onFailure {
                    AppStateManager.setGenerationComplete()
                    resetStreamingState()
                }
            }.onFailure { e ->
                reportError("Failed to save chat: ${e.message}")
            }
        }.onFailure { e ->
            reportError("Failed to create chat: ${e.message}")
        }
    }

    private suspend fun createChatWithImageMessage(
        userPrompt: String, imageBase64: String, imagePrompt: String, seed: Long
    ) {
        val diffusionModelId = LlmModelWorker.currentDiffusionModelId.value
        chatManager.createNewChat().onSuccess { newChatId ->
            _currentChatId.value = newChatId
            val userMsg = Messages(
                role = Role.User,
                content = MessageContent(contentType = ContentType.Text, content = userPrompt),
                modelId = diffusionModelId,
                )
            chatManager.addMessage(newChatId, userMsg).onSuccess { userMessage ->
                _messages.add(userMessage)
                userMessageAdded.set(true)
                val imageMsg = Messages(
                    role = Role.Assistant,
                    content = MessageContent(
                        contentType = ContentType.Image,
                        content = "Generated image for: $imagePrompt",
                        imageData = imageBase64,
                        imagePrompt = imagePrompt,
                        imageSeed = seed
                    ),
                    modelId = diffusionModelId,
                    imageMetrics = currentImageMetrics
                )
                chatManager.addMessage(newChatId, imageMsg).onSuccess { imageMessage ->
                    _messages.add(imageMessage)
                    AppStateManager.setGenerationComplete()
                    AppStateManager.chatRefreshed()
                    resetStreamingState()
                    generateChatTitleAsync(newChatId, userPrompt, "Generated image for: $imagePrompt")
                }
            }.onFailure { e ->
                reportError("Failed to save chat: ${e.message}")
            }
        }.onFailure { e ->
            reportError("Failed to create chat: ${e.message}")
        }
    }

    private fun generateChatTitleAsync(chatId: String, userPrompt: String, assistantResponse: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val providerType = ActiveModelSession.currentModelType.value ?: return@launch
            val summaryText = "User: $userPrompt\nAssistant: ${assistantResponse.take(500)}"
            val userMsg = "Generate a short title (5 words maximum) for this conversation:\n\n$summaryText\n\nRespond with ONLY the title text, no quotes, no punctuation, no explanation."
            val systemMsg = "You are a title generator. Output only a short title in the same language as the conversation."

            var generatedTitle = ""

            try {
                if (providerType == ProviderType.API) {
                    val remoteCfg = getRemoteInferenceConfig() ?: return@launch
                    val provider = LlmProviderResolver.resolveProvider(remoteCfg.endpoint, remoteCfg.model)
                    val apiKey = LlmProviderResolver.cleanApiKey(remoteCfg.authHeader)
                    
                    val titlePrompt = listOf(
                        com.bit.api.ChatMessage(
                            text = userMsg,
                            participant = com.bit.api.Participant.USER,
                            status = com.bit.api.MessageStatus.SUCCESS
                        )
                    )
                    
                    val config = ProviderConfig(
                        apiKey = apiKey,
                        modelId = remoteCfg.model,
                        systemPrompt = systemMsg,
                        maxContextWindow = 1,
                        thinkingEnabled = false,
                        baseUrl = LlmProviderResolver.cleanBaseUrl(remoteCfg.endpoint)
                    )
                    
                    val titleBuilder = StringBuilder()
                    provider.generateResponse(titlePrompt, config).collect { event ->
                        if (event is StreamEvent.TextChunk) {
                            titleBuilder.append(event.text)
                        } else if (event is StreamEvent.Error) {
                            Log.e(TAG, "Title generation error from provider: ${event.message}")
                        }
                    }
                    generatedTitle = titleBuilder.toString()
                } else if (providerType == ProviderType.GGUF) {
                    val messages = JSONArray().apply {
                        put(JSONObject().put("role", "system").put("content", systemMsg))
                        put(JSONObject().put("role", "user").put("content", userMsg))
                    }
                    PluginManager.clearGrammar()
                    
                    val textBuilder = java.lang.StringBuilder()
                    LlmModelWorker.ggufGenerateMultiTurnStreaming(messages.toString(), maxTokens = 60).collect { event ->
                        if (event is GenerationEvent.Token) {
                            textBuilder.append(event.text)
                        }
                    }
                    generatedTitle = textBuilder.toString()
                    PluginManager.restoreGrammar()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to generate title: ${e.message}", e)
                return@launch
            }

            var cleanTitle = generatedTitle
                .replace(Regex("<think>[\\s\\S]*?(?:</think>|$)", RegexOption.IGNORE_CASE), "")
                .replace(Regex("^[\\s\\S]*?</think>", RegexOption.IGNORE_CASE), "")
                .trim()
                .replace("\n", " ")
                .replace("\"", "")
            
            if (cleanTitle.endsWith(".")) {
                cleanTitle = cleanTitle.dropLast(1)
            }
            cleanTitle = cleanTitle.take(60).trim()

            if (cleanTitle.isNotBlank()) {
                chatManager.updateChatTitle(chatId, cleanTitle).onSuccess {
                    Log.d(TAG, "Successfully generated and saved title: '$cleanTitle'")
                    AppStateManager.chatRefreshed()
                }
            }
        }
    }

    // ==================== Error Handlers ====================

    private fun handleImageGenerationError(prompt: String, errorMessage: String) {
        _isGenerating.value = false
        reportError(errorMessage)
        resetStreamingState()
    }

    private fun handleImageGenerationException(prompt: String, exception: Exception) {
        _isGenerating.value = false
        reportError(exception.message)
        resetStreamingState()
    }

    private fun handleImageGenerationErrorExisting(chatId: String, userMessage: Messages, prompt: String, errorMessage: String) {
        _isGenerating.value = false
        reportError(errorMessage)
        if (!userMessageAdded.get()) { _messages.add(userMessage); userMessageAdded.set(true) }
        _messages.add(Messages(role = Role.Assistant, content = MessageContent(contentType = ContentType.Text, content = "Error generating image: $errorMessage")))
        resetStreamingState()
    }

    private fun handleImageGenerationExceptionExisting(chatId: String, userMessage: Messages, prompt: String, exception: Exception) {
        _isGenerating.value = false
        reportError(exception.message)
        if (!userMessageAdded.get()) { _messages.add(userMessage); userMessageAdded.set(true) }
        resetStreamingState()
    }

    private fun reportError(message: String?) {
        val msg = message ?: "Unknown error"
        _error.value = msg
        AppStateManager.setError(msg)
    }

    private fun resetStreamingState() {
        _isGenerating.value = false
        _streamingUserMessage.value = null
        _streamingAssistantMessage.value = ""
        _streamingImage.value = null
        _imageGenerationProgress.value = 0f
        _imageGenerationStep.value = ""
        currentUserMessage = null
        currentGeneratedImage = null
        currentMetrics = null
        currentImageMetrics = null
        userMessageAdded.set(false)
        _toolChainSteps.value = emptyList()
        _currentToolChainRound.value = 0
        _agentPhase.value = AgentPhase.Idle
        _agentPlan.value = null
        _agentSummary.value = null
        _currentRagContext.value = null
        _currentRagResults.value = emptyList()
    }

    // ==================== Generation Control ====================

    fun stop() {
        if (TTSManager.isPlaying.value) { TTSManager.stopPlayback() }

        val currentGenType = _currentGenerationType.value
        _isGenerating.value = false

        // 1. Snapshot mutable state BEFORE cancellation
        val snapshotChatId = _currentChatId.value
        val snapshotUserMsg = currentUserMessage
        val snapshotContent = _streamingAssistantMessage.value
        val snapshotMetrics = currentMetrics
        val snapshotImage = currentGeneratedImage
        val snapshotImageMetrics = currentImageMetrics
        val snapshotUserAdded = userMessageAdded.get()

        // 2. Stop native generation (signal to engine)
        when (currentGenType) {
            ModelType.TEXT_GENERATION -> LlmModelWorker.ggufStopGeneration()
            ModelType.IMAGE_GENERATION -> LlmModelWorker.stopDiffusionGeneration()
            ModelType.AUDIO_GENERATION -> stopTTS()
        }

        // 3. Cancel the coroutine job
        generationJob?.cancel()
        generationJob = null

        // 4. Persist partial results asynchronously off the Main thread to avoid UI thread blocking
        viewModelScope.launch(Dispatchers.IO) {
            when (currentGenType) {
                ModelType.TEXT_GENERATION -> handleTextStop(
                    snapshotChatId, snapshotUserMsg, snapshotContent, snapshotMetrics, snapshotUserAdded
                )
                ModelType.IMAGE_GENERATION -> handleImageStop(
                    snapshotChatId, snapshotUserMsg, snapshotImage, snapshotImageMetrics, snapshotUserAdded
                )
                else -> resetStreamingState()
            }
            AppStateManager.setGenerationComplete()
        }
    }

    private fun handleTextStop(
        chatId: String?,
        userMsg: Messages?,
        content: String,
        metrics: DecodingMetrics?,
        wasUserAdded: Boolean
    ) {
        // Pre-process the content to forcefully close any unclosed thinking tags
        var processedContent = content
        val openTags = listOf(
            "<think>" to "</think>",
            "[THINK]" to "[/THINK]",
            "<reasoning>" to "</reasoning>",
            "<|channel>thought" to "<|channel>"
        )
        
        var unclosedIndex = -1
        var expectedCloseTag = ""
        
        for ((open, close) in openTags) {
            val lastOpen = processedContent.lastIndexOf(open, ignoreCase = true)
            if (lastOpen != -1) {
                val lastClose = processedContent.lastIndexOf(close, ignoreCase = true)
                if (lastClose < lastOpen) {
                    // Tag is open!
                    if (lastOpen > unclosedIndex) {
                        unclosedIndex = lastOpen
                        expectedCloseTag = close
                    }
                }
            }
        }
        
        if (unclosedIndex != -1) {
            processedContent += expectedCloseTag
        }
        
        processedContent += "\n\n_Stopped_"
        
        val cleanContent = processedContent.trim()
        if (chatId != null && userMsg != null && cleanContent.isNotEmpty()) {
            if (!wasUserAdded && !_messages.contains(userMsg)) {
                _messages.add(userMsg)
            }

            val lastMsg = _messages.lastOrNull()
            if (lastMsg != null && lastMsg.role == Role.Assistant && lastMsg.content.contentType == ContentType.Text) {
                // Assistant message was already added during generation, update content cleanly
                if (lastMsg.content.content != cleanContent) {
                    val updatedMsg = lastMsg.copy(content = lastMsg.content.copy(content = cleanContent))
                    val idx = _messages.indexOf(lastMsg)
                    if (idx >= 0) {
                        _messages[idx] = updatedMsg
                    }
                    viewModelScope.launch { chatManager.addMessage(chatId, updatedMsg) }
                }
            } else {
                val assistantMessage = Messages(
                    role = Role.Assistant,
                    content = MessageContent(contentType = ContentType.Text, content = cleanContent),
                    modelId = currentModelId,
                    decodingMetrics = metrics
                )
                _messages.add(assistantMessage)
                regenerationSnapshot?.let { old ->
                    regenerationSnapshot = null
                    viewModelScope.launch { chatManager.deleteMessage(old.msgId) }
                }
                viewModelScope.launch { chatManager.addMessage(chatId, assistantMessage) }
            }
        } else if (regenerationSnapshot != null) {
            restoreRegenerationSnapshot()
        } else if (userMsg != null && !wasUserAdded && !_messages.contains(userMsg)) {
            _messages.add(userMsg)
        }

        try { PluginManager.restoreGrammar() } catch (_: Exception) {}
        resetStreamingState()
    }

    private fun handleImageStop(
        chatId: String?,
        userMsg: Messages?,
        image: Bitmap?,
        imgMetrics: ImageGenerationMetrics?,
        wasUserAdded: Boolean
    ) {
        if (chatId != null && userMsg != null && image != null) {
            if (!wasUserAdded) { _messages.add(userMsg) }
            val imageBase64 = LlmModelWorker.bitmapToBase64(image)
            val imageMessage = Messages(
                role = Role.Assistant,
                content = MessageContent(contentType = ContentType.Image, content = "Image generation stopped", imageData = imageBase64),
                modelId = LlmModelWorker.currentDiffusionModelId.value,
                imageMetrics = imgMetrics
            )
            _messages.add(imageMessage)
            // Persist to DB async
            viewModelScope.launch { chatManager.addMessage(chatId, imageMessage) }
        } else if (userMsg != null && !wasUserAdded) {
            _messages.add(userMsg)
        }

        resetStreamingState()
    }

    // ==================== TTS Controls ====================

    private fun stripThinkingTags(text: String): String {
        // Remove complete thinking blocks
        var cleaned = text.replace(Regex("<think>(.*?)</think>|\\[THINK](.*?)\\[/THINK]|<reasoning>(.*?)</reasoning>|<\\|channel>thought(.*?)(?:<channel\\|>|<\\|channel\\|>)", RegexOption.DOT_MATCHES_ALL), "")
        // Also strip orphaned closing tags (Qwen3-style: no opening tag, just </think>)
        cleaned = cleaned.replace(Regex("^[\\s\\S]*?</think>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)), "")
        
        // Handle partial/in-progress thinking blocks
        val openTags = listOf("<|channel>thought", "<think>", "[THINK]", "<reasoning>")
        for (tag in openTags) {
            val openIdx = cleaned.indexOf(tag, ignoreCase = true)
            if (openIdx >= 0) {
                cleaned = cleaned.substring(0, openIdx)
            }
        }
        return cleaned.trim()
    }

    private suspend fun autoSpeakIfEnabled(text: String, msgId: String? = null) {
        val cleanedText = stripThinkingTags(text)
        if (cleanedText.isBlank()) return
        val settings = ttsDataStore.settings.first()
        if (!settings.autoSpeak) return

        if (!TTSManager.isLoaded()) {
            val modelDir = TTSManager.getModelDirectory() ?: return
            withContext(Dispatchers.IO) {
                TTSManager.loadModel(modelDir, settings.useNNAPI)
            }
            if (!TTSManager.isLoaded()) return
        }

        TTSManager.speak(text = cleanedText, settings = settings, msgId = msgId)
    }

    fun speakMessage(message: Messages) {
        if (message.content.contentType != ContentType.Text) return
        val text = message.content.content
        val cleanedText = stripThinkingTags(text)
        if (cleanedText.isBlank()) return

        viewModelScope.launch {
            if (!TTSManager.isLoaded()) {
                val modelDir = TTSManager.getModelDirectory()
                if (modelDir == null) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(appContext, "Download the TTS voice model from Model Store to enable speech", Toast.LENGTH_LONG).show()
                    }
                    return@launch
                }
                val settings = ttsDataStore.settings.first()
                withContext(Dispatchers.IO) { TTSManager.loadModel(modelDir, settings.useNNAPI) }
                if (!TTSManager.isLoaded()) return@launch
            }
            val settings = ttsDataStore.settings.first()
            TTSManager.speak(text = cleanedText, settings = settings, msgId = message.msgId)
        }
    }

    fun stopTTS() {
        TTSManager.stopPlayback()
    }

    // ==================== UI Controls ====================

    fun clearMessages() {
        _messages.clear()
        _promptEditState.value = null
        resetStreamingState()
        _error.value = null
        AppStateManager.setHasMessages(false)
    }

    fun clearError() {
        _error.value = null
        AppStateManager.clearError()
    }

    fun deleteMessage(messageId: String) {
        viewModelScope.launch {
            chatManager.deleteMessage(messageId).onSuccess {
                _messages.removeIf { it.msgId == messageId }
                if (_promptEditState.value?.messageId == messageId) {
                    _promptEditState.value = null
                }
            }.onFailure { e ->
                reportError("Failed to delete message: ${e.message}")
            }
        }
    }

    fun startEditingPrompt(message: Messages) {
        if (_isGenerating.value) return
        if (message.content.contentType != ContentType.Text && message.content.contentType != ContentType.TextWithImage) return

        _promptEditState.value = PromptEditState(
            messageId = message.msgId,
            initialText = message.content.content
        )
    }

    fun cancelPromptEdit() {
        _promptEditState.value = null
    }

    fun applyPromptEdit(newText: String) {
        val editState = _promptEditState.value ?: return
        val chatId = _currentChatId.value ?: return
        val normalized = newText.trim()
        if (normalized.isBlank()) return

        viewModelScope.launch {
            val msgIndex = _messages.indexOfFirst { it.msgId == editState.messageId }
            if (msgIndex < 0) {
                _promptEditState.value = null
                return@launch
            }

            val existing = _messages[msgIndex]
            if (existing.content.contentType != ContentType.Text && existing.content.contentType != ContentType.TextWithImage) {
                _promptEditState.value = null
                return@launch
            }

            val updated = existing.copy(
                content = existing.content.copy(content = normalized)
            )

            chatManager.updateMessage(chatId, updated).onSuccess {
                _promptEditState.value = null

                if (updated.role == Role.User) {
                    // Identify and delete all subsequent messages in UI list and DB
                    val messagesToDelete = _messages.subList(msgIndex + 1, _messages.size).toList()
                    messagesToDelete.forEach { msg ->
                        chatManager.deleteMessage(msg.msgId)
                    }

                    // Keep only messages up to the edited one
                    val keptMessages = _messages.take(msgIndex + 1).toMutableList()
                    keptMessages[msgIndex] = updated

                    _messages.clear()
                    _messages.addAll(keptMessages)

                    // Trigger regeneration
                    regenerateLastMessage()
                } else {
                    _messages[msgIndex] = updated
                }
            }.onFailure { e ->
                reportError("Failed to edit prompt: ${e.message}")
            }
        }
    }

    fun foldOlderMessages() {
        val chatId = _currentChatId.value ?: return
        if (_messages.size < 6) return
        if (_isGenerating.value) return

        viewModelScope.launch {
            _isGenerating.value = true
            _streamingAssistantMessage.value = "Summarizing older messages..."

            try {
                val keepCount = 4
                val foldCount = _messages.size - keepCount
                val toFold = _messages.take(foldCount)
                val remaining = _messages.drop(foldCount)

                val historyText = toFold.joinToString("\n") { msg ->
                    val roleStr = when (msg.role) {
                        Role.User -> "User"
                        Role.Assistant -> "Assistant"
                    }
                    "$roleStr: ${msg.content.content}"
                }

                val summaryPrompt = "Summarize the following conversation history concisely, preserving all key details, user preferences, and decisions:\n\n$historyText\n\nConcise Summary:"
                val ggufMsg = JSONObject().put("role", "user").put("content", summaryPrompt)
                val ggufList = listOf(
                    JSONObject().put("role", "system").put("content", "You are a concise summarizer."),
                    ggufMsg
                )

                val summaryResult = generatePlainText(ggufList, 1024)
                val summary = summaryResult.trim().takeIf { it.isNotBlank() } ?: "Summary of earlier messages."

                val summaryMessage = Messages(
                    role = Role.Assistant,
                    content = MessageContent(
                        contentType = ContentType.Text,
                        content = "[Conversation Summary of earlier messages]:\n$summary"
                    )
                )

                for (msg in toFold) {
                    chatManager.deleteMessage(msg.msgId)
                }

                chatManager.addMessage(chatId, summaryMessage)

                _messages.clear()
                _messages.add(summaryMessage)
                _messages.addAll(remaining)

                _streamingAssistantMessage.value = ""
            } catch (e: Exception) {
                reportError("Failed to fold older messages: ${e.message}")
            } finally {
                _isGenerating.value = false
            }
        }
    }

    fun showDynamicWindow() {
        _showDynamicWindow.value = _showDynamicWindow.value.not()
    }

    fun hideDynamicWindow() {
        _showDynamicWindow.value = false
    }

    fun showModelList() {
        _showModelList.value = true
    }

    fun hideModelList() {
        _showModelList.value = false
    }

    // ── Lifecycle ──

    override fun onCleared() {
        super.onCleared()
        generationJob?.cancel()
        generationJob = null
    }

    // ── UTF-8 Token Buffer ──

    /**
     * Buffers incomplete UTF-8 byte sequences from streaming tokens.
     * Some models emit tokens that split multi byte characters (e.g. Turkish ş, emoji)
     * across multiple callbacks. This buffer holds trailing incomplete bytes until
     * the next token completes the character.
     */
    private class Utf8TokenBuffer {
        private val pending = ByteArray(4) // Max UTF-8 char is 4 bytes
        private var pendingLen = 0

        fun append(token: String): String {
            if (token.isEmpty()) return ""
            val bytes = token.toByteArray(Charsets.UTF_8)

            // Prepend any pending bytes from last call
            val combined = if (pendingLen > 0) {
                ByteArray(pendingLen + bytes.size).also {
                    pending.copyInto(it, 0, 0, pendingLen)
                    bytes.copyInto(it, pendingLen)
                }
            } else bytes

            // Find last complete UTF-8 character boundary
            val completeLen = findCompleteUtf8Length(combined)
            pendingLen = combined.size - completeLen
            if (pendingLen > 0) {
                combined.copyInto(pending, 0, completeLen, combined.size)
            }

            return if (completeLen > 0) String(combined, 0, completeLen, Charsets.UTF_8) else ""
        }

        fun flush(): String {
            if (pendingLen == 0) return ""
            // Force-decode whatever is left (replacement chars for truly invalid bytes)
            val result = String(pending, 0, pendingLen, Charsets.UTF_8)
            pendingLen = 0
            return result
        }

        private fun findCompleteUtf8Length(bytes: ByteArray): Int {
            if (bytes.isEmpty()) return 0
            // Walk backwards from end to find if the last char is incomplete
            var i = bytes.size - 1
            // Skip continuation bytes (10xxxxxx)
            while (i >= 0 && bytes[i].toInt() and 0xC0 == 0x80) i--
            if (i < 0) return 0 // All continuation bytes — all incomplete

            val leadByte = bytes[i].toInt() and 0xFF
            val expectedLen = when {
                leadByte and 0x80 == 0 -> 1    // 0xxxxxxx
                leadByte and 0xE0 == 0xC0 -> 2 // 110xxxxx
                leadByte and 0xF0 == 0xE0 -> 3 // 1110xxxx
                leadByte and 0xF8 == 0xF0 -> 4 // 11110xxx
                else -> 1 // Invalid lead byte, treat as single
            }

            val actualLen = bytes.size - i
            return if (actualLen >= expectedLen) bytes.size else i
        }
    }

    fun saveMessageToMemoryVault(messageText: String, title: String? = null) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val savedNote = aiMemoryWriter.saveAiMemory(text = messageText, title = title)
                Log.d("ChatViewModel", "Saved AI memory to: ${savedNote.filePath}")

                withContext(Dispatchers.Main) {
                    Toast.makeText(appContext, "Saved to AI memory", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Log.e("ChatViewModel", "Failed to save message to Memory Vault", e)
            }
        }
    }

    companion object {
        private const val TAG = "ChatViewModel"
        private const val PLAN_MAX_TOKENS = 150
        private const val SUMMARY_MAX_TOKENS = 512
        private const val STREAMING_THROTTLE_MS = 16L
        private const val REPETITION_CHECK_INTERVAL = 200
        private const val REPETITION_MIN_PATTERN_LEN = 30
        private const val REPETITION_MIN_REPEATS = 4
        private const val REPETITION_MAX_CHECK_LEN = 800
    }
}
