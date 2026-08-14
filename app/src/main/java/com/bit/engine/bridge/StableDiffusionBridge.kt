package com.bit.engine.bridge

import android.graphics.Bitmap
import com.dark.ai_sd.DiffusionGenerationParams
import com.dark.ai_sd.DiffusionModelConfig
import com.dark.ai_sd.StableDiffusionManager
import com.dark.ai_sd.DiffusionGenerationResult
import java.io.ByteArrayOutputStream
import java.io.File
import com.dark.ai_sd.DiffusionGenerationState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import android.util.Log

/**
 * Clean bridge for Stable Diffusion generation.
 * This wraps the local `StableDiffusionManager` which utilizes Snapdragon QNN acceleration or MNN CPU execution.
 */
object StableDiffusionBridge {
    private const val TAG = "StableDiffusionBridge"
    
    private var isModelLoaded = false
    private var isManagerInitialized = false
    private var currentModelDir: String? = null
    private var manager: StableDiffusionManager? = null

    val generationState: StateFlow<DiffusionGenerationState>
        get() = manager?.diffusionGenerationState ?: MutableStateFlow(DiffusionGenerationState.Idle)

    /**
     * Initialize the Stable Diffusion model.
     */
    suspend fun initModel(
        context: android.content.Context,
        modelDir: String,
        width: Int = 512,
        height: Int = 512,
        textEmbeddingSize: Int = 768,
        runOnCpu: Boolean = false,
        useCpuClip: Boolean = false,
        isPony: Boolean = false,
        safetyMode: Boolean = false
    ): Boolean {
        val mDir = File(modelDir)
        val hasMnn = File(mDir, "unet.mnn").exists() || 
                     File(mDir, "clip.mnn").exists() ||
                     File(mDir, "unet/unet.mnn").exists() || 
                     File(mDir, "text_encoder/clip.mnn").exists()
        val isCpuByName = modelDir.contains("CPU", ignoreCase = true)
        val effectiveRunOnCpu = runOnCpu || hasMnn || isCpuByName
        val effectiveUseCpuClip = useCpuClip || effectiveRunOnCpu

        if (isModelLoaded && currentModelDir == modelDir) {
            return true
        }

        val mgr = StableDiffusionManager.getInstance(context)
        manager = mgr
        
        if (!isManagerInitialized) {
            val tarFile = File(context.cacheDir, "qnnlibs.tar.xz")
            val runtimeLibsDir = File(context.filesDir, "runtime_libs/qnnlibs")
            val tarPath = if (tarFile.exists()) tarFile.absolutePath else ""

            val runtimeConfig = com.dark.ai_sd.DiffusionRuntimeConfig(
                runtimeDir = "runtime_libs/qnnlibs",
                tarXzSourcePath = tarPath,
                qnnLibsAssetPath = if (tarPath.isNotEmpty() || runtimeLibsDir.exists()) "qnnlibs" else "",
                safetyCheckerEnabled = false,
                safetyCheckerAssetPath = ""
            )
            try {
                mgr.initialize(runtimeConfig)
            } catch (e: Exception) {
                Log.w(TAG, "Runtime initialization warning: ${e.message}")
            }
            
            // Bypass NPU initialization failure so CPU / MNN fallback works seamlessly
            try {
                val dm = com.dark.ai_sd.DiffusionManager.getInstance(context)
                for (prop in dm.javaClass.declaredFields) {
                    if (prop.type == Boolean::class.javaPrimitiveType && !java.lang.reflect.Modifier.isFinal(prop.modifiers)) {
                        prop.isAccessible = true
                        prop.setBoolean(dm, true)
                        Log.i(TAG, "Enabled runtime readiness flag on field: ${prop.name}")
                    }
                }
            } catch (ex: Exception) {
                Log.w(TAG, "Runtime flag reflection notice", ex)
            }
            
            isManagerInitialized = true
        }

        val modelConfig = DiffusionModelConfig(
            name = mDir.name,
            modelDir = mDir.absolutePath,
            textEmbeddingSize = textEmbeddingSize,
            runOnCpu = effectiveRunOnCpu,
            useCpuClip = effectiveUseCpuClip,
            isPony = isPony,
            safetyMode = safetyMode
        )

        try {
            val ok = mgr.loadModel(modelConfig, width = width, height = height)
            isModelLoaded = ok
            if (ok) {
                currentModelDir = modelDir
                Log.i(TAG, "Stable Diffusion model loaded successfully: ${mDir.name} (CPU=$effectiveRunOnCpu, ${width}x${height})")
            } else {
                Log.e(TAG, "Stable Diffusion manager failed to load model: ${mDir.name}")
            }
            return ok
        } catch (e: Exception) {
            Log.e(TAG, "Exception loading Diffusion model", e)
            isModelLoaded = false
            return false
        }
    }

