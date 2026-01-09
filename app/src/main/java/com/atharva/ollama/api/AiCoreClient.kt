package com.atharva.ollama.api

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.mp.ai_core.NativeLib
import com.mp.ai_core.StreamCallback
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Client wrapper for Ai-Core library (llama.cpp based on-device LLM).
 * Supports GGUF models for text generation.
 * Converted to Kotlin to handle suspend functions from the library.
 */
class AiCoreClient(private val context: Context) {

    companion object {
        private const val TAG = "AiCoreClient"
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(Dispatchers.IO)
    private var nativeLib: NativeLib? = null
    var isInitialized: Boolean = false
        private set
    var isGenerating: Boolean = false
        private set
    private val stopRequested = AtomicBoolean(false)
    private var generationJob: Job? = null

    interface GenerationCallback {
        fun onPartialResult(token: String)
        fun onComplete(fullResponse: String)
        fun onError(error: String)
    }

    interface InitCallback {
        fun onResult(success: Boolean, message: String)
    }
    
    fun initialize(modelPath: String, callback: InitCallback) {
        scope.launch {
            try {
                val modelFile = File(modelPath)
                if (!modelFile.exists()) {
                    withContext(Dispatchers.Main) {
                        callback.onResult(false, "Model file not found: $modelPath")
                    }
                    return@launch
                }
                
                if (modelFile.length() < 10 * 1024 * 1024) {
                    withContext(Dispatchers.Main) {
                        callback.onResult(false, "Model file too small")
                    }
                    return@launch
                }

                Log.d(TAG, "Initializing Ai-Core with model: $modelPath")
                
                // Try initializing - assuming constructor or Companion.getInstance()
                // Based on javap output, it seems to be a class we can instantiate or it has a Companion
                nativeLib = NativeLib() 
                
                // Note: The library init is likely a normal function, but if it was suspend we are in a coroutine
                val ok = nativeLib!!.init(
                    modelPath,
                    4,      // threads
                    2048,   // context size
                    0.7f,   // temperature
                    40,     // topK
                    0.9f    // topP
                    // minP seems missing in 6-arg signature found in javap, omitting it
                )
                
                if (ok) {
                    isInitialized = true
                    Log.d(TAG, "Ai-Core initialized successfully")
                    withContext(Dispatchers.Main) {
                        callback.onResult(true, "Model loaded")
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        callback.onResult(false, "Failed to load model")
                    }
                }
                
            } catch (e: UnsatisfiedLinkError) {
                Log.e(TAG, "Native library error: ${e.message}")
                withContext(Dispatchers.Main) {
                    callback.onResult(false, "Device not supported (Native Lib Error)")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Init error: ${e.message}", e)
                withContext(Dispatchers.Main) {
                    callback.onResult(false, "Error: ${e.message}")
                }
            }
        }
    }

    fun generateResponse(
        systemPrompt: String?,
        history: List<UnifiedApiClient.ChatMessageData>,
        maxTokens: Int,
        callback: GenerationCallback
    ) {
        if (!isInitialized || nativeLib == null) {
            callback.onError("Model not initialized")
            return
        }

        if (isGenerating) {
            callback.onError("Generation in progress")
            return
        }

        isGenerating = true
        stopRequested.set(false)

        generationJob = scope.launch {
            try {
                val promptBuilder = StringBuilder()
                
                if (!systemPrompt.isNullOrEmpty()) {
                    promptBuilder.append("System: ").append(systemPrompt).append("\n\n")
                }
                
                for (msg in history) {
                    if ("user" == msg.role) {
                        promptBuilder.append("User: ").append(msg.content).append("\n")
                    } else if ("assistant" == msg.role) {
                        promptBuilder.append("Assistant: ").append(msg.content).append("\n")
                    }
                }
                
                promptBuilder.append("Assistant: ")
                
                val prompt = promptBuilder.toString()
                val fullResponse = StringBuilder()

                // Call the suspend function generatingStreaming
                // The callback needs to be an instance of StreamCallback
                nativeLib!!.generateStreaming(
                    prompt,
                    maxTokens,
                    object : StreamCallback {
                        override fun onToken(token: String) {
                            if (stopRequested.get()) return
                            fullResponse.append(token)
                            mainHandler.post { callback.onPartialResult(fullResponse.toString()) }
                        }

                        override fun onComplete() {
                            isGenerating = false
                            if (!stopRequested.get()) {
                                mainHandler.post { callback.onComplete(fullResponse.toString()) }
                            }
                        }

                        override fun onError(msg: String) {
                            isGenerating = false
                            mainHandler.post { callback.onError(msg) }
                        }
                    }
                )

            } catch (e: Exception) {
                isGenerating = false
                withContext(Dispatchers.Main) {
                    callback.onError("Error: ${e.message}")
                }
            }
        }
    }

    fun stopGeneration() {
        if (isGenerating && nativeLib != null) {
            stopRequested.set(true)
            generationJob?.cancel()
            try {
                nativeLib!!.stop() // Changed from nativeStopGeneration based on javap
            } catch (e: Exception) {
                Log.w(TAG, "Stop error: ${e.message}")
            }
            isGenerating = false
        }
    }

    fun release() {
        stopGeneration()
        // nativeLib.nativeRelease() // Method not seen in javap, maybe not needed or named differently
        // Assuming GC handles it or stop() is enough
        nativeLib = null
        isInitialized = false
    }
}

