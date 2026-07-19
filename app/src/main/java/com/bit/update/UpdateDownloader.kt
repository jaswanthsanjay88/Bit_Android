package com.bit.update

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Environment
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import java.io.File

/**
 * Handles downloading the APK asset via the system DownloadManager (shows a
 * real notification with progress, survives app backgrounding) and launching
 * the install intent once the download completes.
 */
class UpdateDownloader(private val context: Context) {

    private val downloadManager: DownloadManager
        get() = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager

    private val apkFileName = "bit-update.apk"

    /**
     * Starts the download. Returns the DownloadManager request ID, which you
     * can use to query progress via [getDownloadProgress] or just wait for
     * the completion broadcast via [registerInstallOnComplete].
     */
    fun startDownload(update: UpdateInfo): Long {
        // Delete any existing update file to avoid partial/corrupt overwrite issues
        try {
            val file = File(
                context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS),
                apkFileName
            )
            if (file.exists()) file.delete()
        } catch (e: Exception) {
            Log.w("UpdateDownloader", "Failed to clean old update file: ${e.message}")
        }

        val request = DownloadManager.Request(Uri.parse(update.downloadUrl))
            .setTitle("BIT ${update.version}")
            .setDescription("Downloading update…")
            .setDestinationInExternalFilesDir(
                context,
                Environment.DIRECTORY_DOWNLOADS,
                apkFileName
            )
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(true)

        return downloadManager.enqueue(request)
    }

    /**
     * Returns progress as a 0f-1f fraction, or null if the download isn't
     * found / hasn't started reporting sizes yet.
     */
    fun getDownloadProgress(downloadId: Long): Float? {
        if (downloadId == -1L) return null
        val query = DownloadManager.Query().setFilterById(downloadId)
        return try {
            downloadManager.query(query)?.use { cursor ->
                if (!cursor.moveToFirst()) return null
                val bytesDownloadedIdx = cursor.getColumnIndex(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
                val bytesTotalIdx = cursor.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
                if (bytesDownloadedIdx < 0 || bytesTotalIdx < 0) return null
                val downloaded = cursor.getLong(bytesDownloadedIdx)
                val total = cursor.getLong(bytesTotalIdx)
                if (total <= 0) return null
                (downloaded.toFloat() / total.toFloat()).coerceIn(0f, 1f)
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Registers a receiver that automatically verifies STATUS_SUCCESSFUL and launches the install
     * intent once DownloadManager confirms the download completed and flushed to disk.
     */
    fun registerInstallOnComplete(downloadId: Long, onComplete: () -> Unit = {}): BroadcastReceiver {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                val completedId = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
                if (completedId != downloadId) return

                val query = DownloadManager.Query().setFilterById(completedId)
                try {
                    downloadManager.query(query)?.use { cursor ->
                        if (cursor.moveToFirst()) {
                            val statusIdx = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS)
                            val status = if (statusIdx >= 0) cursor.getInt(statusIdx) else -1

                            if (status == DownloadManager.STATUS_SUCCESSFUL) {
                                Log.i("UpdateDownloader", "Download successful for ID $completedId, installing...")
                                installDownloadedApk(completedId)
                                onComplete()
                            } else {
                                val reasonIdx = cursor.getColumnIndex(DownloadManager.COLUMN_REASON)
                                val reason = if (reasonIdx >= 0) cursor.getInt(reasonIdx) else -1
                                Log.e("UpdateDownloader", "Download failed, status=$status, reason=$reason")
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e("UpdateDownloader", "Error handling download completion: ${e.message}", e)
                }

                try {
                    context.unregisterReceiver(this)
                } catch (_: Exception) {}
            }
        }
        ContextCompat.registerReceiver(
            context,
            receiver,
            IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
            ContextCompat.RECEIVER_EXPORTED
        )
        return receiver
    }

    /**
     * Launches the system package installer for the downloaded APK using the verified content URI.
     */
    fun installDownloadedApk(downloadId: Long? = null) {
        var uri: Uri? = null

        if (downloadId != null && downloadId != -1L) {
            try {
                val query = DownloadManager.Query().setFilterById(downloadId)
                downloadManager.query(query)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val statusIdx = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS)
                        if (statusIdx >= 0 && cursor.getInt(statusIdx) == DownloadManager.STATUS_SUCCESSFUL) {
                            uri = downloadManager.getUriForDownloadedFile(downloadId)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w("UpdateDownloader", "getUriForDownloadedFile failed: ${e.message}")
            }
        }

        if (uri == null) {
            val file = File(
                context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS),
                apkFileName
            )
            if (!file.exists() || file.length() == 0L) {
                Log.e("UpdateDownloader", "APK file does not exist or is empty")
                return
            }
            uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )
        }

        val installIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
        }
        try {
            context.startActivity(installIntent)
        } catch (e: Exception) {
            Log.e("UpdateDownloader", "Failed to start package installer: ${e.message}", e)
        }
    }

    /** Whether the app currently has permission to install unknown APKs. */
    fun canInstallUnknownApps(): Boolean {
        return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            context.packageManager.canRequestPackageInstalls()
        } else {
            true // pre-O this is a manifest permission, not a runtime one
        }
    }

    /** Opens system settings so the user can grant the "install unknown apps" permission. */
    fun requestInstallPermission() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            val intent = Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                data = Uri.parse("package:${context.packageName}")
            }
            context.startActivity(intent)
        }
    }
}
