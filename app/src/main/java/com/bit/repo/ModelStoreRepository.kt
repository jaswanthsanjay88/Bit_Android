package com.bit.repo

import android.content.Context
import com.bit.global.formatDecimalBytes
import android.os.Build
import android.util.Log
import com.bit.models.data.HFModelRepository
import com.bit.models.data.HuggingFaceModel
import com.bit.models.data.ModelCategory
import com.bit.models.data.ModelType
import com.bit.models.data.RepositorySource
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import java.io.File
import java.util.Locale
import com.bit.network.HuggingFaceClient
import com.bit.network.HuggingFaceFileResponse
import com.bit.network.ExternalModelApiClient
import com.google.gson.JsonArray
import com.google.gson.JsonObject

@Serializable
data class ModelStoreCache(
    val models: List<HuggingFaceModel>,
    val timestamp: Long,
    val cacheVersion: Int = 0
) {
    companion object {
        // Bump this when filtering logic changes to auto-invalidate stale caches
        const val CURRENT_VERSION = 3
    }
}

class ModelStoreRepository(private val context: Context) {

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = false }
    private val cacheDir = File(context.filesDir, "cache").apply { mkdirs() }
    private val cacheFile = File(cacheDir, "model_store_cache.json")

    @Volatile
    private var cachedModels: List<HuggingFaceModel>? = null

    private val chipsetModelSuffixes = mapOf(
        "SM8475" to "8gen1",
        "SM8450" to "8gen1",
        "SM8550" to "8gen2",
        "SM8550P" to "8gen2",
        "QCS8550" to "8gen2",
        "QCM8550" to "8gen2",
        "SM8650" to "8gen3",
        "SM8650P" to "8gen3",
        "SM8750" to "8elite",
        "SM8750P" to "8elite",
        "SM8850" to "8elite",
        "SM8850P" to "8elite",
        "SM8735" to "8gen3",
        "SM8845" to "8gen3",
    )

    private fun getDeviceSoc(): String {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Build.SOC_MODEL
        } else {
            "UNKNOWN"
        }
    }

    fun isQualcommDevice(): Boolean {
        val soc = getDeviceSoc()
        return soc.startsWith("SM") || soc.startsWith("QCS") || soc.startsWith("QCM")
    }

    fun getChipsetSuffix(soc: String): String? {
        if (soc in chipsetModelSuffixes) {
            return chipsetModelSuffixes[soc]
        }
        if (soc.startsWith("SM")) {
            return "min"
        }
        return null
    }

    fun getDeviceInfo(): Map<String, String> {
        val soc = getDeviceSoc()
        return mapOf(
            "soc" to soc,
            "chipset" to (getChipsetSuffix(soc) ?: "Not Supported"),
            "npu" to if (isQualcommDevice()) "Available" else "Not Available",
            "device" to "${Build.MANUFACTURER} ${Build.MODEL}"
        )
    }

    /**
     * Build the ordered list of QNN zip suffixes this device should try.
     * - 8gen1+: prefer exact chipset, fall back through older gens, then "min"
     * - SM7* / unknown SM8*: "min" only (NPU too weak for gen-specific builds)
     * - Non-Qualcomm: null (skip NPU repos entirely)
     */
    private fun getNpuSuffixChain(): List<String>? {
        val soc = getDeviceSoc()
        if (!isQualcommDevice()) return null

        val suffix = getChipsetSuffix(soc)
        val genChain = listOf("8elite", "8gen3", "8gen2", "8gen1", "min")
        val idx = genChain.indexOf(suffix)

        // Known 8gen* suffix → slice from that point to include all compatible older builds
        return if (idx >= 0) genChain.subList(idx, genChain.size) else listOf("min")
    }

    suspend fun getAvailableModels(
        repositories: List<HFModelRepository>,
        forceRefresh: Boolean = false
    ): Result<List<HuggingFaceModel>> {
        // Return in-memory cache if available and not force-refresh
        if (!forceRefresh && cachedModels != null) {
            return Result.success(cachedModels!!)
        }

        // Load from disk cache if not force-refresh
        if (!forceRefresh) {
            loadDiskCache()?.let { cached ->
                cachedModels = cached
                return Result.success(cached)
            }
        }

        return fetchAndCache(repositories)
    }

    suspend fun refreshModels(
        repositories: List<HFModelRepository>
    ): Result<List<HuggingFaceModel>> {
        return fetchAndCache(repositories)
    }

    private val curatedCacheFile = File(cacheDir, "curated_models_cache.json")

    companion object {
        private const val CURATED_API_URL = "https://bit.jaswanthsanjay.me/api/models.json"
        private const val CURATED_API_RAW_URL = "https://raw.githubusercontent.com/jaswanthsanjay88/Bit_Android/master/site/api/models.json"
        private const val CURATED_API_ALT_URL = "https://bit.jaswanthsanjay.me/api/models"
    }

    suspend fun fetchCuratedModels(forceRefresh: Boolean = false): Result<List<HuggingFaceModel>> = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        // Return disk cache if not forcing refresh
        if (!forceRefresh) {
            loadCuratedDiskCache()?.let { cached ->
                return@withContext Result.success(cached)
            }
        }

        val client = okhttp3.OkHttpClient.Builder()
            .connectTimeout(12, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(12, java.util.concurrent.TimeUnit.SECONDS)
            .build()

        val urlsToTry = listOf(CURATED_API_URL, CURATED_API_RAW_URL, CURATED_API_ALT_URL)
        for (url in urlsToTry) {
            try {
                val request = okhttp3.Request.Builder()
                    .url(url)
                    .get()
                    .build()

                val response = client.newCall(request).execute()
                if (response.isSuccessful) {
                    val body = response.body.string().ifBlank { null }
                    if (body != null) {
                        val models = parseCuratedJson(body)
                        if (models.isNotEmpty()) {
                            try { curatedCacheFile.writeText(body) } catch (e: Exception) { Log.w("ModelStoreRepository", "Failed to cache curated models", e) }
                            return@withContext Result.success(models)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w("ModelStoreRepository", "Failed to fetch from $url: ${e.message}")
            }
        }

        // Fall back to disk cache if available
        loadCuratedDiskCache()?.let { return@withContext Result.success(it) }

        // Ultimate fallback: built-in curated models so the store NEVER fails
        val fallbackModels = getBuiltInCuratedModels()
        if (fallbackModels.isNotEmpty()) {
            return@withContext Result.success(fallbackModels)
        }

        Result.failure(Exception("Failed to fetch curated models from API"))
    }

    private fun getBuiltInCuratedModels(): List<HuggingFaceModel> {
        return listOf(
            HuggingFaceModel(
                id = "qwen3.5-0.8b-q4km",
                name = "Qwen 3.5 0.8B",
                description = "Ultra-lightweight chat model with tool calling support. Perfect for low-RAM devices.",
                fileUri = "https://huggingface.co/unsloth/Qwen3.5-0.8B-GGUF/resolve/main/Qwen3.5-0.8B-Q4_K_M.gguf",
                approximateSize = "600 MB",
                modelType = ModelType.GGUF,
                isZip = false,
                runOnCpu = false,
                minRamGb = 4,
                sizeBytes = 629145600L,
                icon = "qwen",
                iconUrl = "https://unpkg.com/@lobehub/icons-static-png@1.95.0/dark/qwen.png",
                tags = listOf("Chat", "Tool Calling", "Tested")
            ),
            HuggingFaceModel(
                id = "qwen3.5-0.8b-q8",
                name = "Qwen 3.5 0.8B (Q8)",
                description = "Higher precision variant of Qwen 3.5 0.8B. Better quality, slightly larger.",
                fileUri = "https://huggingface.co/unsloth/Qwen3.5-0.8B-GGUF/resolve/main/Qwen3.5-0.8B-Q8_0.gguf",
                approximateSize = "900 MB",
                modelType = ModelType.GGUF,
                isZip = false,
                runOnCpu = false,
                minRamGb = 4,
                sizeBytes = 943718400L,
                icon = "qwen",
                iconUrl = "https://unpkg.com/@lobehub/icons-static-png@1.95.0/dark/qwen.png",
                tags = listOf("Chat", "Tool Calling", "Tested", "High Quality")
            ),
            HuggingFaceModel(
                id = "qwen3.5-4b-q4km",
                name = "Qwen 3.5 4B",
                description = "Balanced chat model with strong reasoning and tool calling. Good for mid-range devices.",
                fileUri = "https://huggingface.co/unsloth/Qwen3.5-4B-GGUF/resolve/main/Qwen3.5-4B-Q4_K_M.gguf",
                approximateSize = "2.7 GB",
                modelType = ModelType.GGUF,
                isZip = false,
                runOnCpu = false,
                minRamGb = 6,
                sizeBytes = 2899102720L,
                icon = "qwen",
                iconUrl = "https://unpkg.com/@lobehub/icons-static-png@1.95.0/dark/qwen.png",
                tags = listOf("Chat", "Tool Calling", "Tested")
            ),
            HuggingFaceModel(
                id = "qwen3.5-4b-q8",
                name = "Qwen 3.5 4B (Q8)",
                description = "High precision Qwen 3.5 4B. Best quality for 8GB+ RAM devices.",
                fileUri = "https://huggingface.co/unsloth/Qwen3.5-4B-GGUF/resolve/main/Qwen3.5-4B-Q8_0.gguf",
                approximateSize = "4.5 GB",
                modelType = ModelType.GGUF,
                isZip = false,
                runOnCpu = false,
                minRamGb = 8,
                sizeBytes = 4831838208L,
                icon = "qwen",
                iconUrl = "https://unpkg.com/@lobehub/icons-static-png@1.95.0/dark/qwen.png",
                tags = listOf("Chat", "Tool Calling", "Tested", "High Quality")
            ),
            HuggingFaceModel(
                id = "qwen3.5-9b-q4km",
                name = "Qwen 3.5 9B",
                description = "Powerful reasoning model with tool calling. Requires 8GB+ RAM.",
                fileUri = "https://huggingface.co/unsloth/Qwen3.5-9B-GGUF/resolve/main/Qwen3.5-9B-Q4_K_M.gguf",
                approximateSize = "5.5 GB",
                modelType = ModelType.GGUF,
                isZip = false,
                runOnCpu = false,
                minRamGb = 8,
                sizeBytes = 5905580032L,
                icon = "qwen",
                iconUrl = "https://unpkg.com/@lobehub/icons-static-png@1.95.0/dark/qwen.png",
                tags = listOf("Chat", "Tool Calling", "Tested")
            ),
            HuggingFaceModel(
                id = "lfm2-350m-q8",
                name = "LFM2 350M",
                description = "Ultra-fast tiny model from Liquid AI. Instant responses, runs on any device.",
                fileUri = "https://huggingface.co/LiquidAI/LFM2-350M-GGUF/resolve/main/LFM2-350M-Q8_0.gguf",
                approximateSize = "400 MB",
                modelType = ModelType.GGUF,
                isZip = false,
                runOnCpu = false,
                minRamGb = 3,
                sizeBytes = 419430400L,
                icon = "liquid",
                iconUrl = "https://unpkg.com/@lobehub/icons-static-png@1.95.0/dark/liquid.png",
                tags = listOf("Chat", "Tested", "Ultra Fast")
            ),
            HuggingFaceModel(
                id = "sd-cpu-sd15",
                name = "Stable Diffusion 1.5 (CPU)",
                description = "Image generation that works on all devices. Uses CPU for inference.",
                fileUri = "https://huggingface.co/xororz/sd-mnn/resolve/main/sd1.5.zip",
                approximateSize = "1.5 GB",
                modelType = ModelType.SD,
                isZip = true,
                runOnCpu = true,
                textEmbeddingSize = 768,
                minRamGb = 4,
                sizeBytes = 1610612736L,
                icon = "stability",
                iconUrl = "https://unpkg.com/@lobehub/icons-static-png@1.95.0/dark/stability.png",
                tags = listOf("Image", "CPU", "Tested")
            ),
            HuggingFaceModel(
                id = "anythingv5_cpu",
                name = "Anything V5 (CPU)",
                description = "Popular anime & illustration diffusion model. Runs on CPU.",
                fileUri = "https://huggingface.co/xororz/sd-mnn/resolve/main/AnythingV5.zip",
                approximateSize = "1.2 GB",
                modelType = ModelType.SD,
                isZip = true,
                runOnCpu = true,
                textEmbeddingSize = 768,
                minRamGb = 4,
                sizeBytes = 1200000000L,
                icon = "stability",
                iconUrl = "https://unpkg.com/@lobehub/icons-static-png@1.95.0/dark/stability.png",
                tags = listOf("Image", "Anime", "CPU", "Tested")
            ),
            HuggingFaceModel(
                id = "qteamix_cpu",
                name = "QteaMix (CPU)",
                description = "Cute chibi & anime style diffusion model. Runs on CPU.",
                fileUri = "https://huggingface.co/xororz/sd-mnn/resolve/main/QteaMix.zip",
                approximateSize = "1.2 GB",
                modelType = ModelType.SD,
                isZip = true,
                runOnCpu = true,
                textEmbeddingSize = 768,
                minRamGb = 4,
                sizeBytes = 1200000000L,
                icon = "stability",
                iconUrl = "https://unpkg.com/@lobehub/icons-static-png@1.95.0/dark/stability.png",
                tags = listOf("Image", "Chibi", "CPU", "Tested")
            ),
            HuggingFaceModel(
                id = "absolutereality_cpu",
                name = "Absolute Reality (CPU)",
                description = "Photorealistic image generation diffusion model. Runs on CPU.",
                fileUri = "https://huggingface.co/xororz/sd-mnn/resolve/main/AbsoluteReality.zip",
                approximateSize = "1.2 GB",
                modelType = ModelType.SD,
                isZip = true,
                runOnCpu = true,
                textEmbeddingSize = 768,
                minRamGb = 4,
                sizeBytes = 1200000000L,
                icon = "stability",
                iconUrl = "https://unpkg.com/@lobehub/icons-static-png@1.95.0/dark/stability.png",
                tags = listOf("Image", "Realistic", "CPU", "Tested")
            ),
            HuggingFaceModel(
                id = "cuteyukimix_cpu",
                name = "CuteYukiMix (CPU)",
                description = "Vibrant anime & character design diffusion model. Runs on CPU.",
                fileUri = "https://huggingface.co/xororz/sd-mnn/resolve/main/CuteYukiMix.zip",
                approximateSize = "1.2 GB",
                modelType = ModelType.SD,
                isZip = true,
                runOnCpu = true,
                textEmbeddingSize = 768,
                minRamGb = 4,
                sizeBytes = 1200000000L,
                icon = "stability",
                iconUrl = "https://unpkg.com/@lobehub/icons-static-png@1.95.0/dark/stability.png",
                tags = listOf("Image", "Anime", "CPU", "Tested")
            ),
            HuggingFaceModel(
                id = "chilloutmix_cpu",
                name = "ChilloutMix (CPU)",
                description = "High quality photo-realistic portraits diffusion model. Runs on CPU.",
                fileUri = "https://huggingface.co/xororz/sd-mnn/resolve/main/ChilloutMix.zip",
                approximateSize = "1.2 GB",
                modelType = ModelType.SD,
                isZip = true,
                runOnCpu = true,
                textEmbeddingSize = 768,
                minRamGb = 4,
                sizeBytes = 1200000000L,
                icon = "stability",
                iconUrl = "https://unpkg.com/@lobehub/icons-static-png@1.95.0/dark/stability.png",
                tags = listOf("Image", "Realistic", "CPU", "Tested")
            ),
            HuggingFaceModel(
                id = "piper-amy-tts",
                name = "Piper US Amy (English TTS)",
                description = "High-quality offline English text-to-speech. Natural-sounding voice synthesis.",
                fileUri = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-piper-en_US-amy-low.tar.bz2",
                approximateSize = "28 MB",
                modelType = ModelType.TTS,
                isZip = false,
                runOnCpu = true,
                minRamGb = 2,
                sizeBytes = 29360128L,
                icon = "tts",
                iconUrl = "https://unpkg.com/@lobehub/icons-static-png@1.95.0/dark/huggingface.png",
                tags = listOf("TTS", "English", "Piper", "Tested")
            ),
            HuggingFaceModel(
                id = "vits-ljs-tts",
                name = "VITS LJSpeech (English TTS)",
                description = "On-device VITS TTS engine. English voice, 22.05kHz, high-quality offline synthesis.",
                fileUri = "https://huggingface.co/csukuangfj/vits-ljs/resolve/main/vits-ljs.onnx",
                approximateSize = "40 MB",
                modelType = ModelType.TTS,
                isZip = false,
                runOnCpu = true,
                minRamGb = 2,
                sizeBytes = 41943040L,
                icon = "tts",
                iconUrl = "https://unpkg.com/@lobehub/icons-static-png@1.95.0/dark/huggingface.png",
                tags = listOf("TTS", "English", "VITS", "Tested")
            ),
            HuggingFaceModel(
                id = "whisper-tiny-stt",
                name = "Whisper Tiny (English STT)",
                description = "On-device speech recognition using Whisper. Fast and accurate English transcription.",
                fileUri = "https://huggingface.co/csukuangfj/sherpa-onnx-whisper-tiny.en/resolve/main",
                approximateSize = "75 MB",
                modelType = ModelType.STT,
                isZip = false,
                runOnCpu = true,
                minRamGb = 2,
                sizeBytes = 78643200L,
                icon = "openai",
                iconUrl = "https://unpkg.com/@lobehub/icons-static-png@1.95.0/dark/openai.png",
                tags = listOf("STT", "English", "Whisper", "Tested")
            )
        )
    }

    private fun parseCuratedJson(jsonString: String): List<HuggingFaceModel> {
        val models = mutableListOf<HuggingFaceModel>()
        try {
            val root = com.google.gson.JsonParser.parseString(jsonString).asJsonObject
            val array = root.getAsJsonArray("models") ?: return models

            array.forEach { element ->
                if (!element.isJsonObject) return@forEach
                val item = element.asJsonObject

                val id = item.get("id")?.asString ?: return@forEach
                val name = item.get("name")?.asString ?: return@forEach
                val description = item.get("description")?.asString ?: ""
                val url = item.get("url")?.asString ?: return@forEach
                val size = item.get("size")?.asString ?: "Unknown"
                val typeStr = item.get("type")?.asString ?: "GGUF"

                val modelType = when (typeStr.uppercase(Locale.US)) {
                    "GGUF", "LLM" -> ModelType.GGUF
                    "SD", "DIFFUSION" -> ModelType.SD
                    "TTS" -> ModelType.TTS
                    "STT" -> ModelType.STT
                    else -> ModelType.GGUF
                }

                val tags = mutableListOf<String>()
                item.getAsJsonArray("tags")?.forEach { t ->
                    if (t.isJsonPrimitive) t.asString.takeIf { it.isNotBlank() }?.let { tags.add(it) }
                }

                models.add(
                    HuggingFaceModel(
                        id = id,
                        name = name,
                        description = description,
                        fileUri = url,
                        approximateSize = size,
                        modelType = modelType,
                        isZip = item.get("isZip")?.asBoolean ?: false,
                        runOnCpu = item.get("runOnCpu")?.asBoolean ?: false,
                        textEmbeddingSize = item.get("textEmbeddingSize")?.asInt ?: 768,
                        tags = tags,
                        requiresNPU = item.get("requiresNPU")?.asBoolean ?: false,
                        chipsetSuffix = item.get("chipsetSuffix")?.asString,
                        repositoryUrl = "",
                        minRamGb = item.get("minRamGb")?.asInt ?: 0,
                        sizeBytes = item.get("sizeBytes")?.asLong ?: 0L,
                        icon = item.get("icon")?.asString,
                        iconUrl = item.get("iconUrl")?.asString
                    )
                )
            }
        } catch (e: Exception) {
            Log.e("ModelStoreRepository", "Error parsing curated JSON", e)
        }
        return models
    }

    private fun loadCuratedDiskCache(): List<HuggingFaceModel>? {
        return try {
            if (!curatedCacheFile.exists()) return null
            val body = curatedCacheFile.readText()
            val models = parseCuratedJson(body)
            models.ifEmpty { null }
        } catch (e: Exception) {
            Log.e("ModelStoreRepository", "Failed to load curated cache", e)
            null
        }
    }

    private suspend fun fetchAndCache(
        repositories: List<HFModelRepository>
    ): Result<List<HuggingFaceModel>> {
        return try {
            val models = mutableListOf<HuggingFaceModel>()

            val enabledRepos = repositories.filter { it.isEnabled }
            val hfRepos = enabledRepos.filter { it.source == RepositorySource.HUGGING_FACE }
            val apiRepos = enabledRepos.filter { it.source == RepositorySource.CUSTOM_API }

            val sdModels = getSDModels(hfRepos.filter { it.modelType == ModelType.SD })
            val ggufModels = getGGUFModels(hfRepos.filter { it.modelType == ModelType.GGUF })
            val apiModels = getApiModels(apiRepos)
            val ttsModels = getTTSModels(hfRepos.filter { it.modelType == ModelType.TTS })
            val sttModels = getSTTModels(hfRepos.filter { it.modelType == ModelType.STT })

            models.addAll(sdModels)
            models.addAll(ggufModels)
            models.addAll(apiModels)
            models.addAll(ttsModels)
            models.addAll(sttModels)

            val modelList = models.toList()
            cachedModels = modelList
            writeDiskCache(modelList)

            Result.success(modelList)
        } catch (e: Exception) {
            Log.e("ModelStoreRepository", "Error loading models", e)
            Result.failure(e)
        }
    }

    private fun loadDiskCache(): List<HuggingFaceModel>? {
        return try {
            if (!cacheFile.exists()) return null
            val cache = json.decodeFromString<ModelStoreCache>(cacheFile.readText())
            // Invalidate cache if filtering logic has changed
            if (cache.cacheVersion < ModelStoreCache.CURRENT_VERSION) {
                cacheFile.delete()
                return null
            }
            cache.models.ifEmpty { null }
        } catch (e: Exception) {
            Log.e("ModelStoreRepository", "Failed to load disk cache", e)
            null
        }
    }

    private fun writeDiskCache(models: List<HuggingFaceModel>) {
        try {
            val cache = ModelStoreCache(
                models = models,
                timestamp = System.currentTimeMillis(),
                cacheVersion = ModelStoreCache.CURRENT_VERSION
            )
            cacheFile.writeText(json.encodeToString(cache))
        } catch (e: Exception) {
            Log.e("ModelStoreRepository", "Failed to write disk cache", e)
        }
    }

    private suspend fun getSDModels(repositories: List<HFModelRepository>): List<HuggingFaceModel> {
        val models = mutableListOf<HuggingFaceModel>()
        val npuSuffixChain = getNpuSuffixChain() // null = non-Qualcomm

        repositories.forEach { repo ->
            try {
                val isNpuRepo = repo.repoPath.contains("qnn", ignoreCase = true)
                // Skip NPU repos on non-Qualcomm devices
                if (isNpuRepo && npuSuffixChain == null) return@forEach

                val response = HuggingFaceClient.api.getRepoFiles(repo.repoPath)
                if (!response.isSuccessful) {
                    Log.e("ModelStoreRepository", "Failed to fetch SD repo ${repo.repoPath}: ${response.code()}")
                    return@forEach
                }

                val files = response.body() ?: emptyList()
                val zipFiles = files.filter { it.path.endsWith(".zip", ignoreCase = true) }

                if (isNpuRepo) {
                    val suffixChain = npuSuffixChain ?: return@forEach
                    val isMinOnly = suffixChain.size == 1 && suffixChain[0] == "min"

                    // Find the best available suffix from the fallback chain
                    var matchingFiles = emptyList<HuggingFaceFileResponse>()
                    var matchedSuffix = suffixChain.last()
                    var matchedPattern = Regex("[_-]${Regex.escape(matchedSuffix)}\\.zip$", RegexOption.IGNORE_CASE)

                    for (suffix in suffixChain) {
                        val pattern = Regex("[_-]${Regex.escape(suffix)}\\.zip$", RegexOption.IGNORE_CASE)
                        val matches = zipFiles.filter { file ->
                            pattern.containsMatchIn(file.path.substringAfterLast("/"))
                        }
                        if (matches.isNotEmpty()) {
                            matchingFiles = matches
                            matchedSuffix = suffix
                            matchedPattern = pattern
                            break
                        }
                    }

                    matchingFiles.forEach { file ->
                        val fileName = file.path.substringAfterLast("/")
                        val baseName = fileName
                            .replace(matchedPattern, "")
                            .replace(Regex("[_-]qnn[\\d.]*$", RegexOption.IGNORE_CASE), "")
                        val sizeStr = formatDecimalBytes(file.size ?: 0)

                        val tags = mutableListOf("NPU", repo.name)
                        if (repo.category == ModelCategory.UNCENSORED) tags.add("NSFW")

                        models.add(
                            HuggingFaceModel(
                                id = "${repo.id}-${baseName.lowercase()}",
                                name = baseName,
                                description = "$baseName image generation for Qualcomm NPU",
                                fileUri = "${repo.repoPath}/resolve/main/${file.path}",
                                approximateSize = sizeStr,
                                modelType = ModelType.SD,
                                isZip = true,
                                chipsetSuffix = matchedSuffix,
                                runOnCpu = isMinOnly,
                                textEmbeddingSize = 768,
                                tags = tags,
                                requiresNPU = !isMinOnly,
                                repositoryUrl = repo.repoPath
                            )
                        )
                    }
                } else {
                    // CPU repo: show all zips
                    zipFiles.forEach { file ->
                        val fileName = file.path.substringAfterLast("/")
                        val baseName = fileName.removeSuffix(".zip").removeSuffix(".ZIP")
                        val sizeStr = formatDecimalBytes(file.size ?: 0)

                        models.add(
                            HuggingFaceModel(
                                id = "${repo.id}-${baseName.lowercase()}",
                                name = baseName,
                                description = "$baseName image generation (CPU)",
                                fileUri = "${repo.repoPath}/resolve/main/${file.path}",
                                approximateSize = sizeStr,
                                modelType = ModelType.SD,
                                isZip = true,
                                runOnCpu = true,
                                textEmbeddingSize = 768,
                                tags = mutableListOf("CPU", repo.name).apply {
                                    if (repo.category == ModelCategory.UNCENSORED) add("NSFW")
                                },
                                requiresNPU = false,
                                repositoryUrl = repo.repoPath
                            )
                        )
                    }
                }
            } catch (e: Exception) {
                Log.e("ModelStoreRepository", "Error fetching SD models from ${repo.repoPath}", e)
            }
        }

        return models
    }

    private suspend fun getTTSModels(repositories: List<HFModelRepository>): List<HuggingFaceModel> {
        val models = mutableListOf<HuggingFaceModel>()

        // 1. Add Default Presets (VITS LJSpeech & Piper Amy low & Kokoro)
        models.add(
            HuggingFaceModel(
                id = "vits-ljs-tts",
                name = "VITS LJSpeech (English TTS)",
                description = "On-device VITS TTS engine: English voice, 22.05kHz, high-quality offline synthesis",
                fileUri = "csukuangfj/vits-ljs/resolve/main/vits-ljs.onnx",
                approximateSize = "40 MB",
                modelType = ModelType.TTS,
                isZip = false,
                runOnCpu = true,
                textEmbeddingSize = 0,
                tags = listOf("TTS", "English", "VITS", "sherpa-onnx"),
                requiresNPU = false,
                repositoryUrl = "csukuangfj/vits-ljs"
            )
        )

        models.add(
            HuggingFaceModel(
                id = "vits-piper-en_us-amy-low",
                name = "Piper US Amy (English TTS)",
                description = "Pre-packaged Piper Amy low voice model (16kHz), highly natural offline speech synthesis",
                fileUri = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-piper-en_US-amy-low.tar.bz2",
                approximateSize = "28 MB",
                modelType = ModelType.TTS,
                isZip = false, // Download service will detect .tar.bz2 extension and extract it
                runOnCpu = true,
                textEmbeddingSize = 0,
                tags = listOf("TTS", "English", "Piper", "sherpa-onnx"),
                requiresNPU = false,
                repositoryUrl = "csukuangfj/vits-piper-en_US-amy-low"
            )
        )



        // 2. Scan enabled TTS repositories
        repositories.forEach { repo ->
            try {
                val response = HuggingFaceClient.api.getRepoFiles(repo.repoPath)
                if (response.isSuccessful) {
                    val files = response.body() ?: emptyList()
                    val onnxFiles = files.filter { it.path.endsWith(".onnx", ignoreCase = true) }

                    onnxFiles.forEach { file ->
                        val fileName = file.path.substringAfterLast("/")
                        val baseName = fileName.removeSuffix(".onnx").removeSuffix(".ONNX")
                        val sizeStr = formatDecimalBytes(file.size ?: 0)

                        val pathParts = file.path.split("/")
                        val tags = mutableListOf("TTS", repo.name, "sherpa-onnx")
                        if (pathParts.size >= 4) {
                            tags.add(pathParts[1]) // language/locale
                            tags.add(pathParts[2]) // voice name
                            tags.add(pathParts[3]) // quality
                        }

                        val description = when {
                            repo.repoPath.contains("piper", ignoreCase = true) ->
                                "Piper offline TTS model: $baseName. High-quality speech synthesis."
                            repo.repoPath.contains("Kokoro", ignoreCase = true) ->
                                "Kokoro offline TTS model: $baseName. Highly natural speech synthesis."
                            else -> "${repo.name} TTS model: $baseName."
                        }

                        models.add(
                            HuggingFaceModel(
                                id = "${repo.id}-${baseName.lowercase()}",
                                name = baseName.replace("-", " ").replace("_", " "),
                                description = description,
                                fileUri = "${repo.repoPath}/resolve/main/${file.path}",
                                approximateSize = sizeStr,
                                modelType = ModelType.TTS,
                                isZip = false,
                                runOnCpu = true,
                                textEmbeddingSize = 0,
                                tags = tags.distinct(),
                                requiresNPU = false,
                                repositoryUrl = repo.repoPath
                            )
                        )
                    }
                }
            } catch (e: Exception) {
                Log.e("ModelStoreRepository", "Error fetching TTS models from ${repo.repoPath}", e)
            }
        }

        return models
    }

    private suspend fun getSTTModels(repositories: List<HFModelRepository>): List<HuggingFaceModel> {
        val models = mutableListOf<HuggingFaceModel>()

        // 1. Add Default Presets (Whisper Tiny English)
        models.add(
            HuggingFaceModel(
                id = "sherpa-whisper-tiny",
                name = "Whisper Tiny (English)",
                description = "On-device Whisper Speech-to-Text recognizer model using Sherpa ONNX · ~75 MB",
                fileUri = "https://huggingface.co/csukuangfj/sherpa-onnx-whisper-tiny.en/resolve/main",
                approximateSize = "75 MB",
                modelType = ModelType.STT,
                isZip = false,
                runOnCpu = true,
                textEmbeddingSize = 0,
                tags = listOf("STT", "English", "Whisper", "sherpa-onnx"),
                requiresNPU = false,
                repositoryUrl = "csukuangfj/sherpa-onnx-whisper-tiny.en"
            )
        )

        // 2. Scan enabled STT repositories
        repositories.forEach { repo ->
            try {
                val response = HuggingFaceClient.api.getRepoFiles(repo.repoPath)
                if (response.isSuccessful) {
                    val files = response.body() ?: emptyList()
                    val onnxFiles = files.filter { it.path.endsWith(".onnx", ignoreCase = true) }

                    onnxFiles.forEach { file ->
                        val fileName = file.path.substringAfterLast("/")
                        val baseName = fileName.removeSuffix(".onnx").removeSuffix(".ONNX")
                        val sizeStr = formatDecimalBytes(file.size ?: 0)

                        val tags = mutableListOf("STT", repo.name, "sherpa-onnx")
                        models.add(
                            HuggingFaceModel(
                                id = "${repo.id}-${baseName.lowercase()}",
                                name = baseName.replace("-", " ").replace("_", " "),
                                description = "${repo.name} STT model: $baseName.",
                                fileUri = "${repo.repoPath}/resolve/main/${file.path}",
                                approximateSize = sizeStr,
                                modelType = ModelType.STT,
                                isZip = false,
                                runOnCpu = true,
                                textEmbeddingSize = 0,
                                tags = tags.distinct(),
                                requiresNPU = false,
                                repositoryUrl = repo.repoPath
                            )
                        )
                    }
                }
            } catch (e: Exception) {
                Log.e("ModelStoreRepository", "Error fetching STT models from ${repo.repoPath}", e)
            }
        }

        return models
    }

    private suspend fun getGGUFModels(repositories: List<HFModelRepository>): List<HuggingFaceModel> {
        val models = mutableListOf<HuggingFaceModel>()

        repositories.forEach { repo ->
            try {
                val response = HuggingFaceClient.api.getRepoFiles(repo.repoPath)

                if (response.isSuccessful) {
                    val files = response.body() ?: emptyList()

                    // Detect if this repo supports tool calling (Qwen/ChatML models)
                    val supportsToolCalling = repo.repoPath.contains("qwen", ignoreCase = true) ||
                            repo.repoPath.contains("Qwen", ignoreCase = false) ||
                            repo.name.contains("qwen", ignoreCase = true)

                    val isSmallRepo = repo.repoPath.contains("350m", ignoreCase = true) ||
                            repo.name.contains("350m", ignoreCase = true) ||
                            repo.id.contains("350m", ignoreCase = true)

                    files.filter { file ->
                        file.path.endsWith(".gguf") &&
                                // Filter out mmproj/vision projection files - these are not standalone models
                                !file.path.contains("mmproj", ignoreCase = true) &&
                                !file.path.contains("vision-adapter", ignoreCase = true) &&
                                !file.path.contains("projector", ignoreCase = true) &&
                                !(isSmallRepo && file.path.contains("q4_0", ignoreCase = true))
                    }.forEach { file ->
                            val fileName = file.path.substringAfterLast("/")
                            val sizeStr = formatDecimalBytes(file.size ?: 0)

                            // Extract quantization type from filename
                            val quantType =
                                fileName.substringAfterLast("-").removeSuffix(".gguf").uppercase()

                            val baseTags = mutableListOf("GGUF", quantType, repo.name)
                            if (supportsToolCalling) {
                                baseTags.add("Tool Calling")
                            }

                            models.add(
                                HuggingFaceModel(
                                    id = "${repo.id}-${fileName.removeSuffix(".gguf")}",
                                    name = "${repo.name} - $quantType",
                                    description = "${repo.name} model with $quantType quantization",
                                    fileUri = "${repo.repoPath}/resolve/main/${file.path}",
                                    approximateSize = sizeStr,
                                    modelType = ModelType.GGUF,
                                    isZip = false,
                                    runOnCpu = false,
                                    textEmbeddingSize = 0,
                                    tags = baseTags,
                                    requiresNPU = false,
                                    repositoryUrl = repo.repoPath
                                )
                            )
                        }
                } else {
                    Log.e(
                        "ModelStoreRepository",
                        "Failed to fetch from ${repo.repoPath}: ${response.code()}"
                    )
                }
            } catch (e: Exception) {
                Log.e("ModelStoreRepository", "Error fetching GGUF models from ${repo.repoPath}", e)
            }
        }

        return models
    }

    private suspend fun getApiModels(repositories: List<HFModelRepository>): List<HuggingFaceModel> {
        val models = mutableListOf<HuggingFaceModel>()

        repositories.forEach { repo ->
            if (repo.apiBaseUrl.isBlank()) {
                Log.w("ModelStoreRepository", "Skipping API repo ${repo.name}: apiBaseUrl is blank")
                return@forEach
            }

            try {
                val response = ExternalModelApiClient.fetchCatalog(repo)
                if (!response.isSuccessful) {
                    Log.e("ModelStoreRepository", "API repo ${repo.name} failed: ${response.code()}")
                    return@forEach
                }

                val body = response.body()
                if (body == null || body.isJsonNull) return@forEach

                val entries: JsonArray = when {
                    body.isJsonArray -> body.asJsonArray
                    body.isJsonObject -> {
                        val obj = body.asJsonObject
                        when {
                            obj.has("models") && obj.get("models").isJsonArray -> obj.getAsJsonArray("models")
                            obj.has("data") && obj.get("data").isJsonArray -> obj.getAsJsonArray("data")
                            else -> JsonArray()
                        }
                    }
                    else -> JsonArray()
                }

                entries.forEachIndexed { index, jsonElement ->
                    if (!jsonElement.isJsonObject) return@forEachIndexed
                    val item = jsonElement.asJsonObject

                    val modelIdRaw = item.stringValue("id")
                        ?: item.stringValue("model_id")
                        ?: item.stringValue("name")
                        ?: "model_${index + 1}"

                    val modelName = item.stringValue("name")
                        ?: item.stringValue("title")
                        ?: modelIdRaw

                    val fileUri = item.stringValue("downloadUrl")
                        ?: item.stringValue("download_url")
                        ?: item.stringValue("url")
                        ?: item.stringValue("fileUri")
                        // Ollama /api/tags doesn't provide a download URL.
                        // For remote API models we can still install by storing
                        // the inference endpoint directly. Prefer /api/chat for
                        // chat-first UX, while still allowing endpoint hints.
                        ?: run {
                            val hasModelKey = item.stringValue("model") != null || 
                                              item.stringValue("name") != null || 
                                              item.stringValue("id") != null
                            if (hasModelKey) {
                                val base = repo.apiBaseUrl.trim().removeSuffix("/")
                                val hintedPath = item.stringValue("endpoint")
                                    ?: item.stringValue("chatEndpoint")
                                if (!hintedPath.isNullOrBlank()) {
                                    if (hintedPath.startsWith("http://") || hintedPath.startsWith("https://")) {
                                        hintedPath
                                    } else {
                                        "$base/${hintedPath.removePrefix("/")}"
                                    }
                                } else {
                                    val baseClean = base.removeSuffix("/")
                                    val baseLower = baseClean.lowercase(java.util.Locale.US)
                                    if (baseLower.contains("openrouter.ai") ||
                                        baseLower.contains("openai.com") ||
                                        baseLower.contains("googleapis.com") ||
                                        baseLower.contains("groq.com") ||
                                        baseLower.contains("nvidia.com") ||
                                        baseLower.contains("deepinfra.com") ||
                                        baseLower.contains("together.xyz") ||
                                        baseLower.contains("mistral.ai") ||
                                        baseLower.contains("/v1")
                                    ) {
                                        if (baseLower.contains("/v1")) {
                                            "$baseClean/chat/completions"
                                        } else {
                                            "$baseClean/v1/chat/completions"
                                        }
                                    } else {
                                        "$baseClean/api/chat"
                                    }
                                }
                            } else {
                                null
                            }
                        }
                        ?: return@forEachIndexed

                    val description = item.stringValue("description")
                        ?: item.stringValue("details")
                        ?: "${repo.name} model from custom API"

                    val declaredType = item.stringValue("modelType")
                        ?: item.stringValue("type")
                        ?: repo.modelType.name

                    val modelType = parseModelTypeOrDefault(declaredType, repo.modelType)

                    val approximateSize = item.stringValue("approximateSize")
                        ?: item.stringValue("size")
                        ?: item.stringValue("sizeLabel")
                        ?: item.longValue("sizeBytes")?.let { formatDecimalBytes(it) }
                        ?: "Unknown"

                    val isLikelyOllama = item.stringValue("model") != null && item.stringValue("name") != null
                    val tags = item.arrayOfStrings("tags") + listOf("API", repo.name) + if (isLikelyOllama) listOf("Ollama") else emptyList()

                    models.add(
                        HuggingFaceModel(
                            id = "${repo.id}-${modelIdRaw.replace(" ", "-").lowercase(Locale.US)}",
                            name = modelName,
                            description = description,
                            fileUri = fileUri,
                            approximateSize = approximateSize,
                            modelType = modelType,
                            isZip = item.booleanValue("isZip") ?: fileUri.endsWith(".zip", ignoreCase = true),
                            runOnCpu = item.booleanValue("runOnCpu") ?: false,
                            textEmbeddingSize = item.intValue("textEmbeddingSize") ?: 768,
                            tags = tags.distinct(),
                            requiresNPU = item.booleanValue("requiresNPU") ?: false,
                            repositoryUrl = repo.apiBaseUrl
                        )
                    )
                }
            } catch (e: Exception) {
                Log.e("ModelStoreRepository", "Error fetching API models from ${repo.name}", e)
            }
        }

        return models
    }

    private fun parseModelTypeOrDefault(raw: String, fallback: ModelType): ModelType {
        return when (raw.trim().uppercase(Locale.US)) {
            "GGUF", "LLM" -> ModelType.GGUF
            "SD", "DIFFUSION", "STABLE_DIFFUSION" -> ModelType.SD
            "TTS" -> ModelType.TTS
            "STT" -> ModelType.STT
            else -> fallback
        }
    }

    private fun JsonObject.stringValue(key: String): String? {
        if (!has(key)) return null
        val v = get(key)
        return if (v != null && v.isJsonPrimitive) v.asString.takeIf { it.isNotBlank() } else null
    }

    private fun JsonObject.booleanValue(key: String): Boolean? {
        if (!has(key)) return null
        val v = get(key)
        return if (v != null && v.isJsonPrimitive) runCatching { v.asBoolean }.getOrNull() else null
    }

    private fun JsonObject.intValue(key: String): Int? {
        if (!has(key)) return null
        val v = get(key)
        return if (v != null && v.isJsonPrimitive) runCatching { v.asInt }.getOrNull() else null
    }

    private fun JsonObject.longValue(key: String): Long? {
        if (!has(key)) return null
        val v = get(key)
        return if (v != null && v.isJsonPrimitive) runCatching { v.asLong }.getOrNull() else null
    }

    private fun JsonObject.arrayOfStrings(key: String): List<String> {
        if (!has(key)) return emptyList()
        val v = get(key)
        if (v == null || !v.isJsonArray) return emptyList()
        return v.asJsonArray.mapNotNull { item ->
            if (item.isJsonPrimitive) item.asString.takeIf { it.isNotBlank() } else null
        }
    }

}