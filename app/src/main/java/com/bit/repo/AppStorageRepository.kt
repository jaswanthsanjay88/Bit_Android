package com.bit.repo

import android.content.Context
import android.os.Environment
import android.os.StatFs
import android.util.Log
import com.bit.database.AppDatabase
import com.bit.global.AppPaths
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

data class StorageCategoryUsage(
    val id: String,
    val label: String,
    val bytes: Long,
    val fileCount: Int,
    val canClear: Boolean = false,
    val canInspect: Boolean = true
)

data class StorageFileItem(
    val id: String,
    val path: String,
    val displayName: String,
    val sizeBytes: Long,
    val lastModified: Long,
    val categoryId: String,
    val isDeletable: Boolean = true
)

data class AppStorageSnapshot(
    val totalAppBytes: Long = 0L,
    val freeDeviceBytes: Long = 0L,
    val totalDeviceBytes: Long = 0L,
    val categories: List<StorageCategoryUsage> = emptyList(),
    val isScanning: Boolean = false
)

class AppStorageRepository(
    private val context: Context
) {
    companion object {
        private const val TAG = "AppStorageRepo"
        const val CATEGORY_MODELS = "models"
        const val CATEGORY_VOICE = "voice"
        const val CATEGORY_WORKSPACE = "workspace"
        const val CATEGORY_SKILLS = "skills"
        const val CATEGORY_VAULT = "vault"
        const val CATEGORY_RAGS = "rags"
        const val CATEGORY_DATABASE = "database"
        const val CATEGORY_CACHE = "cache"
    }

    private val _snapshot = MutableStateFlow(AppStorageSnapshot(isScanning = true))
    val snapshot: Flow<AppStorageSnapshot> = _snapshot.asStateFlow()

    private val isScanning = AtomicBoolean(false)

    suspend fun refresh() = withContext(Dispatchers.IO) {
        if (isScanning.getAndSet(true)) return@withContext
        try {
            _snapshot.value = _snapshot.value.copy(isScanning = true)
            val categories = scanAllCategories()
            val totalAppBytes = categories.sumOf { it.bytes }

            val stat = try {
                val path = Environment.getDataDirectory()
                val statFs = StatFs(path.path)
                val blockSize = statFs.blockSizeLong
                val availableBlocks = statFs.availableBlocksLong
                val totalBlocks = statFs.blockCountLong
                Triple(availableBlocks * blockSize, totalBlocks * blockSize, true)
            } catch (e: Exception) {
                Triple(0L, 0L, false)
            }

            _snapshot.value = AppStorageSnapshot(
                totalAppBytes = totalAppBytes,
                freeDeviceBytes = stat.first,
                totalDeviceBytes = stat.second,
                categories = categories,
                isScanning = false
            )
        } catch (e: Exception) {
            Log.e(TAG, "Storage scan failed", e)
            _snapshot.value = _snapshot.value.copy(isScanning = false)
        } finally {
            isScanning.set(false)
        }
    }

    private fun scanAllCategories(): List<StorageCategoryUsage> {
        val list = mutableListOf<StorageCategoryUsage>()

        // 1. LLM & Vision Models (exclude voice models which live in ttsModel/sttModel)
        val modelsDir = AppPaths.models(context)
        val ttsDir = AppPaths.ttsModel(context)
        val sttDir = AppPaths.sttModel(context)
        val voicePaths = setOf(ttsDir.absolutePath, sttDir.absolutePath)

        var modelsBytes = 0L
        var modelsCount = 0
        if (modelsDir.exists()) {
            modelsDir.listFiles()?.forEach { file ->
                if (file.absolutePath !in voicePaths) {
                    val size = calculateDirectorySize(file)
                    modelsBytes += size
                    modelsCount += if (file.isDirectory) (file.listFiles()?.size ?: 1) else 1
                }
            }
        }
        list.add(
            StorageCategoryUsage(
                id = CATEGORY_MODELS,
                label = "Local AI Models",
                bytes = modelsBytes,
                fileCount = modelsCount,
                canClear = false,
                canInspect = true
            )
        )

        // 2. Voice & Audio Models (Kokoro, Piper, Whisper)
        var voiceBytes = 0L
        var voiceCount = 0
        if (ttsDir.exists()) {
            voiceBytes += calculateDirectorySize(ttsDir)
            voiceCount += ttsDir.listFiles()?.size ?: 1
        }
        if (sttDir.exists()) {
            voiceBytes += calculateDirectorySize(sttDir)
            voiceCount += sttDir.listFiles()?.size ?: 1
        }
        list.add(
            StorageCategoryUsage(
                id = CATEGORY_VOICE,
                label = "Voice & Speech Engines",
                bytes = voiceBytes,
                fileCount = voiceCount,
                canClear = false,
                canInspect = true
            )
        )

        // 3. Linux Workspaces & Sandboxes
        val workspacesDir = File(context.filesDir, "workspaces")
        val rootfsCacheDir = File(context.filesDir, "rootfs_cache")
        val legacyWorkspaceDir = File(context.filesDir, "workspace")
        var workspaceBytes = 0L
        var workspaceCount = 0
        listOf(workspacesDir, rootfsCacheDir, legacyWorkspaceDir).forEach { dir ->
            if (dir.exists()) {
                workspaceBytes += calculateDirectorySize(dir)
                workspaceCount += countFiles(dir)
            }
        }
        list.add(
            StorageCategoryUsage(
                id = CATEGORY_WORKSPACE,
                label = "Linux Workspaces & Sandboxes",
                bytes = workspaceBytes,
                fileCount = workspaceCount,
                canClear = false,
                canInspect = true
            )
        )

        // 4. Agent Skills & Prompts
        val skillsDir = File(context.filesDir, "skills")
        var skillsBytes = 0L
        var skillsCount = 0
        if (skillsDir.exists()) {
            skillsBytes += calculateDirectorySize(skillsDir)
            skillsCount += countFiles(skillsDir)
        }
        list.add(
            StorageCategoryUsage(
                id = CATEGORY_SKILLS,
                label = "Agent Skills & Prompts",
                bytes = skillsBytes,
                fileCount = skillsCount,
                canClear = false,
                canInspect = true
            )
        )

        // 5. Memory Vault & Vectors
        val vaultDir = AppPaths.vaultRoot(context)
        val umsDir = AppPaths.ums(context)
        val legacyVault = AppPaths.memoryVault(context)
        var vaultBytes = 0L
        var vaultCount = 0
        if (vaultDir.exists()) {
            vaultBytes += calculateDirectorySize(vaultDir)
            vaultCount += countFiles(vaultDir)
        }
        if (umsDir.exists()) {
            vaultBytes += calculateDirectorySize(umsDir)
            vaultCount += countFiles(umsDir)
        }
        if (legacyVault.exists()) {
            vaultBytes += calculateDirectorySize(legacyVault)
            vaultCount += countFiles(legacyVault)
        }
        list.add(
            StorageCategoryUsage(
                id = CATEGORY_VAULT,
                label = "Memory Vault & Embeddings",
                bytes = vaultBytes,
                fileCount = vaultCount,
                canClear = false,
                canInspect = true
            )
        )

        // 6. RAG Knowledge Bases
        val ragsDir = AppPaths.rags(context)
        var ragsBytes = 0L
        var ragsCount = 0
        if (ragsDir.exists()) {
            ragsBytes += calculateDirectorySize(ragsDir)
            ragsCount += countFiles(ragsDir)
        }
        list.add(
            StorageCategoryUsage(
                id = CATEGORY_RAGS,
                label = "RAG Knowledge Bases",
                bytes = ragsBytes,
                fileCount = ragsCount,
                canClear = false,
                canInspect = true
            )
        )

        // 7. Chat Database & SQLite files
        val dbDir = context.getDatabasePath("bit_database").parentFile
        var dbBytes = 0L
        var dbCount = 0
        if (dbDir != null && dbDir.exists()) {
            dbDir.listFiles()?.forEach { dbFile ->
                dbBytes += dbFile.length()
                dbCount++
            }
        }
        list.add(
            StorageCategoryUsage(
                id = CATEGORY_DATABASE,
                label = "Chat SQLite Database",
                bytes = dbBytes,
                fileCount = dbCount,
                canClear = false,
                canInspect = true
            )
        )

        // 8. Temporary Cache & Logs
        val cacheDir = context.cacheDir
        val promptCacheDir = AppPaths.promptCache(context)
        val tempDownloadsDir = File(context.filesDir, "temp_downloads")
        val logsDir = File(context.filesDir, "logs")
        val ttsOutputDir = File(context.filesDir, "tts_output")
        var cacheBytes = 0L
        var cacheCount = 0
        listOf(cacheDir, promptCacheDir, tempDownloadsDir, logsDir, ttsOutputDir).forEach { dir ->
            if (dir.exists()) {
                cacheBytes += calculateDirectorySize(dir)
                cacheCount += countFiles(dir)
            }
        }
        list.add(
            StorageCategoryUsage(
                id = CATEGORY_CACHE,
                label = "Temporary Cache & Downloads",
                bytes = cacheBytes,
                fileCount = cacheCount,
                canClear = true,
                canInspect = true
            )
        )

        return list
    }

    suspend fun listCategoryFiles(categoryId: String): List<StorageFileItem> = withContext(Dispatchers.IO) {
        val items = mutableListOf<StorageFileItem>()
        when (categoryId) {
            CATEGORY_MODELS -> {
                val modelsDir = AppPaths.models(context)
                val ttsPath = AppPaths.ttsModel(context).absolutePath
                val sttPath = AppPaths.sttModel(context).absolutePath
                if (modelsDir.exists()) {
                    modelsDir.listFiles()?.forEach { file ->
                        if (file.absolutePath != ttsPath && file.absolutePath != sttPath) {
                            items.add(
                                StorageFileItem(
                                    id = file.absolutePath,
                                    path = file.absolutePath,
                                    displayName = file.name,
                                    sizeBytes = calculateDirectorySize(file),
                                    lastModified = file.lastModified(),
                                    categoryId = categoryId,
                                    isDeletable = true
                                )
                            )
                        }
                    }
                }
            }
            CATEGORY_VOICE -> {
                val ttsDir = AppPaths.ttsModel(context)
                val sttDir = AppPaths.sttModel(context)
                if (ttsDir.exists()) {
                    items.add(
                        StorageFileItem(
                            id = ttsDir.absolutePath,
                            path = ttsDir.absolutePath,
                            displayName = "TTS Engine (${ttsDir.name})",
                            sizeBytes = calculateDirectorySize(ttsDir),
                            lastModified = ttsDir.lastModified(),
                            categoryId = categoryId,
                            isDeletable = true
                        )
                    )
                }
                if (sttDir.exists()) {
                    items.add(
                        StorageFileItem(
                            id = sttDir.absolutePath,
                            path = sttDir.absolutePath,
                            displayName = "STT Whisper (${sttDir.name})",
                            sizeBytes = calculateDirectorySize(sttDir),
                            lastModified = sttDir.lastModified(),
                            categoryId = categoryId,
                            isDeletable = true
                        )
                    )
                }
            }
            CATEGORY_WORKSPACE -> {
                val workspacesDir = File(context.filesDir, "workspaces")
                val rootfsCacheDir = File(context.filesDir, "rootfs_cache")
                val legacyWorkspaceDir = File(context.filesDir, "workspace")
                listOf(workspacesDir, rootfsCacheDir, legacyWorkspaceDir).forEach { dir ->
                    if (dir.exists()) {
                        dir.listFiles()?.forEach { file ->
                            items.add(
                                StorageFileItem(
                                    id = file.absolutePath,
                                    path = file.absolutePath,
                                    displayName = if (dir.name == "rootfs_cache") "Cached Rootfs (${file.name})" else file.name,
                                    sizeBytes = calculateDirectorySize(file),
                                    lastModified = file.lastModified(),
                                    categoryId = categoryId,
                                    isDeletable = true
                                )
                            )
                        }
                    }
                }
            }
            CATEGORY_SKILLS -> {
                val skillsDir = File(context.filesDir, "skills")
                if (skillsDir.exists()) {
                    skillsDir.listFiles()?.forEach { file ->
                        items.add(
                            StorageFileItem(
                                id = file.absolutePath,
                                path = file.absolutePath,
                                displayName = file.name,
                                sizeBytes = calculateDirectorySize(file),
                                lastModified = file.lastModified(),
                                categoryId = categoryId,
                                isDeletable = true
                            )
                        )
                    }
                }
            }
            CATEGORY_VAULT -> {
                val vaultDir = AppPaths.vaultRoot(context)
                if (vaultDir.exists()) {
                    vaultDir.walkTopDown().filter { it.isFile }.forEach { file ->
                        items.add(
                            StorageFileItem(
                                id = file.absolutePath,
                                path = file.absolutePath,
                                displayName = file.name,
                                sizeBytes = file.length(),
                                lastModified = file.lastModified(),
                                categoryId = categoryId,
                                isDeletable = !file.name.endsWith(".db")
                            )
                        )
                    }
                }
            }
            CATEGORY_RAGS -> {
                val ragsDir = AppPaths.rags(context)
                if (ragsDir.exists()) {
                    ragsDir.listFiles()?.forEach { file ->
                        items.add(
                            StorageFileItem(
                                id = file.absolutePath,
                                path = file.absolutePath,
                                displayName = file.name,
                                sizeBytes = calculateDirectorySize(file),
                                lastModified = file.lastModified(),
                                categoryId = categoryId,
                                isDeletable = true
                            )
                        )
                    }
                }
            }
            CATEGORY_DATABASE -> {
                val dbDir = context.getDatabasePath("bit_database").parentFile
                if (dbDir != null && dbDir.exists()) {
                    dbDir.listFiles()?.forEach { file ->
                        items.add(
                            StorageFileItem(
                                id = file.absolutePath,
                                path = file.absolutePath,
                                displayName = file.name,
                                sizeBytes = file.length(),
                                lastModified = file.lastModified(),
                                categoryId = categoryId,
                                isDeletable = false // Protected database file
                            )
                        )
                    }
                }
            }
            CATEGORY_CACHE -> {
                val cacheDir = context.cacheDir
                val promptCache = AppPaths.promptCache(context)
                val tempDownloads = File(context.filesDir, "temp_downloads")
                listOf(cacheDir, promptCache, tempDownloads).forEach { dir ->
                    if (dir.exists()) {
                        dir.listFiles()?.forEach { file ->
                            items.add(
                                StorageFileItem(
                                    id = file.absolutePath,
                                    path = file.absolutePath,
                                    displayName = file.name,
                                    sizeBytes = calculateDirectorySize(file),
                                    lastModified = file.lastModified(),
                                    categoryId = categoryId,
                                    isDeletable = true
                                )
                            )
                        }
                    }
                }
            }
        }
        items.sortedByDescending { it.sizeBytes }
    }

    suspend fun deleteFile(path: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val file = File(path)
            if (file.exists()) {
                val deleted = if (file.isDirectory) file.deleteRecursively() else file.delete()
                refresh()
                return@withContext deleted
            }
            false
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete file: $path", e)
            false
        }
    }

    suspend fun clearTempCache(): Long = withContext(Dispatchers.IO) {
        var freedBytes = 0L
        try {
            val cacheDir = context.cacheDir
            if (cacheDir.exists()) {
                freedBytes += calculateDirectorySize(cacheDir)
                cacheDir.deleteRecursively()
                cacheDir.mkdirs()
            }
            val promptCache = AppPaths.promptCache(context)
            if (promptCache.exists()) {
                freedBytes += calculateDirectorySize(promptCache)
                promptCache.deleteRecursively()
            }
            val tempDownloads = File(context.filesDir, "temp_downloads")
            if (tempDownloads.exists()) {
                freedBytes += calculateDirectorySize(tempDownloads)
                tempDownloads.deleteRecursively()
            }
            refresh()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to clear temp cache", e)
        }
        freedBytes
    }

    suspend fun vacuumDatabase(): Boolean = withContext(Dispatchers.IO) {
        try {
            val db = AppDatabase.getDatabase(context)
            db.openHelper.writableDatabase.execSQL("VACUUM;")
            db.openHelper.writableDatabase.execSQL("PRAGMA optimize;")
            refresh()
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to vacuum database", e)
            false
        }
    }

    private fun calculateDirectorySize(directory: File): Long {
        if (!directory.exists()) return 0L
        if (directory.isFile) return directory.length()
        var length = 0L
        val files = directory.listFiles() ?: return 0L
        for (file in files) {
            length += if (file.isFile) file.length() else calculateDirectorySize(file)
        }
        return length
    }

    private fun countFiles(directory: File): Int {
        if (!directory.exists()) return 0
        if (directory.isFile) return 1
        var count = 0
        val files = directory.listFiles() ?: return 0
        for (file in files) {
            count += if (file.isFile) 1 else countFiles(file)
        }
        return count
    }
}
