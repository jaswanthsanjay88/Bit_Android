package com.bit.viewmodel

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.bit.data.AppSettingsDataStore
import com.bit.data.HuggingFaceTokenManager
import com.bit.di.AppContainer
import com.bit.global.DeviceTuner
import com.bit.global.HardwareProfile
import com.bit.global.HardwareScanner
import com.bit.global.PerformanceMode
import com.bit.models.engine_schema.GgufEngineSchema
import com.bit.models.enums.ProviderType
import com.bit.models.table_schema.Model
import com.bit.network.HuggingFaceApi
import com.bit.plugins.PluginManager
import com.bit.service.ModelDownloadService
import com.bit.state.AppStateManager
import com.bit.tts.TTSDataStore
import com.bit.tts.TTSManager
import com.bit.tts.TTSSettings
import com.bit.ui.screen.settings.HfTestResult
import com.bit.ui.screen.settings.HfTokenState
import com.bit.worker.SystemBackupManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

class SettingsViewModel(application: Application) : AndroidViewModel(application) {

    private val profileJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val appSettingsDataStore = AppSettingsDataStore(application)
    private val ttsDataStore = TTSDataStore(application)

    private val modelRepository = AppContainer.getModelRepository()

    // ── HuggingFace Token ──
    private val hfTokenManager = HuggingFaceTokenManager(application)
    private val _hfTokenState = MutableStateFlow(
        if (hfTokenManager.hasToken()) HfTokenState.SET else HfTokenState.NOT_SET
    )
    val hfTokenState: StateFlow<HfTokenState> = _hfTokenState

    private val _hfTestResult = MutableStateFlow<HfTestResult?>(null)
    val hfTestResult: StateFlow<HfTestResult?> = _hfTestResult

    init {
        // Sync bypass setting with PluginManager on startup
        viewModelScope.launch {
            appSettingsDataStore.toolCallingBypassEnabled.collect { enabled ->
                PluginManager.setToolCallingBypassEnabled(enabled)
            }
        }

        // Dynamic synchronization of HuggingFace token from DataStore
        viewModelScope.launch {
            appSettingsDataStore.huggingFaceToken.collect { token ->
                _hfTokenState.value = if (!token.isNullOrBlank()) HfTokenState.SET else HfTokenState.NOT_SET
            }
        }
    }

    fun saveHfToken(token: String) {
        _hfTokenState.value = HfTokenState.SAVING
        hfTokenManager.saveToken(token)
        viewModelScope.launch {
            appSettingsDataStore.saveHuggingFaceToken(token)
        }
        _hfTokenState.value = HfTokenState.SET
        _hfTestResult.value = null  // Clear old test result
    }

    fun clearHfToken() {
        hfTokenManager.clearToken()
        viewModelScope.launch {
            appSettingsDataStore.saveHuggingFaceToken(null)
        }
        _hfTokenState.value = HfTokenState.NOT_SET
        _hfTestResult.value = null
    }

    fun testHfConnection() {
        viewModelScope.launch {
            _hfTestResult.value = HfTestResult.Testing
            withContext(Dispatchers.IO) {
                try {
                    val api = AppContainer.huggingFaceApi
                    val response = api.whoami()
                    if (response.isSuccessful) {
                        val user = response.body()
                        _hfTestResult.value = HfTestResult.Success(
                            username = user?.name ?: user?.fullname ?: "Unknown"
                        )
                    } else {
                        _hfTestResult.value = HfTestResult.Failed(
                            error = when (response.code()) {
                                401 -> "Invalid or expired token"
                                403 -> "Insufficient permissions"
                                else -> "HTTP ${response.code()}"
                            }
                        )
                    }
                } catch (e: Exception) {
                    _hfTestResult.value = HfTestResult.Failed(
                        error = e.message ?: "Connection failed"
                    )
                }
            }
        }
    }

    // Installed models
    val installedModels: Flow<List<Model>> = modelRepository.getAllModels()

