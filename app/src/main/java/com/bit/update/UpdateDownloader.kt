package com.bit.update

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Environment
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
     * found / hasn't started reporting sizes yet. Call this from a polling
     * loop (e.g. every 300ms) while a download is in flight if you want a
     * progress bar rather than relying purely on the system notification.
     */
    fun getDownloadProgress(downloadId: Long): Float? {
        val query = DownloadManager.Query().setFilterById(downloadId)
        downloadManager.query(query).use { cursor ->
            if (!cursor.moveToFirst()) return null
            val bytesDownloadedIdx = cursor.getColumnIndex(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
            val bytesTotalIdx = cursor.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
            val downloaded = cursor.getLong(bytesDownloadedIdx)
            val total = cursor.getLong(bytesTotalIdx)
            if (total <= 0) return null
            return downloaded.toFloat() / total.toFloat()
        }
    }

    /**
     * Registers a one-shot receiver that automatically launches the install
     * intent once DownloadManager reports the given download as complete.
     * Remember to unregister via the returned BroadcastReceiver if the
     * hosting component is destroyed before the download finishes.
     */
    fun registerInstallOnComplete(downloadId: Long, onComplete: () -> Unit = {}): BroadcastReceiver {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                val completedId = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
                if (completedId == downloadId) {
                    installDownloadedApk()
                    onComplete()
                    context.unregisterReceiver(this)
                }
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
     * Launches the system package installer for the previously-downloaded
     * APK. Requires REQUEST_INSTALL_PACKAGES permission + a FileProvider
     * declared in the manifest (see AndroidManifest snippet in README).
     */
    fun installDownloadedApk() {
        val file = File(
            context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS),
            apkFileName
        )
        if (!file.exists()) return

        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )

        val installIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
        }
        context.startActivity(installIntent)
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
