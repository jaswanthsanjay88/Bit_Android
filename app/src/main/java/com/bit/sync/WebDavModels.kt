package com.bit.sync

import kotlinx.serialization.Serializable

@Serializable
data class WebDavConfig(
    val url: String = "",
    val username: String = "",
    val password: String = "",
    val path: String = "BIT_Backups",
    val autoBackupEnabled: Boolean = false,
    val autoBackupIntervalHours: Int = 24
) {
    val isConfigured: Boolean
        get() = url.isNotBlank() && (url.startsWith("http://") || url.startsWith("https://"))

    /**
     * Constructs the full normalized WebDAV collection URL with trailing slash.
     */
    fun getFullCollectionUrl(): String {
        val base = url.trim().trimEnd('/')
        val cleanPath = path.trim().trim('/')
        return if (cleanPath.isBlank()) "$base/" else "$base/$cleanPath/"
    }
}

data class WebDavBackupItem(
    val href: String,
    val displayName: String,
    val sizeBytes: Long,
    val lastModifiedEpochMs: Long
)

sealed class WebDavSyncState {
    data object Idle : WebDavSyncState()
    data class Loading(val message: String) : WebDavSyncState()
    data class Success(val message: String) : WebDavSyncState()
    data class Error(val errorMessage: String) : WebDavSyncState()
}
