package com.bit.viewmodel

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.bit.data.AppSettingsDataStore
import com.bit.data.SetupDataStore
import com.bit.data.VaultManager
import com.bit.di.AppContainer
import com.bit.global.HardwareScanner
import com.bit.models.data.HuggingFaceModel
import com.bit.models.data.ModelType
import com.bit.models.enums.ProviderType
import com.bit.global.PerformanceMode
import com.bit.global.HardwareProfile
import com.bit.global.CpuTopology
import com.bit.repo.ModelStoreRepository
import com.bit.service.ModelDownloadService
import com.bit.worker.SystemBackupManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

enum class SetupOption {
    TEXT,
    TEXT_RECOMMENDED,
    TEXT_TTS,
    IMAGE_GEN,
    POWER_MODE
}

class SetupViewModel(application: Application) : AndroidViewModel(application) {

    private val setupDataStore = SetupDataStore(application)
    private val appSettingsDataStore = AppSettingsDataStore(application)
    private val modelStoreRepository = ModelStoreRepository(application)

    // ModelRepository is deferred until vault is ready
    private val modelRepository get() = AppContainer.getModelRepository()

    val downloadStates = ModelDownloadService.downloadStates

    private val _selectedOption = MutableStateFlow<SetupOption?>(null)
    val selectedOption: StateFlow<SetupOption?> = _selectedOption

    private val _setupComplete = MutableStateFlow(false)
    val setupComplete: StateFlow<Boolean> = _setupComplete

    private val _downloadError = MutableStateFlow<String?>(null)
    val downloadError: StateFlow<String?> = _downloadError

    private val _primaryModelId = MutableStateFlow<String?>(null)
    val primaryModelId: StateFlow<String?> = _primaryModelId

    private val _showPerformancePicker = MutableStateFlow(false)
    val showPerformancePicker: StateFlow<Boolean> = _showPerformancePicker

    private val _selectedPerformanceMode = MutableStateFlow(PerformanceMode.BALANCED)
    val selectedPerformanceMode: StateFlow<PerformanceMode> = _selectedPerformanceMode

    // ==================== Setup Model Definitions ====================

    private val llama1bModel = HuggingFaceModel(
        id = "unsloth-llama-3_2-1b-instruct-q4_k_m",
        name = "Llama-3.2 1B Instruct",
        description = "Highly optimized, state-of-the-art Llama-3.2 1B text model",
        fileUri = "unsloth/Llama-3.2-1B-Instruct-GGUF/resolve/main/Llama-3.2-1B-Instruct-Q4_K_M.gguf",
        approximateSize = "640 MB",
        modelType = ModelType.GGUF,
        isZip = false,
        tags = listOf("GGUF", "Q4_K_M", "Llama-3.2 (1B)", "Tool Calling"),
        requiresNPU = false,
        repositoryUrl = "unsloth/Llama-3.2-1B-Instruct-GGUF"
    )

    private val llama3bModel = HuggingFaceModel(
        id = "unsloth-llama-3_2-3b-instruct-q4_k_m",
        name = "Llama-3.2 3B Instruct",
        description = "Powerful, state-of-the-art Llama-3.2 3B text model",
        fileUri = "unsloth/Llama-3.2-3B-Instruct-GGUF/resolve/main/Llama-3.2-3B-Instruct-Q4_K_M.gguf",
        approximateSize = "2.0 GB",
        modelType = ModelType.GGUF,
        isZip = false,
        tags = listOf("GGUF", "Q4_K_M", "Llama-3.2 (3B)", "Tool Calling"),
        requiresNPU = false,
        repositoryUrl = "unsloth/Llama-3.2-3B-Instruct-GGUF"
    )

    private val ttsModel = HuggingFaceModel(
        id = "kokoro-multi-lang-v1_0",
        name = "Kokoro v1.0 (TTS)",
        description = "Frontier-class Kokoro speech synthesis model (24kHz, 53 voices)",
        fileUri = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/kokoro-multi-lang-v1_0.tar.bz2",
        approximateSize = "340 MB",
        modelType = ModelType.TTS,
        isZip = false,
        runOnCpu = true,
        textEmbeddingSize = 0,
        tags = listOf("TTS", "Kokoro", "sherpa-onnx"),
        requiresNPU = false,
        repositoryUrl = "csukuangfj/kokoro-multi-lang-v1_0"
    )

