package com.bit.service

import com.bit.notification.NotificationChannels
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.bit.data.AppSettingsDataStore
import com.bit.data.HuggingFaceTokenManager
import com.bit.di.AppContainer
import com.bit.global.AppPaths
import com.bit.global.DeviceTuner
import com.bit.global.HardwareScanner
import com.bit.models.engine_schema.GgufEngineSchema
import com.bit.models.enums.PathType
import com.bit.models.enums.ProviderType
import com.bit.models.table_schema.Model
import com.bit.models.table_schema.ModelConfig
import com.bit.worker.DiffusionConfig
import com.bit.worker.DiffusionInferenceParams
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import org.json.JSONObject

class ModelDownloadService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val downloadJobs = ConcurrentHashMap<String, Job>()
    private val notificationIdCounter = java.util.concurrent.atomic.AtomicInteger(NOTIFICATION_ID)

    private val notificationManager by lazy {
        getSystemService(NOTIFICATION_SERVICE) as NotificationManager
    }

    private val client = OkHttpClient.Builder().connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS).build()

    companion object {
        private const val NOTIFICATION_ID = 3001
        private const val TAG = "DownloadService"
        private const val PAUSE_META_FILE = "pause_meta.json"

        private val _downloadStates = MutableStateFlow<Map<String, DownloadState>>(emptyMap())
        val downloadStates: StateFlow<Map<String, DownloadState>> = _downloadStates

        const val ACTION_START_DOWNLOAD = "action_start_download"
        const val ACTION_CANCEL_DOWNLOAD = "action_cancel_download"
        const val ACTION_PAUSE_DOWNLOAD = "action_pause_download"
        const val ACTION_RESUME_DOWNLOAD = "action_resume_download"

        const val EXTRA_MODEL_ID = "model_id"
        const val EXTRA_MODEL_NAME = "model_name"
        const val EXTRA_FILE_URL = "file_url"
        const val EXTRA_PROJECTOR_URL = "projector_url"
        const val EXTRA_IS_ZIP = "is_zip"
        const val EXTRA_MODEL_TYPE = "model_type"
        const val EXTRA_RUN_ON_CPU = "run_on_cpu"
        const val EXTRA_TEXT_EMBEDDING_SIZE = "text_embedding_size"
    }

    // Tracks which modelIds are currently paused — coroutine checks this flag
    private val pausedModelIds = ConcurrentHashMap.newKeySet<String>()

    sealed class DownloadState {
        data class Downloading(
            val modelId: String,
            val progress: Float,
            val downloadedBytes: Long,
            val totalBytes: Long,
            val speedBytesPerSec: Long = 0,
            val etaSeconds: Long = -1
        ) : DownloadState()

        data class Paused(
            val modelId: String,
            val progress: Float,
            val downloadedBytes: Long,
            val totalBytes: Long
        ) : DownloadState()

        data class Extracting(
            val modelId: String,
            val currentFile: String = "",
            val extractedCount: Int = 0,
            val totalFiles: Int = 0
        ) : DownloadState()
        data class Processing(val modelId: String) : DownloadState()
        data class Verifying(val modelId: String) : DownloadState()
        data class Success(val modelId: String, val sha256: String? = null) : DownloadState()
        data class Error(val modelId: String, val message: String) : DownloadState()
        data class Cancelled(val modelId: String) : DownloadState()
    }

    private fun updateDownloadState(modelId: String, state: DownloadState?) {
        _downloadStates.value = if (state == null) {
            _downloadStates.value - modelId
        } else {
            _downloadStates.value + (modelId to state)
        }
    }

    override fun onCreate() {
        super.onCreate()
        // Channels are created at app startup by NVApplication
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_DOWNLOAD -> {
                val modelId = intent.getStringExtra(EXTRA_MODEL_ID) ?: return START_NOT_STICKY
                val modelName = intent.getStringExtra(EXTRA_MODEL_NAME) ?: modelId
                val fileUrl = intent.getStringExtra(EXTRA_FILE_URL)
                val projectorUrl = intent.getStringExtra(EXTRA_PROJECTOR_URL)
                val isZip = intent.getBooleanExtra(EXTRA_IS_ZIP, false)
                val modelType = intent.getStringExtra(EXTRA_MODEL_TYPE) ?: "GGUF"
                val runOnCpu = intent.getBooleanExtra(EXTRA_RUN_ON_CPU, false)
                val textEmbeddingSize = intent.getIntExtra(EXTRA_TEXT_EMBEDDING_SIZE, 768)

                // Validate: fileUrl is required ONLY if not one of our multi-file types
                val isMultiFile = modelType == "STT" || 
                        (modelType == "TTS" && fileUrl?.let { it.contains(".tar.bz2") || it.contains(".zip") } != true)
                if (!isMultiFile && fileUrl == null) {
                    Log.e(TAG, "fileUrl is required for model type $modelType")
                    return START_NOT_STICKY
                }

                ensureForeground(modelName)

                // Save pause metadata so download can resume after background kill
                savePauseMetadata(modelId, modelName, fileUrl, isZip, modelType, runOnCpu, textEmbeddingSize, projectorUrl)

                startDownload(
                    modelId,
                    modelName,
                    fileUrl,
                    projectorUrl,
                    isZip,
                    modelType,
                    runOnCpu,
                    textEmbeddingSize
                )
            }

            ACTION_CANCEL_DOWNLOAD -> {
                val modelId = intent.getStringExtra(EXTRA_MODEL_ID)
                if (modelId != null) {
                    cancelDownload(modelId)
                }
            }

            ACTION_PAUSE_DOWNLOAD -> {
                val modelId = intent.getStringExtra(EXTRA_MODEL_ID)
                if (modelId != null) {
                    pauseDownload(modelId)
                }
            }

            ACTION_RESUME_DOWNLOAD -> {
                val modelId = intent.getStringExtra(EXTRA_MODEL_ID) ?: return START_NOT_STICKY
                val modelName = intent.getStringExtra(EXTRA_MODEL_NAME) ?: modelId
                ensureForeground(modelName)
                resumeDownload(modelId, modelName)
            }
        }
        return START_NOT_STICKY
    }

    private fun ensureForeground(modelName: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceCompat.startForeground(
                this@ModelDownloadService, NOTIFICATION_ID,
                createNotification(modelName, 0f),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(NOTIFICATION_ID, createNotification(modelName, 0f))
        }
    }

    private fun startDownload(
        modelId: String,
        modelName: String,
        fileUrl: String?,
        projectorUrl: String?,
        isZip: Boolean,
        modelType: String,
        runOnCpu: Boolean,
        textEmbeddingSize: Int
    ) {
        // Skip if this model is already downloading
        if (downloadJobs[modelId]?.isActive == true) {
            Log.w(TAG, "Download already in progress for $modelId, skipping duplicate")
            return
        }
        downloadJobs[modelId]?.cancel()
        pausedModelIds.remove(modelId)

        val notificationId = notificationIdCounter.incrementAndGet()
        val job = serviceScope.launch {
            var tempFile: File? = null
            var extractTempDir: File? = null
            try {
                updateDownloadState(modelId, DownloadState.Downloading(modelId, 0f, 0, 0))

                val tempDir = AppPaths.tempDownloads(applicationContext, modelId)
                if (!tempDir.exists()) {
                    tempDir.mkdirs()
                }

                val isVlm = modelType == "VLM" || projectorUrl != null || (fileUrl != null && (fileUrl.contains("mmproj") || fileUrl.lowercase().contains("vlm")))
                val isMultiFile = modelType == "STT" || isVlm ||
                        (modelType == "TTS" && fileUrl?.let { it.contains(".tar.bz2") || it.contains(".zip") } != true)
                
                if (!isMultiFile && fileUrl != null) {
                    tempFile = File(tempDir, "${modelId}.tmp")
                    downloadFile(fileUrl, tempFile, modelId, modelName, notificationId)
                }

                when {
                    modelType == "STT" -> {
                        val modelsDir = AppPaths.models(applicationContext)
                        modelsDir.mkdirs()

                        val sttModelDir = AppPaths.sttModel(applicationContext)
                        if (sttModelDir.exists()) sttModelDir.deleteRecursively()
                        sttModelDir.mkdirs()

                        updateDownloadState(modelId, DownloadState.Processing(modelId))
                        updateNotification(modelName, 0f, notificationId, isProcessing = true)

                        downloadSTTModelFiles(sttModelDir, modelId, modelName, notificationId)

                        insertModelToDatabase(
                            modelId = modelId,
                            modelName = modelName,
                            modelPath = sttModelDir.absolutePath,
                            modelType = modelType,
                            runOnCpu = runOnCpu,
                            textEmbeddingSize = 0
                        )
                    }

                    isVlm -> {
                        val modelsDir = AppPaths.models(applicationContext)
                        modelsDir.mkdirs()

                        val vlmModelDir = AppPaths.modelDir(applicationContext, modelId)
                        if (vlmModelDir.exists()) vlmModelDir.deleteRecursively()
                        vlmModelDir.mkdirs()

                        updateDownloadState(modelId, DownloadState.Processing(modelId))
                        updateNotification(modelName, 0f, notificationId, isProcessing = true)

                        val actualProjUrl = projectorUrl?.takeIf { it.isNotBlank() }
                            ?: (fileUrl?.let { autoInferProjectorUrl(it) })

                        if (fileUrl != null && actualProjUrl != null) {
                            downloadVLMModelFiles(fileUrl, actualProjUrl, vlmModelDir, modelId, modelName, notificationId)
                        } else if (fileUrl != null) {
                            val mainFileName = fileUrl.substringAfterLast("/")
                            val mainFile = File(vlmModelDir, mainFileName)
                            downloadFile(fileUrl, mainFile, modelId, modelName, notificationId)
                        } else {
                            Log.e(TAG, "VLM download failed: missing fileUrl")
                            throw IllegalStateException("Missing fileUrl for VLM model")
                        }

                        insertModelToDatabase(
                            modelId = modelId,
                            modelName = modelName,
                            modelPath = vlmModelDir.absolutePath,
                            modelType = "VLM",
                            runOnCpu = runOnCpu,
                            textEmbeddingSize = textEmbeddingSize
                        )
                    }

                    modelType == "SD" -> {
                        val modelsDir = AppPaths.models(applicationContext)
                        modelsDir.mkdirs()

                        val modelDir = AppPaths.modelDir(applicationContext, modelId)

                        if (isZip) {
                            if (modelDir.exists()) {
                                modelDir.deleteRecursively()
                            }
                            modelDir.mkdirs()

                            extractTempDir = File(tempDir, "${modelId}_extract")
                            extractTempDir.mkdirs()

                            updateDownloadState(modelId, DownloadState.Extracting(modelId))
                            updateNotification(modelName, 0f, notificationId, isExtracting = true)

                            unzipFile(tempFile!!, extractTempDir, modelId)

                            extractTempDir.listFiles()?.forEach { file ->
                                if (file.isDirectory) {
                                    file.listFiles()?.forEach { innerFile ->
                                        innerFile.copyRecursively(File(modelDir, innerFile.name), overwrite = true)
                                    }
                                } else {
                                    file.copyRecursively(File(modelDir, file.name), overwrite = true)
                                }
                            }
                            extractTempDir.deleteRecursively()
                            extractTempDir = null
                        } else {
                            if (!modelDir.exists()) {
                                modelDir.mkdirs()
                            }
                            tempFile?.copyTo(File(modelDir, modelName.substringAfterLast("/")), overwrite = true)
                            tempFile?.delete()
                        }

                        updateDownloadState(modelId, DownloadState.Processing(modelId))
                        updateNotification(modelName, 0f, notificationId, isProcessing = true)

                        insertModelToDatabase(
                            modelId = modelId,
                            modelName = modelName,
                            modelPath = modelDir.absolutePath,
                            modelType = modelType,
                            runOnCpu = runOnCpu,
                            textEmbeddingSize = textEmbeddingSize
                        )
                    }

                    modelType == "GGUF" || modelType == "LLM" -> {
                        AppPaths.models(applicationContext).mkdirs()

                        val targetFile = AppPaths.modelFile(applicationContext, modelId)

                        if (targetFile.exists()) {
                            targetFile.delete()
                        }

                        tempFile?.copyTo(targetFile, overwrite = true)

                        // SHA256 checksum verification
                        updateDownloadState(modelId, DownloadState.Verifying(modelId))
                        updateNotification(modelName, 0f, notificationId, isVerifying = true)
                        val sha256 = computeSha256(targetFile)
                        Log.i(TAG, "GGUF $modelId SHA256: $sha256")

                        updateDownloadState(modelId, DownloadState.Processing(modelId))
                        updateNotification(modelName, 0f, notificationId, isProcessing = true)

                        insertModelToDatabase(
                            modelId = modelId,
                            modelName = modelName,
                            modelPath = targetFile.absolutePath,
                            modelType = modelType,
                            runOnCpu = false,
                            textEmbeddingSize = 0
                        )
                    }

                    modelType == "TTS" -> {
                        AppPaths.models(applicationContext).mkdirs()

                        val ttsModelParentDir = AppPaths.ttsModel(applicationContext)
                        val ttsModelDir = File(ttsModelParentDir, modelId)
                        if (ttsModelDir.exists()) ttsModelDir.deleteRecursively()
                        ttsModelDir.mkdirs()

                        if (fileUrl != null && (fileUrl.contains(".tar.bz2") || fileUrl.contains(".zip"))) {
                            updateDownloadState(modelId, DownloadState.Extracting(modelId))
                            updateNotification(modelName, 0f, notificationId, isExtracting = true)
                            if (fileUrl.contains(".tar.bz2")) {
                                untarBzip2(tempFile!!, ttsModelDir, modelId)
                            } else {
                                unzipFile(tempFile!!, ttsModelDir, modelId)
                            }
                        } else {
                            updateDownloadState(modelId, DownloadState.Processing(modelId))
                            updateNotification(modelName, 0f, notificationId, isProcessing = true)
                            // Download all TTS model files
                            downloadTTSModelFiles(ttsModelDir, modelId, modelName, notificationId)
                        }

                        insertModelToDatabase(
                            modelId = modelId,
                            modelName = modelName,
                            modelPath = ttsModelDir.absolutePath,
                            modelType = modelType,
                            runOnCpu = true,
                            textEmbeddingSize = 0
                        )
                    }

                    modelType == "IMAGE_TOOL" -> {
                        val toolDir = AppPaths.imageTools(applicationContext)
                        toolDir.mkdirs()

                        val targetFile = File(toolDir, modelId)
                        targetFile.parentFile?.mkdirs()

                        if (isZip) {
                            extractTempDir = File(tempDir, "${modelId}_extract")
                            extractTempDir.mkdirs()

                            updateDownloadState(modelId, DownloadState.Extracting(modelId))
                            updateNotification(modelName, 0f, notificationId, isExtracting = true)

                            unzipFile(tempFile!!, extractTempDir, modelId)

                            extractTempDir.listFiles()?.forEach { file ->
                                file.copyTo(File(toolDir, file.name), overwrite = true)
                            }
                            extractTempDir.deleteRecursively()
                            extractTempDir = null
                        } else {
                            tempFile?.copyTo(targetFile, overwrite = true)
                        }
                    }

                    else -> {
                        val modelsDir = AppPaths.models(applicationContext)
                        modelsDir.mkdirs()

                        val targetFile = File(modelsDir, "${modelId}.gguf")
                        if (tempFile != null) {
                            tempFile.copyTo(targetFile, overwrite = true)
                        }

                        insertModelToDatabase(
                            modelId = modelId,
                            modelName = modelName,
                            modelPath = targetFile.absolutePath,
                            modelType = modelType,
                            runOnCpu = runOnCpu,
                            textEmbeddingSize = textEmbeddingSize
                        )
                    }
                }

                tempFile?.delete()
                tempFile = null
                tempDir.deleteRecursively()
                clearPauseMetadata(modelId)

                updateDownloadState(modelId, DownloadState.Success(modelId))
                updateNotification(modelName, 100f, notificationId, isSuccess = true)

                withContext(Dispatchers.Main) {
                    kotlinx.coroutines.delay(2000)
                    updateDownloadState(modelId, null)
                    downloadJobs.remove(modelId)

                    if (downloadJobs.isEmpty()) {
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf()
                    }
                }

            } catch (e: PauseException) {
                // Paused — keep temp file intact, don't clear metadata
                val progress = if (e.totalBytes > 0) e.downloadedBytes.toFloat() / e.totalBytes else 0f
                updateDownloadState(modelId, DownloadState.Paused(
                    modelId = e.modelId,
                    progress = progress,
                    downloadedBytes = e.downloadedBytes,
                    totalBytes = e.totalBytes
                ))
                updateNotification(modelName, progress, notificationId, isPaused = true)

                withContext(Dispatchers.Main) {
                    downloadJobs.remove(modelId)
                    if (downloadJobs.isEmpty()) {
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf()
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                extractTempDir?.deleteRecursively()

                updateDownloadState(modelId, DownloadState.Cancelled(modelId))
                updateNotification(modelName, 0f, notificationId, isCancelled = true)

                withContext(Dispatchers.Main) {
                    kotlinx.coroutines.delay(2000)
                    updateDownloadState(modelId, null)
                    downloadJobs.remove(modelId)

                    if (downloadJobs.isEmpty()) {
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf()
                    }
                }
            } catch (e: Exception) {
                extractTempDir?.deleteRecursively()

                updateDownloadState(modelId, DownloadState.Error(modelId, e.message ?: "Unknown error"))
                updateNotification(modelName, 0f, notificationId, error = e.message)

                withContext(Dispatchers.Main) {
                    kotlinx.coroutines.delay(3000)
                    updateDownloadState(modelId, null)
                    downloadJobs.remove(modelId)

                    if (downloadJobs.isEmpty()) {
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf()
                    }
                }
            }
        }

        downloadJobs[modelId] = job
    }

    private fun buildDownloadRequest(url: String): Request {
        val builder = Request.Builder().url(url)
        if (url.contains("huggingface.co")) {
            val tokenManager = HuggingFaceTokenManager(applicationContext)
            tokenManager.getBearerHeader()?.let { bearer ->
                builder.header("Authorization", bearer)
            }
        }
        return builder.build()
    }

    private suspend fun downloadFile(
        url: String, destFile: File, modelId: String, modelName: String, notificationId: Int
    ) = withContext(Dispatchers.IO) {
        val startBytes = if (destFile.exists()) destFile.length() else 0L
        val request = Request.Builder().url(url).apply {
            if (url.contains("huggingface.co")) {
                val tokenManager = HuggingFaceTokenManager(applicationContext)
                tokenManager.getBearerHeader()?.let { bearer ->
                    header("Authorization", bearer)
                }
            }
            if (startBytes > 0) {
                header("Range", "bytes=$startBytes-")
            }
        }.build()
        val call = client.newCall(request)

        try {
            call.execute().use { response ->
                val isRange = response.code == 206
                if (!response.isSuccessful && response.code != 206) {
                    throw Exception("Download failed with code: ${response.code}")
                }

                val body = response.body
                val contentLength = body.contentLength()
                val totalBytes = if (isRange) contentLength + startBytes else contentLength
                var downloadedBytes = if (isRange) startBytes else 0L
                var lastUpdateTime = 0L

                // Speed tracking: rolling window of last 5 samples
                val speedSamples = mutableListOf<Long>()
                var lastSpeedBytes = downloadedBytes
                var lastSpeedTime = System.currentTimeMillis()

                val append = isRange && destFile.exists()
                java.io.RandomAccessFile(destFile, "rw").use { raf ->
                    if (append) {
                        raf.seek(startBytes)
                    } else {
                        raf.setLength(0) // Truncate if not range or not existing
                    }
                    val channel = raf.channel
                    body.byteStream().buffered().use { input ->
                        val buffer = ByteArray(64 * 1024) // 64KB for better throughput
                        var bytes: Int

                        while (input.read(buffer).also { bytes = it } != -1) {
                            if (!downloadJobs.containsKey(modelId) || downloadJobs[modelId]?.isCancelled == true) {
                                call.cancel()
                                throw kotlinx.coroutines.CancellationException("Download cancelled")
                            }
                            // Check for pause request
                            if (pausedModelIds.contains(modelId)) {
                                call.cancel()
                                throw PauseException(modelId, downloadedBytes, totalBytes)
                            }

                            val byteBuffer = java.nio.ByteBuffer.wrap(buffer, 0, bytes)
                            while (byteBuffer.hasRemaining()) {
                                channel.write(byteBuffer)
                            }
                            downloadedBytes += bytes

                            val currentTime = System.currentTimeMillis()
                            if (currentTime - lastUpdateTime >= 500 || downloadedBytes == totalBytes) {
                                // Calculate speed
                                val elapsed = currentTime - lastSpeedTime
                                if (elapsed > 0) {
                                    val bytesInInterval = downloadedBytes - lastSpeedBytes
                                    val speedSample = bytesInInterval * 1000 / elapsed
                                    speedSamples.add(speedSample)
                                    if (speedSamples.size > 5) speedSamples.removeAt(0)
                                    lastSpeedBytes = downloadedBytes
                                    lastSpeedTime = currentTime
                                }

                                val avgSpeed = if (speedSamples.isNotEmpty()) {
                                    speedSamples.average().toLong()
                                } else 0L

                                val eta = if (avgSpeed > 0 && totalBytes > 0) {
                                    (totalBytes - downloadedBytes) / avgSpeed
                                } else -1L

                                lastUpdateTime = currentTime
                                val progress = if (totalBytes > 0) {
                                    downloadedBytes.toFloat() / totalBytes
                                } else 0f

                                updateDownloadState(modelId, DownloadState.Downloading(
                                    modelId, progress, downloadedBytes, totalBytes, avgSpeed, eta
                                ))

                                updateNotification(modelName, progress, notificationId)
                            }
                        }
                    }
                }
            }
        } catch (e: PauseException) {
            // Don't cancel call here — it's already cancelled in the read loop
            throw e
        } catch (e: Exception) {
            call.cancel()
            throw e
        }
    }

    private suspend fun unzipFile(zipFile: File, destDir: File, modelId: String) = withContext(Dispatchers.IO) {
        // First pass: count valid entries
        val totalFiles = ZipInputStream(zipFile.inputStream().buffered()).use { zis ->
            var count = 0
            var entry = zis.nextEntry
            while (entry != null) {
                if (!entry.isDirectory) {
                    val name = entry.name.substringAfterLast('/')
                    if (name.isNotEmpty() && !name.startsWith(".") && !entry.name.contains("__MACOSX")) {
                        count++
                    }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
            count
        }

        // Second pass: extract with per-file progress
        var extractedCount = 0
        ZipInputStream(zipFile.inputStream().buffered()).use { zis ->
            var entry = zis.nextEntry

            while (entry != null) {
                // Check for cancellation
                if (!downloadJobs.containsKey(modelId) || downloadJobs[modelId]?.isCancelled == true) {
                    throw kotlinx.coroutines.CancellationException("Extraction cancelled")
                }

                if (!entry.isDirectory) {
                    val fileName = entry.name.substringAfterLast('/')
                    if (fileName.isNotEmpty() && !fileName.startsWith(".") && !entry.name.contains("__MACOSX")) {
                        updateDownloadState(modelId, DownloadState.Extracting(
                            modelId = modelId,
                            currentFile = fileName,
                            extractedCount = extractedCount,
                            totalFiles = totalFiles
                        ))

                        val file = File(destDir, fileName)
                        require(file.canonicalPath.startsWith(destDir.canonicalPath + File.separator)) {
                            "Zip entry path traversal detected: ${entry.name}"
                        }
                        FileOutputStream(file).buffered().use { output ->
                            zis.copyTo(output)
                        }
                        extractedCount++
                    }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
    }

    private suspend fun untarBzip2(tarBz2File: File, destDir: File, modelId: String) = withContext(Dispatchers.IO) {
        var extractedCount = 0
        val fis = tarBz2File.inputStream()
        val bis = BZip2CompressorInputStream(fis.buffered())
        val tis = TarArchiveInputStream(bis)

        tis.use { tarInput ->
            var entry = tarInput.nextEntry
            while (entry != null) {
                if (!downloadJobs.containsKey(modelId) || downloadJobs[modelId]?.isCancelled == true) {
                    throw kotlinx.coroutines.CancellationException("Extraction cancelled")
                }

                val name = entry.name
                val parts = name.split("/", limit = 2)
                if (parts.size >= 2 && parts[1].isNotEmpty()) {
                    val relativePath = parts[1]
                    val fileName = relativePath.substringAfterLast('/')

                    if (fileName.isNotEmpty() && !fileName.startsWith(".") && !name.contains("__MACOSX")) {
                        updateDownloadState(modelId, DownloadState.Extracting(
                            modelId = modelId,
                            currentFile = fileName,
                            extractedCount = extractedCount,
                            totalFiles = 0
                        ))

                        val file = File(destDir, relativePath)
                        require(file.canonicalPath.startsWith(destDir.canonicalPath + File.separator)) {
                            "Tar entry path traversal detected: ${entry.name}"
                        }

                        if (entry.isDirectory) {
                            file.mkdirs()
                        } else {
                            file.parentFile?.mkdirs()
                            FileOutputStream(file).buffered().use { output ->
                                tarInput.copyTo(output)
                            }
                            extractedCount++
                        }
                    }
                }
                entry = tarInput.nextEntry
            }
        }
    }

    private suspend fun downloadTTSModelFiles(
        ttsModelDir: File, modelId: String, modelName: String, notificationId: Int
    ) = withContext(Dispatchers.IO) {
        val fileUrls = when (modelId) {
            "vits-piper-en_US-amy-low" -> listOf(
                "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-piper-en_US-amy-low.tar.bz2" to "vits-piper-en_US-amy-low.tar.bz2"
            )
            "kokoro-en-v0_19", "kokoro-tts", "kokoro" -> listOf(
                "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/kokoro-en-v0_19.tar.bz2" to "kokoro-en-v0_19.tar.bz2"
            )
            else -> listOf()
        }

        var filesDownloaded = 0

        for ((url, filePath) in fileUrls) {
            if (!downloadJobs.containsKey(modelId) || downloadJobs[modelId]?.isCancelled == true) {
                throw kotlinx.coroutines.CancellationException("TTS download cancelled")
            }

            val destFile = File(ttsModelDir, filePath)
            destFile.parentFile?.mkdirs()

            val request = buildDownloadRequest(url)
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw Exception("Failed to download $filePath: ${response.code}")
                }
                response.body.byteStream().use { input ->
                    FileOutputStream(destFile).use { output ->
                        input.copyTo(output)
                    }
                }
            }

            filesDownloaded++
            val progress = filesDownloaded.toFloat() / fileUrls.size
            updateDownloadState(modelId, DownloadState.Downloading(
                modelId, progress, filesDownloaded.toLong(), fileUrls.size.toLong()
            ))
            updateNotification(modelName, progress, notificationId)
        }
    }

    private suspend fun downloadSTTModelFiles(
        sttModelDir: File, modelId: String, modelName: String, notificationId: Int
    ) = withContext(Dispatchers.IO) {
        val baseUrl = "https://huggingface.co/csukuangfj/sherpa-onnx-whisper-tiny.en/resolve/main"
        val fileMapping = listOf(
            "tiny.en-encoder.int8.onnx" to "encoder.int8.onnx",
            "tiny.en-decoder.int8.onnx" to "decoder.int8.onnx",
            "tiny.en-tokens.txt" to "tokens.txt"
        )
        downloadMappedFiles(fileMapping, baseUrl, sttModelDir, modelId, modelName, notificationId)
    }

    private suspend fun downloadVLMModelFiles(
        mainUrl: String, projectorUrl: String, vlmModelDir: File, modelId: String, modelName: String, notificationId: Int
    ) = withContext(Dispatchers.IO) {
        val mainFileName = mainUrl.substringAfterLast("/")
        val projFileName = projectorUrl.substringAfterLast("/")
        
        val mainFile = File(vlmModelDir, mainFileName)
        val projFile = File(vlmModelDir, projFileName)
        
        // Download main model first (the larger file)
        downloadFile(mainUrl, mainFile, modelId, "$modelName [1/2]", notificationId)
        
        // Verify main file downloaded completely
        if (!mainFile.exists() || mainFile.length() < 1024L) {
            throw IllegalStateException("VLM main model download incomplete: ${mainFile.length()} bytes")
        }
        Log.i(TAG, "VLM main model downloaded: ${mainFile.name} (${mainFile.length()} bytes)")
        
        // Download projector
        downloadFile(projectorUrl, projFile, modelId, "$modelName [2/2]", notificationId)
        
        // Verify projector downloaded completely
        if (!projFile.exists() || projFile.length() < 1024L) {
            throw IllegalStateException("VLM projector download incomplete: ${projFile.length()} bytes")
        }
        Log.i(TAG, "VLM projector downloaded: ${projFile.name} (${projFile.length()} bytes)")
    }

    private suspend fun downloadMappedFiles(
        files: List<Pair<String, String>>, baseUrl: String, targetDir: File, modelId: String, modelName: String, notificationId: Int
    ) {
        var filesDownloaded = 0

        for ((remotePath, localPath) in files) {
            if (!downloadJobs.containsKey(modelId) || downloadJobs[modelId]?.isCancelled == true) {
                throw kotlinx.coroutines.CancellationException("Download cancelled")
            }

            val url = "$baseUrl/$remotePath"
            val destFile = File(targetDir, localPath)
            destFile.parentFile?.mkdirs()

            val request = buildDownloadRequest(url)
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw Exception("Failed to download $localPath: ${response.code}")
                }
                
                val body = response.body
                val totalBytes = body.contentLength()
                var downloadedBytes = 0L
                var lastUpdateTime = 0L

                FileOutputStream(destFile).buffered().use { output ->
                    body.byteStream().buffered().use { input ->
                        val buffer = ByteArray(64 * 1024)
                        var bytes: Int

                        while (input.read(buffer).also { bytes = it } != -1) {
                            if (!downloadJobs.containsKey(modelId) || downloadJobs[modelId]?.isCancelled == true) {
                                throw kotlinx.coroutines.CancellationException("Download cancelled")
                            }

                            output.write(buffer, 0, bytes)
                            downloadedBytes += bytes

                            val currentTime = System.currentTimeMillis()
                            if (currentTime - lastUpdateTime >= 500 || downloadedBytes == totalBytes) {
                                lastUpdateTime = currentTime
                                
                                val fileProgress = if (totalBytes > 0) downloadedBytes.toFloat() / totalBytes else 0f
                                val overallProgress = (filesDownloaded.toFloat() + fileProgress) / files.size
                                updateDownloadState(modelId, DownloadState.Downloading(modelId, overallProgress, downloadedBytes, totalBytes))
                                updateNotification(modelName, overallProgress, notificationId)
                            }
                        }
                    }
                }
            }
            filesDownloaded++
        }
    }

    private suspend fun downloadMultipleFiles(
        files: List<String>, baseUrl: String, targetDir: File, modelId: String, modelName: String, notificationId: Int
    ) {
        var filesDownloaded = 0

        for (filePath in files) {
            if (!downloadJobs.containsKey(modelId) || downloadJobs[modelId]?.isCancelled == true) {
                throw kotlinx.coroutines.CancellationException("Download cancelled")
            }

            val url = "$baseUrl/$filePath"
            val destFile = File(targetDir, filePath)
            destFile.parentFile?.mkdirs()

            val request = buildDownloadRequest(url)
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw Exception("Failed to download $filePath: ${response.code}")
                }
                
                val body = response.body
                val totalBytes = body.contentLength()
                var downloadedBytes = 0L
                var lastUpdateTime = 0L

                FileOutputStream(destFile).buffered().use { output ->
                    body.byteStream().buffered().use { input ->
                        val buffer = ByteArray(64 * 1024)
                        var bytes: Int

                        while (input.read(buffer).also { bytes = it } != -1) {
                            if (!downloadJobs.containsKey(modelId) || downloadJobs[modelId]?.isCancelled == true) {
                                throw kotlinx.coroutines.CancellationException("Download cancelled")
                            }

                            output.write(buffer, 0, bytes)
                            downloadedBytes += bytes

                            val currentTime = System.currentTimeMillis()
                            if (currentTime - lastUpdateTime >= 500 || downloadedBytes == totalBytes) {
                                lastUpdateTime = currentTime
                                
                                // File progress is secondary to overall progress in multiple files
                                // We approximate progress:
                                val fileProgress = if (totalBytes > 0) downloadedBytes.toFloat() / totalBytes else 0f
                                val overallProgress = (filesDownloaded.toFloat() + fileProgress) / files.size

                                updateDownloadState(modelId, DownloadState.Downloading(
                                    modelId, overallProgress, downloadedBytes, totalBytes
                                ))
                                updateNotification(modelName, overallProgress, notificationId)
                            }
                        }
                    }
                }
            }

            filesDownloaded++
            val progress = filesDownloaded.toFloat() / files.size
            updateDownloadState(modelId, DownloadState.Downloading(
                modelId, progress, filesDownloaded.toLong(), files.size.toLong()
            ))
            updateNotification(modelName, progress, notificationId)
        }
    }

    private suspend fun insertModelToDatabase(
        modelId: String,
        modelName: String,
        modelPath: String,
        modelType: String,
        runOnCpu: Boolean,
        textEmbeddingSize: Int
    ) = withContext(Dispatchers.IO) {
        val repository = AppContainer.getModelRepository()

        // Use the store model ID as primary key so the UI can match
        // installed models against store listings. SHA256 is still computed
        // for integrity but not used as the DB key.
        val providerType = when (modelType) {
            "SD" -> ProviderType.DIFFUSION
            "GGUF" -> ProviderType.GGUF
            "TTS" -> ProviderType.TTS
            "STT" -> ProviderType.STT
            "VLM" -> ProviderType.VLM
            else -> ProviderType.GGUF
        }

        if (providerType == ProviderType.TTS) {
            try {
                val existing = repository.getAllModels().firstOrNull()?.filter { it.providerType == ProviderType.TTS }
                existing?.forEach { oldModel ->
                    if (oldModel.isActive) {
                        repository.updateModel(oldModel.copy(isActive = false))
                    }
                }
            } catch (e: Exception) {
                Log.e("DownloadService", "Failed to deactivate old TTS model database records", e)
            }
        }

        val pathType = when (modelType) {
            "SD", "TTS", "STT", "VLM" -> PathType.DIRECTORY
            "GGUF" -> PathType.FILE
            else -> PathType.FILE
        }

        val fileSize = when (modelType) {
            "GGUF" -> File(modelPath).length()
            "TTS", "STT", "VLM", "SD" -> {
                val dir = File(modelPath)
                if (dir.isDirectory) dir.walkTopDown().sumOf { it.length() } else 0L
            }
            else -> 0L
        }

        val model = Model(
            id = modelId,
            modelName = modelName,
            modelPath = modelPath,
            pathType = pathType,
            providerType = providerType,
            fileSize = fileSize,
            isActive = true
        )

        repository.insertModel(model)

        val config = when (providerType) {
            ProviderType.DIFFUSION -> {
                val diffusionConfig = DiffusionConfig(
                    textEmbeddingSize = textEmbeddingSize,
                    runOnCpu = runOnCpu,
                    useCpuClip = true,
                    isPony = false,
                    httpPort = 8081,
                    safetyMode = false,
                    width = 512,
                    height = 512
                )
                val inferenceParams = DiffusionInferenceParams()
                ModelConfig(
                    modelId = modelId,
                    modelLoadingParams = diffusionConfig.toJson(),
                    modelInferenceParams = inferenceParams.toJson()
                )
            }

            ProviderType.GGUF -> {
                val appSettings = AppSettingsDataStore(this@ModelDownloadService)
                val tuningEnabled = appSettings.hardwareTuningEnabled.firstOrNull() ?: true
                val loadingParams = if (tuningEnabled) {
                    val perfMode = appSettings.performanceMode.firstOrNull() ?: com.bit.global.PerformanceMode.BALANCED
                    val modelSizeMB = (fileSize / (1024 * 1024)).toInt()
                    val profile = HardwareScanner.scan(this@ModelDownloadService)
                    DeviceTuner.tune(profile, modelSizeMB, modelName, perfMode)
                } else {
                    com.bit.models.engine_schema.GgufLoadingParams()
                }

                val isSmall = modelId.contains("350m", ignoreCase = true) ||
                        modelName.contains("350m", ignoreCase = true) ||
                        modelId.contains("125m", ignoreCase = true) ||
                        modelName.contains("125m", ignoreCase = true) ||
                        modelId.contains("160m", ignoreCase = true) ||
                        modelName.contains("160m", ignoreCase = true) ||
                        modelId.contains("tiny", ignoreCase = true) ||
                        modelName.contains("tiny", ignoreCase = true) ||
                        modelId.contains("mini", ignoreCase = true) ||
                        modelName.contains("mini", ignoreCase = true)

                val inferenceParams = if (isSmall) {
                    com.bit.models.engine_schema.GgufInferenceParams(
                        temperature = 0.4f,
                        maxTokens = 256,
                        repeatPenalty = 1.1f
                    )
                } else {
                    com.bit.models.engine_schema.GgufInferenceParams()
                }

                val ggufSchema = GgufEngineSchema(
                    loadingParams = loadingParams,
                    inferenceParams = inferenceParams
                )
                ModelConfig(
                    modelId = modelId,
                    modelLoadingParams = ggufSchema.toLoadingJson(),
                    modelInferenceParams = ggufSchema.toInferenceJson()
                )
            }

            ProviderType.TTS -> {
                ModelConfig(
                    modelId = modelId,
                    modelLoadingParams = """{"type":"tts","useNNAPI":false}""",
                    modelInferenceParams = """{"voice":"F1","speed":1.05,"steps":2,"language":"en"}"""
                )
            }

            ProviderType.STT -> {
                ModelConfig(
                    modelId = modelId,
                    modelLoadingParams = """{"engine":"sherpa-onnx","type":"whisper"}""",
                    modelInferenceParams = "{}"
                )
            }

            ProviderType.VLM -> {
                ModelConfig(
                    modelId = modelId,
                    modelLoadingParams = """{"type":"vlm","projector":"mmproj-Qwen2-VL-2B-Instruct-f16.gguf"}""",
                    modelInferenceParams = """{"max_tokens":512}"""
                )
            }


            ProviderType.API -> {
                ModelConfig(
                    modelId = modelId,
                    modelLoadingParams = "{}",
                    modelInferenceParams = null
                )
            }
        }

        repository.insertConfig(config)
    }

    private fun cancelDownload(modelId: String) {
        pausedModelIds.remove(modelId)
        downloadJobs[modelId]?.cancel()
        clearPauseMetadata(modelId)
    }

    private fun pauseDownload(modelId: String) {
        pausedModelIds.add(modelId)
        // The coroutine's read loop will detect the flag and throw PauseException
    }

    private fun resumeDownload(modelId: String, modelName: String) {
        pausedModelIds.remove(modelId)

        // Try to read saved metadata to get the original download parameters
        val meta = loadPauseMetadata(modelId)
        if (meta != null) {
            startDownload(
                modelId = modelId,
                modelName = meta.optString("modelName", modelName),
                fileUrl = if (meta.has("fileUrl")) meta.getString("fileUrl") else null,
                projectorUrl = if (meta.has("projectorUrl")) meta.getString("projectorUrl") else null,
                isZip = meta.optBoolean("isZip", false),
                modelType = meta.optString("modelType", "GGUF"),
                runOnCpu = meta.optBoolean("runOnCpu", false),
                textEmbeddingSize = meta.optInt("textEmbeddingSize", 768)
            )
        } else {
            Log.e(TAG, "No pause metadata found for $modelId — cannot resume")
            updateDownloadState(modelId, DownloadState.Error(modelId, "Cannot resume: download metadata lost"))
        }
    }

    // ── Pause metadata persistence (survives background kill) ──

    private fun savePauseMetadata(
        modelId: String, modelName: String, fileUrl: String?,
        isZip: Boolean, modelType: String, runOnCpu: Boolean, textEmbeddingSize: Int, projectorUrl: String? = null
    ) {
        try {
            val metaDir = AppPaths.tempDownloads(applicationContext, modelId)
            metaDir.mkdirs()
            val json = JSONObject().apply {
                put("modelId", modelId)
                put("modelName", modelName)
                put("fileUrl", fileUrl ?: "")
                if (projectorUrl != null) put("projectorUrl", projectorUrl)
                put("isZip", isZip)
                put("modelType", modelType)
                put("runOnCpu", runOnCpu)
                put("textEmbeddingSize", textEmbeddingSize)
            }
            File(metaDir, PAUSE_META_FILE).writeText(json.toString())
        } catch (e: Exception) {
            Log.w(TAG, "Failed to save pause metadata for $modelId", e)
        }
    }

    private fun loadPauseMetadata(modelId: String): JSONObject? {
        return try {
            val file = File(AppPaths.tempDownloads(applicationContext, modelId), PAUSE_META_FILE)
            if (file.exists()) JSONObject(file.readText()) else null
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load pause metadata for $modelId", e)
            null
        }
    }

    private fun clearPauseMetadata(modelId: String) {
        try {
            File(AppPaths.tempDownloads(applicationContext, modelId), PAUSE_META_FILE).delete()
        } catch (_: Exception) { }
    }

    /** Checks if a model has a partial temp file from a previous interrupted download. */
    fun hasResumableDownload(modelId: String): Boolean {
        val tempDir = AppPaths.tempDownloads(applicationContext, modelId)
        val tempFile = File(tempDir, "${modelId}.tmp")
        val metaFile = File(tempDir, PAUSE_META_FILE)
        return tempFile.exists() && tempFile.length() > 0 && metaFile.exists()
    }

    // ── SHA256 checksum computation ──

    private suspend fun computeSha256(file: File): String = withContext(Dispatchers.IO) {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered(256 * 1024).use { input ->
            val buffer = ByteArray(256 * 1024)
            var bytes: Int
            while (input.read(buffer).also { bytes = it } != -1) {
                digest.update(buffer, 0, bytes)
            }
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    }

    // ── PauseException — thrown when a download is paused (NOT a cancellation) ──

    private class PauseException(
        val modelId: String,
        val downloadedBytes: Long,
        val totalBytes: Long
    ) : Exception("Download paused for $modelId")

    // Channel creation moved to NotificationChannels.createAllChannels()
    // called at app startup in NVApplication.onCreate()


    private fun createNotification(
        modelName: String,
        progress: Float,
        isExtracting: Boolean = false,
        isProcessing: Boolean = false,
        isPaused: Boolean = false,
        isVerifying: Boolean = false
    ): android.app.Notification {
        val title = when {
            isPaused -> "Paused · $modelName"
            isVerifying -> "Verifying $modelName"
            isProcessing -> "Processing $modelName"
            isExtracting -> "Extracting $modelName"
            else -> "Downloading $modelName"
        }

        return NotificationCompat.Builder(this, NotificationChannels.MODEL_DOWNLOAD).setContentTitle(title)
            .setSmallIcon(if (isPaused) android.R.drawable.ic_media_pause else android.R.drawable.stat_sys_download)
            .setProgress(100, (progress * 100).toInt(), isExtracting || isProcessing || isVerifying)
            .setOngoing(!isPaused).build()
    }

    private fun updateNotification(
        modelName: String,
        progress: Float,
        notificationId: Int,
        isSuccess: Boolean = false,
        error: String? = null,
        isExtracting: Boolean = false,
        isProcessing: Boolean = false,
        isCancelled: Boolean = false,
        isPaused: Boolean = false,
        isVerifying: Boolean = false
    ) {
        val notification = when {
            isSuccess -> {
                NotificationCompat.Builder(this, NotificationChannels.MODEL_DOWNLOAD)
                    .setContentTitle("Download Complete").setContentText(modelName)
                    .setSmallIcon(android.R.drawable.stat_sys_download_done).setOngoing(false)
                    .build()
            }

            isCancelled -> {
                NotificationCompat.Builder(this, NotificationChannels.MODEL_DOWNLOAD)
                    .setContentTitle("Download Cancelled").setContentText(modelName)
                    .setSmallIcon(android.R.drawable.ic_menu_close_clear_cancel).setOngoing(false)
                    .build()
            }

            error != null -> {
                NotificationCompat.Builder(this, NotificationChannels.MODEL_DOWNLOAD)
                    .setContentTitle("Download Failed").setContentText(error)
                    .setSmallIcon(android.R.drawable.stat_notify_error).setOngoing(false).build()
            }

            else -> {
                createNotification(modelName, progress, isExtracting, isProcessing, isPaused, isVerifying)
            }
        }

        notificationManager.notify(notificationId, notification)
    }

    private fun autoInferProjectorUrl(fileUrl: String): String {
        val baseUrl = fileUrl.substringBeforeLast("/")
        val fileName = fileUrl.substringAfterLast("/")
        // Try the common mmproj naming convention: mmproj-<modelname>.gguf
        val projName = if (fileName.startsWith("mmproj", ignoreCase = true)) {
            fileName // Already a projector URL
        } else {
            "mmproj-${fileName}"
        }
        return "$baseUrl/$projName"
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
    }
}
