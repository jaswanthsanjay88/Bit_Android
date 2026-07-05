package com.bit.service

import android.app.Notification
import com.bit.notification.NotificationChannels
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.DeadObjectException
import android.os.ParcelFileDescriptor
import android.os.Process
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.bit.R
import com.bit.engine.DiffusionEngine
import com.bit.engine.GGUFEngine
import com.bit.engine.GenerationEvent
import com.bit.models.enums.PathType
import com.bit.models.enums.ProviderType
import com.bit.models.table_schema.Model
import com.bit.models.table_schema.ModelConfig
import com.bit.state.AppStateManager
import com.bit.data.AppSettingsDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

class LLMService : Service() {

    companion object {
        private const val TAG = "LLMService"

        /** Direct reference for same-process callers (avoids AIDL serialization). */
        @Volatile
        var instance: LLMService? = null
            private set
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val ggufEngine = GGUFEngine()
    private val diffusionEngine = DiffusionEngine()

    private fun collectGenerationFlow(
        flow: kotlinx.coroutines.flow.Flow<GenerationEvent>,
        callback: IGgufGenerationCallback
    ) {
        scope.launch(Dispatchers.IO) {
            try {
                flow.collect { event ->
                    try {
                        when (event) {
                            is GenerationEvent.Token -> callback.onToken(event.text)
                            is GenerationEvent.Done -> callback.onDone()
                            is GenerationEvent.Error -> callback.onError(event.message)
                            is GenerationEvent.Metrics -> {
                                val m = event.metrics
                                callback.onMetrics(
                                    m.tokensPerSecond, m.timeToFirstTokenMs, m.totalTimeMs,
                                    m.tokensEvaluated, m.tokensPredicted,
                                    m.modelSizeMB, m.contextSizeMB, m.peakMemoryMB, m.memoryUsagePercent
                                )
                            }
                            is GenerationEvent.ToolCall -> callback.onToolCall(event.name, event.args)
                            is GenerationEvent.Progress -> callback.onProgress(event.progress)
                            is GenerationEvent.ThinkingBlock -> {}
                            is GenerationEvent.PartialResponse -> {}
                        }
                    } catch (e: DeadObjectException) {
                        Log.w(TAG, "Client disconnected during generation")
                        ggufEngine.stopGeneration()
                        return@collect
                    }
                }
            } catch (e: DeadObjectException) {
                Log.w(TAG, "Client disconnected during generation", e)
                ggufEngine.stopGeneration()
            } catch (e: Exception) {
                try {
                    callback.onError(e.message ?: "Unknown error")
                } catch (_: Exception) { }
            }
        }
    }

    private val binder = object : ILLMService.Stub() {

        // ── GGUF Methods ──

        override fun loadGgufModel(
            modelPath: String,
            modelName: String,
            loadingParams: String,
            inferenceParams: String,
            callback: IModelLoadCallback
        ) {
            scope.launch(Dispatchers.IO) {
                try {
                    AppStateManager.setLoadingModel(modelName)

                    val model = Model(
                        id = modelName,
                        modelPath = modelPath,
                        modelName = modelName,
                        pathType = PathType.FILE,
                        providerType = ProviderType.GGUF,
                        fileSize = null
                    )
                    val config = ModelConfig(
                        modelId = modelName,
                        modelLoadingParams = loadingParams,
                        modelInferenceParams = inferenceParams
                    )

                    val success = ggufEngine.load(model, config)

                    if (success) {
                        try {
                            val speedMode = AppSettingsDataStore(applicationContext).speedModeEnabled.first()
                            ggufEngine.setSpeculativeDecoding(speedMode)
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to apply speculative decoding on load", e)
                        }
                        AppStateManager.setModelLoaded(modelName)
                        callback.onSuccess()
                    } else {
                        AppStateManager.setError("Failed to load model: $modelName")
                        callback.onError("Failed to load model")
                    }
                } catch (e: Exception) {
                    AppStateManager.setError(e.message ?: "Unknown error loading model")
                    callback.onError(e.message ?: "Unknown error")
                }
            }
        }

        override fun loadGgufModelFromFd(
            pfd: ParcelFileDescriptor,
            modelName: String,
            loadingParams: String,
            inferenceParams: String,
            callback: IModelLoadCallback
        ) {
            // Detach fd synchronously before coroutine launch —
            // Binder may close the ParcelFileDescriptor after the AIDL call returns
            val fd = pfd.detachFd()

            scope.launch(Dispatchers.IO) {
                try {
                    AppStateManager.setLoadingModel(modelName)

                    val config = ModelConfig(
                        modelId = modelName,
                        modelLoadingParams = loadingParams,
                        modelInferenceParams = inferenceParams
                    )

                    val success = ggufEngine.loadFromFd(fd, config)

                    if (success) {
                        try {
                            val speedMode = AppSettingsDataStore(applicationContext).speedModeEnabled.first()
                            ggufEngine.setSpeculativeDecoding(speedMode)
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to apply speculative decoding on load FD", e)
                        }
                        AppStateManager.setModelLoaded(modelName)
                        callback.onSuccess()
                    } else {
                        AppStateManager.setError("Failed to load model from FD: $modelName")
                        callback.onError("Failed to load model from file descriptor")
                    }
                } catch (e: Exception) {
                    AppStateManager.setError(e.message ?: "Unknown error loading model from FD")
                    callback.onError(e.message ?: "Unknown error")
                }
            }
        }

        override fun generateGguf(
            prompt: String, maxTokens: Int, callback: IGgufGenerationCallback
        ) {
            collectGenerationFlow(ggufEngine.generateFlow(prompt, maxTokens), callback)
        }

        override fun stopGenerationGguf() {
            ggufEngine.stopGeneration()
        }

        override fun unloadModelGguf() {
            scope.launch(Dispatchers.IO) {
                ggufEngine.unload()
                AppStateManager.setModelUnloaded()
            }
        }

        override fun getModelInfoGguf(): String? = ggufEngine.getModelInfo()

        override fun setToolsJsonGguf(toolsJson: String): Boolean =
            ggufEngine.setToolsJson(toolsJson)

        override fun clearToolsGguf() {
            ggufEngine.clearTools()
        }

        // ── Multi-turn Tool Calling ──

        override fun enableToolCallingGguf(
            toolsJson: String, grammarMode: Int, useTypedGrammar: Boolean
        ): Boolean = ggufEngine.enableToolCalling(toolsJson, grammarMode, useTypedGrammar)

        override fun generateGgufMultiTurn(
            messagesJson: String, maxTokens: Int, callback: IGgufGenerationCallback
        ) {
            collectGenerationFlow(ggufEngine.generateMultiTurnFlow(messagesJson, maxTokens), callback)
        }

        override fun setGrammarModeGguf(mode: Int) {
            // Grammar mode applied via tool calling config
        }

        override fun setTypedGrammarGguf(enabled: Boolean) {
            // Grammar mode applied via tool calling config
        }

        override fun isToolCallingSupportedGguf(): Boolean =
            ggufEngine.isToolCallingSupported()

        // ── Persona Engine ──

        override fun updateSamplerParamsGguf(paramsJson: String): Boolean =
            ggufEngine.updateSamplerParams(paramsJson)

        override fun setLogitBiasGguf(biasJson: String): Boolean =
            ggufEngine.setLogitBias(biasJson)

        override fun loadControlVectorsGguf(vectorsJson: String): Boolean =
            ggufEngine.loadControlVectors(vectorsJson)

        override fun clearControlVectorGguf(): Boolean =
            ggufEngine.clearControlVector()

        // ── KV Cache State Persistence ──

        override fun getStateSizeGguf(): Long = ggufEngine.getStateSize()
        override fun stateSaveToFileGguf(path: String): Boolean = ggufEngine.stateSaveToFile(path)
        override fun stateLoadFromFileGguf(path: String): Boolean = ggufEngine.stateLoadFromFile(path)

        // ── New Optimizations ──

        override fun setSpeculativeDecodingGguf(enabled: Boolean, nDraft: Int, ngramSize: Int) {
            ggufEngine.setSpeculativeDecoding(enabled, nDraft, ngramSize)
        }

        override fun setPromptCacheDirGguf(path: String) {
            ggufEngine.setPromptCacheDir(path)
        }

        override fun warmUpGguf(): Boolean = ggufEngine.warmUp()

        override fun supportsThinkingGguf(): Boolean = ggufEngine.supportsThinking()

        override fun setThinkingEnabledGguf(enabled: Boolean) {
            ggufEngine.setThinkingEnabled(enabled)
        }

        override fun getContextUsageGguf(): Float = ggufEngine.getContextUsage()

        // ── Context Window Tracking ──

        override fun getContextInfoGguf(prompt: String?): String {
            val info = ggufEngine.getContextInfo(prompt)
            return org.json.JSONObject().apply {
                put("total", info.total)
                put("used", info.used)
                put("remaining", info.remaining)
                put("promptEstimate", info.promptEstimate)
                put("afterPrompt", info.afterPrompt)
            }.toString()
        }

        // ── Character Engine ──

        override fun setPersonalityGguf(personalityJson: String): Boolean =
            ggufEngine.setPersonality(personalityJson)

        override fun setMoodGguf(mood: Int): Boolean =
            ggufEngine.setMood(mood)

        override fun setCustomMoodGguf(tempMod: Float, topPMod: Float, repPenaltyMod: Float): Boolean =
            ggufEngine.setCustomMood(tempMod, topPMod, repPenaltyMod)

        override fun getCharacterContextGguf(): String =
            ggufEngine.getCharacterContext()

        override fun buildPromptGguf(userPrompt: String): String =
            ggufEngine.buildPrompt(userPrompt)

        override fun setUncensoredGguf(enabled: Boolean): Boolean =
            ggufEngine.setUncensored(enabled)

        override fun isUncensoredGguf(): Boolean =
            ggufEngine.isUncensored()

        // ── Upscaler ──

        override fun loadUpscaler(modelPath: String, callback: IModelLoadCallback) {
            callback.onError("Upscaler support disabled")
        }

        override fun releaseUpscaler() {
            // No-op
        }

        // ── Diffusion Methods ──

        override fun loadDiffusionModel(
            name: String,
            modelDir: String,
            height: Int,
            width: Int,
            textEmbeddingSize: Int,
            runOnCpu: Boolean,
            useCpuClip: Boolean,
            isPony: Boolean,
            httpPort: Int,
            safetyMode: Boolean,
            callback: IModelLoadCallback
        ) {
            callback.onError("Diffusion support disabled")
        }

        override fun generateDiffusionImage(
            prompt: String,
            negativePrompt: String,
            steps: Int,
            cfgScale: Float,
            seed: Long,
            width: Int,
            height: Int,
            scheduler: String,
            useOpenCL: Boolean,
            inputImage: String?,
            mask: String?,
            denoiseStrength: Float,
            showDiffusionProcess: Boolean,
            showDiffusionStride: Int,
            callback: IDiffusionGenerationCallback
        ) {
            callback.onError("Diffusion support disabled")
        }

        override fun stopGenerationDiffusion() {
            // No-op
        }

        override fun restartDiffusionBackend(callback: IModelLoadCallback) {
            callback.onError("Diffusion support disabled")
        }

        override fun stopDiffusionBackend() {
            // No-op
        }

        override fun getDiffusionBackendState(): String = "DISABLED"
        override fun getCurrentDiffusionModel(): String? = null

        override fun simulateProcessCrash() {
            Log.w(TAG, "Simulating native engine crash. Killing process...")
            android.os.Process.killProcess(android.os.Process.myPid())
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        instance = this
        Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)

        // Tell llama.cpp where to find CPU backend variant .so files
        // (libggml-cpu-android_armv8.*.so) for runtime arch-level dispatch.
        try {
            val engineClass = Class.forName("com.dark.gguf_lib.GGMLEngine")
            val initMethod = engineClass.getMethod("initBackendDir", android.content.Context::class.java)
            initMethod.invoke(null, applicationContext)
        } catch (_: Throwable) {
            // Old AAR without initBackendDir — dladdr() fallback handles it
        }

        // scope.launch(Dispatchers.IO) {
        //     try {
        //         diffusionEngine.init(applicationContext, safetyCheckerEnabled = true)
        //         Log.i(TAG, "DiffusionEngine initialized in LLMService")
        //     } catch (e: Exception) {
        //         Log.e(TAG, "Failed to initialize diffusion engine", e)
        //     }
        // }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceCompat.startForeground(
                this, 1, createNotification(),
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                else 0
            )
        } else {
            startForeground(1, createNotification())
        }
    }

    override fun onDestroy() {
        instance = null
        runBlocking(Dispatchers.IO) {
            ggufEngine.unload()
            // diffusionEngine.cleanup()
        }
        scope.cancel()
        super.onDestroy()
        Log.i(TAG, "LLMService destroyed")
    }

    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, NotificationChannels.LLM_SERVICE)
            .setContentTitle("AI Model Service")
            .setContentText("Running...")
            .setSmallIcon(R.drawable.user)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }
}
