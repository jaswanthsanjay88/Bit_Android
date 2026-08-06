package com.bit.update

import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager

data class UpdateInfo(
    val version: String,          // e.g. "v1.4.2"
    val currentVersion: String,   // e.g. "1.4.1"
    val title: String,
    val changelog: String,        // raw markdown from release body
    val downloadUrl: String,
    val sizeBytes: Long,
    val releasePageUrl: String
)

sealed class UpdateCheckResult {
    data class UpdateAvailable(val info: UpdateInfo) : UpdateCheckResult()
    object UpToDate : UpdateCheckResult()
    data class Error(val message: String) : UpdateCheckResult()
}

class UpdateChecker(
    private val context: Context,
    private val api: GithubApi = GithubApiFactory.create()
) {

    companion object {
        private const val PREFS_NAME = "bit_update_prefs"
        private const val KEY_SKIPPED_VERSION = "skipped_version"
        private const val KEY_LAST_INSTALLED_VERSION = "last_installed_version"
    }

    private val prefs: SharedPreferences
        get() = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * Fetches the latest GitHub release and compares it against the
     * currently-installed versionName. Never throws — always returns a
     * result you can branch on. Safe to call on every app start.
     *
     * If the user previously tapped "Later" for a given version, we
     * suppress the prompt for that version until a newer release lands.
     * If the installed version changed since the last check (i.e. user
     * actually updated), we clear the skipped-version marker.
     */
    suspend fun checkForUpdate(): UpdateCheckResult {
        return try {
            val currentVersion = getCurrentVersionName()
                ?: return UpdateCheckResult.Error("Could not read current app version")

            // If the installed version changed since we last ran, clear
            // any stale "skipped" marker — the user may have updated.
            val lastInstalled = prefs.getString(KEY_LAST_INSTALLED_VERSION, null)
            if (lastInstalled != null && lastInstalled != currentVersion) {
                prefs.edit()
                    .remove(KEY_SKIPPED_VERSION)
                    .putString(KEY_LAST_INSTALLED_VERSION, currentVersion)
                    .apply()
            } else if (lastInstalled == null) {
                prefs.edit()
                    .putString(KEY_LAST_INSTALLED_VERSION, currentVersion)
                    .apply()
            }

            val releases = api.getReleases(GithubApiFactory.OWNER, GithubApiFactory.REPO)
            
            // Find the first release that has an APK and is not a draft. We allow pre-releases.
            val release = releases.firstOrNull { r -> 
                !r.draft && r.assets.any { it.name.endsWith(".apk", ignoreCase = true) }
            } ?: return UpdateCheckResult.Error("No valid releases found")

            val apkAsset = release.assets.firstOrNull { it.name.endsWith(".apk", ignoreCase = true) }
                ?: return UpdateCheckResult.Error("Latest release has no APK asset")

            if (isNewerVersion(currentVersion, release.tagName)) {
                // Check if user already skipped this exact version
                val skippedVersion = prefs.getString(KEY_SKIPPED_VERSION, null)
                val normalizedTag = release.tagName.removePrefix("v").removePrefix("V")
                if (skippedVersion == normalizedTag) {
                    return UpdateCheckResult.UpToDate
                }

                UpdateCheckResult.UpdateAvailable(
                    UpdateInfo(
                        version = release.tagName,
                        currentVersion = currentVersion,
                        title = release.name ?: release.tagName,
                        changelog = release.body?.trim().orEmpty(),
                        downloadUrl = apkAsset.browserDownloadUrl,
                        sizeBytes = apkAsset.size,
                        releasePageUrl = release.htmlUrl
                    )
                )
            } else {
                UpdateCheckResult.UpToDate
            }
        } catch (e: Exception) {
            // Network failures, rate limits, parsing errors — never crash app start over this
            UpdateCheckResult.Error(e.message ?: "Unknown error checking for updates")
        }
    }

    /**
     * Mark a version as skipped so the update prompt won't appear again
     * for that version. Called when the user taps "Later".
     */
    fun skipVersion(versionTag: String) {
        val normalized = versionTag.removePrefix("v").removePrefix("V")
        prefs.edit().putString(KEY_SKIPPED_VERSION, normalized).apply()
    }

    /**
     * Clears the skipped-version marker, forcing the next check to
     * surface the update prompt even if the user previously dismissed it.
     * Useful when the user explicitly checks for updates from Settings.
     */
    fun clearSkippedVersion() {
        prefs.edit().remove(KEY_SKIPPED_VERSION).apply()
    }

    private fun getCurrentVersionName(): String? = try {
        val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
        packageInfo.versionName
    } catch (e: PackageManager.NameNotFoundException) {
        null
    }

    /**
     * Semantic-ish version comparison. Strips a leading "v", splits on ".",
     * compares numerically segment by segment. Handles mismatched segment
     * counts (e.g. "1.4" vs "1.4.2") by treating missing segments as 0.
     */
    internal fun isNewerVersion(current: String, latest: String): Boolean {
        val cur = current.removePrefix("v").removePrefix("V")
            .split(".").map { it.filter(Char::isDigit).toIntOrNull() ?: 0 }
        val lat = latest.removePrefix("v").removePrefix("V")
            .split(".").map { it.filter(Char::isDigit).toIntOrNull() ?: 0 }

        val maxLen = maxOf(cur.size, lat.size)
        for (i in 0 until maxLen) {
            val c = cur.getOrElse(i) { 0 }
            val l = lat.getOrElse(i) { 0 }
            if (l > c) return true
            if (l < c) return false
        }
        return false // equal versions
    }
}
