package com.bit.worker

import java.io.File
import com.bit.util.VlmPaths
import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.bit.engine.GenerationEvent
import com.bit.models.enums.ProviderType
import com.bit.models.table_schema.Model
import com.bit.models.table_schema.ModelConfig
import com.bit.service.IDiffusionGenerationCallback
import com.bit.service.IGgufGenerationCallback
import com.bit.service.ILLMService
import com.bit.service.IModelLoadCallback
import com.bit.service.LLMService
import com.bit.models.engine_schema.DecodingMetrics
import com.bit.global.AppPaths
import com.bit.data.AppSettingsDataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import java.util.Base64
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

@SuppressLint("StaticFieldLeak")
object LlmModelWorker {

    private const val TAG = "LlmModelWorker"

    // ── Service Binding ──
    private val _serviceFlow = MutableStateFlow<ILLMService?>(null)
    private var boundContext: Context? = null
    @Volatile private var isBinding = false

    // Session recovery tracking
    @Volatile private var lastLoadedModel: Model? = null
    @Volatile private var lastLoadedConfig: ModelConfig? = null

    // GGUF state
    private val _isGgufModelLoaded = MutableStateFlow(false)
    val isGgufModelLoaded: StateFlow<Boolean> = _isGgufModelLoaded.asStateFlow()

    // Diffusion state
    private val _isDiffusionModelLoaded = MutableStateFlow(false)
    val isDiffusionModelLoaded: StateFlow<Boolean> = _isDiffusionModelLoaded.asStateFlow()

    private val _diffusionBackendState = MutableStateFlow("Idle")
    val diffusionBackendState: StateFlow<String> = _diffusionBackendState.asStateFlow()

    private val _currentGgufModelId = MutableStateFlow<String?>(null)
    val currentGgufModelId: StateFlow<String?> = _currentGgufModelId.asStateFlow()

    private val _currentDiffusionModelId = MutableStateFlow<String?>(null)
    val currentDiffusionModelId: StateFlow<String?> = _currentDiffusionModelId.asStateFlow()

    fun setCurrentGgufModelId(id: String?) { _currentGgufModelId.value = id }
    fun setCurrentDiffusionModelId(id: String?) { _currentDiffusionModelId.value = id }


    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            _serviceFlow.value = ILLMService.Stub.asInterface(binder)
            isBinding = false
            Log.i(TAG, "Service connected to inference process")
            restoreSessionAfterCrash()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            _serviceFlow.value = null
            isBinding = false
            Log.w(TAG, "Inference process disconnected unexpectedly (crashed or killed)")
            
