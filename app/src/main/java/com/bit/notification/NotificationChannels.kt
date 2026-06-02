package com.bit.notification

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.util.Log

/**
 * Centralized notification channel definitions for the app.
 *
 * All channels are created once at app startup in [createAllChannels].
 * Individual services no longer need their own channel creation.
 *
 * Channel IDs are stable strings — do NOT rename them after release,
 * or users lose their per-channel settings.
 */
object NotificationChannels {

    private const val TAG = "NotificationChannels"

    // ── Channel IDs ──────────────────────────────────────────────
    /** LLM / model inference foreground service */
    const val LLM_SERVICE = "llm_service"

    /** Model download progress  */
    const val MODEL_DOWNLOAD = "model_download_channel"

    /** Embedding model download (WorkManager) */
    const val EMBEDDING_DOWNLOAD = "embedding_download"

    /** Voice AI foreground capture (if needed in future) */
    const val VOICE_AI = "voice_ai_service"

    /** General one-shot notifications (completion, errors) */
    const val GENERAL = "general"

    // ── Public API ───────────────────────────────────────────────

    /**
     * Create every notification channel the app uses.
     * Safe to call multiple times — the system ignores duplicates,
     * and never resets user-modified importance / vibration settings.
     */
    fun createAllChannels(context: Context) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE)
                as? NotificationManager ?: return

        val channels = listOf(
            NotificationChannel(
                LLM_SERVICE,
                "AI Model Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Persistent notification while the on-device AI engine is running"
                setShowBadge(false)
            },

            NotificationChannel(
                MODEL_DOWNLOAD,
                "Model Downloads",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows download and extraction progress for AI models"
                setShowBadge(false)
            },

            NotificationChannel(
                EMBEDDING_DOWNLOAD,
                "Embedding Model Download",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Progress of embedding model downloads"
                setShowBadge(false)
            },

            NotificationChannel(
                VOICE_AI,
                "Voice AI",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Active while voice assistant is recording"
                setShowBadge(false)
            },

            NotificationChannel(
                GENERAL,
                "General",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Completion alerts, errors, and other app notifications"
            }
        )

        channels.forEach { channel ->
            manager.createNotificationChannel(channel)
        }

        Log.d(TAG, "Created ${channels.size} notification channels")
    }
}
