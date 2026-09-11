package com.bit.worker

import android.content.Context
import android.net.Uri
import android.util.Log
import com.bit.data.EmbeddingCache
import com.bit.data.VaultFileStore
import com.bit.database.dao.MemoryNoteDao
import com.bit.engine.EmbeddingEngine
import com.bit.models.table_schema.AiMemory
import com.bit.models.table_schema.MemoryNote
import com.bit.di.AppContainer
import dagger.hilt.android.qualifiers.ApplicationContext
import com.bit.neuron_example.NeuronGraph
import com.bit.neuron_example.RetrievalResult
import com.bit.util.DocumentParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class GlobalRagOrchestrator @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val embeddingEngine: EmbeddingEngine,
    private val embeddingCache: EmbeddingCache,
    private val vaultStore: VaultFileStore,
    private val memoryNoteDao: MemoryNoteDao
) {
    private val TAG = "GlobalRagOrchestrator"

    private var globalGraph: NeuronGraph? = null
    private val graphMutex = Mutex()
    private val scope = CoroutineScope(Dispatchers.IO)

    private val _isProcessing = MutableStateFlow(false)
    val isProcessing: StateFlow<Boolean> = _isProcessing.asStateFlow()

    private val _graphNodes = MutableStateFlow<List<com.bit.neuron_example.NeuronNode>>(emptyList())
    val graphNodes: StateFlow<List<com.bit.neuron_example.NeuronNode>> = _graphNodes.asStateFlow()

    private val _graphStats = MutableStateFlow<com.bit.neuron_example.GraphStats?>(null)
    val graphStats: StateFlow<com.bit.neuron_example.GraphStats?> = _graphStats.asStateFlow()

    private val _isGraphReady = MutableStateFlow(false)
    val isGraphReady: StateFlow<Boolean> = _isGraphReady.asStateFlow()

    private fun updateGraphState(graph: NeuronGraph) {
        _graphNodes.value = graph.getAllNodes()
        _graphStats.value = graph.getStats()
        _isGraphReady.value = true
    }

    suspend fun getGraph(): NeuronGraph = getOrInitGraph()

    init {
        // Initialize the global graph asynchronously on startup if possible
        scope.launch {
            try {
                getOrInitGraph()
            } catch (e: Exception) {
                Log.w(TAG, "Initial global graph load deferred: ${e.message}")
            }
        }
    }

    private suspend fun getOrInitGraph(): NeuronGraph = graphMutex.withLock {
        globalGraph?.let { return it }
        _isProcessing.value = true
        try {
            embeddingEngine.ensureInitialized(context)
            val graph = NeuronGraph(embeddingEngine = embeddingEngine, embeddingCache = embeddingCache)
            val notes = vaultStore.listAllNotes()
            for (note in notes) {
                graph.addText(note.content, note.title, note.id)
            }
            // Also index all memories from UMS (Single Source of Truth)
            val memoryRepo = runCatching { AppContainer.getMemoryRepo() }.getOrNull()
            val memories = memoryRepo?.getAllOnce().orEmpty()
            for (mem in memories) {
                graph.addText(mem.fact, "Memory (${mem.category.name})", mem.id)
            }
            globalGraph = graph
            updateGraphState(graph)
            Log.d(TAG, "Global graph initialized with ${notes.size} notes and ${memories.size} UMS memories.")
            graph
        } finally {
            _isProcessing.value = false
        }
    }

    /**
     * Call this when an AI memory is edited or created to keep the graph in sync.
     */
    suspend fun reloadMemoryIntoGraph(memory: AiMemory) {
        try {
            val graph = getOrInitGraph()
            embeddingEngine.ensureInitialized(context)
            graph.removeSource(memory.id)
            graph.addText(memory.fact, "Memory (${memory.category.name})", memory.id)
            updateGraphState(graph)
            Log.d(TAG, "Reloaded memory ${memory.id} into graph (total nodes: ${graph.getStats().nodeCount})")
        } catch (e: Exception) {
            Log.e(TAG, "Error reloading memory into graph: ${memory.id}", e)
        }
    }

    /**
     * Call this when an AI memory is deleted to evict it from the graph.
     */
    suspend fun removeMemoryFromGraph(memoryId: String) {
        removeNoteFromGraph(memoryId)
    }

    /**
     * Call this when a note is edited or created to keep the graph in sync.
     * Purges previous chunks for note.id first to prevent duplicate bloat.
     */
    suspend fun reloadNoteIntoGraph(note: MemoryNote) {
        try {
            val graph = getOrInitGraph()
            embeddingEngine.ensureInitialized(context)
            graph.removeSource(note.id)
            graph.addText(note.content, note.title, note.id)
            updateGraphState(graph)
        } catch (e: Exception) {
            Log.e(TAG, "Error reloading note into graph", e)
        }
    }

    /**
     * Call this when a note is deleted to evict its nodes and edges from the graph.
     */
    suspend fun removeNoteFromGraph(noteId: String) {
        try {
            val graph = getOrInitGraph()
            graph.removeSource(noteId)
            updateGraphState(graph)
        } catch (e: Exception) {
            Log.e(TAG, "Error removing note from graph", e)
        }
    }

    suspend fun deleteDocumentNote(docId: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val note = memoryNoteDao.getNoteById(docId)
            if (note?.filePath?.isNotBlank() == true) {
                val f = File(note.filePath)
                if (f.exists()) f.delete()
            }
            // Clean up original PDF binary if present
            try {
                val pdfFile = File(com.bit.global.AppPaths.documentsVault(context), "$docId.pdf")
                if (pdfFile.exists()) pdfFile.delete()
            } catch (_: Exception) {}
            memoryNoteDao.deleteNoteById(docId)
            removeNoteFromGraph(docId)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete document $docId", e)
            false
        }
    }

    suspend fun toggleDocumentEnabled(docId: String, isEnabled: Boolean) = withContext(Dispatchers.IO) {
        try {
            memoryNoteDao.updateAiMemoryEnabled(docId, isEnabled)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to toggle enabled state for $docId", e)
        }
    }

    suspend fun attachDocumentContent(
        name: String,
        content: String,
        sourceUri: Uri? = null
    ): Result<MemoryNote> = withContext(Dispatchers.IO) {
        _isProcessing.value = true
        try {
            if (content.isBlank()) {
                return@withContext Result.failure(Exception("Extracted document text is empty"))
            }

            // Check if document with same title already exists to prevent duplicate files and ghost notes
            val existing = try {
                memoryNoteDao.getAllNotesOnce().find {
                    (it.noteType == "document" || it.folder == "documents") &&
                    (it.title.equals(name, ignoreCase = true) ||
                     it.title.equals(name.substringBeforeLast("."), ignoreCase = true))
                }
            } catch (_: Exception) { null }

            val docId = existing?.id ?: java.util.UUID.randomUUID().toString()

            // If the source is a PDF, persist the original binary so the user can preview the real PDF
            val isPdf = name.endsWith(".pdf", ignoreCase = true) ||
                (sourceUri != null && context.contentResolver.getType(sourceUri)?.contains("pdf", ignoreCase = true) == true)
            if (isPdf && sourceUri != null) {
                try {
                    val pdfFile = File(com.bit.global.AppPaths.documentsVault(context), "$docId.pdf")
                    context.contentResolver.openInputStream(sourceUri)?.use { input ->
                        pdfFile.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                    Log.d(TAG, "Persisted original PDF binary: ${pdfFile.absolutePath} (${pdfFile.length()} bytes)")
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to persist raw PDF binary: ${e.message}")
                }
            }

            val docNote = com.bit.models.table_schema.MemoryNote(
                id = docId,
                title = name,
                content = content,
                noteType = "document",
                folder = "documents",
                filePath = existing?.filePath ?: "",
                isAiMemoryEnabled = true,
                createdAt = existing?.createdAt ?: System.currentTimeMillis(),
                updatedAt = System.currentTimeMillis()
            )

            // Write full document to disk in vault
            val writtenNote = vaultStore.writeNote(docNote)

            // Save to Room DB: cap stored content at 50,000 chars to avoid SQLite 2MB CursorWindow crashes
            val noteForDb = if (writtenNote.content.length > 50_000) {
                writtenNote.copy(content = writtenNote.content.take(50_000) + "\n\n...[Full document stored in vault on disk]...")
            } else {
                writtenNote
            }
            try {
                memoryNoteDao.insertNote(noteForDb)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to insert document note into Room DB: ${e.message}")
            }

            // Index into RAG NeuronGraph
            val graph = getOrInitGraph()
            runCatching { embeddingEngine.ensureInitialized(context) }

            if (existing != null) {
                try { graph.removeSource(docId) } catch (_: Exception) {}
            }

            val addResult = graph.addText(text = content, sourceName = name, sourceId = docId)
            updateGraphState(graph)
            if (addResult.isFailure) {
                Log.w(TAG, "Document saved to vault, but graph indexing returned: ${addResult.exceptionOrNull()?.message}")
            } else {
                Log.d(TAG, "Document '$name' successfully attached with ${addResult.getOrNull()?.size ?: 0} chunks.")
            }

            Result.success(writtenNote)
        } catch (e: Exception) {
            Log.e(TAG, "Error attaching document content", e)
            Result.failure(e)
        } finally {
            _isProcessing.value = false
        }
    }

    suspend fun attachDocument(uri: Uri): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val fileName = com.bit.util.DocumentParser.getFileName(context, uri).ifBlank { "Attached Document" }
            val mimeType = context.contentResolver.getType(uri)
            val parseResult = com.bit.util.DocumentParser.parseDocument(uri, context, mimeType)

            if (parseResult.isFailure) {
                return@withContext Result.failure(parseResult.exceptionOrNull() ?: Exception("Failed to parse document"))
            }

            val content = parseResult.getOrThrow()
            attachDocumentContent(fileName, content, sourceUri = uri).map { Unit }
        } catch (e: Exception) {
            Log.e(TAG, "Error attaching document from URI", e)
            Result.failure(e)
        }
    }

    /**
     * Purge corrupted media/binary files that were incorrectly saved as notes.
     * Prevents OOMs and UI freezes from decoding binary media as text.
     */
    suspend fun cleanupCorruptedVaultNotes(): Int = withContext(Dispatchers.IO) {
        var deletedCount = 0
        try {
            val notes = vaultStore.listAllNotes()
            val dao = com.bit.database.AppDatabase.getDatabase(context).memoryNoteDao()
            val blockedExts = setOf("mp4", "mkv", "avi", "mov", "png", "jpg", "jpeg", "webp", "gif", "mp3", "wav", "zip", "apk", "bin")

            for (note in notes) {
                val hasBlockedExt = blockedExts.any { note.title.endsWith(".$it", ignoreCase = true) || (note.filePath.endsWith(".$it.md", ignoreCase = true)) }
                val hasCorruptChars = note.content.count { it == '\uFFFD' || it == '\u0000' } > 50
                val isSuspectBinary = note.content.length > 5000 && (note.content.count { it == '\uFFFD' }.toFloat() / note.content.length) > 0.02f

                if (hasBlockedExt || hasCorruptChars || isSuspectBinary) {
                    Log.w(TAG, "Cleaning up corrupted binary vault note: ${note.title} (${note.filePath})")
                    try {
                        if (note.filePath.isNotBlank()) {
                            val f = java.io.File(note.filePath)
                            if (f.exists()) f.delete()
                        }
                        dao.deleteNoteById(note.id)
                        deletedCount++
                    } catch (e: Exception) {
                        Log.e(TAG, "Error deleting corrupted note ${note.id}", e)
                    }
                }
            }
            if (deletedCount > 0) {
                Log.i(TAG, "Successfully purged $deletedCount corrupted media/binary notes from vault.")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error during vault cleanup", e)
        }
        deletedCount
    }

    /**
     * Rebuild the in-memory graph from the vault files on disk.
     * Used after bulk operations that write .md files directly (e.g. backup restore)
     * so the graph never serves stale or empty knowledge.
     */
    suspend fun rebuildFromDisk() {
        try {
            cleanupCorruptedVaultNotes()
            val graph = getOrInitGraph()
            runCatching { embeddingEngine.ensureInitialized(context) }
            val notes = vaultStore.listAllNotes()
            graph.clear()
            for (note in notes) {
                if (note.content.count { it == '\uFFFD' || it == '\u0000' } < 20) {
                    graph.addText(note.content, note.title, note.id)
                }
            }
            updateGraphState(graph)
            Log.d(TAG, "Rebuilt global graph from disk with ${notes.size} notes.")
        } catch (e: Exception) {
            Log.e(TAG, "Error rebuilding global graph from disk", e)
        }
    }

    suspend fun queryGlobalKnowledge(query: String, topK: Int = 5): RetrievalResult? = withContext(Dispatchers.IO) {
        try {
            val graph = getOrInitGraph()
            runCatching { embeddingEngine.ensureInitialized(context) }
            val rawResult = graph.queryWithPipeline(query, topK * 2)
            val enabledNotes = runCatching { memoryNoteDao.getAiEnabledNotesOnce().map { it.id }.toSet() }.getOrNull()
            if (enabledNotes != null && rawResult != null) {
                val filteredResults = rawResult.results.filter { result ->
                    val sourceId = result.node.metadata.sourceId
                    sourceId.isBlank() || sourceId in enabledNotes
                }.take(topK)
                val context = filteredResults.joinToString("\n\n") { it.node.content }
                RetrievalResult(filteredResults, rawResult.confidence, context)
            } else {
                rawResult
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error querying global knowledge graph", e)
            null
        }
    }
}
