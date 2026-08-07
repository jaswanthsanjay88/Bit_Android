package com.bit.data

import android.content.Context
import android.util.Log
import com.bit.global.AppPaths
import com.bit.models.table_schema.MemoryNote
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

import com.bit.database.dao.MemoryNoteDao
import com.bit.worker.GlobalRagOrchestrator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@Singleton
class AiMemoryWriter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val vaultFileStore: VaultFileStore,
    private val memoryNoteDao: MemoryNoteDao,
    private val globalRagOrchestrator: GlobalRagOrchestrator
) {
    private val TAG = "AiMemoryWriter"
    private val scope = CoroutineScope(Dispatchers.IO)

    fun saveAiMemory(text: String, sourceConversationId: String? = null, title: String? = null): MemoryNote {
        val now = System.currentTimeMillis()
        val dateStamp = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(now))
        val noteTitle = title ?: if (text.length > 40) text.take(40).trim() + "…" else text.trim()

        val safeSlug = noteTitle
            .replace(Regex("[^a-zA-Z0-9_\\- ]"), "")
            .replace(Regex("\\s+"), "-")
            .lowercase()
            .take(40)
            .ifBlank { "ai-fact" }
        val fileName = "$dateStamp-$safeSlug.md"
        val targetFile = File(AppPaths.aiMemoriesVault(context), fileName)

        val note = MemoryNote(
            id = UUID.randomUUID().toString(),
            title = noteTitle.ifBlank { "AI saved memory" },
            content = text,
            tags = "ai_memory",
            noteType = "fact",
            folder = "ai_memory",
            filePath = targetFile.absolutePath,
            isAiMemoryEnabled = true,
            createdAt = now,
            updatedAt = now
        )

        val written = vaultFileStore.writeNote(note)
        scope.launch {
            try { memoryNoteDao.insertNote(written) } catch (e: Exception) { Log.e(TAG, "Failed to insert memory into Room", e) }
            try { globalRagOrchestrator.reloadNoteIntoGraph(written) } catch (e: Exception) { Log.e(TAG, "Failed to reload memory into graph", e) }
        }
        Log.d(TAG, "Saved AI memory to disk: ${written.filePath}")
        return written
    }

    fun importMemory(text: String, category: String, parsedDate: Long): MemoryNote {
        val now = System.currentTimeMillis()
        val noteTitle = if (text.length > 40) text.take(40).trim() + "…" else text.trim()

        val safeSlug = noteTitle
            .replace(Regex("[^a-zA-Z0-9_\\- ]"), "")
            .replace(Regex("\\s+"), "-")
            .lowercase()
            .take(40)
            .ifBlank { "ai-fact" }

        // Give imported notes a random suffix to avoid file name collisions
        val randomSuffix = UUID.randomUUID().toString().take(6)
        val fileName = "imported-$category-$safeSlug-$randomSuffix.md"
        val targetFile = File(AppPaths.aiMemoriesVault(context), fileName)

        val note = MemoryNote(
            id = UUID.randomUUID().toString(),
            title = noteTitle.ifBlank { "Imported memory" },
            content = text,
            tags = "ai_memory, $category",
            noteType = "fact",
            folder = "ai_memory",
            filePath = targetFile.absolutePath,
            isAiMemoryEnabled = true,
            createdAt = parsedDate,
            updatedAt = now
        )

        val written = vaultFileStore.writeNote(note)
        scope.launch {
            try { memoryNoteDao.insertNote(written) } catch (e: Exception) { Log.e(TAG, "Failed to insert imported memory into Room", e) }
            try { globalRagOrchestrator.reloadNoteIntoGraph(written) } catch (e: Exception) { Log.e(TAG, "Failed to reload imported memory into graph", e) }
        }
        Log.d(TAG, "Imported AI memory to disk: ${written.filePath}")
        return written
    }

    fun isExplicitRememberCommand(text: String): Boolean {
        val lower = text.lowercase()
        return lower.contains("remember that") ||
               lower.contains("remember this") ||
               lower.contains("don't forget") ||
               lower.contains("save to memory") ||
               lower.contains("note that") ||
               lower.contains("keep in mind")
    }
}
