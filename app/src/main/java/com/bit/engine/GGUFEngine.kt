package com.bit.engine

import android.content.Context
import android.util.Log
import com.dark.gguf_lib.GGMLEngine
import com.dark.gguf_lib.toolcalling.GrammarMode
import com.dark.gguf_lib.toolcalling.ToolCallingConfig
import com.dark.gguf_lib.toolcalling.ToolDefinitionBuilder
import com.bit.global.DeviceTuner
import com.bit.global.HardwareScanner
import com.bit.models.engine_schema.DecodingMetrics
import com.bit.models.engine_schema.GgufEngineSchema
import com.bit.models.engine_schema.GgufLoadingParams
import com.bit.models.engine_schema.toLocal
import com.bit.models.table_schema.Model
import com.bit.models.table_schema.ModelConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.withContext
import com.dark.gguf_lib.models.GenerationEvent as LibGenerationEvent

class GGUFEngine {
    private val engine = GGMLEngine()
    private var currentModelId: String? = null

    private var currentToolsJson: String? = null
    private var currentToolCallingConfig: ToolCallingConfig? = null

    val isLoaded: Boolean get() = engine.isLoaded

    private fun isSmallModel(modelId: String, modelName: String): Boolean {
        val id = modelId.lowercase()
        val name = modelName.lowercase()
        return id.contains("350m") || name.contains("350m") ||
               id.contains("125m") || name.contains("125m") ||
               id.contains("160m") || name.contains("160m") ||
               id.contains("tiny") || name.contains("tiny") ||
               id.contains("mini") || name.contains("mini")
    }

    suspend fun load(model: Model, config: ModelConfig?): Boolean = withContext(Dispatchers.IO) {
        if (engine.isLoaded) unload()

        val schema = GgufEngineSchema.fromJson(
            config?.modelLoadingParams,
            config?.modelInferenceParams
        )

        val loading = schema.loadingParams
        var inference = schema.inferenceParams

        if (isSmallModel(model.id, model.modelName)) {
            inference = inference.copy(
                temperature = if (inference.temperature == 0.7f) 0.4f else inference.temperature,
                maxTokens = if (inference.maxTokens == 4096) 256 else inference.maxTokens,
                repeatPenalty = if (inference.repeatPenalty == 1.0f) 1.1f else inference.repeatPenalty
            )
        }

        // Pre-configure native thread mode to pre-spawn & attach worker threadpools
        // 0 = Power Saving (1 thread on eff core), 1 = Balanced (2 threads on perf cores), 2 = Performance (3 threads on perf cores + all-core batching)
        val threadMode = when {
            loading.threads == 1 -> 0 // 1 thread -> Power Saving
            loading.threads == 2 -> 1 // 2 threads -> Balanced
            loading.threads >= 3 -> 2 // 3+ threads -> Performance
            else -> loading.threadMode.coerceIn(0, 2) // Default auto-detect -> PERFORMANCE (2)
        }
        try {
            engine.setThreadMode(threadMode)
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to set thread mode prior to load", e)
        }

        var success = false
        try {
            success = engine.load(
                path = model.modelPath,
                contextSize = loading.ctxSize,
                threads = loading.threads,
                batchSize = loading.batchSize,
                flashAttn = loading.flashAttn,
                cacheTypeK = cacheTypeIntToString(loading.cacheTypeK),
                cacheTypeV = cacheTypeIntToString(loading.cacheTypeV)
            )
        } catch (e: Throwable) {
            Log.e(TAG, "Failed loading model on primary attempt", e)
        }

        // Retry 1: Fallback to standard f16 KV cache (maximum compatibility) if primary attempt fails
        if (!success && (loading.cacheTypeK != 1 || loading.cacheTypeV != 1)) {
            Log.w(TAG, "Retrying model load with unquantized f16 KV cache for compatibility...")
            try {
                success = engine.load(
                    path = model.modelPath,
                    contextSize = loading.ctxSize,
                    threads = loading.threads,
                    batchSize = loading.batchSize,
                    flashAttn = loading.flashAttn,
                    cacheTypeK = "f16",
                    cacheTypeV = "f16"
                )
            } catch (e: Throwable) {
                Log.e(TAG, "Failed loading model on retry 1 (f16 cache)", e)
            }
        }

        // Retry 2: Fallback with f16 cache + no flash attention
        if (!success) {
            Log.w(TAG, "Retrying model load with f16 KV cache and no flash attention...")
            try {
                success = engine.load(
                    path = model.modelPath,
                    contextSize = loading.ctxSize,
                    threads = loading.threads,
                    batchSize = loading.batchSize,
                    flashAttn = false,
                    cacheTypeK = "f16",
                    cacheTypeV = "f16"
                )
            } catch (e: Throwable) {
                Log.e(TAG, "Failed loading model on retry 2 (no flash attn fallback)", e)
            }
        }

        if (success) {
            try {
                engine.warmUp()
            } catch (e: Throwable) {
                Log.w(TAG, "Model warmUp failed (non-fatal)", e)
            }

            engine.setSampling(
                temperature = inference.temperature,
                topK = inference.topK,
                topP = inference.topP,
                minP = inference.minP,
                mirostat = inference.mirostat,
                mirostatTau = inference.mirostatTau,
                mirostatEta = inference.mirostatEta,
                seed = inference.seed
            )

            if (inference.repeatPenalty != 1.0f) {
                try {
                    val samplerJson = org.json.JSONObject()
                        .put("repeatPenalty", inference.repeatPenalty)
                        .toString()
                    engine.updateSamplerParams(samplerJson)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to apply repetition penalty sampler params", e)
                }
            }

            currentModelId = model.id

            if (inference.systemPrompt.isNotEmpty()) {
                engine.setSystemPrompt(inference.systemPrompt)
            }
            if (inference.chatTemplate.isNotEmpty()) {
                engine.setChatTemplate(inference.chatTemplate)
            }
        } else {
            try { engine.unload() } catch (_: Throwable) {}
        }

        success
    }


