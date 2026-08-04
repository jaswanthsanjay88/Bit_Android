package com.bit.engine

import android.content.Context
import android.util.Log
import com.dark.gguf_lib.EmbeddingEngine as LibEmbeddingEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import com.bit.global.AppPaths
import java.io.File
import java.io.FileInputStream

data class EmbeddingConfig(
    val modelPath: String,
    val threads: Int = 0,
    val contextSize: Int = 512,
    val normalize: Boolean = true
)

class EmbeddingEngine {
    private val libEngine = LibEmbeddingEngine()
    private var config: EmbeddingConfig? = null
    private var dimension: Int = 0
    private val initMutex = Mutex()

    companion object {
        private const val TAG = "EmbeddingEngine"
        private const val MIN_MODEL_SIZE_BYTES = 1_000_000L
        private val GGUF_MAGIC = byteArrayOf('G'.code.toByte(), 'G'.code.toByte(), 'U'.code.toByte(), 'F'.code.toByte())
        private const val COMPATIBILITY_HINT =
            "Use an embedding GGUF model (not chat/generation). Recommended: all-MiniLM-L6-v2-Q5_K_M.gguf"

        fun getModelPath(context: Context): File {
            return AppPaths.embeddingModel(context)
        }

        fun isModelDownloaded(context: Context): Boolean {
            return isModelFileValid(getModelPath(context))
        }

        fun isModelFileValid(modelFile: File): Boolean {
            if (!modelFile.exists() || !modelFile.isFile || !modelFile.canRead()) return false
            if (modelFile.length() < MIN_MODEL_SIZE_BYTES) return false

            return try {
                FileInputStream(modelFile).use { input ->
                    val header = ByteArray(4)
                    val bytesRead = input.read(header)
                    bytesRead == 4 && header.contentEquals(GGUF_MAGIC)
                }
            } catch (e: Exception) {
                false
            }
        }

        fun getModelValidationError(modelFile: File): String {
            if (!modelFile.exists()) return "Model file missing"
            if (!modelFile.isFile) return "Model path is not a regular file"
            if (!modelFile.canRead()) return "Model file is not readable"
            if (modelFile.length() < MIN_MODEL_SIZE_BYTES) {
                return "Model file is too small (${modelFile.length()} bytes)"
            }

            return try {
                FileInputStream(modelFile).use { input ->
                    val header = ByteArray(4)
                    val bytesRead = input.read(header)
                    if (bytesRead != 4 || !header.contentEquals(GGUF_MAGIC)) {
                        "Model header is invalid (not GGUF)"
                    } else {
                        "OK"
                    }
                }
            } catch (e: Exception) {
                "Failed to read model header: ${e.message}"
            }
        }

        fun getModelDiagnostics(context: Context): String {
            val modelFile = getModelPath(context)
            return buildString {
                append("Embedding Model Status:\n")
                append("  Path: ${modelFile.absolutePath}\n")
                append("  Parent Dir Exists: ${modelFile.parentFile?.exists() ?: false}\n")
                append("  Parent Dir Writable: ${modelFile.parentFile?.canWrite() ?: false}\n")
                append("  File Exists: ${modelFile.exists()}\n")
                
                if (modelFile.exists()) {
                    append("  File Size: ${modelFile.length() / 1024 / 1024}MB (${modelFile.length()} bytes)\n")
                    append("  File Readable: ${modelFile.canRead()}\n")
                    append("  Valid GGUF: ${isModelFileValid(modelFile)}\n")
                    append("  Validation: ${getModelValidationError(modelFile)}\n")
                    append("  Last Modified: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(modelFile.lastModified())}\n")
                } else {
                    append("  File Size: N/A (file missing)\n")
                }
            }
        }
    }

    suspend fun ensureInitialized(context: Context): Boolean {
        if (isInitialized()) return true
        val modelFile = getModelPath(context)
        if (isModelFileValid(modelFile)) {
            val result = initialize(EmbeddingConfig(modelPath = modelFile.absolutePath))
            if (result.isSuccess) {
                Log.d(TAG, "Auto-initialized EmbeddingEngine from model path: ${modelFile.absolutePath}")
                return true
            } else {
                Log.e(TAG, "Auto-initialization failed: ${result.exceptionOrNull()?.message}")
            }
        } else {
            Log.w(TAG, "Embedding model file missing or invalid at ${modelFile.absolutePath}")
        }
        return false
    }

