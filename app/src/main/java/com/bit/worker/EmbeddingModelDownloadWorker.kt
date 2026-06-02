package com.bit.worker

import com.bit.notification.NotificationChannels
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.bit.R
import com.bit.engine.EmbeddingEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedOutputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

class EmbeddingModelDownloadWorker(
    private val context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        private const val NOTIFICATION_ID = 1001
        const val TAG = "EmbeddingModelDownload"

        private const val MODEL_URL =
            "https://huggingface.co/spaces/Void2377/neurov/resolve/main/all-MiniLM-L6-v2-Q5_K_M.gguf?download=true"

        /** Follow HTTP redirects and return the final HttpURLConnection. */
        private fun openFinalConnection(startUrl: String, context: Context): HttpURLConnection {
            var url = startUrl
            var redirects = 0
            while (true) {
                val conn = URL(url).openConnection() as HttpURLConnection
                conn.instanceFollowRedirects = false
                conn.connectTimeout = 30_000
                conn.readTimeout = 60_000
                conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Android) AppleWebKit/537.36")
                conn.setRequestProperty("Accept-Encoding", "identity")
                
                // Add Hugging Face Authorization if url is Hugging Face
                if (url.contains("huggingface.co")) {
                    val tokenManager = com.bit.data.HuggingFaceTokenManager(context)
                    tokenManager.getBearerHeader()?.let { bearer ->
                        conn.setRequestProperty("Authorization", bearer)
                    }
                }

                conn.connect()

                val code = conn.responseCode
                if (code in 300..399) {
                    val location = conn.getHeaderField("Location")
                        ?: throw IllegalStateException("Redirect with no Location header")
                    conn.disconnect()
                    url = location
                    if (++redirects > 10) throw IllegalStateException("Too many redirects")
                } else {
                    return conn
                }
            }
        }
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            // Channels are created at app startup by NVApplication
            setForeground(createForegroundInfo(0, indeterminate = true))

            val modelPath = EmbeddingEngine.getModelPath(context)
            Log.d(TAG, "Runtime package: ${context.packageName}")
            Log.d(TAG, "Runtime filesDir: ${context.filesDir.absolutePath}")
            Log.d(TAG, "Embedding model target path: ${modelPath.absolutePath}")

            // Already downloaded and valid?
            if (EmbeddingEngine.isModelFileValid(modelPath)) {
                Log.d(TAG, "Embedding model already exists at ${modelPath.absolutePath} (${modelPath.length() / 1024 / 1024}MB)")
                showCompletionNotification(true)
                return@withContext Result.success()
            }

            // Clean up stale / invalid existing file
            if (modelPath.exists()) {
                val reason = EmbeddingEngine.getModelValidationError(modelPath)
                Log.w(TAG, "Existing model is invalid: $reason. Deleting before download.")
                try {
                    modelPath.delete()
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to delete invalid model: ${e.message}")
                }
            }

            // Ensure parent directory exists with proper permissions
            val parentDir = modelPath.parentFile
            if (parentDir == null || (!parentDir.exists() && !parentDir.mkdirs())) {
                Log.e(TAG, "Failed to create parent directory: ${parentDir?.absolutePath}")
                showCompletionNotification(false, "Failed to create directory")
                return@withContext Result.failure()
            }

            Log.d(TAG, "Downloading embedding model to: ${modelPath.absolutePath}")
            Log.d(TAG, "Parent directory writable: ${parentDir.canWrite()}")

            // Open connection, following redirects manually so we always land on the
            // final CDN URL and can read Content-Length reliably.
            val connection = openFinalConnection(MODEL_URL, context)
            val contentLength = connection.contentLengthLong   // -1 if unknown
            if (contentLength > 0) {
                Log.d(TAG, "Download size reported: ${contentLength / 1024 / 1024}MB")
            } else {
                Log.w(TAG, "Server did not send Content-Length — downloading without progress tracking")
            }

            // Write directly to final location with explicit flushing
            connection.inputStream.use { inputStream ->
                // Use FileOutputStream for explicit control + buffer for performance
                val fileOutputStream = FileOutputStream(modelPath)
                val bufferedOutput = BufferedOutputStream(fileOutputStream, 65_536)

                bufferedOutput.use { outputStream ->
                    val buffer = ByteArray(65_536) // 64KiB buffer for faster transfer
                    var totalBytesRead = 0L
                    var bytesRead: Int
                    var lastProgress = -1
                    var lastUpdateTime = System.currentTimeMillis()

                    while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                        if (isStopped) {
                            Log.w(TAG, "Download cancelled by user")
                            inputStream.close()
                            return@withContext Result.failure()
                        }

                        outputStream.write(buffer, 0, bytesRead)
                        totalBytesRead += bytesRead

                        val currentTime = System.currentTimeMillis()
                        if (contentLength > 0) {
                            val progress = (totalBytesRead.toFloat() / contentLength.toFloat() * 100).toInt()
                            // Update notification every 5% or every 3 seconds
                            if (progress >= lastProgress + 5 || currentTime - lastUpdateTime >= 3000) {
                                setForeground(createForegroundInfo(progress, indeterminate = false))
                                lastProgress = progress
                                lastUpdateTime = currentTime
                                Log.d(TAG, "Download progress: $progress% (${totalBytesRead / 1024 / 1024}MB / ${contentLength / 1024 / 1024}MB)")
                            }
                        } else {
                            // No content-length — show indeterminate every 3 seconds
                            if (currentTime - lastUpdateTime >= 3000) {
                                setForeground(createForegroundInfo(0, indeterminate = true))
                                lastUpdateTime = currentTime
                                Log.d(TAG, "Downloaded so far: ${totalBytesRead / 1024 / 1024}MB")
                            }
                        }
                    }

                    // Explicit flush and sync to disk
                    outputStream.flush()
                    fileOutputStream.fd.sync()
                    Log.d(TAG, "File flushed and synced to disk")
                }
            }

            connection.disconnect()

            // Verify file was actually written to disk
            if (!modelPath.exists()) {
                Log.e(TAG, "Downloaded file does not exist after close!")
                showCompletionNotification(false, "File write failed - not found on disk")
                return@withContext Result.failure()
            }

            val fileSize = modelPath.length()
            if (!modelPath.canRead()) {
                Log.e(TAG, "Downloaded file exists but is not readable")
                modelPath.delete()
                showCompletionNotification(false, "File write failed - not readable")
                return@withContext Result.failure()
            }

            // Verify
            val finalSize = modelPath.length()
            Log.d(TAG, "Download complete. File size: ${finalSize / 1024 / 1024}MB")

            if (finalSize < 1_000_000L) {
                Log.e(TAG, "Downloaded file is too small ($finalSize bytes), likely corrupted")
                modelPath.delete()
                showCompletionNotification(false, "Download incomplete - file too small")
                return@withContext Result.failure()
            }

            if (!EmbeddingEngine.isModelFileValid(modelPath)) {
                val reason = EmbeddingEngine.getModelValidationError(modelPath)
                Log.e(TAG, "Downloaded model failed GGUF validation: $reason")
                modelPath.delete()
                showCompletionNotification(false, "Downloaded file is invalid (not GGUF)")
                return@withContext Result.failure()
            }

            showCompletionNotification(true)
            Log.d(TAG, "Embedding model download successful")
            Result.success()

        } catch (e: Exception) {
            Log.e(TAG, "Embedding model download failed: ${e.message}", e)
            showCompletionNotification(false, e.message ?: "Unknown error")
            Result.failure()
        }
    }

    // Channel creation moved to NotificationChannels.createAllChannels()


    private fun createForegroundInfo(progress: Int, indeterminate: Boolean): ForegroundInfo {
        val notification = NotificationCompat.Builder(context, NotificationChannels.EMBEDDING_DOWNLOAD)
            .setContentTitle("Downloading Embedding Model")
            .setContentText(
                when {
                    indeterminate -> "Downloading..."
                    progress > 0  -> "$progress% complete"
                    else          -> "Starting download..."
                }
            )
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setProgress(100, progress, indeterminate)
            .setOngoing(true)
            .build()

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    private fun showCompletionNotification(success: Boolean, error: String? = null) {
        val notificationManager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val notification = NotificationCompat.Builder(context, NotificationChannels.EMBEDDING_DOWNLOAD)
            .setContentTitle(if (success) "Download Complete" else "Download Failed")
            .setContentText(
                if (success) "Embedding model ready for RAG features"
                else "Error: ${error ?: "Unknown"}"
            )
            .setSmallIcon(
                if (success) android.R.drawable.stat_sys_download_done
                else android.R.drawable.stat_notify_error
            )
            .setAutoCancel(true)
            .build()

        notificationManager.notify(NOTIFICATION_ID + 1, notification)
    }
}