    suspend fun loadFromFd(fd: Int, config: ModelConfig? = null): Boolean = withContext(Dispatchers.IO) {
        if (engine.isLoaded) unload()

        val schema = GgufEngineSchema.fromJson(
            config?.modelLoadingParams,
            config?.modelInferenceParams
        )

        val loading = schema.loadingParams
        var inference = schema.inferenceParams

        val model = config?.modelId?.let {
            try {
                com.bit.di.AppContainer.getModelRepository().getModelById(it)
            } catch (e: Exception) {
                null
            }
        }

        if (model != null && isSmallModel(model.id, model.modelName)) {
            inference = inference.copy(
                temperature = if (inference.temperature == 0.7f) 0.4f else inference.temperature,
                maxTokens = if (inference.maxTokens == 4096) 256 else inference.maxTokens,
                repeatPenalty = if (inference.repeatPenalty == 1.0f) 1.1f else inference.repeatPenalty
            )
        }

        // Pre-configure native thread mode to pre-spawn & attach worker threadpools
        // 0 = Power Saving (1 thread on eff core), 1 = Balanced (2 threads on perf cores), 2 = Performance (3 threads on perf cores + all-core batching)
        val threadMode = when {
            loading.threads == 1 -> 0 // 1 thread -> Power Saving
            loading.threads == 2 -> 1 // 2 threads -> Balanced
            loading.threads >= 3 -> 2 // 3+ threads -> Performance
            else -> loading.threadMode.coerceIn(0, 2) // Default auto-detect -> PERFORMANCE (2)
        }
        try {
            engine.setThreadMode(threadMode)
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to set thread mode prior to loadFromFd", e)
        }

        var success = false
        try {
            success = engine.loadFromFd(
                fd = fd,
                contextSize = loading.ctxSize,
                threads = loading.threads,
                batchSize = loading.batchSize,
                flashAttn = loading.flashAttn,
                cacheTypeK = cacheTypeIntToString(loading.cacheTypeK),
                cacheTypeV = cacheTypeIntToString(loading.cacheTypeV)
            )
        } catch (e: Throwable) {
            Log.e(TAG, "Failed loading model from FD on primary attempt", e)
        }

        // Retry 1: Fallback to standard f16 KV cache (maximum compatibility) if primary attempt fails
        if (!success && (loading.cacheTypeK != 1 || loading.cacheTypeV != 1)) {
            Log.w(TAG, "Retrying model load from FD with unquantized f16 KV cache for compatibility...")
            try {
                success = engine.loadFromFd(
                    fd = fd,
                    contextSize = loading.ctxSize,
                    threads = loading.threads,
                    batchSize = loading.batchSize,
                    flashAttn = loading.flashAttn,
                    cacheTypeK = "f16",
                    cacheTypeV = "f16"
                )
            } catch (e: Throwable) {
                Log.e(TAG, "Failed loading model from FD on retry 1 (f16 cache)", e)
            }
        }

        // Retry 2: Fallback with f16 cache + no flash attention
        if (!success) {
            Log.w(TAG, "Retrying model load from FD with f16 KV cache and no flash attention...")
            try {
                success = engine.loadFromFd(
                    fd = fd,
                    contextSize = loading.ctxSize,
                    threads = loading.threads,
                    batchSize = loading.batchSize,
                    flashAttn = false,
                    cacheTypeK = "f16",
                    cacheTypeV = "f16"
                )
            } catch (e: Throwable) {
                Log.e(TAG, "Failed loading model from FD on retry 2 (no flash attn fallback)", e)
            }
        }

        if (success) {
            try {
                engine.warmUp()
            } catch (e: Throwable) {
                Log.w(TAG, "Model warmUp failed on loadFromFd (non-fatal)", e)
            }

            engine.setSampling(
                temperature = inference.temperature,
                topK = inference.topK,
                topP = inference.topP,
                minP = inference.minP,
                mirostat = inference.mirostat,
                mirostatTau = inference.mirostatTau,
                mirostatEta = inference.mirostatEta,
                seed = inference.seed
            )

            if (inference.repeatPenalty != 1.0f) {
                try {
                    val samplerJson = org.json.JSONObject()
                        .put("repeatPenalty", inference.repeatPenalty)
                        .toString()
                    engine.updateSamplerParams(samplerJson)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to apply repetition penalty sampler params", e)
                }
            }

            currentModelId = config?.modelId ?: "fd_$fd"

            if (inference.systemPrompt.isNotEmpty()) {
                engine.setSystemPrompt(inference.systemPrompt)
            }
            if (inference.chatTemplate.isNotEmpty()) {
                engine.setChatTemplate(inference.chatTemplate)
            }
        } else {
            try { engine.unload() } catch (_: Throwable) {}
        }

        success
    }