    private fun getImageModel(): HuggingFaceModel {
        val isQualcomm = modelStoreRepository.isQualcommDevice()
        return HuggingFaceModel(
            id = "absolutereality-sd",
            name = "AbsoluteReality",
            description = "Realistic image generation",
            fileUri = "xororz/sd-qnn/resolve/main/AbsoluteReality_qnn2.28_min.zip",
            approximateSize = "1.1 GB",
            modelType = ModelType.SD,
            isZip = true,
            runOnCpu = !isQualcomm,
            textEmbeddingSize = 768,
            tags = if (isQualcomm) listOf("NPU", "Realistic") else listOf("CPU", "Realistic"),
            requiresNPU = false,
            repositoryUrl = "xororz/sd-qnn"
        )
    }

    private val _recommendedTextModel = MutableStateFlow<HuggingFaceModel>(llama1bModel)
    val recommendedTextModel: StateFlow<HuggingFaceModel> = _recommendedTextModel

    companion object {
        private const val TAG = "SetupVM"
        private const val PROFILE_MAX_AGE_MS = 30L * 24 * 60 * 60 * 1000 // 30 days
    }

    // ==================== Initialization ====================

    init {
        // Auto-init plaintext vault if not ready
        if (!VaultManager.isReady.value) {
            viewModelScope.launch(Dispatchers.IO) {
                try {
                    VaultManager.initPlaintext(getApplication())
                    AppContainer.ensureVaultInitialized()
                } catch (e: Exception) {
                    Log.e(TAG, "Auto vault init failed", e)
                }
            }
        }

        // Scan hardware if no profile exists or profile is stale (>30 days)
        viewModelScope.launch(Dispatchers.IO) {
            scanHardwareIfNeeded()
            determineRecommendedModel()
        }

        // Resume active setup downloads if any
        if (VaultManager.isReady.value) {
            resumeActiveDownloads()
            watchModelInstallations()
        }

        // Watch for vault readiness to start model monitoring
        viewModelScope.launch {
            VaultManager.isReady.collect { ready ->
                if (ready) {
                    resumeActiveDownloads()
                    watchModelInstallations()
                }
            }
        }

        // Watch for download errors
        viewModelScope.launch {
            downloadStates.collect { states ->
                val primaryId = _primaryModelId.value ?: return@collect
                val state = states[primaryId]
                if (state is ModelDownloadService.DownloadState.Error) {
                    _downloadError.value = state.message
                    _selectedOption.value = null
                    _primaryModelId.value = null
                }
            }
        }
    }

    private fun watchModelInstallations() {
        viewModelScope.launch {
            modelRepository.getAllModels().collect { models ->
                if (_selectedOption.value != null && _selectedOption.value != SetupOption.POWER_MODE && !_setupComplete.value) {
                    val hasTextOrImage = models.any {
                        it.providerType == ProviderType.GGUF || it.providerType == ProviderType.DIFFUSION
                    }
                    if (hasTextOrImage) {
                        appSettingsDataStore.savePerformanceMode(PerformanceMode.BALANCED)
                        setupDataStore.completeSetup()
                        _setupComplete.value = true
                    }
                }
            }
        }
    }

    private fun resumeActiveDownloads() {
        val currentStates = downloadStates.value
        val imageModelId = getImageModel().id
        val recommendedModel = recommendedTextModel.value

        when {
            currentStates.containsKey(recommendedModel.id) && currentStates.containsKey(ttsModel.id) -> {
                _selectedOption.value = SetupOption.TEXT_TTS
                _primaryModelId.value = recommendedModel.id
            }
            currentStates.containsKey(llama1bModel.id) && currentStates.containsKey(ttsModel.id) -> {
                _selectedOption.value = SetupOption.TEXT_TTS
                _primaryModelId.value = llama1bModel.id
            }
            currentStates.containsKey(llama1bModel.id) -> {
                _selectedOption.value = SetupOption.TEXT
                _primaryModelId.value = llama1bModel.id
            }
            currentStates.containsKey(llama3bModel.id) -> {
                _selectedOption.value = SetupOption.TEXT_RECOMMENDED
                _primaryModelId.value = llama3bModel.id
            }
            currentStates.containsKey(imageModelId) -> {
                _selectedOption.value = SetupOption.IMAGE_GEN
                _primaryModelId.value = imageModelId
            }
        }
    }

