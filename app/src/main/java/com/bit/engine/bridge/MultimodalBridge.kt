package com.bit.engine.bridge

import com.dark.gguf_lib.GGMLEngine
import com.dark.gguf_lib.ImageQuality
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.util.Base64

/**
 * Clean Llamatik-style API for Multimodal (VLM) Vision Models, adapted to use the local llama-kt backend.
 */
object MultimodalBridge {

    private val engine = GGMLEngine()
    private var isModelLoaded = false

    fun initModel(modelPath: String, mmprojPath: String): Boolean {
        if (isModelLoaded) {
            try { engine.releaseVlmProjector() } catch (e: Exception) {}
            runBlocking { engine.unload() }
        }
        
        // Load the language model
        val modelOk = runBlocking {
            engine.load(
                path = modelPath,
                contextSize = 4096,
                flashAttn = true,
                cacheTypeK = "q8_0",
                cacheTypeV = "q8_0"
            )
        }
        if (!modelOk) return false
        
        // Load the multimodal projector
        val vlmOk = runBlocking {
            engine.loadVlmProjector(
                path = mmprojPath,
                threads = 4,
                imageMinTokens = 256,
                imageMaxTokens = 1024
            )
        }
        if (!vlmOk) {
            runBlocking { engine.unload() }
            return false
        }
        
        isModelLoaded = true
        return true
    }

    fun analyzeImageBytesStream(imageBytes: ByteArray, prompt: String, callback: GenStream) {
        if (!isModelLoaded) return
        
        // Convert the simple prompt to the internal JSON messages array format required by GGMLEngine
        val messages = JSONArray()
        val userMessage = JSONObject()
        userMessage.put("role", "user")
        
        // We use the default marker for images if available, otherwise just [img-0]
        val marker = engine.getVlmDefaultMarker() ?: "[img-0]"
        userMessage.put("content", "$marker\n$prompt")
        messages.put(userMessage)
        
        val messagesJson = messages.toString()

        runBlocking {
            engine.generateVlmFlow(
                messagesJson = messagesJson,
                imageData = listOf(imageBytes),
                maxTokens = 2048,
                vtKeys = null,
                vlmKvKey = null,
                imageQuality = ImageQuality.HIGH
            ).collect { event ->
                val text = (event as? com.dark.gguf_lib.models.GenerationEvent.Token)?.text
                if (text != null) {
                    val shouldContinue = callback.onToken(text)
                    if (!shouldContinue) {
                        cancelAnalysis()
                    }
                }
            }
        }
    }

    fun cancelAnalysis() {
        engine.stopGeneration()
    }

    fun release() {
        try { runBlocking { engine.releaseVlmProjector() } } catch (e: Exception) {}
        runBlocking { engine.unload() }
        isModelLoaded = false
    }
}