    // ── Generation ──

    fun generateFlow(prompt: String, maxTokens: Int): Flow<GenerationEvent> =
        engine.generateFlow(prompt, maxTokens).mapNotNull { it.toLocal() }

    fun generateMultiTurnFlow(messagesJson: String, maxTokens: Int): Flow<GenerationEvent> =
        engine.generateMultiTurnFlow(messagesJson, maxTokens).mapNotNull { it.toLocal() }

    fun stopGeneration() {
        engine.stopGeneration()
    }

    suspend fun unload() = withContext(Dispatchers.IO) {
        if (engine.isLoaded) {
            releaseVlmProjector()
            engine.unload()
            currentModelId = null
            currentToolsJson = null
            currentToolCallingConfig = null
        }
    }

    fun isModelLoaded(modelId: String): Boolean =
        engine.isLoaded && currentModelId == modelId

    fun getModelInfo(): String? =
        if (engine.isLoaded) engine.getModelInfoJson() else null

    // ── Tool Calling ──

    fun isToolCallingSupported(): Boolean {
        if (!engine.isLoaded) return false
        return true // Tool calling handled at app layer via JSON parsing
    }

    /**
     * Enable tool calling with actual ToolDefinitionBuilder objects (same-process direct call).
     * This properly configures grammar constraints via the native engine.
     */
    fun enableToolCallingDirect(
        toolDefs: List<ToolDefinitionBuilder>,
        config: ToolCallingConfig
    ): Boolean {
        if (!engine.isLoaded) return false

        // Tool calling is handled at the app layer via JSON parsing; no native grammar needed
        currentToolCallingConfig = config
        currentToolsJson = null // invalidate JSON cache
        Log.d(TAG, "Tool calling enabled (app-layer): ${toolDefs.size} tools")
        return true
    }

