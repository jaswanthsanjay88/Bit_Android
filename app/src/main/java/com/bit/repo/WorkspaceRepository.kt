package com.bit.repo

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import me.rerere.workspace.ProotShellRunner
import me.rerere.workspace.RootfsInstallProgress
import me.rerere.workspace.RootfsInstallStage
import me.rerere.workspace.RootfsInstaller
import me.rerere.workspace.WorkspaceCommandResult
import me.rerere.workspace.WorkspaceFileEntry
import me.rerere.workspace.WorkspaceManager
import me.rerere.workspace.WorkspaceShellStatus
import me.rerere.workspace.WorkspaceStorageArea
import com.bit.database.dao.WorkspaceDao
import com.bit.models.table_schema.WorkspaceEntity
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

data class LinuxDistro(
    val id: String,
    val name: String,
    val version: String,
    val description: String,
    val downloadUrl: String,
    val sizeText: String,
    val packageManager: String,
    val tag: String,
    val sha256: String? = null,
    val isRecommended: Boolean = false,
    val isDownloaded: Boolean = false,
    val cachedSizeBytes: Long = 0L,
)

data class InstalledDistroInfo(
    val name: String,
    val version: String,
    val prettyName: String,
    val packageManager: String,
    val sizeBytes: Long,
    val sizeText: String,
    val arch: String,
    val isAlpine: Boolean,
    val isUbuntu: Boolean,
    val isDebian: Boolean,
)