    // TTS install state
    val hasTtsModel: StateFlow<Boolean> = modelRepository.getAllModels()
        .map { models -> models.any { it.providerType == ProviderType.TTS } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    val installedTtsModelId: Flow<String?> = modelRepository.getAllModels()
        .map { models -> models.find { it.providerType == ProviderType.TTS }?.id }

    // Tool calling model install state — any GGUF model can support tool calling
    // (actual compatibility is checked at load time via native chat-template detection)
    val hasToolCallingModel: StateFlow<Boolean> = modelRepository.getAllModels()
        .map { models ->
            models.any { model -> model.providerType == ProviderType.GGUF }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val ttsDownloadStates: StateFlow<Map<String, ModelDownloadService.DownloadState>> =
        ModelDownloadService.downloadStates

    // Tool calling model download state
    val toolCallingModelDownloadState: StateFlow<Map<String, ModelDownloadService.DownloadState>> =
        ModelDownloadService.downloadStates

    // App settings
    val streamingEnabled: StateFlow<Boolean> = appSettingsDataStore.streamingEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    val chatMemoryEnabled: StateFlow<Boolean> = appSettingsDataStore.chatMemoryEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    val toolCallingEnabled: StateFlow<Boolean> = appSettingsDataStore.toolCallingEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    val toolCallingBypassEnabled: StateFlow<Boolean> = appSettingsDataStore.toolCallingBypassEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val imageBlurEnabled: StateFlow<Boolean> = appSettingsDataStore.imageBlurEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    val loadTTSOnStart: StateFlow<Boolean> = appSettingsDataStore.loadTTSOnStart
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    val codeHighlightEnabled: StateFlow<Boolean> = appSettingsDataStore.codeHighlightEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    val aiMemoryEnabled: StateFlow<Boolean> = appSettingsDataStore.aiMemoryEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    val speedModeEnabled: StateFlow<Boolean> = appSettingsDataStore.speedModeEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    // Hardware tuning
    val hardwareTuningEnabled: StateFlow<Boolean> = appSettingsDataStore.hardwareTuningEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    val hardwareProfile: StateFlow<HardwareProfile?> = appSettingsDataStore.hardwareProfileJson
        .map { json ->
            json?.takeIf { it.isNotBlank() }?.let {
                try {
                    profileJson.decodeFromString<HardwareProfile>(it)
                } catch (_: Exception) { null }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val performanceMode: StateFlow<PerformanceMode> = appSettingsDataStore.performanceMode
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), PerformanceMode.BALANCED)

    // User Profile
    val profileName: StateFlow<String> = appSettingsDataStore.profileName
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "Jaswanthsanjay Nekkanti")

    val profileEmail: StateFlow<String> = appSettingsDataStore.profileEmail
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "jaswanthsanjay88@gmail.com")

