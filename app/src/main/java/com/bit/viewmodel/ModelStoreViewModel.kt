package com.bit.viewmodel

import android.app.Application
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.SavedStateHandle
import com.bit.di.AppContainer
import com.bit.global.AppPaths
import com.bit.models.data.HFModelRepository
import com.bit.models.data.HuggingFaceModel
import com.bit.models.data.ModelCategory
import com.bit.models.data.ModelType
import com.bit.models.data.RepositorySource
import com.bit.models.enums.PathType
import com.bit.models.enums.ProviderType
import com.bit.models.table_schema.Model
import com.bit.models.table_schema.ModelConfig
import com.bit.repo.HuggingFaceExplorerRepo
import com.bit.repo.HuggingFaceExplorerRepository
import com.bit.repo.ModelRepositoryDataStore
import com.bit.repo.ModelStoreRepository
import com.bit.repo.RepositoryValidator
import com.bit.repo.ValidationResult
import com.bit.service.ModelDownloadService
import com.bit.ui.screen.model_store.StoreTab
import com.bit.utils.ModelMetadataExtractor
import com.bit.utils.SizeCategory
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File

enum class SortOption {
    NAME,
    SIZE,
    RECENTLY_ADDED
}

data class RepoGroupInfo(
    val displayName: String,
    val author: String,
    val modelType: ModelType,
    val modelCount: Int
)

