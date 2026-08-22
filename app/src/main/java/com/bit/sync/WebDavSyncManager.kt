package com.bit.sync

import android.content.Context
import android.util.Log
import android.util.Xml
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.io.FileOutputStream
import java.io.StringReader
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WebDavSyncManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "WebDavSyncManager"
        private val XML_MEDIA_TYPE = "application/xml; charset=utf-8".toMediaType()
        private val OCTET_MEDIA_TYPE = "application/octet-stream".toMediaType()

        private val PROPFIND_XML_BODY = """
            <?xml version="1.0" encoding="utf-8" ?>
            <D:propfind xmlns:D="DAV:">
                <D:prop>
                    <D:displayname/>
                    <D:getcontentlength/>
                    <D:getlastmodified/>
                    <D:resourcetype/>
                </D:prop>
            </D:propfind>
        """.trimIndent()
    }

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    private fun getAuthHeader(config: WebDavConfig): String? {
        if (config.username.isNotBlank()) {
            return Credentials.basic(config.username, config.password)
        }
        return null
    }

    /**
     * Tests WebDAV connectivity against the specified URL and credentials.
     */
    suspend fun testConnection(config: WebDavConfig): Result<Boolean> = withContext(Dispatchers.IO) {
        if (!config.isConfigured) {
            return@withContext Result.failure(IllegalArgumentException("WebDAV server URL is required"))
        }

        try {
            val url = config.getFullCollectionUrl()
            val reqBuilder = Request.Builder()
                .url(url)
                .method("PROPFIND", PROPFIND_XML_BODY.toRequestBody(XML_MEDIA_TYPE))
                .header("Depth", "0")
                .header("User-Agent", "BIT-Android-WebDAV/1.0")

            getAuthHeader(config)?.let { reqBuilder.header("Authorization", it) }

            val response = client.newCall(reqBuilder.build()).execute()
            response.use {
                if (it.isSuccessful || it.code == 207 || it.code == 404) {
                    // If 404, the base server works but collection doesn't exist yet — try creating it
                    if (it.code == 404) {
                        ensureCollectionExists(config)
                    }
                    Result.success(true)
                } else if (it.code == 401) {
                    Result.failure(Exception("Authentication failed (HTTP 401): Check username and password"))
                } else {
                    Result.failure(Exception("WebDAV returned HTTP ${it.code}: ${it.message}"))
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "WebDAV test connection error", e)
            Result.failure(e)
        }
    }

    /**
     * Ensures that the target remote directory exists via WebDAV MKCOL.
     */
    suspend fun ensureCollectionExists(config: WebDavConfig): Result<Unit> = withContext(Dispatchers.IO) {
        val collectionUrl = config.getFullCollectionUrl()
        try {
            val reqBuilder = Request.Builder()
                .url(collectionUrl)
                .method("MKCOL", null)
                .header("User-Agent", "BIT-Android-WebDAV/1.0")

            getAuthHeader(config)?.let { reqBuilder.header("Authorization", it) }

            val response = client.newCall(reqBuilder.build()).execute()
            response.use {
                // 201 Created or 405 Method Not Allowed (already exists) are both considered success
                if (it.isSuccessful || it.code == 201 || it.code == 405) {
                    Result.success(Unit)
                } else {
                    Result.failure(Exception("Failed to create remote directory (HTTP ${it.code})"))
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Lists all backup archives present in the remote WebDAV collection.
     */
    suspend fun listBackups(config: WebDavConfig): Result<List<WebDavBackupItem>> = withContext(Dispatchers.IO) {
        if (!config.isConfigured) {
            return@withContext Result.failure(IllegalArgumentException("WebDAV is not configured"))
        }

        val collectionUrl = config.getFullCollectionUrl()
        try {
            val reqBuilder = Request.Builder()
                .url(collectionUrl)
                .method("PROPFIND", PROPFIND_XML_BODY.toRequestBody(XML_MEDIA_TYPE))
                .header("Depth", "1")
                .header("User-Agent", "BIT-Android-WebDAV/1.0")

            getAuthHeader(config)?.let { reqBuilder.header("Authorization", it) }

            val response = client.newCall(reqBuilder.build()).execute()
            response.use { resp ->
                if (!resp.isSuccessful && resp.code != 207) {
                    if (resp.code == 404) {
                        return@withContext Result.success(emptyList())
                    }
                    return@withContext Result.failure(Exception("HTTP ${resp.code}: ${resp.message}"))
                }

                val xmlBody = resp.body.string()
                val items = parseWebDavPropfindResponse(xmlBody, collectionUrl)
                Result.success(items.sortedByDescending { it.lastModifiedEpochMs })
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error listing WebDAV backups", e)
            Result.failure(e)
        }
    }

    /**
     * Uploads a local backup file to the remote WebDAV collection.
     */
    suspend fun uploadBackup(config: WebDavConfig, backupFile: File): Result<Unit> = withContext(Dispatchers.IO) {
        if (!config.isConfigured) {
            return@withContext Result.failure(IllegalArgumentException("WebDAV is not configured"))
        }
        if (!backupFile.exists() || backupFile.length() == 0L) {
            return@withContext Result.failure(IllegalArgumentException("Backup file is empty or missing"))
        }

        // Ensure parent remote folder exists first
        ensureCollectionExists(config)

        val targetUrl = config.getFullCollectionUrl() + backupFile.name
        try {
            val reqBuilder = Request.Builder()
                .url(targetUrl)
                .put(backupFile.asRequestBody(OCTET_MEDIA_TYPE))
                .header("User-Agent", "BIT-Android-WebDAV/1.0")

            getAuthHeader(config)?.let { reqBuilder.header("Authorization", it) }

            val response = client.newCall(reqBuilder.build()).execute()
            response.use {
                if (it.isSuccessful || it.code == 201 || it.code == 204) {
                    Log.i(TAG, "Successfully uploaded backup to WebDAV: ${backupFile.name}")
                    Result.success(Unit)
                } else {
                    Result.failure(Exception("Upload failed with HTTP ${it.code}: ${it.message}"))
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error uploading backup to WebDAV", e)
            Result.failure(e)
        }
    }

    /**
     * Downloads a remote WebDAV backup item to a local file.
     */
    suspend fun downloadBackup(config: WebDavConfig, item: WebDavBackupItem, targetFile: File): Result<File> = withContext(Dispatchers.IO) {
        val downloadUrl = resolveAbsoluteHref(config.url, item.href)

        try {
            val reqBuilder = Request.Builder()
                .url(downloadUrl)
                .get()
                .header("User-Agent", "BIT-Android-WebDAV/1.0")

            getAuthHeader(config)?.let { reqBuilder.header("Authorization", it) }

            val response = client.newCall(reqBuilder.build()).execute()
            response.use { resp ->
                if (!resp.isSuccessful) {
                    return@withContext Result.failure(Exception("Download failed with HTTP ${resp.code}: ${resp.message}"))
                }

                if (targetFile.exists()) targetFile.delete()
                targetFile.parentFile?.mkdirs()

                resp.body.byteStream().use { input ->
                    FileOutputStream(targetFile).use { output ->
                        input.copyTo(output)
                    }
                }

                Log.i(TAG, "Successfully downloaded remote backup: ${targetFile.name} (${targetFile.length()} bytes)")
                Result.success(targetFile)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error downloading backup from WebDAV", e)
            Result.failure(e)
        }
    }

    /**
     * Deletes a remote backup file on WebDAV.
     */
    suspend fun deleteBackup(config: WebDavConfig, item: WebDavBackupItem): Result<Unit> = withContext(Dispatchers.IO) {
        val targetUrl = resolveAbsoluteHref(config.url, item.href)

        try {
            val reqBuilder = Request.Builder()
                .url(targetUrl)
                .delete()
                .header("User-Agent", "BIT-Android-WebDAV/1.0")

            getAuthHeader(config)?.let { reqBuilder.header("Authorization", it) }

            val response = client.newCall(reqBuilder.build()).execute()
            response.use {
                if (it.isSuccessful || it.code == 204 || it.code == 404) {
                    Result.success(Unit)
                } else {
                    Result.failure(Exception("Delete failed with HTTP ${it.code}: ${it.message}"))
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error deleting remote backup", e)
            Result.failure(e)
        }
    }

    private fun resolveAbsoluteHref(baseUrl: String, href: String): String {
        if (href.startsWith("http://") || href.startsWith("https://")) {
            return href
        }
        val schemeAndHost = baseUrl.substringBefore("://") + "://" + baseUrl.substringAfter("://").substringBefore("/")
        return if (href.startsWith("/")) schemeAndHost + href else "$schemeAndHost/$href"
    }

    /**
     * Robust XML parser for standard WebDAV multistatus (RFC 4918) PROPFIND responses.
     */
    private fun parseWebDavPropfindResponse(xmlContent: String, collectionUrl: String): List<WebDavBackupItem> {
        val items = mutableListOf<WebDavBackupItem>()
        if (xmlContent.isBlank()) return items

        try {
            val parser = Xml.newPullParser()
            parser.setInput(StringReader(xmlContent))

            var eventType = parser.eventType
            var currentHref: String? = null
            var currentDisplayName: String? = null
            var currentContentLength: Long = 0L
            var currentLastModified: Long = 0L
            var isCollection = false

            val httpDateFormat = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss z", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("GMT")
            }

            while (eventType != XmlPullParser.END_DOCUMENT) {
                val tag = parser.name?.substringAfterLast(":") ?: ""

                when (eventType) {
                    XmlPullParser.START_TAG -> {
                        when (tag.lowercase()) {
                            "response" -> {
                                currentHref = null
                                currentDisplayName = null
                                currentContentLength = 0L
                                currentLastModified = 0L
                                isCollection = false
                            }
                            "href" -> {
                                currentHref = parser.nextText()?.trim()
                            }
                            "displayname" -> {
                                currentDisplayName = parser.nextText()?.trim()
                            }
                            "getcontentlength" -> {
                                val lenStr = parser.nextText()?.trim()
                                currentContentLength = lenStr?.toLongOrNull() ?: 0L
                            }
                            "getlastmodified" -> {
                                val dateStr = parser.nextText()?.trim()
                                if (!dateStr.isNullOrBlank()) {
                                    try {
                                        currentLastModified = httpDateFormat.parse(dateStr)?.time ?: System.currentTimeMillis()
                                    } catch (_: Exception) {
                                        currentLastModified = System.currentTimeMillis()
                                    }
                                }
                            }
                            "collection" -> {
                                isCollection = true
                            }
                        }
                    }
                    XmlPullParser.END_TAG -> {
                        if (tag.equals("response", ignoreCase = true)) {
                            val href = currentHref
                            if (href != null && !isCollection) {
                                val cleanHref = href.trimEnd('/')
                                val fileName = currentDisplayName?.takeIf { it.isNotBlank() }
                                    ?: cleanHref.substringAfterLast("/")

                                // Only add valid backup files (.bitbackup, .zip, .bak, .json)
                                if (fileName.isNotBlank() && (fileName.endsWith(".bitbackup") || fileName.endsWith(".zip") || fileName.endsWith(".bak") || fileName.endsWith(".json"))) {
                                    items.add(
                                        WebDavBackupItem(
                                            href = href,
                                            displayName = fileName,
                                            sizeBytes = currentContentLength,
                                            lastModifiedEpochMs = if (currentLastModified > 0) currentLastModified else System.currentTimeMillis()
                                        )
                                    )
                                }
                            }
                        }
                    }
                }
                eventType = parser.next()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed parsing WebDAV response XML: ${e.message}")
        }

        return items
    }
}