    // ==================== Actions ====================

    fun selectOption(option: SetupOption) {
        if (_selectedOption.value != null) return

        _selectedOption.value = option
        _downloadError.value = null

        when (option) {
            SetupOption.TEXT -> {
                _primaryModelId.value = llama1bModel.id
                downloadModel(llama1bModel)
            }
            SetupOption.TEXT_RECOMMENDED -> {
                val model = recommendedTextModel.value
                _primaryModelId.value = model.id
                downloadModel(model)
            }
            SetupOption.TEXT_TTS -> {
                _primaryModelId.value = llama1bModel.id
                downloadModel(llama1bModel)
                downloadModel(ttsModel)
            }
            SetupOption.IMAGE_GEN -> {
                val imageModel = getImageModel()
                _primaryModelId.value = imageModel.id
                downloadModel(imageModel)
            }
            SetupOption.POWER_MODE -> {
                viewModelScope.launch {
                    appSettingsDataStore.savePerformanceMode(PerformanceMode.BALANCED)
                    setupDataStore.skipSetup()
                    _setupComplete.value = true
                }
            }
        }
    }

    fun selectPerformanceMode(mode: PerformanceMode) {
        _selectedPerformanceMode.value = mode
    }

    fun confirmPerformanceMode() {
        viewModelScope.launch {
            appSettingsDataStore.savePerformanceMode(_selectedPerformanceMode.value)
            setupDataStore.completeSetup()
            _setupComplete.value = true
        }
    }

    fun retryDownload() {
        val lastOption = _selectedOption.value
        _selectedOption.value = null
        _downloadError.value = null
        _primaryModelId.value = null
        if (lastOption != null) {
            selectOption(lastOption)
        }
    }

    fun cancelDownload() {
        val primaryId = _primaryModelId.value ?: return
        val context = getApplication<Application>()
        val intent = Intent(context, ModelDownloadService::class.java).apply {
            action = ModelDownloadService.ACTION_CANCEL_DOWNLOAD
            putExtra(ModelDownloadService.EXTRA_MODEL_ID, primaryId)
        }
        context.startService(intent)
        _selectedOption.value = null
        _primaryModelId.value = null
        _downloadError.value = null
    }

    private fun downloadModel(model: HuggingFaceModel) {
        val context = getApplication<Application>()
        val fileUrl = if (model.fileUri.startsWith("http://") || model.fileUri.startsWith("https://")) {
            model.fileUri
        } else {
            "https://huggingface.co/${model.fileUri}"
        }

        val intent = Intent(context, ModelDownloadService::class.java).apply {
            action = ModelDownloadService.ACTION_START_DOWNLOAD
            putExtra(ModelDownloadService.EXTRA_MODEL_ID, model.id)
            putExtra(ModelDownloadService.EXTRA_MODEL_NAME, model.name)
            putExtra(ModelDownloadService.EXTRA_FILE_URL, fileUrl)
            putExtra(ModelDownloadService.EXTRA_IS_ZIP, model.isZip)
            putExtra(ModelDownloadService.EXTRA_MODEL_TYPE, model.modelType.name)
            putExtra(ModelDownloadService.EXTRA_RUN_ON_CPU, model.runOnCpu)
            putExtra(ModelDownloadService.EXTRA_TEXT_EMBEDDING_SIZE, model.textEmbeddingSize)
        }

        androidx.core.content.ContextCompat.startForegroundService(context, intent)
    }

    // ==================== Restore from Backup ====================

    private val _restoreProgress = MutableStateFlow<SystemBackupManager.BackupProgress?>(null)
    val restoreProgress: StateFlow<SystemBackupManager.BackupProgress?> = _restoreProgress