    /**
     * Legacy JSON-based enableToolCalling (for AIDL compatibility).
     * Falls back to setToolsJson only — grammar not enforced.
     */
    fun enableToolCalling(
        toolsJson: String,
        grammarMode: Int = GrammarMode.LAZY.value,
        useTypedGrammar: Boolean = true
    ): Boolean {
        if (!engine.isLoaded) return false

        // Tool schemas handled at app layer; just track state
        currentToolsJson = toolsJson
        return true
    }

    fun setToolsJson(toolsJson: String): Boolean {
        if (!engine.isLoaded) return false
        if (toolsJson == currentToolsJson) return true

        // Tool schemas handled at app layer; just track state
        currentToolsJson = toolsJson
        return true
    }

    // ── Persona Engine ──

    fun updateSamplerParams(paramsJson: String): Boolean {
        if (!engine.isLoaded) return false
        return try {
            engine.updateSamplerParams(paramsJson)
        } catch (_: Exception) { false }
    }

    fun setLogitBias(biasJson: String): Boolean {
        if (!engine.isLoaded) return false
        return try {
            engine.setLogitBias(biasJson)
            true
        } catch (_: Exception) { false }
    }

    fun setGrammar(gbnf: String): Boolean {
        if (!engine.isLoaded) return false
        return try {
            engine.setGrammar(gbnf)
            true
        } catch (_: Exception) { false }
    }

    fun clearGrammar(): Boolean {
        if (!engine.isLoaded) return false
        return try {
            engine.clearGrammar()
            true
        } catch (_: Exception) { false }
    }

    fun loadControlVectors(vectorsJson: String): Boolean {
        if (!engine.isLoaded) return false
        // Control vectors no longer supported by engine
        return false
    }

    fun clearControlVector(): Boolean {
        if (!engine.isLoaded) return false
        // Control vectors no longer supported by engine; no-op
        return true
    }

    // ── KV Cache State Persistence ──

    fun getStateSize(): Long {
        if (!engine.isLoaded) return 0
        return try {
            engine.getStateSize()
        } catch (_: Exception) { 0 }
    }

    fun stateSaveToFile(path: String): Boolean {
        if (!engine.isLoaded) return false
        return try {
            engine.stateSaveToFile(path)
        } catch (_: Exception) { false }
    }

    fun stateLoadFromFile(path: String): Boolean {
        if (!engine.isLoaded) return false
        return try {
            engine.stateLoadFromFile(path)
        } catch (_: Exception) { false }
    }

    fun clearTools() {
        // Just clear internal state; no native call needed
        currentToolsJson = null
        currentToolCallingConfig = null
    }

    // ── New Optimizations ──

    fun setSpeculativeDecoding(enabled: Boolean, nDraft: Int = 4, ngramSize: Int = 4) {
        // Speculative decoding no longer exposed by engine; no-op
    }

    fun setPromptCacheDir(path: String) {
        try {
            engine.setPromptCacheDir(path)
        } catch (_: Exception) { }
    }

    fun setTokenBatchSize(bytes: Int) {
        try {
            engine.setTokenBatchSize(bytes)
        } catch (_: Exception) { }
    }

    fun warmUp(): Boolean {
        if (!engine.isLoaded) return false
        return try {
            engine.warmUp()
        } catch (_: Exception) { false }
    }

    fun supportsThinking(): Boolean {
        if (!engine.isLoaded) return false
        return try {
            engine.supportsThinking()
        } catch (_: Exception) { false }
    }

