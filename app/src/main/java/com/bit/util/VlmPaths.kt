package com.bit.util

import java.io.File

object VlmPaths {

    const val VLM_DIR = "vlm"

    fun isMmprojFileName(fileName: String): Boolean {
        val lower = fileName.lowercase()
        return lower.contains("mmproj") || lower.contains("projector")
    }

    fun hasMmprojFile(repoFilePaths: Iterable<String>): Boolean =
        repoFilePaths.any { it.endsWith(".gguf", ignoreCase = true) && isMmprojFileName(it) }

    fun vlmFolderName(repoPath: String): String {
        val base = repoPath.substringAfterLast('/').ifBlank { repoPath }
        return sanitize(base)
    }

    fun vlmFolder(modelsDir: File, repoPath: String): File =
        File(File(modelsDir, VLM_DIR), vlmFolderName(repoPath))

    fun isInsideVlmFolder(absolutePath: String, modelsDir: File): Boolean {
        val vlmRoot = File(modelsDir, VLM_DIR).absolutePath + File.separator
        return absolutePath.startsWith(vlmRoot)
    }

    fun colocatedMmproj(baseModelFileOrDir: File): File? {
        val dir = if (baseModelFileOrDir.isDirectory) baseModelFileOrDir else (baseModelFileOrDir.parentFile ?: return null)
        val listing = dir.listFiles() ?: return null
        return listing
            .filter { it.isFile && it.name.endsWith(".gguf", ignoreCase = true) }
            .firstOrNull { isMmprojFileName(it.name) }
    }

    fun listColocatedMmprojs(baseModelFileOrDir: File): List<File> {
        val dir = if (baseModelFileOrDir.isDirectory) baseModelFileOrDir else (baseModelFileOrDir.parentFile ?: return emptyList())
        val listing = dir.listFiles() ?: return emptyList()
        return listing
            .filter { it.isFile && it.name.endsWith(".gguf", ignoreCase = true) }
            .filter { isMmprojFileName(it.name) }
            .sortedBy { it.name.lowercase() }
    }

    fun resolveProjector(baseModelFileOrDir: File, preferredFileName: String? = null): File? {
        val candidates = listColocatedMmprojs(baseModelFileOrDir)
        if (candidates.isEmpty()) return null
        if (!preferredFileName.isNullOrBlank()) {
            candidates.firstOrNull { it.name.equals(preferredFileName, ignoreCase = true) }?.let { return it }
            candidates.firstOrNull { it.name.contains(preferredFileName, ignoreCase = true) }?.let { return it }
        }
        return candidates.first()
    }

    fun findGgufModelFile(dirOrFile: File): File? {
        if (dirOrFile.isFile && dirOrFile.name.endsWith(".gguf", ignoreCase = true) && !isMmprojFileName(dirOrFile.name)) {
            return dirOrFile
        }
        if (dirOrFile.isDirectory) {
            val files = dirOrFile.listFiles() ?: return null
            return files.find { f ->
                f.isFile && f.name.endsWith(".gguf", ignoreCase = true) && !isMmprojFileName(f.name)
            }
        }
        return null
    }

    fun findAnyProjector(modelsDir: File): File? {
        if (!modelsDir.exists()) return null
        return try {
            modelsDir.walkTopDown()
                .maxDepth(4)
                .filter { it.isFile && it.name.endsWith(".gguf", ignoreCase = true) && isMmprojFileName(it.name) }
                .firstOrNull()
        } catch (_: Exception) { null }
    }

    private fun sanitize(name: String): String =
        name.replace(Regex("[^A-Za-z0-9._-]"), "_")
}
