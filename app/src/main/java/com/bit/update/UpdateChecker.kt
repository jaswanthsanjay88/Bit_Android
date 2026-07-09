package com.bit.update

import android.content.Context
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

    /**
     * Fetches the latest GitHub release and compares it against the
     * currently-installed versionName. Never throws — always returns a
     * result you can branch on. Safe to call on every app start.
     */
    suspend fun checkForUpdate(): UpdateCheckResult {
        return try {
            val currentVersion = getCurrentVersionName()
                ?: return UpdateCheckResult.Error("Could not read current app version")

            val release = api.getLatestRelease(GithubApiFactory.OWNER, GithubApiFactory.REPO)

            if (release.draft || release.prerelease) {
                return UpdateCheckResult.UpToDate // skip drafts/prereleases by default
            }

            val apkAsset = release.assets.firstOrNull { it.name.endsWith(".apk", ignoreCase = true) }
                ?: return UpdateCheckResult.Error("Latest release has no APK asset")

            if (isNewerVersion(currentVersion, release.tagName)) {
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