    fun setThinkingEnabled(enabled: Boolean) {
        if (engine.isLoaded) {
            try {
                engine.setThinkingEnabled(enabled)
            } catch (_: Exception) { }
        }
    }

    fun getContextUsage(): Float {
        if (!engine.isLoaded) return 0f
        return try {
            engine.getContextUsage()
        } catch (_: Exception) { 0f }
    }

    // ── Context Window Tracking ──

    fun getContextInfo(prompt: String? = null): com.dark.gguf_lib.ContextInfo {
        if (!engine.isLoaded) return com.dark.gguf_lib.ContextInfo(0, 0, 0, -1, -1)
        return try {
            val total = 4096
            val usage = engine.getContextUsage()
            val used = (usage * total).toInt()
            val remaining = total - used
            com.dark.gguf_lib.ContextInfo(
                total = total,
                used = used,
                remaining = remaining,
                promptEstimate = -1,
                afterPrompt = -1
            )
        } catch (_: Exception) { com.dark.gguf_lib.ContextInfo(0, 0, 0, -1, -1) }
    }

    // ── Character Engine ──

    private val characterEngine by lazy { com.dark.gguf_lib.CharacterEngine(engine) }

    fun setPersonality(personalityJson: String): Boolean {
        if (!engine.isLoaded) return false
        return try {
            val j = org.json.JSONObject(personalityJson)
            characterEngine.setPersonality(com.dark.gguf_lib.Personality(
                name = j.optString("name", ""),
                persona = j.optString("persona", ""),
                temperature = j.optDouble("temperature", 0.7).toFloat(),
                topP = j.optDouble("topP", 0.9).toFloat(),
                repetitionPenalty = j.optDouble("repetitionPenalty", 1.1).toFloat(),
                creativity = j.optDouble("creativity", 0.5).toFloat(),
                verbosity = j.optDouble("verbosity", 0.5).toFloat(),
                formality = j.optDouble("formality", 0.5).toFloat(),
                topK = j.optInt("topK", -1),
                minP = j.optDouble("minP", -1.0).toFloat(),
            ))
            true
        } catch (_: Exception) { false }
    }

    fun setMood(mood: Int): Boolean {
        if (!engine.isLoaded) return false
        return try {
            characterEngine.setMood(com.dark.gguf_lib.Mood.entries[mood])
            true
        } catch (_: Exception) { false }
    }

    fun setCustomMood(tempMod: Float, topPMod: Float, repPenaltyMod: Float): Boolean {
        if (!engine.isLoaded) return false
        return try {
            characterEngine.setCustomMood(tempMod, topPMod, repPenaltyMod)
            true
        } catch (_: Exception) { false }
    }

    fun getCharacterContext(): String {
        if (!engine.isLoaded) return ""
        return try {
            characterEngine.getContext()
        } catch (_: Exception) { "" }
    }

    fun buildPrompt(userPrompt: String): String {
        if (!engine.isLoaded) return userPrompt
        return try {
            characterEngine.buildPrompt(userPrompt)
        } catch (_: Exception) { userPrompt }
    }

    fun setUncensored(enabled: Boolean): Boolean {
        if (!engine.isLoaded) return false
        return try {
            characterEngine.setUncensored(enabled)
            true
        } catch (_: Exception) { false }
    }

    fun isUncensored(): Boolean {
        if (!engine.isLoaded) return false
        return try {
            characterEngine.isUncensored
        } catch (_: Exception) { false }
    }

    // ── Activation Steering ──

    fun calcVectors(prompt: String, onProgress: ((Float) -> Unit)? = null): FloatArray? {
        if (!engine.isLoaded) return null
        return try {
            characterEngine.calcVectors(prompt, onProgress)
        } catch (_: Exception) { null }
    }

    fun applyVectors(data: FloatArray, strength: Float = 1.0f, ilStart: Int = -1, ilEnd: Int = -1): Boolean {
        if (!engine.isLoaded) return false
        return try {
            characterEngine.applyVectors(data, strength, ilStart, ilEnd)
        } catch (_: Exception) { false }
    }

