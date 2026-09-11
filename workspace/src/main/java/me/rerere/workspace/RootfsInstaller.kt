package me.rerere.workspace

import java.io.BufferedInputStream
import java.io.EOFException
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.util.Locale
import java.util.zip.GZIPInputStream
import org.tukaani.xz.XZInputStream

import java.security.MessageDigest

class RootfsInstaller(
    private val manager: WorkspaceManager,
    private val patcher: RootfsPatcher = RootfsPatcher(),
    private val cacheDir: File? = null,
) {
    fun calculateSha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered(64 * 1024).use { input ->
            val buffer = ByteArray(64 * 1024)
            var bytesRead: Int
            while (input.read(buffer).also { bytesRead = it } != -1) {
                digest.update(buffer, 0, bytesRead)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    fun getCachedArchive(url: String, expectedSha256: String? = null): File? {
        val dir = cacheDir ?: return null
        val format = ArchiveFormat.fromUrl(url)
        val hash = (url.hashCode().toLong() and 0xFFFFFFFFL).toString(16)
        val legacyHash = url.hashCode().toString(16)

        val candidateNames = mutableListOf(
            "rootfs_$hash.${format.extension}",
            "rootfs_$legacyHash.${format.extension}"
        )
        if (!expectedSha256.isNullOrBlank()) {
            candidateNames.add("rootfs_${expectedSha256.take(16)}.${format.extension}")
        }

        for (name in candidateNames) {
            val file = File(dir, name)
            if (file.exists() && file.length() > 500_000L) {
                val marker = File(dir, "${file.nameWithoutExtension}.verified")
                if (marker.exists()) return file
                if (!expectedSha256.isNullOrBlank()) {
                    val actualHash = runCatching { calculateSha256(file) }.getOrNull()
                    if (expectedSha256.trim().equals(actualHash, ignoreCase = true)) {
                        marker.writeText(actualHash ?: "verified")
                        return file
                    }
                } else {
                    return file
                }
            }
        }

        // Search directory for any verified archive matching expectedSha256
        if (!expectedSha256.isNullOrBlank()) {
            val matched = dir.listFiles()?.firstOrNull { f ->
                (f.name.endsWith(".tar.gz") || f.name.endsWith(".tar.xz")) &&
                f.length() > 500_000L &&
                File(dir, "${f.nameWithoutExtension}.verified").takeIf { it.exists() }?.readText()?.trim()?.equals(expectedSha256.trim(), ignoreCase = true) == true
            }
            if (matched != null) return matched
        }

        return null
    }

    fun isCached(url: String, expectedSha256: String? = null): Boolean = getCachedArchive(url, expectedSha256) != null

    fun install(
        root: String,
        url: String,
        expectedSha256: String? = null,
        onProgress: (RootfsInstallProgress) -> Unit = {},
    ) {
        require(url.isNotBlank()) { "Rootfs download url is required" }
        manager.ensureWorkspace(root)
        val format = ArchiveFormat.fromUrl(url)
        val tempDir = manager.tempDir(root)
        val stagingDir = File(tempDir, "rootfs-staging")
        val linuxDir = manager.linuxDir(root)

        val cachedArchive = getCachedArchive(url, expectedSha256)
        val archive = cachedArchive ?: File(tempDir, "rootfs.${format.extension}")

        try {
            stagingDir.deleteRecursively()
            stagingDir.mkdirs()

            if (cachedArchive != null && cachedArchive.exists()) {
                onProgress(RootfsInstallProgress(
                    stage = RootfsInstallStage.DOWNLOADING,
                    bytesRead = cachedArchive.length(),
                    totalBytes = cachedArchive.length()
                ))
            } else {
                download(url, archive, onProgress)
            }

            // Cryptographic SHA-256 verification before extraction
            if (!expectedSha256.isNullOrBlank()) {
                onProgress(RootfsInstallProgress(
                    stage = RootfsInstallStage.VERIFYING,
                    bytesRead = archive.length(),
                    totalBytes = archive.length()
                ))
                val actualSha256 = calculateSha256(archive)
                if (!expectedSha256.trim().equals(actualSha256, ignoreCase = true)) {
                    archive.delete()
                    cacheDir?.let { cDir ->
                        val hash = url.hashCode().toString(16)
                        File(cDir, "rootfs_$hash.${format.extension}").delete()
                    }
                    throw SecurityException(
                        "Rootfs archive cryptographic integrity check failed for $url!\n" +
                        "Expected SHA-256: ${expectedSha256.trim()}\n" +
                        "Computed SHA-256: $actualSha256"
                    )
                }
            }

            // Cache the verified archive for subsequent workspaces
            if (cachedArchive == null) {
                cacheDir?.let { cDir ->
                    runCatching {
                        cDir.mkdirs()
                        val hash = (url.hashCode().toLong() and 0xFFFFFFFFL).toString(16)
                        val persistentCache = File(cDir, "rootfs_$hash.${format.extension}")
                        if (archive.absolutePath != persistentCache.absolutePath) {
                            archive.copyTo(persistentCache, overwrite = true)
                        }
                        File(cDir, "rootfs_$hash.verified").writeText(expectedSha256 ?: "verified")
                        if (!expectedSha256.isNullOrBlank()) {
                            val shaPrefix = expectedSha256.take(16)
                            File(cDir, "rootfs_$shaPrefix.verified").writeText(expectedSha256)
                        }
                    }
                }
            }

            extractTar(archive, stagingDir, format, onProgress)
            linuxDir.deleteRecursively()
            linuxDir.mkdirs()
            if (!stagingDir.renameTo(linuxDir)) {
                stagingDir.copyRecursively(linuxDir, overwrite = true)
                stagingDir.deleteRecursively()
            }
            patcher.patch(linuxDir)
            require(linuxDir.hasUsableRootfs()) {
                "Rootfs does not contain a usable shell environment"
            }
            onProgress(RootfsInstallProgress(stage = RootfsInstallStage.INSTALLED))
        } finally {
            val hash = url.hashCode().toString(16)
            val persistentCache = cacheDir?.let { File(it, "rootfs_$hash.${format.extension}") }
            if (cachedArchive == null && (persistentCache == null || archive.absolutePath != persistentCache.absolutePath)) {
                archive.delete()
            }
            stagingDir.deleteRecursively()
        }
    }

    private fun download(
        url: String,
        target: File,
        onProgress: (RootfsInstallProgress) -> Unit,
    ) {
        var currentUrl = url
        var connection: HttpURLConnection? = null
        var redirects = 0

        while (redirects < 10) {
            val conn = (URL(currentUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14; Mobile) BIT/1.0")
            }
            connection = conn
            val code = conn.responseCode
            if (code in 300..399) {
                val location = conn.getHeaderField("Location")
                conn.disconnect()
                if (location.isNullOrBlank()) break
                currentUrl = if (location.startsWith("http://") || location.startsWith("https://")) {
                    location
                } else {
                    URL(URL(currentUrl), location).toString()
                }
                redirects++
            } else {
                break
            }
        }

        val conn = connection ?: error("Failed to establish HTTP connection")
        try {
            val code = conn.responseCode
            require(code in 200..299) { "Rootfs download failed: HTTP $code from $currentUrl" }
            val totalBytes = conn.contentLengthLong.takeIf { it > 0 }
            target.parentFile?.mkdirs()
            conn.inputStream.use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    var bytesRead = 0L
                    var lastReportBytes = 0L
                    while (true) {
                        checkInterrupted()
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        bytesRead += read
                        if (bytesRead - lastReportBytes >= PROGRESS_STEP_BYTES || bytesRead == totalBytes) {
                            lastReportBytes = bytesRead
                            onProgress(
                                RootfsInstallProgress(
                                    stage = RootfsInstallStage.DOWNLOADING,
                                    bytesRead = bytesRead,
                                    totalBytes = totalBytes,
                                )
                            )
                        }
                    }
                    if (bytesRead == 0L) {
                        onProgress(
                            RootfsInstallProgress(
                                stage = RootfsInstallStage.DOWNLOADING,
                                bytesRead = 0,
                                totalBytes = totalBytes,
                            )
                        )
                    }
                }
            }
        } finally {
            conn.disconnect()
        }
    }

    internal fun extractTar(
        archive: File,
        targetDir: File,
        format: ArchiveFormat = ArchiveFormat.fromFile(archive),
        onProgress: (RootfsInstallProgress) -> Unit,
    ) {
        format.wrapStream(BufferedInputStream(archive.inputStream())).use { input ->
            var entries = 0
            var pendingName: String? = null
            var pendingLinkName: String? = null
            while (true) {
                checkInterrupted()
                val rawHeader = input.readTarHeader() ?: break
                val header = rawHeader.copy(
                    name = pendingName ?: rawHeader.name,
                    linkName = pendingLinkName ?: rawHeader.linkName,
                )
                pendingName = null
                pendingLinkName = null

                if (header.name.isBlank() || header.name == ".") {
                    if (header.type != TarEntryType.FILE) {
                        input.skipFully(header.size)
                    }
                    input.skipFully(header.size.paddingSize())
                    continue
                }

                if (header.type == TarEntryType.LONG_NAME) {
                    pendingName = input.readExactly(header.size).toString(Charsets.UTF_8).trimEnd('\u0000', '\n')
                    input.skipFully(header.size.paddingSize())
                    continue
                }
                if (header.type == TarEntryType.LONG_LINK) {
                    pendingLinkName = input.readExactly(header.size).toString(Charsets.UTF_8).trimEnd('\u0000', '\n')
                    input.skipFully(header.size.paddingSize())
                    continue
                }
                if (header.type == TarEntryType.PAX) {
                    val pax = parsePax(input.readExactly(header.size).toString(Charsets.UTF_8))
                    pendingName = pax["path"]
                    pendingLinkName = pax["linkpath"]
                    input.skipFully(header.size.paddingSize())
                    continue
                }

                val target = targetDir.safeResolve(header.name)
                target.parentFile?.mkdirs()
                when (header.type) {
                    TarEntryType.DIRECTORY -> target.mkdirs()
                    TarEntryType.SYMLINK -> createSymlink(targetDir, target, header.linkName)
                    TarEntryType.HARDLINK -> createHardLink(targetDir, target, header.linkName)
                    TarEntryType.FILE -> {
                        target.outputStream().use { output ->
                            input.copyExactly(output, header.size)
                        }
                        target.applyMode(header.mode)
                    }
                    TarEntryType.LONG_NAME,
                    TarEntryType.LONG_LINK,
                    TarEntryType.PAX,
                    TarEntryType.OTHER -> Unit
                }
                if (header.type != TarEntryType.FILE) {
                    input.skipFully(header.size)
                }
                input.skipFully(header.size.paddingSize())
                if (header.modTime > 0 && header.type != TarEntryType.SYMLINK) {
                    target.setLastModified(header.modTime * 1000)
                }
                entries++
                onProgress(
                    RootfsInstallProgress(
                        stage = RootfsInstallStage.EXTRACTING,
                        entriesExtracted = entries,
                        currentEntry = header.name,
                    )
                )
            }
        }
    }

    private fun createSymlink(root: File, target: File, linkName: String) {
        if (linkName.isBlank()) return
        val rootFile = root.canonicalFile
        val sourceForFallback: File?
        val linkTarget = if (File(linkName).isAbsolute) {
            sourceForFallback = root.safeResolve(linkName.trimStart('/'))
            File(linkName)
        } else {
            val resolved = File(target.parentFile ?: root, linkName).canonicalFile
            require(resolved.path == rootFile.path || resolved.path.startsWith(rootFile.path + File.separator)) {
                "Symlink escapes rootfs: ${target.name}"
            }
            sourceForFallback = resolved
            File(linkName)
        }
        target.delete()
        runCatching {
            Files.createSymbolicLink(target.toPath(), linkTarget.toPath())
        }.recoverCatching { error ->
            if (error !is IOException &&
                error !is UnsupportedOperationException &&
                error !is SecurityException
            ) {
                throw error
            }
            if (sourceForFallback != null && sourceForFallback.exists()) {
                if (sourceForFallback.isDirectory) {
                    sourceForFallback.copyRecursively(target, overwrite = true)
                } else {
                    sourceForFallback.copyTo(target, overwrite = true)
                    target.setReadable(sourceForFallback.canRead(), false)
                    target.setWritable(sourceForFallback.canWrite(), true)
                    target.setExecutable(sourceForFallback.canExecute(), false)
                }
            }
        }.getOrNull()
    }

    private fun createHardLink(root: File, target: File, linkName: String) {
        if (linkName.isBlank()) return
        val source = root.safeResolve(linkName)
        if (!source.exists()) return
        target.delete()
        runCatching {
            Files.createLink(target.toPath(), source.toPath())
        }.recoverCatching { error ->
            if (error !is IOException &&
                error !is UnsupportedOperationException &&
                error !is SecurityException
            ) {
                throw error
            }
            source.copyTo(target, overwrite = true)
            target.setReadable(source.canRead(), false)
            target.setWritable(source.canWrite(), true)
            target.setExecutable(source.canExecute(), false)
        }.getOrNull()
    }

    private fun InputStream.readTarHeader(): TarHeader? {
        val header = ByteArray(TAR_BLOCK_SIZE)
        val read = readFullyOrEnd(header)
        if (read == 0) return null
        if (read < TAR_BLOCK_SIZE) throw EOFException("Unexpected EOF while reading tar header")
        if (header.all { it == 0.toByte() }) return null

        val name = header.string(0, 100)
        val prefix = header.string(345, 155)
        val fullName = listOf(prefix, name)
            .filter { it.isNotBlank() }
            .joinToString("/")
        return TarHeader(
            name = normalizeTarPath(fullName),
            mode = header.octal(100, 8).toInt(),
            size = header.octal(124, 12),
            modTime = header.octal(136, 12),
            type = when (header[156].toInt().toChar()) {
                '0', '\u0000' -> TarEntryType.FILE
                '5' -> TarEntryType.DIRECTORY
                '2' -> TarEntryType.SYMLINK
                '1' -> TarEntryType.HARDLINK
                'L' -> TarEntryType.LONG_NAME
                'K' -> TarEntryType.LONG_LINK
                'x' -> TarEntryType.PAX
                else -> TarEntryType.OTHER
            },
            linkName = header.string(157, 100),
        )
    }

    private fun parsePax(text: String): Map<String, String> {
        val result = mutableMapOf<String, String>()
        var index = 0
        while (index < text.length) {
            val space = text.indexOf(' ', index)
            if (space < 0) break
            val length = text.substring(index, space).toIntOrNull() ?: break
            val end = (index + length).coerceAtMost(text.length)
            val record = text.substring(space + 1, end).trimEnd('\n')
            val equals = record.indexOf('=')
            if (equals > 0) {
                result[record.substring(0, equals)] = record.substring(equals + 1)
            }
            index += length
        }
        return result
    }

    private fun checkInterrupted() {
        if (Thread.currentThread().isInterrupted) {
            throw InterruptedException("Rootfs install cancelled")
        }
    }

    private fun InputStream.copyExactly(output: java.io.OutputStream, bytes: Long) {
        val buffer = ByteArray(BUFFER_SIZE)
        var remaining = bytes
        while (remaining > 0) {
            checkInterrupted()
            val read = read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
            if (read < 0) throw EOFException("Unexpected EOF while extracting tar entry")
            output.write(buffer, 0, read)
            remaining -= read
        }
    }

    private fun InputStream.readExactly(bytes: Long): ByteArray {
        require(bytes <= Int.MAX_VALUE) { "Tar entry is too large to buffer: $bytes" }
        val buffer = ByteArray(bytes.toInt())
        val read = readFullyOrEnd(buffer)
        if (read != buffer.size) throw EOFException("Unexpected EOF while reading tar entry")
        return buffer
    }

    private fun InputStream.skipFully(bytes: Long) {
        var remaining = bytes
        val buffer = ByteArray(BUFFER_SIZE)
        while (remaining > 0) {
            checkInterrupted()
            val read = read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
            if (read < 0) throw EOFException("Unexpected EOF while skipping tar block")
            remaining -= read
        }
    }

    private fun InputStream.readFullyOrEnd(buffer: ByteArray): Int {
        var offset = 0
        while (offset < buffer.size) {
            val read = read(buffer, offset, buffer.size - offset)
            if (read < 0) break
            offset += read
        }
        return offset
    }

    private fun File.safeResolve(path: String): File {
        val normalized = normalizeTarPath(path)
        if (normalized.isBlank() || normalized == ".") return this
        val rootPath = toPath().toAbsolutePath().normalize()
        val targetPath = rootPath.resolve(normalized).normalize()
        require(targetPath == rootPath || targetPath.startsWith(rootPath)) {
            "Rootfs entry escapes target directory: $path"
        }
        return targetPath.toFile()
    }

    private fun File.applyMode(mode: Int) {
        setReadable(mode and 0b100_000_000 != 0, false)
        setWritable(mode and 0b010_000_000 != 0, true)
        setExecutable(mode and 0b001_000_000 != 0, false)
    }

    private fun normalizeTarPath(path: String): String {
        val normalized = path
            .replace('\\', '/')
            .trim()
            .trimStart('/')
            .removePrefix("./")
            .trimStart('/')
        if (normalized.isBlank() || normalized == ".") return ""
        require(!normalized.contains('\u0000')) { "Rootfs entry path contains invalid character" }
        require(normalized.split('/').none { it == ".." }) { "Rootfs entry escapes target directory: $path" }
        return normalized
    }

    private fun ByteArray.string(offset: Int, length: Int): String {
        val end = (offset until offset + length)
            .firstOrNull { this[it] == 0.toByte() }
            ?: (offset + length)
        return copyOfRange(offset, end).toString(Charsets.UTF_8).trim()
    }

    private fun ByteArray.octal(offset: Int, length: Int): Long {
        val value = string(offset, length)
            .trim()
            .lowercase(Locale.US)
            .trimEnd('\u0000')
        return if (value.isBlank()) 0L else value.toLong(8)
    }

    private fun Long.paddingSize(): Long = (TAR_BLOCK_SIZE - (this % TAR_BLOCK_SIZE)).let {
        if (it == TAR_BLOCK_SIZE.toLong()) 0L else it
    }

    private fun Long.paddedTarSize(): Long = this + paddingSize()

    private data class TarHeader(
        val name: String,
        val mode: Int,
        val size: Long,
        val modTime: Long,
        val type: TarEntryType,
        val linkName: String,
    )

    private enum class TarEntryType {
        FILE,
        DIRECTORY,
        SYMLINK,
        HARDLINK,
        LONG_NAME,
        LONG_LINK,
        PAX,
        OTHER,
    }

    enum class ArchiveFormat(val extension: String) {
        TAR_GZ("tar.gz") {
            override fun wrapStream(input: InputStream): InputStream = GZIPInputStream(input)
        },
        TAR_XZ("tar.xz") {
            override fun wrapStream(input: InputStream): InputStream = XZInputStream(input)
        };

        abstract fun wrapStream(input: InputStream): InputStream

        companion object {
            fun fromUrl(url: String): ArchiveFormat {
                val path = url.substringBefore('?').substringBefore('#')
                return when {
                    path.endsWith(".tar.xz") || path.endsWith(".txz") -> TAR_XZ
                    else -> TAR_GZ
                }
            }

            fun fromFile(file: File): ArchiveFormat = fromUrl(file.name)
        }
    }

    companion object {
        private const val TAR_BLOCK_SIZE = 512
        private const val BUFFER_SIZE = 64 * 1024
        private const val PROGRESS_STEP_BYTES = 512 * 1024
        private const val CONNECT_TIMEOUT_MS = 30_000
        private const val READ_TIMEOUT_MS = 60_000
    }
}
