package com.bit.data

import android.content.Context
import android.util.Log
import com.bit.global.AppPaths
import com.bit.models.table_schema.MemoryNote
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class VaultFileStore @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val TAG = "VaultFileStore"

    fun getVaultRoot(): File = AppPaths.vaultRoot(context)

    fun getFolderForType(folder: String): File {
        return when (folder) {
            "ai_memory" -> AppPaths.aiMemoriesVault(context)
            "tasks" -> AppPaths.tasksVault(context)
            "documents" -> AppPaths.documentsVault(context)
            else -> AppPaths.notesVault(context)
        }
    }

    @Synchronized
    fun readNote(file: File): MemoryNote? {
        if (!file.exists() || !file.isFile || file.extension.lowercase() != "md" || file.name.startsWith(".")) return null
        return try {
            val raw = String(file.readBytes(), Charsets.UTF_8)
            val (frontmatter, body) = splitFrontmatter(raw)
            val metaMap = parseFrontmatterYaml(frontmatter)

            val folderName = when {
                file.parentFile?.name == "ai_memory" -> "ai_memory"
                file.parentFile?.name == "tasks" -> "tasks"
                file.parentFile?.name == "documents" -> "documents"
                else -> metaMap["folder"] ?: "notes"
            }

            MemoryNote(
                id = metaMap["id"] ?: file.nameWithoutExtension,
                title = metaMap["title"] ?: file.nameWithoutExtension.replace("-", " ").capitalizeWords(),
                content = body,
                tags = metaMap["tags"] ?: "",
                noteType = metaMap["type"] ?: "note",
                folder = folderName,
                status = metaMap["status"]?.takeIf { it != "null" } ?: "todo",
                dueDate = metaMap["due_date"]?.takeIf { it != "null" }?.toLongOrNull(),
                completedAt = metaMap["completed_at"]?.takeIf { it != "null" }?.toLongOrNull(),
                filePath = file.absolutePath,
                conflictWithFactId = metaMap["conflict_with_fact_id"]?.takeIf { it != "null" },
                lastBackedUpAt = metaMap["last_backed_up_at"]?.takeIf { it != "null" }?.toLongOrNull() ?: 0L,
                isPinned = metaMap["is_pinned"]?.toBooleanStrictOrNull() ?: false,
                accessCount = metaMap["access_count"]?.toIntOrNull() ?: 1,
                isAiMemoryEnabled = metaMap["is_ai_memory_enabled"]?.toBooleanStrictOrNull() ?: true,
                createdAt = metaMap["created_at"]?.toLongOrNull() ?: file.lastModified(),
                updatedAt = metaMap["updated_at"]?.toLongOrNull() ?: file.lastModified()
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error reading note file: ${file.absolutePath}", e)
            null
        }
    }

    /**
     * Write a note to disk. Returns the written file AND the note with filePath populated,
     * so the caller can track the file for future edits without creating duplicates.
     */
    @Synchronized
    fun writeNote(note: MemoryNote): MemoryNote {
        val folderDir = getFolderForType(note.folder)
        val safeSlug = note.title.trim()
            .replace(Regex("[^a-zA-Z0-9_\\- ]"), "")
            .replace(Regex("\\s+"), "-")
            .lowercase()
            .take(60)
            .ifBlank { "untitled-note" }

        // Find any pre-existing files for this ID on disk
        val existingFiles = findAllFilesById(note.id)

        val targetFile = when {
            note.filePath.isNotBlank() && File(note.filePath).parentFile?.exists() == true -> File(note.filePath)
            existingFiles.isNotEmpty() -> existingFiles.first()
            else -> dedupeFileName(folderDir, safeSlug)
        }

        // Clean up any extra orphan files matching this ID (e.g. created during rapid typing/autosave)
        existingFiles.filter { it.absolutePath != targetFile.absolutePath }.forEach { orphan ->
            try { if (orphan.exists()) orphan.delete() } catch (_: Exception) {}
        }

        val yamlFrontmatter = buildString {
            appendLine("---")
            appendLine("id: ${note.id}")
            appendLine("title: ${note.title.replace("\n", " ")}")
            appendLine("type: ${note.noteType}")
            appendLine("folder: ${note.folder}")
            appendLine("tags: ${note.tags}")
            appendLine("status: ${note.status}")
            appendLine("due_date: ${note.dueDate ?: "null"}")
            appendLine("completed_at: ${note.completedAt ?: "null"}")
            appendLine("conflict_with_fact_id: ${note.conflictWithFactId ?: "null"}")
            appendLine("last_backed_up_at: ${note.lastBackedUpAt}")
            appendLine("is_pinned: ${note.isPinned}")
            appendLine("access_count: ${note.accessCount}")
            appendLine("is_ai_memory_enabled: ${note.isAiMemoryEnabled}")
            appendLine("created_at: ${note.createdAt}")
            appendLine("updated_at: ${note.updatedAt}")
            append("---")
        }

        val fullContent = "$yamlFrontmatter\n\n${note.content}"
        targetFile.parentFile?.mkdirs()
        targetFile.writeText(fullContent)
        Log.d(TAG, "Wrote note '${note.title}' to ${targetFile.absolutePath}")

        return note.copy(filePath = targetFile.absolutePath)
    }

    /** Find all .md files sharing a matching id in frontmatter */
    fun findAllFilesById(id: String): List<File> {
        val root = getVaultRoot()
        if (!root.exists()) return emptyList()
        return root.walkTopDown()
            .filter { it.isFile && it.extension.lowercase() == "md" && !it.name.startsWith(".") }
            .filter { file ->
                try {
                    val firstLines = file.bufferedReader().use { reader ->
                        val lines = mutableListOf<String>()
                        var line = reader.readLine()
                        if (line?.trim() == "---") {
                            line = reader.readLine()
                            while (line != null && line.trim() != "---") {
                                lines.add(line)
                                line = reader.readLine()
                            }
                        }
                        lines
                    }
                    firstLines.any { it.trim().startsWith("id:") && it.substringAfter("id:").trim() == id }
                } catch (_: Exception) { false }
            }
            .toList()
    }

    /** Find an existing .md file by scanning frontmatter for matching id */
    private fun findFileById(id: String): File? = findAllFilesById(id).firstOrNull()

    /** Deduplicate file names: my-note.md, my-note-2.md, my-note-3.md */
    private fun dedupeFileName(dir: File, slug: String): File {
        var candidate = File(dir, "$slug.md")
        var counter = 2
        while (candidate.exists()) {
            candidate = File(dir, "$slug-$counter.md")
            counter++
        }
        return candidate
    }

    @Synchronized
    fun deleteNote(note: MemoryNote) {
        val targets = mutableSetOf<File>()
        if (note.filePath.isNotBlank()) {
            targets.add(File(note.filePath))
        }
        targets.addAll(findAllFilesById(note.id))

        targets.forEach { file ->
            try {
                if (file.exists()) {
                    file.delete()
                    Log.d(TAG, "Deleted vault note file: ${file.absolutePath}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to delete vault file ${file.absolutePath}", e)
            }
        }
    }

    @Synchronized
    fun listAllNotes(): List<MemoryNote> {
        val root = getVaultRoot()
        if (!root.exists()) return emptyList()
        val noteFiles = root.walkTopDown()
            .filter { it.isFile && it.extension.lowercase() == "md" && !it.name.startsWith(".") }
            .toList()

        val parsedNotes = noteFiles.mapNotNull { readNote(it) }

        // Deduplicate by note ID — keep newest per ID and auto-delete orphan duplicate files
        val uniqueNotes = mutableMapOf<String, MemoryNote>()
        val orphans = mutableListOf<File>()

        for (note in parsedNotes.sortedByDescending { it.updatedAt }) {
            if (!uniqueNotes.containsKey(note.id)) {
                uniqueNotes[note.id] = note
            } else {
                if (note.filePath.isNotBlank()) {
                    orphans.add(File(note.filePath))
                }
            }
        }

        // Clean up orphan duplicate files in background
        orphans.forEach { file ->
            try { if (file.exists()) file.delete() } catch (_: Exception) {}
        }

        return uniqueNotes.values.sortedByDescending { it.updatedAt }
    }

    private fun splitFrontmatter(raw: String): Pair<String, String> {
        if (!raw.startsWith("---")) return "" to raw
        val endIdx = raw.indexOf("---", 3)
        if (endIdx < 0) return "" to raw
        return raw.substring(3, endIdx).trim() to raw.substring(endIdx + 3).trimStart('\n')
    }

    private fun parseFrontmatterYaml(yaml: String): Map<String, String> {
        val map = mutableMapOf<String, String>()
        yaml.lines().forEach { line ->
            val colonIdx = line.indexOf(':')
            if (colonIdx > 0) {
                val key = line.substring(0, colonIdx).trim()
                val value = line.substring(colonIdx + 1).trim()
                if (value != "null") {
                    map[key] = value
                }
            }
        }
        return map
    }

    private fun String.capitalizeWords(): String = split(" ")
        .joinToString(" ") { it.replaceFirstChar { char -> char.uppercase() } }
}
