package com.bit.data

import android.content.Context
import android.util.Log
import com.bit.database.dao.MemoryNoteDao
import com.bit.di.AppContainer
import com.bit.engine.EmbeddingEngine
import com.bit.global.AppPaths
import com.bit.models.table_schema.AiMemory
import com.bit.models.table_schema.MemoryCategory
import com.bit.models.table_schema.MemoryNote
import com.bit.worker.GlobalRagOrchestrator
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.sqrt

@Singleton
class AiMemoryWriter @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val vaultFileStore: VaultFileStore,
    private val memoryNoteDao: MemoryNoteDao,
    private val globalRagOrchestrator: GlobalRagOrchestrator,
    private val embeddingEngine: EmbeddingEngine,
    private val embeddingCache: EmbeddingCache
) {
    private val TAG = "AiMemoryWriter"
    private val scope = CoroutineScope(Dispatchers.IO)

    /**
     * Unified memory storage & reconciliation (Mem0-style state machine):
     * 1. Check for duplicates (sim >= 0.88): NOOP new entry, bump timestamp & access count.
     * 2. Check for updates/evolution (0.65 <= sim < 0.88): Update existing memory in-place.
     * 3. Novel fact (< 0.65): Insert new memory into UMS.
     *
     * In all cases:
     * - UMS (memories.ums) is the single source of truth.
     * - NeuronGraph RAG is updated.
     * - Markdown vault file & Room are mirrored for Obsidian-style file browsing.
     */
    suspend fun saveOrUpdateAiMemory(
        text: String,
        title: String? = null,
        category: MemoryCategory = MemoryCategory.GENERAL,
        sourceConversationId: String? = null
    ): AiMemory = withContext(Dispatchers.IO) {
        val cleanText = text.trim()
        val now = System.currentTimeMillis()
        val memoryRepo = runCatching { AppContainer.getMemoryRepo() }.getOrNull()
        val existingMemories = memoryRepo?.getAllOnce().orEmpty()

        // 1. Get embedding for candidate text if engine is ready
        val candidateEmbedding: FloatArray? = try {
            if (embeddingEngine.isInitialized()) {
                embeddingCache.getEmbedding(cleanText) ?: embeddingEngine.embed(cleanText)?.also {
                    embeddingCache.putEmbedding(cleanText, it)
                }
            } else null
        } catch (e: Exception) {
            Log.w(TAG, "Could not generate candidate embedding: ${e.message}")
            null
        }

        // 2. Similarity search against existing memories in UMS
        var bestMatch: AiMemory? = null
        var highestSimilarity = 0f

        if (candidateEmbedding != null) {
            for (existing in existingMemories) {
                val existingEmb = existing.embedding?.toFloatArray() ?: run {
                    try {
                        embeddingCache.getEmbedding(existing.fact) ?: embeddingEngine.embed(existing.fact)
                    } catch (_: Exception) { null }
                }
                if (existingEmb != null) {
                    val sim = cosineSimilarity(candidateEmbedding, existingEmb)
                    if (sim > highestSimilarity) {
                        highestSimilarity = sim
                        bestMatch = existing
                    }
                }
            }
        } else {
            // Lexical fallback
            val lowerClean = cleanText.lowercase()
            bestMatch = existingMemories.firstOrNull {
                it.fact.lowercase() == lowerClean ||
                it.fact.lowercase().contains(lowerClean) ||
                lowerClean.contains(it.fact.lowercase())
            }
            if (bestMatch != null) highestSimilarity = 0.90f
        }

        val memoryToPersist: AiMemory
        when {
            // High similarity (>= 0.88) -> Duplicate fact: touch metadata, NOOP new entry
            highestSimilarity >= 0.88f && bestMatch != null -> {
                Log.d(TAG, "Duplicate memory detected (sim: $highestSimilarity). Touching timestamp.")
                memoryToPersist = bestMatch.copy(
                    updatedAt = now,
                    lastAccessedAt = now,
                    accessCount = bestMatch.accessCount + 1
                )
                memoryRepo?.update(memoryToPersist)
            }

            // Moderate similarity (0.65 - 0.88) -> Update existing fact in-place
            highestSimilarity >= 0.65f && bestMatch != null -> {
                Log.d(TAG, "Fact evolution detected for existing memory '${bestMatch.id}'. Updating in-place.")
                memoryToPersist = bestMatch.copy(
                    fact = cleanText,
                    category = category,
                    updatedAt = now,
                    lastAccessedAt = now,
                    embedding = candidateEmbedding?.toByteArray() ?: bestMatch.embedding
                )
                memoryRepo?.update(memoryToPersist)
            }

            // Novel fact (< 0.65) -> Insert new entry
            else -> {
                val newId = UUID.randomUUID().toString()
                memoryToPersist = AiMemory(
                    id = newId,
                    fact = cleanText,
                    category = category,
                    sourceChatId = sourceConversationId,
                    createdAt = now,
                    updatedAt = now,
                    lastAccessedAt = now,
                    accessCount = 1,
                    embedding = candidateEmbedding?.toByteArray()
                )
                memoryRepo?.insert(memoryToPersist)
            }
        }

        // 3. Immediately re-index into NeuronGraph RAG
        try {
            globalRagOrchestrator.reloadMemoryIntoGraph(memoryToPersist)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to reload memory into NeuronGraph", e)
        }

        // 4. Mirror to VaultFileStore & Room memory_notes
        try {
            val dateStamp = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(now))
            val noteTitle = title ?: if (cleanText.length > 40) cleanText.take(40).trim() + "…" else cleanText.trim()
            val safeSlug = noteTitle
                .replace(Regex("[^a-zA-Z0-9_\\- ]"), "")
                .replace(Regex("\\s+"), "-")
                .lowercase()
                .take(40)
                .ifBlank { "ai-fact" }
            val fileName = "$dateStamp-$safeSlug.md"
            val targetFile = File(AppPaths.aiMemoriesVault(context), fileName)

            val note = MemoryNote(
                id = memoryToPersist.id,
                title = noteTitle.ifBlank { "AI saved memory" },
                content = cleanText,
                tags = "ai_memory, ${category.name.lowercase()}",
                noteType = "fact",
                folder = "ai_memory",
                filePath = targetFile.absolutePath,
                isAiMemoryEnabled = true,
                createdAt = memoryToPersist.createdAt,
                updatedAt = memoryToPersist.updatedAt
            )
            val written = vaultFileStore.writeNote(note)
            memoryNoteDao.insertNote(written)
        } catch (e: Exception) {
            Log.e(TAG, "Error mirroring memory to file vault", e)
        }

        Log.d(TAG, "Successfully persisted memory: id=${memoryToPersist.id}, fact='${memoryToPersist.fact.take(40)}'")
        memoryToPersist
    }

    /**
     * Explicit memory update by ID.
     */
    suspend fun updateMemory(
        id: String,
        newFact: String,
        category: MemoryCategory = MemoryCategory.GENERAL
    ): Boolean = withContext(Dispatchers.IO) {
        val memoryRepo = runCatching { AppContainer.getMemoryRepo() }.getOrNull() ?: return@withContext false
        val existing = memoryRepo.getById(id) ?: return@withContext false
        val cleanText = newFact.trim()
        val now = System.currentTimeMillis()

        val newEmbedding = try {
            if (embeddingEngine.isInitialized()) {
                embeddingEngine.embed(cleanText)
            } else null
        } catch (_: Exception) { null }

        val updated = existing.copy(
            fact = cleanText,
            category = category,
            updatedAt = now,
            embedding = newEmbedding?.toByteArray() ?: existing.embedding
        )

        memoryRepo.update(updated)
        globalRagOrchestrator.reloadMemoryIntoGraph(updated)

        // Mirror update to file & Room
        val existingNote = memoryNoteDao.getNoteById(id)
        if (existingNote != null) {
            val updatedNote = existingNote.copy(
                content = cleanText,
                updatedAt = now
            )
            val written = vaultFileStore.writeNote(updatedNote)
            memoryNoteDao.insertNote(written)
        }
        true
    }

    /**
     * Explicit memory deletion by ID.
     */
    suspend fun deleteMemory(id: String): Boolean = withContext(Dispatchers.IO) {
        val memoryRepo = runCatching { AppContainer.getMemoryRepo() }.getOrNull()
        memoryRepo?.deleteById(id)
        globalRagOrchestrator.removeMemoryFromGraph(id)

        // Mirror deletion to file & Room
        val note = memoryNoteDao.getNoteById(id)
        if (note != null) {
            if (note.filePath.isNotBlank()) {
                val f = File(note.filePath)
                if (f.exists()) f.delete()
            }
            memoryNoteDao.deleteNoteById(id)
        }
        true
    }

    /**
     * Legacy adapter for existing callers (ChatViewModel, MemoryViewModel, MemoryVaultViewModel).
     */
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
            try {
                // Sync to UMS as Single Source of Truth
                saveOrUpdateAiMemory(text = text, title = title, sourceConversationId = sourceConversationId)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to sync to UMS", e)
            }
        }
        return written
    }

    fun importMemory(text: String, category: String, parsedDate: Long): MemoryNote {
        val catEnum = when (category.lowercase()) {
            "personal" -> MemoryCategory.PERSONAL
            "preference" -> MemoryCategory.PREFERENCE
            "work" -> MemoryCategory.WORK
            "interest" -> MemoryCategory.INTEREST
            else -> MemoryCategory.GENERAL
        }
        val now = System.currentTimeMillis()
        val noteTitle = if (text.length > 40) text.take(40).trim() + "…" else text.trim()

        val safeSlug = noteTitle
            .replace(Regex("[^a-zA-Z0-9_\\- ]"), "")
            .replace(Regex("\\s+"), "-")
            .lowercase()
            .take(40)
            .ifBlank { "ai-fact" }

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
            try {
                saveOrUpdateAiMemory(text = text, title = noteTitle, category = catEnum)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to sync imported memory to UMS", e)
            }
        }
        return written
    }

    fun isExplicitRememberCommand(text: String): Boolean {
        val lower = text.lowercase()
        return lower.contains("remember that") ||
               lower.contains("remember this") ||
               lower.contains("don't forget that") ||
               lower.contains("don't forget") ||
               lower.contains("save to memory") ||
               lower.contains("note that") ||
               lower.contains("keep in mind")
    }

    private fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
        if (a.size != b.size || a.isEmpty()) return 0f
        var dot = 0f
        var normA = 0f
        var normB = 0f
        for (i in a.indices) {
            dot += a[i] * b[i]
            normA += a[i] * a[i]
            normB += b[i] * b[i]
        }
        val denom = sqrt(normA) * sqrt(normB)
        return if (denom > 0f) dot / denom else 0f
    }

    private fun FloatArray.toByteArray(): ByteArray {
        val bb = ByteBuffer.allocate(this.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        for (f in this) bb.putFloat(f)
        return bb.array()
    }

    private fun ByteArray.toFloatArray(): FloatArray {
        val bb = ByteBuffer.wrap(this).order(ByteOrder.LITTLE_ENDIAN)
        val fa = FloatArray(this.size / 4)
        for (i in fa.indices) fa[i] = bb.float
        return fa
    }
}
