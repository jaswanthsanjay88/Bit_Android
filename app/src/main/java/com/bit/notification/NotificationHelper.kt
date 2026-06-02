package com.bit.notification

import android.Manifest
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.bit.R

/**
 * Centralised helper for building and posting notifications.
 *
 * Rules followed:
 *  • Always checks POST_NOTIFICATIONS on API 33+
 *  • Uses FLAG_IMMUTABLE for every PendingIntent
 *  • Uses NotificationCompat for backward compatibility
 *  • Never creates channels (that's [NotificationChannels]'s job)
 */
object NotificationHelper {

    private const val TAG = "NotificationHelper"

    // ── Permission Check ────────────────────────────────────────

    /** Returns true if the app may post notifications on this device. */
    fun hasPermission(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
                    PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    // ── Simple Notification ───────────────────────────────────────

    fun showSimple(
        context: Context,
        title: String,
        message: String,
        channelId: String = NotificationChannels.GENERAL,
        notificationId: Int = generateId()
    ) {
        if (!hasPermission(context)) return

        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.user)
            .setContentTitle(title)
            .setContentText(message)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .build()

        safeNotify(context, notificationId, notification)
    }

    // ── Progress Notification (determinate) ────────────────────

    fun showProgress(
        context: Context,
        title: String,
        contentText: String,
        progress: Int,
        maxProgress: Int = 100,
        notificationId: Int,
        channelId: String = NotificationChannels.MODEL_DOWNLOAD
    ): Notification {
        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setContentText(contentText)
            .setProgress(maxProgress, progress, false)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        safeNotify(context, notificationId, notification)
        return notification
    }

    // ── Progress Notification (indeterminate) ──────────────────

    fun showIndeterminateProgress(
        context: Context,
        title: String,
        contentText: String,
        notificationId: Int,
        channelId: String = NotificationChannels.MODEL_DOWNLOAD
    ): Notification {
        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setContentText(contentText)
            .setProgress(0, 0, true)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        safeNotify(context, notificationId, notification)
        return notification
    }

    // ── Completion / Error Notifications ────────────────────────

    fun showDownloadComplete(
        context: Context,
        modelName: String,
        notificationId: Int,
        channelId: String = NotificationChannels.MODEL_DOWNLOAD
    ) {
        if (!hasPermission(context)) return

        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("Download Complete")
            .setContentText(modelName)
            .setOngoing(false)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        safeNotify(context, notificationId, notification)
    }

    fun showDownloadCancelled(
        context: Context,
        modelName: String,
        notificationId: Int,
        channelId: String = NotificationChannels.MODEL_DOWNLOAD
    ) {
        if (!hasPermission(context)) return

        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(android.R.drawable.ic_menu_close_clear_cancel)
            .setContentTitle("Download Cancelled")
            .setContentText(modelName)
            .setOngoing(false)
            .setAutoCancel(true)
            .build()

        safeNotify(context, notificationId, notification)
    }

    fun showError(
        context: Context,
        title: String,
        errorMessage: String,
        notificationId: Int,
        channelId: String = NotificationChannels.GENERAL
    ) {
        if (!hasPermission(context)) return

        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle(title)
            .setContentText(errorMessage)
            .setOngoing(false)
            .setAutoCancel(true)
            .build()

        safeNotify(context, notificationId, notification)
    }

    // ── Foreground Service Notification ─────────────────────────

    /**
     * Builds a low-priority ongoing notification suitable for foreground services.
     * Does NOT post it — the caller must pass it to startForeground().
     */
    fun buildForegroundServiceNotification(
        context: Context,
        title: String,
        contentText: String,
        channelId: String
    ): Notification {
        return NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.user)
            .setContentTitle(title)
            .setContentText(contentText)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    // ── Dismiss ─────────────────────────────────────────────────

    fun dismiss(context: Context, notificationId: Int) {
        NotificationManagerCompat.from(context).cancel(notificationId)
    }

    fun dismissAll(context: Context) {
        NotificationManagerCompat.from(context).cancelAll()
    }

    // ── Internals ───────────────────────────────────────────────

    private fun safeNotify(context: Context, id: Int, notification: Notification) {
        try {
            if (!hasPermission(context)) return
            NotificationManagerCompat.from(context).notify(id, notification)
        } catch (e: SecurityException) {
            Log.w(TAG, "Notification permission revoked: ${e.message}")
        }
    }

    /** Timestamp-based ID — unique enough for transient notifications. */
    fun generateId(): Int = System.currentTimeMillis().toInt()
}