            _isGgufModelLoaded.value = false
            com.bit.state.AppStateManager.setError("AI engine process crashed. Recovering session...")
            triggerAutoRecovery()
        }

        override fun onBindingDied(name: ComponentName?) {
            _serviceFlow.value = null
            isBinding = false
            Log.e(TAG, "Service binding died")
            
            _isGgufModelLoaded.value = false
            com.bit.state.AppStateManager.setError("AI engine process crashed. Recovering session...")
            triggerAutoRecovery()
        }

        override fun onNullBinding(name: ComponentName?) {
            _serviceFlow.value = null
            isBinding = false
            Log.e(TAG, "Service returned null binding")
        }
    }

    fun bindService(context: Context) {
        synchronized(this) {
            if (_serviceFlow.value != null) {
                Log.w(TAG, "Service already bound and connected")
                return
            }

            if (isBinding) {
                Log.w(TAG, "Service binding already in progress")
                return
            }

            isBinding = true
        }

        val appContext = context.applicationContext
        val intent = Intent(appContext, LLMService::class.java)

        val bound = appContext.bindService(
            intent,
            connection,
            Context.BIND_AUTO_CREATE or Context.BIND_IMPORTANT
        )

        if (bound) {
            boundContext = appContext
            Log.i(TAG, "Service binding initiated")
        } else {
            isBinding = false
            Log.e(TAG, "Failed to bind service")
        }
    }

    fun unbindService() {
        try {
            boundContext?.unbindService(connection)
            Log.i(TAG, "Service unbound")
        } catch (e: Exception) {
            Log.e(TAG, "Error unbinding service", e)
        } finally {
            _serviceFlow.value = null
            boundContext = null
            isBinding = false
            _isGgufModelLoaded.value = false
            _isDiffusionModelLoaded.value = false
        }
    }

    private suspend fun ensureServiceBound(): ILLMService {
        return withTimeout(10_000) {
            _serviceFlow.first { it != null }!!
        }
    }

    /** Wait for the LLM service to be bound (no return value). */
    suspend fun ensureServiceReady() {
        withTimeout(10_000) { _serviceFlow.first { it != null } }
    }

    private fun triggerAutoRecovery() {
        val context = boundContext ?: return
        Log.i(TAG, "Triggering auto-recovery Service bind...")
        kotlinx.coroutines.CoroutineScope(Dispatchers.Main).launch {
            kotlinx.coroutines.delay(1000)
            bindService(context)
        }
    }

    private fun restoreSessionAfterCrash() {
        val model = lastLoadedModel
        val config = lastLoadedConfig
        if (model != null && config != null) {
            Log.i(TAG, "Attempting to auto-restore crashed model session: ${model.modelName}")
            kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
                val context = boundContext ?: return@launch
                try {
                    com.bit.state.AppStateManager.setLoadingModel(model.modelName)
                    val success = loadGgufModel(model, config)
                    if (success) {
                        // Restore performance optimizations on restart
                        try {
                            val cacheDir = AppPaths.promptCache(context).also { it.mkdirs() }
                            setPromptCacheDirGguf(cacheDir.absolutePath)
                            val speedMode = AppSettingsDataStore(context).speedModeEnabled.first()
                            setSpeculativeDecodingGguf(speedMode)
                            warmUpGguf()
                            Log.d(TAG, "Optimizations re-applied after crash recovery")
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed to re-apply optimizations on recovery: ${e.message}")
                        }

                        com.bit.state.AppStateManager.setModelLoaded(model.modelName)
                        Log.i(TAG, "Auto-recovery successful for model: ${model.modelName}")
                    } else {
                        com.bit.state.AppStateManager.setError("Failed to auto-restore model after crash")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Auto-recovery failed", e)
                    com.bit.state.AppStateManager.setError("Auto-recovery failed: ${e.message}")
                }
            }
        }
    }

    /**
     * Diagnostic simulation of a native engine crash.
     * Tells the service to exit its process immediately.
     */
    fun simulateEngineCrash() {
        val svc = _serviceFlow.value ?: return
        try {
            svc.simulateProcessCrash()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send crash simulation signal", e)
        }
    }

    // ── Shared GGUF Callback Factory ──

    private fun createGgufCallback(
        scope: ProducerScope<GenerationEvent>
    ): IGgufGenerationCallback.Stub = object : IGgufGenerationCallback.Stub() {
        override fun onToken(token: String) {
            scope.trySend(GenerationEvent.Token(token))
        }

        override fun onToolCall(name: String, args: String) {
            scope.trySend(GenerationEvent.ToolCall(name, args))
        }

        override fun onMetrics(
            tps: Float, ttftMs: Float, totalMs: Float,
            tokensEvaluated: Int, tokensPredicted: Int,
            modelMB: Float, ctxMB: Float, peakMB: Float, memPct: Float
        ) {
            scope.trySend(
                GenerationEvent.Metrics(
                    DecodingMetrics(
                        tokensPerSecond = tps,
                        timeToFirstTokenMs = ttftMs,
                        totalTimeMs = totalMs,
                        tokensEvaluated = tokensEvaluated,
                        tokensPredicted = tokensPredicted,
                        modelSizeMB = modelMB,
                        contextSizeMB = ctxMB,
                        peakMemoryMB = peakMB,
                        memoryUsagePercent = memPct
                    )
                )
            )
        }

        override fun onProgress(progress: Float) {
            scope.trySend(GenerationEvent.Progress(progress))
        }

        override fun onDone() {
            scope.trySend(GenerationEvent.Done)
            scope.close()
        }

        override fun onError(message: String) {
            scope.trySend(GenerationEvent.Error(message))
            scope.close()
        }
    }

    // ==================== GGUF Methods ====================

    suspend fun loadGgufModel(model: Model, modelConfig: ModelConfig): Boolean {
        val loadingParams = modelConfig.modelLoadingParams ?: ""
        if (model.providerType == ProviderType.STT ||
            loadingParams.contains("\"type\":\"whisper\"") ||
            loadingParams.contains("\"type\":\"stt\"") ||
            loadingParams.contains("\"engine\":\"sherpa-onnx\"") ||
            model.modelName.lowercase().contains("whisper")
        ) {
            Log.e(TAG, "Prevented crash: STT/Whisper model '${model.modelName}' cannot be loaded into LLMService.")
            return false
        }

        val svc = ensureServiceBound()

        return suspendCancellableCoroutine { continuation ->
            val resumed = AtomicBoolean(false)
            val callback = object : IModelLoadCallback.Stub() {
                override fun onSuccess() {
                    if (!resumed.compareAndSet(false, true)) return
                    lastLoadedModel = model
                    lastLoadedConfig = modelConfig
                    _isGgufModelLoaded.value = true
                    Log.i(TAG, "GGUF model loaded successfully")

                    // Auto-detect and load mmproj projector file if present in model directory
                    try {
                        val mFile = File(model.modelPath)
                        val projFile = VlmPaths.colocatedMmproj(mFile)
                        if (projFile != null && projFile.exists() && projFile.length() > 1024L) {
                            Log.i(TAG, "Auto-detected VLM projector: ${projFile.name}")
                            loadVlmProjector(projFile.absolutePath)
                        } else {
                            Log.i(TAG, "No VLM projector found for ${mFile.name} (Assuming text-only GGUF)")
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to auto-detect VLM projector: ${e.message}")
                    }

                    continuation.resume(true)
                }

                override fun onError(message: String) {
                    if (!resumed.compareAndSet(false, true)) return
                    _isGgufModelLoaded.value = false
                    Log.e(TAG, "Failed to load GGUF model: $message")
                    continuation.resume(false)
                }
            }

            try {
                svc.loadGgufModel(
                    model.modelPath,
                    model.modelName,
                    modelConfig.modelLoadingParams ?: "",
                    modelConfig.modelInferenceParams ?: "",
                    callback
                )
            } catch (e: Exception) {
                if (!resumed.compareAndSet(false, true)) return@suspendCancellableCoroutine
                Log.e(TAG, "Exception loading GGUF model", e)
                continuation.resumeWithException(e)
            }
        }
    }

    /**
     * Load GGUF model from a content:// URI using file descriptor
     * This is used for SAF (Storage Access Framework) URIs
     */
    suspend fun loadGgufModelFromUri(
        context: Context,
        uri: Uri,
        modelName: String,
        modelConfig: ModelConfig
    ): Boolean {
        val loadingParams = modelConfig.modelLoadingParams ?: ""
        if (loadingParams.contains("\"type\":\"whisper\"") ||
            loadingParams.contains("\"type\":\"stt\"") ||
            loadingParams.contains("\"engine\":\"sherpa-onnx\"") ||
            modelName.lowercase().contains("whisper")
        ) {
            Log.e(TAG, "Prevented crash: STT/Whisper model '$modelName' cannot be loaded into LLMService.")
            return false
        }

        val svc = ensureServiceBound()

        // Open ParcelFileDescriptor from content URI
        val pfd = context.contentResolver.openFileDescriptor(uri, "r")
            ?: throw IllegalArgumentException("Cannot open file descriptor for URI: $uri")

        return suspendCancellableCoroutine { continuation ->
            val resumed = AtomicBoolean(false)

            continuation.invokeOnCancellation {
                try { pfd.close() } catch (_: Exception) {}
            }

            val callback = object : IModelLoadCallback.Stub() {
                override fun onSuccess() {
                    if (!resumed.compareAndSet(false, true)) return
                    try { pfd.close() } catch (_: Exception) {}
                    _isGgufModelLoaded.value = true
                    Log.i(TAG, "GGUF model loaded successfully from URI")
                    continuation.resume(true)
                }

                override fun onError(message: String) {
                    if (!resumed.compareAndSet(false, true)) return
                    try { pfd.close() } catch (_: Exception) {}
                    _isGgufModelLoaded.value = false
                    Log.e(TAG, "Failed to load GGUF model from URI: $message")
                    continuation.resume(false)
                }
            }

            try {
                svc.loadGgufModelFromFd(
                    pfd,
                    modelName,
                    modelConfig.modelLoadingParams ?: "",
                    modelConfig.modelInferenceParams ?: "",
                    callback
                )
            } catch (e: Exception) {
                if (!resumed.compareAndSet(false, true)) return@suspendCancellableCoroutine
                Log.e(TAG, "Exception loading GGUF model from URI", e)
                pfd.close()
                continuation.resumeWithException(e)
            }
        }
    }

    fun ggufStopGeneration() {
        val svc = _serviceFlow.value
        if (svc == null) { Log.w(TAG, "ggufStopGeneration: service not bound"); return }
        svc.stopGenerationGguf()
        Log.i(TAG, "GGUF generation stopped")
    }

    fun unloadGgufModel() {
        val svc = _serviceFlow.value
        if (svc == null) { Log.w(TAG, "unloadGgufModel: service not bound"); return }
        try {
            svc.unloadModelGguf()
        } catch (_: Exception) {}
        lastLoadedModel = null
        lastLoadedConfig = null
        _isGgufModelLoaded.value = false
        _isVlmLoaded.value = false
        Log.i(TAG, "GGUF model unloaded")
    }

    fun getGgufModelInfo(): String? {
        return _serviceFlow.value?.modelInfoGguf
    }

    // ==================== Tool Calling Methods ====================

    /**
     * Enable tool calling with grammar configuration.
     * Sets up tools, grammar mode, and typed grammar enforcement.
     *
     * @param toolsJson JSON array of tool definitions in OpenAI format
     * @param grammarMode 0=STRICT (forces JSON), 1=LAZY (model chooses text or tool call)
     * @param useTypedGrammar Whether to enforce exact param names/types/enums
     * @return true if tool calling was enabled successfully
     */
    fun enableToolCallingGguf(
        toolsJson: String,
        grammarMode: Int = 1, // LAZY by default
        useTypedGrammar: Boolean = true
    ): Boolean {
        return try {
            _serviceFlow.value?.enableToolCallingGguf(toolsJson, grammarMode, useTypedGrammar) ?: false
        } catch (e: Exception) {
            Log.e(TAG, "Failed to enable tool calling: ${e.message}")
            false
        }
    }

    /**
     * Direct same-process tool calling setup — bypasses AIDL, properly configures grammar.
     */
    fun enableToolCallingDirect(
        toolDefs: List<com.dark.gguf_lib.toolcalling.ToolDefinitionBuilder>,
        config: com.dark.gguf_lib.toolcalling.ToolCallingConfig
    ): Boolean {
        return try {
            val engine = LLMService.instance?.ggufEngine ?: return false
            engine.enableToolCallingDirect(toolDefs, config)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to enable tool calling (direct): ${e.message}")
            false
        }
    }

    /**
     * Clear all registered tools
     */
    fun clearToolsGguf() {
        try {
            _serviceFlow.value?.clearToolsGguf()
            Log.i(TAG, "Tools cleared")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to clear tools: ${e.message}")
        }
    }

    /**
     * Set custom GBNF grammar constraint.
     */
    fun setCustomGrammarGguf(grammar: String): Boolean {
        return try {
            _serviceFlow.value?.setCustomGrammarGguf(grammar) ?: false
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set custom grammar: ${e.message}")
            false
        }
    }

    /**
     * Clear custom GBNF grammar constraint.
     */
    fun clearCustomGrammarGguf(): Boolean {
        return try {
            _serviceFlow.value?.clearCustomGrammarGguf() ?: false
        } catch (e: Exception) {
            Log.e(TAG, "Failed to clear custom grammar: ${e.message}")
            false
        }
    }

    /**
     * Check if the loaded model supports tool calling.
     * Returns true for any model with a built-in chat template (model-agnostic).
     */
    fun isToolCallingSupportedGguf(): Boolean {
        return try {
            _serviceFlow.value?.isToolCallingSupportedGguf() ?: false
        } catch (e: Exception) {
            Log.e(TAG, "Failed to check tool calling support: ${e.message}")
            false
        }
    }

    /**
     * Set grammar enforcement mode
     * @param mode 0=STRICT, 1=LAZY
     */
    fun setGrammarModeGguf(mode: Int) {
        try {
            _serviceFlow.value?.setGrammarModeGguf(mode)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set grammar mode: ${e.message}")
        }
    }

    /**
     * Enable or disable parameter-aware typed grammar
     */
    fun setTypedGrammarGguf(enabled: Boolean) {
        try {
            _serviceFlow.value?.setTypedGrammarGguf(enabled)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set typed grammar: ${e.message}")
        }
    }

    // ==================== Persona Engine ====================

    fun updateSamplerParamsGguf(paramsJson: String): Boolean {
        return try {
            _serviceFlow.value?.updateSamplerParamsGguf(paramsJson) ?: false
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update sampler params: ${e.message}")
            false
        }
    }

    fun setLogitBiasGguf(biasJson: String): Boolean {
        return try {
            _serviceFlow.value?.setLogitBiasGguf(biasJson) ?: false
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set logit bias: ${e.message}")
            false
        }
    }

    fun loadControlVectorsGguf(vectorsJson: String): Boolean {
        return try {
            _serviceFlow.value?.loadControlVectorsGguf(vectorsJson) ?: false
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load control vectors: ${e.message}")
            false
        }
    }

    fun clearControlVectorGguf(): Boolean {
        return try {
            _serviceFlow.value?.clearControlVectorGguf() ?: false
        } catch (e: Exception) {
            Log.e(TAG, "Failed to clear control vector: ${e.message}")
            false
        }
    }

    // ==================== New Optimizations ====================

    fun setSpeculativeDecodingGguf(enabled: Boolean, nDraft: Int = 4, ngramSize: Int = 4) {
        try {
            _serviceFlow.value?.setSpeculativeDecodingGguf(enabled, nDraft, ngramSize)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set speculative decoding: ${e.message}")
        }
    }

    fun setPromptCacheDirGguf(path: String) {
        try {
            _serviceFlow.value?.setPromptCacheDirGguf(path)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set prompt cache dir: ${e.message}")
        }
    }

    fun warmUpGguf(): Boolean {
        return try {
            _serviceFlow.value?.warmUpGguf() ?: false
        } catch (e: Exception) {
            Log.e(TAG, "Failed to warm up: ${e.message}")
            false
        }
    }

    fun supportsThinkingGguf(): Boolean {
        return try {
            _serviceFlow.value?.supportsThinkingGguf() ?: false
        } catch (e: Exception) {
            Log.e(TAG, "Failed to check thinking support: ${e.message}")
            false
        }
    }

    fun setThinkingEnabledGguf(enabled: Boolean) {
        try {
            _serviceFlow.value?.setThinkingEnabledGguf(enabled)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set thinking enabled: ${e.message}")
        }
    }

    fun getContextUsageGguf(): Float {
        return try {
            _serviceFlow.value?.contextUsageGguf ?: 0f
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get context usage: ${e.message}")
            0f
        }
    }

    // ==================== Context Window Tracking ====================

    fun getContextInfoGguf(prompt: String? = null): String? {
        return try {
            _serviceFlow.value?.getContextInfoGguf(prompt)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get context info: ${e.message}")
            null
        }
    }

    // ==================== Character Engine ====================

    fun setPersonalityGguf(personalityJson: String): Boolean {
        return try {
            _serviceFlow.value?.setPersonalityGguf(personalityJson) ?: false
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set personality: ${e.message}")
            false
        }
    }

    fun setMoodGguf(mood: Int): Boolean {
        return try {
            _serviceFlow.value?.setMoodGguf(mood) ?: false
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set mood: ${e.message}")
            false
        }
    }

    fun setCustomMoodGguf(tempMod: Float, topPMod: Float, repPenaltyMod: Float): Boolean {
        return try {
            _serviceFlow.value?.setCustomMoodGguf(tempMod, topPMod, repPenaltyMod) ?: false
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set custom mood: ${e.message}")
            false
        }
    }

    fun getCharacterContextGguf(): String? {
        return try {
            _serviceFlow.value?.getCharacterContextGguf()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get character context: ${e.message}")
            null
        }
    }

    fun buildPromptGguf(userPrompt: String): String {
        return try {
            _serviceFlow.value?.buildPromptGguf(userPrompt) ?: userPrompt
        } catch (e: Exception) {
            Log.e(TAG, "Failed to build prompt: ${e.message}")
            userPrompt
        }
    }

    fun setUncensoredGguf(enabled: Boolean): Boolean {
        return try {
            _serviceFlow.value?.setUncensoredGguf(enabled) ?: false
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set uncensored: ${e.message}")
            false
        }
    }

    fun isUncensoredGguf(): Boolean {
        return try {
            _serviceFlow.value?.isUncensoredGguf() ?: false
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get uncensored state: ${e.message}")
            false
        }
    }

    // ── Activation Steering (direct path — FloatArray can't cross AIDL) ──

    fun calcVectorsDirect(prompt: String, onProgress: ((Float) -> Unit)? = null): FloatArray? {
        return try {
            val engine = LLMService.instance?.ggufEngine ?: return null
            engine.calcVectors(prompt, onProgress)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to calc vectors: ${e.message}")
            null
        }
    }

    fun applyVectorsDirect(data: FloatArray, strength: Float = 1.0f, ilStart: Int = -1, ilEnd: Int = -1): Boolean {
        return try {
            val engine = LLMService.instance?.ggufEngine ?: return false
            engine.applyVectors(data, strength, ilStart, ilEnd)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to apply vectors: ${e.message}")
            false
        }
    }

    fun clearVectorsDirect(): Boolean {
        return try {
            val engine = LLMService.instance?.ggufEngine ?: return false
            engine.clearVectors()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to clear vectors: ${e.message}")
            false
        }
    }

    // ==================== Upscaler ====================

    suspend fun loadUpscaler(modelPath: String): Boolean {
        val svc = ensureServiceBound()
        return suspendCancellableCoroutine { continuation ->
            val resumed = AtomicBoolean(false)
            val callback = object : IModelLoadCallback.Stub() {
                override fun onSuccess() {
                    if (!resumed.compareAndSet(false, true)) return
                    continuation.resume(true)
                }
                override fun onError(message: String) {
                    if (!resumed.compareAndSet(false, true)) return
                    Log.e(TAG, "Failed to load upscaler: $message")
                    continuation.resume(false)
                }
            }
            try {
                svc.loadUpscaler(modelPath, callback)
            } catch (e: Exception) {
                if (!resumed.compareAndSet(false, true)) return@suspendCancellableCoroutine
                continuation.resumeWithException(e)
            }
        }
    }

    fun releaseUpscaler() {
        try {
            _serviceFlow.value?.releaseUpscaler()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to release upscaler: ${e.message}")
        }
    }

    // ==================== Multi-turn Generation ====================

    /**
     * Multi-turn streaming generation using full conversation history.
     * Used for multi-turn tool calling flows where the model generates,
     * calls a tool, receives the result, and generates again.
     *
     * @param messagesJson JSON array of {role, content} objects
     * @param maxTokens Maximum tokens per turn
     */
    fun ggufGenerateMultiTurnStreaming(
        messagesJson: String,
        maxTokens: Int
    ): Flow<GenerationEvent> = callbackFlow {
        val svc = _serviceFlow.first { it != null }!!

        val callback = createGgufCallback(this)

        try {
            svc.generateGgufMultiTurn(messagesJson, maxTokens, callback)
        } catch (e: Exception) {
            trySend(GenerationEvent.Error(e.message ?: "Failed to start multi-turn generation"))
            close()
        }

        awaitClose { }
    }.buffer(Channel.UNLIMITED)
        .flowOn(Dispatchers.IO)

    // ==================== VLM (Vision Language Model) Methods ====================

    private val _isVlmLoaded = MutableStateFlow(false)
    val isVlmLoaded: StateFlow<Boolean> = _isVlmLoaded.asStateFlow()

    fun loadVlmProjector(path: String, threads: Int = 0): Boolean {
        val svc = _serviceFlow.value ?: return false
        val success = try {
            svc.loadVlmProjectorGguf(path)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load VLM projector via IPC", e)
            false
        }
        _isVlmLoaded.value = success
        if (success) Log.i(TAG, "VLM projector loaded: $path")
        else Log.e(TAG, "VLM projector failed to load: $path")
        return success
    }

    fun loadVlmProjectorFromFd(fd: Int, threads: Int = 0): Boolean {
        val svc = _serviceFlow.value ?: return false
        val pfd = try {
            android.os.ParcelFileDescriptor.fromFd(fd)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create PFD from fd: $fd", e)
            return false
        }
        val success = try {
            svc.loadVlmProjectorFromFdGguf(pfd)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load VLM projector from FD via IPC", e)
            false
        }
        _isVlmLoaded.value = success
        return success
    }

    fun releaseVlmProjector() {
        try {
            _serviceFlow.value?.releaseVlmProjectorGguf()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to release VLM projector via IPC", e)
        }
        _isVlmLoaded.value = false
        Log.i(TAG, "VLM projector released")
    }

    fun getVlmDefaultMarker(): String {
        return try {
            _serviceFlow.value?.vlmDefaultMarkerGguf ?: "<__image__>"
        } catch (e: Exception) {
            "<__image__>"
        }
    }

    fun vlmGenerateStreaming(
        messagesJson: String,
        imageData: List<ByteArray>,
        maxTokens: Int
    ): Flow<GenerationEvent> = kotlinx.coroutines.flow.callbackFlow {
        val svc = _serviceFlow.value
        if (svc == null) {
            trySend(GenerationEvent.Error("LLM service not available"))
            close()
            return@callbackFlow
        }

        val callback = object : IGgufGenerationCallback.Stub() {
            override fun onToken(token: String) { trySend(GenerationEvent.Token(token)) }
            override fun onToolCall(name: String?, args: String?) {}
            override fun onMetrics(
                tps: Float, ttftMs: Float, totalMs: Float, tokensEvaluated: Int,
                tokensPredicted: Int, modelMB: Float, ctxMB: Float, peakMB: Float, memPct: Float
            ) {
                trySend(GenerationEvent.Metrics(
                    DecodingMetrics(
                        tokensPerSecond = tps,
                        timeToFirstTokenMs = ttftMs,
                        totalTimeMs = totalMs,
                        tokensEvaluated = tokensEvaluated,
                        tokensPredicted = tokensPredicted,
                        modelSizeMB = modelMB,
                        contextSizeMB = ctxMB,
                        peakMemoryMB = peakMB,
                        memoryUsagePercent = memPct
                    )
                ))
            }
            override fun onProgress(progress: Float) {}
            override fun onDone() {
                trySend(GenerationEvent.Done)
                close()
            }
            override fun onError(message: String) {
                trySend(GenerationEvent.Error(message))
                close()
            }
        }

        val tempFiles = mutableListOf<java.io.File>()
        try {
            val imagePaths = imageData.mapIndexed { index, bytes ->
                val tempFile = java.io.File(boundContext?.cacheDir, "vlm_temp_img_$index.jpg")
                tempFile.writeBytes(bytes)
                tempFiles.add(tempFile)
                tempFile.absolutePath
            }
            svc.generateGgufVlm(messagesJson, imagePaths, maxTokens, callback)
        } catch (e: Exception) {
            trySend(GenerationEvent.Error(e.message ?: "IPC Error"))
            close()
        }
        
        awaitClose {
            try { svc.stopGenerationGguf() } catch (e: Exception) {}
            // Clean up temporary image files
            tempFiles.forEach { file ->
                try { file.delete() } catch (_: Exception) {}
            }
        }
    }.flowOn(Dispatchers.IO)

    // ==================== Diffusion Methods ====================

    /**
     * Load a Stable Diffusion model
     */
    suspend fun loadDiffusionModel(
        name: String,
        modelDir: String,
        height: Int = 512,
        width: Int = 512,
        textEmbeddingSize: Int = 768,
        runOnCpu: Boolean = false,
        useCpuClip: Boolean = false,
        isPony: Boolean = false,
        httpPort: Int = 8081,
        safetyMode: Boolean
    ): Boolean {
        val svc = ensureServiceBound()

        return suspendCancellableCoroutine { continuation ->
            val resumed = AtomicBoolean(false)
            val callback = object : IModelLoadCallback.Stub() {
                override fun onSuccess() {
                    if (!resumed.compareAndSet(false, true)) return
                    _isDiffusionModelLoaded.value = true
                    _diffusionBackendState.value = "Running"
                    Log.i(TAG, "Diffusion model loaded successfully: $name")
                    continuation.resume(true)
                }

                override fun onError(message: String) {
                    if (!resumed.compareAndSet(false, true)) return
                    _isDiffusionModelLoaded.value = false
                    _diffusionBackendState.value = "Error: $message"
                    Log.e(TAG, "Failed to load diffusion model: $message")
                    continuation.resume(false)
                }
            }

            try {
                svc.loadDiffusionModel(
                    name,
                    modelDir,
                    height,
                    width,
                    textEmbeddingSize,
                    runOnCpu,
                    useCpuClip,
                    isPony,
                    httpPort,
                    safetyMode,
                    callback
                )
            } catch (e: Exception) {
                if (!resumed.compareAndSet(false, true)) return@suspendCancellableCoroutine
                Log.e(TAG, "Exception loading diffusion model", e)
                continuation.resumeWithException(e)
            }
        }
    }

    /**
     * Diffusion generation event types
     */
    sealed class DiffusionGenerationEvent {
        data class Progress(
            val progress: Float,
            val currentStep: Int,
            val totalSteps: Int,
            val intermediateImage: Bitmap?
        ) : DiffusionGenerationEvent()

        data class Complete(
            val image: Bitmap,
            val seed: Long,
            val width: Int,
            val height: Int
        ) : DiffusionGenerationEvent()

        data class Error(val message: String) : DiffusionGenerationEvent()
    }

    /**
     * Generate image with Stable Diffusion as a Flow
     */
    fun generateDiffusionImage(
        prompt: String,
        negativePrompt: String = "",
        steps: Int = 28,
        cfgScale: Float = 7f,
        seed: Long = -1L,
        width: Int = 512,
        height: Int = 512,
        scheduler: String = "dpm",
        useOpenCL: Boolean = false,
        inputImage: String? = null,
        mask: String? = null,
        denoiseStrength: Float = 0.6f,
        showDiffusionProcess: Boolean = false,
        showDiffusionStride: Int = 1
    ): Flow<DiffusionGenerationEvent> = callbackFlow {
        val svc = _serviceFlow.first { it != null }!!

        val callback = object : IDiffusionGenerationCallback.Stub() {
            override fun onProgress(
                progress: Float,
                currentStep: Int,
                totalSteps: Int,
                intermediatePayload: String?
            ) {
                val bitmap = intermediatePayload?.takeIf { it.isNotEmpty() }?.let { payload ->
                    val file = File(payload)
                    if (file.exists() && file.isFile) {
                        BitmapFactory.decodeFile(file.absolutePath)
                    } else {
                        base64ToBitmap(payload)
                    }
                }

                trySend(
                    DiffusionGenerationEvent.Progress(
                        progress,
                        currentStep,
                        totalSteps,
                        bitmap
                    )
                )
            }

            override fun onComplete(
                imagePayload: String,
                completedSeed: Long,
                resultWidth: Int,
                resultHeight: Int
            ) {
                try {
                    val file = File(imagePayload)
                    val bitmap = if (file.exists() && file.isFile) {
                        val bmp = BitmapFactory.decodeFile(file.absolutePath)
                        try { file.delete() } catch (_: Exception) {}
                        bmp
                    } else {
                        base64ToBitmap(imagePayload)
                    }
                    if (bitmap != null) {
                        trySend(
                            DiffusionGenerationEvent.Complete(
                                bitmap,
                                completedSeed,
                                resultWidth,
                                resultHeight
                            )
                        )
                    } else {
                        trySend(DiffusionGenerationEvent.Error("Failed to decode result image"))
                    }
                    close()
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to decode result image", e)
                    trySend(DiffusionGenerationEvent.Error("Failed to decode result: ${e.message}"))
                    close()
                }
            }

            override fun onError(message: String) {
                trySend(DiffusionGenerationEvent.Error(message))
                close()
            }
        }

        try {
            svc.generateDiffusionImage(
                prompt,
                negativePrompt,
                steps,
                cfgScale,
                seed,
                width,
                height,
                scheduler,
                useOpenCL,
                inputImage,
                mask,
                denoiseStrength,
                showDiffusionProcess,
                showDiffusionStride,
                callback
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start diffusion generation", e)
            trySend(DiffusionGenerationEvent.Error(e.message ?: "Failed to start generation"))
            close()
        }

        awaitClose {
            // Flow cancelled
        }
    }.buffer(Channel.UNLIMITED)
        .flowOn(Dispatchers.IO)

    /**
     * Stop diffusion image generation
     */
    fun stopDiffusionGeneration() {
        val svc = _serviceFlow.value
        if (svc == null) { Log.w(TAG, "stopDiffusionGeneration: service not bound"); return }
        svc.stopGenerationDiffusion()
        Log.i(TAG, "Diffusion generation stopped")
    }

    /**
     * Restart diffusion backend
     */
    suspend fun restartDiffusionBackend(): Boolean {
        val svc = ensureServiceBound()

        return suspendCancellableCoroutine { continuation ->
            val resumed = AtomicBoolean(false)
            val callback = object : IModelLoadCallback.Stub() {
                override fun onSuccess() {
                    if (!resumed.compareAndSet(false, true)) return
                    _diffusionBackendState.value = "Running"
                    Log.i(TAG, "Diffusion backend restarted")
                    continuation.resume(true)
                }

                override fun onError(message: String) {
                    if (!resumed.compareAndSet(false, true)) return
                    _diffusionBackendState.value = "Error: $message"
                    Log.e(TAG, "Failed to restart diffusion backend: $message")
                    continuation.resume(false)
                }
            }

            try {
                svc.restartDiffusionBackend(callback)
            } catch (e: Exception) {
                if (!resumed.compareAndSet(false, true)) return@suspendCancellableCoroutine
                Log.e(TAG, "Exception restarting diffusion backend", e)
                continuation.resumeWithException(e)
            }
        }
    }

    /**
     * Stop diffusion backend
     */
    fun stopDiffusionBackend() {
        val svc = _serviceFlow.value
        if (svc == null) { Log.w(TAG, "stopDiffusionBackend: service not bound"); return }
        svc.stopDiffusionBackend()
        _isDiffusionModelLoaded.value = false
        _diffusionBackendState.value = "Idle"
        Log.i(TAG, "Diffusion backend stopped")
    }

    /**
     * Get current diffusion backend state
     */
    suspend fun getDiffusionBackendState(): String {
        val svc = ensureServiceBound()
        return svc.diffusionBackendState ?: "Unknown"
    }

    /**
     * Get current diffusion model info
     */
    suspend fun getCurrentDiffusionModel(): String? {
        val svc = ensureServiceBound()
        return svc.currentDiffusionModel
    }

    // ==================== Utility Methods ====================

    /**
     * Convert base64 string to Bitmap
     */
    private fun base64ToBitmap(base64String: String): Bitmap {
        val decodedBytes = Base64.getDecoder().decode(base64String)
        return BitmapFactory.decodeByteArray(decodedBytes, 0, decodedBytes.size)
            ?: throw IllegalArgumentException("Failed to decode bitmap from base64")
    }

    /**
     * Convert Bitmap to base64 string (for img2img)
     */
    fun bitmapToBase64(bitmap: Bitmap, quality: Int = 95): String {
        val outputStream = java.io.ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, quality, outputStream)
        val bytes = outputStream.toByteArray()
        return Base64.getEncoder().encodeToString(bytes)
    }

    // ==================== Embedding Model Download ====================

    /**
     * Start background download of embedding model
     */
    fun startEmbeddingModelDownload(context: Context) {
        val workRequest = OneTimeWorkRequestBuilder<EmbeddingModelDownloadWorker>()
            .addTag(EmbeddingModelDownloadWorker.TAG)
            .build()

        WorkManager.getInstance(context).enqueueUniqueWork(
            EmbeddingModelDownloadWorker.TAG,
            ExistingWorkPolicy.REPLACE,
            workRequest
        )

        Log.i(TAG, "Embedding model download started in background")
    }
}