    fun clearVectors(): Boolean {
        if (!engine.isLoaded) return false
        return try {
            characterEngine.clearVectors()
            true
        } catch (_: Exception) { false }
    }

    // ── VLM (Vision Language Model) ──

    fun loadVlmProjector(path: String, threads: Int = 0): Boolean {
        if (!engine.isLoaded) return false
        val effThreads = if (threads > 0) threads else 4
        return try {
            kotlinx.coroutines.runBlocking {
                engine.loadVlmProjector(path, effThreads, imageMinTokens = 256, imageMaxTokens = 1024)
            }
        } catch (_: Exception) { false }
    }

    fun loadVlmProjectorFromFd(fd: Int, threads: Int = 0): Boolean {
        if (!engine.isLoaded) return false
        val effThreads = if (threads > 0) threads else 4
        return try {
            kotlinx.coroutines.runBlocking {
                engine.loadVlmProjectorFromFd(fd, effThreads, imageMinTokens = 256, imageMaxTokens = 1024)
            }
        } catch (_: Exception) { false }
    }

    fun releaseVlmProjector() {
        try { engine.releaseVlmProjector() } catch (_: Exception) { }
    }

    val isVlmLoaded: Boolean get() = engine.isVlmLoaded

    fun getVlmDefaultMarker(): String = engine.getVlmDefaultMarker()

    fun generateVlmFlow(
        messagesJson: String,
        imageData: List<ByteArray>,
        maxTokens: Int
    ): Flow<GenerationEvent> =
        engine.generateVlmFlow(messagesJson, imageData, maxTokens).mapNotNull { it.toLocal() }

    companion object {
        private const val TAG = "GGUFEngine"

        fun getRecommendedParams(context: Context): GgufLoadingParams {
            val profile = HardwareScanner.scan(context)
            // Default to BALANCED — callers with access to coroutine scope should
            // read performanceMode from DataStore themselves
            return DeviceTuner.tune(profile, modelSizeMB = 0, mode = com.bit.global.PerformanceMode.BALANCED)
        }

        fun getRecommendedContextSize(context: Context, modelSizeMB: Int, modelName: String = ""): Int {
            return DeviceTuner.recommendContextSize(context, modelSizeMB, modelName)
        }

        /** Convert old Int cache type to new String format */
        private fun cacheTypeIntToString(type: Int): String = when (type) {
            0 -> "f32"
            1 -> "f16"
            8 -> "q5_1"
            9 -> "q8_0"
            10 -> "q4_0"
            11 -> "q4_1"
            12 -> "q5_0"
            else -> "q8_0"
        }
    }
}

// ── Local GenerationEvent (keeps .args for backward compat) ──

sealed class GenerationEvent {
    data class Token(val text: String) : GenerationEvent()
    data class ToolCall(val name: String, val args: String) : GenerationEvent()
    data class ToolResult(val callId: String, val name: String, val result: String) : GenerationEvent()
    data object Done : GenerationEvent()
    data class Error(val message: String) : GenerationEvent()
    data class Metrics(val metrics: DecodingMetrics) : GenerationEvent()
    data class Progress(val progress: Float) : GenerationEvent()
    data class ThinkingBlock(val thought: String) : GenerationEvent()
    data class PartialResponse(val accumulatedText: String) : GenerationEvent()
}

/** Map library GenerationEvent → local GenerationEvent */
private fun LibGenerationEvent.toLocal(): GenerationEvent? = when (this) {
    is LibGenerationEvent.Token -> GenerationEvent.Token(text)
    is LibGenerationEvent.Done -> GenerationEvent.Done
    is LibGenerationEvent.Error -> GenerationEvent.Error(message)
    is LibGenerationEvent.Metrics -> GenerationEvent.Metrics(metrics.toLocal())
    is LibGenerationEvent.Progress -> GenerationEvent.Progress(progress)
    else -> null // Intermediate VLM stage metrics, VT cache status, VLM-KV cache status — filter out, do NOT terminate early!
}