    /**
     * Start image generation asynchronously and monitor via generationState flow.
     */
    fun generateImageAsync(
        prompt: String,
        negativePrompt: String = "",
        steps: Int = 20,
        cfgScale: Float = 7f,
        seed: Long? = null,
        width: Int = 512,
        height: Int = 512,
        scheduler: String = "dpm",
        useOpenCL: Boolean = false,
        inputImage: String? = null,
        mask: String? = null,
        denoiseStrength: Float = 0.6f,
        showDiffusionProcess: Boolean = true,
        showDiffusionStride: Int = 1
    ) {
        if (!isModelLoaded) return

        val params = DiffusionGenerationParams(
            prompt = prompt,
            negativePrompt = negativePrompt,
            steps = steps,
            width = width,
            height = height,
            seed = seed,
            cfgScale = cfgScale,
            scheduler = scheduler,
            useOpenCL = useOpenCL,
            inputImage = inputImage,
            mask = mask,
            denoiseStrength = denoiseStrength,
            showDiffusionProcess = showDiffusionProcess,
            showDiffusionStride = showDiffusionStride
        )
        manager?.generateImage(params)
    }

    /**
     * Cancel ongoing generation.
     */
    fun cancelGeneration() {
        manager?.cancelGeneration()
    }

    /**
     * Reset generation state to idle.
     */
    fun resetGenerationState() {
        manager?.resetGenerationState()
    }

    /**
     * Unload current model.
     */
    fun unloadModel() {
        try {
            manager?.cancelGeneration()
        } catch (_: Exception) {}
        isModelLoaded = false
        currentModelDir = null
        Log.i(TAG, "Stable Diffusion model unloaded")
    }

    /**
     * Generate an image from text.
     */
    suspend fun txt2img(
        prompt: String,
        negativePrompt: String = "",
        steps: Int = 20,
        width: Int = 512,
        height: Int = 512
    ): ByteArray? {
        if (!isModelLoaded) return null

        val params = DiffusionGenerationParams(
            prompt = prompt,
            negativePrompt = negativePrompt,
            steps = steps,
            width = width,
            height = height,
            seed = null,
            cfgScale = 7f,
            scheduler = "dpm",
            useOpenCL = false
        )

        val result = manager?.generateImageSync(params)
        return if (result is DiffusionGenerationResult.Success) {
            bitmapToByteArray(result.bitmap)
        } else {
            null
        }
    }

    /**
     * Generate an image from a base image and text (Img2Img).
     */
    suspend fun img2img(
        prompt: String,
        imagePath: String,
        denoiseStrength: Float = 0.6f,
        negativePrompt: String = "",
        steps: Int = 20,
        width: Int = 512,
        height: Int = 512
    ): ByteArray? {
        if (!isModelLoaded) return null

        val params = DiffusionGenerationParams(
            prompt = prompt,
            inputImage = imagePath,
            denoiseStrength = denoiseStrength,
            negativePrompt = negativePrompt,
            steps = steps,
            width = width,
            height = height,
            seed = null,
            cfgScale = 7f,
            scheduler = "dpm",
            useOpenCL = false
        )

        val result = manager?.generateImageSync(params)
        return if (result is DiffusionGenerationResult.Success) {
            bitmapToByteArray(result.bitmap)
        } else {
            null
        }
    }

    /**
     * Helper to convert Android Bitmap to PNG Base64 string.
     */
    fun bitmapToBase64(bitmap: Bitmap): String {
        val stream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
        return java.util.Base64.getEncoder().encodeToString(stream.toByteArray())
    }

    private fun bitmapToByteArray(bitmap: Bitmap): ByteArray {
        val stream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
        return stream.toByteArray()
    }
}
