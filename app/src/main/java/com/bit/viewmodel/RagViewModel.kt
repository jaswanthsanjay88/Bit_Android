package com.bit.viewmodel

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bit.engine.EmbeddingConfig
import com.bit.engine.EmbeddingEngine
import com.bit.models.table_schema.InstalledRag
import com.bit.models.table_schema.RagStatus
import com.bit.neuron_example.GraphSettings
import com.bit.neuron_example.NeuronGraph
import com.bit.neuron_example.RetrievalConfidence
import com.bit.repo.RagRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

// Data class for displaying RAG query results in UI
data class RagQueryDisplayResult(
    val ragName: String,
    val content: String,
    val score: Float,
    val nodeId: String
)

@HiltViewModel
class RagViewModel @Inject constructor(
    private val ragRepository: RagRepository,
    private val embeddingEngine: EmbeddingEngine,
    @param:ApplicationContext private val context: Context
) : ViewModel() {

    // UI State
    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    private val _embeddingStatus = MutableStateFlow("Not Initialized")
    val embeddingStatus: StateFlow<String> = _embeddingStatus

    private val _isEmbeddingInitialized = MutableStateFlow(false)
    val isEmbeddingInitialized: StateFlow<Boolean> = _isEmbeddingInitialized

    private val _isEmbeddingModelDownloading = MutableStateFlow(false)
    val isEmbeddingModelDownloading: StateFlow<Boolean> = _isEmbeddingModelDownloading

    private val _isEmbeddingModelDownloaded = MutableStateFlow(false)
    val isEmbeddingModelDownloaded: StateFlow<Boolean> = _isEmbeddingModelDownloaded

    private val _embeddingDownloadProgress = MutableStateFlow(0f)
    val embeddingDownloadProgress: StateFlow<Float> = _embeddingDownloadProgress

    // RAG Lists
    val installedRags = ragRepository.getAllRags()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val loadedRags = ragRepository.getLoadedRags()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Counts
    private val _installedCount = MutableStateFlow(0)
    val installedCount: StateFlow<Int> = _installedCount

    private val _loadedCount = MutableStateFlow(0)
    val loadedCount: StateFlow<Int> = _loadedCount

    // Embedding state
    val isEmbeddingReady: Boolean get() = embeddingEngine.isInitialized()

    // RAG enabled for chat
    private val _isRagEnabledForChat = MutableStateFlow(false)
    val isRagEnabledForChat: StateFlow<Boolean> = _isRagEnabledForChat

    // Last RAG query results for display
    private val _lastRagResults = MutableStateFlow<List<RagQueryDisplayResult>>(emptyList())
    val lastRagResults: StateFlow<List<RagQueryDisplayResult>> = _lastRagResults

    init {
        // Sync database state with in-memory state on startup
        // Since loadedGraphs is empty on app restart, mark all RAGs as unloaded
        viewModelScope.launch(Dispatchers.IO) {
            ragRepository.syncLoadedStateOnStartup()
        }

        refreshCounts()
        _isEmbeddingModelDownloaded.value = EmbeddingEngine.isModelDownloaded(context)
        _isEmbeddingInitialized.value = embeddingEngine.isInitialized()
        if (embeddingEngine.isInitialized()) {
            _embeddingStatus.value = "Ready (dim: ${embeddingEngine.getDimension()})"
        }

    }


    fun startEmbeddingDownload() {
        viewModelScope.launch(Dispatchers.IO) {
            if (_isEmbeddingModelDownloading.value) return@launch

            if (EmbeddingEngine.isModelDownloaded(context)) {
                _isEmbeddingModelDownloaded.value = true
                initializeEmbeddingFromFiles()
                return@launch
            }

            val modelPath = EmbeddingEngine.getModelPath(context)
            val tempPath = modelPath.resolveSibling("${modelPath.name}.part")

            // Clean up any stale file
            if (modelPath.exists()) {
                Log.w("RagViewModel", "Stale/invalid model found, deleting: ${EmbeddingEngine.getModelValidationError(modelPath)}")
                modelPath.delete()
            }
            if (tempPath.exists()) tempPath.delete()
            modelPath.parentFile?.mkdirs()

            _isEmbeddingModelDownloading.value = true
            _embeddingDownloadProgress.value = 0f
            _embeddingStatus.value = "Starting download..."

            try {
                Log.d("RagViewModel", "Starting direct embedding model download to ${modelPath.absolutePath}")

                // Follow HuggingFace CDN redirects manually so Content-Length is accurate
                val connection = openFinalConnection(
                    "https://huggingface.co/spaces/Void2377/neurov/resolve/main/all-MiniLM-L6-v2-Q5_K_M.gguf?download=true"
                )
                val fileSize = connection.contentLengthLong  // -1 if unknown
                if (fileSize > 0) {
                    Log.d("RagViewModel", "Model size: ${fileSize / 1024 / 1024}MB")
                } else {
                    Log.w("RagViewModel", "Server did not report Content-Length — downloading without size info")
                }

                connection.inputStream.use { inputStream ->
                    tempPath.outputStream().use { outputStream ->
                        val buffer = ByteArray(65_536)
                        var totalBytesRead = 0L
                        var bytesRead: Int

                        while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                            outputStream.write(buffer, 0, bytesRead)
                            totalBytesRead += bytesRead

                            if (fileSize > 0) {
                                val progress = (totalBytesRead.toFloat() / fileSize.toFloat()).coerceIn(0f, 1f)
                                _embeddingDownloadProgress.value = progress
                                _embeddingStatus.value = "Downloading: ${(progress * 100).toInt()}%"
                            } else {
                                _embeddingStatus.value = "Downloading: ${totalBytesRead / (1024 * 1024)}MB"
                            }
                        }
                        outputStream.flush()
                    }
                }
                connection.disconnect()

                // Rename temp → final
                if (!tempPath.renameTo(modelPath)) {
                    tempPath.copyTo(modelPath, overwrite = true)
                    tempPath.delete()
                }

                if (!EmbeddingEngine.isModelFileValid(modelPath)) {
                    val reason = EmbeddingEngine.getModelValidationError(modelPath)
                    Log.e("RagViewModel", "Downloaded model failed validation: $reason")
                    modelPath.delete()
                    _embeddingDownloadProgress.value = 0f
                    _embeddingStatus.value = "Download failed"
                    _error.value = "Embedding model download failed: $reason"
                    _isEmbeddingModelDownloading.value = false
                    return@launch
                }

                Log.d("RagViewModel", "Embedding model downloaded successfully (${modelPath.length() / 1024 / 1024}MB)")
                _isEmbeddingModelDownloaded.value = true
                _embeddingDownloadProgress.value = 1f
                _embeddingStatus.value = "Download complete"
                _isEmbeddingModelDownloading.value = false
                initializeEmbeddingFromFiles()

            } catch (e: Exception) {
                Log.e("RagViewModel", "Embedding model download failed", e)
                tempPath.delete()
                _embeddingDownloadProgress.value = 0f
                _embeddingStatus.value = "Download failed"
                _error.value = "Download failed: ${e.message}"
                _isEmbeddingModelDownloading.value = false
            }
        }
    }

    /**
     * Follow HTTP redirects manually so that the final CDN connection
     * always has a correct Content-Length header.
     */
    private fun openFinalConnection(startUrl: String): java.net.HttpURLConnection {
        var url = startUrl
        var redirects = 0
        while (true) {
            val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
            conn.instanceFollowRedirects = false
            conn.connectTimeout = 30_000
            conn.readTimeout = 60_000
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Android) AppleWebKit/537.36")
            conn.setRequestProperty("Accept-Encoding", "identity")
            conn.connect()
            val code = conn.responseCode
            if (code in 300..399) {
                val location = conn.getHeaderField("Location")
                    ?: throw IllegalStateException("Redirect with no Location header")
                conn.disconnect()
                url = location
                if (++redirects > 10) throw IllegalStateException("Too many HTTP redirects")
            } else {
                return conn
            }
        }
    }

    private fun refreshCounts() {
        viewModelScope.launch(Dispatchers.IO) {
            _installedCount.value = ragRepository.getRagCount()
            _loadedCount.value = ragRepository.getLoadedRagCount()
        }
    }

    // ==================== UI Controls ====================

    fun clearError() {
        _error.value = null
    }

    // ==================== RAG Operations ====================

    fun toggleRagEnabled(ragId: String, isEnabled: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            ragRepository.updateRagEnabled(ragId, isEnabled)
        }
    }

    fun loadRag(ragId: String, password: String? = null) {
        viewModelScope.launch(Dispatchers.IO) {
            _isLoading.value = true
            _error.value = null

            try {
                // Initialize embedding engine if not already initialized
                if (!embeddingEngine.isInitialized()) {
                    Log.d("RagViewModel", "Embedding engine not initialized, initializing now...")
                    Log.d("RagViewModel", EmbeddingEngine.getModelDiagnostics(context))
                    
                    val modelFile = com.bit.engine.EmbeddingEngine.getModelPath(context)

                    if (!EmbeddingEngine.isModelFileValid(modelFile)) {
                        Log.w("RagViewModel", "Model file is missing or invalid, auto-starting download...")
                        if (!_isEmbeddingModelDownloading.value) {
                            Log.d("RagViewModel", "Embedding model missing/invalid, auto-starting download")
                            startEmbeddingDownload()
                        }
                        _error.value = "Embedding model is downloading. RAG will be available once complete."
                        _isLoading.value = false
                        ragRepository.updateRagStatus(ragId, RagStatus.INSTALLED)
                        return@launch
                    }

                    val config = EmbeddingConfig(modelPath = modelFile.absolutePath)
                    Log.d("RagViewModel", "Initializing embedding engine with model: ${modelFile.absolutePath} (${modelFile.length() / 1024 / 1024}MB)")
                    val initResult = embeddingEngine.initialize(config)

                    if (initResult.isFailure) {
                        val errorMsg = initResult.exceptionOrNull()?.message ?: "Unknown error"
                        Log.e("RagViewModel", "Embedding initialization failed: $errorMsg", initResult.exceptionOrNull())
                        
                        // Check if model is corrupted and was deleted, trigger auto-repeat
                        if (
                            (errorMsg.contains("corrupted", ignoreCase = true) ||
                                errorMsg.contains("native library returned false", ignoreCase = true)) &&
                            !modelFile.exists()
                        ) {
                            Log.w("RagViewModel", "Model was corrupted and deleted. Auto-starting download...")
                            startEmbeddingDownload()
                            _error.value = "Embedding model was corrupted. Re-downloading automatically. RAG will be available once complete."
                            _isLoading.value = false
                            ragRepository.updateRagStatus(ragId, RagStatus.INSTALLED)
                            return@launch
                        } else {
                            _error.value = "Failed to initialize embedding engine: $errorMsg. Try re-downloading the embedding model."
                            _isLoading.value = false
                            ragRepository.updateRagStatus(ragId, RagStatus.ERROR)
                            return@launch
                        }
                    }

                    Log.d("RagViewModel", "Embedding engine initialized successfully")
                }

                ragRepository.updateRagStatus(ragId, RagStatus.LOADING)

                val graph = NeuronGraph(embeddingEngine, GraphSettings.DEFAULT)
                val result = ragRepository.loadGraph(ragId, graph, password)

                if (result.isSuccess) {
                    _loadedCount.value = ragRepository.getLoadedRagCount()
                    _isRagEnabledForChat.value = true
                    Log.d("RagViewModel", "RAG loaded successfully, total loaded: ${_loadedCount.value}")
                } else {
                    Log.e("RagViewModel", "Error loading RAG: ${result.exceptionOrNull()?.message}")
                    ragRepository.updateRagStatus(ragId, RagStatus.ERROR)
                    _error.value = result.exceptionOrNull()?.message ?: "Failed to load RAG"
                }
            } catch (e: Exception) {
                ragRepository.updateRagStatus(ragId, RagStatus.ERROR)
                Log.e("RagViewModel", "Error loading RAG: ${e.message}")
                _error.value = e.message
            }

            _isLoading.value = false
        }
    }

    fun unloadRag(ragId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            ragRepository.unloadGraph(ragId)
            val remaining = ragRepository.getLoadedRagCount()
            _loadedCount.value = remaining
            if (remaining == 0) _isRagEnabledForChat.value = false
        }
    }

    fun deleteRag(ragId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            ragRepository.deleteRag(ragId)
            refreshCounts()
        }
    }

    fun installRagFromUri(uri: Uri, name: String? = null) {
        viewModelScope.launch(Dispatchers.IO) {
            _isLoading.value = true
            _error.value = null

            // Auto-initialize embedding engine if needed for document parsing
            if (!embeddingEngine.isInitialized()) {
                val modelFile = EmbeddingEngine.getModelPath(context)
                if (EmbeddingEngine.isModelFileValid(modelFile)) {
                    val config = com.bit.engine.EmbeddingConfig(modelPath = modelFile.absolutePath)
                    embeddingEngine.initialize(config)
                }
            }

            val graph = if (embeddingEngine.isInitialized()) NeuronGraph(embeddingEngine, GraphSettings.DEFAULT) else null
            val result = ragRepository.installRagFromUri(uri, name, graph)
            if (result.isFailure) {
                _error.value = result.exceptionOrNull()?.message ?: "Failed to install RAG"
            } else {
                refreshCounts()
            }

            _isLoading.value = false
        }
    }

    // ==================== Creation Operations ====================

    fun createRagFromText(
        name: String,
        description: String,
        text: String,
        domain: String = "general",
        tags: List<String> = emptyList(),
        onComplete: (Result<InstalledRag>) -> Unit
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            _isLoading.value = true
            _error.value = null

            // Auto-initialize embedding engine if needed
            if (!embeddingEngine.isInitialized()) {
                val modelFile = EmbeddingEngine.getModelPath(context)
                if (!EmbeddingEngine.isModelFileValid(modelFile)) {
                    startEmbeddingDownload()
                    _error.value = "Embedding model is downloading. Please retry when complete."
                    _isLoading.value = false
                    onComplete(Result.failure(Exception("Embedding model is downloading")))
                    return@launch
                }

                val config = EmbeddingConfig(modelPath = modelFile.absolutePath)
                Log.d("RagViewModel", "Initializing embedding engine for document update: ${modelFile.absolutePath}")
                val initResult = embeddingEngine.initialize(config)
                if (initResult.isFailure) {
                    val errorMsg = initResult.exceptionOrNull()?.message ?: "Unknown error"
                    _error.value = "Failed to initialize embedding engine: $errorMsg. Try re-downloading the embedding model."
                    Log.e("RagViewModel", "Embedding init failed in updateDocument: $errorMsg", initResult.exceptionOrNull())
                    _isLoading.value = false
                    onComplete(Result.failure(initResult.exceptionOrNull() ?: Exception("Failed to initialize embedding engine")))
                    return@launch
                }
            }

            val graph = NeuronGraph(embeddingEngine, GraphSettings.DEFAULT)
            val result = ragRepository.createRagFromText(name, description, text, graph, domain, tags)

            if (result.isFailure) {
                _error.value = result.exceptionOrNull()?.message ?: "Failed to create RAG"
            } else {
                refreshCounts()
            }

            _isLoading.value = false
            onComplete(result)
        }
    }

    fun createRagFromFile(
        name: String,
        description: String,
        fileUri: Uri,
        domain: String = "general",
        tags: List<String> = emptyList(),
        onComplete: (Result<InstalledRag>) -> Unit
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            _isLoading.value = true
            _error.value = null

            // Auto-initialize embedding engine if needed
            if (!embeddingEngine.isInitialized()) {
                val modelFile = EmbeddingEngine.getModelPath(context)
                if (!EmbeddingEngine.isModelFileValid(modelFile)) {
                    startEmbeddingDownload()
                    _error.value = "Embedding model is downloading. Please retry when complete."
                    _isLoading.value = false
                    onComplete(Result.failure(Exception("Embedding model is downloading")))
                    return@launch
                }

                val config = EmbeddingConfig(modelPath = modelFile.absolutePath)
                Log.d("RagViewModel", "Initializing embedding engine for document add: ${modelFile.absolutePath}")
                val initResult = embeddingEngine.initialize(config)
                if (initResult.isFailure) {
                    val errorMsg = initResult.exceptionOrNull()?.message ?: "Unknown error"
                    _error.value = "Failed to initialize embedding engine: $errorMsg. Try re-downloading the embedding model."
                    Log.e("RagViewModel", "Embedding init failed in addDocument: $errorMsg", initResult.exceptionOrNull())
                    _isLoading.value = false
                    onComplete(Result.failure(initResult.exceptionOrNull() ?: Exception("Failed to initialize embedding engine")))
                    return@launch
                }
            }

            val graph = NeuronGraph(embeddingEngine, GraphSettings.DEFAULT)
            val result = ragRepository.createRagFromFile(name, description, fileUri, graph, domain, tags)

            if (result.isFailure) {
                _error.value = result.exceptionOrNull()?.message ?: "Failed to create RAG"
            } else {
                refreshCounts()
            }

            _isLoading.value = false
            onComplete(result)
        }
    }

    // ==================== Embedding Initialization ====================

    fun initializeEmbeddingFromFiles() {
        viewModelScope.launch(Dispatchers.IO) {
            _embeddingStatus.value = "Checking models..."
            _isLoading.value = true

            val modelFile = EmbeddingEngine.getModelPath(context)

            if (EmbeddingEngine.isModelFileValid(modelFile)) {
                val config = EmbeddingConfig(
                    modelPath = modelFile.absolutePath
                )

                val result = embeddingEngine.initialize(config)
                if (result.isSuccess) {
                    _isEmbeddingInitialized.value = true
                    _embeddingStatus.value = "Ready (dim: ${embeddingEngine.getDimension()})"
                } else {
                    _isEmbeddingInitialized.value = false
                    _embeddingStatus.value = "Error: ${result.exceptionOrNull()?.message}"
                    _error.value = result.exceptionOrNull()?.message
                }
            } else {
                startEmbeddingDownload()
                _embeddingStatus.value = "Model not ready - downloading embedding model"
                _error.value = "Embedding model missing or invalid. Download started automatically."
            }

            _isLoading.value = false
        }
    }

    // ==================== RAG for Chat Toggle ====================

    fun toggleRagForChat(enabled: Boolean) {
        _isRagEnabledForChat.value = enabled
    }

    // ==================== Query with Display Results ====================

    suspend fun queryAndStoreResults(query: String, topK: Int = 5): String {
        // Use the advanced retrieval pipeline
        val aggregated = ragRepository.queryAllLoadedGraphsWithPipeline(query, topK)

        if (aggregated.ragResults.isEmpty()) {
            Log.w("RagViewModel", "No RAG results found for query: $query")
            _lastRagResults.value = emptyList()
            return ""
        }

        // Store results for UI display
        val displayResults = mutableListOf<RagQueryDisplayResult>()
        for ((rag, retrievalResult) in aggregated.ragResults) {
            for (result in retrievalResult.results) {
                displayResults.add(
                    RagQueryDisplayResult(
                        ragName = rag.name,
                        content = result.node.content,
                        score = result.score,
                        nodeId = result.node.id
                    )
                )
            }
        }
        _lastRagResults.value = displayResults.sortedByDescending { it.score }

        // Build context with confidence-aware prefix
        val contextBuilder = StringBuilder()
        when (aggregated.overallConfidence) {
            RetrievalConfidence.HIGH -> {
                contextBuilder.append("### Relevant Knowledge:\n")
            }
            RetrievalConfidence.MEDIUM -> {
                contextBuilder.append("### Relevant Knowledge:\n")
            }
            RetrievalConfidence.LOW -> {
                contextBuilder.append("### Relevant Knowledge (uncertain — retrieved context may not fully answer the question):\n")
            }
        }
        contextBuilder.append(aggregated.combinedContext)

        Log.d("RagViewModel", "RAG context: ${contextBuilder.length} chars, ${displayResults.size} results, confidence=${aggregated.overallConfidence}")
        return contextBuilder.toString()
    }

    // ==================== Secure RAG Creation ====================

    fun createSecureRagFromText(
        name: String,
        description: String,
        text: String,
        domain: String = "general",
        tags: List<String> = emptyList(),
        adminPassword: String,
        readOnlyUsers: List<com.neuronpacket.UserCredentials> = emptyList(),
        loadingMode: com.neuronpacket.LoadingMode = com.neuronpacket.LoadingMode.EMBEDDED,
        onProgress: (Float, String) -> Unit,
        onComplete: (Result<InstalledRag>) -> Unit
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            _isLoading.value = true
            _error.value = null

            // Auto-initialize embedding engine if needed
            if (!embeddingEngine.isInitialized()) {
                onProgress(0.05f, "Initializing embedding engine...")
                val modelFile = EmbeddingEngine.getModelPath(context)

                if (!EmbeddingEngine.isModelFileValid(modelFile)) {
                    startEmbeddingDownload()
                    _error.value = "Embedding model is downloading. Please retry when complete."
                    _isLoading.value = false
                    onComplete(Result.failure(Exception("Embedding model is downloading")))
                    return@launch
                }

                val config = EmbeddingConfig(modelPath = modelFile.absolutePath)
                Log.d("RagViewModel", "Initializing embedding engine for text RAG creation: ${modelFile.absolutePath}")
                val initResult = embeddingEngine.initialize(config)

                if (initResult.isFailure) {
                    val errorMsg = initResult.exceptionOrNull()?.message ?: "Unknown error"
                    _error.value = "Failed to initialize embedding engine: $errorMsg. Try re-downloading the embedding model."
                    Log.e("RagViewModel", "Embedding init failed in createSecureRagFromText: $errorMsg", initResult.exceptionOrNull())
                    _isLoading.value = false
                    onComplete(Result.failure(initResult.exceptionOrNull() ?: Exception("Failed to initialize embedding engine")))
                    return@launch
                }
            }

            val graph = NeuronGraph(embeddingEngine, GraphSettings.DEFAULT)
            val result = ragRepository.createSecureRagFromText(
                name, description, text, graph, domain, tags,
                adminPassword, readOnlyUsers, loadingMode, onProgress
            )

            if (result.isFailure) {
                _error.value = result.exceptionOrNull()?.message ?: "Failed to create secure RAG"
            } else {
                refreshCounts()
            }

            _isLoading.value = false
            onComplete(result)
        }
    }

    fun createSecureRagFromFile(
        name: String,
        description: String,
        fileUri: Uri,
        domain: String = "general",
        tags: List<String> = emptyList(),
        adminPassword: String,
        readOnlyUsers: List<com.neuronpacket.UserCredentials> = emptyList(),
        loadingMode: com.neuronpacket.LoadingMode = com.neuronpacket.LoadingMode.EMBEDDED,
        onProgress: (Float, String) -> Unit,
        onComplete: (Result<InstalledRag>) -> Unit
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            _isLoading.value = true
            _error.value = null

            // Auto-initialize embedding engine if needed
            if (!embeddingEngine.isInitialized()) {
                onProgress(0.05f, "Initializing embedding engine...")
                val modelFile = EmbeddingEngine.getModelPath(context)

                if (!EmbeddingEngine.isModelFileValid(modelFile)) {
                    startEmbeddingDownload()
                    _error.value = "Embedding model is downloading. Please retry when complete."
                    _isLoading.value = false
                    onComplete(Result.failure(Exception("Embedding model is downloading")))
                    return@launch
                }

                val config = EmbeddingConfig(modelPath = modelFile.absolutePath)
                Log.d("RagViewModel", "Initializing embedding engine for secure RAG creation from file: ${modelFile.absolutePath}")
                val initResult = embeddingEngine.initialize(config)

                if (initResult.isFailure) {
                    val errorMsg = initResult.exceptionOrNull()?.message ?: "Unknown error"
                    _error.value = "Failed to initialize embedding engine: $errorMsg. Try re-downloading the embedding model."
                    Log.e("RagViewModel", "Embedding init failed in createSecureRagFromFile: $errorMsg", initResult.exceptionOrNull())
                    _isLoading.value = false
                    onComplete(Result.failure(initResult.exceptionOrNull() ?: Exception("Failed to initialize embedding engine")))
                    return@launch
                }
            }

            val graph = NeuronGraph(embeddingEngine, GraphSettings.DEFAULT)
            val result = ragRepository.createSecureRagFromFile(
                name, description, fileUri, graph, domain, tags,
                adminPassword, readOnlyUsers, loadingMode, onProgress
            )

            if (result.isFailure) {
                _error.value = result.exceptionOrNull()?.message ?: "Failed to create secure RAG"
            } else {
                refreshCounts()
            }

            _isLoading.value = false
            onComplete(result)
        }
    }

}