@Singleton
class WorkspaceRepository @Inject constructor(
    private val dao: WorkspaceDao,
    @param:ApplicationContext private val context: Context,
) {
    companion object {
        private const val TAG = "WorkspaceRepository"

        // Official published Canonical Ubuntu Base 24.04.4 LTS SHA256SUMS
        const val UBUNTU_24_04_ARM64_SHA256 = "04207713ece899c3740823d33690441ad3a7f0ded1101aca744e2b0f37ac7ff2"
        const val UBUNTU_24_04_AMD64_SHA256 = "c1e67ef7b17a6300e136118bd1dc04725009cb376c1aad10abcf8cd453628d58"

        // Official published Alpine Linux 3.20.0 Minirootfs SHA256SUMS
        const val ALPINE_3_20_AARCH64_SHA256 = "83a79199bf3112c65e62ddf1732b051daf62ed2ddf5a8413c440dc25956116f0"
        const val ALPINE_3_20_X86_64_SHA256 = "602efda518516787c716320bd46a3f50e83a74bb749e55483c2f4a9c9f8b9a38"

        fun getUbuntuUrl(): String {
            val isArm = android.os.Build.SUPPORTED_ABIS.firstOrNull()?.contains("arm") ?: true
            val arch = if (isArm) "arm64" else "amd64"
            return "https://cdimage.ubuntu.com/ubuntu-base/releases/24.04/release/ubuntu-base-24.04.4-base-$arch.tar.gz"
        }

        fun getUbuntuSha256(): String {
            val isArm = android.os.Build.SUPPORTED_ABIS.firstOrNull()?.contains("arm") ?: true
            return if (isArm) UBUNTU_24_04_ARM64_SHA256 else UBUNTU_24_04_AMD64_SHA256
        }

        fun getAlpineUrl(): String {
            val isArm = android.os.Build.SUPPORTED_ABIS.firstOrNull()?.contains("arm") ?: true
            val arch = if (isArm) "aarch64" else "x86_64"
            return "https://dl-cdn.alpinelinux.org/alpine/v3.20/releases/$arch/alpine-minirootfs-3.20.0-$arch.tar.gz"
        }

        fun getAlpineSha256(): String {
            val isArm = android.os.Build.SUPPORTED_ABIS.firstOrNull()?.contains("arm") ?: true
            return if (isArm) ALPINE_3_20_AARCH64_SHA256 else ALPINE_3_20_X86_64_SHA256
        }
    }

    val manager: WorkspaceManager by lazy {
        val baseDir = File(context.filesDir, "workspaces").apply { mkdirs() }
        val nativeLibDir = File(context.applicationInfo.nativeLibraryDir)
        WorkspaceManager(
            baseDir = baseDir,
            shellRunner = ProotShellRunner(nativeLibraryDir = nativeLibDir)
        )
    }

    val rootfsCacheDir: File by lazy {
        File(context.filesDir, "rootfs_cache").apply { mkdirs() }
    }

    private val rootfsInstaller: RootfsInstaller by lazy {
        RootfsInstaller(manager = manager, cacheDir = rootfsCacheDir)
    }

    fun isDistroCached(url: String, expectedSha256: String? = null): Boolean =
        rootfsInstaller.isCached(url, expectedSha256)

    fun getAvailableDistros(): List<LinuxDistro> {
        val ubuntuUrl = getUbuntuUrl()
        val ubuntuSha = getUbuntuSha256()
        val alpineUrl = getAlpineUrl()
        val alpineSha = getAlpineSha256()

        val isUbuntuCached = rootfsInstaller.isCached(ubuntuUrl, ubuntuSha)
        val isAlpineCached = rootfsInstaller.isCached(alpineUrl, alpineSha)

        val ubuntuCachedFile = rootfsInstaller.getCachedArchive(ubuntuUrl, ubuntuSha)
        val alpineCachedFile = rootfsInstaller.getCachedArchive(alpineUrl, alpineSha)

        return listOf(
            LinuxDistro(
                id = "ubuntu",
                name = "Ubuntu",
                version = "24.04 LTS (Noble)",
                description = "Full developer environment with apt, Python 3, GCC, pip, curl & Git. Verified Canonical release.",
                downloadUrl = ubuntuUrl,
                sizeText = if (isUbuntuCached) "Downloaded (${formatBytes(ubuntuCachedFile?.length() ?: 0)})" else "~35 MB",
                packageManager = "apt",
                tag = "Full Developer Suite",
                sha256 = ubuntuSha,
                isRecommended = true,
                isDownloaded = isUbuntuCached,
                cachedSizeBytes = ubuntuCachedFile?.length() ?: 0L
            ),
            LinuxDistro(
                id = "alpine",
                name = "Alpine Linux",
                version = "3.20",
                description = "Ultra-lightweight, minimal memory footprint with apk package manager. Verified Alpine release.",
                downloadUrl = alpineUrl,
                sizeText = if (isAlpineCached) "Downloaded (${formatBytes(alpineCachedFile?.length() ?: 0)})" else "~4 MB",
                packageManager = "apk",
                tag = "Lightweight & Fast",
                sha256 = alpineSha,
                isRecommended = false,
                isDownloaded = isAlpineCached,
                cachedSizeBytes = alpineCachedFile?.length() ?: 0L
            )
        )
    }

    fun detectInstalledDistro(root: String): InstalledDistroInfo? {
        val linuxDir = manager.linuxDir(root)
        if (!manager.hasRootfs(root)) return null

        var prettyName = ""
        var name = ""
        var version = ""

        val osRelease = File(linuxDir, "etc/os-release")
        if (osRelease.exists()) {
            runCatching {
                osRelease.readLines().forEach { line ->
                    if (line.startsWith("PRETTY_NAME=")) prettyName = line.substringAfter("=").trim('"', '\'')
                    if (line.startsWith("NAME=") && name.isEmpty()) name = line.substringAfter("=").trim('"', '\'')
                    if (line.startsWith("VERSION_ID=")) version = line.substringAfter("=").trim('"', '\'')
                }
            }
        }

        if (prettyName.isBlank()) {
            val alpineRelease = File(linuxDir, "etc/alpine-release")
            if (alpineRelease.exists()) {
                val v = runCatching { alpineRelease.readText().trim() }.getOrDefault("")
                prettyName = "Alpine Linux $v"
                name = "Alpine Linux"
                version = v
            }
        }

        if (prettyName.isBlank()) {
            prettyName = if (File(linuxDir, "sbin/apk").exists()) "Alpine Linux" else "Linux Rootfs"
        }

        val isAlpine = prettyName.contains("Alpine", ignoreCase = true) || File(linuxDir, "sbin/apk").exists()
        val isUbuntu = prettyName.contains("Ubuntu", ignoreCase = true) || File(linuxDir, "usr/bin/apt").exists()
        val isDebian = prettyName.contains("Debian", ignoreCase = true)

        val pkgManager = if (isAlpine) "apk" else if (isUbuntu || isDebian) "apt" else "pkg"
        val arch = android.os.Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a"

        val sizeBytes = runCatching {
            linuxDir.walkTopDown().maxDepth(3).filter { it.isFile }.sumOf { it.length() }
        }.getOrDefault(0L)

        return InstalledDistroInfo(
            name = name.ifBlank { if (isUbuntu) "Ubuntu" else if (isAlpine) "Alpine" else "Linux" },
            version = version,
            prettyName = prettyName,
            packageManager = pkgManager,
            sizeBytes = sizeBytes,
            sizeText = formatBytes(sizeBytes),
            arch = arch,
            isAlpine = isAlpine,
            isUbuntu = isUbuntu,
            isDebian = isDebian
        )
    }

    fun formatBytes(bytes: Long): String {
        if (bytes <= 0) return "0 B"
        val kb = bytes / 1024.0
        val mb = kb / 1024.0
        val gb = mb / 1024.0
        return when {
            gb >= 1.0 -> String.format(java.util.Locale.US, "%.1f GB", gb)
            mb >= 1.0 -> String.format(java.util.Locale.US, "%.1f MB", mb)
            kb >= 1.0 -> String.format(java.util.Locale.US, "%.1f KB", kb)
            else -> "$bytes B"
        }
    }

    fun listFlow(): Flow<List<WorkspaceEntity>> = dao.listFlow()

    suspend fun getAll(): List<WorkspaceEntity> = withContext(Dispatchers.IO) {
        dao.getAll()
    }

    suspend fun getById(id: String): WorkspaceEntity? = withContext(Dispatchers.IO) {
        dao.getById(id)
    }

    suspend fun checkIntegrity() = withContext(Dispatchers.IO) {
        val workspaces = dao.getAll()
        for (workspace in workspaces) {
            val dir = manager.workspaceDir(workspace.root)
            if (!dir.exists()) {
                Log.w(TAG, "Workspace directory missing, removing record: id=${workspace.id}")
                dao.deleteById(workspace.id)
                continue
            }
            val hasRootfs = manager.hasRootfs(workspace.root)
            val statusName = workspace.shellStatus
            if (statusName == WorkspaceShellStatus.INSTALLING.name) {
                val resolved = if (hasRootfs) WorkspaceShellStatus.READY.name else WorkspaceShellStatus.DISABLED.name
                Log.i(TAG, "Resolving stale INSTALLING workspace ${workspace.id} to $resolved (hasRootfs=$hasRootfs)")
                updateShellState(workspace.id, resolved)
            } else if (statusName == WorkspaceShellStatus.READY.name && !hasRootfs) {
                Log.w(TAG, "Rootfs missing, resetting shell status: id=${workspace.id}")
                updateShellState(workspace.id, WorkspaceShellStatus.DISABLED.name)
            }
        }
    }

    suspend fun create(name: String): WorkspaceEntity = withContext(Dispatchers.IO) {
        val id = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        val finalName = name.trim().ifBlank { "Workspace" }
        val workspace = WorkspaceEntity(
            id = id,
            name = finalName,
            root = id,
            createdAt = now,
            updatedAt = now,
            lastAccessAt = null,
        )
        manager.ensureWorkspace(workspace.root)
        runCatching {
            manager.writeText(
                root = workspace.root,
                path = "welcome.py",
                text = """# On-Device Linux Workspace
# Run with: python3 /workspace/welcome.py
print("🚀 Welcome to your isolated Linux Workspace!")
print("Use the Terminal to install packages (apk add / apt install)")
print("Or ask AI to execute scripts in this workspace.")
""".trimIndent()
            )
        }
        dao.upsert(workspace)
        workspace
    }

    suspend fun rename(id: String, name: String): Boolean = withContext(Dispatchers.IO) {
        val workspace = dao.getById(id) ?: return@withContext false
        val finalName = name.trim().ifBlank { workspace.name }
        dao.upsert(
            workspace.copy(
                name = finalName,
                updatedAt = System.currentTimeMillis(),
            )
        )
        true
    }

    suspend fun delete(id: String): Boolean = withContext(Dispatchers.IO) {
        val workspace = dao.getById(id) ?: return@withContext false
        manager.deleteWorkspace(workspace.root)
        dao.deleteById(id) > 0
    }

    suspend fun setToolApproval(id: String, toolName: String, needsApproval: Boolean): Boolean = withContext(Dispatchers.IO) {
        val workspace = dao.getById(id) ?: return@withContext false
        val overrides = workspace.toolApprovalOverrides() + (toolName to needsApproval)
        dao.upsert(
            workspace.copy(
                toolApprovals = Json.encodeToString(overrides),
                updatedAt = System.currentTimeMillis(),
            )
        )
        true
    }

    suspend fun installRootfs(
        id: String,
        url: String,
        expectedSha256: String? = null,
        onProgress: (RootfsInstallProgress) -> Unit = {},
    ): Boolean = withContext(Dispatchers.IO) {
        val workspace = dao.getById(id) ?: return@withContext false
        updateShellState(workspace.id, WorkspaceShellStatus.INSTALLING.name)
        try {
            val sha = expectedSha256 ?: when (url) {
                getUbuntuUrl() -> getUbuntuSha256()
                getAlpineUrl() -> getAlpineSha256()
                else -> null
            }
            runInterruptible(Dispatchers.IO) {
                rootfsInstaller.install(
                    root = workspace.root,
                    url = url,
                    expectedSha256 = sha,
                    onProgress = onProgress,
                )
                onProgress(RootfsInstallProgress(stage = RootfsInstallStage.CONFIGURING))
            }
            val ready = manager.hasRootfs(workspace.root)
            if (ready) {
                // Auto-provision Python 3 with real-time estimated progress before finalizing
                onProgress(RootfsInstallProgress(
                    stage = RootfsInstallStage.CONFIGURING,
                    customStageMessage = "Configuring Linux sandbox & installing Python 3 runtime (~15s)...",
                    estimatedSecondsRemaining = 15
                ))
                runCatching {
                    ensurePythonInstalled(workspace.id)
                }.onFailure { e ->
                    Log.w(TAG, "Background Python provision failed: ${e.message}")
                }
                onProgress(RootfsInstallProgress(
                    stage = RootfsInstallStage.INSTALLED,
                    customStageMessage = "Linux environment and Python 3 ready!",
                    estimatedSecondsRemaining = 0
                ))
            }
            val newStatus = if (ready) WorkspaceShellStatus.READY.name else WorkspaceShellStatus.BROKEN.name
            withContext(NonCancellable) {
                updateShellState(workspace.id, newStatus)
            }
            ready
        } catch (e: Exception) {
            Log.e(TAG, "Rootfs installation failed for ${workspace.id}", e)
            withContext(NonCancellable) {
                val newStatus = if (manager.hasRootfs(workspace.root)) {
                    WorkspaceShellStatus.READY.name
                } else {
                    WorkspaceShellStatus.DISABLED.name
                }
                updateShellState(workspace.id, newStatus)
            }
            throw e
        }
    }

    suspend fun ensurePythonInstalled(id: String): Boolean = withContext(Dispatchers.IO) {
        val workspace = dao.getById(id) ?: return@withContext false
        if (!manager.hasRootfs(workspace.root)) return@withContext false

        // Check if python3 is already installed and responsive
        val preCheck = runCatching {
            manager.executeCommand(
                root = workspace.root,
                command = "python3 --version",
                timeoutMillis = 5_000L
            )
        }.getOrNull()
        if (preCheck?.exitCode == 0 && preCheck.stdout.contains("Python 3", ignoreCase = true)) {
            Log.i(TAG, "Python 3 is already verified: ${preCheck.stdout.trim()}")
            return@withContext true
        }

        val linuxDir = manager.linuxDir(workspace.root)
        val isAlpine = File(linuxDir, "sbin/apk").exists()
        val isUbuntuOrDebian = File(linuxDir, "usr/bin/apt-get").exists() || File(linuxDir, "usr/bin/apt").exists()

        val bootstrapCmd = if (isAlpine) {
            "sed -i 's/^#//g' /etc/apk/repositories 2>/dev/null || true; " +
            "grep -q 'community' /etc/apk/repositories || (grep 'main' /etc/apk/repositories | sed 's/main/community/' >> /etc/apk/repositories 2>/dev/null || true); " +
            "(apk update || true); " +
            "(apk add --no-cache python3 py3-pip bash curl git ca-certificates || apk add --no-cache python3 || apk add --no-cache --force-broken-world python3); " +
            "ln -sf /usr/bin/python3 /usr/bin/python 2>/dev/null || true; " +
            "ln -sf /usr/bin/pip3 /usr/bin/pip 2>/dev/null || true"
        } else if (isUbuntuOrDebian) {
            "export DEBIAN_FRONTEND=noninteractive; " +
            "echo 'Acquire::ForceIPv4 \"true\";' > /etc/apt/apt.conf.d/99force-ipv4 2>/dev/null || true; " +
            "echo 'Acquire::http::Timeout \"20\";' >> /etc/apt/apt.conf.d/99force-ipv4 2>/dev/null || true; " +
            "apt-get update -o Acquire::ForceIPv4=true && " +
            "apt-get install -y --no-install-recommends -o Acquire::ForceIPv4=true python3 ca-certificates && " +
            "(apt-get install -y --no-install-recommends -o Acquire::ForceIPv4=true python3-pip python3-venv python-is-python3 || true); " +
            "ln -sf /usr/bin/python3 /usr/bin/python 2>/dev/null || true; " +
            "ln -sf /usr/bin/pip3 /usr/bin/pip 2>/dev/null || true"
        } else {
            "which python3 || (which apk && apk add --no-cache python3) || (which apt-get && apt-get update && apt-get install -y python3)"
        }

        try {
            val result = manager.executeCommand(
                root = workspace.root,
                command = bootstrapCmd,
                timeoutMillis = 120_000L
            )
            Log.i(TAG, "Python provision exitCode=${result.exitCode}: stdout=${result.stdout.takeLast(200)}, stderr=${result.stderr.takeLast(200)}")
            // Verify if python3 is now working
            val postCheck = manager.executeCommand(
                root = workspace.root,
                command = "python3 --version",
                timeoutMillis = 5_000L
            )
            postCheck.exitCode == 0
        } catch (e: Exception) {
            Log.e(TAG, "Error installing Python 3", e)
            false
        }
    }

    suspend fun updateShellState(id: String, status: String) = withContext(Dispatchers.IO) {
        dao.updateShellStatus(id, status, System.currentTimeMillis())
    }

    suspend fun executeCommand(
        id: String,
        command: String,
        cwd: String = "",
        timeoutMillis: Long = WorkspaceManager.DEFAULT_COMMAND_TIMEOUT_MS,
        stdin: ByteArray? = null,
    ): WorkspaceCommandResult = withContext(Dispatchers.IO) {
        val workspace = dao.getById(id) ?: return@withContext WorkspaceCommandResult(
            exitCode = 127,
            stdout = "",
            stderr = "Workspace not found",
        )
        manager.executeCommand(
            root = workspace.root,
            command = command,
            cwd = cwd,
            timeoutMillis = timeoutMillis,
            stdin = stdin,
        )
    }

    suspend fun readText(
        id: String,
        path: String,
        area: WorkspaceStorageArea = WorkspaceStorageArea.FILES,
    ): String = withContext(Dispatchers.IO) {
        val workspace = dao.getById(id) ?: error("Workspace not found")
        when (area) {
            WorkspaceStorageArea.FILES -> manager.readText(workspace.root, path)
            WorkspaceStorageArea.LINUX -> {
                val file = File(manager.linuxDir(workspace.root), path.trimStart('/'))
                require(file.exists()) { "File does not exist: $path" }
                file.readText()
            }
        }
    }

    suspend fun writeText(
        id: String,
        path: String,
        text: String,
        overwrite: Boolean = true,
    ): WorkspaceFileEntry = withContext(Dispatchers.IO) {
        val workspace = dao.getById(id) ?: error("Workspace not found")
        manager.writeText(workspace.root, path, text, overwrite)
    }

    suspend fun listFiles(
        id: String,
        path: String = "",
        area: WorkspaceStorageArea = WorkspaceStorageArea.FILES,
    ): List<WorkspaceFileEntry> = withContext(Dispatchers.IO) {
        val workspace = dao.getById(id) ?: return@withContext emptyList()
        manager.listFiles(workspace.root, path, area)
    }

    suspend fun fileSize(
        id: String,
        area: WorkspaceStorageArea = WorkspaceStorageArea.FILES,
        path: String,
    ): Long = withContext(Dispatchers.IO) {
        val workspace = dao.getById(id) ?: error("Workspace not found")
        manager.fileSize(workspace.root, path, area)
    }

    suspend fun deleteFile(
        id: String,
        path: String,
        recursive: Boolean = false,
        area: WorkspaceStorageArea = WorkspaceStorageArea.FILES,
    ): Boolean = withContext(Dispatchers.IO) {
        val workspace = dao.getById(id) ?: return@withContext false
        manager.deleteFile(workspace.root, path, recursive, area)
    }

    suspend fun importFile(
        id: String,
        destinationPath: String = "",
        area: WorkspaceStorageArea = WorkspaceStorageArea.FILES,
        fileName: String,
        inputStream: InputStream,
    ): WorkspaceFileEntry = withContext(Dispatchers.IO) {
        val workspace = dao.getById(id) ?: error("Workspace not found")
        manager.importFile(workspace.root, destinationPath, area, fileName, inputStream)
    }

    suspend fun exportFile(
        id: String,
        path: String,
        area: WorkspaceStorageArea = WorkspaceStorageArea.FILES,
        outputStream: OutputStream,
    ) = withContext(Dispatchers.IO) {
        val workspace = dao.getById(id) ?: error("Workspace not found")
        manager.exportFile(workspace.root, path, area, outputStream)
    }

    fun hasRootfs(id: String): Boolean {
        return manager.hasRootfs(id)
    }

    fun workspaceDir(id: String): File {
        return manager.workspaceDir(id)
    }

    fun filesDir(id: String): File {
        return manager.filesDir(id)
    }

    fun linuxDir(id: String): File {
        return manager.linuxDir(id)
    }
}