    val profilePhone: StateFlow<String> = appSettingsDataStore.profilePhone
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "+918088997070")

    // TTS settings
    val ttsSettings: StateFlow<TTSSettings> = ttsDataStore.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), TTSSettings())

    val ttsModelLoaded: StateFlow<Boolean> = TTSManager.isModelLoaded
    val ttsAvailableVoices: StateFlow<List<String>> = TTSManager.availableVoices

    // STT settings
    val sttThreads: StateFlow<Int> = appSettingsDataStore.sttThreads
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 2)

    val sttLanguage: StateFlow<String> = appSettingsDataStore.sttLanguage
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "en")

    // App info
    val appVersion: String = try {
        val pInfo = application.packageManager.getPackageInfo(application.packageName, 0)
        pInfo.versionName ?: "1.0"
    } catch (_: Exception) {
        "1.0"
    }

    // App settings updaters
    fun updateProfileName(name: String) {
        viewModelScope.launch { appSettingsDataStore.saveProfileName(name) }
    }

    fun updateProfileEmail(email: String) {
        viewModelScope.launch { appSettingsDataStore.saveProfileEmail(email) }
    }

    fun updateProfilePhone(phone: String) {
        viewModelScope.launch { appSettingsDataStore.saveProfilePhone(phone) }
    }

    fun setStreamingEnabled(enabled: Boolean) {
        viewModelScope.launch { appSettingsDataStore.updateStreamingEnabled(enabled) }
    }

    fun setChatMemoryEnabled(enabled: Boolean) {
        viewModelScope.launch { appSettingsDataStore.updateChatMemoryEnabled(enabled) }
    }

    fun setToolCallingEnabled(enabled: Boolean) {
        viewModelScope.launch { appSettingsDataStore.updateToolCallingEnabled(enabled) }
    }

    fun setToolCallingBypassEnabled(enabled: Boolean) {
        viewModelScope.launch {
            appSettingsDataStore.updateToolCallingBypassEnabled(enabled)
            // Sync with PluginManager
            PluginManager.setToolCallingBypassEnabled(enabled)
        }
    }

    fun setImageBlurEnabled(enabled: Boolean) {
        viewModelScope.launch { appSettingsDataStore.updateImageBlurEnabled(enabled) }
    }

    fun setLoadTTSOnStart(enabled: Boolean) {
        viewModelScope.launch { appSettingsDataStore.updateLoadTTSOnStart(enabled) }
    }

    fun setSttThreads(threads: Int) {
        viewModelScope.launch { appSettingsDataStore.updateSttThreads(threads) }
    }

    fun setSttLanguage(language: String) {
        viewModelScope.launch { appSettingsDataStore.updateSttLanguage(language) }
    }

    fun setCodeHighlightEnabled(enabled: Boolean) {
        viewModelScope.launch { appSettingsDataStore.updateCodeHighlightEnabled(enabled) }
    }

    fun setAiMemoryEnabled(enabled: Boolean) {
        viewModelScope.launch { appSettingsDataStore.updateAiMemoryEnabled(enabled) }
    }

    fun setSpeedModeEnabled(enabled: Boolean) {
        viewModelScope.launch {
            appSettingsDataStore.updateSpeedModeEnabled(enabled)
            withContext(Dispatchers.IO) {
                com.bit.service.LLMService.instance?.ggufEngine?.setSpeculativeDecoding(enabled)
            }
        }
    }

    fun setHardwareTuningEnabled(enabled: Boolean) {
        viewModelScope.launch {
            appSettingsDataStore.updateHardwareTuningEnabled(enabled)
            // When re-enabling, retune all GGUF configs with current performance mode
            if (enabled) {
                val mode = appSettingsDataStore.performanceMode.firstOrNull() ?: PerformanceMode.BALANCED
                retuneAllGgufConfigs(mode)
                AppStateManager.requestModelReload()
            }
        }
    }

    fun setPerformanceMode(mode: PerformanceMode) {
        viewModelScope.launch {
            appSettingsDataStore.savePerformanceMode(mode)

            val tuningEnabled = appSettingsDataStore.hardwareTuningEnabled.firstOrNull() ?: true
            if (!tuningEnabled) return@launch

            retuneAllGgufConfigs(mode)
            AppStateManager.requestModelReload()
        }
    }

    private suspend fun retuneAllGgufConfigs(mode: PerformanceMode) {
        withContext(Dispatchers.IO) {
            try {
                val profile = HardwareScanner.scan(getApplication())
                val allModels = modelRepository.getAllModels().first()

                for (model in allModels.filter { it.providerType == ProviderType.GGUF }) {
                    val config = modelRepository.getConfigByModelId(model.id) ?: continue
                    val modelSizeMB = ((model.fileSize ?: 0L) / (1024 * 1024)).toInt()
                    val newLoading = DeviceTuner.tune(profile, modelSizeMB, model.modelName, mode)
                    val schema = GgufEngineSchema(loadingParams = newLoading)
                    modelRepository.updateConfig(config.copy(modelLoadingParams = schema.toLoadingJson()))
                }
            } catch (e: Exception) {
                Log.e("SettingsVM", "Failed to retune configs", e)
            }
        }
    }

    fun rescanHardware() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val profile = HardwareScanner.scan(getApplication())
                val json = profileJson.encodeToString(profile)
                appSettingsDataStore.saveHardwareProfile(json)
            } catch (e: Exception) {
                Log.e("SettingsVM", "Hardware rescan failed", e)
            }
        }
    }

    // TTS settings updaters
    fun updateVoice(voice: String) {
        viewModelScope.launch { ttsDataStore.updateVoice(voice) }
    }

    fun updateSpeed(speed: Float) {
        viewModelScope.launch { ttsDataStore.updateSpeed(speed) }
    }

    fun updateSteps(steps: Int) {
        viewModelScope.launch { ttsDataStore.updateSteps(steps) }
    }

    fun updateLanguage(language: String) {
        viewModelScope.launch { ttsDataStore.updateLanguage(language) }
    }

    fun updateAutoSpeak(enabled: Boolean) {
        viewModelScope.launch { ttsDataStore.updateAutoSpeak(enabled) }
    }

    fun updateUseNNAPI(enabled: Boolean) {
        viewModelScope.launch { ttsDataStore.updateUseNNAPI(enabled) }
    }

    // Downloads
    companion object {
        private const val TTS_MODEL_ID = "supertonic-v2-tts"
    }

    fun downloadTts() {
        val context = getApplication<Application>()
        val intent = Intent(context, ModelDownloadService::class.java).apply {
            action = ModelDownloadService.ACTION_START_DOWNLOAD
            putExtra(ModelDownloadService.EXTRA_MODEL_ID, TTS_MODEL_ID)
            putExtra(ModelDownloadService.EXTRA_MODEL_NAME, "Supertonic v2 TTS")
            putExtra(ModelDownloadService.EXTRA_FILE_URL, "https://huggingface.co/Supertone/supertonic-2/resolve/main")
            putExtra(ModelDownloadService.EXTRA_IS_ZIP, false)
            putExtra(ModelDownloadService.EXTRA_MODEL_TYPE, "TTS")
            putExtra(ModelDownloadService.EXTRA_RUN_ON_CPU, true)
            putExtra(ModelDownloadService.EXTRA_TEXT_EMBEDDING_SIZE, 0)
        }
        androidx.core.content.ContextCompat.startForegroundService(context, intent)
    }

    fun downloadTtsModel(modelId: String, modelName: String, fileUrl: String) {
        val context = getApplication<Application>()
        val intent = Intent(context, ModelDownloadService::class.java).apply {
            action = ModelDownloadService.ACTION_START_DOWNLOAD
            putExtra(ModelDownloadService.EXTRA_MODEL_ID, modelId)
            putExtra(ModelDownloadService.EXTRA_MODEL_NAME, modelName)
            putExtra(ModelDownloadService.EXTRA_FILE_URL, fileUrl)
            putExtra(ModelDownloadService.EXTRA_IS_ZIP, false)
            putExtra(ModelDownloadService.EXTRA_MODEL_TYPE, "TTS")
            putExtra(ModelDownloadService.EXTRA_RUN_ON_CPU, true)
            putExtra(ModelDownloadService.EXTRA_TEXT_EMBEDDING_SIZE, 0)
        }
        androidx.core.content.ContextCompat.startForegroundService(context, intent)
    }

    fun downloadToolCallingModel() {
        val context = getApplication<Application>()
        val model = PluginManager.TOOL_CALLING_MODEL
        val intent = Intent(context, ModelDownloadService::class.java).apply {
            action = ModelDownloadService.ACTION_START_DOWNLOAD
            putExtra(ModelDownloadService.EXTRA_MODEL_ID, model.id)
            putExtra(ModelDownloadService.EXTRA_MODEL_NAME, model.name)
            putExtra(ModelDownloadService.EXTRA_FILE_URL, "https://huggingface.co/${model.fileUri}")
            putExtra(ModelDownloadService.EXTRA_IS_ZIP, model.isZip)
            putExtra(ModelDownloadService.EXTRA_MODEL_TYPE, "GGUF")
            putExtra(ModelDownloadService.EXTRA_RUN_ON_CPU, model.runOnCpu)
            putExtra(ModelDownloadService.EXTRA_TEXT_EMBEDDING_SIZE, model.textEmbeddingSize)
        }
        androidx.core.content.ContextCompat.startForegroundService(context, intent)
    }

    fun loadTtsAfterDownload() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                val modelDir = TTSManager.getModelDirectory() ?: return@withContext
                TTSManager.loadModel(modelDir)
            }
        }
    }

    // ==================== Backup / Restore / Delete ====================

    private val _backupProgress = MutableStateFlow<SystemBackupManager.BackupProgress?>(null)
    val backupProgress: StateFlow<SystemBackupManager.BackupProgress?> = _backupProgress

    private val _backupOptions = MutableStateFlow(SystemBackupManager.BackupOptions())
    val backupOptions: StateFlow<SystemBackupManager.BackupOptions> = _backupOptions

    private val _backupSizeEstimate = MutableStateFlow<SystemBackupManager.BackupSizeEstimate?>(null)
    val backupSizeEstimate: StateFlow<SystemBackupManager.BackupSizeEstimate?> = _backupSizeEstimate

    fun updateBackupOptions(options: SystemBackupManager.BackupOptions) {
        _backupOptions.value = options
        estimateBackupSize(options)
    }

    fun estimateBackupSize(options: SystemBackupManager.BackupOptions = _backupOptions.value) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val manager = SystemBackupManager(getApplication())
                _backupSizeEstimate.value = manager.estimateBackupSize(options)
            } catch (e: Exception) {
                _backupSizeEstimate.value = null
            }
        }
    }

    fun createBackup(uri: Uri, password: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val manager = SystemBackupManager(getApplication())
            manager.createBackup(uri, password, _backupOptions.value) { progress ->
                _backupProgress.value = progress
            }
        }
    }

    fun restoreBackup(uri: Uri, password: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val manager = SystemBackupManager(getApplication())
            manager.restoreBackup(uri, password) { progress ->
                _backupProgress.value = progress
            }
        }
    }

    fun deleteAllData() {
        viewModelScope.launch(Dispatchers.IO) {
            val manager = SystemBackupManager(getApplication())
            manager.deleteAllData { progress ->
                _backupProgress.value = progress
            }
        }
    }

    fun clearBackupProgress() {
        _backupProgress.value = null
    }
}