    suspend fun initialize(config: EmbeddingConfig): Result<Unit> = initMutex.withLock {
        withContext(Dispatchers.IO) {
            try {
                val normalizedPath = config.modelPath
                    .trim()
                    .replace(Regex("""\s*/\s*"""), "/")
                val effectiveConfig = if (normalizedPath != config.modelPath) {
                    Log.w(TAG, "Normalized embedding model path from '${config.modelPath}' to '$normalizedPath'")
                    config.copy(modelPath = normalizedPath)
                } else {
                    config
                }

                if (isInitialized() && this@EmbeddingEngine.config?.modelPath == effectiveConfig.modelPath) {
                    Log.d(TAG, "Already initialized with same model")
                    return@withContext Result.success(Unit)
                }

                val modelFile = File(effectiveConfig.modelPath)
                if (!modelFile.exists()) {
                    Log.e(TAG, "Model file not found: ${effectiveConfig.modelPath}")
                    return@withContext Result.failure(Exception("Model file not found: ${effectiveConfig.modelPath}"))
                }

                if (!isModelFileValid(modelFile)) {
                    val reason = getModelValidationError(modelFile)
                    Log.e(TAG, "Model file failed validation: $reason")
                    val deleted = modelFile.delete()
                    Log.w(TAG, "Deleted invalid model file before init (success=$deleted)")
                    return@withContext Result.failure(
                        Exception("Embedding model is corrupted. It will be automatically re-downloaded. Please try again.")
                    )
                }

                if (!modelFile.canRead()) {
                    Log.e(TAG, "Model file not readable: ${effectiveConfig.modelPath}")
                    return@withContext Result.failure(Exception("Model file not readable: ${effectiveConfig.modelPath}"))
                }

                Log.d(TAG, "Loading embedding model: ${effectiveConfig.modelPath} (${modelFile.length() / 1024}KB)")

                val threadCandidates = listOf(effectiveConfig.threads, 4, 2, 1)
                    .filter { it >= 0 }
                    .distinct()
                val contextCandidates = listOf(effectiveConfig.contextSize, 1024, 2048)
                    .filter { it > 0 }
                    .distinct()

                var success = false
                var usedThreads = effectiveConfig.threads
                var usedContextSize = effectiveConfig.contextSize

                outer@ for (candidateThreads in threadCandidates) {
                    for (candidateContext in contextCandidates) {
                        Log.d(
                            TAG,
                            "Trying embedding model load with threads=$candidateThreads contextSize=$candidateContext"
                        )

                        val loaded = libEngine.load(
                            path = effectiveConfig.modelPath,
                            threads = candidateThreads,
                            contextSize = candidateContext
                        )

                        if (loaded) {
                            success = true
                            usedThreads = candidateThreads
                            usedContextSize = candidateContext
                            break@outer
                        }

                        libEngine.close()
                    }
                }

                if (!success) {
                    Log.e(TAG, "Native load returned false")
                    libEngine.close()
                    return@withContext Result.failure(
                        Exception("Embedding model is not compatible with the native engine. $COMPATIBILITY_HINT")
                    )
                }

                Log.d(TAG, "Model loaded, running test embedding...")
                Log.d(TAG, "Model file size: ${modelFile.length() / 1024 / 1024}MB")

                // Try test embedding with retry logic
                var testResult: FloatArray? = null
                var retryCount = 0
                val maxRetries = 3
                
                while (testResult == null && retryCount < maxRetries) {
                    try {
                        // Validate directly with the native embedding handle; embed() is gated on full initialization.
                        testResult = libEngine.embed("test text for embedding validation", effectiveConfig.normalize)
                        if (testResult == null || testResult.isEmpty()) {
                            Log.w(TAG, "Test embedding returned null/empty on attempt ${retryCount + 1}/$maxRetries")
                            retryCount++
                        } else {
                            dimension = testResult.size
                            Log.d(TAG, "Embedding engine initialized: dimension=$dimension")
                            break
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Test embedding threw exception on attempt ${retryCount + 1}/$maxRetries: ${e.message}")
                        retryCount++
                    }
                }
                
                if (testResult == null || testResult.isEmpty()) {
                    Log.e(TAG, "Test embedding failed after $maxRetries attempts — releasing native handle")
                    libEngine.close()
                    return@withContext Result.failure(
                        Exception("Model loaded but did not produce embeddings. $COMPATIBILITY_HINT")
                    )
                }

                this@EmbeddingEngine.config = effectiveConfig.copy(
                    threads = usedThreads,
                    contextSize = usedContextSize
                )
                Result.success(Unit)
            } catch (e: Exception) {
                Log.e(TAG, "Initialization failed: ${e.message}", e)
                Result.failure(e)
            }
        }
    }

    suspend fun embed(text: String): FloatArray? {
        if (!isInitialized()) {
            Log.w(TAG, "embed() called before initialization")
            return null
        }
        return libEngine.embed(text, config?.normalize ?: true)
    }

    suspend fun embedBatch(texts: List<String>): List<FloatArray?> =
        libEngine.embedBatch(texts, config?.normalize ?: true)

    fun isInitialized(): Boolean = config != null && dimension > 0

    fun getDimension(): Int = dimension

    fun getModelName(): String = config?.modelPath?.substringAfterLast("/") ?: "unknown"

    fun close() {
        libEngine.close()
        config = null
        dimension = 0
    }
}