    fun restoreFromBackup(uri: Uri, password: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val manager = SystemBackupManager(getApplication())
            val success = manager.restoreBackup(uri, password) { progress ->
                _restoreProgress.value = progress
            }
            if (success) {
                setupDataStore.completeSetup()
                _setupComplete.value = true
            }
        }
    }

    // ==================== Hardware Scan ====================

    private val lenientJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private suspend fun scanHardwareIfNeeded() {
        try {
            val existingJson = appSettingsDataStore.hardwareProfileJson.firstOrNull()
            val needsScan = if (existingJson.isNullOrBlank()) {
                true
            } else {
                val profile = lenientJson.decodeFromString<com.bit.global.HardwareProfile>(existingJson)
                System.currentTimeMillis() - profile.scanTimestamp > PROFILE_MAX_AGE_MS
            }
            if (needsScan) {
                val profile = HardwareScanner.scan(getApplication())
                val json = lenientJson.encodeToString(profile)
                appSettingsDataStore.saveHardwareProfile(json)
                val topo = profile.cpuTopology
                val coreInfo = if (topo.scanSucceeded) "${topo.primeCoreCount}P+${topo.performanceCoreCount}P+${topo.efficiencyCoreCount}E" else "${profile.cpuCores}"
                Log.d(TAG, "Hardware profile scanned: ${profile.totalRamMB}MB RAM, $coreInfo cores")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Hardware scan failed", e)
        }
    }

    private suspend fun determineRecommendedModel() {
        try {
            val existingJson = appSettingsDataStore.hardwareProfileJson.firstOrNull()
            val profile = if (!existingJson.isNullOrBlank()) {
                lenientJson.decodeFromString<HardwareProfile>(existingJson)
            } else {
                val p = HardwareScanner.scan(getApplication())
                val json = lenientJson.encodeToString(p)
                appSettingsDataStore.saveHardwareProfile(json)
                p
            }
            val ramGb = profile.totalRamMB / 1024.0
            val topo = profile.cpuTopology
            val primeCores = if (topo.scanSucceeded) topo.primeCoreCount else 0

            val recommended = when {
                ramGb < 10.0 -> llama1bModel
                ramGb >= 10.0 -> {
                    if (primeCores > 0) {
                        llama3bModel
                    } else {
                        llama1bModel
                    }
                }
                else -> llama1bModel
            }

            _recommendedTextModel.value = recommended
            Log.d(TAG, "Recommended text model determined: ${recommended.name} (RAM: ${profile.totalRamMB}MB, Prime cores: $primeCores)")
        } catch (e: Exception) {
            Log.e(TAG, "Error determining recommended model", e)
            _recommendedTextModel.value = llama1bModel
        }
    }

    fun configureRemoteApi(provider: String, baseUrl: String, modelName: String, apiKey: String) {
        viewModelScope.launch {
            val providerClean = provider.lowercase(java.util.Locale.US)
            val modelClean = modelName.lowercase(java.util.Locale.US).replace(" ", "-")
            val modelId = "api-$providerClean-$modelClean"

            // 1. Create and insert Model record
            val model = com.bit.models.table_schema.Model(
                id = modelId,
                modelName = "$modelName ($provider)",
                modelPath = baseUrl.trim(),
                pathType = com.bit.models.enums.PathType.FILE,
                providerType = com.bit.models.enums.ProviderType.API,
                fileSize = null,
                isActive = true
            )
            modelRepository.insertModel(model)

            // 2. Create and insert ModelConfig record
            val loadingJson = org.json.JSONObject().apply {
                put("endpoint", baseUrl.trim())
                put("model", modelName.trim())
                put("stream", true)
                put("authHeader", apiKey.trim())
            }.toString()

            val config = com.bit.models.table_schema.ModelConfig(
                modelId = modelId,
                modelLoadingParams = loadingJson,
                modelInferenceParams = "{}"
            )
            modelRepository.insertConfig(config)

            // 3. Mark as the last/active text model
            appSettingsDataStore.saveLastModelId(modelId)

            // 4. Complete setup
            appSettingsDataStore.savePerformanceMode(PerformanceMode.BALANCED)
            setupDataStore.completeSetup()
            _setupComplete.value = true
        }
    }
}