@HiltViewModel
class ModelStoreViewModel @Inject constructor(
    application: Application,
    private val explorerRepository: HuggingFaceExplorerRepository,
    private val savedStateHandle: SavedStateHandle
) : AndroidViewModel(application) {

    private val repository = ModelStoreRepository(application)
    private val systemRepo = AppContainer.getModelRepository()
    private val repoDataStore = ModelRepositoryDataStore(application)
    private val repositoryValidator = RepositoryValidator()

    private val _selectedTab = MutableStateFlow(StoreTab.MODELS)
    val selectedTab: StateFlow<StoreTab> = _selectedTab

    private val _models = MutableStateFlow<List<HuggingFaceModel>>(emptyList())
    val models: StateFlow<List<HuggingFaceModel>> = _models

    // Curated models from bit.jaswanthsanjay.me/api/models (flat list, no repo navigation)
    private val _curatedModels = MutableStateFlow<List<HuggingFaceModel>>(emptyList())
    val curatedModels: StateFlow<List<HuggingFaceModel>> = _curatedModels

    private val _filteredModels = MutableStateFlow<List<HuggingFaceModel>>(emptyList())
    val filteredModels: StateFlow<List<HuggingFaceModel>> = _filteredModels

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    private val _installedModels = MutableStateFlow<List<Model>>(emptyList())
    val installedModels: StateFlow<List<Model>> = _installedModels

    private val _deviceInfo = MutableStateFlow<Map<String, String>>(emptyMap())
    val deviceInfo: StateFlow<Map<String, String>> = _deviceInfo

    private val _deleteInProgress = MutableStateFlow<String?>(null)
    val deleteInProgress: StateFlow<String?> = _deleteInProgress

    val repositories = repoDataStore.repositories
    val downloadStates = ModelDownloadService.downloadStates

    // Cached repos for synchronous lookup in getGroupedRepos
    private var cachedRepos: List<HFModelRepository> = emptyList()

    // Filter states
    private val _selectedModelType = MutableStateFlow<ModelType?>(null)
    val selectedModelType: StateFlow<ModelType?> = _selectedModelType

    private val _selectedCategory = MutableStateFlow<ModelCategory?>(null)
    val selectedCategory: StateFlow<ModelCategory?> = _selectedCategory

    private val _selectedParameters = MutableStateFlow<Set<String>>(emptySet())
    val selectedParameters: StateFlow<Set<String>> = _selectedParameters

    private val _selectedQuantizations = MutableStateFlow<Set<String>>(emptySet())
    val selectedQuantizations: StateFlow<Set<String>> = _selectedQuantizations

    private val _selectedSizeCategory = MutableStateFlow<SizeCategory?>(null)
    val selectedSizeCategory: StateFlow<SizeCategory?> = _selectedSizeCategory

    private val _selectedTags = MutableStateFlow<Set<String>>(emptySet())
    val selectedTags: StateFlow<Set<String>> = _selectedTags

    private val _showNsfw = MutableStateFlow(true)
    val showNsfw: StateFlow<Boolean> = _showNsfw

    private val _executionTarget = MutableStateFlow<String?>(null)
    val executionTarget: StateFlow<String?> = _executionTarget

    private val _sortBy = MutableStateFlow(SortOption.NAME)
    val sortBy: StateFlow<SortOption> = _sortBy

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery

    // Repo card navigation: null = show repo list, non-null = show models inside that repo
    private val _selectedRepository = MutableStateFlow<String?>(null)
    val selectedRepository: StateFlow<String?> = _selectedRepository

    // Validation results
    private val _validationResults = MutableStateFlow<Map<String, ValidationResult>>(emptyMap())
    val validationResults: StateFlow<Map<String, ValidationResult>> = _validationResults

    private val _explorerQuery = MutableStateFlow("")
    val explorerQuery: StateFlow<String> = _explorerQuery

    private val _explorerResults = MutableStateFlow<List<HuggingFaceExplorerRepo>>(emptyList())
    val explorerResults: StateFlow<List<HuggingFaceExplorerRepo>> = _explorerResults

    private val _isExplorerLoading = MutableStateFlow(false)
    val isExplorerLoading: StateFlow<Boolean> = _isExplorerLoading

    private val _explorerError = MutableStateFlow<String?>(null)
    val explorerError: StateFlow<String?> = _explorerError

    private var explorerSearchJob: Job? = null

    // App's internal models directory
    private val appModelsDir = AppPaths.models(application)

    init {
        loadDeviceInfo()
        loadCuratedModels()
        loadInstalledModels()

        // Read optional tab param and set initial state
        val tabArg = savedStateHandle.get<String>("tab")
        if (tabArg == "installed") {
            _selectedTab.value = StoreTab.INSTALLED
        } else if (tabArg == "models") {
            _selectedTab.value = StoreTab.MODELS
        }
    }

    private fun loadDeviceInfo() {
        _deviceInfo.value = repository.getDeviceInfo()
    }

    fun selectTab(tab: StoreTab) {
        _selectedTab.value = tab
    }

    fun refreshModels() {
        loadCuratedModels(forceRefresh = true)
    }

    private fun loadCuratedModels(forceRefresh: Boolean = false) {
        viewModelScope.launch {
            _isLoading.value = true
            _error.value = null

            try {
                repository.fetchCuratedModels(forceRefresh).onSuccess { modelsList ->
                    _curatedModels.value = modelsList
                    _models.value = modelsList
                    applyAllFilters()
                }.onFailure { exception ->
                    _error.value = exception.message
                }
            } catch (e: Exception) {
                _error.value = e.message
            } finally {
                _isLoading.value = false
            }
        }
    }

    // Legacy repo-based loading for Advanced tab
    fun loadModels() {
        viewModelScope.launch {
            _isLoading.value = true
            _error.value = null

            try {
                val repos = repositories.first()
                cachedRepos = repos
                repository.getAvailableModels(repos).onSuccess { modelsList ->
                    _models.value = modelsList
                    applyAllFilters()
                }.onFailure { exception ->
                    _error.value = exception.message
                }
            } catch (e: Exception) {
                _error.value = e.message
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun loadInstalledModels() {
        viewModelScope.launch {
            try {
                systemRepo.getAllModels().collect { installedList ->
                    _installedModels.value = installedList.distinctBy { it.id }
                }
            } catch (e: Exception) {
                Log.e("ModelStoreViewModel", "Error loading installed models", e)
            }
        }
    }

    private fun applyAllFilters() {
        viewModelScope.launch {
            var filtered = if (_selectedModelType.value == null) {
                // Show all types from curated list (it's already curated/small)
                _models.value.toList()
            } else {
                _models.value.filter { it.modelType == _selectedModelType.value }
            }

            // Category filter (repository level) - only applies to GGUF
            _selectedCategory.value?.let { category ->
                val repos = repositories.first()
                val enabledRepos = repos
                    .filter { it.category == category && it.isEnabled }
                    .map { it.id }
                    .toSet()
                filtered = filtered.filter { model ->
                    // Category filter only applies to GGUF models
                    model.modelType != ModelType.GGUF ||
                            enabledRepos.any { model.id.startsWith(it) }
                }
            }

            // Parameter count filter (GGUF only)
            if (_selectedParameters.value.isNotEmpty()) {
                filtered = filtered.filter { model ->
                    if (model.modelType != ModelType.GGUF) true
                    else {
                        val params = ModelMetadataExtractor.extractParameterCount(model.name)
                        params != null && params in _selectedParameters.value
                    }
                }
            }

            // Quantization filter (GGUF only)
            if (_selectedQuantizations.value.isNotEmpty()) {
                filtered = filtered.filter { model ->
                    if (model.modelType != ModelType.GGUF) true
                    else {
                        val quant = ModelMetadataExtractor.extractQuantization(model.name)
                        quant != null && quant in _selectedQuantizations.value
                    }
                }
            }

            // Size category filter
            _selectedSizeCategory.value?.let { sizeCategory ->
                filtered = filtered.filter { model ->
                    ModelMetadataExtractor.extractSizeCategory(model.approximateSize) == sizeCategory
                }
            }

            // Tag filter
            if (_selectedTags.value.isNotEmpty()) {
                filtered = filtered.filter { model ->
                    _selectedTags.value.all { tag -> tag in model.tags }
                }
            }

            // NSFW filter
            if (!_showNsfw.value) {
                filtered = filtered.filter { model ->
                    "NSFW" !in model.tags
                }
            }

            // Execution target filter
            _executionTarget.value?.let { target ->
                filtered = filtered.filter { model ->
                    target in model.tags
                }
            }

            // Search query filter
            if (_searchQuery.value.isNotBlank()) {
                val query = _searchQuery.value
                filtered = filtered.filter {
                    it.name.contains(query, ignoreCase = true) ||
                            it.description.contains(query, ignoreCase = true) ||
                            it.tags.any { tag -> tag.contains(query, ignoreCase = true) }
                }
            }

            // Apply sorting
            filtered = when (_sortBy.value) {
                SortOption.NAME -> filtered.sortedBy { it.name.lowercase() }
                SortOption.SIZE -> filtered.sortedBy { ModelMetadataExtractor.parseSizeToBytes(it.approximateSize) }
                SortOption.RECENTLY_ADDED -> filtered.reversed()
            }

            _filteredModels.value = filtered
        }
    }

    fun filterModels(query: String) {
        _searchQuery.value = query
        applyAllFilters()
    }

    fun filterByModelType(modelType: ModelType?) {
        _selectedModelType.value = modelType
        applyAllFilters()
    }

    fun filterByCategory(category: ModelCategory?) {
        _selectedCategory.value = category
        applyAllFilters()
    }

    fun toggleParameterFilter(parameter: String) {
        _selectedParameters.value = if (parameter in _selectedParameters.value) {
            _selectedParameters.value - parameter
        } else {
            _selectedParameters.value + parameter
        }
        applyAllFilters()
    }

    fun toggleQuantizationFilter(quantization: String) {
        _selectedQuantizations.value = if (quantization in _selectedQuantizations.value) {
            _selectedQuantizations.value - quantization
        } else {
            _selectedQuantizations.value + quantization
        }
        applyAllFilters()
    }

    fun filterBySizeCategory(sizeCategory: SizeCategory?) {
        _selectedSizeCategory.value = sizeCategory
        applyAllFilters()
    }

    fun setSortOption(sortOption: SortOption) {
        _sortBy.value = sortOption
        applyAllFilters()
    }

    fun toggleTagFilter(tag: String) {
        _selectedTags.value = if (tag in _selectedTags.value) {
            _selectedTags.value - tag
        } else {
            _selectedTags.value + tag
        }
        applyAllFilters()
    }

    fun setShowNsfw(show: Boolean) {
        _showNsfw.value = show
        applyAllFilters()
    }

    fun setExecutionTarget(target: String?) {
        _executionTarget.value = target
        applyAllFilters()
    }

    fun getAvailableTags(): List<String> {
        return _models.value
            .flatMap { it.tags }
            .distinct()
            .filter { tag ->
                tag !in listOf("GGUF") && !tag.matches(Regex("Q\\d.*"))
            }
            .sorted()
    }

    fun clearAllFilters() {
        _selectedModelType.value = null
        _selectedCategory.value = null
        _selectedParameters.value = emptySet()
        _selectedQuantizations.value = emptySet()
        _selectedSizeCategory.value = null
        _selectedTags.value = emptySet()
        _showNsfw.value = true
        _executionTarget.value = null
        _sortBy.value = SortOption.NAME
        _searchQuery.value = ""
        applyAllFilters()
    }

    fun selectRepository(repoKey: String?) {
        _selectedRepository.value = repoKey
    }

    fun getGroupedRepos(): Map<String, RepoGroupInfo> {
        val models = _filteredModels.value
        val grouped = mutableMapOf<String, RepoGroupInfo>()

        val repoLookup = cachedRepos.associateBy { repo ->
            if (repo.source == RepositorySource.CUSTOM_API) {
                repo.apiBaseUrl.trim().removeSuffix("/")
            } else {
                repo.repoPath
            }
        }

        models.groupBy { model ->
            when (model.modelType) {
                ModelType.GGUF -> model.repositoryUrl.trim().removeSuffix("/").ifEmpty { "Unknown" }
                ModelType.SD -> model.repositoryUrl.trim().removeSuffix("/").ifEmpty { "SD Models" }
                ModelType.TTS -> "tts-models"
                ModelType.STT -> "stt-models"
                ModelType.EMBEDDING -> "embedding-models"
            }
        }.forEach { (key, groupModels) ->
            val first = groupModels.first()
            val repo = repoLookup[key]
            val displayName = when (first.modelType) {
                ModelType.TTS, ModelType.STT, ModelType.EMBEDDING -> first.name
                else -> repo?.name ?: key.substringAfterLast("/")
            }
            val author = when {
                first.modelType == ModelType.TTS || first.modelType == ModelType.STT || first.modelType == ModelType.EMBEDDING -> ""
                repo?.source == RepositorySource.CUSTOM_API -> "API"
                key.contains("/") -> key.substringBefore("/")
                else -> ""
            }
            grouped[key] = RepoGroupInfo(displayName, author, first.modelType, groupModels.size)
        }

        return grouped
    }

    fun getModelsForRepo(repoKey: String): List<HuggingFaceModel> {
        val cleanRepoKey = repoKey.trim().removeSuffix("/")
        return _filteredModels.value.filter { model ->
            when (model.modelType) {
                ModelType.GGUF -> {
                    val cleanUrl = model.repositoryUrl.trim().removeSuffix("/")
                    (cleanUrl.ifEmpty { "Unknown" }) == cleanRepoKey
                }
                ModelType.SD -> {
                    val cleanUrl = model.repositoryUrl.trim().removeSuffix("/")
                    (cleanUrl.ifEmpty { "SD Models" }) == cleanRepoKey
                }
                ModelType.TTS -> cleanRepoKey == "tts-models"
                ModelType.STT -> cleanRepoKey == "stt-models"
                ModelType.EMBEDDING -> cleanRepoKey == "embedding-models"
            }
        }
    }

    fun downloadModel(model: HuggingFaceModel) {
        val context = getApplication<Application>()

        // API-catalog models are remote inference entries, not file downloads.
        if ("API" in model.tags) {
            installRemoteApiModel(model)
            return
        }

        // Warn user if model is likely too large for their device
        val approxSizeMB = parseApproxSizeMB(model.approximateSize)
        if (approxSizeMB > 0) {
            val activityManager = context.getSystemService(android.content.Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            val memInfo = android.app.ActivityManager.MemoryInfo()
            activityManager.getMemoryInfo(memInfo)
            val totalRamMB = (memInfo.totalMem / (1024 * 1024)).toInt()
            if (approxSizeMB > totalRamMB * 0.8) {
                _error.value = "Warning: This model (~${approxSizeMB}MB) may be too large for your device (${totalRamMB}MB RAM). It might fail to load."
            }
        }

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

    private fun installRemoteApiModel(model: HuggingFaceModel) {
        viewModelScope.launch {
            try {
                val endpoint = model.fileUri.trim()
                if (!endpoint.startsWith("http://") && !endpoint.startsWith("https://")) {
                    _error.value = "API model requires full endpoint URL in catalog"
                    return@launch
                }

                val sourceRepo = cachedRepos.firstOrNull {
                    it.source == RepositorySource.CUSTOM_API &&
                        it.apiBaseUrl.trim().removeSuffix("/").equals(model.repositoryUrl.trim().removeSuffix("/"), ignoreCase = true)
                }
                val authHeader = sourceRepo?.apiAuthToken
                    ?.trim()
                    ?.takeIf { it.isNotBlank() }

                val apiModel = Model(
                    id = model.id,
                    modelName = model.name,
                    modelPath = endpoint,
                    pathType = PathType.FILE,
                    providerType = ProviderType.API,
                    fileSize = null,
                    isActive = true
                )

                val loadingJson = JSONObject()
                    .put("endpoint", endpoint)
                    .put("model", model.name)
                    .put("stream", false)
                    .put("authHeader", authHeader)
                    .toString()

                val config = ModelConfig(
                    modelId = apiModel.id,
                    modelLoadingParams = loadingJson,
                    modelInferenceParams = "{}"
                )

                systemRepo.getModelById(apiModel.id)?.let { systemRepo.deleteModel(it) }
                systemRepo.getConfigByModelId(apiModel.id)?.let { systemRepo.deleteConfig(it) }

                systemRepo.insertModel(apiModel)
                systemRepo.insertConfig(config)
                loadInstalledModels()
                _error.value = "Installed API model: ${model.name}"
            } catch (e: Exception) {
                _error.value = "Failed to install API model: ${e.message}"
            }
        }
    }

    private fun parseApproxSizeMB(sizeStr: String): Int {
        val cleaned = sizeStr.trim().uppercase()
        val number = cleaned.filter { it.isDigit() || it == '.' }.toDoubleOrNull() ?: return 0
        return when {
            cleaned.endsWith("GB") -> (number * 1024).toInt()
            cleaned.endsWith("MB") -> number.toInt()
            else -> 0
        }
    }

    fun cancelDownload(modelId: String) {
        val context = getApplication<Application>()
        val intent = Intent(context, ModelDownloadService::class.java).apply {
            action = ModelDownloadService.ACTION_CANCEL_DOWNLOAD
            putExtra(ModelDownloadService.EXTRA_MODEL_ID, modelId)
        }
        context.startService(intent)
    }

    fun pauseDownload(modelId: String) {
        val context = getApplication<Application>()
        val intent = Intent(context, ModelDownloadService::class.java).apply {
            action = ModelDownloadService.ACTION_PAUSE_DOWNLOAD
            putExtra(ModelDownloadService.EXTRA_MODEL_ID, modelId)
        }
        context.startService(intent)
    }

    fun resumeDownload(modelId: String, modelName: String) {
        val context = getApplication<Application>()
        val intent = Intent(context, ModelDownloadService::class.java).apply {
            action = ModelDownloadService.ACTION_RESUME_DOWNLOAD
            putExtra(ModelDownloadService.EXTRA_MODEL_ID, modelId)
            putExtra(ModelDownloadService.EXTRA_MODEL_NAME, modelName)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
    }

    fun deleteModel(model: Model) {
        viewModelScope.launch {
            _deleteInProgress.value = model.id
            try {
                // Delete model file if it's in app's internal directory
                val modelFile = File(model.modelPath)
                if (modelFile.exists() && modelFile.absolutePath.startsWith(appModelsDir.absolutePath)) {
                    val deleted = modelFile.delete()
                    Log.d("ModelStoreViewModel", "Model file deleted: $deleted - ${modelFile.absolutePath}")

                    // If it's a directory (for SD models), delete recursively
                    if (modelFile.isDirectory) {
                        modelFile.deleteRecursively()
                    }
                }

                // Delete config from database
                val config = systemRepo.getConfigByModelId(model.id)
                if (config != null) {
                    systemRepo.deleteConfig(config)
                    Log.d("ModelStoreViewModel", "Model config deleted for: ${model.id}")
                }

                // Delete model from database
                systemRepo.deleteModel(model)
                Log.d("ModelStoreViewModel", "Model deleted from database: ${model.modelName}")

                // Reload installed models
                loadInstalledModels()
            } catch (e: Exception) {
                Log.e("ModelStoreViewModel", "Error deleting model", e)
                _error.value = "Failed to delete model: ${e.message}"
            } finally {
                _deleteInProgress.value = null
            }
        }
    }

    fun addRepository(repo: HFModelRepository) {
        viewModelScope.launch {
            repoDataStore.addRepository(repo)
            loadModels()
        }
    }

    fun setExplorerQuery(query: String) {
        _explorerQuery.value = query
        if (query.isBlank()) {
            _explorerResults.value = emptyList()
            _explorerError.value = null
        }
    }

    fun searchExplorerRepositories() {
        explorerSearchJob?.cancel()
        explorerSearchJob = viewModelScope.launch {
            val query = _explorerQuery.value.trim()
            if (query.isBlank()) {
                _explorerError.value = "Enter a search term"
                _explorerResults.value = emptyList()
                return@launch
            }

            _isExplorerLoading.value = true
            _explorerError.value = null

            try {
                explorerRepository.searchGgufRepositories(query).onSuccess { repos ->
                    _explorerResults.value = repos
                    if (repos.isEmpty()) {
                        _explorerError.value = "No repositories found"
                    }
                }.onFailure { exception ->
                    _explorerResults.value = emptyList()
                    _explorerError.value = exception.message ?: "Search failed"
                }
            } finally {
                _isExplorerLoading.value = false
            }
        }
    }

    fun addExplorerRepository(explorerRepo: HuggingFaceExplorerRepo) {
        viewModelScope.launch {
            val currentRepos = repositories.first()
            if (currentRepos.any { it.repoPath.equals(explorerRepo.id, ignoreCase = true) }) {
                _explorerError.value = "Repository already added"
                return@launch
            }

            val lowerId = explorerRepo.id.lowercase()
            val lowerTags = explorerRepo.tags.map { it.lowercase() }

            val modelType = when {
                lowerTags.any { tag -> tag in listOf("stable-diffusion", "diffusion", "sd", "diffusers", "text-to-image", "image-to-image") } ||
                        lowerId.contains("diffusion") || lowerId.contains("stable-diffusion") || lowerId.contains("sd-") -> ModelType.SD

                lowerTags.any { tag -> tag in listOf("text-to-speech", "tts") } || lowerId.contains("tts") -> ModelType.TTS

                lowerTags.any { tag -> tag in listOf("automatic-speech-recognition", "stt", "speech-recognition", "whisper") } || lowerId.contains("whisper") -> ModelType.STT

                else -> ModelType.GGUF
            }

            val category = ModelCategory.GENERAL

            val repo = HFModelRepository(
                id = "hf-${explorerRepo.id.replace("/", "-").lowercase()}",
                name = explorerRepo.id.substringAfter("/"),
                repoPath = explorerRepo.id,
                modelType = modelType,
                isEnabled = true,
                category = category
            )

            repoDataStore.addRepository(repo)
            _explorerError.value = null
            loadModels()
        }
    }

    fun removeRepository(repoId: String) {
        viewModelScope.launch {
            repoDataStore.removeRepository(repoId)
            loadModels()
        }
    }

    fun toggleRepository(repoId: String) {
        viewModelScope.launch {
            repoDataStore.toggleRepository(repoId)
            loadModels()
        }
    }

    fun updateRepository(repo: HFModelRepository) {
        viewModelScope.launch {
            repoDataStore.updateRepository(repo)
            
            // Sync any already installed API models from this repo
            try {
                val installed = systemRepo.getAllModels().first()
                val apiModels = installed.filter { 
                    it.providerType == ProviderType.API && it.id.startsWith("${repo.id}-")
                }
                apiModels.forEach { model ->
                    val config = systemRepo.getConfigByModelId(model.id)
                    if (config != null && !config.modelLoadingParams.isNullOrBlank()) {
                        val json = JSONObject(config.modelLoadingParams)
                        
                        val base = repo.apiBaseUrl.trim().removeSuffix("/")
                        val baseClean = base.removeSuffix("/")
                        val baseLower = baseClean.lowercase(java.util.Locale.US)
                        
                        val newEndpoint = if (baseLower.contains("openrouter.ai") ||
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
                        
                        val authHeader = repo.apiAuthToken.trim().takeIf { it.isNotBlank() }
                        
                        json.put("endpoint", newEndpoint)
                        json.put("authHeader", authHeader)
                        
                        val updatedConfig = config.copy(
                            modelLoadingParams = json.toString()
                        )
                        systemRepo.updateConfig(updatedConfig)
                        
                        val updatedModel = model.copy(
                            modelPath = newEndpoint
                        )
                        systemRepo.updateModel(updatedModel)
                        Log.i("ModelStoreViewModel", "Synced repository config for model: ${model.id} -> $newEndpoint")
                    }
                }
            } catch (e: Exception) {
                Log.e("ModelStoreViewModel", "Failed to sync models after repo update", e)
            }
            
            loadModels()
        }
    }

    fun validateRepository(repo: HFModelRepository) {
        viewModelScope.launch {
            _validationResults.value += repo.id to ValidationResult.Checking
            val result = repositoryValidator.validateRepository(repo)
            _validationResults.value += repo.id to result
        }
    }

    fun getValidationResult(repoId: String): ValidationResult? {
        return _validationResults.value[repoId]
    }

    suspend fun getModelConfig(modelId: String): ModelConfig? {
        return systemRepo.getConfigByModelId(modelId)
    }
}
